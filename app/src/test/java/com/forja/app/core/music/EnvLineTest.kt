package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rândul ENV (4.4.1): cel mult 120 de caractere, doar coduri de pachete și forma ID-ului, niciun titlu. */
class EnvLineTest {

    @Test fun fitsIn120AndCarriesNoTitles() {
        val secret = TrackRef("Marș secret", "Trupa Ascunsă", SPOTIFY, "spotify:track:4uLU6hMCjMI75M1A2tKUQC")
        // Cel mai lung rând posibil: 3 coduri pe fiecare listă, ID, o excepție cu nume lung.
        val worst = EnvLine.Facts(
            launch = false, info = true,
            installed = listOf(YTM, MusicKind.SAMSUNG_MUSIC, SPOTIFY, "deezer.android.app"),
            sessions = listOf(YTM, YOUTUBE, MusicKind.SAMSUNG_MUSIC, SPOTIFY),
            keyTarget = YTM,
            history = listOf(YTM, MusicKind.SAMSUNG_MUSIC, SPOTIFY, "com.aspiro.tidal"),
            last = YTM, preferred = YTM, track = secret,
            spotifyMediaId = "spotify:track:4uLU6hMCjMI75M1A2tKUQC", fg = false,
            error = "DataStoreCorruptionExceptionWithAVeryLongNameIndeed"
        )
        val line = EnvLine.line(worst)
        assertTrue("${line.length}: $line", line.length <= EnvLine.MAX)
        for (s in listOf("Marș", "secret", "Trupa", "4uLU6", "com.", "deezer", "tidal")) assertFalse(s, line.contains(s))
        assertTrue(line, line.startsWith("v1 la:0 pi:1 "))
        assertTrue(line, line.contains(" top:id "))
        assertTrue(line, line.contains(" idsh:spotify:track:22 "))
        assertTrue(line, line.contains(" ex:DataStore"))
        assertTrue(line, line.contains(" fg:0 "))

        // Ziua ei, cu 4.4.1: exact rândul din design.
        val lana = EnvLine.line(
            EnvLine.Facts(
                launch = false, info = true, installed = listOf(SPOTIFY), sessions = listOf(YOUTUBE), keyTarget = YOUTUBE,
                history = listOf(SPOTIFY), last = SPOTIFY, preferred = SPOTIFY, track = TrackRef("Piesa", "Artist", SPOTIFY),
                spotifyMediaId = "spotify:track:4uLU6hMCjMI75M1A2tKUQC", fg = true, error = null
            )
        )
        assertEquals("v1 la:0 pi:1 inst:sp ses:yt key:yt hist:sp last:sp pref:sp top:q idsh:spotify:track:22 fg:1 ex:-", lana)

        // Nimic știut: toate câmpurile există, cu „-”.
        assertEquals(
            "v1 la:0 pi:0 inst:- ses:- key:- hist:- last:- pref:- top:- idsh:- fg:1 ex:-",
            EnvLine.line(EnvLine.Facts(launch = false, info = false))
        )
        // Un nume de excepție cu spații sau rânduri noi rămâne un singur cuvânt.
        assertFalse(EnvLine.line(EnvLine.Facts(launch = true, info = true, error = "Bad\nName")).contains("\n"))
    }

    @Test fun packageCodes() {
        assertEquals("sp", EnvLine.code(SPOTIFY))
        assertEquals("ytm", EnvLine.code(YTM))
        assertEquals("sm", EnvLine.code(MusicKind.SAMSUNG_MUSIC))
        assertEquals("yt", EnvLine.code(YOUTUBE))
        assertEquals("o", EnvLine.code("deezer.android.app"))
        assertEquals("-", EnvLine.code(null))
        assertEquals("-", EnvLine.codes(emptyList()))
        // Cel mult 3 coduri, fără repetări, în ordinea dată.
        assertEquals("sp,o,ytm", EnvLine.codes(listOf(SPOTIFY, "deezer.android.app", "com.aspiro.tidal", YTM, MusicKind.SAMSUNG_MUSIC)))
        assertEquals("sp", EnvLine.codes(listOf(SPOTIFY, YTM), max = 1))
        // Piesa cerută: are ID Spotify (saltul spotify:track e posibil), doar căutare, niciuna.
        assertEquals("id", EnvLine.trackCode(TOP))
        assertEquals("q", EnvLine.trackCode(TrackRef("Piesa", "Artist")))
        assertEquals("q", EnvLine.trackCode(TrackRef("Piesa", "Artist", YTM, "spotify:track:1")))
        assertEquals("-", EnvLine.trackCode(null))
    }
}
