package com.forja.app.core.music

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Permisul muzicii. Android arată sesiunile media (ce cântă, în ce aplicație) doar unui „serviciu de notificări”
 * aprobat de om în „Acces la notificări”; FORJA îl declară DOAR pentru asta.
 *
 * Notificările nu se citesc și nu se păstrează: metodele de mai jos sunt goale, iar manifestul cere sistemului să nu
 * trimită niciun tip de notificare (default_filter_types = 0, toate tipurile dezactivate). Singurele date folosite
 * sunt metadatele sesiunii media, în [Music].
 */
class MusicListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Music.onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Music.onListenerDisconnected(this)
        // Deconectat de sistem, dar accesul e încă dat (ex. după o actualizare): cerem legarea din nou.
        if (Music.hasAccess(this)) {
            try { NotificationListenerService.requestRebind(Music.listenerComponent(this)) } catch (_: Exception) { }
        }
    }

    // Conținutul notificărilor nu ne privește: nu citim nimic, nu păstrăm nimic.
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: NotificationListenerService.RankingMap?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: NotificationListenerService.RankingMap?) = Unit
}
