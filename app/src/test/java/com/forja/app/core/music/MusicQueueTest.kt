package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verificarea aterizării cozii FORJA (4.4.1): o piesă cerută trebuie să cânte în 6 s; o reclamă pornește așteptarea de la
 * capăt; altfel coada se retrage „refused” (Spotify alege ordinea azi), iar rândul QUEUE spune câte piese au aterizat.
 */
class MusicQueueTest {

    private fun item(n: Int) = PlayItem(TrackKey.of("Piesa $n", "A$n"), "Piesa $n", "A$n", SPOTIFY, "spotify:track:$n", null, 200, Tier.NEW)

    private fun queue() = MusicQueue(listOf(item(1), item(2), item(3)), emptyList(), SPOTIFY)

    /** Piesa 1 s-a terminat natural, iar Spotify a trecut la radioul lui: coada cere piesa 2, cererea pleacă la [now]. */
    private fun issueSecond(q: MusicQueue, now: Long): MusicQueue.Action.Issue {
        val a = q.onTrack("Radio", "Cineva", null, 180_000, prevPosMs = 195_000, prevDurMs = 200_000, targetPlaying = true, fg = true)
        a as MusicQueue.Action.Issue
        q.issued(a.index, now)
        return a
    }

    @Test fun issuedTrackLandsWithinSixSeconds() {
        val q = queue()
        assertEquals("piesa 1 a verificat-o pornirea", 1, q.landed)
        val a = issueSecond(q, now = 10_000)
        assertEquals(1, a.index)
        assertEquals(16_000L, q.landingDeadline)
        assertEquals(MusicQueue.Action.None, q.check("Radio", "Cineva", null, 180_000, now = 11_000))
        // Piesa 2 apare după 2,5 s: a aterizat.
        assertEquals(MusicQueue.Action.None, q.onTrack("Piesa 2", "A2", "spotify:track:2", 200_000, 1_000, 180_000, true, true))
        assertEquals(MusicQueue.Action.Landed(1), q.check("Piesa 2", "A2", "spotify:track:2", 200_000, now = 12_500))
        assertEquals(2, q.landed)
        assertNull(q.landingDeadline)
        assertFalse(q.released)
        // Nimic în așteptare: verificarea nu mai face nimic.
        assertEquals(MusicQueue.Action.None, q.check("Radio", "Cineva", null, 180_000, now = 40_000))
    }

    @Test fun noLandingInSixSecondsIsRefused() {
        val q = queue()
        issueSecond(q, now = 10_000)
        assertEquals(MusicQueue.Action.None, q.check("Radio", "Cineva", null, 180_000, now = 15_900))
        assertEquals(MusicQueue.Action.Release(MusicQueue.REFUSED), q.check("Radio", "Cineva", null, 180_000, now = 16_000))
        assertTrue(q.released)
        assertEquals(MusicQueue.REFUSED, q.releaseReason)
        assertEquals("doar piesa 1 a cântat din listă", 1, q.landed)
        assertNull(q.landingDeadline)
        assertEquals(MusicQueue.Action.None, q.next())
        // Ce află ea, o singură dată pe antrenament.
        assertEquals("Azi, ordinea o alege Spotify.", MusicQueue.refusedNotice(SPOTIFY))
        assertEquals("Azi, ordinea o alege YouTube Music.", MusicQueue.refusedNotice(YTM))
    }

    @Test fun anAdRestartsTheLandingWait() {
        val q = queue()
        issueSecond(q, now = 10_000)
        // Reclamă la secunda 5 (Spotify gratuit): așteptarea o ia de la capăt.
        assertEquals(MusicQueue.Action.Hold, q.check("Advertisement", "", null, 30_000, now = 15_000))
        assertEquals(21_000L, q.landingDeadline)
        assertEquals(MusicQueue.Action.None, q.check("Radio", "Cineva", null, 180_000, now = 20_000))
        assertEquals(MusicQueue.Action.Landed(1), q.check("Piesa 2", "A2", null, 200_000, now = 20_500))
        // Altă cerere, cu reclamă la mijloc: refuzată abia la 6 s după reclamă.
        val next = q.next() as MusicQueue.Action.Issue
        q.issued(next.index, 30_000)
        assertEquals(MusicQueue.Action.Hold, q.check("Spotify", "", null, 0, now = 33_000))
        assertEquals(MusicQueue.Action.None, q.check("Radio", "Cineva", null, 180_000, now = 38_000))
        assertEquals(MusicQueue.Action.Release(MusicQueue.REFUSED), q.check("Radio", "Cineva", null, 180_000, now = 39_000))
        assertEquals(2, q.landed)
    }

