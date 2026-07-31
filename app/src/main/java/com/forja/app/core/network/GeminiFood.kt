package com.forja.app.core.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** O componentă din farfurie, estimată de AI — mereu editabilă. */
@Serializable
data class FoodComponent(
    val nume: String = "",
    val grame: Int = 0,
    val kcal: Int = 0,
    val proteine: Int = 0,
    val carbo: Int = 0,
    val grasimi: Int = 0
)

@Serializable
data class MealAnalysis(
    val fel: String = "",
    val incredere: String = "medie",
    val componente: List<FoodComponent> = emptyList()
)

/**
 * Analiza pozelor cu mâncare prin Google Gemini (cheia utilizatorului, nivel gratuit).
 * Principiu FORJA: estimarea e a AI-ului, decizia e a ta — totul editabil.
 */
class GeminiFood {
    private val client = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class Result {
        data class Ok(val analysis: MealAnalysis) : Result()
        data class Fail(val message: String) : Result()
    }

    suspend fun analyze(apiKey: String, jpegBytes: ByteArray): Result = withContext(Dispatchers.IO) {
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        var lastError = "Serverul AI nu a răspuns."
        for (model in models) {
            try {
                val body = buildJsonObject {
                    putJsonArray("contents") {
                        add(buildJsonObject {
                            putJsonArray("parts") {
                                add(buildJsonObject {
                                    put(
                                        "text",
                                        "Analizează fotografia unei mese. Răspunde DOAR cu JSON valid, fără alt text, cu structura exactă: " +
                                            "{\"fel\":\"numele scurt al felului în română\",\"incredere\":\"ridicată|medie|scăzută\"," +
                                            "\"componente\":[{\"nume\":\"...\",\"grame\":0,\"kcal\":0,\"proteine\":0,\"carbo\":0,\"grasimi\":0}]}. " +
                                            "Descompune farfuria pe componente vizibile (nu un singur fel!), estimează gramaje realiste pentru porția din imagine " +
                                            "și calculează kcal și macronutrienții per componentă la gramajul estimat. " +
                                            "Ține cont de blind spots: grăsimi de gătit invizibile → încredere scăzută. " +
                                            "Dacă imaginea nu conține mâncare, întoarce {\"fel\":\"\",\"incredere\":\"scăzută\",\"componente\":[]}."
                                    )
                                })
                                add(buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", "image/jpeg")
                                        put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                                    }
                                })
                            }
                        })
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", 0.2)
                        put("response_mime_type", "application/json")
                    }
                }.toString()

                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        lastError = when (resp.code) {
                            400, 403 -> "Cheia AI pare invalidă. Verific-o în Profil → Cheie AI."
                            404 -> "Modelul $model nu e disponibil — încerc altul."
                            429 -> "Limita gratuită atinsă pentru azi. Încearcă mai târziu."
                            else -> "Serverul AI a răspuns cu ${resp.code}."
                        }
                        return@use
                    }
                    val root = json.parseToJsonElement(text).jsonObject
                    val out = root["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: run { lastError = "Răspuns AI gol."; return@use }
                    val cleaned = out.trim()
                        .removePrefix("```json").removePrefix("```")
                        .removeSuffix("```").trim()
                    val analysis = json.decodeFromString<MealAnalysis>(cleaned)
                    if (analysis.componente.isEmpty()) {
                        lastError = "N-am recunoscut mâncare în poză. Încearcă un unghi de sus, cu lumină."
                        return@use
                    }
                    return@withContext Result.Ok(analysis)
                }
            } catch (e: Exception) {
                lastError = "Fără conexiune sau răspuns neașteptat de la AI."
            }
        }
        Result.Fail(lastError)
    }
}
