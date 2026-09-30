package com.forja.app.core.voice

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Bounded, private JSONL: no audio, UI trees, account details, or recognized query text. */
object VoiceTelemetry {
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_EVENTS = 2_000
    private const val RETENTION_MILLIS = 14 * 24 * 60 * 60 * 1000L
    private val lock = Any()

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun record(
        context: Context,
        sessionId: String,
        stage: String,
        detail: String = "",
        reason: String? = null,
        failedStage: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()
    ): Boolean = synchronized(lock) {
        try {
            val file = journal(context)
            trim(file, timestampMillis)
            val event = JSONObject()
                .put("session_id", sessionId)
                .put("stage", stage.take(80))
                .put("timestamp", Instant.ofEpochMilli(timestampMillis).toString())
                .put("timestamp_ms", timestampMillis)
                .put("elapsed_realtime_ms", elapsedRealtimeMillis)
                .put("detail", detail.take(240))
            if (reason != null) event.put("reason", reason.take(160))
            if (failedStage != null) event.put("failed_stage", failedStage.take(80))
            file.appendText(event.toString() + "\n", Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Caller shares only this snapshot through the app's dedicated FileProvider path. */
    fun exportFile(context: Context): File = synchronized(lock) {
        val source = journal(context)
        trim(source, System.currentTimeMillis())
        val dir = File(context.filesDir, "voice-agent/export").apply { mkdirs() }
        File(dir, "forja-voice-telemetry.jsonl").apply {
            writeBytes(if (source.exists()) source.readBytes() else byteArrayOf())
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        journal(context).delete()
        File(context.filesDir, "voice-agent/export").deleteRecursively()
        Unit
    }

    private fun journal(context: Context): File =
        File(context.filesDir, "voice-agent").apply { mkdirs() }
            .resolve("events.jsonl")

    private fun trim(file: File, now: Long) {
        if (!file.exists()) return
        val cutoff = now - RETENTION_MILLIS
        // Maximum file size is small. Parsing also removes malformed entries after an abrupt kill.
        val current = file.readLines(Charsets.UTF_8)
        var kept = current.filter { line ->
            runCatching { JSONObject(line).optLong("timestamp_ms") >= cutoff }.getOrDefault(false)
        }.takeLast(MAX_EVENTS - 1)
        var bytes = kept.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }.toLong()
        while (bytes > MAX_BYTES - 2_048 && kept.isNotEmpty()) {
            bytes -= kept.first().toByteArray(Charsets.UTF_8).size + 1
            kept = kept.drop(1)
        }
        if (kept != current) {
            val temporary = File(file.parentFile, "events.jsonl.tmp")
            temporary.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
            check(temporary.renameTo(file)) { "Nu s-a putut actualiza jurnalul vocal." }
        }
    }
}