    @Test fun aSubstitutedSongIsARefusalNotATakeover() {
        // Piesa 1 s-a terminat natural, Spotify a trecut la radioul lui („Radio”), coada a cerut piesa 2. Spotify pune
        // altă piesă decât cea cerută (pe gratuit: una înlocuitoare, un amestec al artistului) la 2 s după ce „Radio”
        // pornise. Nu a preluat ea: e răspunsul lui Spotify la cerere, deci termenul aterizării decide („refused”).
        val q = queue()
        issueSecond(q, now = 10_000)
        assertEquals(
            MusicQueue.Action.None,
            q.onTrack("Alta piesa", "Altcineva", null, 190_000, prevPosMs = 2_000, prevDurMs = 180_000, targetPlaying = true, fg = true)
        )
        assertFalse("nu e „takeover”: împrumutul rămâne, muzica pornită de FORJA se oprește la final", q.released)
        assertEquals(16_000L, q.landingDeadline)
        assertEquals(MusicQueue.Action.None, q.check("Alta piesa", "Altcineva", null, 190_000, now = 12_000))
        assertEquals(MusicQueue.Action.Release(MusicQueue.REFUSED), q.check("Alta piesa", "Altcineva", null, 190_000, now = 16_000))
        assertEquals(MusicQueue.REFUSED, q.releaseReason)
        assertEquals("doar piesa 1 a cântat din listă", 1, q.landed)
    }

    @Test fun theRequestedSongArrivingInTwoMetadataStepsStillLands() {
        // Spotify dă întâi titlul (fără artist), apoi și artistul: primul pas nu e o piesă străină, nici un refuz.
        val q = queue()
        issueSecond(q, now = 10_000)
        assertEquals(MusicQueue.Action.None, q.onTrack("Piesa 2", null, null, 0, 1_000, 180_000, true, true))
        assertEquals(MusicQueue.Action.None, q.check("Piesa 2", null, null, 0, now = 10_400))
        assertFalse(q.released)
        assertEquals(MusicQueue.Action.None, q.onTrack("Piesa 2", "A2", "spotify:track:2", 200_000, 400, 0, true, true))
        assertEquals(MusicQueue.Action.Landed(1), q.check("Piesa 2", "A2", "spotify:track:2", 200_000, now = 10_700))
        assertEquals(2, q.landed)
    }

    @Test fun withNothingPendingAnEarlyForeignSongIsStillHerTakeover() {
        // Piesa 2 a aterizat; la minutul 1 ea alege altă listă în Spotify: coada se retrage fără luptă (ca până acum).
        val q = queue()
        issueSecond(q, now = 10_000)
        assertEquals(MusicQueue.Action.Landed(1), q.check("Piesa 2", "A2", null, 200_000, now = 11_000))
        assertEquals(
            MusicQueue.Action.Release("takeover"),
            q.onTrack("Lista ei", "Altcineva", null, 210_000, prevPosMs = 60_000, prevDurMs = 200_000, targetPlaying = true, fg = true)
        )
    }

    @Test fun theSongStillPlayingIsNotALanding() {
        // ⏭ pe piesa 2: se cere piesa 3; piesa 2, care încă sună, nu e o aterizare.
        val q = queue()
        issueSecond(q, now = 10_000)
        assertEquals(MusicQueue.Action.Landed(1), q.check("Piesa 2", "A2", null, 200_000, now = 11_000))
        val skip = q.next() as MusicQueue.Action.Issue
        assertEquals(2, skip.index)
        q.issued(skip.index, 20_000)
        assertEquals(MusicQueue.Action.None, q.check("Piesa 2", "A2", null, 200_000, now = 20_100))
        assertEquals(MusicQueue.Action.Landed(2), q.check("Piesa 3", "A3", "spotify:track:3", 200_000, now = 21_000))
        assertEquals(3, q.landed)
    }
}
