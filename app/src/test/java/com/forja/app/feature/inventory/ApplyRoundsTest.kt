package com.forja.app.feature.inventory

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import com.forja.app.core.inventory.ApplyResult
import com.forja.app.core.inventory.MoveDiag
import com.forja.app.core.inventory.MoveFail
import com.forja.app.core.inventory.MoveReason
import com.forja.app.core.inventory.TrashAsk
import com.forja.app.feature.inventory.ConsentGate.Answer
import com.forja.app.feature.inventory.ConsentGate.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Bucla aplicării (ApplyRounds, cea din InventoryViewModel.apply) cu cereri și runde scrise dinainte: ce dialoguri cere,
 * câte runde rulează și când se oprește. Robolectric doar pentru IntentSender-ele adevărate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApplyRoundsTest {
    private fun sender(): IntentSender =
        PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender

    private fun result(moved: Int = 0, trashed: Int = 0, failed: Int = 0, failures: Map<String, MoveFail> = emptyMap()) =
        ApplyResult(moved, trashed, failed, 0L, failures = failures)

    private fun fails(reason: MoveReason, vararg ids: String): Map<String, MoveFail> =
        ids.associateWith { MoveDiag.fail(reason, "image/jpeg", false, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "Pictures/FORJA/X/") }

    /** Cererile, răspunsurile și rundele, în ordine; ce lipsește = nimic de cerut / DA / o rundă goală. */
    private inner class Script(
        writes: List<IntentSender?> = emptyList(),
        trash: List<TrashAsk> = emptyList(),
        answers: List<Answer> = emptyList(),
        results: List<ApplyResult> = emptyList(),
        manager: Boolean = false
    ) {
        private val writes = ArrayDeque(writes)
        private val trash = ArrayDeque(trash)
        private val answers = ArrayDeque(answers)
        private val results = ArrayDeque(results)
        val asked = mutableListOf<Kind>()
        val waiting = mutableListOf<Boolean>()
        val order = mutableListOf<String>()
        var applies = 0
        /** Ce a primit fiecare cerere de scriere ca „de sărit” și cu ce mod a rulat fiecare rundă. */
        val skips = mutableListOf<Set<String>>()
        val modes = mutableListOf<Boolean>()
        val loop = ApplyRounds(
            writeRequest = { skip -> order += "write"; skips += skip.toSet(); this.writes.removeFirstOrNull() },
            trashRequest = { order += "trash"; this.trash.removeFirstOrNull() ?: TrashAsk.None },
            ask = { _, k -> order += "ask:$k"; asked += k; this.answers.removeFirstOrNull() ?: Answer.YES },
            waiting = { waiting += it },
            runApply = { m -> order += "apply"; modes += m; applies++; this.results.removeFirstOrNull() ?: result() },
            manager = manager
        )
        fun run(): Answer? = runBlocking { loop.run() }
    }

    @Test fun lanasSavedPlanNeedsOnlyTheWriteDialog() {
        // 9 mutări + 1 în „De aruncat”, poza aceea deja la coș (acordul dat în aplicarea agățată din 4.4).
        val s = Script(writes = listOf(sender()), trash = listOf(TrashAsk.AlreadyTrashed), results = listOf(result(moved = 9, trashed = 1)))
        assertNull(s.run())
        assertEquals(listOf(Kind.WRITE), s.asked)
        assertEquals(1, s.applies)
        assertEquals(9, s.loop.total.moved)
        assertEquals(1, s.loop.total.trashed)
        assertEquals(listOf(true, false), s.waiting)
    }

    @Test fun theTrashRequestIsReadyBeforeTheWriteDialog() {
        // Interogarea coșului (IO) se face înainte: după „Permite” la scriere, dialogul coșului urmează pe loc.
        val s = Script(writes = listOf(sender()), trash = listOf(TrashAsk.Dialog(sender())), results = listOf(result(moved = 9, trashed = 1)))
        assertNull(s.run())
        assertEquals(listOf("write", "trash", "ask:WRITE", "ask:TRASH", "apply", "write", "trash"), s.order)
        assertEquals(listOf(true, false, true, false), s.waiting)
    }

    @Test fun aLaterRoundWithOnlyAChunkAlreadyInTheTrashStillRuns() {
        // Runda 2 nu cere niciun dialog, dar are ce aplica: fără ea, poza deja aruncată ar rămâne în plan.
        val s = Script(
            writes = listOf(sender(), null),
            trash = listOf(TrashAsk.None, TrashAsk.AlreadyTrashed),
            results = listOf(result(moved = 500), result(trashed = 3))
        )
        assertNull(s.run())
        assertEquals(listOf(Kind.WRITE), s.asked)
        assertEquals(2, s.applies)
        assertEquals(3, s.loop.total.trashed)
        assertEquals(2, s.loop.rounds)
    }

    @Test fun aChunkAlreadyInTheTrashDoesNotEndTheLoopBeforeTheNextChunk() {
        // Peste 500 în „De aruncat”: prima bucată e deja la coș, a doua mai cere dialogul.
        val s = Script(
            trash = listOf(TrashAsk.AlreadyTrashed, TrashAsk.Dialog(sender())),
            results = listOf(result(trashed = 500), result(trashed = 200))
        )
        assertNull(s.run())
        assertEquals(listOf(Kind.TRASH), s.asked)
        assertEquals(2, s.applies)
        assertEquals(700, s.loop.total.trashed)
    }

    @Test fun nothingToAskStillAppliesOnce() {
        // Documente, sau sub API 30: fără dialoguri, o singură rundă.
        val s = Script(results = listOf(result(moved = 3)))
        assertNull(s.run())
        assertEquals(emptyList<Kind>(), s.asked)
        assertEquals(1, s.applies)
        assertEquals(1, s.loop.rounds)
    }

    @Test fun aRoundThatAppliesNothingEndsTheLoop() {
        val s = Script(writes = listOf(sender(), sender()), results = listOf(result(failed = 5)))
        assertNull(s.run())
        assertEquals(1, s.applies)
        assertEquals(5, s.loop.total.failed)
    }

    @Test fun noStopsBeforeAnythingIsApplied() {
        val s = Script(writes = listOf(sender()), answers = listOf(Answer.NO))
        assertEquals(Answer.NO, s.run())
        assertEquals(0, s.applies)
        assertEquals(listOf(true, false), s.waiting)
    }

    @Test fun droppedTrashAfterTheWriteDialogStopsTheRound() {
        val s = Script(writes = listOf(sender()), trash = listOf(TrashAsk.Dialog(sender())), answers = listOf(Answer.YES, Answer.DROPPED))
        assertEquals(Answer.DROPPED, s.run())
        assertEquals(listOf(Kind.WRITE, Kind.TRASH), s.asked)
        assertEquals(0, s.applies)
    }

    // ───────────── 4.4.2: „Acces la toate fișierele” și eșecurile cu motiv ─────────────

    @Test fun withFullAccessNothingIsAskedAndOneRoundAppliesEverything() {
        // Planul Lanei cu accesul dat: nicio cerere MediaStore, nicio fereastră, o rundă cu tot (9 mutări + 1 la gunoi).
        val s = Script(
            writes = listOf(sender()), trash = listOf(TrashAsk.Dialog(sender())),
            results = listOf(result(moved = 9, trashed = 1)), manager = true
        )
        assertNull(s.run())
        assertEquals(listOf("apply"), s.order)
        assertEquals(emptyList<Kind>(), s.asked)
        assertEquals(emptyList<Boolean>(), s.waiting)
        assertEquals(listOf(true), s.modes)
        assertEquals(1, s.loop.rounds)
        assertEquals(9, s.loop.total.moved)
        assertEquals(1, s.loop.total.trashed)
    }

    @Test fun withFullAccessTheLoopStopsAfterTheRoundEvenWithFailures() {
        val s = Script(results = listOf(result(moved = 8, failed = 1, failures = fails(MoveReason.Volume, "m:9")), result(moved = 1)), manager = true)
        assertNull(s.run())
        assertEquals(1, s.applies)
        assertEquals(setOf("m:9"), s.loop.failures.keys)
        assertEquals(MoveReason.Volume, s.loop.failures.getValue("m:9").reason)
    }

    @Test fun withoutFullAccessTheRoundsRunInTheOldMode() {
        val s = Script(writes = listOf(sender()), results = listOf(result(moved = 3)))
        assertNull(s.run())
        assertEquals(listOf(false), s.modes)
        assertEquals(listOf(Kind.WRITE), s.asked)
    }

    @Test fun itemsThatCannotMoveWithAGrantAreNotAskedForAgain() {
        // 4.4.1 pe S23: runda 2 cerea din nou acordul pentru aceleași 6 poze din WhatsApp, care au eșuat iar.
        val owned = fails(MoveReason.Owned, "m:4", "m:5", "m:6", "m:7", "m:8", "m:9")
        val s = Script(writes = listOf(sender(), null), results = listOf(result(moved = 3, trashed = 1, failed = 6, failures = owned)))
        assertNull(s.run())
        assertEquals(listOf(emptySet(), owned.keys), s.skips)
        assertEquals(listOf(Kind.WRITE), s.asked)
        assertEquals(1, s.applies)
        assertEquals(owned.keys, s.loop.skip)
        assertEquals(owned.keys, s.loop.failures.keys)
    }

    @Test fun transientFailuresAreStillRetriedOnce() {
        val s = Script(
            writes = listOf(sender(), sender()),
            results = listOf(result(moved = 3, failed = 2, failures = fails(MoveReason.Error, "m:1", "m:2")), result(moved = 2))
        )
        assertNull(s.run())
        assertEquals(listOf(emptySet<String>(), emptySet(), emptySet()), s.skips)
        assertEquals(2, s.applies)
        assertEquals(5, s.loop.total.moved)
        assertTrue(ApplyRounds.permanent(MoveReason.Owned) && ApplyRounds.permanent(MoveReason.Dir) && ApplyRounds.permanent(MoveReason.Volume))
        assertTrue(!ApplyRounds.permanent(MoveReason.Denied) && !ApplyRounds.permanent(MoveReason.Error) && !ApplyRounds.permanent(MoveReason.Mismatch))
    }

    @Test fun rebuildingATrashRequestMapsToTheGate() {
        val t = sender()
        val again = TrashAsk.Dialog(t).renewal()
        assertTrue(again is ConsentGate.Renewal.Again)
        assertSame(t, (again as ConsentGate.Renewal.Again).payload)
        // Toată bucata deja la coș: acordul a fost dat, cererea se închide cu DA.
        assertEquals(ConsentGate.Renewal.Done, TrashAsk.AlreadyTrashed.renewal())
        assertEquals(ConsentGate.Renewal.Drop, TrashAsk.None.renewal())
    }
}
