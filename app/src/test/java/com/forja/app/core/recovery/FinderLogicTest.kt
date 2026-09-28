package com.forja.app.core.recovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regulile găsirii, pe JVM, fără Android: starea din Profil, comenzile de pe site, soneria, textele. */
class FinderLogicTest {

    private val now = 1_800_000_000_000L

    private fun cmd(
        id: String = "c1",
        kind: FinderCommand.Kind = FinderCommand.Kind.Locate,
        until: Long = now + 10 * 60_000L,
        startBefore: Long = now + 30 * 60_000L,
        phase: String = "queued",
        seconds: Int? = null,
    ) = FinderCommand(id, kind, now - 1_000L, startBefore, until, phase, if (kind == FinderCommand.Kind.Locate) 10 else null, seconds)

    // ── Starea rândului „Telefonul meu” ──

    @Test fun withoutContractTheRowSaysNoContract() {
        assertEquals(FinderState.NoContract, FinderLogic.state(contractOn = false, prerequisitesOk = true, lastOkAt = now, now = now))
    }

    @Test fun missingPermissionsWinOverAFreshBeat() {
        assertEquals(FinderState.Incomplete, FinderLogic.state(true, prerequisitesOk = false, lastOkAt = now, now = now))
    }

    @Test fun aBeatInTheLastTenMinutesIsGuard() {
        assertEquals(FinderState.Guard, FinderLogic.state(true, true, now - 9 * 60_000L, now))
        assertEquals(FinderState.Guard, FinderLogic.state(true, true, now, now))
    }

    @Test fun silenceLongerThanTenMinutesOrNoBeatIsNoLink() {
        assertEquals(FinderState.NoLink, FinderLogic.state(true, true, now - 11 * 60_000L, now))
        assertEquals(FinderState.NoLink, FinderLogic.state(true, true, 0L, now))
    }

    @Test fun rowWordsAreTheOnesFromTheDesign() {
        assertEquals(listOf("în gardă", "fără contract", "incomplet", "fără legătură"), FinderState.entries.map { it.word })
    }

    // ── Starea trimisă site-ului ──

    @Test fun beatStatusPrefersWhatThePhoneIsDoing() {
        assertEquals("ringing", FinderLogic.beatStatus(ringing = true, locating = false, locationEnabled = false, locationPermission = false, notices = false))
        assertEquals("locating", FinderLogic.beatStatus(false, true, true, true, true))
    }

    @Test fun beatStatusReportsTheFirstObstacle() {
        assertEquals("location_off", FinderLogic.beatStatus(false, false, locationEnabled = false, locationPermission = false, notices = false))
        assertEquals("permission_missing", FinderLogic.beatStatus(false, false, true, locationPermission = false, notices = false))
        // Un canal mut NU mai revocă găsirea: site-ul primește notification_missing.
        assertEquals("notification_missing", FinderLogic.beatStatus(false, false, true, true, notices = false))
        assertEquals("ready", FinderLogic.beatStatus(false, false, true, true, true))
    }

    // ── Comenzile de pe site ──

    @Test fun newStartableCommandStarts() {
        val d = FinderLogic.decide(cmd(), activeId = null, activeUntil = 0L, handled = emptySet(), now = now)
        assertTrue(d is FinderLogic.Decision.Start)
    }

    @Test fun commandClosedOnThePhoneIsNotRestarted() {
        assertEquals(FinderLogic.Decision.Keep, FinderLogic.decide(cmd(), null, 0L, setOf("c1"), now))
        // …și dacă încă rulează aici, se oprește.
        assertEquals(FinderLogic.Decision.StopActive, FinderLogic.decide(cmd(), "c1", now + 60_000L, setOf("c1"), now))
    }

    @Test fun commandGoneFromTheSiteStopsTheActiveOne() {
        assertEquals(FinderLogic.Decision.StopActive, FinderLogic.decide(null, "c1", now + 60_000L, emptySet(), now))
        assertEquals(FinderLogic.Decision.Keep, FinderLogic.decide(null, null, 0L, emptySet(), now))
    }

    @Test fun sameCommandWithLaterUntilExtends() {
        val extended = cmd(until = now + 20 * 60_000L, phase = "active")
        val d = FinderLogic.decide(extended, "c1", now + 10 * 60_000L, emptySet(), now)
        assertEquals(FinderLogic.Decision.Extend(extended), d)
        assertEquals(FinderLogic.Decision.Keep, FinderLogic.decide(extended, "c1", extended.until, emptySet(), now))
    }

