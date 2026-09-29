package com.forja.app.feature.inventory

import com.forja.app.core.inventory.MoveDiag
import com.forja.app.core.inventory.MoveFail
import com.forja.app.core.inventory.MoveReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 4.4.2 (Kotlin pur): ce spune pagina de la finalul unei aplicări, în locul toast-ului „Nu s-a aplicat tot.” și al
 * întoarcerii la dosare — câte au rămas, UN motiv și UNA acțiune.
 */
class ResultPageTest {
    private fun fail(r: MoveReason): MoveFail = MoveDiag.fail(r, "image/jpeg", false, "DCIM/Camera/", "Pictures/FORJA/X/")

    @Test fun lanasApplyAsksForFullAccess() {
        // 9 mutări + 1 la gunoi: 3 mutate, gunoiul făcut, cele 6 din WhatsApp rămân în plan.
        val left = (1..6).map { "m:$it" }.toSet()
        val r = ApplyReports.of(photos = true, remaining = left, lost = 0, failures = left.associateWith { fail(MoveReason.Owned) },
            accessAvailable = true, granted = false)
        assertEquals(6, r.failed)
        assertEquals(MoveReason.Owned, r.reason)
        assertEquals(DoneFix.Access, r.fix)
    }

    @Test fun withFullAccessAlreadyGivenTheActionIsARetry() {
        val left = setOf("m:1")
        val r = ApplyReports.of(true, left, 0, mapOf("m:1" to fail(MoveReason.Denied)), accessAvailable = true, granted = true)
        assertEquals(MoveReason.Denied, r.reason)
        assertEquals(DoneFix.Retry, r.fix)
    }

    @Test fun reasonsAccessCannotFixAreRetried() {
        val left = setOf("m:1", "m:2")
        val r = ApplyReports.of(true, left, 0, left.associateWith { fail(MoveReason.Error) }, accessAvailable = true, granted = false)
        assertEquals(MoveReason.Error, r.reason)
        assertEquals(DoneFix.Retry, r.fix)
    }

    @Test fun belowAndroid11ThereIsNoAccessToAskFor() {
        val left = setOf("m:1")
        val r = ApplyReports.of(true, left, 0, mapOf("m:1" to fail(MoveReason.Dir)), accessAvailable = false, granted = false)
        assertEquals(DoneFix.Retry, r.fix)
    }

    @Test fun nothingAppliedStillGetsAReasonAndAnAction() {
        // Fereastra Android n-a putut fi arătată (FORJA a renunțat): nimic încercat, nimic mutat.
        val left = setOf("m:1", "m:2", "m:3")
        val r = ApplyReports.of(true, left, 0, emptyMap(), accessAvailable = true, granted = false)
        assertEquals(3, r.failed)
        assertEquals(MoveReason.Error, r.reason)
        assertEquals(DoneFix.Retry, r.fix)
    }

    @Test fun aFailureFixedInALaterRoundDoesNotCount() {
        // m:1 a eșuat în runda 1 și s-a mutat în runda 2: nu mai e în plan, deci nu mai e motiv.
        val r = ApplyReports.of(true, setOf("m:2"), 0, mapOf("m:1" to fail(MoveReason.Owned), "m:2" to fail(MoveReason.Error)),
            accessAvailable = true, granted = false)
        assertEquals(MoveReason.Error, r.reason)
        assertEquals(setOf("m:2"), r.effective.keys)
    }

    @Test fun aCompletePlanIsTheUsualDonePageWithTheGoneLine() {
        val r = ApplyReports.of(true, emptySet(), lost = 2, failures = mapOf("m:8" to fail(MoveReason.Gone), "m:9" to fail(MoveReason.Gone)),
            accessAvailable = true, granted = false)
        assertNull(r.fix)
        assertEquals(2, r.failed)
        assertEquals(MoveReason.Gone, r.reason)
        val clean = ApplyReports.of(true, emptySet(), 0, emptyMap(), true, false)
        assertNull(clean.fix)
        assertNull(clean.reason)
        assertEquals(0, clean.failed)
    }

    @Test fun documentsHaveNoAccessStep() {
        val r = ApplyReports.of(photos = false, remaining = setOf("d:1"), lost = 0, failures = emptyMap(), accessAvailable = true, granted = false)
        assertEquals(DoneFix.Retry, r.fix)
        assertEquals(MoveReason.Error, r.reason)
        // Documentele pierdute (copie rămasă în „De aruncat”) nu se explică cu „au dispărut”.
        assertNull(ApplyReports.of(false, emptySet(), 1, emptyMap(), true, false).reason)
    }
}
