package com.forja.app.feature.inventory

import com.forja.app.feature.inventory.ConsentGate.Answer
import com.forja.app.feature.inventory.ConsentGate.Kind
import com.forja.app.feature.inventory.ConsentGate.Missing
import com.forja.app.feature.inventory.ConsentGate.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Poarta acordului pe un „fir principal” cu semantica lui Dispatchers.Main.immediate (HandlerContext: nicio expediere
 * când ești deja pe el) — exact condiția în care 4.4 pierdea cererea coșului după acordul de scriere și rămânea în
 * „AȘTEPT ACORDUL TĂU”. Răspunsurile sosesc ca mesaje simple pe firul principal, ca onActivityResult.
 */
class ConsentGateTest {
    private class ImmediateMain : CoroutineDispatcher() {
        @Volatile var thread: Thread? = null
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "main").also { thread = it } }
        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread
        override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
    }

    private val main = ImmediateMain()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val gate = ConsentGate<String>()

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        main.executor.execute { try { block() } catch (t: Throwable) { error = t } finally { done.countDown() } }
        assertTrue("firul principal nu a răspuns", done.await(5, TimeUnit.SECONDS))
        error?.let { throw it }
    }

    /** Prima rundă din InventoryViewModel.apply(): scriere, apoi coș, apoi „runApply” (aceeași formă, aceleași ramuri). */
    private fun applyLoop(write: Boolean, trash: Boolean, out: MutableList<String>): Job = scope.launch {
        if (write) { val a = gate.ask("W", Kind.WRITE); if (a != Answer.YES) { out += "stop:$a"; return@launch } }
        if (trash) { val a = gate.ask("T", Kind.TRASH); if (a != Answer.YES) { out += "stop:$a"; return@launch } }
        out += "runApply"
    }

    /** Ce face ecranul: lansează cererea publicată (launch() a trecut) și o marchează lansată. */
    private fun launchCurrent(expected: String) {
        val r = gate.current.value
        assertEquals(expected, r?.payload)
        assertTrue(gate.launched(r!!.id, r.attempt))
    }

    @After fun tearDown() { scope.cancel(); main.executor.shutdownNow() }

    // ───────────── cazul Lanei (R1) ─────────────

    @Test fun writeThenTrashBothApprovedReachesApply() {
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = true, out = out) }
        onMain { launchCurrent("W") }
        onMain {
            assertEquals(Outcome.APPLIED, gate.answer(true))   // reia bucla pe loc: cererea coșului apare în același pas
            assertEquals("T", gate.current.value?.payload)      // 4.4: aici cererea coșului era deja ștearsă
        }
        onMain { launchCurrent("T") }
        onMain { assertEquals(Outcome.APPLIED, gate.answer(true)) }
        onMain {
            assertEquals(listOf("runApply"), out)
            assertFalse(job.isActive)
            assertNull(gate.current.value)
        }
    }

    @Test fun trashRefusedAfterWriteApprovedStopsInsteadOfHanging() {
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = true, out = out) }
        onMain { launchCurrent("W") }
        onMain { gate.answer(true) }
        onMain { launchCurrent("T") }
        onMain { gate.answer(false) }
        onMain { assertEquals(listOf("stop:NO"), out); assertFalse(job.isActive) }
    }

    @Test fun writeRefusedStopsBeforeTrash() {
        val out = mutableListOf<String>()
        onMain { applyLoop(write = true, trash = true, out = out) }
        onMain { launchCurrent("W") }
        onMain { gate.answer(false) }
        onMain { assertEquals(listOf("stop:NO"), out); assertNull(gate.current.value) }
    }

    // ───────────── potrivirea rezultatelor ─────────────

    @Test fun answerForARequestNotYetLaunchedIsIgnored() {
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = false, out = out) }
        onMain { assertEquals(Outcome.NO_LAUNCH, gate.peek()); assertEquals(Outcome.NO_LAUNCH, gate.answer(true)) }
        onMain { assertTrue(job.isActive); assertEquals("W", gate.current.value?.payload) }
    }

    @Test fun peekTellsWhatAnAnswerWouldDoWithoutApplyingIt() {
        onMain { applyLoop(write = true, trash = false, out = mutableListOf()) }
        onMain { launchCurrent("W") }
        onMain {
            assertEquals(Outcome.APPLIED, gate.peek())
            assertEquals("W", gate.current.value?.payload)     // peek nu consumă nimic
        }
    }

    @Test fun staleResultOfTheWriteDoesNotTouchTheTrashRequest() {
        // Scrierea lansată de două ori (lansarea lentă + cea refăcută): al doilea răspuns vine după ce bucla a trecut la
        // coș, dar coșul nu e încă lansat → nu îl atinge.
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = true, out = out) }
        onMain { launchCurrent("W") }
        onMain { val r = gate.current.value!!; gate.renew(r.id, "W2") }
        onMain { launchCurrent("W2") }
        onMain { gate.answer(true) }
        onMain { assertEquals("T", gate.current.value?.payload) }
        onMain { assertEquals(Outcome.NO_LAUNCH, gate.answer(false)) }   // răspunsul întârziat al celuilalt dialog
        onMain { assertTrue(job.isActive); assertEquals("T", gate.current.value?.payload) }
        onMain { launchCurrent("T") }
        onMain { gate.answer(true) }
        onMain { assertEquals(listOf("runApply"), out) }
    }

    @Test fun lateAnswerOfTheFirstAttemptCountsForTheSameRequest() {
        // Dialogul primei încercări a apărut după plasă (cererea a fost deja refăcută): răspunsul lui e bun, aceeași bucată.
        val out = mutableListOf<String>()
        onMain { applyLoop(write = true, trash = false, out = out) }
        onMain { launchCurrent("W") }
        onMain { val r = gate.current.value!!; assertEquals(Missing.RENEW, gate.missing(r.id, 0)); gate.renew(r.id, "W2") }
        onMain { assertEquals(Outcome.APPLIED, gate.answer(true)) }
        onMain { assertEquals(listOf("runApply"), out) }
    }

    @Test fun launchedIsIgnoredForAnOlderAttemptOrTwice() {
        onMain { applyLoop(write = true, trash = false, out = mutableListOf()) }
        onMain {
            val r = gate.current.value!!
            gate.renew(r.id, "W2")
            assertFalse(gate.launched(r.id, 0))                 // încercarea veche nu mai e a cererii
            assertTrue(gate.launched(r.id, 1))
            assertFalse(gate.launched(r.id, 1))                 // o singură lansare pe încercare
        }
    }

    // ───────────── dialogul care nu apare ─────────────

    @Test fun firstMissRenewsSilentlyThenTheRequestIsStuck() {
        onMain { applyLoop(write = true, trash = false, out = mutableListOf()) }
        onMain { launchCurrent("W") }
        onMain {
            val r = gate.current.value!!
            assertEquals(Missing.RENEW, gate.missing(r.id, 0))
            assertFalse(gate.current.value!!.stuck)
            assertTrue(gate.renew(r.id, "W2"))
            val n = gate.current.value!!
            assertEquals(r.id, n.id); assertEquals(1, n.attempt); assertFalse(n.launched)
        }
        onMain { launchCurrent("W2") }
        onMain {
            val r = gate.current.value!!
            assertEquals(Missing.STUCK, gate.missing(r.id, 1))
            assertTrue(gate.current.value!!.stuck)
            assertEquals(Missing.IGNORED, gate.missing(r.id, 1))   // blocată o singură dată
        }
    }

    @Test fun userRetryFromStuckClearsItAndCanStickAgain() {
        val out = mutableListOf<String>()
        onMain { applyLoop(write = true, trash = false, out = out) }
        onMain { launchCurrent("W") }
        onMain { val r = gate.current.value!!; gate.missing(r.id, 0); gate.renew(r.id, "W2") }
        onMain { launchCurrent("W2") }
        onMain { val r = gate.current.value!!; gate.missing(r.id, 1) }
        onMain { val r = gate.current.value!!; assertTrue(r.stuck); gate.renew(r.id, "W3") }   // „Încearcă din nou”
        onMain { val r = gate.current.value!!; assertFalse(r.stuck); assertEquals(2, r.attempt) }
        onMain { launchCurrent("W3") }
        onMain { gate.answer(true) }
        onMain { assertEquals(listOf("runApply"), out) }
    }

    @Test fun missForAStaleAttemptIsIgnored() {
        onMain { applyLoop(write = true, trash = false, out = mutableListOf()) }
        onMain { launchCurrent("W") }
        onMain {
            val r = gate.current.value!!
            gate.renew(r.id, "W2")
            assertEquals(Missing.IGNORED, gate.missing(r.id, 0))   // plasa încercării vechi a întârziat
            assertEquals(Missing.IGNORED, gate.missing(r.id + 7, 1))
        }
    }

    @Test fun sendFailureIsNotTheUsersNo() {
        // SendIntentException: AndroidX trimite RESULT_CANCELED; nu e „Nu” — cererea rămâne și se reface.
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = false, out = out) }
        onMain { launchCurrent("W") }
        onMain {
            val r = gate.dropLaunch()!!
            assertEquals("W", r.payload)
            assertEquals(Missing.RENEW, gate.missing(r.id, r.attempt))
            gate.renew(r.id, "W2")
        }
        onMain { assertTrue(job.isActive); launchCurrent("W2") }
        onMain { gate.answer(true) }
        onMain { assertEquals(listOf("runApply"), out) }
    }

    // ───────────── „Înapoi la dosare”, înlocuire, anulare ─────────────

    @Test fun backWhileStuckBehavesLikeNo() {
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = true, out = out) }
        onMain { launchCurrent("W") }
        onMain { gate.cancel(Answer.NO) }
        onMain { assertEquals(listOf("stop:NO"), out); assertFalse(job.isActive); assertNull(gate.current.value) }
        onMain { assertEquals(Outcome.NO_LAUNCH, gate.answer(true)) }   // dialogul târziu nu mai atinge nimic
    }

    @Test fun droppedIsNotNo() {
        val out = mutableListOf<String>()
        onMain { applyLoop(write = true, trash = false, out = out) }
        onMain { gate.cancel(Answer.DROPPED) }
        onMain { assertEquals(listOf("stop:DROPPED"), out) }
    }

    @Test fun newerRequestDropsTheOlderOneAndKeepsItself() {
        var older: Answer? = null
        onMain {
            scope.launch { older = gate.ask("L", Kind.LAPTOP) }
            scope.launch { gate.ask("W", Kind.WRITE) }
        }
        onMain {
            assertEquals(Answer.DROPPED, older)                  // laptopul nu e refuzat, doar lăsat în așteptare
            assertEquals("W", gate.current.value?.payload)       // finally-ul celui vechi nu o șterge pe cea nouă
        }
    }

    @Test fun supersededLaunchDoesNotShiftTheNewRequest() {
        var laptop: Answer? = null
        val out = mutableListOf<String>()
        onMain { scope.launch { laptop = gate.ask("L", Kind.LAPTOP) } }
        onMain { launchCurrent("L") }
        onMain { applyLoop(write = true, trash = false, out = out) }
        onMain { assertEquals(Answer.DROPPED, laptop); launchCurrent("W") }
        onMain { gate.answer(true) }
        onMain { assertEquals(listOf("runApply"), out) }
    }

    @Test fun cancelledWaiterRemovesItsRequest() {
        lateinit var job: Job
        onMain { job = applyLoop(write = true, trash = true, out = mutableListOf()) }
        onMain { job.cancel() }
        onMain { assertNull(gate.current.value) }
    }

    // ───────────── martorul: ordinea din 4.4 chiar agață pe acest fir ─────────────

    /**
     * Copia ordinii din 4.4 (`answer?.complete(ok); answer = null`) pe același fir: dacă testul ăsta nu mai găsește
     * agățarea, firul de test nu mai are semantica lui Main.immediate și testele de mai sus nu mai dovedesc nimic.
     */
    @Test fun legacyOrderStillHangsOnThisThread() {
        var answer: CompletableDeferred<Boolean>? = null
        var sender: String? = null
        suspend fun ask(s: String): Boolean {
            val d = CompletableDeferred<Boolean>()
            answer?.complete(false); answer = d; sender = s
            return d.await()
        }
        fun onDialogResult(ok: Boolean) { answer?.complete(ok); answer = null }
        val out = mutableListOf<String>()
        lateinit var job: Job
        onMain { job = scope.launch { if (ask("W") && ask("T")) out += "runApply" } }
        onMain { assertEquals("W", sender); onDialogResult(true) }
        onMain { assertEquals("T", sender); onDialogResult(true) }   // dialogul coșului a apărut și a primit răspuns…
        onMain {
            assertTrue(job.isActive)                                   // …dar bucla așteaptă pentru totdeauna
            assertNull(answer)
            assertTrue(out.isEmpty())
        }
    }
}
