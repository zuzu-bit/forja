package com.forja.app.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.forja.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Mese v2 (`POST /v1/meal`, SPEC-ai §2) — parserul tolerant.
 * Răspunsul v1 (fel/incredere/componente) merge în continuare: câmpurile noi lipsesc → widgeturile lor nu apar.
 * Nimic din ce vine aici nu e „măsurat”: totul e estimare, editabilă înainte de salvare.
 */

/** O componentă din farfurie, cu fibre și încredere proprii (v2); în v1 ambele lipsesc. */
@Serializable
data class MealItem(
    val nume: String = "",
    val grame: Int = 0,
    val kcal: Int = 0,
    val proteine: Int = 0,
    val carbo: Int = 0,
    val grasimi: Int = 0,
    val fibre: Int? = null,
    val incredere: String? = null,
    /** Opțional (v2+): dreptunghiul componentei în poză, normalizat 0..1 — [x, y, lățime, înălțime]. */
    val bbox: List<Float>? = null
) {
    fun toComponent() = FoodComponent(nume, grame, kcal, proteine, carbo, grasimi)

    /** Gramaj nou → toate valorile scalate proporțional (fibrele inclusiv). Baza 0 g → doar gramajul se schimbă. */
    fun scaledTo(newGrams: Int): MealItem {
        val g = newGrams.coerceAtLeast(0)
        if (grame <= 0) return copy(grame = g)
        val f = g.toDouble() / grame
        return copy(
            grame = g,
            kcal = (kcal * f).roundToInt(),
            proteine = (proteine * f).roundToInt(),
            carbo = (carbo * f).roundToInt(),
            grasimi = (grasimi * f).roundToInt(),
            fibre = fibre?.let { (it * f).roundToInt() }
        )
    }

    companion object {
        fun from(c: FoodComponent) = MealItem(c.nume, c.grame, c.kcal, c.proteine, c.carbo, c.grasimi)
    }
}

/** „Scor de rație” 1–10 + motivul într-o propoziție. */
@Serializable
data class MealScore(val valoare: Int, val motiv: String = "")

/**
 * Raportul complet al unei analize. `versiune` = 1 pentru răspunsurile vechi (sau cheia Gemini proprie),
 * 2 când serverul a trecut prin verificarea porțiilor. Câmpurile opționale sunt `null`/goale la v1.
 */
