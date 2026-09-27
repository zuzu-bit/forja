package com.forja.app.core.network

import com.forja.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ═══════════════ Curățenie v2 — /v1/organize cu PDF-uri și verdicte cu motiv (SPEC-ai §3) ═══════════════
// Pleacă miniaturi ≤ 512 px pentru poze, PDF-ul întreg (≤ 4 MB) pentru PDF-uri, un fragment ≤ 2000 caractere
// pentru documente text. Serverul răspunde per element cu keep/delete/move + rezumat, categorie, dosar,
// ștergere recomandată (motiv + încredere) și duplicatDe. Nimic nu se șterge de la sine.

@Serializable
data class OrganizeItemV2(
    val id: String,                 // "m:<mediaId>" sau "d:<sha256 al uri-ului documentului>"
    val kind: String,               // "image" | "document"
    val name: String,
    val size: Long,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val bucket: String? = null,
    val takenAt: Long? = null,
    val thumbnail: String? = null,  // JPEG base64 ≤ 512 px (doar poze)
    val text: String? = null,       // documente text: fragment ≤ 2000 caractere
    val pdfB64: String? = null,     // PDF întreg, base64, ≤ 4 MB (≤ 6 per cerere)
    val localHints: List<String> = emptyList()
) {
    val isPdf: Boolean get() = pdfB64 != null
    /** Mărimea aproximativă în corpul JSON (base64 + text + metadate). */
    val approxBytes: Int get() = (thumbnail?.length ?: 0) + (pdfB64?.length ?: 0) + (text?.length ?: 0) + 400
}

@Serializable
data class OrganizeDeleteV2(
    val recomandat: Boolean = false,
    val motiv: String = "",
    val incredere: String = ""      // "ridicată" | "medie" | "scăzută"
)

@Serializable
data class OrganizeVerdictV2(
    val id: String,
    val suggestion: String = "keep",   // "keep" | "delete" | "move" (ca în v1)
    val folder: String? = null,        // v1: dosar relativ
    val reason: String = "",
    val confidence: String = "medie",
    val rezumat: String = "",          // v2: o linie — ce e poza/documentul
    val categorie: String = "",
    val dosar: String = "",            // v2: nume sugestiv de dosar, ≤ 24 caractere
    val sterge: OrganizeDeleteV2? = null,
    val duplicatDe: String? = null
) {
    /** Ștergerea e doar o recomandare: pre-bifează, nu șterge. */
    val deleteRecommended: Boolean get() = suggestion == "delete" || sterge?.recomandat == true

    /** Dosarul propus: v2 `dosar` când există, altfel `folder` din v1. */
    val targetFolder: String? get() = dosar.trim().ifBlank { folder?.trim() }?.takeIf { it.isNotBlank() }

    /** Motivul afișat: al ștergerii când e recomandată, altfel motivul general. */
    val displayReason: String
        get() = (if (deleteRecommended) sterge?.motiv?.ifBlank { null } ?: reason else reason).trim()

    val displayConfidence: String
        get() = (if (deleteRecommended) sterge?.incredere?.ifBlank { null } ?: confidence else confidence).trim()

    /** Linia din UI: „AI: <rezumat> · dosar: <nume> · <motiv>". */
    fun line(): String = buildString {
        append("AI: ")
        val what = rezumat.trim().ifBlank {
            when {
                deleteRecommended -> "de aruncat"
                targetFolder != null -> "de mutat"
                else -> "păstrează"
            }
        }
        append(what)
        targetFolder?.let { append(" · dosar: ").append(it) }
        if (deleteRecommended && !rezumat.contains("arunc", ignoreCase = true)) append(" · de aruncat")
        val r = displayReason
        if (r.isNotBlank()) append(" · ").append(r)
        if (deleteRecommended && displayConfidence.isNotBlank()) append(" (").append(displayConfidence).append(")")
    }
}

@Serializable
data class OrganizeResponseV2(
    val items: List<OrganizeVerdictV2> = emptyList(),
    val summary: String = "",
    val provider: String = "",
    val model: String = "",
    val partial: Boolean = false
)

@Serializable
data class OrganizeRequestV2(val items: List<OrganizeItemV2>, val locale: String = "ro", val version: Int = 2)

/** Numele scurt al modelului care a răspuns, pentru linia „Modelul a răspuns (Claude)". */
fun providerLabel(provider: String, model: String = ""): String {
    val p = "$provider $model".lowercase()
    return when {
        "claude" in p || "anthropic" in p -> "Claude"
        "gemini" in p -> "Gemini"
        "gpt" in p || "openai" in p -> "OpenAI"
        "@cf" in p || "workers" in p || "llama" in p || "llava" in p || "cloudflare" in p -> "Cloudflare"
        provider.isBlank() -> "model"
        else -> provider.trim()
    }
}

/**
 * Clientul /v1/organize v2. Loturi: ≤ 24 poze, ≤ 6 PDF-uri, ≤ 30 elemente și ≤ 6 MB per cerere.
 * Nu are chei: se autentifică cu contul (token Firebase) prin ForjaApi.authHeader().
 */
