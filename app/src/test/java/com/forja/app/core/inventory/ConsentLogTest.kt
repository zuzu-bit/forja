package com.forja.app.core.inventory

import com.forja.app.core.music.AttemptEvent
import com.forja.app.core.music.DiagResult
import com.forja.app.core.music.MusicLog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rândurile acordului din Inventar stau în jurnalul muzicii (MusicLog), dar cu locurile lor: o aplicare mare nu mai
 * împinge afară încercările muzicii (inelul din memorie, jurnalul de pe telefon, coada de trimis).
 */
class ConsentLogTest {
    private fun row(at: Long, want: String) =
        AttemptEvent(at = at, want = want, rung = "R$at", pkg = null, ver = null, kind = null, result = DiagResult.OK, ms = 0)

    @Test fun consentRowsNeverPushMusicRowsOut() {
        val music = (1L..100L).map { row(it, "workout") }
        val consent = (101L..170L).map { row(it, ConsentLog.WANT) }   // o aplicare de 6 runde, cu rânduri de rezervă
        val kept = MusicLog.keepLast(music + consent, 100, 40)
        assertEquals("toate cele 100 ale muzicii rămân", music, kept.filter { it.want != ConsentLog.WANT })
        assertEquals("din acord, cele mai noi 40", consent.takeLast(40), kept.filter { it.want == ConsentLog.WANT })
        assertEquals("ordinea timpului", kept.sortedBy { it.at }, kept)
    }

    @Test fun eachKindKeepsItsNewestRowsInterleaved() {
        val rows = (1L..12L).map { row(it, if (it % 3 == 0L) ConsentLog.WANT else "mymusic") }
        val kept = MusicLog.keepLast(rows, 3, 2)
        assertEquals(listOf(8L, 9L, 10L, 11L, 12L), kept.map { it.at })
    }

    @Test fun theWantMatchesTheServerWhitelist() {
        assertEquals("consent", ConsentLog.WANT)   // server/music-diag.mjs: MUSIC_DIAG.wants
    }
}
