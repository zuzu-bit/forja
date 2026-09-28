package com.forja.app.core.data

import com.forja.app.feature.nutrition.BodyProfile
import com.forja.app.feature.nutrition.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Documentele pentru site: antrenamentul (users/{uid}/workouts/w{id}) și rația (users/{uid}/settings/targets). */
class SitePayloadsTest {

    private val gym = WorkoutRecord(
        id = 12, planId = 0, planName = "Piept & Spate", planMeta = "FORȚĂ · 45 MIN · SALĂ",
        startedAt = 1_700_000_000_000L, endedAt = 1_700_000_000_000L + 47 * 60_000L + 30_000L, totalSets = 9
    )

    // ── greutăți și volum ──

    @Test
    fun loadsWrittenInThePlanBecomeKilograms() {
        assertEquals(62.5, SitePayloads.loadKg("62,5")!!, 1e-9)
        assertEquals(70.0, SitePayloads.loadKg("70")!!, 1e-9)
        assertEquals(28.0, SitePayloads.loadKg("2×14")!!, 1e-9)
        assertEquals(28.0, SitePayloads.loadKg("2x14")!!, 1e-9)
        assertEquals(20.0, SitePayloads.loadKg("20 kg")!!, 1e-9)
        assertEquals(12.5, SitePayloads.loadKg(" 12.5KG ")!!, 1e-9)
    }

    @Test
    fun bodyweightAndNonsenseAreNotKilograms() {
        assertNull(SitePayloads.loadKg("corp"))
        assertNull(SitePayloads.loadKg(""))
        assertNull(SitePayloads.loadKg("0"))
        assertNull(SitePayloads.loadKg("5000"))
        assertNull(SitePayloads.loadKg("bandă elastică"))
    }

    @Test
    fun volumeIsRepsTimesKilograms() {
        val sets = listOf(SetRecord(10, "62,5"), SetRecord(10, "62,5"), SetRecord(8, "2×14"), SetRecord(12, "corp"))
        assertEquals(10 * 62.5 * 2 + 8 * 28.0, SitePayloads.volumeKg(sets)!!, 1e-9)
    }

    @Test
    fun volumeIsNullWithoutAnyWeight() {
        assertNull(SitePayloads.volumeKg(listOf(SetRecord(15, "corp"), SetRecord(0, "40"))))
        assertNull(SitePayloads.volumeKg(emptyList()))
    }

    @Test
    fun volumeIsRoundedToOneDecimal() {
        assertEquals(40.8, SitePayloads.volumeKg(listOf(SetRecord(3, "13.6")))!!, 1e-9)
    }

    // ── antrenamentul ──

    @Test
    fun gymWorkoutDocumentMatchesTheContract() {
        val doc = SitePayloads.workout(gym, listOf(SetRecord(10, "62,5")))
        assertEquals(
            setOf("startAt", "endAt", "durationS", "title", "kind", "sets", "volumeKg", "kcal", "source"),
            doc.keys
        )
        assertEquals(gym.startedAt, doc["startAt"])
        assertEquals(gym.endedAt, doc["endAt"])
        assertEquals(47L * 60 + 30, doc["durationS"])
        assertEquals("Piept & Spate", doc["title"])
        assertEquals("forta", doc["kind"])
        assertEquals(9, doc["sets"])
        assertEquals(625.0, doc["volumeKg"])
        assertNull(doc["kcal"])
        assertEquals("instructie", doc["source"])
    }

    @Test
    fun homePlanIsRecognisedFromItsMeta() {
        val home = gym.copy(planId = 2, planName = "Full Body Acasă", planMeta = "FĂRĂ ECHIPAMENT · 30 MIN")
        assertEquals("acasa", SitePayloads.kind(home))
        assertEquals("acasa", SitePayloads.kind(home.copy(planMeta = null)))
    }

    @Test
    fun waitingIntervalsAreMarkedAsSuch() {
        val wait = WorkoutRecord(3, planId = -3, planName = "Cât aștepți · Plin de energie", planMeta = null,
            startedAt = 1_000L, endedAt = 901_000L, totalSets = 5)
        val doc = SitePayloads.workout(wait, emptyList())
        assertEquals("asteptare", doc["source"])
        assertEquals("intervale", doc["kind"])
        assertEquals(5, doc["sets"])
        assertNull(doc["volumeKg"])
        assertEquals(900L, doc["durationS"])
    }

