package com.forja.app.core.games

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.forja.app.core.music.Music
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Sunetele jocurilor. Fișierele se sintetizează o dată pe telefon (fără resurse audio în APK). */
enum class Sfx(val file: String) {
    Move("move"), Rotate("rotate"), Lock("lock"), Clear1("clear1"), Clear2("clear2"), Clear3("clear3"), Clear4("clear4"),
    Hold("hold"), Win("win"), Lose("lose"),
    Paddle("paddle"), Brick("brick"), Break("break"), Steel("steel"), Boom("boom"), Capsule("capsule"), Life("life")
}

/**
 * Efectele sonore prin SoundPool (USAGE_GAME, fără focus audio: muzica ei cântă mai departe dedesubt și sunetele
 * urmează volumul media). WAV-urile (PCM 16 biți mono) se scriu o singură dată în cacheDir/games_sfx/v1/;
 * până se încarcă, nu sună nimic. Volumul: 0,7, sau 0,45 cât îi cântă muzica. Cel mult un sunet de același fel la 35 ms.
 * Se eliberează când ruta jocului iese din compoziție ([release]).
 */
class GameSfx(context: Context) {
    private val app = context.applicationContext
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val count = Sfx.entries.size
    private val ids = IntArray(count)
    private val loaded = BooleanArray(count)
    private val last = LongArray(count)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var released = false

    /** Setarea „Sunet” din Pauză. */
    @Volatile var enabled: Boolean = true

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            for (i in 0 until count) if (ids[i] == sampleId) loaded[i] = true
        }
        scope.launch {
            val files = try { withContext(Dispatchers.IO) { SfxSynth.ensure(app) } } catch (_: Exception) { null } ?: return@launch
            if (released) return@launch
            for (s in Sfx.entries) {
                val f = files[s.ordinal]
                if (f != null) try { ids[s.ordinal] = pool.load(f.absolutePath, 1) } catch (_: Exception) { }
            }
        }
    }

    fun play(s: Sfx, gain: Float = 1f) {
        if (!enabled || released) return
        val i = s.ordinal
        if (!loaded[i]) return
        val now = SystemClock.uptimeMillis()
        if (now - last[i] < 35) return
        last[i] = now
        val master = if (Music.nowPlaying.value?.playing == true) 0.45f else 0.7f
        val v = (master * gain).coerceIn(0f, 1f)
        try { pool.play(ids[i], v, v, 1, 0, 1f) } catch (_: Exception) { }
    }

    fun release() {
        released = true
        scope.cancel()
        try { pool.release() } catch (_: Exception) { }
    }
}

/** Sinteza sunetelor (unde sinusoidale, armonice, stingere exponențială, zgomot filtrat), în stilul MissionChime. */
internal object SfxSynth {
    private const val RATE = 44_100
    private const val DIR = "games_sfx/v1"

