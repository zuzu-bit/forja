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

    /**
     * Înlocuiește `b4_noPlayerAtAllEndsInTheLauncher` (4.4), care aștepta ca rezultat corect exact eșecul ei de pe 29.09:
     * O_LAUNCH fără pachet (selectorul de muzică, „no-activity” pe S23). Acum un pas final există doar spre un player
     * anume; fără niciunul (Spotify dovedit lipsă), încercarea se termină fără nimic de deschis.
     */
    @Test fun nothingToOpenLeavesNoTerminal() {
        val s = snap(
            sessions = emptyList(), keyTarget = null, preferred = null, installed = emptySet(), searchable = emptySet(), top = null,
            absent = setOf(SPOTIFY), history = emptySet()
        )
        for (want in listOf(Want.MyMusic, Want.Top, Want.Workout(null))) {
            val plan = Planner.plan(want, s)
            assertTrue(want.wire, plan.none { it.rung.terminal })
            assertTrue(want.wire, plan.all { it.skip != null })
        }
        // Detecția goală, dar Spotify nedovedit lipsă: pasul final e pagina lui, niciodată O_LAUNCH fără pachet.
        val undetected = Planner.plan(Want.MyMusic, s.copy(absent = emptySet()))
        assertEquals(Step(Rung.O_LIKED_PAGE, SPOTIFY), undetected.last())
        assertTrue(undetected.none { it.rung == Rung.O_LAUNCH })
    }

    /** Jurnalul ei de pe 29.09 (10:16:03), cu 4.4.1: aceeași lume, acum un salt în Spotify în loc de nota tăiată. */
    @Test fun workout_lanaLog_2909() {
        val steps = Planner.plan(Want.Workout(null), lanaSnap())
        val key = steps.first { it.rung == Rung.K_PLAY }
        assertEquals("keyTarget:$YOUTUBE", key.skip)
        val run = steps.filter { it.skip == null }
        assertEquals(Step(Rung.V_LIKED_PLAY, SPOTIFY), run.first())
        assertEquals(Step(Rung.O_LIKED_PAGE, SPOTIFY), run.last())
        assertTrue("niciodată O_LAUNCH", steps.none { it.rung == Rung.O_LAUNCH })
        assertTrue("YouTube nu primește nimic", run.none { it.pkg == YOUTUBE || it.sessionId == "yt" })

        // Piesa ei de top are ID Spotify, dar lista e încă rece (hubul spune APRECIATE): saltul duce tot la Melodii
        // apreciate, nu la o singură piesă urmată de radioul lui Spotify.
        val withTop = Planner.plan(Want.Workout(null), lanaSnap(top = TOP)).filter { it.skip == null }
        assertEquals(Step(Rung.V_LIKED_PLAY, SPOTIFY), withTop.first())
        assertTrue(withTop.none { it.rung == Rung.V_TRACK || it.rung == Rung.V_PFS_TOP })
        // Chiar dacă tabelul învățat pune V_TRACK primul (a mers la TOP 1 sau „Pornește muzica”).
        val trackFirst = LearnedTable.EMPTY.record(SPOTIFY, "9.0.62", Rung.V_TRACK, LearnedTable.Outcome.OK, 1L)
        assertEquals(Step(Rung.V_LIKED_PLAY, SPOTIFY), Planner.plan(Want.Workout(null), lanaSnap(top = TOP, learned = trackFirst)).first { it.skip == null })
        // TOP 1 și „Pornește muzica” sar în continuare direct la piesa de top.
        assertEquals(Rung.V_TRACK, Planner.plan(Want.Top, lanaSnap(top = TOP)).first { it.skip == null }.rung)
        assertEquals(Rung.V_TRACK, Planner.plan(Want.MyMusic, lanaSnap(top = TOP)).first { it.skip == null }.rung)
    }

    @Test fun spotifyIsNeverHiddenByDetection() {
        // PackageManager n-a găsit nimic (29.09), dar istoricul știe Spotify: treptele lui vizibile rămân toate.
        val s = snap(sessions = emptyList(), keyTarget = STORYTEL, preferred = null, installed = emptySet(), searchable = emptySet(), history = setOf(SPOTIFY))
        val run = Planner.plan(Want.MyMusic, s).filter { it.skip == null }
        assertEquals(listOf(Rung.V_TRACK, Rung.V_LIKED_PLAY, Rung.V_PFS_DATA, Rung.V_PFS_TOP, Rung.O_LIKED_PAGE), run.map { it.rung })
        assertTrue(run.all { it.pkg == SPOTIFY })
        // TOP 1 sare direct la piesă, și fără detecție.
        assertEquals(Rung.V_TRACK, Planner.plan(Want.Top, s).first { it.skip == null }.rung)
    }

    @Test fun spotifyProvenAbsentUsesTheOtherPlayer() {
        val s = snap(
            sessions = emptyList(), keyTarget = null, preferred = null, installed = setOf(YTM), searchable = setOf(YTM), top = null,
            absent = setOf(SPOTIFY), history = emptySet()
        )
        val plan = Planner.plan(Want.MyMusic, s)
        assertEquals(listOf(Step(Rung.V_PFS_ANY, YTM), Step(Rung.O_LAUNCH, YTM)), plan.filter { it.skip == null })
        assertTrue(plan.none { it.pkg == SPOTIFY })
    }

    @Test fun onlyAViewLinkWithoutAnswerProvesSpotifyMissing() {
        // PackageManager nu-l vede și un link VIEW spotify: n-are activitate: Spotify chiar lipsește.
        for (r in listOf(Rung.V_TRACK, Rung.V_LIKED_PLAY, Rung.O_LIKED_PAGE)) {
            assertTrue(r.id, Planner.provesSpotifyMissing(Step(r, SPOTIFY), probedAny = false))
        }
        // O căutare fără răspuns poate veni și de la un Spotify instalat (filtrul lui nu declară forma cu date).
        for (r in listOf(Rung.V_PFS_DATA, Rung.V_PFS_TOP, Rung.V_PFS_ANY)) {
            assertFalse(r.id, Planner.provesSpotifyMissing(Step(r, SPOTIFY), probedAny = false))
        }
        // Sesiunea, alt player sau o detecție care îl vede: nimic dovedit.
        assertFalse(Planner.provesSpotifyMissing(Step(Rung.O_SESSION, SPOTIFY, "sp"), probedAny = false))
        assertFalse(Planner.provesSpotifyMissing(Step(Rung.O_LAUNCH, YTM), probedAny = false))
        assertFalse(Planner.provesSpotifyMissing(Step(Rung.V_LIKED_PLAY, SPOTIFY), probedAny = true))
    }

    @Test fun openStepNeverOpensAVideoForMusic() {
        val yt = session("yt", YOUTUBE, kind = MediaKind.VIDEO, state = PState.PLAYING, title = "Un video")
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN, title = "Fetele care ard")
        // 29.09: o atingere după eșec deschidea YouTube (Music.other). Pentru muzică: Spotify la Melodii apreciate.
        assertEquals(Step(Rung.O_LIKED_PAGE, SPOTIFY), Planner.openStep(yt, null, emptySet()))
        assertEquals(Step(Rung.O_LIKED_PAGE, SPOTIFY), Planner.openStep(book, SPOTIFY, emptySet()))
        // Muzica pe care o vede: exact sesiunea ei.
        assertEquals(Step(Rung.O_SESSION, SPOTIFY, "sp"), Planner.openStep(session("sp", SPOTIFY), SPOTIFY, emptySet()))
        // Playerul ei, când nu e Spotify; Spotify dovedit lipsă → playerul ei; nimic → nimic de deschis.
        assertEquals(Step(Rung.O_LAUNCH, YTM), Planner.openStep(null, YTM, emptySet()))
        assertEquals(Step(Rung.O_LAUNCH, YTM), Planner.openStep(yt, YTM, setOf(SPOTIFY)))
        assertEquals(null, Planner.openStep(yt, null, setOf(SPOTIFY)))
        // Un video nu devine „playerul ei” nici din preferințe.
        assertEquals(Step(Rung.O_LIKED_PAGE, SPOTIFY), Planner.openStep(null, YOUTUBE, emptySet()))
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
