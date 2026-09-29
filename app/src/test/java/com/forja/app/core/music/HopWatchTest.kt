package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Întoarcerea din Spotify (RET_SUB, 4.4.1): FORJA închide ecranul deschis pentru rezultat și scrie rândul RET —
 * auto (FORJA e din nou în față), none (ecranul Spotify stă în taskul lui: e nevoie de Înapoi), back (s-a întors ea).
 */
class HopWatchTest {

    @Test fun forjaClosesTheScreenAndIsBackInFront() {
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 1_000)
        assertTrue(h.open)
        assertTrue(h.confirm(4_200))
        assertFalse("o singură închidere", h.confirm(4_250))
        // FORJA revine (ciclul procesului): nu e „back”, a închis-o FORJA.
        assertNull(h.back(4_300))
        // Rezultatul anulat vine la întoarcere, după închidere: nu contează.
        h.result(4_350)
        assertEquals(HopWatch.Row("workout", SPOTIFY, DiagResult.OK, 4_000, "auto"), h.check(visible = true, now = 5_000))
        assertFalse(h.open)
        assertNull(h.check(visible = true, now = 6_000))
    }

    @Test fun singleTaskScreenStaysAndSheNeedsBack() {
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 1_000)
        // Android a anulat rezultatul imediat: ecranul Spotify s-a deschis în taskul lui.
        h.result(1_060)
        assertTrue(h.confirm(4_000))
        assertEquals(HopWatch.Row("workout", SPOTIFY, DiagResult.TIMEOUT, 3_800, "none cancel:60"), h.check(visible = false, now = 4_800))
        // Revine ea mai târziu cu Înapoi: rândul s-a scris deja.
        assertNull(h.back(20_000))
    }

    @Test fun sheCameBackFirst() {
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 1_000)
        assertEquals(HopWatch.Row("workout", SPOTIFY, DiagResult.OK, 9_000, "back"), h.back(10_000))
        assertFalse("nimic de închis după ce s-a întors", h.confirm(12_000))
    }

    @Test fun aNewTaskHopIsNeverClosedByForja() {
        val h = HopWatch()
        h.sent("mymusic", SPOTIFY, MusicSource.INVENTORY, sub = false, now = 0)
        assertFalse(h.confirm(3_000))
        assertFalse("nimic de închis", h.drop(null))
        h.sent("mymusic", SPOTIFY, MusicSource.INVENTORY, sub = false, now = 0)
        assertEquals("back task", h.back(8_000)?.note)
    }

    @Test fun theWorkoutEndClosesAnOpenJumpQuietly() {
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 0)
        assertFalse("alt ecran nu-l închide", h.drop(MusicSource.INVENTORY))
        assertTrue(h.drop(MusicSource.WORKOUT))
        assertFalse(h.open)
        assertNull(h.back(5_000))
        // Deja închis de FORJA: rândul RET îl scrie verificarea, nu se pierde.
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 0)
        assertTrue(h.confirm(3_000))
        assertFalse(h.drop(MusicSource.WORKOUT))
        assertEquals("auto", h.check(visible = true, now = 3_800)?.note)
    }
}
