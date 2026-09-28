package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Istoricul local: formatul nou, compatibil cu liniile din 4.3. */
class HistoryCodecTest {

    @Test fun legacyThreeColumnLinesStayValid() {
        val rows = HistoryCodec.parse("1700000000000\tMarș\tFanfara\n1700000100000\tAltă piesă\t")
        assertEquals(2, rows.size)
        assertNull(rows[0].kind)
        assertTrue(rows[0].isMusic)
        assertEquals(PlayEvent.PLAY, rows[0].event)
        assertEquals(PlaySource.USER, rows[0].src)
        assertEquals("", rows[1].artist)
    }

    @Test fun roundTripKeepsEveryField() {
        val r = PlayRow(
            at = 1_700_000_000_000L, title = "Marș\tde\ndimineață", artist = "Fanfara", pkg = SPOTIFY, kind = MediaKind.MUSIC,
            mediaId = "spotify:track:abc", uri = "spotify:track:abc", durS = 220, src = PlaySource.FORJA, event = PlayEvent.SKIP
        )
        val back = HistoryCodec.parseLine(HistoryCodec.line(r))!!
        assertEquals("Marș de dimineață", back.title)
        assertEquals(SPOTIFY, back.pkg)
        assertEquals(MediaKind.MUSIC, back.kind)
        assertEquals("spotify:track:abc", back.mediaId)
        assertEquals(220, back.durS)
        assertEquals(PlaySource.FORJA, back.src)
        assertEquals(PlayEvent.SKIP, back.event)
    }

    @Test fun garbageLinesAreIgnored() {
        assertEquals(0, HistoryCodec.parse("x\ty\tz\n\n1700\t\tA").size)
    }

    @Test fun libraryFoldCountsUserPlaysSkipsAndLatestMetadata() {
        val t0 = 1_700_000_000_000L
        val rows = listOf(
            PlayRow(t0, "Song", "A"),
            PlayRow(t0 + 1, "Song", "A", pkg = SPOTIFY, mediaId = "spotify:track:1", durS = 200, kind = MediaKind.MUSIC),
            PlayRow(t0 + 2, "Song", "A", src = PlaySource.FORJA),
            PlayRow(t0 + 3, "Song (Live)", "A", event = PlayEvent.SKIP)
        )
        val lib = HistoryCodec.fold(rows)
        val e = lib.getValue(TrackKey.of("Song", "A"))
        assertEquals(3, e.plays)
        assertEquals(2, e.userPlays)
        assertEquals(1, e.skips)
        assertEquals("spotify:track:1", e.mediaId)
        assertEquals(t0 + 1, e.lastUserAt)
        val again = HistoryCodec.parseLibrary(HistoryCodec.encodeLibrary(lib))
        assertEquals(e, again.getValue(e.key))
    }

    @Test fun libraryTrimDropsForgottenAndWeakest() {
        val now = 1_800_000_000_000L
        val lib = LinkedHashMap<String, LibraryEntry>()
        fun add(title: String, plays: Int, lastAt: Long) {
            lib[title] = LibraryEntry(title, title, "A", null, null, null, null, 0, lastAt, lastAt, plays, plays, 0, lastAt)
        }
        add("vechi", 50, now - 401L * HistoryCodec.DAY_MS)
        add("slab", 1, now - HistoryCodec.DAY_MS)
        add("tare", 9, now - HistoryCodec.DAY_MS)
        HistoryCodec.trimLibrary(lib, now, max = 1)
        assertEquals(listOf("tare"), lib.keys.toList())
    }
}