class OrganizeApi(private val forja: ForjaApi) {
    private val client = OkHttpClient.Builder()
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    val available: Boolean get() = forja.available
    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    sealed class Result {
        data class Ok(val response: OrganizeResponseV2) : Result()
        data class Fail(val message: String) : Result()
    }

    /**
     * Trimite elementele în loturi și adună verdictele. `onProgress` primește linii oneste:
     * „Trimit 24 poze la analiză…", „Modelul a răspuns (Claude)".
     */
    suspend fun organize(items: List<OrganizeItemV2>, onProgress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.Fail("Nimic de trimis la analiză.")
        if (!available) return@withContext Result.Fail("Serverul FORJA nu e configurat în această versiune; sugestiile locale rămân.")
        val auth = forja.authHeader() ?: return@withContext Result.Fail("Intră în cont ca modelul să vadă pozele. Sugestiile locale rămân.")
        val batches = batches(items)
        val merged = ArrayList<OrganizeVerdictV2>()
        var summary = ""
        var provider = ""
        var model = ""
        var partial = false
        var failure: String? = null
        batches.forEachIndexed { index, batch ->
            onProgress(sendingLine(batch, index, batches.size))
            val body = encode(batch)
            try {
                val req = Request.Builder()
                    .url("$base/v1/organize")
                    .header("Authorization", auth)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        val msg = try {
                            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                        } catch (_: Exception) { null }
                        failure = when {
                            !msg.isNullOrBlank() -> "$msg Sugestiile locale rămân."
                            resp.code == 401 || resp.code == 403 -> "Contul nu a fost recunoscut de server. Intră din nou în cont; sugestiile locale rămân."
                            else -> "Serverul FORJA a răspuns cu ${resp.code}; sugestiile locale rămân."
                        }
                        partial = true
                    } else {
                        val r = json.decodeFromString(OrganizeResponseV2.serializer(), text)
                        merged += r.items
                        if (summary.isBlank() && r.summary.isNotBlank()) summary = r.summary
                        if (provider.isBlank() && r.provider.isNotBlank()) provider = r.provider
                        if (model.isBlank() && r.model.isNotBlank()) model = r.model
                        if (r.partial) partial = true
                        onProgress("Modelul a răspuns (${providerLabel(r.provider, r.model)})" + if (batches.size > 1) " · lot ${index + 1} din ${batches.size}" else "")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = if (e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException)
                    "Analiza durează prea mult; sugestiile locale rămân. Mai încearcă o dată."
                else "Serverul nu răspunde; sugestiile locale rămân."
                partial = true
            }
        }
        val fail = failure
        if (merged.isEmpty() && fail != null) Result.Fail(fail)
        else Result.Ok(OrganizeResponseV2(merged, summary, provider, model, partial))
    }

    private fun sendingLine(batch: List<OrganizeItemV2>, index: Int, total: Int): String {
        val images = batch.count { it.kind == "image" }
        val pdfs = batch.count { it.isPdf }
        val docs = batch.size - images
        val what = when {
            images > 0 && docs == 0 -> "$images ${if (images == 1) "poză" else "poze"}"
            docs > 0 && images == 0 -> "$docs ${if (docs == 1) "fișier" else "fișiere"}" + (if (pdfs > 0) " ($pdfs PDF)" else "")
            else -> "${batch.size} elemente"
        }
        val lot = if (total > 1) " · lot ${index + 1} din $total" else ""
        return "Trimit $what la analiză…$lot"
    }

    /** Loturi în ordinea primită: ≤ 24 poze, ≤ 6 PDF-uri, ≤ 30 elemente, ≤ 6 MB estimat. */
    internal fun batches(items: List<OrganizeItemV2>): List<List<OrganizeItemV2>> {
        val out = ArrayList<List<OrganizeItemV2>>()
        var cur = ArrayList<OrganizeItemV2>()
        var bytes = 0
        var images = 0
        var pdfs = 0
        for (raw in items) {
            // Un PDF care nu încape nici singur pleacă doar cu nume + metadate.
            val item = if (raw.approxBytes > MAX_BODY) raw.copy(pdfB64 = null, thumbnail = null) else raw
            val fits = cur.isNotEmpty() &&
                cur.size < MAX_ITEMS &&
                (item.kind != "image" || images < MAX_IMAGES) &&
                (!item.isPdf || pdfs < MAX_PDFS) &&
                bytes + item.approxBytes <= MAX_BODY
            if (cur.isNotEmpty() && !fits) {
                out += cur; cur = ArrayList(); bytes = 0; images = 0; pdfs = 0
            }
            cur += item
            bytes += item.approxBytes
            if (item.kind == "image") images++
            if (item.isPdf) pdfs++
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun encode(batch: List<OrganizeItemV2>): String =
        json.encodeToString(OrganizeRequestV2.serializer(), OrganizeRequestV2(batch))

    companion object {
        const val MAX_IMAGES = 24
        const val MAX_PDFS = 6
        const val MAX_ITEMS = 30
        const val MAX_BODY = 6_000_000
        const val MAX_PDF_BYTES = 4L * 1024 * 1024
    }
}
