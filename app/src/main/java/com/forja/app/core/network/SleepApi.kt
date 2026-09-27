package com.forja.app.core.network

import com.forja.app.BuildConfig
import com.forja.app.core.sleep.AacRecorder
import com.forja.app.core.sleep.SleepTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Somnul pe server (SPEC-ai §4) — separat de [ForjaApi], care rămâne neatins; refolosește `authHeader()`.
 *
 *  · `PUT  /v1/sleep-chunk?session=s<id>&index=<i>&from=<ms>&dur=<ms>`  corp audio/mp4 ≤ 25 MB
 *  · `GET  /v1/sleep-chunk?session=s<id>&index=<i>`                       redare (cu Range)
 *  · `POST /v1/sleep-analyze {session, chunks:[{index,from,dur}]}`        → cronologie | {status:"processing"} | {status:"clips_only", motiv}
 *  · `GET  /v1/sleep-analysis?session=s<id>`                              → JSON-ul salvat (sau processing)
 *  · `POST /v1/sleep-summary {…cifre, timeline:{stats, quotes}}`          → {summary}
 */
class SleepApi(private val api: ForjaApi) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Urcările de ~10 MB și analiza pe server cer răbdare. */
    private val longClient = OkHttpClient.Builder()
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val available: Boolean get() = api.available
    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    /** Identitatea sesiunii pe server — aceeași convenție ca la `/v1/sleep-recording`. */
    fun serverSession(sessionId: Long): String = "s$sessionId"

    suspend fun authHeader(): String? = api.authHeader()

    /** Rezultatul unei urcări: ok, sau motivul (pentru reîncercare ori renunțare). */
    sealed class Upload {
        object Ok : Upload()
        /** Serverul a refuzat definitiv (4xx, în afară de 401/408/429) — nu reîncercăm bucata. */
        data class Rejected(val code: Int) : Upload()
        /** Rețea / 5xx / fără token — merită reîncercat mai târziu. */
        data class Retry(val why: String) : Upload()
    }

    /** O bucată de noapte → R2. Idempotent pe (session, index): a doua urcare o înlocuiește pe prima. */
    suspend fun uploadChunk(sessionId: Long, chunk: AacRecorder.Chunk, file: File): Upload = withContext(Dispatchers.IO) {
        val auth = api.authHeader() ?: return@withContext Upload.Retry("fără cont")
        if (!file.exists() || file.length() <= 0L) return@withContext Upload.Rejected(0)
        if (file.length() > MAX_CHUNK_BYTES) return@withContext Upload.Rejected(413)
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-chunk?session=${serverSession(sessionId)}&index=${chunk.index}&from=${chunk.from}&dur=${chunk.dur}")
                .header("Authorization", auth)
                .put(file.asRequestBody("audio/mp4".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Upload.Ok
                    resp.code in 500..599 || resp.code == 401 || resp.code == 408 || resp.code == 429 -> Upload.Retry("server ${resp.code}")
                    else -> Upload.Rejected(resp.code)
                }
            }
        } catch (e: Exception) {
            Upload.Retry(e.javaClass.simpleName)
        }
    }

    /** URL-ul de redare al unei bucăți (cere antetul Authorization). */
    fun chunkUrl(sessionId: Long, index: Int): String =
        "$base/v1/sleep-chunk?session=${serverSession(sessionId)}&index=$index"

    /**
     * Cere analiza întregii nopți. Întoarce cronologia normalizată (status done / processing / clips_only /
     * failed) sau null dacă serverul n-a putut fi întrebat (rețea, fără cont).
     */
    suspend fun analyze(sessionId: Long, chunks: List<AacRecorder.Chunk>, startedAt: Long): SleepTimeline? = withContext(Dispatchers.IO) {
        val auth = api.authHeader() ?: return@withContext null
        val body = buildJsonObject {
            put("session", serverSession(sessionId))
            putJsonArray("chunks") {
                chunks.forEach { c ->
                    add(buildJsonObject { put("index", c.index); put("from", c.from); put("dur", c.dur) })
                }
            }
        }.toString()
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-analyze")
                .header("Authorization", auth)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                parseAnalysis(resp.code, text, startedAt, chunks)
            }
        } catch (_: Exception) { null }
    }

    /** Sondaj: JSON-ul salvat pe server pentru sesiune. null = nu s-a putut întreba. */
    suspend fun analysis(sessionId: Long, startedAt: Long, chunks: List<AacRecorder.Chunk>): SleepTimeline? = withContext(Dispatchers.IO) {
        val auth = api.authHeader() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-analysis?session=${serverSession(sessionId)}")
                .header("Authorization", auth)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 404) return@withContext SleepTimeline("processing", startedAt = startedAt)
                parseAnalysis(resp.code, text, startedAt, chunks)
            }
        } catch (_: Exception) { null }
    }

    private fun parseAnalysis(code: Int, text: String, startedAt: Long, chunks: List<AacRecorder.Chunk>): SleepTimeline? {
        val chunkFrom = chunks.associate { it.index to it.from }
        val parsed = SleepTimeline.parseServer(text, startedAt, chunkFrom)
        if (code == 202) return (parsed ?: SleepTimeline("processing", startedAt = startedAt)).let {
            if (it.status == "done" && it.events.isEmpty() && it.stats.coverageMin == 0) it.copy(status = "processing") else it
        }
        if (code in 200..299) return parsed ?: SleepTimeline("failed", "răspuns de neînțeles", startedAt = startedAt)
        if (code in 500..599 || code == 429 || code == 408) return null   // reîncercăm
        val reason = parsed?.reason?.takeIf { it.isNotBlank() } ?: "serverul a răspuns cu $code"
        return SleepTimeline("failed", reason, startedAt = startedAt)
    }

    /**
     * Rezumatul de dimineață cu cronologia: cifrele nopții + statistici + până la 6 citate EXACTE.
     * Serverul răspunde `{summary}`; null dacă nu a putut.
     */
    suspend fun summaryWithTimeline(
        minutes: Int, score: Int, deepMin: Int, remMin: Int, movements: Int,
        snoreEvents: Int, talkEvents: Int, timeline: SleepTimeline
    ): String? = withContext(Dispatchers.IO) {
        val auth = api.authHeader() ?: return@withContext null
        val body = buildJsonObject {
            put("minutes", minutes); put("score", score)
            put("deepMin", deepMin); put("remMin", remMin)
            put("movements", movements); put("snoreEvents", snoreEvents); put("talkEvents", talkEvents)
            putJsonObject("timeline") {
                put("status", timeline.status)
                putJsonObject("stats") {
                    put("snoreMin", timeline.stats.snoreMin)
                    put("snoreEpisodes", timeline.stats.snoreEpisodes)
                    put("talkCount", timeline.stats.talkCount)
                    put("coughCount", timeline.stats.coughCount)
                    put("coverageMin", timeline.stats.coverageMin)
                    put("totalMin", timeline.stats.totalMin)
                }
                putJsonArray("quotes") { timeline.quotes(6).forEach { add(it) } }
                putJsonArray("events") {
                    timeline.events.take(40).forEach { e ->
                        add(buildJsonObject {
                            put("at", e.at); put("end", e.end); put("type", e.type)
                            put("intensity", e.intensity); put("transcript", e.transcript); put("confidence", e.confidence)
                        })
                    }
                }
            }
        }.toString()
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-summary")
                .header("Authorization", auth)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: return@withContext null).jsonObject
                root["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 25L * 1024 * 1024

        @Volatile private var cached: SleepApi? = null
        /** O singură instanță per proces (clienții OkHttp sunt scumpi). */
        fun get(api: ForjaApi): SleepApi = cached ?: synchronized(this) { cached ?: SleepApi(api).also { cached = it } }
    }
}
