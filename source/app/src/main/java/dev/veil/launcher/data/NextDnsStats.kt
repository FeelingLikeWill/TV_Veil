package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig
import org.json.JSONObject

/** Stats from the NextDNS analytics API. Only active when a profile and API key are configured. */
object NextDnsStats {
    data class Details(
        val blocked: Int,
        val total: Int,
        /** Most blocked over 24 h: tracker company if NextDNS knows it, else the domain. */
        val topBlocked: List<Pair<String, Int>>,
        /** Share of lookups that were encrypted, 0..100, or null if unknown. */
        val encryptedPct: Int?,
    )

    val enabled: Boolean
        get() = BuildConfig.NEXTDNS_PROFILE.isNotBlank() && BuildConfig.NEXTDNS_API_KEY.isNotBlank()

    private fun get(path: String): JSONObject? {
        val (code, text) = runCatching {
            Http.call(
                "GET",
                "https://api.nextdns.io/profiles/${BuildConfig.NEXTDNS_PROFILE}$path",
                mapOf("X-Api-Key" to BuildConfig.NEXTDNS_API_KEY),
            )
        }.getOrNull() ?: return null
        return if (code == 200) runCatching { JSONObject(text) }.getOrNull() else null
    }

    /** Blocked queries over the last 24 hours, or null if unavailable. */
    fun blockedLast24h(): Int? = statusCounts()?.first

    /** (blocked, total) over 24 h. */
    private fun statusCounts(): Pair<Int, Int>? {
        val data = get("/analytics/status?from=-24h")?.optJSONArray("data") ?: return null
        var blocked = 0; var total = 0
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val q = o.optInt("queries")
            total += q
            if (o.str("status") == "blocked") blocked += q
        }
        return blocked to total
    }

    /** Last 7 days: total blocked, the most-blocked tracker, and blocked per day (oldest first). */
    data class Weekly(val blocked: Int, val topTracker: String?, val days: List<Pair<String, Int>>)

    fun weekly(): Weekly? {
        if (!enabled) return null
        val tz = java.util.TimeZone.getDefault().id
        val series = get("/analytics/status;series?from=-7d&interval=1d&alignment=clock&timezone=$tz&partials=end") ?: return null
        val data = series.optJSONArray("data") ?: return null
        var blockedDays: org.json.JSONArray? = null
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            if (o.str("status") == "blocked") blockedDays = o.optJSONArray("queries")
        }
        val times = series.obj("meta")?.obj("series")?.optJSONArray("times")
        val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val days = (0 until (times?.length() ?: 0)).map { i ->
            val label = runCatching { fmt.format(java.util.Date(java.time.Instant.parse(times!!.getString(i)).toEpochMilli())) }
                .getOrDefault("")
            label to (blockedDays?.optInt(i) ?: 0)
        }.takeLast(7)
        val top = get("/analytics/domains?status=blocked&from=-7d&limit=1&root=true")?.optJSONArray("data")
            ?.optJSONObject(0)?.let { it.str("tracker") ?: it.str("domain") }
        return Weekly(days.sumOf { it.second }, top, days)
    }

    fun details(): Details? {
        if (!enabled) return null
        val (blocked, total) = statusCounts() ?: return null
        val top = ArrayList<Pair<String, Int>>()
        get("/analytics/domains?status=blocked&from=-24h&limit=3&root=true")?.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val label = o.str("tracker") ?: o.str("domain") ?: continue
                top += label to o.optInt("queries")
            }
        }
        val pct = get("/analytics/encryption?from=-24h")?.optJSONArray("data")?.let { arr ->
            var enc = 0; var all = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val q = o.optInt("queries")
                all += q
                if (o.optBoolean("encrypted")) enc += q
            }
            if (all > 0) (enc * 100 / all) else null
        }
        return Details(blocked, total, top, pct)
    }
}
