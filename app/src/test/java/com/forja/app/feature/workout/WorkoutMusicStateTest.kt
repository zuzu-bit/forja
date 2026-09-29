package com.forja.app.feature.workout

import com.forja.app.core.music.Badge
import com.forja.app.core.music.FPlaylist
import com.forja.app.core.music.FailReason
import com.forja.app.core.music.Mix
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicQueue
import com.forja.app.core.music.Rung
import com.forja.app.core.music.StartState
import com.forja.app.core.music.Step
import com.forja.app.core.music.TierCounts
import com.forja.app.core.music.Track
import com.forja.app.core.music.Want
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eticheta rândului „Muzică”, alegerile din foaie și fazele discului (fără Android). */
class WorkoutMusicStateTest {

    @Test fun labelsFollowTheChoiceAndTheHistory() {
        val s = WorkoutMusicSamples.music
        assertEquals("MIX · 12 PIESE", s.label)
        assertEquals("VECHI · ${s.lists.getValue(Mix.OLD).items.size}", s.copy(mix = Mix.OLD).label)
        assertEquals("APRECIATE", s.copy(mix = Mix.LIKED).label)
        assertEquals("OPRITĂ", s.copy(on = false).label)
        // Puține ascultări: pornește Melodii apreciate, iar eticheta o spune.
        assertEquals("APRECIATE", WorkoutMusicSamples.cold.label)
        assertEquals(Mix.LIKED, WorkoutMusicSamples.cold.effective)
        // Fără acces: doar Apreciate.
        assertEquals("APRECIATE", WorkoutMusicSamples.noAccess.label)
    }

    @Test fun emptyChoicesAreDisabled() {
        val cold = WorkoutMusicSamples.cold
        assertFalse(cold.enabled(Mix.MIX))
        assertFalse(cold.enabled(Mix.OLD))
        assertTrue(cold.enabled(Mix.LIKED))
        val empty = WorkoutMusicSamples.music.copy(lists = WorkoutMusicSamples.music.lists + (Mix.OLD to FPlaylist(Mix.OLD, emptyList(), emptyList(), null, TierCounts())))
        assertFalse(empty.enabled(Mix.OLD))
        assertEquals(null, empty.count(Mix.OLD))
    }

    private fun track(playing: Boolean) = Track("Marș", "Fanfara", null, null, "Spotify", playing, 60_000, 200_000, pkg = MusicKind.SPOTIFY)

    @Test fun discPhases() {
        assertEquals(DiscPhase.IDLE, discUi(StartState.Idle, true, null, null, 0, false).phase)
        assertEquals(DiscPhase.STARTING, discUi(StartState.Starting(Rung.S_TOP, Want.MyMusic), true, null, null, 0, false).phase)
        val needs = discUi(StartState.NeedsTap(Step(Rung.V_LIKED_PLAY, MusicKind.SPOTIFY), Want.Workout(null)), true, null, null, 0, false)
        assertEquals(DiscPhase.NEEDS_TAP, needs.phase)
        assertEquals("Deschide Spotify", needs.action)
        assertEquals(DiscPhase.FAILED, discUi(StartState.Failed(com.forja.app.core.music.FailReason.TIMEOUT, null), true, null, null, 0, false).phase)
        val playing = discUi(StartState.Playing(Rung.S_TOP, Badge.FORJA), true, track(true), null, 100_000, queueActive = true)
        assertEquals(DiscPhase.PLAYING, playing.phase)
        assertEquals(Badge.FORJA, playing.badge)
        assertEquals(0.5f, playing.progress, 0.001f)
        assertEquals(DiscPhase.PAUSED, discUi(StartState.Idle, true, track(false), null, 0, false).phase)
        // O pornire cerută de ecranul Muzică nu apare pe discul Antrenamentului.
        assertEquals(DiscPhase.IDLE, discUi(StartState.Starting(Rung.S_TOP, Want.MyMusic), fromWorkout = false, null, null, 0, false).phase)
    }

