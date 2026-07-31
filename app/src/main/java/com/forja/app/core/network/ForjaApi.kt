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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
            client.newCall(req).execute().use { resp ->
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
        } catch (_: Exception) {
            MealResult.Fail("Serverul FORJA nu răspunde. Verifică internetul.")
        }
    }

    data class AudioVerdict(val type: String, val words: Int)

    /** Clip de somn (WAV 5s) → Whisper pe server: vorbire reală vs sforăit. */
    suspend fun classifySleepAudio(wavBytes: ByteArray): AudioVerdict? = withContext(Dispatchers.IO) {
        val token = idToken() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("$base/v1/sleep-audio")
                .header("Authorization", "Bearer $token")
                .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: return@withContext null).jsonObject
                val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                val words = root["words"]?.jsonPrimitive?.intOrNull ?: 0
                AudioVerdict(type, words)
            }
        } catch (_: Exception) { null }
    }
}