    @Test
    fun setsFallBackToTheLogAndTimesNeverGoNegative() {
        val odd = gym.copy(totalSets = 0, endedAt = gym.startedAt - 5_000L, planName = "  ")
        val doc = SitePayloads.workout(odd, listOf(SetRecord(5, "corp"), SetRecord(5, "corp")))
        assertEquals(2, doc["sets"])
        assertEquals(0L, doc["durationS"])
        assertEquals("Antrenament", doc["title"])
    }

    @Test
    fun firstPassBackfillsSixtyDaysThenOnlyNewOnes() {
        val day = 24 * 3_600_000L
        val now = 100 * day
        val old = gym.copy(id = 1, startedAt = now - 70 * day, endedAt = now - 70 * day + 3_600_000L)
        val recent = gym.copy(id = 2, startedAt = now - 10 * day, endedAt = now - 10 * day + 3_600_000L)
        val today = gym.copy(id = 3, startedAt = now - 7_200_000L, endedAt = now - 3_600_000L)
        val (first, mark) = SitePayloads.workoutsToSend(listOf(today, old, recent), since = 0L, now = now, horizonMs = 60 * day)
        assertEquals(listOf(2L, 3L), first.map { it.id })
        assertEquals(today.endedAt, mark)

        val next = gym.copy(id = 4, startedAt = now - 1_800_000L, endedAt = now)
        val (second, mark2) = SitePayloads.workoutsToSend(listOf(today, recent, next), since = mark, now = now, horizonMs = 60 * day)
        assertEquals(listOf(4L), second.map { it.id })
        assertEquals(now, mark2)

        val (none, same) = SitePayloads.workoutsToSend(listOf(today, recent, next), since = mark2, now = now, horizonMs = 60 * day)
        assertTrue(none.isEmpty())
        assertEquals(mark2, same)
    }

    @Test
    fun sessionFinishedLaterIsPickedUpEvenIfItStartedEarlier() {
        val now = 10_000_000_000L
        val a = gym.copy(id = 1, startedAt = now - 50_000L, endedAt = now - 40_000L)
        val b = gym.copy(id = 2, startedAt = now - 90_000L, endedAt = now - 10_000L)   // început înainte, terminat după
        val (todo, _) = SitePayloads.workoutsToSend(listOf(b), since = a.endedAt, now = now, horizonMs = 3_600_000L)
        assertEquals(listOf(2L), todo.map { it.id })
    }

    // ── rația ──

    @Test
    fun targetsFromACompleteBodyProfile() {
        val profile = BodyProfile(sex = 0, age = 30, heightCm = 168, weightKg = 60f, activity = 2, goal = 1)
        val t = Targets.of(profile)!!
        val site = SitePayloads.targets(t, manualKcal = 2000)
        assertEquals(SiteTargets(t.kcal, t.protein, t.carbs, t.fat), site)
    }

    @Test
    fun targetsWithoutProfileSplitTheChosenKcalLikeTheRingsDo() {
        val site = SitePayloads.targets(null, manualKcal = 2000)
        assertEquals(SiteTargets(kcal = 2000, protein = 125, carbs = 225, fat = 66), site)
    }

    @Test
    fun targetsDocumentHasOnlyTheFiveContractFields() {
        val doc = SitePayloads.targetsDoc(SiteTargets(1850, 108, 208, 61), updatedAt = 42L)
        assertEquals(setOf("kcal", "protein", "carbs", "fat", "updatedAt"), doc.keys)
        assertEquals(1850, doc["kcal"])
        assertEquals(108, doc["protein"])
        assertEquals(208, doc["carbs"])
        assertEquals(61, doc["fat"])
        assertEquals(42L, doc["updatedAt"])
    }

    @Test
    fun signatureChangesWithAnyNumber() {
        val a = SiteTargets(2000, 125, 225, 66)
        assertEquals(a.signature, a.copy().signature)
        assertTrue(a.signature != a.copy(fat = 67).signature)
    }
}
