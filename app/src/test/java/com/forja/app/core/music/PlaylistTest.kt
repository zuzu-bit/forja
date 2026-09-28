package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lista FORJA a Antrenamentului: trepte, amestec, spațiere, filtre, pornire la rece. */
class PlaylistTest {
    private val day = HistoryCodec.DAY_MS
    private val now = 1_800_000_000_000L

    private fun plays(title: String, artist: String, vararg daysAgo: Double, durS: Int = 200, kind: MediaKind? = MediaKind.MUSIC,
                      src: PlaySource = PlaySource.USER, pkg: String? = SPOTIFY): List<PlayRow> =
        daysAgo.map { PlayRow((now - it * day).toLong(), title, artist, pkg, kind, "spotify:track:${title.hashCode()}", null, durS, src) }

    /** 12 piese noi (8 artiști), 6 vechi, câteva constante. */
    private fun history(): Pair<List<PlayRow>, Map<String, LibraryEntry>> {
        val rows = ArrayList<PlayRow>()
        for (i in 1..12) rows += plays("Nouă $i", "Artist ${i % 8}", 0.5, 1.5, 2.5 + i * 0.1)
        for (i in 1..4) rows += plays("Constantă $i", "Grup $i", 10.0, 20.0)
        val oldRows = ArrayList<PlayRow>()
        for (i in 1..6) oldRows += plays("Veche $i", "Clasic $i", 60.0, 70.0, 80.0, 90.0)
        val lib = HistoryCodec.fold(rows + oldRows)
        return rows to lib
    }

    @Test fun mixStartsWithTheBestNewTrackAndAlternatesNewNewOld() {
        val (rows, lib) = history()
        val p = Playlist.build(rows, lib, Mix.MIX, targetMin = 40, now = now)
        assertFalse(p.liked)
        assertEquals(Tier.NEW, p.items[0].tier)
        val tiers = p.items.map { it.tier }
        assertTrue(tiers.contains(Tier.OLD))
        // După primul loc: [Noi, Noi, Vechi] cât timp există piese vechi.
        assertEquals(listOf(Tier.NEW, Tier.NEW, Tier.OLD), tiers.subList(1, 4))
        assertEquals(SPOTIFY, p.playerPkg)
    }

    @Test fun sameArtistNeverWithinThreeSlots() {
        val (rows, lib) = history()
        val p = Playlist.build(rows, lib, Mix.MIX, targetMin = 60, now = now)
        val artists = p.items.map { TrackKey.artist(it.artist) }
        for (i in artists.indices) for (j in (i + 1)..minOf(i + 3, artists.lastIndex)) {
            assertFalse("${artists[i]} la $i și $j", artists[i] == artists[j])
        }
        val cap = kotlin.math.ceil(p.items.size / 4.0).toInt()
        assertTrue(artists.groupingBy { it }.eachCount().values.all { it <= cap })
    }

    @Test fun lengthFollowsTheSessionAndIsClamped() {
        val (rows, lib) = history()
        assertEquals(Playlist.MIN_ITEMS, Playlist.build(rows, lib, Mix.MIX, targetMin = 5, now = now).items.size)
        val long = Playlist.build(rows, lib, Mix.MIX, targetMin = 500, now = now)
        assertTrue(long.items.size <= Playlist.MAX_ITEMS)
    }

    @Test fun sameDaySameList() {
        val (rows, lib) = history()
        val a = Playlist.build(rows, lib, Mix.MIX, 40, now)
        val b = Playlist.build(rows, lib, Mix.MIX, 40, now + 3_600_000)
        assertEquals(a.items.map { it.key }, b.items.map { it.key })
    }

    @Test fun oldOnlyHasOnlyOldAndSteady() {
        val (rows, lib) = history()
        val p = Playlist.build(rows, lib, Mix.OLD, 40, now)
        assertTrue(p.items.all { it.tier == Tier.OLD || it.tier == Tier.STEADY })
        assertEquals(Tier.OLD, p.items.first().tier)
    }

    @Test fun spokenLongJinglesSkipsAndForjaPlaysAreExcluded() {
        val rows = ArrayList<PlayRow>()
        for (i in 1..6) rows += plays("Bună $i", "Artist $i", 0.2, 1.2)
        rows += plays("Capitol 1", "Autor", 0.3, 1.3, kind = MediaKind.SPOKEN)
        rows += plays("Set lung", "DJ", 0.3, 1.3, durS = 3_600)
        rows += plays("Jingle", "Radio", 0.3, 1.3, durS = 20)
        rows += plays("Doar din listă", "FORJA", 0.3, 1.3, src = PlaySource.FORJA)
        rows += plays("Sărită", "Cineva", 0.2, 1.2)
        repeat(3) { rows += PlayRow(now - day / 2, "Sărită", "Cineva", event = PlayEvent.SKIP) }
        val p = Playlist.build(rows, HistoryCodec.fold(rows), Mix.NEW, 30, now)
        val titles = p.items.map { it.title }
        assertFalse(titles.any { it.startsWith("Capitol") || it == "Set lung" || it == "Jingle" || it == "Doar din listă" || it == "Sărită" })
        assertEquals(6, titles.size)
    }

    @Test fun coldStartGoesStraightToLiked() {
        val rows = plays("Una", "A", 0.2, 0.4) + plays("Două", "B", 0.2, 0.4)
        val p = Playlist.build(rows, HistoryCodec.fold(rows), Mix.MIX, 40, now)
        assertTrue(p.liked)
        assertTrue(p.counts.cold)
    }

    @Test fun emptyTierDoesNotPretend() {
        val rows = ArrayList<PlayRow>()
        for (i in 1..8) rows += plays("Nouă $i", "Artist $i", 0.2, 1.2)
        val lib = HistoryCodec.fold(rows)
        assertEquals(0, Playlist.counts(rows, lib, now).old)
        assertTrue("Vechi fără piese vechi nu se umple cu altele", Playlist.build(rows, lib, Mix.OLD, 40, now).liked)
    }

    @Test fun targetMinutesFromSetsAndReps() {
        // 3 exerciții × 4 serii × (10 × 3 s + 90 s) = 24 min.
        assertEquals(24, Playlist.targetMinutes(List(3) { 4 to 10 }))
        assertEquals(10, Playlist.targetMinutes(emptyList()))
    }
}
