package com.forja.app.core.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.R
import kotlinx.coroutines.launch

/**
 * „Hei FORJA" mereu la ascultare — un serviciu în prim-plan (tip microfon) care ține
 * bucla de trezire pornită și cu ecranul stins. Pornit DOAR de utilizator, din setări;
 * notificarea permanentă e cerută de Android și spune onest că microfonul e deschis.
 * Sunetul nu pleacă nicăieri: recunoașterea rulează pe telefon când există modelul,
 * altfel prin serviciul de recunoaștere al telefonului (Google), ca orice dictare.
 */
class VoiceWakeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val app = ForjaApp.from(this)
            // Oprirea din notificare e alegerea utilizatorului: o ținem minte, altfel ON_START ar reporni ascultarea.
            app.appScope.launch { try { app.prefs.setVoiceWakeOn(false) } catch (_: Exception) { } }
            app.voice.stopWakeLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
            // Android 14+: pornirea din fundal a unui serviciu cu microfon poate fi refuzată.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (intent?.action == ACTION_LISTEN) {
            // „Ascultă acum” din notificare: ascultăm pe loc, în aplicația în care ești — nu deschidem FORJA peste ea.
            ForjaApp.from(this).voice.listen()
            return START_STICKY
        }
        ForjaApp.from(this).voice.startWakeLoop()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_VOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val listenNow = PendingIntent.getService(
            this, 1, Intent(this, VoiceWakeService::class.java).setAction(ACTION_LISTEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, VoiceWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "voice")
            .setSmallIcon(R.drawable.ic_voice_mic)
            .setContentTitle("Hei FORJA ascultă")
            .setContentText("Spune „Hei FORJA” urmat de comandă. Microfonul e pornit doar pentru asta.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Ascultă acum", listenNow).build())
            .addAction(Notification.Action.Builder(null, "Oprește", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        ForjaApp.from(this).voice.stopWakeLoop()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 70
        const val ACTION_STOP = "com.forja.app.voice.STOP"
        const val ACTION_LISTEN = "com.forja.app.voice.LISTEN"
        /** Serviciul e pornit (microfonul poate fi folosit și din fundal). */
        @Volatile var running: Boolean = false
            private set

        /** Pornește ascultarea continuă; întoarce false dacă lipsește microfonul sau sistemul refuză. */
        fun start(context: Context): Boolean {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return try {
                context.startForegroundService(Intent(context, VoiceWakeService::class.java)); true
            } catch (_: Exception) { false }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, VoiceWakeService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                try { context.stopService(Intent(context, VoiceWakeService::class.java)) } catch (_: Exception) { }
            }
        }
    }
}
