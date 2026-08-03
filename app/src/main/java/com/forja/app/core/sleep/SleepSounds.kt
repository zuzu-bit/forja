package com.forja.app.core.sleep

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Random
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
            var lp = 0.0        // filtru trece-jos
            var brown = 0.0     // integrator (zgomot brun)
            var lfo = 0.0       // modulație lentă (valuri/vânt)
            var envLp = 0.0
            while (running) {
                if (stopAt > 0 && SystemClock.elapsedRealtime() >= stopAt) { break }
                for (i in buf.indices) {
                    val white = rnd.nextDouble() * 2 - 1
                    val s: Double = when (type) {
                        "rain" -> {
                            // hiss ușor filtrat, ca stropii pe geam
                            lp = lp * 0.86 + white * 0.14
                            (white * 0.55 + lp * 0.45) * 0.5
                        }
                        "waves" -> {
                            brown = (brown + 0.018 * white)
                            if (brown > 1) brown = 1.0; if (brown < -1) brown = -1.0
                            lfo += 0.00007
                            val amp = 0.35 + 0.45 * (0.5 + 0.5 * sin(lfo))
                            (brown * 3.2).coerceIn(-1.0, 1.0) * amp
                        }
                        "wind" -> {
                            lp = lp * 0.992 + white * 0.008
                            lfo += 0.00004
                            val amp = 0.5 + 0.4 * (0.5 + 0.5 * sin(lfo))
                            (lp * 6.0).coerceIn(-1.0, 1.0) * amp
                        }
                        else -> white * 0.4 // zgomot alb
                    }
                    // fade-in blând (evită pocnetul de start)
                    envLp = envLp * 0.9995 + 0.0005
                    val v = (s * envLp * 0.55).coerceIn(-1.0, 1.0)
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
