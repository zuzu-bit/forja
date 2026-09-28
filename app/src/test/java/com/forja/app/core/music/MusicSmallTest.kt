package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rezumatul săptămânii, tabelul învățat, coada FORJA, împrumuturile, discul Play/Pauză. */
class MusicSmallTest {
    private val day = HistoryCodec.DAY_MS
    private val now = 1_800_000_000_000L

    // ───────────────────────────── Rezumatul săptămânii ─────────────────────────────

    @Test fun weeklySummaryCountsOnlyMusicPlaysOfTheLastSevenDays() {
        val rows = listOf(
            PlayRow(now - day, "A", "X", SPOTIFY, MediaKind.MUSIC, durS = 240),
            PlayRow(now - 2 * day, "A", "X", SPOTIFY, MediaKind.MUSIC, durS = 240),
            PlayRow(now - 3 * day, "B", "Y", null, null),
            PlayRow(now - 3 * day, "Capitol", "Autor", STORYTEL, MediaKind.SPOKEN, durS = 1800),
            PlayRow(now - 3 * day, "C", "Z", SPOTIFY, MediaKind.MUSIC, event = PlayEvent.SKIP),
            PlayRow(now - 9 * day, "Vechi", "V", SPOTIFY, MediaKind.MUSIC)
        )
        val s = WeeklySummary.build(rows, now) { pkg -> if (pkg == SPOTIFY) "Spotify" else null }
        assertEquals(listOf("A", "B"), s.top.map { it.title })
        assertEquals(2, s.top[0].plays)
        assertEquals(8, s.top[0].minutes)
        assertEquals("Spotify", s.top[0].app)
        assertNull(s.top[1].app)
        assertEquals(11, s.totalMinutes)   // 4 + 4 + 3,4
        val map = s.toMap()
        assertEquals(7, map["windowDays"])
        assertEquals(2, (map["top"] as List<*>).size)
    }

    @Test fun weeklySummaryKeepsTenAtMost() {
        val rows = (1..15).map { PlayRow(now - day, "P$it", "A") }
        assertEquals(10, WeeklySummary.build(rows, now) { null }.top.size)
    }

    // ───────────────────────────── Tabelul învățat ─────────────────────────────

    @Test fun learnedTableRoundTripsAndResetsOnPlayerUpdate() {
        var t = LearnedTable.EMPTY
        t = t.record(SPOTIFY, "9.0.62", Rung.V_TRACK, LearnedTable.Outcome.FAIL, now)
        t = t.record(SPOTIFY, "9.0.62", Rung.V_TRACK, LearnedTable.Outcome.FAIL, now)
        assertTrue(t.skip(SPOTIFY, "9.0.70", Rung.V_TRACK, now + day))
        assertFalse("după 14 zile se reîncearcă", t.skip(SPOTIFY, "9.0.70", Rung.V_TRACK, now + 15 * day))
        assertFalse("o versiune nouă pornește de la zero", t.skip(SPOTIFY, "9.1.2", Rung.V_TRACK, now + day))
        assertEquals(t, LearnedTable.decode(t.encode()))
        val updated = t.record(SPOTIFY, "9.1.2", Rung.S_PLAY, LearnedTable.Outcome.OK, now)
        assertFalse(updated.encode().contains("@9.0"))
    }

    @Test fun learnedOkResetsTheStreakAndMissesStopTheQueue() {
        var t = LearnedTable.EMPTY
        t = t.record(SPOTIFY, "9.0", Rung.S_TOP, LearnedTable.Outcome.FAIL, now)
        t = t.record(SPOTIFY, "9.0", Rung.S_TOP, LearnedTable.Outcome.OK, now + 1)
        t = t.record(SPOTIFY, "9.0", Rung.S_TOP, LearnedTable.Outcome.FAIL, now + 2)
        assertFalse(t.skip(SPOTIFY, "9.0", Rung.S_TOP, now + 3))
        assertTrue(t.tracksLand(SPOTIFY, "9.0"))
        t = t.record(SPOTIFY, "9.0", Rung.S_TOP, LearnedTable.Outcome.MISS, now + 4)
        t = t.record(SPOTIFY, "9.0", Rung.S_TOP, LearnedTable.Outcome.MISS, now + 5)
        assertFalse(t.tracksLand(SPOTIFY, "9.0"))
        assertEquals("9.0", LearnedTable.versionKey("9.0.62.1055"))
        assertEquals("?", LearnedTable.versionKey(null))
    }

    // ───────────────────────────── Coada FORJA ─────────────────────────────

    private fun item(n: Int) = PlayItem(TrackKey.of("Piesa $n", "A$n"), "Piesa $n", "A$n", SPOTIFY, "spotify:track:$n", null, 200, Tier.NEW)

    @Test fun queueAdvancesAfterANaturalEndIncludingCrossfade() {
        val q = MusicQueue(listOf(item(1), item(2), item(3)), emptyList(), SPOTIFY)
        // Radioul Spotify pornește după ce piesa 1 s-a terminat (crossfade de 10 s).
        val a = q.onTrack("Radio", "Cineva", null, 180_000, prevPosMs = 190_000, prevDurMs = 200_000, targetPlaying = true, fg = true)
        assertEquals(MusicQueue.Action.Issue(item(2), 1), a)
        // Piesa cerută a sosit: doar se confirmă.
        assertEquals(MusicQueue.Action.None, q.onTrack("Piesa 2", "A2", "spotify:track:2", 200_000, 0, 180_000, true, true))
        assertEquals(1, q.index)
    }

