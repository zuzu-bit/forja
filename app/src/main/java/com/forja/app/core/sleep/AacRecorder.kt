package com.forja.app.core.sleep

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PCM → AAC (.m4a) în timp real, în BUCĂȚI de ~30 min: `sleep_full/<sesiune>/chunk_<i>.m4a`.
 *
 * Fiecare bucată are propriul encoder + muxer. Rotația se face exact la granița de mostre
 * (`chunkSamples`): blocul de PCM care trece peste graniță se împarte — prima parte închide bucata
 * veche, restul deschide bucata nouă. `from`/`dur` se calculează din mostrele primite, nu din ceas.
 *
 * Cât costă rotația pe firul audio: doar deschiderea encoderului nou (zeci de ms). Închiderea bucății
 * vechi (golirea encoderului, indexul MP4 — până la câteva secunde pentru 30 min) se face pe un fir
 * separat, în ordine. Tamponul AudioRecord de 2 s ([SleepTrackService]) acoperă pauza, deci în condiții
 * normale nu se pierd mostre; rămâne aproximarea codecului AAC (~1024 mostre de „primire” la începutul
 * fiecărui fișier, ≈ 30 ms). Dacă sistemul ar bloca totuși firul audio mai mult de 2 s, blocul pierdut
 * ar decala timpii următori — nu pretindem mai mult decât atât.
 *
 * Manifestul `chunks.json` se rescrie după fiecare bucată închisă și la STOP, ca urcarea (WorkManager,
 * alt ciclu de viață) să știe ce există chiar dacă procesul moare între timp.
 *
 * Fire: [feed] vine de pe firul audio, [stop] de pe alt fir (serviciul, la STOP) — se exclud reciproc;
 * lista de bucăți și manifestul se scriu doar de pe firul de închidere, în ordinea bucăților.
 */
