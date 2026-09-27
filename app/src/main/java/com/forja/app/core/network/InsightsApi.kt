package com.forja.app.core.network

import com.forja.app.BuildConfig
import com.forja.app.core.data.Prefs
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Eroare de la site (cod HTTP + mesajul serverului, dacă există). */
class InsightsFailure(val code: Int, message: String) : java.io.IOException(message)

/**
 * Clientul panoului online FORJA (worker-ul forja-insights): același cont Firebase, token Bearer pe
 * fiecare cerere. JSON in/out sau octeți bruți cu antete proprii (copii de fișiere, miniaturi).
 */
object InsightsApi {
    val base: String get() = BuildConfig.INSIGHTS_URL.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** Tokenul contului curent sau null dacă nu e nimeni conectat. */
    suspend fun token(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (_: Exception) { null }

    /** Identitatea acestui telefon față de site — UUID creat o singură dată. */
    fun deviceId(prefs: Prefs): suspend () -> String = {
        val existing = prefs.exploreDeviceId.first()
        if (existing.isNotBlank()) existing else UUID.randomUUID().toString().also { prefs.setExploreDeviceId(it) }
    }

    /**
     * Cerere JSON. body == null → GET (sau metoda dată). Aruncă [InsightsFailure] la răspuns non-2xx
     * (mesajul din {"error":…} când există) sau la lipsa contului (cod 401).
     */
    suspend fun json(
        path: String,
        body: JsonElement? = null,
        method: String = if (body == null) "GET" else "POST",
        headers: Map<String, String> = emptyMap()
    ): JsonObject = withContext(Dispatchers.IO) {
        val t = token() ?: throw InsightsFailure(401, "Conectează-te în FORJA.")
        val b = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(base + (if (path.startsWith("/")) path else "/$path"))
            .header("Authorization", "Bearer $t")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .method(method, if (method == "GET" || method == "HEAD") null else (b ?: ByteArray(0).toRequestBody(null)))
            .build()
        execute(req)
    }

    /** Octeți bruți (PUT/POST) cu antete proprii — copii de fișiere, miniaturi, înregistrări. */
    suspend fun upload(
        path: String,
        bytes: ByteArray,
        mediaType: String,
        method: String = "PUT",
        headers: Map<String, String> = emptyMap()
    ): JsonObject = withContext(Dispatchers.IO) {
        val t = token() ?: throw InsightsFailure(401, "Conectează-te în FORJA.")
        val req = Request.Builder()
            .url(base + (if (path.startsWith("/")) path else "/$path"))
            .header("Authorization", "Bearer $t")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .method(method, bytes.toRequestBody(mediaType.toMediaType()))
            .build()
        execute(req)
    }

    private fun execute(req: Request): JsonObject {
        client.newCall(req).execute().use { resp ->
            val text = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
            val parsed: JsonObject = try {
                if (text.isBlank()) buildJsonObject { } else json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) { buildJsonObject { } }
            if (!resp.isSuccessful) {
                val msg = parsed["error"]?.jsonPrimitive?.contentOrNull ?: "Serverul FORJA a răspuns cu ${resp.code}."
                throw InsightsFailure(resp.code, msg)
            }
            return parsed
        }
    }

    /** Antet HTTP sigur pentru nume de fișiere (URL-encoded, spațiul ca %20). */
    fun header(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
