package dev.veil.launcher.data

import android.content.Context
import dev.veil.launcher.BuildConfig
import org.json.JSONObject
import java.io.IOException

/** Trakt client using the TV-friendly device-code sign in. Tokens stay in app-private storage. */
class Trakt(context: Context) {
    private val prefs = context.getSharedPreferences("trakt", Context.MODE_PRIVATE)
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val secret = BuildConfig.TRAKT_CLIENT_SECRET

    /** Only the client ID is required. Newer Trakt apps (PKCE, since Oct 2026) don't get a secret. */
    val hasKeys: Boolean get() = clientId.isNotBlank()

    /** Adds client_secret only for older Trakt apps that have one; secret-less apps must omit it. */
    private fun JSONObject.withSecret(): JSONObject = apply { if (secret.isNotBlank()) put("client_secret", secret) }
    val isConnected: Boolean get() = prefs.getString("access", null) != null

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val url: String,
        val interval: Int,
        val expiresIn: Int,
    )

    enum class Poll { AUTHORIZED, PENDING, SLOW_DOWN, FAILED }

    fun requestDeviceCode(): DeviceCode {
        val (code, text) = Http.call(
            "POST", "$API/oauth/device/code",
            body = JSONObject().put("client_id", clientId).toString(),
        )
        if (code != 200) throw IOException("Trakt returned $code")
        val j = JSONObject(text)
        return DeviceCode(
            j.getString("device_code"), j.getString("user_code"),
            j.optString("verification_url", "https://trakt.tv/activate"),
            j.optInt("interval", 5), j.optInt("expires_in", 600),
        )
    }

    fun pollDeviceToken(dc: DeviceCode): Poll {
        val (code, text) = Http.call(
            "POST", "$API/oauth/device/token",
            body = JSONObject()
                .put("code", dc.deviceCode)
                .put("client_id", clientId)
                .withSecret().toString(),
        )
        return when (code) {
            200 -> { saveTokens(JSONObject(text)); Poll.AUTHORIZED }
            400 -> Poll.PENDING
            429 -> Poll.SLOW_DOWN
            else -> Poll.FAILED
        }
    }

    fun disconnect() = prefs.edit().clear().apply()

    private fun saveTokens(j: JSONObject) {
        val created = j.optLong("created_at", System.currentTimeMillis() / 1000)
        val expiresIn = j.optLong("expires_in", 86_400)
        prefs.edit()
            .putString("access", j.getString("access_token"))
            .putString("refresh", j.getString("refresh_token"))
            .putLong("expires", (created + expiresIn) * 1000)
            .apply()
    }

    @Synchronized
    private fun refresh(): Boolean {
        val refresh = prefs.getString("refresh", null) ?: return false
        val (code, text) = Http.call(
            "POST", "$API/oauth/token",
            body = JSONObject()
                .put("refresh_token", refresh)
                .put("client_id", clientId)
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                .put("grant_type", "refresh_token")
                .withSecret().toString(),
        )
        if (code == 200) { saveTokens(JSONObject(text)); return true }
        if (code == 400 || code == 401) disconnect() // refresh token revoked or expired
        return false
    }

    private fun headers() = mapOf(
        "trakt-api-version" to "2",
        "trakt-api-key" to clientId,
        "Authorization" to "Bearer ${prefs.getString("access", "")}",
    )

    fun get(path: String): String {
        if (System.currentTimeMillis() > prefs.getLong("expires", 0) - 60_000) refresh()
        var (code, text) = Http.call("GET", API + path, headers())
        if (code == 401 && refresh()) {
            val retry = Http.call("GET", API + path, headers())
            code = retry.first; text = retry.second
        }
        if (code !in 200..299) throw IOException("Trakt returned $code")
        return text
    }

    /** POST/DELETE with the same token refresh as [get]. Returns the HTTP status code. */
    fun send(method: String, path: String, body: JSONObject? = null): Int {
        if (System.currentTimeMillis() > prefs.getLong("expires", 0) - 60_000) refresh()
        var code = Http.call(method, API + path, headers(), body?.toString()).first
        if (code == 401 && refresh()) code = Http.call(method, API + path, headers(), body?.toString()).first
        return code
    }

    // ---- Writes used by the Continue Watching hold menu ----

    /** Adds the episode/movie to your Trakt watch history (watched now) and clears its paused progress. */
    fun markWatched(item: WatchItem): Boolean {
        val now = java.time.Instant.now().toString() // watched right now
        val body = if (item.isMovie) {
            val id = item.traktId ?: return false
            JSONObject().put("movies", org.json.JSONArray().put(
                JSONObject().put("watched_at", now).put("ids", JSONObject().put("trakt", id)),
            ))
        } else {
            val show = item.traktId ?: return false
            JSONObject().put(
                "shows", org.json.JSONArray().put(
                    JSONObject().put("watched_at", now).put("ids", JSONObject().put("trakt", show)).put(
                        "seasons", org.json.JSONArray().put(
                            JSONObject().put("number", item.season).put(
                                "episodes", org.json.JSONArray().put(JSONObject().put("number", item.episode)),
                            ),
                        ),
                    ),
                ),
            )
        }
        val ok = send("POST", "/sync/history", body) in 200..299
        if (ok) item.playbackId?.let { send("DELETE", "/sync/playback/$it") }
        return ok
    }

    /**
     * Takes the item out of Continue Watching on Trakt: clears paused progress, and for shows also uses
     * Trakt's "hide from progress" (undo on trakt.tv under Hidden items).
     */
    fun removeFromProgress(item: WatchItem): Boolean {
        var ok = true
        item.playbackId?.let { ok = send("DELETE", "/sync/playback/$it") in 200..299 || it == 0L }
        if (!item.isMovie) {
            val show = item.traktId ?: return false
            val body = JSONObject().put("shows", org.json.JSONArray().put(JSONObject().put("ids", JSONObject().put("trakt", show))))
            ok = ok && send("POST", "/users/hidden/progress_watched", body) in 200..299
        }
        return ok
    }

    /** Trakt ids of shows you've hidden from progress (so they stay out of Up Next). */
    fun hiddenShows(): Set<Int> = runCatching {
        val arr = org.json.JSONArray(get("/users/hidden/progress_watched?type=show&limit=250"))
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).obj("show")?.obj("ids")?.int("trakt") }.toSet()
    }.getOrDefault(emptySet())

    companion object { const val API = "https://api.trakt.tv" }
}
