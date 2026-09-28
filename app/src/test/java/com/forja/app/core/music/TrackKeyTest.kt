package com.forja.app.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cheia pieselor: aceeași melodie scrisă altfel e aceeași cheie. */
class TrackKeyTest {

    @Test fun diacriticsCaseAndSpacingDoNotMatter() {
        assertEquals(TrackKey.of("Marș de dimineață", "Fanfara FORJA"), TrackKey.of("MARS  de   dimineata", "fanfara forja"))
        // Sedila și virgula dau aceeași literă.
        assertEquals(TrackKey.norm("\u015Ftefan \u0163ara"), TrackKey.norm("ștefan țara"))
    }

    @Test fun versionSuffixesAreDropped() {
        val k = TrackKey.of("Bohemian Rhapsody", "Queen")
        assertEquals(k, TrackKey.of("Bohemian Rhapsody - Remastered 2011", "Queen"))
        assertEquals(k, TrackKey.of("Bohemian Rhapsody (Live)", "Queen"))
        assertEquals(TrackKey.of("Song", "A"), TrackKey.of("Song (feat. B)", "A"))
    }

    @Test fun firstArtistOnly() {
        assertEquals(TrackKey.artist("Inna"), TrackKey.artist("Inna, Sean Paul"))
        assertEquals(TrackKey.artist("Inna"), TrackKey.artist("Inna feat. Sean Paul"))
        assertEquals(TrackKey.artist("Inna"), TrackKey.artist("Inna & Sean Paul"))
    }

    @Test fun differentSongsStayDifferent() {
        assertNotEquals(TrackKey.of("Dragostea din tei", "O-Zone"), TrackKey.of("Despre tine", "O-Zone"))
    }

    @Test fun matchesToleratesPlayerDecorations() {
        assertTrue(TrackKey.matches("Bohemian Rhapsody", "Bohemian Rhapsody - Remastered 2011"))
        assertTrue(TrackKey.matches("Marș de dimineață", "Mars de dimineata"))
        assertFalse(TrackKey.matches("Marș de dimineață", "Fetele care ard"))
        assertFalse(TrackKey.matches("Marș", null))
    }

    @Test fun labelJoinsWithDot() {
        assertEquals("Titlu · Artist", TrackKey.label("Titlu", "Artist"))
        assertEquals("Titlu", TrackKey.label("Titlu", " "))
    }
}