    @Test fun queuedCommandPastItsWindowIsIgnored() {
        val late = cmd(startBefore = now - 1L, phase = "queued")
        assertFalse(late.startable(now))
        assertEquals(FinderLogic.Decision.Keep, FinderLogic.decide(late, null, 0L, emptySet(), now))
        // Activă deja (telefonul a confirmat), fereastra de pornire nu mai contează.
        assertTrue(late.copy(phase = "active").startable(now))
    }

    @Test fun anotherCommandReplacesOrStopsTheActiveOne() {
        assertTrue(FinderLogic.decide(cmd(id = "c2"), "c1", now + 60_000L, emptySet(), now) is FinderLogic.Decision.Start)
        val dead = cmd(id = "c2", until = now - 1L)
        assertEquals(FinderLogic.Decision.StopActive, FinderLogic.decide(dead, "c1", now + 60_000L, emptySet(), now))
    }

    @Test fun parseReadsV2AndV1Commands() {
        val v2 = Json.parseToJsonElement(
            """{"id":"a","kind":"ring","created_at":1,"start_before":2,"until":3,"phase":"queued","minutes":null,"seconds":60}"""
        ).jsonObject
        val ring = FinderCommand.parse(v2)!!
        assertEquals(FinderCommand.Kind.Ring, ring.kind)
        assertEquals(60, ring.seconds)
        assertNull(ring.minutes)
        // Serverul vechi nu trimite `kind`: e o localizare.
        val v1 = Json.parseToJsonElement("""{"id":"b","created_at":1,"start_before":2,"until":3,"phase":"active"}""").jsonObject
        assertEquals(FinderCommand.Kind.Locate, FinderCommand.parse(v1)!!.kind)
        assertNull(FinderCommand.parse(null))
        assertNull(FinderCommand.parse(Json.parseToJsonElement("""{"kind":"ring"}""").jsonObject))
    }

    // ── Soneria și ritmul ──

    @Test fun ringLastsSixtySecondsByDefaultAndIsClamped() {
        val ring = cmd(kind = FinderCommand.Kind.Ring, until = now + 30 * 60_000L)
        assertEquals(60_000L, FinderLogic.ringMillis(ring, now))
        assertEquals(120_000L, FinderLogic.ringMillis(ring.copy(seconds = 999), now))
        assertEquals(10_000L, FinderLogic.ringMillis(ring.copy(seconds = 1), now))
        // Nu sună dincolo de termenul comenzii.
        assertEquals(20_000L, FinderLogic.ringMillis(ring.copy(until = now + 20_000L), now))
    }

    @Test fun nextBeatFollowsTheServerWithinBounds() {
        assertEquals(60_000L, FinderLogic.nextBeatMillis(null))
        assertEquals(30_000L, FinderLogic.nextBeatMillis(5))
        assertEquals(900_000L, FinderLogic.nextBeatMillis(10_000))
        assertEquals(120_000L, FinderLogic.nextBeatMillis(120))
    }

    // ── Textele ──

    @Test fun romanianPluralForMinutesAndSeconds() {
        assertEquals("un minut", FinderLogic.minutes(1))
        assertEquals("2 minute", FinderLogic.minutes(2))
        assertEquals("19 minute", FinderLogic.minutes(19))
        assertEquals("20 de minute", FinderLogic.minutes(20))
        assertEquals("101 minute", FinderLogic.minutes(101))
        assertEquals("o secundă", FinderLogic.seconds(1))
        assertEquals("45 de secunde", FinderLogic.seconds(45))
        assertEquals("12 secunde", FinderLogic.seconds(12))
    }

    @Test fun locateNotificationCountsDownInWholeMinutes() {
        assertEquals("Poziția pleacă încă 10 minute.", FinderLogic.locateText(now + 10 * 60_000L, now))
        assertEquals("Poziția pleacă încă 10 minute.", FinderLogic.locateText(now + 9 * 60_000L + 1L, now))
        assertEquals("Poziția pleacă încă un minut.", FinderLogic.locateText(now + 5_000L, now))
    }

    @Test fun sheetStatusLine() {
        assertEquals("ÎN GARDĂ · VĂZUT ACUM 1 MIN · 64%", FinderLogic.statusLine(FinderState.Guard, now - 61_000L, 64, now))
        assertEquals("ÎN GARDĂ · VĂZUT CHIAR ACUM · 100%", FinderLogic.statusLine(FinderState.Guard, now - 5_000L, 100, now))
        assertEquals("FĂRĂ LEGĂTURĂ · VĂZUT ACUM 3 H · 12%", FinderLogic.statusLine(FinderState.NoLink, now - 3 * 3_600_000L, 12, now))
        // Niciodată văzut: fără baterie (nu știm una reală).
        assertEquals("FĂRĂ LEGĂTURĂ · ÎNCĂ NEVĂZUT", FinderLogic.statusLine(FinderState.NoLink, 0L, 50, now))
    }
}
