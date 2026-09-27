package com.forja.app.core.network

import android.util.Base64
import android.util.Base64OutputStream
import com.forja.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ═══════════════ Curățenie v2 — /v1/organize cu PDF-uri și verdicte cu motiv (SPEC-ai §3) ═══════════════
// Pleacă miniaturi ≤ 512 px pentru poze, PDF-ul întreg (≤ 4 MB) pentru PDF-uri, un fragment ≤ 2000 caractere
// pentru documente text. Serverul răspunde per element cu keep/delete/move + rezumat, categorie, dosar,
// ștergere recomandată (motiv + încredere) și duplicatDe. Nimic nu se șterge de la sine.

/**
 * PDF citit abia la trimitere: octeții se codifică base64 direct în corpul cererii, unul câte unul, ca să nu stea
 * o rundă întreagă de PDF-uri ca text în memorie (pe un heap de 256 MB ar însemna o cădere). `size` e doar pentru
 * împărțirea în loturi; `read` întoarce null când fișierul nu se mai poate citi — atunci pleacă doar numele.
 */
class PdfSource(val size: Long, val read: () -> ByteArray?)

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
    val pdfB64: String? = null,     // PDF întreg, base64, ≤ 4 MB (≤ 6 per cerere) — gata codificat; pentru fișiere mari vezi `pdfSource`
    val localHints: List<String> = emptyList(),
    @Transient val pdfSource: PdfSource? = null   // PDF-ul citit și codificat la scrierea cererii (nu apare în JSON-ul din memorie)
) {
    val isPdf: Boolean get() = pdfB64 != null || pdfSource != null
    /** Mărimea aproximativă în corpul JSON (base64 + text + metadate). */
    val approxBytes: Int
        get() = (thumbnail?.length ?: 0) + (pdfB64?.length ?: 0) + (pdfSource?.let { (it.size * 4 / 3 + 4).toInt() } ?: 0) + (text?.length ?: 0) + 400
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
    val duplicatDe: String? = null     // v2: id-ul altui element din listă căruia îi e copie
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

    /**
     * Linia din UI: „AI: <rezumat> · dosar: <nume> · copie a <fișier> · <motiv> (încredere)".
     * `nameOf` traduce id-ul din `duplicatDe` în numele elementului din listă (null → „altui element din listă").
     */
    fun line(nameOf: (String) -> String? = { null }): String = buildString {
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
        // Se verifică textul afișat, nu câmpul brut: fără rezumat, „de aruncat" e deja pe linie.
        if (deleteRecommended && !what.contains("arunc", ignoreCase = true)) append(" · de aruncat")
        duplicatDe?.trim()?.takeIf { it.isNotBlank() && it != id }?.let { other ->
            append(" · copie a ").append(nameOf(other) ?: "altui element din listă")
        }
        val r = displayReason
        if (r.isNotBlank()) append(" · ").append(r)
        if (deleteRecommended && displayConfidence.isNotBlank()) append(" (").append(displayConfidence).append(")")
    }
}

@Serializable
data class OrganizeResponseV2(
    val items: List<OrganizeVerdictV2> = emptyList(),
    val summary: String = "",
    val provider: String = "",      // v2: „<furnizor>/<model>" („gemini/gemini-2.5-flash", „groq/meta-llama/…"); v1: doar furnizorul
    val model: String = "",         // doar serverele mai vechi îl trimit separat; v2 îl pune în `provider` (providerLabel le citește pe amândouă)
    val partial: Boolean = false,
    val note: String = ""           // pus de client: de ce lipsește o parte (lotul care a picat) — „doar o parte: Serverul nu răspunde"
)

@Serializable
data class OrganizeRequestV2(val items: List<OrganizeItemV2>, val locale: String = "ro", val version: Int = 2)

/**
 * Numele scurt al modelului care a răspuns, pentru linia „Modelul a răspuns (Gemini)". Întâi furnizorul din prefixul
 * lui `provider` (serverul v2 trimite „<furnizor>/<model>"), abia apoi numele modelului — altfel „llama" de la Groq
 * ar trece drept Cloudflare.
 */
fun providerLabel(provider: String, model: String = ""): String {
    val name = provider.substringBefore('/').trim().lowercase()
    val p = "$provider $model".lowercase()
    return when (name) {
        "anthropic", "claude" -> "Claude"
        "gemini", "google" -> "Gemini"
        "groq" -> "Groq"
        "openai" -> "OpenAI"
        "workers", "cloudflare", "workers-ai" -> "Cloudflare"
        else -> when {
            "claude" in p || "anthropic" in p -> "Claude"
            "gemini" in p -> "Gemini"
            "groq" in p -> "Groq"
            "gpt" in p || "openai" in p -> "OpenAI"
            "@cf" in p || "workers" in p || "llama" in p || "llava" in p || "cloudflare" in p -> "Cloudflare"
            name.isBlank() -> "model"
            else -> provider.substringBefore('/').trim()
        }
    }
}

/**
 * Clientul /v1/organize v2. Loturi: ≤ 24 poze, ≤ 6 PDF-uri, ≤ 30 elemente și ≤ 6 MB per cerere.
 * Nu are chei: se autentifică cu contul (token Firebase) prin ForjaApi.authHeader().
 */
