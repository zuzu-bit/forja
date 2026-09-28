package com.forja.app.core.explore

import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.data.db.PlaceEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce pleacă la explore/sync (schema 1 vs 2), retrimiterea o dată, suprapunerea ceasului și ritmul la celulă nouă. */
class ExplorePayloadTest {

    private val cell = ExploreCellEntity(
        id = "172345_561234", minLat = 44.4268, minLng = 26.1025, maxLat = 44.4281, maxLng = 26.1043,
        firstAt = 1_000L, lastAt = 5_000L, visits = 3, mode = "run"
    )

    private val place = PlaceEntity(
        id = 7, lat = 44.43, lng = 26.10, firstAt = 100L, lastAt = 900L, stayMs = 18_000_000L,
        name = "Parcul\tIOR", stars = 4, note = "Bancă\r\numbrită", recommended = true,
        remoteId = null, cellId = "172345_561234", updatedAt = 950L, visits = 5
    )

    // ── celule ──

    @Test
    fun cellWithoutV2HasExactlyTheEightFieldsTheOldServerAccepts() {
        val json = ExplorePayload.cell(cell, v2 = false)
        assertEquals(setOf("id", "min_lat", "min_lng", "max_lat", "max_lng", "first_at", "last_at", "visits"), json.keys)
    }

    @Test
    fun cellWithV2CarriesTheConquestMode() {
        val json = ExplorePayload.cell(cell, v2 = true)
        assertEquals("run", json.str("mode"))
        assertEquals(3, json["visits"]!!.jsonPrimitive.int)
    }

    @Test
    fun unknownModeIsNeverSent() {
        val json = ExplorePayload.cell(cell.copy(mode = "car"), v2 = true)
        assertFalse(json.containsKey("mode"))
    }

    @Test
    fun cellIsClampedToWhatTheServerValidates() {
        val flat = cell.copy(maxLat = cell.minLat, maxLng = cell.minLng, lastAt = 10L, firstAt = 20L, visits = 0)
        val json = ExplorePayload.cell(flat, v2 = false)
        assertTrue(json.num("max_lat") > json.num("min_lat"))
        assertTrue(json.num("max_lng") > json.num("min_lng"))
        assertEquals(20L, json["last_at"]!!.jsonPrimitive.long)
        assertEquals(1, json["visits"]!!.jsonPrimitive.int)
    }

    @Test
    fun coordinatesAreRoundedToSixDecimals() {
        assertEquals(44.426812, ExplorePayload.coord(44.42681234), 0.0)
    }

    // ── locuri ──

    @Test
    fun placeWithV2CarriesVisitsAndV1DoesNot() {
        val v1 = ExplorePayload.placeEntries(place, emptySet(), emptySet(), v2 = false).single()
        val v2 = ExplorePayload.placeEntries(place, emptySet(), emptySet(), v2 = true).single()
        assertFalse(v1.containsKey("visits"))
        assertEquals(5, v2["visits"]!!.jsonPrimitive.int)
        assertEquals(v1.keys + "visits", v2.keys)
    }

    @Test
    fun placeTextIsCleanedForTheServer() {
        val json = ExplorePayload.placeEntries(place, emptySet(), emptySet(), v2 = true).single()
        assertEquals("Parcul IOR", json.str("name"))
        assertEquals("Bancă\numbrită", json.str("note"))
        assertEquals("p7", json.str("id"))
    }