    @Test fun queueReleasesWhenSheTakesOver() {
        val q = MusicQueue(listOf(item(1), item(2)), emptyList(), SPOTIFY)
        val a = q.onTrack("Altă listă", "Ea", null, 200_000, prevPosMs = 40_000, prevDurMs = 200_000, targetPlaying = true, fg = true)
        assertTrue(a is MusicQueue.Action.Release)
        assertTrue(q.released)
        assertEquals(MusicQueue.Action.None, q.next())
    }

    @Test fun queueHoldsDuringAdsAndNeverCommandsAStoppedPlayerInBackground() {
        val q = MusicQueue(listOf(item(1), item(2)), emptyList(), SPOTIFY)
        assertEquals(MusicQueue.Action.Hold, q.onTrack("Advertisement", "", null, 30_000, 199_000, 200_000, true, true))
        val bg = q.onTrack("Radio", "X", null, 180_000, 199_000, 200_000, targetPlaying = false, fg = false)
        assertEquals(MusicQueue.Action.Release("background-stopped"), bg)
    }

    @Test fun queueRefillsFromReserveThenEnds() {
        val q = MusicQueue(listOf(item(1)), listOf(item(9)), SPOTIFY)
        assertEquals(MusicQueue.Action.Issue(item(9), 1), q.next())
        assertTrue(q.next() is MusicQueue.Action.Release)
    }

    @Test fun queuePreviousRestartsOrGoesBack() {
        val q = MusicQueue(listOf(item(1), item(2)), emptyList(), SPOTIFY)
        q.next()
        assertEquals(MusicQueue.Action.SeekZero, q.previous(12_000))
        assertEquals(MusicQueue.Action.Issue(item(1), 0), q.previous(2_000))
    }

    // ───────────────────────────── Împrumuturi ─────────────────────────────

    @Test fun inventoryFinishNeverStopsWorkoutMusic() {
        val book = LeaseBook()
        val inv = book.acquire(MusicSource.INVENTORY)
        val gym = book.acquire(MusicSource.WORKOUT)
        assertEquals(MusicSource.WORKOUT, book.owner())
        assertFalse("împrumutul Inventarului a căzut", book.release(inv))
        assertEquals(MusicSource.WORKOUT, book.owner())
        assertTrue(book.release(gym))
        assertNull(book.owner())
    }

    @Test fun inventoryFinishNeverPausesDuringAWorkoutEvenWithoutAWorkoutLease() {
        // Muzica ei cânta deja la „Începe sesiunea” (fără împrumut), sau a schimbat lista: sala tot nu tace.
        val book = LeaseBook()
        assertTrue(book.inventoryMayPause())
        book.workoutLive = true
        assertFalse(book.inventoryMayPause())
        book.acquire(MusicSource.INVENTORY)
        assertFalse(book.inventoryMayPause())
        book.workoutLive = false
        assertTrue(book.inventoryMayPause())
    }

    @Test fun inventoryPlayDuringAWorkoutKeepsTheWorkoutLease() {
        val book = LeaseBook()
        val gym = book.acquire(MusicSource.WORKOUT)
        book.acquire(MusicSource.INVENTORY)
        assertEquals(MusicSource.WORKOUT, book.owner())
        assertFalse(book.inventoryMayPause())
        assertTrue("la final, muzica de sală se oprește tot", book.release(gym))
    }

    @Test fun aMediaIdFromAnotherPlayerIsNeverSentToThisOne() {
        val ytm = TrackRef("Piesa", "Artist", YTM, "ytm-video-id", "https://music.youtube.com/watch?v=x")
        assertEquals(TrackRef("Piesa", "Artist", YTM), ytm.forPlayer(SPOTIFY))
        assertEquals(ytm, ytm.forPlayer(YTM))
        val unknown = TrackRef("Piesa", "Artist", null, "id")
        assertEquals(unknown, unknown.forPlayer(SPOTIFY))
    }

    @Test fun takeoverDropsTheLease() {
        val book = LeaseBook()
        val gym = book.acquire(MusicSource.WORKOUT)
        book.drop()
        assertFalse("muzica ei nu se oprește la final", book.release(gym))
    }

    // ───────────────────────────── Discul Play/Pauză (H5) ─────────────────────────────

    @Test fun playNeverSendsPauseAfterAStart() {
        // 4.3: după „Pornește muzica”, fără piesă vizibilă, discul trimitea PAUZĂ. Acum decide doar starea reală.
        val afterStart = StartState.Playing(Rung.K_PLAY)
        assertEquals(ToggleAction.START, Transport.toggle(access = true, heroPlaying = null, audible = false, state = afterStart))
        assertEquals(ToggleAction.START, Transport.toggle(access = false, heroPlaying = null, audible = false, state = afterStart))
        assertEquals(ToggleAction.RESUME, Transport.toggle(true, heroPlaying = false, audible = true, state = afterStart))
        assertEquals(ToggleAction.PAUSE, Transport.toggle(true, heroPlaying = true, audible = false, state = StartState.Idle))
        assertEquals(ToggleAction.PAUSE, Transport.toggle(false, heroPlaying = null, audible = true, state = StartState.Idle))
        assertEquals(ToggleAction.NONE, Transport.toggle(true, null, false, StartState.Starting(Rung.S_PLAY, Want.MyMusic)))
    }
}
