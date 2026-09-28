package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mașina de stări cu o lume falsă (ceas controlat, sesiuni care reacționează la comenzi). Fiecare caz e o situație
 * reală: pornire caldă, refuz, carte trezită de tastă, pornire lentă, salt vizibil doar la atingere, fără acces.
 */
class StartMachineTest {

    private fun machine(world: Snapshot): FakePort {
        val port = FakePort(world)
        port.machine = StartMachine(port)
        return port
    }

    @Test fun warmResumeOfPausedSpotifySucceedsAfterTheHold() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY))))
        p.onSend = { step -> if (step.rung == Rung.S_PLAY) p.setState("sp", PState.PLAYING); SendResult.Sent() }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        assertTrue(p.last is StartState.Starting)
        p.advance(1_000)
        assertTrue("nu înainte de fereastra de refuz", p.last is StartState.Starting)
        p.advance(2_000)
        val st = p.last as StartState.Playing
        assertEquals(Rung.S_PLAY, st.route)
        assertEquals(listOf("S_PLAY:ok"), p.results())
        assertEquals(Triple<String?, Rung, LearnedTable.Outcome>(SPOTIFY, Rung.S_PLAY, LearnedTable.Outcome.OK), p.learned.single())
        assertEquals(1, p.successes.size)
    }

    @Test fun playThenSelfPauseWithinThreeSecondsIsRefusedAndTheNextRungRuns() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY))))
        p.onSend = { step -> if (step.rung == Rung.S_PLAY) p.setState("sp", PState.PLAYING); SendResult.Sent() }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(800)
        p.setState("sp", PState.PAUSED)
        p.advance(200)
        assertEquals("S_PLAY:refused", p.results().first())
        assertEquals(Rung.S_BTN, p.sent[1].rung)
    }

    @Test fun silentPlayerTimesOutAndFallsThroughWithoutEverSendingPause() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY))))
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(20_000)
        assertTrue(p.results().first() == "S_PLAY:timeout")
        assertTrue(p.undone.isEmpty())
        // După ce invizibilele au eșuat și a trecut fereastra atingerii: Spotify așteaptă o atingere, nu sare singur.
        val last = p.last
        assertTrue(last.toString(), last is StartState.NeedsTap)
        assertTrue(p.sent.none { it.rung.visible })
    }

    @Test fun visibleRungRunsImmediatelyWhenItIsTheTapsDirectResult() {
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN, title = "Fetele care ard")
        val p = machine(snap(sessions = listOf(book), keyTarget = STORYTEL))
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        assertEquals(Rung.V_TRACK, p.sent.single().rung)
        assertTrue(p.results().contains("K_PLAY:skipped"))
        assertTrue("cartea nu primește nicio comandă", p.sent.none { it.sessionId == "book" })
    }

    @Test fun automaticStartNeverHopsIntoThePlayer() {
        val p = machine(snap(sessions = emptyList(), keyTarget = STORYTEL))
        p.machine.start(Want.Workout(TOP), MusicSource.WORKOUT, tap = false)
        assertTrue(p.sent.isEmpty())
        val st = p.last as StartState.NeedsTap
        assertTrue(st.step.rung.visible)
        // Atingerea pe disc face exact pasul acela.
        assertTrue(p.machine.tap())
        assertEquals(st.step.rung, p.sent.single().rung)
    }

    @Test fun keyThatWakesAnAudiobookIsUndoneAndTheLadderContinues() {
        val p = machine(snap(sessions = emptyList(), keyTarget = SPOTIFY))
        p.onSend = { step ->
            if (step.rung == Rung.K_PLAY) p.add(session("sp", SPOTIFY, kind = MediaKind.SPOKEN, state = PState.PLAYING, title = "Capitolul 3", mediaId = "spotify:chapter:9"))
            SendResult.Sent()
        }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(200)
        assertEquals(UndoTarget.Session("sp"), p.undone.single())
        assertTrue(p.results().contains("K_PLAY:wrong_kind"))
        // Spotify există acum, cu o carte: următoarea treaptă cere piesa de top.
        assertEquals(Rung.S_TOP, p.sent[1].rung)
        assertEquals(TOP, p.sent[1].track)
    }

    @Test fun bufferingExtendsTheWaitUpToTheCap() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY))))
        p.onSend = { step -> if (step.rung == Rung.S_PLAY) p.setState("sp", PState.BUFFERING); SendResult.Sent() }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(6_000)
        assertTrue("încărcarea nu e un eșec la 4 s", p.results().isEmpty())
        p.setState("sp", PState.PLAYING)
        p.advance(3_000)
        assertTrue(p.last is StartState.Playing)
        assertEquals(listOf("S_PLAY:ok"), p.results())
    }

    @Test fun alreadyPlayingMusicSendsNothing() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY, state = PState.PLAYING))))
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        assertTrue(p.sent.isEmpty())
        assertEquals(Rung.ALREADY, (p.last as StartState.Playing).route)
        assertEquals(listOf("ALREADY:ok"), p.results())
    }

    @Test fun topTrackMismatchIsAcceptedButMarkedWrongTrack() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY, kind = MediaKind.SPOKEN, title = "Cartea"))))
        p.onSend = { step ->
            if (step.rung == Rung.S_TOP) p.setState("sp", PState.PLAYING, kind = MediaKind.MUSIC, title = "Radio: altă piesă")
            SendResult.Sent()
        }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(3_000)
        val st = p.last as StartState.Playing
        assertTrue(st.wrongTrack)
        assertEquals(listOf("S_TOP:wrong_track"), p.results())
        assertEquals(LearnedTable.Outcome.MISS, p.learned.single().third)
    }

    @Test fun workoutFirstTrackLandingGetsTheForjaBadge() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY, "spotify:track:1")
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY, title = "Altceva"))))
        p.onSend = { step ->
            if (step.rung == Rung.S_TOP) p.setState("sp", PState.PLAYING, title = "Piesa 1")
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = false)
        p.advance(3_000)
        assertEquals(Badge.FORJA, (p.last as StartState.Playing).badge)
    }

    @Test fun resumeTargetsTheBookAndSucceedsWhenTheBookPlays() {
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN, title = "Fetele care ard")
        val p = machine(snap(sessions = listOf(book, session("sp", SPOTIFY))))
        p.onSend = { step -> if (step.sessionId == "book") p.setState("book", PState.PLAYING); SendResult.Sent() }
        p.machine.start(Want.Resume("book"), MusicSource.INVENTORY, tap = true)
        p.advance(3_000)
        assertTrue(p.last is StartState.Playing)
        assertTrue(p.sent.all { it.sessionId == "book" })
        assertTrue(p.undone.isEmpty())
    }

    @Test fun failedResumeOffersToOpenThatPlayerNeverSpotify() {
        val book = session("book", STORYTEL, kind = MediaKind.SPOKEN)
        val p = machine(snap(sessions = listOf(book)))
        p.machine.start(Want.Resume("book"), MusicSource.INVENTORY, tap = true)
        p.advance(20_000)
        val st = p.last as StartState.Failed
        assertEquals(Rung.O_SESSION, st.open!!.rung)
        assertEquals("book", st.open!!.sessionId)
        assertTrue(p.sent.none { it.pkg == SPOTIFY })
    }

    @Test fun noAccessKeyVerifiedByAMusicPlayerConfig() {
        val p = machine(snap(access = false))
        p.onSend = { step ->
            if (step.rung == Rung.K_PLAY) p.world = p.world.copy(configs = listOf(ConfigView(7, ContentHint.MUSIC)))
            SendResult.Sent()
        }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(3_000)
        assertTrue(p.last is StartState.Playing)
    }

    @Test fun noAccessSpeechStartedByTheKeyIsPausedWithTheKey() {
        val p = machine(snap(access = false))
        p.onSend = { step ->
            if (step.rung == Rung.K_PLAY) p.world = p.world.copy(configs = listOf(ConfigView(9, ContentHint.SPEECH)))
            SendResult.Sent()
        }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(100)
        assertEquals(UndoTarget.Key, p.undone.single())
        assertTrue(p.results().contains("K_PLAY:wrong_kind"))
    }

    @Test fun terminalRungEndsInPlayerAndReturnDecidesTheState() {
        val p = machine(snap(sessions = emptyList(), keyTarget = null, top = null, searchable = emptySet()))
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        // Nimic invizibil de încercat; primul vizibil e V_LIKED_PLAY, apoi…
        p.advance(11_000)
        val failed = p.last
        assertTrue(failed.toString(), failed is StartState.NeedsTap || failed is StartState.Failed)
        while (p.last !is StartState.Failed) {
            p.machine.tap()
            p.advance(11_000)
        }
        val f = p.last as StartState.Failed
        assertEquals(Rung.O_LIKED_PAGE, f.open!!.rung)
        p.machine.tap()
        assertTrue(p.last is StartState.InPlayer)
        p.machine.resumed()
        assertEquals(StartState.Idle, p.last)
        assertEquals("O_LIKED_PAGE:timeout", p.results().last())
    }

    @Test fun backgroundNeverCommandsAStoppedPlayer() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY, state = PState.STOPPED)), fg = false))
        p.machine.start(Want.Workout(null), MusicSource.WORKOUT, tap = false)
        assertTrue(p.sent.none { it.sessionId == "sp" })
        assertTrue(p.events.any { it.err == "background" })
    }

    @Test fun cancelReturnsToIdle() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY))))
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.machine.cancel()
        assertEquals(StartState.Idle, p.last)
        assertFalse(p.machine.active)
        p.advance(10_000)
        assertEquals(1, p.sent.size)
    }

    @Test fun diagnosticsCarryNoTitles() {
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY, title = "Marș secret"))))
        p.onSend = { p.setState("sp", PState.PLAYING); SendResult.Sent() }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(3_000)
        assertTrue(p.events.isNotEmpty())
        assertTrue(p.events.none { (it.err ?: "").contains("Marș") })
        assertEquals("9.0.62", p.events.first().ver)
        assertEquals("mymusic", p.events.first().want)
    }
}
