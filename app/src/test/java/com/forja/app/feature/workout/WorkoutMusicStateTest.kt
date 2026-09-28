package com.forja.app.feature.workout

import com.forja.app.core.music.Badge
import com.forja.app.core.music.FPlaylist
import com.forja.app.core.music.Mix
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.Rung
import com.forja.app.core.music.StartState
import com.forja.app.core.music.Step
import com.forja.app.core.music.TierCounts
import com.forja.app.core.music.Track
import com.forja.app.core.music.Want
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun likedBadgeOnlyWithoutTheForjaQueue() {
        val d = discUi(StartState.Playing(Rung.S_LIKED, Badge.LIKED), true, track(true), null, 0, queueActive = false)
        assertEquals(Badge.LIKED, d.badge)
    }
}
