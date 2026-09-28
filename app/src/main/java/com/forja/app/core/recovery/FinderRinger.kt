package com.forja.app.core.recovery

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Soneria „Sună” de pe site. Separată de soneria alarmei de dimineață (core/sleep/AlarmRinger): „M-am trezit”
 * nu oprește căutarea, iar „Am găsit telefonul” nu oprește alarma.
 *
 * USAGE_ALARM: sună pe canalul de alarmă, deci și cu telefonul pe silențios sau cu volumul media la zero.
 * Volumul alarmei urcă la maxim cât sună și revine apoi la cel ales de tine.
 */
object FinderRinger {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var restoreVolume: Int? = null

    @Volatile
    var ringing: Boolean = false
        private set

    @Synchronized
    fun start(context: Context) {
        if (ringing) return
        ringing = true
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (audio != null) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val now = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                if (now < max) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                    restoreVolume = now
                }
            }
        } catch (_: Exception) { restoreVolume = null }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(app, uri)
            mp.isLooping = true
            // CPU-ul rămâne treaz cât sună, chiar cu ecranul stins.
            try { mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK) } catch (_: Exception) { }
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
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 1200), 0), attrs)
        } catch (_: Exception) {
            vibrator = null
        }
    }

    @Synchronized
    fun stop(context: Context) {
        ringing = false
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (_: Exception) { }
        vibrator = null
        val restore = restoreVolume ?: return
        restoreVolume = null
        try {
            (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.setStreamVolume(AudioManager.STREAM_ALARM, restore, 0)
        } catch (_: Exception) { }
    }
}
