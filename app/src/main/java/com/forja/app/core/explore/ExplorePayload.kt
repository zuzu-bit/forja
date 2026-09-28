package com.forja.app.core.explore

import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.data.db.PlaceEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Ce pleacă la `POST /v2/social/explore/sync` și când — logică pură, fără Android (testată în ExplorePayloadTest).
 *
 * Schema v1 (serverul vechi): celula fără `mode`, locul fără `visits` — serverul respinge câmpurile necunoscute, deci
 * v2 pleacă doar când `/health` spune `explore_sync ≥ 2`. La prima sincronizare v2 retrimitem tot (celulele vechi
 * n-au mod pe site). Ceasul de sincronizare se ia cu 15 minute în urmă: fixurile din fundal ajung în loturi, cu ora
 * măsurării, deci o celulă poate intra în baza de date „în trecut”, după o sincronizare.
 */
internal object ExplorePayload {
    const val GRID_M = 150
    const val SCHEMA_V1 = 1
    const val SCHEMA_V2 = 2
    const val OVERLAP_MS = 15 * 60_000L
    val MODES = setOf("walk", "run", "ride")

    /** Serverul primește ≤ 64 KiB pe cerere; paginăm și după octeți, nu doar după număr. */
    const val PAGE_BYTES = 56_000

    /** De unde citim modificările: tot (0) la retrimitere, altfel ceasul minus suprapunerea. */
    fun since(watermark: Long, full: Boolean): Long =
        if (full) 0L else (watermark - OVERLAP_MS).coerceAtLeast(0L)

    /**
     * Retrimitem tot când serverul a trecut la v2 și celulele noastre au plecat încă fără mod,
     * sau când telefonul sincronizează acum pentru alt cont decât data trecută.
     */
    fun needsFullResend(serverSchema: Int, schemaSent: Int, lastUid: String?, uid: String): Boolean =
        (serverSchema >= SCHEMA_V2 && schemaSent < SCHEMA_V2) || (!lastUid.isNullOrBlank() && lastUid != uid)

    /** Id-ul locului pe site: id-ul recomandării (places/) când există, altfel „p<id local>”. */
    fun remoteId(p: PlaceEntity): String = p.remoteId?.takeIf { it.isNotBlank() } ?: "p${p.id}"

    /** Toate id-urile sub care locul a putut ajunge pe site (înainte și după recomandare). */
    fun tombstoneIds(p: PlaceEntity): List<String> =
        listOfNotNull(p.remoteId?.takeIf { it.isNotBlank() }, "p${p.id}").distinct()

    fun cell(c: ExploreCellEntity, v2: Boolean): JsonObject = buildJsonObject {
        put("id", c.id)
        put("min_lat", coord(c.minLat))
        put("min_lng", coord(c.minLng))
        put("max_lat", coord(maxOf(c.maxLat, c.minLat + 0.000001)))
        put("max_lng", coord(maxOf(c.maxLng, c.minLng + 0.000001)))
        put("first_at", c.firstAt)
        put("last_at", maxOf(c.lastAt, c.firstAt))
        put("visits", c.visits.coerceAtLeast(1))
        if (v2 && c.mode in MODES) put("mode", c.mode)
    }

    /**
     * Locul ca intrări de sincronizare. Când locul a primit între timp un id de recomandare,
     * vechiul „p<id>” de pe site se șterge, ca să nu apară de două ori.
     */
    fun placeEntries(p: PlaceEntity, family: Set<String>, friends: Set<String>, v2: Boolean): List<JsonObject> {
        val id = remoteId(p)
        val visible = LinkedHashSet<String>()
        visible.addAll(family)
        if (p.recommended) visible.addAll(friends)
        val place = buildJsonObject {
            put("id", id)
            put("lat", coord(p.lat))
            put("lng", coord(p.lng))
            put("first_at", p.firstAt)
            put("last_at", maxOf(p.lastAt, p.firstAt))
            put("stay_ms", p.stayMs.coerceAtLeast(0L))
            put("name", line(p.name, 80))
            put("stars", p.stars.coerceIn(0, 5))
            put("note", note(p.note, 300))
            put("recommended", p.recommended)
            putJsonArray("visible_to") { visible.take(100).forEach { add(it) } }
            put("updated_at", p.updatedAt.coerceAtLeast(0L))
            put("deleted", false)
            if (v2) put("visits", p.visits.coerceAtLeast(1))
        }
        val legacy = "p${p.id}"
        return if (id != legacy) listOf(tombstone(legacy, p.updatedAt.coerceAtLeast(0L)), place) else listOf(place)
    }

    fun tombstone(id: String, at: Long): JsonObject = buildJsonObject {
        put("id", id)
        put("deleted", true)
        put("updated_at", at.coerceAtLeast(0L))
    }

    /** Pagini de cel mult [maxCount] intrări și ~[maxBytes] octeți (o intrare mare merge singură). */
    fun pages(items: List<JsonObject>, maxCount: Int, maxBytes: Int = PAGE_BYTES): List<List<JsonObject>> {
        val out = ArrayList<List<JsonObject>>()
        var page = ArrayList<JsonObject>()
        var bytes = 0
        for (item in items) {
            val size = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (page.isNotEmpty() && (page.size >= maxCount || bytes + size > maxBytes)) {
                out.add(page); page = ArrayList(); bytes = 0
            }
            page.add(item); bytes += size
        }
        if (page.isNotEmpty()) out.add(page)
        return out
    }

    /** Coordonate la 6 zecimale (~11 cm): destul pentru hartă, de trei ori mai puțini octeți. */
    fun coord(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    /** Text pe o singură linie, fără caractere de control (serverul le respinge). */
    fun line(v: String, max: Int): String =
        v.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").trim().take(max)

    /** Nota poate avea rânduri noi; restul caracterelor de control pleacă. */
    fun note(v: String, max: Int): String =
        v.replace("\r\n", "\n").replace(Regex("[\\u0000-\\u0008\\u000B-\\u001F\\u007F]"), " ").trim().take(max)
}

/**
 * Sincronizarea pornită de o celulă nouă: cel mult una la [gapMs], cu margine finală — o celulă cucerită în fereastră
 * nu se pierde, ci pleacă la capătul ferestrei (o singură rulare programată; WorkManager KEEP o ține unică).
 */
internal object KickThrottle {
    /**
     * @param scheduledAt momentul programat al ultimei rulări (0 = niciodată)
     * @return întârzierea până la rularea permisă, sau null când o rulare e deja programată în viitor
     */
    fun delayMs(now: Long, scheduledAt: Long, gapMs: Long): Long? {
        if (scheduledAt <= 0L || scheduledAt - now > gapMs) return 0L   // niciodată, sau ceasul a sărit înapoi
        if (scheduledAt > now) return null
        return (scheduledAt + gapMs - now).coerceAtLeast(0L)
    }
}
