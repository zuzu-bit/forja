package com.forja.app.core.sleep

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Soneria alarmei — un singur player pentru tot procesul, ca să nu sune de două ori
 * când serviciul și AlarmActivity pornesc amândouă. USAGE_ALARM: sună pe canalul de
 * alarmă, nu pe cel de muzică, deci nu contează volumul media lăsat pe zero.
 */
object AlarmRinger {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Volatile
    var ringing: Boolean = false
        private set

    @Synchronized
    fun start(context: Context) {
        if (ringing) return
        ringing = true
        val app = context.applicationContext
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Exception) {
            player = null
        }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0), attrs)
        } catch (_: Exception) {
            vibrator = null
        }
    }

    @Synchronized
    fun stop() {
        ringing = false
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (_: Exception) { }
        vibrator = null
    }
}
