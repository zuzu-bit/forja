package com.forja.app.core.sleep

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * „Noaptea, ascultată”: cronologia întoarsă de server (`POST /v1/sleep-analyze` / `GET /v1/sleep-analysis`),
 * NORMALIZATĂ și salvată în `filesDir/sleep_full/<id>/timeline.json`.
 *
 * Formatul salvat e al aplicației (câmpurile de mai jos). Răspunsul serverului se citește tolerant
 * (`parseServer`): evenimentele pot veni sub `events`/`timeline`, timpii sub `at`/`start`/`from`/`t`
 * (ms de la începutul audio-ului), textul sub `transcript`/`text`, iar statisticile sub `stats` sau
 * direct în rădăcină. Dacă serverul nu a putut asculta (fără cheie Gemini), `status` = `clips_only`
 * și `reason` spune de ce — ecranul o repetă onest, nu pretinde mai mult.
 */
@Serializable
data class SleepTimeline(
    /** done · processing · clips_only · failed · timeout · offline */
    val status: String,
    val reason: String = "",
    val events: List<Event> = emptyList(),
    val stats: Stats = Stats(),
    /** epoch ms al începutului audio (din manifest) — ora unui eveniment = startedAt + at */
    val startedAt: Long = 0L,
    val savedAt: Long = 0L,
    val provider: String = ""
) {
    /** Un eveniment auzit pe server. `at`/`end` în ms de la începutul audio-ului. */
    @Serializable
    data class Event(
        val at: Long,
        val end: Long = 0L,
        /** talk · snore · cough · noise · breath · silence */
        val type: String,
        val intensity: Double = 0.0,      // 0..1
        val transcript: String = "",       // EXACT ce s-a auzit, fără completări
        val confidence: Double = 0.0,      // 0..1
        val chunk: Int = -1
    ) {
        val durMs: Long get() = (end - at).coerceAtLeast(0L)
    }

    @Serializable
    data class Stats(
        val snoreMin: Int = 0,
        val snoreEpisodes: Int = 0,
        val talkCount: Int = 0,
        val coughCount: Int = 0,
        /** minute efectiv ascultate de server (acoperire) din totalul trimis */
        val coverageMin: Int = 0,
        val totalMin: Int = 0
    )

    val listened: Boolean get() = status == "done"

    /** Până la 6 fraze exacte, pentru rezumatul de dimineață. */
    fun quotes(limit: Int = 6): List<String> =
        events.filter { it.type == "talk" && it.transcript.isNotBlank() }.map { it.transcript }.take(limit)

    companion object {
        const val FILE = "timeline.json"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        fun save(dir: File, t: SleepTimeline) {
            try {
                dir.mkdirs()
                File(dir, FILE).writeText(json.encodeToString(serializer(), t.copy(savedAt = System.currentTimeMillis())))
            } catch (_: Exception) { }
        }

        fun load(dir: File): SleepTimeline? = try {
            val f = File(dir, FILE)
            if (f.exists()) json.decodeFromString(serializer(), f.readText()) else null
        } catch (_: Exception) { null }

        /** Bucata în care cade un moment (ms de la începutul audio-ului). Ultima bucată prinde și coada. */
        fun chunkFor(offsetMs: Long, chunks: List<AacRecorder.Chunk>): AacRecorder.Chunk? {
            if (chunks.isEmpty()) return null
            for (c in chunks) {
                val end = if (c.dur > 0) c.from + c.dur else Long.MAX_VALUE
                if (offsetMs >= c.from && offsetMs < end) return c
            }
            return if (offsetMs < chunks.first().from) chunks.first() else chunks.last()
        }

        // ───────────── citirea tolerantă a răspunsului serverului ─────────────

        private fun JsonObject.str(vararg keys: String): String? {
            for (k in keys) (this[k] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
            return null
        }
        private fun JsonObject.num(vararg keys: String): Double? {
            for (k in keys) (this[k] as? JsonPrimitive)?.doubleOrNull?.let { return it }
            return null
        }
        private fun JsonObject.longOf(vararg keys: String): Long? {
            for (k in keys) (this[k] as? JsonPrimitive)?.let { p -> p.longOrNull?.let { return it }; p.doubleOrNull?.let { return it.toLong() } }
            return null
        }
        private fun JsonObject.obj(vararg keys: String): JsonObject? {
            for (k in keys) (this[k] as? JsonObject)?.let { return it }
            return null
        }
        private fun JsonObject.arr(vararg keys: String): JsonArray? {
            for (k in keys) (this[k] as? JsonArray)?.let { return it }
            return null
        }

        /** Tipul normalizat: vorbit/sforăit/tuse/zgomot/respirație/liniște, oricum l-ar numi serverul. */
        fun normalizeType(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "talk", "speech", "vorbit", "vorbire", "voice" -> "talk"
            "snore", "snoring", "sforait", "sforăit" -> "snore"
            "cough", "tuse" -> "cough"
            "breath", "breathing", "respiratie", "respirație", "gasp", "apnea" -> "breath"
            "silence", "liniste", "liniște" -> "silence"
            null, "" -> "noise"
            else -> "noise"
        }

        /** Intensitate 0..1 din orice: număr 0..1, 0..3, sau cuvânt. */
        private fun intensityOf(el: JsonElement?): Double {
            val p = el as? JsonPrimitive ?: return 0.0
            p.doubleOrNull?.let { v -> return if (v > 1.0) (v / 3.0).coerceIn(0.0, 1.0) else v.coerceIn(0.0, 1.0) }
            return when (p.contentOrNull?.lowercase()) {
                "high", "loud", "puternic", "strong" -> 0.9
                "medium", "moderate", "moderat" -> 0.6
                "low", "quiet", "redus", "soft" -> 0.3
                else -> 0.0
            }
        }

        /**
         * Răspunsul serverului → cronologie normalizată. `chunkFrom`: index → `from` (ms), pentru serverele
         * care întorc evenimentele pe bucată (`chunks:[{index, events:[…]}]`) cu timpi relativi la bucată.
         * Întoarce null doar dacă textul nu e JSON.
         */
        fun parseServer(text: String, startedAt: Long, chunkFrom: Map<Int, Long> = emptyMap()): SleepTimeline? {
            val root = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return null }
            val rawStatus = root.str("status", "state")?.lowercase()
            val reason = root.str("motiv", "reason", "error", "message") ?: ""
            val provider = root.str("provider", "model") ?: ""

            val events = ArrayList<Event>()
            fun addEvents(list: JsonArray, baseMs: Long, chunkIdx: Int) {
                for (e in list) {
                    val o = e as? JsonObject ?: continue
                    val at = o.longOf("at", "start", "from", "t", "startMs", "start_ms", "offset") ?: continue
                    val end = o.longOf("end", "to", "endMs", "end_ms")
                        ?: o.longOf("dur", "duration", "durationMs", "duration_ms")?.let { at + it }
                        ?: at
                    val type = normalizeType(o.str("type", "kind", "label"))
                    if (type == "silence") continue
                    events += Event(
                        at = baseMs + at,
                        end = baseMs + end,
                        type = type,
                        intensity = intensityOf(o["intensity"] ?: o["level"]),
                        transcript = o.str("transcript", "text", "phrase", "words") ?: "",
                        confidence = (o.num("confidence", "conf", "incredere") ?: 0.0).coerceIn(0.0, 1.0),
                        chunk = chunkIdx
                    )
                }
            }
            root.arr("events", "timeline", "evenimente")?.let { addEvents(it, 0L, -1) }
            root.arr("chunks", "bucati")?.forEach { c ->
                val o = c as? JsonObject ?: return@forEach
                val idx = o.longOf("index", "i")?.toInt() ?: -1
                val from = o.longOf("from") ?: chunkFrom[idx] ?: 0L
                o.arr("events", "timeline")?.let { addEvents(it, from, idx) }
            }
            events.sortBy { it.at }

            val st = root.obj("stats", "statistici", "summary") ?: root
            val snoreMin = st.longOf("snoreMin", "snore_min", "snoreMinutes", "sforaitMin")?.toInt()
                ?: (st.num("snoreSec", "snore_sec")?.let { (it / 60).toInt() })
                ?: events.filter { it.type == "snore" }.sumOf { it.durMs }.let { (it / 60_000L).toInt() }
            val snoreEpisodes = st.longOf("snoreEpisodes", "snore_episodes", "episoade")?.toInt() ?: events.count { it.type == "snore" }
            val talkCount = st.longOf("talkCount", "talk_count", "phrases", "fraze")?.toInt() ?: events.count { it.type == "talk" }
            val coughCount = st.longOf("coughCount", "cough_count")?.toInt() ?: events.count { it.type == "cough" }
            val coverageMin = st.longOf("coverageMin", "coverage_min", "listenedMin", "analyzedMin", "ascultatMin")?.toInt()
                ?: st.obj("coverage", "acoperire")?.longOf("min", "listened", "analyzed")?.toInt() ?: 0
            val totalMin = st.longOf("totalMin", "total_min", "sentMin", "trimisMin")?.toInt()
                ?: st.obj("coverage", "acoperire")?.longOf("total", "of", "din")?.toInt() ?: 0

            val status = when {
                rawStatus == "processing" || rawStatus == "pending" || rawStatus == "queued" -> "processing"
                rawStatus == "clips_only" || rawStatus == "unavailable" -> "clips_only"
                rawStatus == "failed" || rawStatus == "error" -> "failed"
                rawStatus == "done" || rawStatus == "ok" || rawStatus == "ready" || rawStatus == "complete" || rawStatus == "completed" -> "done"
                rawStatus == null && (events.isNotEmpty() || coverageMin > 0 || root.containsKey("events") || root.containsKey("chunks")) -> "done"
                rawStatus == null -> "processing"
                else -> rawStatus
            }
            val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
            val finalStatus = if (ok == false && status == "done") "failed" else status

            return SleepTimeline(
                status = finalStatus,
                reason = reason,
                events = events,
                stats = Stats(snoreMin, snoreEpisodes, talkCount, coughCount, coverageMin, totalMin),
                startedAt = startedAt,
                provider = provider
            )
        }
    }
}