    @Test
    fun familySeesEveryPlaceFriendsOnlyRecommendedOnes() {
        val family = setOf("fam1")
        val friends = setOf("fr1", "fr2")
        val recommended = ExplorePayload.placeEntries(place, family, friends, v2 = true).single()
        assertEquals(listOf("fam1", "fr1", "fr2"), recommended["visible_to"]!!.jsonArray.map { it.jsonPrimitive.content })
        val private = ExplorePayload.placeEntries(place.copy(recommended = false), family, friends, v2 = true).single()
        assertEquals(listOf("fam1"), private["visible_to"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun recommendedPlaceReplacesItsLegacyIdWithATombstone() {
        val entries = ExplorePayload.placeEntries(place.copy(remoteId = "abcDEF123"), emptySet(), emptySet(), v2 = true)
        assertEquals(2, entries.size)
        assertEquals("p7", entries[0].str("id"))
        assertTrue(entries[0]["deleted"]!!.jsonPrimitive.boolean)
        assertEquals("abcDEF123", entries[1].str("id"))
        assertFalse(entries[1]["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("abcDEF123", "p7"), ExplorePayload.tombstoneIds(place.copy(remoteId = "abcDEF123")))
    }

    // ── când și cât ──

    @Test
    fun watermarkIsTakenFifteenMinutesBack() {
        val watermark = 10_000_000L
        assertEquals(watermark - 15 * 60_000L, ExplorePayload.since(watermark, full = false))
        assertEquals(0L, ExplorePayload.since(60_000L, full = false))
        assertEquals(0L, ExplorePayload.since(watermark, full = true))
    }

    @Test
    fun lateBatchedFixInsideTheOverlapIsStillSent() {
        // Sincronizare la 12:00 (ceasul = 12:00); un lot din fundal adaugă la 12:03 o celulă cu ora fixului 11:52.
        val syncAt = 12 * 3_600_000L
        val lateFix = syncAt - 8 * 60_000L
        assertTrue(lateFix > ExplorePayload.since(syncAt, full = false))
    }

    @Test
    fun fullResendOnceWhenTheServerMovesToV2() {
        assertTrue(ExplorePayload.needsFullResend(serverSchema = 2, schemaSent = 0, lastUid = "u1", uid = "u1"))
        assertTrue(ExplorePayload.needsFullResend(serverSchema = 2, schemaSent = 1, lastUid = null, uid = "u1"))
        assertFalse(ExplorePayload.needsFullResend(serverSchema = 2, schemaSent = 2, lastUid = "u1", uid = "u1"))
        assertFalse(ExplorePayload.needsFullResend(serverSchema = 1, schemaSent = 0, lastUid = "u1", uid = "u1"))
    }

    @Test
    fun fullResendForAnotherAccountOnTheSamePhone() {
        assertTrue(ExplorePayload.needsFullResend(serverSchema = 1, schemaSent = 1, lastUid = "u1", uid = "u2"))
        assertFalse(ExplorePayload.needsFullResend(serverSchema = 1, schemaSent = 1, lastUid = null, uid = "u2"))
        assertFalse(ExplorePayload.needsFullResend(serverSchema = 1, schemaSent = 1, lastUid = "", uid = "u2"))
    }

    @Test
    fun pagesRespectCountAndBytes() {
        val items = (0..9).map { i -> buildJsonObject { put("id", "c$i"); put("pad", "x".repeat(90)) } }
        assertEquals(listOf(4, 4, 2), ExplorePayload.pages(items, maxCount = 4).map { it.size })
        val one = items.first().toString().toByteArray().size + 1
        assertEquals(List(5) { 2 }, ExplorePayload.pages(items, maxCount = 100, maxBytes = one * 2).map { it.size })
        val huge = listOf(buildJsonObject { put("pad", "x".repeat(500)) })
        assertEquals(1, ExplorePayload.pages(huge, maxCount = 10, maxBytes = 100).single().size)
        assertTrue(ExplorePayload.pages(emptyList(), maxCount = 10).isEmpty())
    }

    // ── ritmul la celulă nouă ──

    @Test
    fun firstNewCellSyncsAtOnce() {
        assertEquals(0L, KickThrottle.delayMs(now = 1_000_000L, scheduledAt = 0L, gapMs = GAP))
    }

    @Test
    fun newCellInsideTheWindowWaitsForItsEnd() {
        val last = 1_000_000L
        assertEquals(3 * 60_000L, KickThrottle.delayMs(now = last + 2 * 60_000L, scheduledAt = last, gapMs = GAP))
        assertEquals(0L, KickThrottle.delayMs(now = last + 6 * 60_000L, scheduledAt = last, gapMs = GAP))
    }

    @Test
    fun aPendingRunAbsorbsNewCells() {
        val scheduled = 2_000_000L
        assertNull(KickThrottle.delayMs(now = scheduled - 60_000L, scheduledAt = scheduled, gapMs = GAP))
    }

    @Test
    fun overAWalkRunsAreSpacedAndEveryCellIsCoveredWithinTheWindow() {
        // O celulă nouă la fiecare 2 minute, o oră: rulările sunt la ≥ 5 min una de alta și fiecare celulă
        // pleacă cel târziu la 5 min după ce a fost cucerită.
        var scheduled = 0L
        val runs = ArrayList<Long>()
        val cells = (0..60 step 2).map { 10_000_000L + it * 60_000L }
        for (now in cells) {
            val d = KickThrottle.delayMs(now, scheduled, GAP) ?: continue
            scheduled = now + d
            runs += scheduled
        }
        runs.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= GAP) }
        cells.forEach { t -> assertTrue(runs.any { it in t..(t + GAP) }) }
        assertTrue(runs.size <= 60 / 5 + 2)
    }

    @Test
    fun clockJumpingBackDoesNotBlockSync() {
        assertEquals(0L, KickThrottle.delayMs(now = 1_000_000L, scheduledAt = 1_000_000L + 3_600_000L, gapMs = GAP))
    }

    private fun JsonObject.str(k: String): String = this[k]!!.jsonPrimitive.content
    private fun JsonObject.num(k: String): Double = this[k]!!.jsonPrimitive.content.toDouble()

    private companion object { const val GAP = 5 * 60_000L }
}
