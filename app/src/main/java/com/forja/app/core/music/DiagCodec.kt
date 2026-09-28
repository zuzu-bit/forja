package com.forja.app.core.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Forma exactă a jurnalului de pornire trimis la forja-api (DESIGN-4.4 §3.5):
 *
 *   POST /v1/diag/music  { device, app, events:[{ at, want, rung, pkg|null, ver|null, kind|null, result, ms, err|null }] }
 *
 * cel mult 50 de rânduri într-o cerere; fără titluri, fără artiști (rândurile nici nu au unde să-i țină).
 * Kotlin pur (testat pe JVM).
 */
object DiagCodec {
    const val MAX_EVENTS = 50
    /** Serverul primește corpuri de cel mult 16 384 de octeți (altfel 413); rămâne loc de siguranță. */
    const val MAX_BYTES = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    fun event(e: AttemptEvent): JsonObject = buildJsonObject {
        put("at", e.at)
        put("want", e.want)
        put("rung", e.rung)
        put("pkg", e.pkg?.let { JsonPrimitive(it.take(100)) } ?: JsonNull)
        put("ver", e.ver?.let { JsonPrimitive(it.take(40)) } ?: JsonNull)
        put("kind", e.kind?.let { JsonPrimitive(it.wire) } ?: JsonNull)
        put("result", e.result.wire)
        put("ms", e.ms)
        put("err", e.err?.let { JsonPrimitive(it.take(120)) } ?: JsonNull)
    }

    /** Corpul unei cereri (primele [MAX_EVENTS] rânduri). */
    fun body(device: String, app: String, events: List<AttemptEvent>): String = buildJsonObject {
        put("device", device.take(120))
        put("app", app.take(40))
        put("events", JsonArray(events.take(MAX_EVENTS).map { event(it) }))
    }.toString()

    fun line(e: AttemptEvent): String = event(e).toString()

    /**
     * Câte rânduri de la începutul lui [events] încap într-o cerere: cel mult [MAX_EVENTS] și corpul (UTF-8) sub
     * [maxBytes]. Cel puțin unul (un rând are cel mult ~600 de octeți).
     */
    fun fit(device: String, app: String, events: List<AttemptEvent>, maxBytes: Int = MAX_BYTES): Int {
        var size = body(device, app, emptyList()).toByteArray(Charsets.UTF_8).size
        var n = 0
        for (e in events.take(MAX_EVENTS)) {
            val add = line(e).toByteArray(Charsets.UTF_8).size + if (n > 0) 1 else 0
            if (n > 0 && size + add > maxBytes) break
            size += add
            n++
        }
        return n
    }

    fun parse(line: String): AttemptEvent? = try {
        val o = json.parseToJsonElement(line).jsonObject
        fun str(k: String) = o[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
        AttemptEvent(
            at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
            want = str("want") ?: "",
            rung = str("rung") ?: "",
            pkg = str("pkg"),
            ver = str("ver"),
            kind = MediaKind.entries.firstOrNull { it.wire == str("kind") },
            result = DiagResult.entries.firstOrNull { it.wire == str("result") } ?: DiagResult.ERROR,
            ms = o["ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            err = str("err")
        )
    } catch (_: Exception) {
        null
    }
}
