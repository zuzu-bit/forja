package com.forja.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror C: id-urile stabile, detaliile mesei, antrenamentul v2 (exerciții, muzică, dus la capăt). */
class SitePayloadsMirrorTest {
    private val w = WorkoutRecord(id = 3, planId = 1, planName = "Forță A", planMeta = "FORȚĂ", startedAt = 1_000_000L, endedAt = 4_000_000L, totalSets = 3)

    @Test fun idsComeFromTheMomentNotTheRoomRow() {
        assertEquals("m-1700000000000", SitePayloads.mealCloudId(1_700_000_000_000L))
        assertEquals("a-5", SitePayloads.activityCloudId(5)); assertEquals("w-6", SitePayloads.workoutCloudId(6))
    }

    @Test fun workoutV2HasExercisesInOrderMusicInsideTheSessionAndCompleted() {
        val sets = listOf(
            SetRecord(8, "62,5", "Genuflexiuni", 1, 1_100_000L), SetRecord(8, "62,5", "Genuflexiuni", 2, 1_300_000L),
            SetRecord(15, "corp", "Flotări", 1, 2_000_000L))
        val music = listOf(TrackRecord("Înainte", "X", 900_000L), TrackRecord("Ploaia", "Trupa", 1_200_000L), TrackRecord("După", "Y", 4_100_000L))
        val d = SitePayloads.workoutV2(w, sets, 12, music)
        assertEquals(2, d["payload"])
        assertEquals(12, d["plannedSets"]); assertEquals(false, d["completed"])
        @Suppress("UNCHECKED_CAST") val ex = d["exercises"] as List<Map<String, Any?>>
        assertEquals(listOf("Genuflexiuni", "Flotări"), ex.map { it["name"] })
        @Suppress("UNCHECKED_CAST") val first = (ex[0]["sets"] as List<Map<String, Any?>>)[0]
        assertEquals(62.5, first["kg"] as Double, 1e-9); assertEquals("62,5", first["load"])
        @Suppress("UNCHECKED_CAST") val m = d["music"] as List<Map<String, Any?>>
        assertEquals(listOf("Ploaia"), m.map { it["title"] })
        assertEquals(true, SitePayloads.workoutV2(w, sets, 3, emptyList())["completed"])
        val noPlan = SitePayloads.workoutV2(w.copy(planId = -1), emptyList(), null, emptyList())
        assertFalse(noPlan.containsKey("completed")); assertFalse(noPlan.containsKey("exercises")); assertFalse(noPlan.containsKey("music"))
    }

    @Test fun mealDocCarriesTheAnalysisOnlyWhenThereIsOne() {
        val details = SitePayloads.encodeDetails(MealDetails(listOf(MealPart("Paste", 250, 390, 13, 72, 4)), score = 7, reason = "Echilibrat", tip = "O salată."))
        val d = SitePayloads.mealDoc("Paste", 390, 13, 72, 4, 250, 1, 20000, "ESTIMARE AI · POZĂ", "medie", 5L, details, true)
        @Suppress("UNCHECKED_CAST") val items = d["items"] as List<Map<String, Any?>>
        assertEquals("Paste", items[0]["name"]); assertEquals(mapOf("value" to 7, "reason" to "Echilibrat"), d["score"])
        assertEquals("O salată.", d["tip"]); assertEquals(true, d["photo"])
        val manual = SitePayloads.mealDoc("Măr", 80, 0, 20, 0, 150, 3, 20000, "MANUAL", "—", 6L, null, null)
        assertFalse(manual.containsKey("items")); assertFalse("null photo leaves the field alone (merge)", manual.containsKey("photo"))
        assertNull(SitePayloads.decodeDetails("{nu e json"))
        assertTrue(SitePayloads.mealDoc("x", 1, 1, 1, 1, 1, 0, 1, "s", "c", 1, SitePayloads.encodeDetails(MealDetails(score = 42)), null)["score"] == null)
    }
}
