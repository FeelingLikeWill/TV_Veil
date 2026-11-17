package dev.veil.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android switches accessibility services off in some situations (for example when the app is
 * force-stopped). Veil holds WRITE_SECURE_SETTINGS, so it quietly re-enables its Home-button
 * service whenever it comes on screen. Other enabled services are left untouched.
 */
object HomeServiceGuard {
    fun ensureEnabled(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val cr = context.contentResolver
        val me = ComponentName(context, HomeButtonService::class.java).flattenToString()
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val list = current.split(':').filter { it.isNotBlank() }
        runCatching {
            if (me !in list) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }
    }
}
