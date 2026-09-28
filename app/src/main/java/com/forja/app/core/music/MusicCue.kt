package com.forja.app.core.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Sunetele FORJA peste muzica ta (workout-music.md §4.4): cer doar „estompare scurtă” (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
 * și o eliberează imediat — muzica scade o clipă și revine, nu se oprește. FORJA nu cere niciodată AUDIOFOCUS_GAIN
 * (acela ar pune Spotify pe pauză). Vibrația rămâne mereu (telefon pe silențios = doar vibrația).
 *
 * - CHIME: „misiune îndeplinită” (arpegiul din [MissionChime]) — finalul inventarului, finalul antrenamentului.
 * - BEEP: două note scurte (proba de sunet din ecranul Probă: se aude peste muzică, în căști?).
 */
object MusicCue {
    enum class Cue { CHIME, BEEP }

    private const val RATE = 44_100

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    suspend fun play(context: Context, cue: Cue, duck: Boolean = true) {
        when (cue) {
            Cue.CHIME -> MissionChime.play(context, duckMusic = duck)
            Cue.BEEP -> beep(context.applicationContext, duck)
        }
    }

    private suspend fun beep(app: Context, duck: Boolean) {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        withContext(Dispatchers.Default) {
            val pcm = synth(floatArrayOf(880f, 1318.5f), 0.11f, 0.05f)
            val focus = if (duck) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build() else null
            if (focus != null) try { am.requestAudioFocus(focus) } catch (_: Exception) { }
            var track: AudioTrack? = null
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(pcm, 0, pcm.size)
                t.play()
                delay(pcm.size * 1000L / RATE + 120)
            } catch (_: Exception) {
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                if (focus != null) try { am.abandonAudioFocusRequest(focus) } catch (_: Exception) { }
            }
        }
    }

    /** Note scurte cu atac rapid și stingere, despărțite de o pauză mică. */
    private fun synth(notes: FloatArray, noteS: Float, gapS: Float): ShortArray {
        val n = (noteS * RATE).toInt()
        val g = (gapS * RATE).toInt()
        val out = ShortArray(notes.size * n + (notes.size - 1) * g)
        var at = 0
        for ((i, f) in notes.withIndex()) {
            for (k in 0 until n) {
                val t = k / RATE.toFloat()
                val env = min(1f, t / 0.004f) * exp(-t * 18f) * min(1f, (n - k) / (0.01f * RATE))
                out[at + k] = (0.55f * Short.MAX_VALUE * env * sin(2f * PI.toFloat() * f * t)).toInt().coerceIn(-32768, 32767).toShort()
            }
            at += n + if (i < notes.size - 1) g else 0
        }
        return out
    }
}
