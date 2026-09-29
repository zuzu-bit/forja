package com.forja.app.feature.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Textele noi ale paginii de aplicare respectă TONE.md: fără „!”, ș/ț cu virgulă, butoane ≤ 18 caractere. */
class ApplyCopyTest {
    private val lines = listOf(ApplyCopy.WAITING, ApplyCopy.NOT_SHOWN)
    private val buttons = listOf(ApplyCopy.RETRY, ApplyCopy.BACK)

    @Test fun toneRules() {
        for (t in lines + buttons) {
            assertFalse("„!” în: $t", '!' in t)
            assertFalse("sedilă în: $t", t.any { it in "şţŞŢ" })
            assertEquals("spații în plus: $t", t.trim().replace(Regex("\\s+"), " "), t)
        }
        for (b in buttons) assertTrue("buton peste 18 caractere: $b", b.length <= 18)
        for (l in lines) assertEquals("rândul de stare e cu majuscule: $l", l.uppercase(), l)
    }

    @Test fun oneWordForFoldersAndCommaBelowDiacritics() {
        assertTrue(ApplyCopy.BACK.endsWith("dosare"))       // „dosar”, singurul cuvânt din flux pentru folder
        assertTrue('Ș' in ApplyCopy.WAITING)
        assertTrue('ă' in ApplyCopy.RETRY)
    }
}
