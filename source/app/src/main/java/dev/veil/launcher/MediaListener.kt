package dev.veil.launcher

import android.service.notification.NotificationListenerService

/**
 * Exists only so Android allows Veil to see active media sessions (for "now playing").
 * It does not read, store or react to notifications.
 */
class MediaListener : NotificationListenerService()
