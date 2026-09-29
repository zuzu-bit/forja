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

    @Test fun backRightAfterAutoCloseDoesNotEndTheWorkout() {
        // Ea apasă Înapoi ca să iasă din Spotify („te întorci cu Înapoi”), dar FORJA închisese deja ecranul: apăsarea
        // ajunge pe ecranul live, unde Înapoi = „Încheie”. Prima se înghite; a doua încheie, ca de obicei.
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 1_000)
        assertTrue(h.confirm(4_200))
        assertTrue("chiar în animația închiderii", h.swallowBack(4_300))
        assertFalse("doar o apăsare", h.swallowBack(4_600))
        // Ciclul procesului (FORJA din nou în față) și verificarea „auto” mută fereastra la revenire.
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 10_000)
        assertFalse("cât e în Spotify, Înapoi e al lui Spotify", h.swallowBack(11_000))
        assertTrue(h.confirm(13_000))
        assertNull(h.back(13_400))
        assertEquals("auto", h.check(visible = true, now = 13_800)?.note)
        assertTrue(h.swallowBack(16_300))
        // Mai târziu de 3 s de la revenire, Înapoi e al ei pentru FORJA: încheie.
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 20_000)
        assertTrue(h.confirm(23_000))
        assertEquals("auto", h.check(visible = true, now = 23_800)?.note)
        assertFalse(h.swallowBack(26_900))
    }

    @Test fun theSecondBackAfterSheReturnsHerselfIsSwallowedOnce() {
        // Ecranul Spotify în taskul lui („none”): FORJA nu-l poate închide, ea revine cu Înapoi mai târziu; un al doilea
        // Înapoi grăbit (nu se vedea nimic schimbat) nu încheie antrenamentul.
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 1_000)
        h.result(1_060)
        assertTrue(h.confirm(4_000))
        assertEquals("none cancel:60", h.check(visible = false, now = 4_800)?.note)
        assertNull(h.back(12_000))
        assertTrue(h.swallowBack(12_700))
        assertFalse(h.swallowBack(13_000))
        // S-a întors ea înaintea închiderii (back): la fel, o singură apăsare.
        h.sent("mymusic", SPOTIFY, MusicSource.INVENTORY, sub = true, now = 30_000)
        assertEquals("back", h.back(33_000)?.note)
        assertTrue(h.swallowBack(34_000))
        assertFalse(h.swallowBack(34_200))
    }

    @Test fun aNewTapInForjaSettlesAStaleJumpWithoutSwallowingBack() {
        // Saltul rămas deschis (s-a întors ea prea repede pentru ciclul procesului), apoi o atingere nouă în FORJA:
        // rândul „back”, iar un Înapoi de acum e al ei pentru FORJA.
        val h = HopWatch()
        h.sent("workout", SPOTIFY, MusicSource.WORKOUT, sub = true, now = 1_000)
        assertEquals(HopWatch.Row("workout", SPOTIFY, DiagResult.OK, 1_500, "back"), h.settle(2_500))
        assertFalse(h.open)
        assertFalse(h.swallowBack(3_000))
        // Nicio revenire după salt fără salt: întoarcerea din fundal (Acasă, apoi FORJA) nu înghite nimic.
        assertNull(HopWatch().back(5_000))
        assertFalse(HopWatch().apply { back(5_000) }.swallowBack(5_500))
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
