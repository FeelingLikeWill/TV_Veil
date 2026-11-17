package dev.veil.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the remote's Home button open Veil. It only looks at the Home key and at which
 * app's window comes to the front; it never reads screen content. All other keys pass through.
 */
class HomeButtonService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Fallback: if Google's home screen still comes up (e.g. after a system event), replace it.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Only the TV's own home *screen* (not its other screens, like an apps list or settings).
        if (pkg == GOOGLE_HOME && cls.endsWith("HomeActivity")) { goHome(); return }
        if (otherHomes.any { (p, c) -> p == pkg && c == cls }) goHome()
    }

    override fun onInterrupt() = Unit

    private fun goHome() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        } catch (_: Exception) {
            // Never crash the service; Google's home screen remains as a fallback.
        }
    }

    /**
     * The home screens other than Veil installed on this TV (package, activity), e.g. Android TV's
     * com.google.android.tvlauncher or a TV maker's own launcher. Android's boot-time "FallbackHome"
     * in Settings is left out.
     */
    private val otherHomes: List<Pair<String, String>> by lazy {
        runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName to it.activityInfo.name }
                .filter { (p, _) -> p != packageName && p != "com.android.settings" && p != "com.android.tv.settings" }
        }.getOrDefault(emptyList())
    }

    private companion object { const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx" }
}
