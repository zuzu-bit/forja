package com.forja.app.core.sleep

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sunete de adormit — generate LOCAL, în timp real, fără fișiere și fără licențe.
 * Ploaie, valuri, vânt, zgomot alb: zgomot filtrat + modulație lentă.
 */
object SleepSounds {
    private const val SR = 44100
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stopAt = 0L

    /** Ce sună acum (null = nimic) — pentru UI. */
    val current = MutableStateFlow<String?>(null)

    fun toggle(type: String, timerMinutes: Int = 0) {
        if (current.value == type) stop() else start(type, timerMinutes)
    }

    fun setTimer(minutes: Int) {
        stopAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    fun start(type: String, timerMinutes: Int = 0) {
        stop()
        running = true
        current.value = type
        stopAt = if (timerMinutes > 0) SystemClock.elapsedRealtime() + timerMinutes * 60_000L else 0L

        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SR))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try { t.play() } catch (_: Exception) { }

        thread = Thread {
            val buf = ShortArray(2048)
            val rnd = Random()
            // Filtru de zgomot ROZ (Paul Kellet) — mult mai natural și mai plin decât white.
            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
            var lp = 0.0            // rumble pentru vânt
            var swell = 0.0         // valul care vine și pleacă
            var windPhase = 0.0
            var env = 0.0           // fade-in liniar 1,5s
            val fadeStep = 1.0 / (SR * 1.5)
            while (running) {
                if (stopAt > 0 && SystemClock.elapsedRealtime() >= stopAt) break
                for (i in buf.indices) {
                    val white = rnd.nextDouble() * 2 - 1
                    b0 = 0.99886 * b0 + white * 0.0555179
                    b1 = 0.99332 * b1 + white * 0.0750759
                    b2 = 0.96900 * b2 + white * 0.1538520
                    b3 = 0.86650 * b3 + white * 0.3104856
                    b4 = 0.55000 * b4 + white * 0.5329522
                    b5 = -0.7616 * b5 - white * 0.0168980
                    val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11
                    b6 = white * 0.115926

                    val s: Double = when (type) {
                        "rain" -> pink * 0.85 + white * 0.28          // roz plin + sizzle de stropi
                        "waves" -> {
                            swell += 2 * PI / (SR * 9.0)              // val la ~9 secunde
                            val amp = 0.22 + 0.78 * (0.5 + 0.5 * sin(swell))
                            (pink * 1.35) * amp
                        }
                        "wind" -> {
                            lp = lp * 0.995 + pink * 0.005            // rumble jos
                            windPhase += 2 * PI / (SR * 7.0)
                            val amp = 0.4 + 0.6 * (0.5 + 0.5 * sin(windPhase))
                            (lp * 9.0).coerceIn(-1.0, 1.0) * amp
                        }
                        else -> pink * 1.0                            // zgomot roz simplu
                    }
                    if (env < 1.0) env += fadeStep
                    val v = (s * env * 0.9).coerceIn(-1.0, 1.0)
                    buf[i] = (v * Short.MAX_VALUE).toInt().toShort()
                }
                try { track?.write(buf, 0, buf.size) } catch (_: Exception) { break }
            }
            running = false
            try { track?.stop(); track?.release() } catch (_: Exception) { }
            track = null
            current.value = null
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(250) } catch (_: Exception) { }
        try { track?.stop(); track?.release() } catch (_: Exception) { }
        track = null
        current.value = null
        stopAt = 0L
    }
}
