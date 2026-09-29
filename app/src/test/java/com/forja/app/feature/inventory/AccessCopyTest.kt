package com.forja.app.feature.inventory

import com.forja.app.core.inventory.MoveReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Textele 4.4.2 (rândul „Acces complet”, pagina de rezultat) respectă TONE.md: fără „!”, ș/ț cu virgulă (nu sedilă),
 * butoane ≤ 18 caractere, propoziții scurte; plus formele de singular / plural și numele aplicației.
 */
class AccessCopyTest {
    private val buttons = listOf(AccessCopy.ALLOW, AccessCopy.SKIP, AccessCopy.ALLOW_RESULT, AccessCopy.RETRY, AccessCopy.BACK)
    private val lines = listOf(AccessCopy.TITLE, AccessCopy.LINE, AccessCopy.DIR, AccessCopy.PARTIAL, AccessCopy.NOTHING) +
        MoveReason.entries.map { AccessCopy.reason(it, "WhatsApp") } +
        listOf(AccessCopy.ownedLine(6, "WhatsApp"), AccessCopy.ownedLine(1, "WhatsApp"), AccessCopy.ownedLine(3, null, 2))

    @Test fun toneRules() {
        for (t in buttons + lines) {
            assertFalse("„!” în: $t", '!' in t)
            assertFalse("sedilă în: $t", t.any { it in "şţŞŢ" })
            assertEquals("spații în plus: $t", t.trim().replace(Regex("\\s+"), " "), t)
        }
        for (b in buttons) assertTrue("buton peste 18 caractere: $b", b.length <= 18)
        // Rândurile încap în două rânduri de 14 sp pe 360 dp (~90 de caractere, TONE §7).
        for (l in lines) assertTrue("prea lung: $l", l.length <= 90)
    }

    @Test fun theCopyFromTheDesign() {
        assertEquals("Acces complet", AccessCopy.TITLE)
        assertEquals("Mută oriunde, fără ferestre de acord.", AccessCopy.LINE)
        assertEquals("Permite", AccessCopy.ALLOW)
        assertEquals("Fără", AccessCopy.SKIP)
        assertEquals("Permite accesul", AccessCopy.ALLOW_RESULT)
        assertEquals("Încearcă din nou", AccessCopy.RETRY)
        assertEquals("Înapoi la dosare", AccessCopy.BACK)
        assertEquals("6 poze sunt ale WhatsApp. Android le mută doar cu acces complet.", AccessCopy.ownedLine(6, "WhatsApp"))
        assertEquals("Sunt ale WhatsApp: Android le mută doar cu acces complet.", AccessCopy.reason(MoveReason.Owned, "WhatsApp"))
        assertEquals("Dosarul ales cere acces complet.", AccessCopy.reason(MoveReason.Dir))
        assertEquals("Android a refuzat mutarea.", AccessCopy.reason(MoveReason.Denied))
        assertEquals("Au dispărut între timp.", AccessCopy.reason(MoveReason.Gone))
        assertEquals("Sunt pe alt card de memorie.", AccessCopy.reason(MoveReason.Volume))
        assertEquals("Android nu le-a mutat.", AccessCopy.reason(MoveReason.Error))
        assertEquals("Android nu le-a mutat.", AccessCopy.reason(MoveReason.Mismatch))
    }

    @Test fun singularPluralAndUnknownApps() {
        assertEquals("1 poză e a WhatsApp. Android o mută doar cu acces complet.", AccessCopy.ownedLine(1, "WhatsApp"))
        assertEquals("2 poze sunt ale altei aplicații. Android le mută doar cu acces complet.", AccessCopy.ownedLine(2, null))
        assertEquals("5 poze sunt ale altor aplicații. Android le mută doar cu acces complet.", AccessCopy.ownedLine(5, "WhatsApp", apps = 2))
        assertEquals("Sunt ale altei aplicații: Android le mută doar cu acces complet.", AccessCopy.reason(MoveReason.Owned, null))
        assertEquals("1\u00A0204 poze sunt ale Telegram. Android le mută doar cu acces complet.", AccessCopy.ownedLine(1204, "Telegram"))
    }

    @Test fun theRowSaysTheMostSpecificThing() {
        assertEquals(AccessCopy.LINE, AccessCopy.line(AccessUi()))
        assertEquals(AccessCopy.ownedLine(6, "WhatsApp"), AccessCopy.line(AccessUi(owned = 6, app = "WhatsApp", apps = 1)))
        // Dosarul ales în afara Pictures/DCIM: fără acces nu se poate deloc — rândul spune asta și n-are „Fără”.
        val dest = AccessUi(owned = 6, app = "WhatsApp", apps = 1, forDest = true)
        assertEquals(AccessCopy.DIR, AccessCopy.line(dest))
        assertTrue(dest.required)
        assertFalse(AccessUi(owned = 6).required)
    }

    @Test fun oneActionPerResult() {
        assertEquals("Permite accesul", AccessCopy.fix(DoneFix.Access))
        assertEquals("Încearcă din nou", AccessCopy.fix(DoneFix.Retry))
    }
}
