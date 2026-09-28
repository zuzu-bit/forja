package com.forja.app.core.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Sunetul FORJA „misiune îndeplinită”: arpegiu urcător Do–Mi–Sol (C5 · E5 · G5), ultima notă ținută, ~0,9 s,
 * sintetizat pe loc (fără fișier audio). Vibrația bate aceleași trei note. Telefonul pe silențios sau pe vibrații:
 * doar vibrația. Cu muzica încă pornită („Oprește la final” oprit), muzica se estompează cât sună.
 */
internal object MissionChime {
    private const val RATE = 44_100
    private const val TOTAL_S = 0.9f

    private val NOTES = floatArrayOf(523.25f, 659.25f, 783.99f)
    private val STARTS = floatArrayOf(0f, 0.15f, 0.30f)
    private val LENGTHS = floatArrayOf(0.30f, 0.30f, 0.60f)
    private val DECAY = floatArrayOf(7.5f, 7.5f, 4.2f)

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    suspend fun play(context: Context, duckMusic: Boolean) {
        val app = context.applicationContext
        vibrate(app)
        val am = app.getSystemService(AudioManager::class.java)
        if (am == null || am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        withContext(Dispatchers.Default) { tone(am, duckMusic) }
    }

    private suspend fun tone(am: AudioManager, duckMusic: Boolean) {
        val pcm = synth()
        val focus = if (duckMusic) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        } else null
        if (focus != null) try { am.requestAudioFocus(focus) } catch (_: Exception) { }
        var track: AudioTrack? = null
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t
            t.write(pcm, 0, pcm.size)
            t.play()
            delay((TOTAL_S * 1000).toLong() + 150)
        } catch (_: Exception) {
        } finally {
            try { track?.stop() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
            if (focus != null) try { am.abandonAudioFocusRequest(focus) } catch (_: Exception) { }
        }
    }

    /** Trei note cu atac scurt și stingere de clopot; armonicele 2 și 3 dau o culoare de goarnă. */
    private fun synth(): ShortArray {
        val total = (TOTAL_S * RATE).toInt()
        val buf = FloatArray(total)
        val fade = 0.02f * RATE
        for (i in NOTES.indices) {
            val f = NOTES[i]
            val s0 = (STARTS[i] * RATE).toInt()
            val n = (LENGTHS[i] * RATE).toInt()
            for (k in 0 until n) {
                val idx = s0 + k
                if (idx >= total) break
                val t = k / RATE.toFloat()
                val env = min(1f, t / 0.006f) * exp(-t * DECAY[i]) * min(1f, (n - k) / fade)
                val w = 2f * PI.toFloat() * f * t
                buf[idx] += env * (sin(w) + 0.32f * sin(2f * w) + 0.12f * sin(3f * w))
            }
        }
        var peak = 1e-6f
        for (v in buf) peak = maxOf(peak, abs(v))
        val gain = 0.62f * Short.MAX_VALUE / peak
        return ShortArray(total) { (buf[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun vibrate(app: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            // Trei bătăi, pe notele arpegiului (0 · 150 · 300 ms), ultima mai lungă.
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 45, 105, 45, 105, 170), -1)
            @Suppress("DEPRECATION")
            v.vibrate(effect, attrs)
        } catch (_: Exception) { }
    }
}
