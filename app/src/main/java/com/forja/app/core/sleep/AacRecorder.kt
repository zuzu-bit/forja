package com.forja.app.core.sleep

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * PCM → AAC (.m4a) în timp real: înregistrarea completă a nopții,
 * comprimată (~20 MB / 8h la 48 kbps), scrisă local și urcată apoi
 * în stocarea companiei cu ștergere automată la 24h.
 */
class AacRecorder(private val sampleRate: Int, outFile: File) {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    var failed = false
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
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Trimite un bloc de PCM (short-uri mono). Sigur la apeluri repetate de pe firul audio. */
    fun feed(samples: ShortArray, count: Int) {
        if (failed) return
        try {
            var offset = 0
            while (offset < count) {
                val inIndex = codec.dequeueInputBuffer(0)
                if (inIndex < 0) { drain(); continue }
                val inBuf: ByteBuffer = codec.getInputBuffer(inIndex) ?: break
                inBuf.clear()
                val maxShorts = inBuf.remaining() / 2
                val n = minOf(maxShorts, count - offset)
                for (i in 0 until n) {
                    val s = samples[offset + i].toInt()
                    inBuf.put((s and 0xFF).toByte())
                    inBuf.put((s shr 8 and 0xFF).toByte())
                }
                codec.queueInputBuffer(inIndex, 0, n * 2, presentationUs, 0)
                presentationUs += n * 1_000_000L / sampleRate
                offset += n
                drain()
            }
        } catch (_: Exception) {
            failed = true
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
                    codec.releaseOutputBuffer(outIndex, false)
                }
                else -> return
            }
        }
    }

    fun stop() {
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain()
        } catch (_: Exception) { }
        try { codec.stop(); codec.release() } catch (_: Exception) { }
        try {
            if (muxerStarted) muxer.stop()
            muxer.release()
        } catch (_: Exception) { }
    }
}
