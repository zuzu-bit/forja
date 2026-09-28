package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planurile, pe cazurile de pe telefonul Lanei (music-start.md §3): a = acces și o sesiune pe pauză, b = acces, playerul
 * omorât, c = fără acces. Se verifică ordinea treptelor care se chiar trimit (cele „sărite” doar se scriu în jurnal).
 */
class PlannerTest {

    private fun runnable(steps: List<Step>) = steps.filter { it.skip == null }.map { it.rung }

    @Test fun a1_pausedSpotifyMusicIsResumedFirst() {
        val s = snap(sessions = listOf(session("sp", SPOTIFY)))
        val plan = runnable(Planner.plan(Want.MyMusic, s))
        assertEquals(Rung.S_PLAY, plan[0])
        assertEquals(Rung.S_BTN, plan[1])
        assertTrue(plan.indexOf(Rung.S_TOP) > 1)
    }

    @Test fun a2_audiobookLatestNeverGetsThePlayCommand() {
        // Cartea audio e prima sesiune; Spotify nu are sesiune; tasta media ar ajunge la carte.
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN, title = "Fetele care ard", artist = "C.J. Tudor")
        val s = snap(sessions = listOf(book), keyTarget = STORYTEL)
        val steps = Planner.plan(Want.MyMusic, s)
        assertTrue(steps.none { it.skip == null && it.sessionId == "book" })
        val key = steps.first { it.rung == Rung.K_PLAY }
        assertEquals("keyTarget:$STORYTEL", key.skip)
        // Prima treaptă care se trimite e vizibilă (o atingere): Spotify.
        assertEquals(Rung.V_TRACK, runnable(steps).first())
        assertEquals(Rung.O_LIKED_PAGE, runnable(steps).last())
    }

    @Test fun a2_spotifyHoldingAnAudiobookGetsTheTopTrackNotPlay() {
        val sp = session("sp", SPOTIFY, kind = MediaKind.SPOKEN, title = "Fetele care ard", mediaId = "spotify:audiobook:1")
        val plan = runnable(Planner.plan(Want.MyMusic, snap(sessions = listOf(sp))))
        assertEquals(listOf(Rung.S_TOP, Rung.S_LIKED), plan.take(2))
        assertFalse(Rung.S_PLAY in plan)
        assertFalse("S_ANY ar relua cartea", Rung.S_ANY in plan)
    }

    @Test fun a3_youtubeSessionIsNotAMusicTarget() {
        val yt = session("yt", YOUTUBE, kind = MediaKind.VIDEO)
        val plan = Planner.plan(Want.MyMusic, snap(sessions = listOf(yt), keyTarget = YOUTUBE))
        assertTrue(plan.none { it.skip == null && it.sessionId == "yt" })
    }

    @Test fun a5_metadataLessSessionIsIgnored() {
        val ghost = session("g", "com.example.game", kind = MediaKind.UNKNOWN, title = null)
        val sp = session("sp", SPOTIFY)
        val plan = Planner.plan(Want.MyMusic, snap(sessions = listOf(ghost, sp)))
        assertEquals("sp", plan.first { it.skip == null }.sessionId)
    }

    @Test fun a6_remoteSessionIsNotResumedAsLocalMusic() {
        val cast = session("sp", SPOTIFY).copy(remote = true)
        val plan = runnable(Planner.plan(Want.MyMusic, snap(sessions = listOf(cast))))
        assertFalse(Rung.S_PLAY in plan)
    }

    @Test fun b1_killedSpotifyIsWokenByTheKeyOnlyWhenItIsTheKeyTarget() {
        val plan = runnable(Planner.plan(Want.MyMusic, snap(sessions = emptyList(), keyTarget = SPOTIFY, keyTokenOutside = true)))
        assertEquals(listOf(Rung.K_TOKEN, Rung.K_PLAY), plan.take(2))
    }

    @Test fun b2_keyGoingToTheAudiobookIsSkipped() {
        val steps = Planner.plan(Want.MyMusic, snap(sessions = emptyList(), keyTarget = STORYTEL))
        assertTrue(steps.filter { it.rung == Rung.K_PLAY }.all { it.skip != null })
        assertTrue(runnable(steps).first().visible)
    }

    @Test fun b4_noPlayerAtAllEndsInTheLauncher() {
        val s = snap(sessions = emptyList(), keyTarget = null, preferred = null, installed = emptySet(), searchable = emptySet(), top = null)
        val plan = Planner.plan(Want.MyMusic, s)
        assertEquals(Rung.O_LAUNCH, plan.last().rung)
    }

    @Test fun c1_noAccessUsesTheKeyThenVisibleRungs() {
        val plan = runnable(Planner.plan(Want.MyMusic, snap(access = false)))
        assertEquals(Rung.K_PLAY, plan.first())
        assertTrue(plan.drop(1).all { it.visible })
    }

    @Test fun resumeCommandsOnlyTheShownSessionAndNeverSpotify() {
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN)
        val sp = session("sp", SPOTIFY)
        val plan = Planner.plan(Want.Resume("book"), snap(sessions = listOf(book, sp)))
        assertEquals(listOf(Rung.S_PLAY, Rung.S_BTN, Rung.O_SESSION), plan.map { it.rung })
        assertTrue(plan.all { it.sessionId == "book" })
    }

    @Test fun resumeWithoutPlayActionUsesTheMediaButton() {
        val s = session("sp", SPOTIFY, actions = SessionView.ACTION_PAUSE)
        val plan = Planner.plan(Want.Resume("sp"), snap(sessions = listOf(s)))
        assertTrue(plan[0].skip!!.startsWith("no-play-action"))
        assertEquals(Rung.S_BTN, runnable(plan).first())
    }

    @Test fun topPlaysTheTopTrackOnTheSession() {
        val sp = session("sp", SPOTIFY, title = "Altceva")
        val plan = Planner.plan(Want.Top, snap(sessions = listOf(sp)))
        assertEquals(Rung.S_TOP, plan[0].rung)
        assertEquals(TOP, plan[0].track)
        assertTrue(plan.any { it.rung == Rung.V_TRACK })
    }

    @Test fun workoutStartsTheFirstListTrackThenLikedThenResume() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY, "spotify:track:1")
        val sp = session("sp", SPOTIFY, title = "Altceva")
        val plan = runnable(Planner.plan(Want.Workout(first), snap(sessions = listOf(sp))))
        assertEquals(listOf(Rung.S_TOP, Rung.S_LIKED, Rung.S_PLAY), plan.take(3))
    }

    @Test fun alreadyPlayingMusicMeansNothingToSend() {
        val playing = session("sp", SPOTIFY, state = PState.PLAYING)
        assertTrue(Planner.alreadyPlaying(Want.MyMusic, snap(sessions = listOf(playing))))
        assertTrue(Planner.alreadyPlaying(Want.Workout(null), snap(sessions = listOf(playing))))
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN, state = PState.PLAYING)
        assertFalse("o carte care cântă nu e muzica ei", Planner.alreadyPlaying(Want.MyMusic, snap(sessions = listOf(book))))
    }

    @Test fun learnedFailuresAreSkippedForTwoWeeks() {
        val now = 1_700_000_000_000L
        var t = LearnedTable.EMPTY
        t = t.record(SPOTIFY, "9.0.62", Rung.V_TRACK, LearnedTable.Outcome.FAIL, now - 1000)
        t = t.record(SPOTIFY, "9.0.62", Rung.V_TRACK, LearnedTable.Outcome.FAIL, now - 500)
        val steps = Planner.plan(Want.MyMusic, snap(sessions = emptyList(), keyTarget = STORYTEL, learned = t, now = now))
        assertEquals("learned-fail", steps.first { it.rung == Rung.V_TRACK }.skip)
    }

    @Test fun learnedSuccessMovesARungFirst() {
        val t = LearnedTable.EMPTY.record(SPOTIFY, "9.0.70", Rung.V_PFS_DATA, LearnedTable.Outcome.OK, 1L)
        val steps = Planner.plan(Want.MyMusic, snap(sessions = emptyList(), keyTarget = STORYTEL, learned = t))
        assertEquals(Rung.V_PFS_DATA, runnable(steps).first())
    }

    @Test fun probeRunsExactlyOneRung() {
        val sp = session("sp", SPOTIFY)
        val plan = Planner.plan(Want.Probe(Rung.S_LIKED, SPOTIFY), snap(sessions = listOf(sp)))
        assertEquals(1, plan.size)
        assertEquals(Step(Rung.S_LIKED, SPOTIFY, "sp"), plan[0])
        val none = Planner.plan(Want.Probe(Rung.K_PLAY, SPOTIFY), snap(sessions = emptyList(), keyTarget = STORYTEL))
        assertEquals("keyTarget:$STORYTEL", none[0].skip)
    }
}
