package com.forja.app.core.network

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
    val incredere: String? = null
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
                    incredere = o.str("incredere")
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
        data class Fail(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val available: Boolean get() = forjaApi.available
    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    suspend fun analyze(jpegBytes: ByteArray, onStage: (Stage) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val auth = forjaApi.authHeader() ?: return@withContext Result.Fail("Intră în cont ca să folosești analiza cu model.")
        try {
            val payload = buildJsonObject {
                put("image", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
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
                    val msg = try {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    } catch (_: Exception) { null }
                    return@withContext Result.Fail(msg ?: "Serverul FORJA a răspuns cu ${resp.code}.")
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
                else "Serverul FORJA nu răspunde. Verifică internetul."
            )
        }
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