class OrganizeApi(private val forja: ForjaApi) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    val available: Boolean get() = forja.available
    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    sealed class Result {
        data class Ok(val response: OrganizeResponseV2) : Result()
        data class Fail(val message: String) : Result()
    }

    /**
     * Trimite elementele în loturi și adună verdictele. `onProgress` primește linii oneste:
     * „Trimit 24 poze la analiză…", „Modelul a răspuns (Claude)". Anularea corutinei oprește cererea în curs
     * și loturile rămase. Când un lot pică după ce altul a răspuns, motivul ajunge în `note` („doar o parte: …").
     */
    suspend fun organize(items: List<OrganizeItemV2>, onProgress: suspend (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.Fail("Nimic de trimis la analiză.")
        if (!available) return@withContext Result.Fail("Serverul FORJA nu e configurat în această versiune; sugestiile locale rămân.")
        val subject = if (items.all { it.kind == "image" }) "pozele" else "fișierele"
        val auth = forja.authHeader() ?: return@withContext Result.Fail("Intră în cont ca modelul să vadă $subject. Sugestiile locale rămân.")
        val batches = batches(items)
        val merged = ArrayList<OrganizeVerdictV2>()
        var summary = ""
        var provider = ""
        var model = ""
        var partial = false
        var failure: String? = null   // mesajul întreg — când nu a venit niciun verdict
        var reason: String? = null    // motivul scurt — când a venit doar o parte
        batches.forEachIndexed { index, batch ->
            ensureActive()
            onProgress(sendingLine(batch, index, batches.size))
            try {
                val req = Request.Builder()
                    .url("$base/v1/organize")
                    .header("Authorization", auth)
                    .post(OrganizeBody(batch))
                    .build()
                client.newCall(req).await().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        val msg = try {
                            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                        } catch (_: Exception) { null }
                        when {
                            !msg.isNullOrBlank() -> {
                                reason = msg.trim().trimEnd('.')
                                failure = "$msg Sugestiile locale rămân."
                            }
                            resp.code == 401 || resp.code == 403 -> {
                                reason = "Contul nu a fost recunoscut de server"
                                failure = "Contul nu a fost recunoscut de server. Intră din nou în cont; sugestiile locale rămân."
                            }
                            else -> {
                                reason = "Serverul FORJA a răspuns cu ${resp.code}"
                                failure = "Serverul FORJA a răspuns cu ${resp.code}; sugestiile locale rămân."
                            }
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
                val timeout = e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException
                reason = if (timeout) "Analiza durează prea mult" else "Serverul nu răspunde"
                failure = if (timeout) "Analiza durează prea mult; sugestiile locale rămân. Mai încearcă o dată."
                else "Serverul nu răspunde; sugestiile locale rămân."
                partial = true
            }
        }
        val fail = failure
        if (merged.isEmpty() && fail != null) Result.Fail(fail)
        else Result.Ok(OrganizeResponseV2(merged, summary, provider, model, partial, note = if (merged.isNotEmpty()) reason ?: "" else ""))
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
            val item = if (raw.approxBytes > MAX_BODY) raw.copy(pdfB64 = null, pdfSource = null, thumbnail = null) else raw
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

    /** Cererea pleacă pe firele OkHttp și se anulează odată cu corutina (nu mai așteptăm un răspuns pe care nu-l mai folosim). */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) { response.close(); return }
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { this@await.cancel() }
    }

    /**
     * Corpul JSON scris direct în conexiune: metadatele fiecărui element ies din serializator, iar PDF-ul se citește
     * și se codifică base64 chiar la scriere, unul câte unul — în memorie stă cel mult un PDF (≤ 4 MB), nu tot lotul
     * ca text. Lungimea nu e cunoscută dinainte (HTTP/2 sau chunked); serverul verifică doar `content-length` declarat.
     */
    private inner class OrganizeBody(private val batch: List<OrganizeItemV2>) : RequestBody() {
        override fun contentType() = JSON_TYPE

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("{\"items\":[")
            batch.forEachIndexed { i, item ->
                if (i > 0) sink.writeUtf8(",")
                val bytes = item.pdfSource?.let { it.read() }
                if (bytes == null) {
                    sink.writeUtf8(json.encodeToString(OrganizeItemV2.serializer(), item))
                } else {
                    // Obiectul fără „}" final, apoi câmpul pdfB64 scris în bucăți prin codificatorul base64.
                    val head = json.encodeToString(OrganizeItemV2.serializer(), item.copy(pdfB64 = null))
                    sink.writeUtf8(head.dropLast(1)).writeUtf8(",\"pdfB64\":\"")
                    Base64OutputStream(sink.outputStream(), Base64.NO_WRAP or Base64.NO_CLOSE).use { b64 ->
                        var off = 0
                        while (off < bytes.size) {
                            val n = minOf(B64_CHUNK, bytes.size - off)
                            b64.write(bytes, off, n)
                            off += n
                        }
                    }
                    sink.writeUtf8("\"}")
                }
            }
            sink.writeUtf8("],\"locale\":\"ro\",\"version\":2}")
        }
    }

    companion object {
        const val MAX_IMAGES = 24
        const val MAX_PDFS = 6
        const val MAX_ITEMS = 30
        const val MAX_BODY = 6_000_000
        const val MAX_PDF_BYTES = 4L * 1024 * 1024
        private const val B64_CHUNK = 48 * 1024
        private val JSON_TYPE = "application/json".toMediaType()

        /** Un singur client (dispatcher + pool de conexiuni) pentru toate instanțele, ca la ForjaApi — nu unul per ViewModel. */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(300, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .build()
        }
    }
}
