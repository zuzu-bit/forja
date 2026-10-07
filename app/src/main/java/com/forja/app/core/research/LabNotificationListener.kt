package com.forja.app.core.research

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Android settings opt-in AND active lab source consent are both necessary. No screen/accessibility scraping. */
class LabNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        labEvent("NOTIFICATION", "listener_connected", JSONObject().put("contentScope", "Android exposed notification extras only"))
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val capture = LabCapture(this)
        if (sbn == null || !capture.allows("NOTIFICATION")) return
        if (sbn.packageName == packageName && sbn.id == 210) return
        val notification = sbn.notification
        val extras = notification.extras
        fun text(key: String): Any = extras.getCharSequence(key)?.toString()?.take(1024) ?: JSONObject.NULL
        val payload = JSONObject().put("package", sbn.packageName).put("app", appLabel(this, sbn.packageName))
            .put("notificationId", sbn.id).put("notificationKey", sbn.key.take(1024))
            .put("tag", sbn.tag?.take(256) ?: JSONObject.NULL).put("title", text(Notification.EXTRA_TITLE))
            .put("text", text(Notification.EXTRA_TEXT)).put("bigText", text(Notification.EXTRA_BIG_TEXT))
            .put("subText", text(Notification.EXTRA_SUB_TEXT)).put("category", notification.category ?: JSONObject.NULL)
            .put("channelId", notification.channelId ?: JSONObject.NULL).put("ongoing", sbn.isOngoing)
            .put("groupKey", sbn.groupKey?.take(1024) ?: JSONObject.NULL).put("flags", notification.flags)
            .put("timestamp", sbn.postTime).put("contentExposed", extras.containsKey(Notification.EXTRA_TEXT) || extras.containsKey(Notification.EXTRA_BIG_TEXT))
        capture.event("NOTIFICATION", "notification_posted", payload, sbn.postTime)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val capture = LabCapture(this)
        if (sbn == null || !capture.allows("NOTIFICATION")) return
        if (sbn.packageName == packageName && sbn.id == 210) return
        capture.event("NOTIFICATION", "notification_removed", JSONObject().put("package", sbn.packageName)
            .put("notificationId", sbn.id).put("notificationKey", sbn.key.take(1024)))
    }
    override fun onListenerDisconnected() {
        labEvent("NOTIFICATION", "listener_disconnected", JSONObject())
        super.onListenerDisconnected()
    }
}
