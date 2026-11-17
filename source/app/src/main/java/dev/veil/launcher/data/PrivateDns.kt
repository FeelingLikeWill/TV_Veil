package dev.veil.launcher.data

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.veil.launcher.DnsResumeReceiver
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import dev.veil.launcher.BuildConfig
import kotlinx.coroutines.delay

/**
 * Network-wide ad blocking via Android's Private DNS (DNS-over-TLS). "Off" returns the setting to
 * Android's default (automatic), which is how the TV was configured before Veil.
 */
class PrivateDns(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** NextDNS when a profile is configured (enables the blocked counter), otherwise AdGuard DNS. */
    val hostname: String =
        BuildConfig.NEXTDNS_PROFILE.takeIf { it.isNotBlank() }?.let { "$it.dns.nextdns.io" } ?: "dns.adguard-dns.com"

    val canControl: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    val isOn: Boolean
        get() = Settings.Global.getString(resolver, MODE) == "hostname" &&
            Settings.Global.getString(resolver, SPECIFIER) == hostname

    fun turnOff() {
        Settings.Global.putString(resolver, MODE, "opportunistic")
    }

    // ---- Pause: off now, back on automatically at a set time ----

    private val prefs get() = context.getSharedPreferences("dns", Context.MODE_PRIVATE)

    /** When a pause ends (epoch ms), or 0 when not paused. */
    val resumeAt: Long get() = prefs.getLong("resumeAt", 0L)
    val isPaused: Boolean get() = resumeAt > 0L && !isOn

    fun pause(minutes: Int) {
        turnOff()
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong("resumeAt", at).apply()
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, resumeIntent())
    }

    fun clearPause() {
        prefs.edit().remove("resumeAt").apply()
        context.getSystemService(AlarmManager::class.java).cancel(resumeIntent())
    }

    private fun resumeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, DnsResumeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Turns ad blocking on, then checks that the network still works through the new DNS server.
     * If it doesn't within ~12 seconds, it switches straight back off and returns false.
     */
    suspend fun turnOnSafely(): Boolean {
        Settings.Global.putString(resolver, SPECIFIER, hostname)
        Settings.Global.putString(resolver, MODE, "hostname")
        val cm = context.getSystemService(ConnectivityManager::class.java)
        repeat(12) {
            delay(1_000)
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            if (validated && lp?.isPrivateDnsActive == true && lp.privateDnsServerName == hostname) {
                // Final proof: an actual lookup through the new resolver.
                if (runCatching { net.getAllByName("api.trakt.tv").isNotEmpty() }.getOrDefault(false)) return true
            }
        }
        turnOff()
        return false
    }

    private companion object {
        const val MODE = "private_dns_mode"
        const val SPECIFIER = "private_dns_specifier"
    }
}
