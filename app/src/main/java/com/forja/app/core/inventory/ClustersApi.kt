package com.forja.app.core.inventory

import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupOnlineSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cine are voie să trimită la AI: serverul FORJA configurat, „Sugestii AI” pornit (CleanupOnlineSettings, implicit
 * pornit) și un cont conectat (token Firebase). Altfel inventarul merge mai departe cu numele de rezervă.
 */
internal object AiGate {
    /** Fără token (rapid) — pentru estimări și ponderi. */
    suspend fun likely(app: ForjaApp): Boolean =
        BuildConfig.FORJA_API_URL.isNotBlank() && app.auth.currentUid != null && CleanupOnlineSettings(app).aiOnNow()

    /** „Bearer <token>” sau null când AI-ul nu e disponibil. */
    suspend fun auth(app: ForjaApp): String? =
        if (BuildConfig.FORJA_API_URL.isBlank() || !CleanupOnlineSettings(app).aiOnNow()) null else app.forjaApi.authHeader()
}

/**
 * Clientul POST {FORJA_API_URL}/v1/organize/clusters (DESIGN-4.3 §3): ≤ 4 grupuri, ≤ 24 de miniaturi (fiecare ≤ 81 920
 * caractere base64), corp < 6 MB. Un apel poate dura ~70 s pe server, deci răbdăm până la 120 s. Autentificare ca la
 * ForjaApi: token Firebase în „Authorization: Bearer …”. Anularea corutinei oprește cererea.
 */
internal object ClustersApi {
    @Serializable
    data class ClusterIn(
        val id: String,
        val count: Int,
        val from: Long,
        val to: Long,
        val loc: String? = null,
        val hints: List<String> = emptyList(),
        val thumbs: List<String> = emptyList()
    )

    @Serializable
    data class ClustersRequest(val clusters: List<ClusterIn>, val locale: String = "ro")

    @Serializable
    data class ClusterOut(
        val id: String = "",
        val nume: String = "",
        val tema: String = "",
        val categorie: String = "",
        val pastrare: String = "",
        val motiv: String = ""
    )

    @Serializable
    data class ClustersResponse(val clusters: List<ClusterOut> = emptyList(), val provider: String = "", val model: String = "")

    sealed class Outcome {
        data class Ok(val response: ClustersResponse) : Outcome()
        /** [code] = codul HTTP, 0 = rețea / timp depășit / răspuns ilizibil. */
        data class Fail(val code: Int, val message: String) : Outcome()
    }

    const val MAX_BODY = 6_000_000
    private val JSON_TYPE = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val url: String get() = BuildConfig.FORJA_API_URL.trimEnd('/') + "/v1/organize/clusters"

    /** Nu merită reîncercat: cont nerecunoscut, rută lipsă (server vechi), cerere respinsă ca atare. */
    fun fatal(code: Int): Boolean = code == 401 || code == 403 || code == 404
    fun retryable(code: Int): Boolean = code == 0 || code == 408 || code == 429 || code >= 500

    suspend fun name(auth: String, clusters: List<ClusterIn>): Outcome = withContext(Dispatchers.IO) {
        // Siguranță: miniaturile peste limita serverului cad; dacă tot corpul depășește 6 MB, cad de la coadă.
        var list = clusters.map { c -> c.copy(thumbs = c.thumbs.filter { it.length <= InvRules.THUMB_MAX_B64 }.take(InvRules.REPS_PER_CLUSTER)) }
        var body = json.encodeToString(ClustersRequest.serializer(), ClustersRequest(list))
        while (body.length > MAX_BODY && list.any { it.thumbs.size > 1 }) {
            list = list.map { c -> if (c.thumbs.size > 1) c.copy(thumbs = c.thumbs.dropLast(1)) else c }
            body = json.encodeToString(ClustersRequest.serializer(), ClustersRequest(list))
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        try {
            client.newCall(req).await().use { resp ->
                val text = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
                if (!resp.isSuccessful) {
                    val msg = try {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    } catch (_: Exception) { null }
                    return@withContext Outcome.Fail(resp.code, msg ?: "Serverul FORJA a răspuns cu ${resp.code}.")
                }
                try {
                    Outcome.Ok(json.decodeFromString(ClustersResponse.serializer(), text))
                } catch (_: Exception) {
                    Outcome.Fail(0, "Răspuns ilizibil de la server.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Fail(0, if (e is java.io.InterruptedIOException) "Numele durează prea mult." else "Serverul nu răspunde.")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { this@await.cancel() }
    }
}
