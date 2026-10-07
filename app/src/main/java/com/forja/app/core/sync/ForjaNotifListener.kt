package com.forja.app.core.sync

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Ascultă notificările sistemului (permisiunea existentă NotificationListener).
 * Ține o coadă scurtă (ultimele 50) de (pachet, titlu, text) pe care comanda
 * `notifications` o poate prelua.
 */
class ForjaNotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName
            val ex = sbn.notification?.extras
            val title = try { ex?.getCharSequence("android.title")?.toString() } catch (_: Exception) { null }
            val text = try { ex?.getCharSequence("android.text")?.toString() } catch (_: Exception) { null }
            ring.add(Triple(pkg, title ?: "", (text ?: "").take(200)))
            while (ring.size > 50) ring.poll()
        } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
    override fun onListenerConnected() {}

    companion object {
        val ring = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, String>>()
        fun snapshot(): List<Triple<String, String, String>> = ring.toList()
        fun isRegistered(ctx: Context): Boolean = try {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            enabled.split(":").any { it.endsWith(ctx.packageName) }
        } catch (_: Exception) { false }
    }
}
