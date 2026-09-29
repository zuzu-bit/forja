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
 * Textul contractului v4 și al găsirii, verificat ca text (fără randare): diacritice cu virgulă, fără „!”,
 * propoziții încheiate, rândurile noi ale oglinzii (v4, marcate față de v3) și corecturile din auditul contractului.
 */
class ContractCopyTest {

    private val lines = CONTRACT_CLAUSES.flatMap { it.lines }.map { it.text }
    private val all = lines + CONTRACT_CLAUSES.map { it.label } + GASIRE_INFO + FIXED_SUMMARY +
        RESIGN_POINTS.flatMap { listOf(it.first, it.second) } +
        FinderState.entries.map { it.word } + listOf(FinderLogic.locateText(60_000L, 0L))

    @Test fun versionIsFourOnTopOfThree() {
        assertEquals(4, Prefs.CONTRACT_VERSION)
        assertEquals(3, Prefs.CONTRACT_BASE)
    }

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
        assertEquals(5, RESIGN_POINTS.size)
        RESIGN_POINTS.forEach { (title, brief) ->
            assertTrue("titlu lung: $title", title.length <= 24)
            assertTrue("rând lung (peste 2 rânduri pe 360 dp): $brief", brief.length <= 90)
        }
    }

    @Test fun newLinesCoverTheNewAbilities() {
        val fresh = CONTRACT_CLAUSES.flatMap { it.lines }.filter { it.mark == ClauseMark.New }.map { it.text }
        listOf(
            "Cât îl cauți", "Poza mesei", "Câte poze", "Coperțile", "Concentrarea", "Cuvintele", "Casca",
            "Jurnalul de ascultare", "Jocurile", "Cronologia nopții", "Galeria: fiecare poză", "Documentele din folderele alese",
        ).forEach { head ->
            assertTrue("lipsește rândul nou „$head”", fresh.any { it.startsWith(head) })
        }
        // Ce era nou în v3 (găsirea, Inventarul, muzica, ținta) nu mai e marcat.
        listOf("Găsirea telefonului", "Inventarul", "Muzica", "Ținta de calorii").forEach { head ->
            assertFalse("„$head” e din v3, nu e nou", fresh.any { it.startsWith(head) })
        }
        // Cele 12 rânduri noi din „Ce se încarcă” (cu oglinda galeriei și a documentelor) încap în cele 5 puncte ale foii de re-semnare.
        assertEquals(12, CONTRACT_CLAUSES.first().lines.count { it.mark == ClauseMark.New })
        assertTrue("cuvintele detoxului: implicit oprit", fresh.any { it.startsWith("Cuvintele") && "Implicit e oprit" in it })
    }

    @Test fun auditFixesAreInTheText() {
        val text = lines.joinToString(" ")
        assertFalse("contractul nu mai promite pași", Regex("\\bpași\\b").containsMatchIn(text))
        assertTrue("codul de bare merge la OpenFoodFacts", "OpenFoodFacts" in text)
        assertTrue("jurnalele stau în Firebase", "Google Firebase" in text)
        assertTrue("galeria și documentele pe site: cât ai contul", lines.any { it.startsWith("Galeria și documentele pe site: cât ai contul") })
        assertTrue("pe site pleacă doar data făcută, nu aparatul", lines.any { it.startsWith("Galeria: fiecare poză") && "fără aparat" in it })
        assertFalse("analiza și oglinda nu se contrazic", lines.any { it.endsWith("Nu rămân acolo.") })
        assertTrue("revocarea scoate telefonul din Găsire", lines.any { it.startsWith("Profil → Contract → Revocă") && "Găsire" in it })
        assertTrue("familia la „Cine vede”", CONTRACT_CLAUSES.first { it.label == "Cine vede" }.lines.any { "Familia" in it.text })
        assertFalse("galeria nu mai e „întreagă”", "Galeria întreagă" in text)
        val friends = CONTRACT_CLAUSES.first { it.label == "Cine vede" }.lines.joinToString(" ") { it.text }
        listOf("kilometrii săptămânii", "ultima activitate", "zone", "recomanzi").forEach {
            assertTrue("„Cine vede” trebuie să spună: $it", it in friends)
        }
        // Revocarea nu promite ce nu poate șterge: pozele meselor stau cu jurnalele, cât ai contul.
        val revoke = lines.first { it.startsWith("Profil → Contract → Revocă") }
        assertFalse("revocarea nu șterge pozele meselor", "pozele meselor" in revoke)
        assertTrue("pozele meselor rămân cu jurnalele", lines.any { it.startsWith("Nopțile expiră") && "pozele meselor" in it })
    }

    @Test fun finderCopyMatchesTheDesign() {
        assertTrue("Închis sau descărcat, rămâne ultima poziție" in GASIRE_INFO)
        assertTrue("10 minute" in GASIRE_INFO)
    }
}