class AacRecorder(
    private val sampleRate: Int,
    private val dir: File,
    private val sessionId: Long,
    private val chunkMs: Long = DEFAULT_CHUNK_MS,
    private val onChunkClosed: ((Chunk, File) -> Unit)? = null
) {
    /** O bucată de înregistrare: `from`/`dur` în ms față de începutul audio-ului (nu al sesiunii). */
    @Serializable
    data class Chunk(val index: Int, val file: String, val from: Long, val dur: Long)

    /** Manifestul unei sesiuni: când a pornit microfonul (epoch ms) și bucățile scrise. */
    @Serializable
    data class Manifest(
        val session: Long,
        val startedAt: Long,
        val sampleRate: Int,
        val chunks: List<Chunk> = emptyList(),
        val closed: Boolean = false
    )

    private val chunkSamples: Long = chunkMs * sampleRate / 1000L
    private var totalSamples = 0L
    /** Bucățile închise — atinse DOAR de pe firul [closer] (o singură coadă, în ordine). */
    private val chunks = ArrayList<Chunk>()
    private var segment: Segment? = null
    private val startedAt = System.currentTimeMillis()
    /** Un singur fir pentru închiderea bucăților și scrierea manifestului. */
    private val closer = Executors.newSingleThreadExecutor { r -> Thread(r, "forja-aac-close").apply { isDaemon = true } }

    @Volatile
    var failed = false
        private set

    init {
        dir.mkdirs()
        segment = Segment(0, 0L)
        writeManifest(closed = false)
    }

    /** Trimite un bloc de PCM (short-uri mono), de pe firul audio. Se exclude reciproc cu [stop]. */
    @Synchronized
    fun feed(samples: ShortArray, count: Int) {
        if (failed || count <= 0) return
        try {
            var offset = 0
            while (offset < count) {
                val seg = segment ?: return
                val roomInChunk = (seg.startSample + chunkSamples - totalSamples).coerceAtLeast(0L)
                if (roomInChunk == 0L) {
                    rotate()
                    continue
                }
                val n = minOf((count - offset).toLong(), roomInChunk).toInt()
                seg.feed(samples, offset, n)
                totalSamples += n
                offset += n
            }
        } catch (_: Exception) {
            failed = true
        }
    }

    /**
     * Rotația: bucata nouă se deschide imediat (aici, pe firul audio — un encoder nou), iar cea veche
     * se închide pe [closer] (EOS, golire, index MP4) și abia apoi intră în manifest.
     */
    private fun rotate() {
        val old = segment ?: return
        segment = Segment(old.index + 1, totalSamples)
        closer.execute {
            old.close()
            val c = old.toChunk()
            chunks += c
            writeManifest(closed = false)
            onChunkClosed?.invoke(c, old.file)
        }
    }

    /**
     * STOP: închide ultima bucată, așteaptă închiderile în curs și scrie manifestul final (`closed`).
     * Durează până la câteva secunde — de apelat de pe un fir de fundal, nu de pe cel principal.
     */
    fun stop() {
        val seg = synchronized(this) { val s = segment; segment = null; s }   // de aici, feed() nu mai scrie nimic
        if (seg != null) try { seg.close() } catch (_: Exception) { }
        var lastChunk: Chunk? = null
        try {
            closer.submit {
                if (seg != null) {
                    if (seg.samples > 0) lastChunk = seg.toChunk().also { chunks += it }
                    else seg.file.delete()
                }
                writeManifest(closed = true)
            }.get(15, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        lastChunk?.let { onChunkClosed?.invoke(it, seg!!.file) }
        closer.shutdown()
    }

    /** Doar de pe firul [closer] (sau din constructor, înainte de orice bucată). */
    private fun writeManifest(closed: Boolean) {
        try {
            val m = Manifest(sessionId, startedAt, sampleRate, chunks.toList(), closed)
            File(dir, MANIFEST).writeText(json.encodeToString(Manifest.serializer(), m))
        } catch (_: Exception) { }
    }

    /** Un fișier .m4a: encoder AAC-LC 48 kbps mono + muxer MP4, cu propriul ceas de prezentare. */
    private inner class Segment(val index: Int, val startSample: Long) {
        val file = File(dir, "chunk_$index.m4a")
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private var trackIndex = -1
        private var muxerStarted = false
        private var presentationUs = 0L
        private val bufferInfo = MediaCodec.BufferInfo()
        var samples = 0L
            private set

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        fun feed(src: ShortArray, start: Int, count: Int) {
            var offset = start
            val end = start + count
            while (offset < end) {
                val inIndex = codec.dequeueInputBuffer(0)
                if (inIndex < 0) { drain(); continue }
                val inBuf: ByteBuffer = codec.getInputBuffer(inIndex) ?: break
                inBuf.clear()
                val maxShorts = inBuf.remaining() / 2
                val n = minOf(maxShorts, end - offset)
                for (i in 0 until n) {
                    val s = src[offset + i].toInt()
                    inBuf.put((s and 0xFF).toByte())
                    inBuf.put((s shr 8 and 0xFF).toByte())
                }
                codec.queueInputBuffer(inIndex, 0, n * 2, presentationUs, 0)
                presentationUs += n * 1_000_000L / sampleRate
                samples += n
                offset += n
                drain()
            }
        }

        private fun drain() {
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        if (eos) return
                    }
                    else -> return
                }
            }
        }

        /** EOS + golire completă, apoi eliberare. Fișierul rămâne valid chiar dacă ceva aruncă. */
        fun close() {
            try {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainUntilEos()
            } catch (_: Exception) { }
            try { codec.stop(); codec.release() } catch (_: Exception) { }
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) { }
        }

        private fun drainUntilEos() {
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        if (eos) return
                    }
                    else -> { /* INFO_TRY_AGAIN_LATER: mai așteptăm */ }
                }
            }
        }

        fun toChunk() = Chunk(
            index = index,
            file = file.name,
            from = startSample * 1000L / sampleRate,
            dur = samples * 1000L / sampleRate
        )
    }

    companion object {
        const val DEFAULT_CHUNK_MS = 30L * 60_000L
        /** Bucăți de 10 s pentru urcarea în timp real. */
        const val REALTIME_CHUNK_MS = 10_000L
        const val MANIFEST = "chunks.json"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        /** Dosarul unei sesiuni: `filesDir/sleep_full/<id>/`. */
        fun sessionDir(filesDir: File, sessionId: Long): File = File(File(filesDir, "sleep_full"), "$sessionId")

        /**
         * Bucățile unei sesiuni, în ordine. Sesiunile vechi (un singur `sleep_full/<id>.m4a`) se
         * întorc ca bucata 0 (`from` 0, `dur` 0 = necunoscut) — redarea lor rămâne.
         */
        fun manifestFor(filesDir: File, sessionId: Long, sessionStartAt: Long): Manifest? {
            val dir = sessionDir(filesDir, sessionId)
            val mf = File(dir, MANIFEST)
            if (mf.exists()) {
                try {
                    val m = json.decodeFromString(Manifest.serializer(), mf.readText())
                    if (m.chunks.isNotEmpty()) return m
                } catch (_: Exception) { }
            }
            val legacy = File(File(filesDir, "sleep_full"), "$sessionId.m4a")
            if (legacy.exists() && legacy.length() > 4000) {
                return Manifest(sessionId, sessionStartAt, 32000, listOf(Chunk(0, legacy.name, 0L, 0L)), closed = true)
            }
            return null
        }

        /** Fișierul local al unei bucăți (în dosarul sesiunii, sau fișierul vechi unic). */
        fun chunkFile(filesDir: File, sessionId: Long, chunk: Chunk): File {
            val inDir = File(sessionDir(filesDir, sessionId), chunk.file)
            if (inDir.exists()) return inDir
            return File(File(filesDir, "sleep_full"), chunk.file)
        }
    }
}
