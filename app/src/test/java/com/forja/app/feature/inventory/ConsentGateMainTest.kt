package com.forja.app.feature.inventory

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.forja.app.feature.inventory.ConsentGate.Answer
import com.forja.app.feature.inventory.ConsentGate.Kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Poarta pe Dispatchers.Main.immediate ADEVĂRAT — HandlerContext din kotlinx-coroutines-android pe Looper-ul principal
 * Robolectric, exact ce folosește viewModelScope. Răspunsurile sunt mesaje postate pe Looper, ca onActivityResult.
 * Martorul (ordinea din 4.4) arată că mediul chiar reproduce agățarea; dacă nu o mai reproduce, testul de acord nu
 * mai dovedește nimic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsentGateMainTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    @After fun tearDown() = scope.cancel()

    /** Un mesaj pe Looper-ul principal, apoi Looper-ul golit (ca onActivityResult, între două cadre). */
    private fun post(block: () -> Unit) {
        main.post(block)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun writeThenTrashReachesApplyOnTheRealMainImmediate() {
        val gate = ConsentGate<String>()
        val out = mutableListOf<String>()
        // Testul rulează pe firul principal: launch pe Main.immediate pornește pe loc, ca la atingerea „Aplică”.
        val job = scope.launch {
            if (gate.ask("W", Kind.WRITE) != Answer.YES) { out += "stop"; return@launch }
            if (gate.ask("T", Kind.TRASH) != Answer.YES) { out += "stop"; return@launch }
            out += "runApply"
        }
        val w = gate.current.value!!
        assertEquals("W", w.payload)
        assertTrue(gate.launched(w.id, w.attempt))
        post { gate.answer(true) }
        val t = gate.current.value
        assertEquals("T", t?.payload)                       // 4.4: aici era null, iar bucla aștepta pentru totdeauna
        assertTrue(gate.launched(t!!.id, t.attempt))
        post { gate.answer(true) }
        assertEquals(listOf("runApply"), out)
        assertFalse(job.isActive)
        assertNull(gate.current.value)
    }

    @Test fun trashRefusedEndsTheApplyOnTheRealMainImmediate() {
        val gate = ConsentGate<String>()
        val out = mutableListOf<String>()
        val job = scope.launch {
            if (gate.ask("W", Kind.WRITE) != Answer.YES) { out += "stop"; return@launch }
            if (gate.ask("T", Kind.TRASH) != Answer.YES) { out += "stop"; return@launch }
            out += "runApply"
        }
        gate.current.value!!.let { gate.launched(it.id, it.attempt) }
        post { gate.answer(true) }
        gate.current.value!!.let { gate.launched(it.id, it.attempt) }
        post { gate.answer(false) }
        assertEquals(listOf("stop"), out)
        assertFalse(job.isActive)
    }

    /** Martorul: ordinea din 4.4 (`answer?.complete(ok); answer = null`) agață pe acest dispecer. */
    @Test fun legacyOrderStillHangsOnTheRealMainImmediate() {
        var answer: CompletableDeferred<Boolean>? = null
        var sender: String? = null
        suspend fun ask(s: String): Boolean {
            val d = CompletableDeferred<Boolean>()
            answer?.complete(false); answer = d; sender = s
            return d.await()
        }
        fun onDialogResult(ok: Boolean) { answer?.complete(ok); answer = null }
        val out = mutableListOf<String>()
        val job = scope.launch { if (ask("W") && ask("T")) out += "runApply" }
        assertEquals("W", sender)
        post { onDialogResult(true) }
        assertEquals("T", sender)                            // dialogul coșului e cerut…
        post { onDialogResult(true) }                        // …și primește răspuns…
        assertTrue(job.isActive)                             // …dar bucla nu mai pleacă din ask(T)
        assertNull(answer)
        assertTrue(out.isEmpty())
    }
}