@Serializable
data class MealReport(
    val fel: String = "",
    val incredere: String = "medie",
    val componente: List<MealItem> = emptyList(),
    val scor: MealScore? = null,
    val sfat: String? = null,
    val observatii: List<String> = emptyList(),
    val portie: String? = null,
    val model: String? = null,
    val versiune: Int = 1
) {
    val isV2: Boolean get() = versiune >= 2
    val hasFibre: Boolean get() = componente.any { it.fibre != null }

    fun toAnalysis() = MealAnalysis(fel, incredere, componente.map { it.toComponent() })

    /** Eticheta onestă a sursei: numește familia de modele, niciodată „precizie”. */
    val sourceLabel: String
        get() {
            val m = (model ?: "").lowercase()
            return when {
                m.startsWith("claude") -> "ESTIMARE AI · CLAUDE"
                m.startsWith("gemini") -> "ESTIMARE AI · GEMINI"
                m.startsWith("gpt") || m.startsWith("o1") || m.startsWith("o3") -> "ESTIMARE AI · OPENAI"
                m.contains("llama") || m.startsWith("@cf") -> "ESTIMARE AI · MODEL DESCHIS"
                else -> "ESTIMARE AI"
            }
        }

    companion object {
        fun from(a: MealAnalysis, model: String? = null) =
            MealReport(a.fel, a.incredere, a.componente.map { MealItem.from(it) }, model = model, versiune = 1)

        /** Parsează v1 sau v2; câmpuri lipsă/greșite → valori implicite, nu excepții. */
        fun parse(root: JsonObject): MealReport {
            val items = (root["componente"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                MealItem(
                    nume = o.str("nume") ?: "",
                    grame = o.int("grame") ?: 0,
                    kcal = o.int("kcal") ?: 0,
                    proteine = o.int("proteine") ?: 0,
                    carbo = o.int("carbo") ?: 0,
                    grasimi = o.int("grasimi") ?: 0,
                    fibre = o.int("fibre"),
                    incredere = o.str("incredere"),
                    bbox = o.bbox("bbox")
                )
            } ?: emptyList()
            val scor = (root["scor"] as? JsonObject)?.let { s ->
                s.int("valoare")?.let { v -> MealScore(v.coerceIn(1, 10), s.str("motiv") ?: "") }
            }
            val obs = (root["observatii"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: emptyList()
            return MealReport(
                fel = root.str("fel") ?: "",
                incredere = root.str("incredere")?.takeIf { it.isNotBlank() } ?: "medie",
                componente = items,
                scor = scor,
                sfat = root.str("sfat")?.trim()?.takeIf { it.isNotEmpty() },
                observatii = obs,
                portie = root.str("portie")?.trim()?.takeIf { it.isNotEmpty() },
                model = root.str("model")?.takeIf { it.isNotBlank() },
                versiune = root.int("versiune") ?: 1
            )
        }

        private fun JsonObject.str(key: String): String? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            return p.contentOrNull
        }

        /** `bbox` ca listă [x,y,w,h] sau obiect {x,y,w,h}; valori > 1 se consideră procente. Orice altceva → null. */
        private fun JsonObject.bbox(key: String): List<Float>? {
            val el = this[key] ?: return null
            val nums: List<Float> = when (el) {
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
                is JsonObject -> listOf("x", "y", "w", "h").map { k -> (el[k] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: return null }
                else -> return null
            }
            if (nums.size != 4) return null
            val scaled = if (nums.any { it > 1f }) nums.map { it / 100f } else nums
            if (scaled.any { it < 0f || it > 1f } || scaled[2] <= 0f || scaled[3] <= 0f) return null
            return scaled
        }

        private fun JsonObject.int(key: String): Int? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            return p.intOrNull ?: p.doubleOrNull?.roundToInt() ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.roundToInt()
        }
    }
}

/** Verificarea de pe telefon: kcal ≈ 4P + 4C + 9G, cu toleranță. Onestă: doar semnalează, nu „repară”. */
object MealCheck {
    fun kcalFromMacros(p: Int, c: Int, g: Int): Int = 4 * p + 4 * c + 9 * g

    /** true când suma componentelor e coerentă cu macro-urile (±15 % sau ±40 kcal). */
    fun coherent(items: List<MealItem>): Boolean {
        val kcal = items.sumOf { it.kcal }
        val fromMacros = kcalFromMacros(items.sumOf { it.proteine }, items.sumOf { it.carbo }, items.sumOf { it.grasimi })
        if (kcal <= 0 && fromMacros <= 0) return true
        val tol = maxOf(40, (0.15 * maxOf(kcal, fromMacros)).roundToInt())
        return abs(kcal - fromMacros) <= tol
    }
}

/**
 * Clientul `/v1/meal` al serverului FORJA — separat de [ForjaApi], dar cu același token (`authHeader()`).
 * Pașii raportați prin [onStage] sunt reali: poza a plecat, răspunsul a venit.
 */
class MealApi(private val forjaApi: ForjaApi) {
    enum class Stage { UPLOADING, ANALYZING, DONE }

    sealed class Result {
        data class Ok(val report: MealReport) : Result()
        /** `message` e pentru om; `detail` e motivul exact al serverului (câmpul `detalii`), afișat mic dedesubt. */
        data class Fail(val message: String, val detail: String? = null) : Result()
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val available: Boolean get() = forjaApi.available
    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    /**
     * Trimite poza la `/v1/meal`. Poza e adusă la ≤ [MAX_SIDE] px, JPEG 80, înainte de plecare (mai puțini octeți,
     * același rezultat); `note` = tipul mesei („mic dejun”, „prânz”…) — un indiciu pentru model, nu o regulă.
     */
    suspend fun analyze(jpegBytes: ByteArray, note: String? = null, onStage: (Stage) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val auth = forjaApi.authHeader() ?: return@withContext Result.Fail("Intră în cont ca să folosești analiza cu model.")
        try {
            val bytes = shrink(jpegBytes)
            val payload = buildJsonObject {
                put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (!note.isNullOrBlank()) put("note", note)
            }.toString().toByteArray()
            onStage(Stage.UPLOADING)
            val body = NotifyingBody(payload, "application/json".toMediaType()) { onStage(Stage.ANALYZING) }
            val req = Request.Builder()
                .url("$base/v1/meal")
                .header("Authorization", auth)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val body = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
                    val msg = body?.get("error")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    val detail = body?.get("detalii")?.let { d ->
                        when (d) {
                            is JsonPrimitive -> d.contentOrNull
                            is JsonObject -> d.entries.joinToString(" · ") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
                            else -> d.toString()
                        }
                    }?.trim()?.takeIf { it.isNotEmpty() }
                    val human = when {
                        !msg.isNullOrBlank() -> msg
                        resp.code == 422 -> "N-am putut citi masa din poza asta."
                        resp.code in 500..599 -> "Serverul FORJA a avut o problemă. Nu e de la poza ta."
                        else -> "Serverul FORJA n-a acceptat cererea."
                    }
                    return@withContext Result.Fail(human, detail)
                }
                val root: JsonElement = json.parseToJsonElement(text)
                val report = MealReport.parse(root.jsonObject)
                onStage(Stage.DONE)
                if (report.componente.isEmpty()) {
                    return@withContext Result.Fail("N-am recunoscut mâncare în poză. Încearcă un cadru de sus, cu lumină.")
                }
                Result.Ok(report)
            }
        } catch (e: Exception) {
            Result.Fail(
                if (e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException)
                    "Analiza durează prea mult acum. Serverul e aglomerat. Mai încearcă o dată."
                else "Serverul FORJA nu răspunde. Verifică internetul.",
                e.message?.takeIf { it.isNotBlank() }
            )
        }
    }

    /** Latura mare ≤ [MAX_SIDE] px, JPEG calitate [JPEG_QUALITY]. Dacă decodarea pică, pleacă octeții originali. */
    private fun shrink(bytes: ByteArray): ByteArray = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) bytes
        else {
            var sample = 1
            while (w / (sample * 2) >= MAX_SIDE && h / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (decoded == null) bytes
            else {
                val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
                val bmp = if (scale < 1f) {
                    Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1), (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
                        .also { if (it !== decoded) decoded.recycle() }
                } else decoded
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bmp.recycle()
                out.toByteArray()
            }
        }
    } catch (_: Exception) { bytes }

    companion object {
        const val MAX_SIDE = 1280
        const val JPEG_QUALITY = 80
    }

    /** Corp de cerere care anunță când ultimul octet a plecat — de aici începe cu adevărat analiza. */
    private class NotifyingBody(
        private val bytes: ByteArray,
        private val type: MediaType,
        private val onSent: () -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
            sink.flush()
            onSent()
        }
    }
}
