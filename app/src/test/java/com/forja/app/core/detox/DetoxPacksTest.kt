package com.forja.app.core.detox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Paznicul numără doar codul pachetului care a prins, niciodată textul. */
class DetoxPacksTest {
    private val words = DetoxPacks.parseWords("pariuri\ncasino\nfumez iar\nab")

    @Test fun builtInListWinsAsForjaList() = assertEquals("18", DetoxPacks.match("vezi pornhub acum", words))
    @Test fun aPackWordCountsForItsPack() = assertEquals("02", DetoxPacks.match("bilet la pariuri azi", words))
    @Test fun herOwnWordsAreOwn() = assertEquals("own", DetoxPacks.match("iar fumez iar", words))
    @Test fun shortWordsAndCleanTextDoNotMatch() {
        assertNull(DetoxPacks.match("ab cd", words)); assertNull(DetoxPacks.match("o zi bună", words))
    }
    @Test fun theResultIsOnlyACode() {
        for (hay in listOf("pornhub", "pariuri", "fumez iar")) assert(DetoxPacks.match(hay, words) in listOf("01", "02", "03", "04", "18", "own"))
    }
}
