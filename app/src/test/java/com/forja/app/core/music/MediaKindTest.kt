package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clasificatorul muzică / vorbit / video (music-start.md §5.2). */
class MediaKindTest {

    @Test fun spokenAppsAreNeverMusicEvenWhenTheyDeclareAudio() {
        assertEquals(MediaKind.SPOKEN, MusicKind.classify(STORYTEL, category = AppCategory.AUDIO))
        assertEquals(MediaKind.SPOKEN, MusicKind.classify("com.audible.application", content = ContentHint.MUSIC))
    }

    @Test fun videoAppsAreVideo() {
        assertEquals(MediaKind.VIDEO, MusicKind.classify(YOUTUBE))
        assertEquals(MediaKind.VIDEO, MusicKind.classify("com.android.chrome", category = AppCategory.AUDIO))
    }

    @Test fun spotifyEpisodesAndAudiobooksAreSpokenTracksAreMusic() {
        assertEquals(MediaKind.SPOKEN, MusicKind.classify(SPOTIFY, mediaId = "spotify:episode:0abc"))
        assertEquals(MediaKind.SPOKEN, MusicKind.classify(SPOTIFY, mediaId = "spotify:audiobook:7xyz"))
        assertEquals(MediaKind.SPOKEN, MusicKind.classify(SPOTIFY, mediaId = "spotify:chapter:1"))
        // Un ID de piesă bate durata lungă (un mix de 25 de minute rămâne muzică).
        assertEquals(MediaKind.MUSIC, MusicKind.classify(SPOTIFY, mediaId = "spotify:track:4uLU6h", durationMs = 25 * 60_000L))
    }

    @Test fun longItemsWithoutIdAreSpoken() {
        assertEquals(MediaKind.SPOKEN, MusicKind.classify(SPOTIFY, durationMs = 42 * 60_000L))
        assertEquals(MediaKind.MUSIC, MusicKind.classify(SPOTIFY, durationMs = 3 * 60_000L))
    }

    @Test fun contentTypeWhilePlayingDecidesForUnknownApps() {
        assertEquals(MediaKind.SPOKEN, MusicKind.classify("ro.voxa.app", content = ContentHint.SPEECH))
        assertEquals(MediaKind.VIDEO, MusicKind.classify("com.example.tv", content = ContentHint.MOVIE))
        assertEquals(MediaKind.MUSIC, MusicKind.classify("com.example.player", content = ContentHint.MUSIC))
    }

    @Test fun genreAndCategoryFallbacks() {
        assertEquals(MediaKind.SPOKEN, MusicKind.classify("com.example.app", genre = "Podcast"))
        assertEquals(MediaKind.MUSIC, MusicKind.classify("com.example.app", category = AppCategory.AUDIO))
        assertEquals(MediaKind.VIDEO, MusicKind.classify("com.example.app", category = AppCategory.VIDEO))
        assertEquals(MediaKind.UNKNOWN, MusicKind.classify("com.example.app"))
    }

    @Test fun idShapeKeepsPrefixAndLengthOnly() {
        assertEquals("spotify:track:22", MusicKind.idShape("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("other:11", MusicKind.idShape("abcdefghijk"))
        assertNull(MusicKind.idShape(""))
        assertEquals("4uLU6h", MusicKind.spotifyTrackId("spotify:track:4uLU6h"))
        assertNull(MusicKind.spotifyTrackId("spotify:episode:4uLU6h"))
    }

    @Test fun musicPlayerCheckExcludesSpokenAndVideo() {
        assertTrue(MusicKind.isMusicPlayer(SPOTIFY))
        assertTrue(MusicKind.isMusicPlayer("com.example.player", history = setOf("com.example.player")))
        assertFalse(MusicKind.isMusicPlayer(STORYTEL, history = setOf(STORYTEL)))
        assertFalse(MusicKind.isMusicPlayer(YOUTUBE))
        assertFalse(MusicKind.isMusicPlayer(null))
    }
}
