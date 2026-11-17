package dev.veil.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.veil.launcher.data.PrivateDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Turns ad blocking back on when a pause ends, even if you're inside another app. */
class DnsResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dns = PrivateDns(context.applicationContext)
        val at = dns.resumeAt
        if (at == 0L) return
        if (at - System.currentTimeMillis() > 60_000) return // stale/early alarm; a newer pause is pending
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dns.clearPause()
                if (!dns.isOn) dns.turnOnSafely()
            } finally {
                pending.finish()
            }
        }
    }
}