    @Test fun withoutAccessAnAudiblePlayerShowsPlaying() {
        // Fără acces nu există piesă arătată: singurul semn e playerul care se aude — discul arată „cântă” (atingerea = pauză).
        val d = discUi(StartState.Playing(Rung.K_PLAY), true, null, null, 0, false, audible = true)
        assertEquals(DiscPhase.PLAYING, d.phase)
        assertEquals(null, d.art)
        assertEquals(DiscPhase.IDLE, discUi(StartState.Playing(Rung.K_PLAY), true, null, null, 0, false, audible = false).phase)
    }

    @Test fun likedBadgeOnlyWithoutTheForjaQueue() {
        val d = discUi(StartState.Playing(Rung.S_LIKED, Badge.LIKED), true, track(true), null, 0, queueActive = false)
        assertEquals(Badge.LIKED, d.badge)
    }

    // ───────────── 4.4.1 ─────────────

    @Test fun failedWithNothingToOpenHasNoActionAndATapRetries() {
        // 29.09: „Nu a pornit.” cu „Deschide playerul”, iar atingerea nu deschidea nimic. Acum: fără nimic de deschis, fără acțiune.
        val none = discUi(StartState.Failed(FailReason.NO_PLAYER, null), true, null, null, 0, false)
        assertEquals(DiscPhase.FAILED, none.phase)
        assertNull(none.action)
        assertEquals("Muzica nu a pornit. Atinge ca să încerci din nou", discDescription(none))
        val sp = discUi(StartState.Failed(FailReason.TIMEOUT, Step(Rung.O_LIKED_PAGE, MusicKind.SPOTIFY)), true, null, null, 0, false)
        assertEquals("Deschide Spotify", sp.action)
        assertEquals("Muzica nu a pornit. Atinge ca să deschizi Spotify", discDescription(sp))
        val other = discUi(StartState.Failed(FailReason.TIMEOUT, Step(Rung.O_LAUNCH, MusicKind.YT_MUSIC)), true, null, null, 0, false)
        assertEquals("Deschide playerul", other.action)
        assertEquals("Muzica nu a pornit. Atinge ca să deschizi playerul", discDescription(other))
        // Starea „pornește” rămâne aceeași cât Spotify e în față (saltul).
        assertEquals("Muzică: pornește", discDescription(discUi(StartState.Starting(Rung.V_LIKED_PLAY, Want.Workout(null)), true, null, null, 0, false)))
    }

    @Test fun refusedListLosesTheForjaDot() {
        // Spotify n-a primit piesa cerută: coada s-a retras, discul arată coperta fără punctul olive.
        val refused = discUi(StartState.Playing(Rung.S_TOP, Badge.FORJA), true, track(true), null, 0, queueActive = false)
        assertEquals(DiscPhase.PLAYING, refused.phase)
        assertEquals(Badge.NONE, refused.badge)
        assertEquals(Badge.FORJA, discUi(StartState.Playing(Rung.V_TRACK, Badge.FORJA), true, track(true), null, 0, queueActive = true).badge)
    }

    @Test fun hopGlyphIsSpokenAndTheCopyFitsTheRules() {
        assertEquals("Spotify se deschide o clipă", WorkoutMusicSamples.coldHop.hopLabel)
        assertEquals("APRECIATE", WorkoutMusicSamples.coldHop.label)
        assertTrue(SHEET_INFO.contains("Pe Spotify gratuit, FORJA cere piesele pe rând, cât timp Spotify le primește."))
        assertEquals("antrenament_441", ANTRENAMENT_GUIDE)
        assertEquals("Muzica ta pornește odată cu sesiunea. Dacă Spotify e închis, apare o clipă.", ANTRENAMENT_STEPS.single().text)
        // Fără „!”, ș și ț cu virgulă dedesubt (nu cu sedilă).
        val copy = listOf(SHEET_INFO, ANTRENAMENT_STEPS.single().text, WorkoutMusicSamples.coldHop.hopLabel, MusicQueue.refusedNotice(MusicKind.SPOTIFY)) +
            listOf(WorkoutMusicSamples.discFailedRetry, WorkoutMusicSamples.discFailedSpotify, WorkoutMusicSamples.discFailed).map { discDescription(it) }
        for (c in copy) {
            assertFalse(c, c.contains('!'))
            assertFalse(c, c.contains('ş') || c.contains('ţ') || c.contains('Ş') || c.contains('Ţ'))
        }
    }
}