    /** Fișierele sunetelor (create acum dacă lipsesc), în ordinea [Sfx]. */
    fun ensure(context: Context): Array<File?> {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return Array(Sfx.entries.size) { i ->
            val s = Sfx.entries[i]
            val f = File(dir, "${s.file}.wav")
            try {
                if (!f.exists() || f.length() < 64) {
                    val tmp = File(dir, "${s.file}.tmp")
                    writeWav(tmp, synth(s))
                    tmp.renameTo(f)
                }
                f
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun synth(s: Sfx): ShortArray = when (s) {
        Sfx.Move -> render(0.02f, 0.25f) { t, _ -> tone(t, 1200f, 300f) }
        Sfx.Rotate -> render(0.05f, 0.35f) { t, _ -> tone(t, 660f, 70f) + 0.3f * tone(t, 1320f, 110f) }
        Sfx.Lock -> {
            val n = Noise(11)
            render(0.09f, 0.6f) { t, _ -> tone(t, 110f - 30f * t / 0.09f, 45f) + 0.45f * n.low(0.25f) * exp(-t * 70f) }
        }
        Sfx.Clear1 -> arpeggio(1)
        Sfx.Clear2 -> arpeggio(2)
        Sfx.Clear3 -> arpeggio(3)
        Sfx.Clear4 -> arpeggio(4)
        Sfx.Hold -> {
            val n = Noise(23)
            render(0.07f, 0.3f) { t, _ ->
                val env = sin(PI.toFloat() * (t / 0.07f).coerceIn(0f, 1f))
                n.band(0.35f) * env
            }
        }
        Sfx.Win -> notes(floatArrayOf(523.25f, 659.25f, 783.99f), floatArrayOf(0f, 0.15f, 0.30f), floatArrayOf(0.30f, 0.30f, 0.60f), 0.9f, 0.6f)
        Sfx.Lose -> notes(floatArrayOf(392.0f, 329.63f, 261.63f), floatArrayOf(0f, 0.2f, 0.4f), floatArrayOf(0.22f, 0.22f, 0.45f), 0.85f, 0.5f, bright = 0.12f)
        Sfx.Paddle -> {
            val n = Noise(31)
            render(0.06f, 0.45f) { t, _ -> tone(t, 220f, 60f) + 0.5f * tone(t, 440f, 90f) + 0.35f * n.white() * exp(-t * 400f) }
        }
        Sfx.Brick -> render(0.07f, 0.35f) { t, _ -> tone(t, 900f, 60f) + 0.55f * tone(t, 1340f, 80f) + 0.3f * tone(t, 2150f, 110f) }
        Sfx.Break -> {
            val n = Noise(47)
            render(0.11f, 0.45f) { t, _ -> n.low(0.4f) * exp(-t * 30f) + 0.4f * tone(t, 300f - 900f * t, 35f) }
        }
        Sfx.Steel -> render(0.16f, 0.3f) { t, _ -> tone(t, 1800f, 22f) + 0.7f * tone(t, 2700f, 30f) }
        Sfx.Boom -> {
            val n = Noise(59)
            render(0.24f, 0.8f) { t, _ -> n.low(0.06f) * exp(-t * 11f) * 1.6f + 0.8f * tone(t, 60f, 14f) }
        }
        Sfx.Capsule -> render(0.13f, 0.4f) { t, _ ->
            // de la 400 la 900 Hz: faza e integrala frecvenței
            sin(2f * PI.toFloat() * (400f * t + 250f * t * t / 0.13f)) * min(1f, t / 0.005f) * min(1f, (0.13f - t) / 0.02f)
        }
        Sfx.Life -> render(0.32f, 0.5f) { t, _ ->
            // de la 700 la 200 Hz: faza e integrala frecvenței
            val phase = 2f * PI.toFloat() * (700f * t - 500f * t * t / (2f * 0.32f))
            (sin(phase) + 0.25f * sin(2f * phase)) * min(1f, t / 0.006f) * exp(-t * 4f) * min(1f, (0.32f - t) / 0.03f)
        }
    }

    /** O notă de clopot: atac de 5 ms, stingere exponențială. */
    private fun tone(t: Float, f: Float, decay: Float): Float = sin(2f * PI.toFloat() * f * t) * min(1f, t / 0.004f) * exp(-t * decay)

    private fun arpeggio(n: Int): ShortArray {
        val all = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)
        val f = all.copyOf(n)
        val starts = FloatArray(n) { it * 0.07f }
        val lens = FloatArray(n) { if (it == n - 1) 0.32f else 0.18f }
        return notes(f, starts, lens, 0.07f * (n - 1) + 0.34f, 0.5f)
    }

    private fun notes(f: FloatArray, starts: FloatArray, lens: FloatArray, total: Float, gain: Float, bright: Float = 0.32f): ShortArray {
        val len = (total * RATE).toInt()
        val buf = FloatArray(len)
        val fade = 0.02f * RATE
        for (i in f.indices) {
            val s0 = (starts[i] * RATE).toInt()
            val n = (lens[i] * RATE).toInt()
            val decay = if (i == f.size - 1) 4.2f else 7.5f
            for (k in 0 until n) {
                val idx = s0 + k
                if (idx >= len) break
                val t = k / RATE.toFloat()
                val env = min(1f, t / 0.006f) * exp(-t * decay) * min(1f, (n - k) / fade)
                val w = 2f * PI.toFloat() * f[i] * t
                buf[idx] += env * (sin(w) + bright * sin(2f * w) + 0.12f * sin(3f * w))
            }
        }
        return normalize(buf, gain)
    }

    private inline fun render(seconds: Float, gain: Float, f: (Float, Int) -> Float): ShortArray {
        val n = (seconds * RATE).toInt()
        val buf = FloatArray(n)
        val fade = (0.004f * RATE).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val out = f(i / RATE.toFloat(), i)
            buf[i] = out * min(1f, (n - i).toFloat() / fade)
        }
        return normalize(buf, gain)
    }

    private fun normalize(buf: FloatArray, gain: Float): ShortArray {
        var peak = 1e-6f
        for (v in buf) peak = maxOf(peak, abs(v))
        val g = gain * 0.9f * Short.MAX_VALUE / peak
        return ShortArray(buf.size) { (buf[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
        b.putShort(1); b.putShort(1)                      // PCM, mono
        b.putInt(RATE); b.putInt(RATE * 2)                // rata, octeți pe secundă
        b.putShort(2); b.putShort(16)                     // aliniere, biți
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(dataBytes)
        for (v in pcm) b.putShort(v)
        FileOutputStream(file).use { it.write(b.array()) }
    }

    /** Zgomot determinist (aceleași fișiere pe orice telefon), cu filtre de o singură treaptă. */
    private class Noise(seed: Long) {
        private val rng = Rng(seed)
        private var lp = 0f
        private var lp2 = 0f
        fun white(): Float = rng.nextFloat() * 2f - 1f
        fun low(a: Float): Float {
            lp += a * (white() - lp)
            return lp * 2.2f
        }
        fun band(a: Float): Float {
            lp += a * (white() - lp)
            lp2 += 0.05f * (lp - lp2)
            return (lp - lp2) * 2f
        }
    }
}
