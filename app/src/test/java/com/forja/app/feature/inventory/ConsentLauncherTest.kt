package com.forja.app.feature.inventory

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.feature.inventory.ConsentGate.Answer
import com.forja.app.feature.inventory.ConsentGate.Kind
import com.forja.app.feature.inventory.ConsentGate.Missing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Lansatorul de pe ecran + poarta adevărată, pe o activitate Robolectric, cu un ActivityResultRegistry fals (ține minte
 * lansările, nu pornește nimic; testul trimite rezultatele ca Android: cu activitatea în pauză, înainte de revenire).
 * Partea de ViewModel e cea din InventoryViewModel, redusă la poartă.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsentLauncherTest {
    private class FakeRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, IntentSenderRequest>>()
        var lastOptions: ActivityOptionsCompat? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += requestCode to (input as IntentSenderRequest)
            lastOptions = options
        }
    }

    /** Ruta din NavHost blocată în STARTED (cum ar ține-o Navigation într-o tranziție neterminată): cazul R2. */
    private class StuckEntry : LifecycleOwner {
        val lc = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lc
    }

    private val registry = FakeRegistry()
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry: ActivityResultRegistry = registry }
    private val gate = ConsentGate<IntentSender>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = mutableListOf<String>()
    private lateinit var controller: ActivityController<ComponentActivity>

    private fun sender(): IntentSender =
        PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender

    // ── ViewModel-ul, redus la poartă (aceleași ramuri ca InventoryViewModel) ──
    private fun onLaunched(id: Long, attempt: Int) { if (gate.launched(id, attempt)) events += "launched:$attempt" }
    private fun onMissing(id: Long, attempt: Int, why: String) {
        events += "missing:$attempt:$why"
        when (gate.missing(id, attempt)) {
            Missing.RENEW -> gate.renew(id, sender())
            Missing.STUCK -> events += "stuck"
            Missing.IGNORED -> Unit
        }
    }
    private fun onResult(ok: Boolean, sendFailed: Boolean) {
        if (sendFailed) { gate.dropLaunch()?.let { onMissing(it.id, it.attempt, "send") }; return }
        gate.answer(ok)
    }

    private fun start(entry: LifecycleOwner? = null, coverMs: Long = 60_000L) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val locals = listOfNotNull(LocalActivityResultRegistryOwner provides owner, entry?.let { LocalLifecycleOwner provides it })
            CompositionLocalProvider(*locals.toTypedArray()) {
                ConsentLauncher(gate.current, ::onLaunched, ::onMissing, ::onResult, trace = { r, _, _, n -> events += "trace:$r:$n" }, coverMs = coverMs)
            }
        }
        settle()
    }

    /** Cadre, efecte și mesaje, până se liniștește Looper-ul principal. */
    private fun settle() { repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) } }

    /** Plasa folosește timpul adevărat (DefaultExecutor): așteptăm cel mult 5 s să se întâmple, nu un număr fix de ms. */
    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("nu s-a întâmplat: $what · $events")
            Thread.sleep(25)
            settle()
        }
    }

    /** Ca Android: dialogul acoperă activitatea, rezultatul vine cu ea în pauză, apoi revine. */
    private fun answerLast(resultCode: Int, data: Intent? = null) {
        controller.pause(); settle()
        registry.dispatchResult(registry.launches.last().first, resultCode, data)
        controller.resume(); settle()
    }

    /**
     * Compose ține pe fir un AndroidUiDispatcher (ThreadLocal) cu Choreographer-ul de la prima folosire; Robolectric
     * refolosește firul principal, dar dă fiecărui test alt Choreographer, așa că din al doilea test nu mai vin cadre
     * și nimic nu se recompune. createComposeRule ocolește asta cu ceasul lui; fără ui-test, uităm dispecerul vechi.
     */
    @Before fun freshUiDispatcher() {
        val f = AndroidUiDispatcher::class.java.getDeclaredField("currentThread").apply { isAccessible = true }
        (f.get(null) as ThreadLocal<*>).remove()
    }

    @After fun tearDown() {
        scope.cancel()
        if (::controller.isInitialized) controller.pause().stop().destroy()
        settle()
    }

    @Test fun writeThenTrashAreBothLaunchedAndTheLoopEnds() {
        start()
        val out = mutableListOf<String>()
        val job = scope.launch {
            if (gate.ask(sender(), Kind.WRITE) != Answer.YES) { out += "stop"; return@launch }
            if (gate.ask(sender(), Kind.TRASH) != Answer.YES) { out += "stop"; return@launch }
            out += "runApply"
        }
        settle()
        assertEquals(1, registry.launches.size)
        answerLast(Activity.RESULT_OK)
        assertEquals("dialogul coșului se lansează după revenire", 2, registry.launches.size)
        answerLast(Activity.RESULT_OK)
        assertEquals(listOf("runApply"), out)
        assertTrue(!job.isActive)
    }

    @Test fun routeStuckInStartedDoesNotBlockTheLaunch() {
        val entry = StuckEntry().apply { lc.currentState = Lifecycle.State.STARTED }
        start(entry = entry)
        scope.launch { gate.ask(sender(), Kind.WRITE) }
        settle()
        assertEquals("4.4 aștepta aici ruta, pentru totdeauna", 1, registry.launches.size)
        assertTrue(events.any { it.startsWith("trace:W_GATE:") && it.contains("host=RESUMED") && it.contains("entry=STARTED") })
    }

    @Test fun pausedActivityDefersTheLaunchUntilResume() {
        start()
        controller.pause(); settle()
        scope.launch { gate.ask(sender(), Kind.WRITE) }
        settle()
        assertEquals(0, registry.launches.size)
        controller.resume(); settle()
        assertEquals(1, registry.launches.size)
    }

    @Test fun requestClosedWhileWaitingForResumeIsNotLaunched() {
        // Închiderea cererii și revenirea activității în același pas, fără cadru între ele (ca rezultatul unui dialog
        // livrat chiar înainte de onResume): efectul care aștepta poarta se trezește înaintea recompunerii care l-ar
        // anula. Fără verificarea de după poartă, ar fi deschis încă o dată un dialog deja închis.
        start()
        controller.pause(); settle()
        val out = mutableListOf<String>()
        scope.launch { out += gate.ask(sender(), Kind.WRITE).name }
        settle()
        assertEquals(0, registry.launches.size)
        gate.cancel(Answer.DROPPED)
        controller.resume(); settle()
        assertEquals(listOf("DROPPED"), out)
        assertEquals("o cerere închisă nu se mai lansează", 0, registry.launches.size)
    }

    @Test fun launchCarriesTheBackgroundStartOptIn() {
        start()
        scope.launch { gate.ask(sender(), Kind.WRITE) }
        settle()
        assertNotNull("API 34+: FORJA își dă voia de pornire PendingIntent-ului MediaStore", registry.lastOptions)
    }

    @Test fun dialogThatNeverCoversRenewsOnceThenSticks() {
        start(coverMs = 100)
        scope.launch { gate.ask(sender(), Kind.WRITE) }
        settle()
        assertEquals(1, registry.launches.size)
        waitUntil("prima plasă") { events.contains("missing:0:timeout") && registry.launches.size == 2 }   // încercarea 1, în tăcere
        waitUntil("a doua plasă") { events.contains("stuck") }
        assertTrue(events.contains("missing:1:timeout"))
        assertTrue(gate.current.value!!.stuck)
        Thread.sleep(400); settle()
        assertEquals("blocată: nu se mai lansează singură", 2, registry.launches.size)
        gate.renew(gate.current.value!!.id, sender())          // „Încearcă din nou”
        settle()
        assertEquals(3, registry.launches.size)
    }

    @Test fun resumeWithoutAResultAsksAgain() {
        start()
        scope.launch { gate.ask(sender(), Kind.WRITE) }
        settle()
        controller.pause(); settle()                          // dialogul a acoperit ecranul…
        controller.resume(); settle()                         // …și s-a închis fără rezultat
        assertTrue(events.contains("missing:0:resume"))
        assertEquals(2, registry.launches.size)
    }

    @Test fun sendFailureIsNotTheUsersNo() {
        start()
        val out = mutableListOf<String>()
        scope.launch { out += gate.ask(sender(), Kind.WRITE).name }
        settle()
        val failed = Intent().putExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION, IntentSender.SendIntentException())
        answerLast(Activity.RESULT_CANCELED, failed)
        assertTrue(out.isEmpty())
        assertTrue(events.contains("missing:0:send"))
        assertEquals(2, registry.launches.size)
        answerLast(Activity.RESULT_OK)
        assertEquals(listOf("YES"), out)
    }
}
