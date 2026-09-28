package com.forja.app.feature.permissions

import com.forja.app.core.data.Prefs
import com.forja.app.core.recovery.FinderLogic
import com.forja.app.core.recovery.FinderState
import com.forja.app.feature.recovery.GASIRE_INFO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Textul contractului v3 și al găsirii, verificat ca text (fără randare): diacritice cu virgulă, fără „!”,
 * propoziții încheiate, rândurile noi cerute de DESIGN-4.4 §3.6 și corecturile din auditul contractului.
 */
class ContractCopyTest {

    private val lines = CONTRACT_CLAUSES.flatMap { it.lines }.map { it.text }
    private val all = lines + CONTRACT_CLAUSES.map { it.label } + GASIRE_INFO + FIXED_SUMMARY +
        RESIGN_POINTS.flatMap { listOf(it.first, it.second) } +
        FinderState.entries.map { it.word } + listOf(FinderLogic.locateText(60_000L, 0L))

    @Test fun versionIsThree() = assertEquals(3, Prefs.CONTRACT_VERSION)

    @Test fun commaBelowDiacriticsNoExclamationNoEmoji() {
        for (t in all) {
            assertFalse("sedilă în: $t", t.any { it in "şţŞŢ" })
            assertFalse("„!” în: $t", '!' in t)
            assertFalse("emoji în: $t", t.codePoints().anyMatch { it >= 0x1F000 })
            assertFalse("spațiu dublu în: $t", "  " in t)
        }
    }

    @Test fun everyLineIsAFinishedSentence() {
        (lines + RESIGN_POINTS.map { it.second } + FIXED_SUMMARY).forEach { assertTrue("fără punct: $it", it.trimEnd().endsWith(".")) }
    }

    @Test fun resignSheetStaysShortForTheS23() {
        assertEquals(4, RESIGN_POINTS.size)
        RESIGN_POINTS.forEach { (title, brief) ->
            assertTrue("titlu lung: $title", title.length <= 24)
            assertTrue("rând lung (peste 2 rânduri pe 360 dp): $brief", brief.length <= 90)
        }
    }

    @Test fun newLinesCoverTheNewAbilities() {
        val fresh = CONTRACT_CLAUSES.flatMap { it.lines }.filter { it.mark == ClauseMark.New }.map { it.text }
        listOf("Găsirea telefonului", "Inventarul", "Muzica", "Ținta de calorii").forEach { head ->
            assertTrue("lipsește rândul nou „$head”", fresh.any { it.startsWith(head) })
        }
        // Fiecare rând nou din „Ce se încarcă” are un punct scurt în foaia de re-semnare (4 puncte, ținta e lângă antrenamente).
        assertEquals(4, CONTRACT_CLAUSES.first().lines.count { it.mark == ClauseMark.New })
    }

    @Test fun auditFixesAreInTheText() {
        val text = lines.joinToString(" ")
        assertFalse("contractul nu mai promite pași", Regex("\\bpași\\b").containsMatchIn(text))
        assertTrue("codul de bare merge la OpenFoodFacts", "OpenFoodFacts" in text)
        assertTrue("jurnalele stau în Firebase", "Google Firebase" in text)
        assertTrue("galeria pe site: 24 de ore, cel mult 500", "cel mult 500" in text)
        assertTrue("revocarea scoate telefonul din Găsire", lines.any { it.startsWith("Profil → Contract → Revocă") && "Găsire" in it })
        assertTrue("familia la „Cine vede”", CONTRACT_CLAUSES.first { it.label == "Cine vede" }.lines.any { "Familia" in it.text })
        assertFalse("galeria nu mai e „întreagă”", "Galeria întreagă" in text)
    }

    @Test fun finderCopyMatchesTheDesign() {
        assertTrue("Închis sau descărcat, rămâne ultima poziție" in GASIRE_INFO)
        assertTrue("10 minute" in GASIRE_INFO)
    }
}
