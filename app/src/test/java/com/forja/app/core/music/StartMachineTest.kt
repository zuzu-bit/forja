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

    // ── Tasta doar trezește playerul când se cere o piesă anume (TOP 1, lista FORJA) ──

    @Test fun topWithoutASessionWakesSpotifyThenAsksForTheTopTrack() {
        val p = machine(snap(sessions = emptyList(), keyTarget = SPOTIFY))
        p.onSend = { step ->
            when (step.rung) {
                Rung.K_PLAY -> p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Altă listă"))
                Rung.S_TOP -> p.setState("sp", PState.PLAYING, title = TOP.title)
                else -> Unit
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Top, MusicSource.INVENTORY, tap = true)
        // Spotify s-a trezit cu ultimul lui context: acela tace, apoi se cere piesa de top pe sesiunea acum prezentă.
        assertEquals(listOf(Rung.K_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        assertEquals(TOP, p.sent[1].track)
        assertEquals(UndoTarget.Session("sp"), p.undone.single())
        p.advance(3_000)
        val st = p.last as StartState.Playing
        assertEquals(Rung.S_TOP, st.route)
        assertFalse(st.wrongTrack)
        assertEquals(listOf("K_PLAY:ok", "S_TOP:ok"), p.results())
    }

    @Test fun workoutWithoutASessionWakesSpotifyThenStartsTheForjaList() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY, "spotify:track:1")
        val p = machine(snap(sessions = emptyList(), keyTarget = SPOTIFY))
        p.onSend = { step ->
            when (step.rung) {
                Rung.K_PLAY -> p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Altă listă"))
                Rung.S_TOP -> p.setState("sp", PState.PLAYING, title = "Piesa 1")
                else -> Unit
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = false)
        assertEquals(listOf(Rung.K_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        assertEquals(first, p.sent[1].track)
        p.advance(3_000)
        val st = p.last as StartState.Playing
        assertEquals(Badge.FORJA, st.badge)
        // Reușita e pe S_TOP: MusicStarter pornește coada FORJA doar atunci.
        assertEquals(Rung.S_TOP, p.successes.single().rung)
        assertEquals(listOf("K_PLAY:ok", "S_TOP:ok"), p.results())
    }

    @Test fun oldContextStillPlayingAfterTheWakeIsNeitherSuccessNorRefusal() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY, "spotify:track:1")
        val p = machine(snap(sessions = emptyList(), keyTarget = SPOTIFY))
        p.pauseOnUndo = false
        p.onSend = { step ->
            if (step.rung == Rung.K_PLAY) p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Altă listă"))
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = false)
        assertEquals(listOf(Rung.K_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        // Contextul vechi mai cântă 3 s (pauza e pe drum): nu e „wrong_track”.
        p.advance(3_000)
        assertTrue(p.last.toString(), p.last is StartState.Starting)
        p.setState("sp", PState.PAUSED)
        p.advance(300)
        p.setState("sp", PState.BUFFERING, title = "Piesa 1")
        p.advance(500)
        p.setState("sp", PState.PLAYING)
        p.advance(3_000)
        assertEquals(Badge.FORJA, (p.last as StartState.Playing).badge)
        assertEquals(listOf("K_PLAY:ok", "S_TOP:ok"), p.results())
    }

    // ── 4.4.1: „Începe sesiunea” e atingerea; un singur salt; saltul spre Apreciate doar trezește Spotify ──

    @Test fun workoutStartIsTheTapAndHopsOnce() {
        // 29.09 cu 4.4.1: tasta media la YouTube, nicio sesiune Spotify, lista încă rece.
        val p = machine(lanaSnap())
        p.machine.start(Want.Workout(null), MusicSource.WORKOUT, tap = true)
        assertEquals(listOf(Rung.V_LIKED_PLAY), p.sent.map { it.rung })
        assertTrue(p.results().contains("K_PLAY:skipped"))
        // Spotify nu cântă: după 16 s pasul următor așteaptă o atingere („Deschide Spotify”); tot un singur salt.
        p.advance(16_000)
        val st = p.last as StartState.NeedsTap
        assertEquals(Rung.V_PFS_DATA, st.step.rung)
        assertEquals(1, p.sent.count { it.rung.visible })
        assertTrue(p.results().contains("V_LIKED_PLAY:timeout"))
    }

    @Test fun slowPreparationParksTheHop() {
        // Lista și detecția au durat 2 s: fereastra se măsoară de la atingerea reală, deci nimic nu sare târziu.
        val p = machine(lanaSnap())
        p.machine.start(Want.Workout(null), MusicSource.WORKOUT, tapAt = p.clock - 2_000)
        assertTrue(p.sent.isEmpty())
        val st = p.last as StartState.NeedsTap
        assertEquals(Rung.V_LIKED_PLAY, st.step.rung)
        // Atingerea pe disc face exact saltul acela.
        assertTrue(p.machine.tap())
        assertEquals(listOf(Rung.V_LIKED_PLAY), p.sent.map { it.rung })
    }

    @Test fun aFailedJumpNeverLeadsToASecondOne() {
        // Saltul a pornit un podcast (oprit pe loc): următorul link așteaptă o atingere nouă, deși 1,5 s nu au trecut.
        val p = machine(lanaSnap())
        p.onSend = { step ->
            if (step.rung == Rung.V_LIKED_PLAY) {
                p.add(session("sp", SPOTIFY, kind = MediaKind.SPOKEN, state = PState.PLAYING, title = "Episodul 4", mediaId = "spotify:episode:1"))
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(null), MusicSource.WORKOUT, tap = true)
        p.advance(100)
        assertTrue(p.results().contains("V_LIKED_PLAY:wrong_kind"))
        assertEquals(UndoTarget.Session("sp"), p.undone.single())
        assertEquals(listOf(Rung.V_LIKED_PLAY), p.sent.map { it.rung })
        assertEquals(Rung.V_PFS_DATA, (p.last as StartState.NeedsTap).step.rung)
        // O atingere nouă = un salt nou.
        assertTrue(p.machine.tap())
        assertEquals(listOf(Rung.V_LIKED_PLAY, Rung.V_PFS_DATA), p.sent.map { it.rung })
    }

    @Test fun aLinkThatOpenedNothingIsNotTheHop() {
        // V_TRACK fără activitate (nimic nu s-a deschis): următorul link pleacă tot din atingerea asta.
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY, "spotify:track:1")
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step -> if (step.rung == Rung.V_TRACK) SendResult.Skipped("no-activity") else SendResult.Sent() }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        assertEquals(listOf(Rung.V_TRACK, Rung.V_LIKED_PLAY), p.sent.map { it.rung })
        assertTrue(p.results().contains("V_TRACK:skipped"))
    }

    @Test fun likedHopWakesSpotifyThenTheListTakesOver() {
        // Lista e gata, dar piesa 1 n-are ID Spotify: saltul spre Melodii apreciate doar trezește Spotify, apoi S_TOP cere piesa 1.
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY)
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step ->
            when (step.rung) {
                Rung.V_LIKED_PLAY -> p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Altceva"))
                Rung.S_TOP -> p.setState("sp", PState.PLAYING, title = "Piesa 1")
                else -> Unit
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        assertEquals(listOf(Rung.V_LIKED_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        assertEquals(first, p.sent[1].track)
        assertEquals("ce a ales Spotify tace", UndoTarget.Session("sp"), p.undone.single())
        p.advance(3_000)
        val st = p.last as StartState.Playing
        assertEquals(Rung.S_TOP, st.route)
        assertEquals(Badge.FORJA, st.badge)
        assertEquals(listOf("V_LIKED_PLAY:ok", "S_TOP:ok"), p.results().filter { !it.endsWith(":skipped") })
        // Reușita e pe S_TOP: MusicStarter pornește coada FORJA.
        assertEquals(Rung.S_TOP, p.successes.single().rung)
    }

    @Test fun aSlowWakeStillGivesSTopItsOwnTwelveSeconds() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY)
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step ->
            if (step.rung == Rung.S_TOP) p.setState("sp", PState.BUFFERING, title = "Piesa 1")
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        // Spotify apare abia după 9 s.
        p.advance(9_000)
        p.add(session("sp", SPOTIFY, state = PState.BUFFERING, title = "Altceva"))
        p.advance(100)
        assertEquals(listOf(Rung.V_LIKED_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        // Piesa 1 se încarcă 7 s: S_TOP are propriile 12 s, nu doar restul celor 15 s de la început.
        p.advance(7_000)
        p.setState("sp", PState.PLAYING)
        p.advance(3_000)
        assertEquals(Rung.S_TOP, (p.last as StartState.Playing).route)
        assertEquals(listOf("V_LIKED_PLAY:ok", "S_TOP:ok"), p.results().filter { !it.endsWith(":skipped") })
    }

    @Test fun aJumpNeverSilencesASessionThatSTopAlreadyTried() {
        // Spotify avea o sesiune, dar n-a ascultat de S_TOP / S_LIKED / S_PLAY. Ea atinge „Deschide Spotify”, iar saltul
        // pornește Melodii apreciate pe aceeași sesiune: aceea e muzica (S_TOP a fost deja încercat), nu tace.
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY)
        val p = machine(snap(sessions = listOf(session("sp", SPOTIFY, title = "Altceva")), keyTarget = SPOTIFY))
        p.onSend = { step ->
            if (step.rung == Rung.V_LIKED_PLAY) p.setState("sp", PState.PLAYING, title = "O piesă apreciată")
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = false)
        p.advance(40_000)
        assertEquals(Rung.V_LIKED_PLAY, (p.last as StartState.NeedsTap).step.rung)
        assertTrue(p.sent.any { it.rung == Rung.S_TOP && it.sessionId == "sp" })
        assertTrue(p.machine.tap())
        p.advance(3_000)
        assertEquals(Rung.V_LIKED_PLAY, (p.last as StartState.Playing).route)
        assertTrue("muzica pornită de salt nu tace", p.undone.isEmpty())
    }

    @Test fun afterTheJumpSpotifyIsCommandedEvenWithForjaBehindIt() {
        // Ecranul Spotify acoperă FORJA (fg = nu), iar sesiunea lui apare oprită: S_TOP pleacă totuși (Spotify e în față).
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY)
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step ->
            when (step.rung) {
                Rung.V_LIKED_PLAY -> p.world = p.world.copy(fg = false)
                Rung.S_TOP -> p.setState("sp", PState.PLAYING, title = "Piesa 1")
                else -> Unit
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        p.advance(500)
        p.add(session("sp", SPOTIFY, state = PState.STOPPED, title = "Altceva"))
        p.advance(2_000)
        assertEquals(listOf(Rung.V_LIKED_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        assertTrue(p.events.none { it.err == "background" })
        p.advance(3_000)
        assertEquals(Rung.S_TOP, (p.last as StartState.Playing).route)
        // Fără salt, regula de fundal rămâne: niciodată o comandă către un player oprit (backgroundNeverCommandsAStoppedPlayer).
    }

    @Test fun aJumpThatOnlyShowedSpotifyPausedTeachesNothingAboutAutoplay() {
        // Saltul spre Melodii apreciate a adus sesiunea Spotify, dar pe pauză (linkul nu pornește singur muzica): S_TOP
        // cere piesa 1 și merge. Tabelul învățat nu află că linkul „pornește muzica” (el ordonează linkurile după asta).
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY)
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step ->
            when (step.rung) {
                Rung.V_LIKED_PLAY -> p.add(session("sp", SPOTIFY, state = PState.PAUSED, title = "Altceva"))
                Rung.S_TOP -> p.setState("sp", PState.PLAYING, title = "Piesa 1")
                else -> Unit
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        p.advance(1_600)
        assertEquals(listOf(Rung.V_LIKED_PLAY, Rung.S_TOP), p.sent.map { it.rung })
        p.advance(3_000)
        assertEquals(Rung.S_TOP, (p.last as StartState.Playing).route)
        assertTrue(p.learned.none { it.second == Rung.V_LIKED_PLAY })
        assertTrue(p.learned.contains(Triple<String?, Rung, LearnedTable.Outcome>(SPOTIFY, Rung.S_TOP, LearnedTable.Outcome.OK)))
        // Jurnalul spune că Spotify doar a apărut, nu că a cântat.
        assertEquals("woke idle", p.events.first { it.rung == "V_LIKED_PLAY" }.err)
    }

    @Test fun aJumpThatStartedSpotifyPlayingIsLearnedAsAutoplay() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY)
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step ->
            when (step.rung) {
                Rung.V_LIKED_PLAY -> p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Altceva"))
                Rung.S_TOP -> p.setState("sp", PState.PLAYING, title = "Piesa 1")
                else -> Unit
            }
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        p.advance(3_000)
        assertEquals(Triple<String?, Rung, LearnedTable.Outcome>(SPOTIFY, Rung.V_LIKED_PLAY, LearnedTable.Outcome.OK), p.learned.first())
        assertEquals("woke", p.events.first { it.rung == "V_LIKED_PLAY" }.err)
    }

    @Test fun likedHopIsANormalStartWhenSpotifyIgnoresTheList() {
        // Spotify a ignorat de două ori piesele cerute (tracksLand = nu): saltul e o pornire obișnuită, Apreciate cântă.
        val now = 1_700_000_000_000L
        val refused = LearnedTable.EMPTY
            .record(SPOTIFY, "9.0.62", Rung.S_TOP, LearnedTable.Outcome.MISS, now - 2_000)
            .record(SPOTIFY, "9.0.62", Rung.S_TOP, LearnedTable.Outcome.MISS, now - 1_000)
        val p = machine(lanaSnap(preferred = SPOTIFY, learned = refused))
        p.onSend = { step ->
            if (step.rung == Rung.V_LIKED_PLAY) p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Altceva"))
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(TrackRef("Piesa 1", "Artist", SPOTIFY)), MusicSource.WORKOUT, tap = true)
        p.advance(3_000)
        val st = p.last as StartState.Playing
        assertEquals(Rung.V_LIKED_PLAY, st.route)
        assertEquals(Badge.LIKED, st.badge)
        assertTrue("nimic nu tace", p.undone.isEmpty())
        assertEquals(listOf(Rung.V_LIKED_PLAY), p.sent.map { it.rung })
    }

    @Test fun trackHopGetsTheForjaBadge() {
        val first = TrackRef("Piesa 1", "Artist", SPOTIFY, "spotify:track:1")
        val p = machine(lanaSnap(preferred = SPOTIFY))
        p.onSend = { step ->
            if (step.rung == Rung.V_TRACK) p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Piesa 1", mediaId = "spotify:track:1"))
            SendResult.Sent()
        }
        p.machine.start(Want.Workout(first), MusicSource.WORKOUT, tap = true)
        assertEquals(listOf(Rung.V_TRACK), p.sent.map { it.rung })
        p.advance(3_000)
        val st = p.last as StartState.Playing
        assertEquals(Rung.V_TRACK, st.route)
        assertEquals(Badge.FORJA, st.badge)
        // Reușita e pe V_TRACK: coada FORJA pornește de la piesa 2; V_TRACK nu trezește, deci nimic nu tace.
        assertEquals(Rung.V_TRACK, p.successes.single().rung)
        assertTrue(p.undone.isEmpty())
    }

    @Test fun nothingToOpenFailsWithoutAnOpenStep() {
        val p = machine(
            snap(sessions = emptyList(), keyTarget = null, preferred = null, installed = emptySet(), searchable = emptySet(), top = null,
                absent = setOf(SPOTIFY), history = emptySet())
        )
        p.machine.start(Want.Workout(null), MusicSource.WORKOUT, tap = true)
        assertTrue(p.sent.isEmpty())
        assertEquals(StartState.Failed(FailReason.NO_PLAYER, null), p.last)
        assertFalse("nimic nu așteaptă o atingere", p.machine.tap())
    }

    @Test fun myMusicKeyThatWakesSpotifyMusicIsDoneWithoutMoreCommands() {
        val p = machine(snap(sessions = emptyList(), keyTarget = SPOTIFY))
        p.onSend = { step ->
            if (step.rung == Rung.K_PLAY) p.add(session("sp", SPOTIFY, state = PState.PLAYING, title = "Muzica ei"))
            SendResult.Sent()
        }
        p.machine.start(Want.MyMusic, MusicSource.INVENTORY, tap = true)
        p.advance(3_000)
        assertEquals(Rung.K_PLAY, (p.last as StartState.Playing).route)
        assertEquals(listOf(Rung.K_PLAY), p.sent.map { it.rung })
        assertTrue(p.undone.isEmpty())
    }
}
