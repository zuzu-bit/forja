package com.forja.app.core.voice.ui

import com.forja.app.core.voice.VoiceIntent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Executes only registered app plans; all plans share cancellation, safety and bounded observation. */
class UiAutomationEngine(
    private val driver: UiDriver,
    adapters: List<AppAdapter> = listOf(YouTubeAdapter()),
    private val timeoutMillis: Long = 12_000,
    private val pollMillis: Long = 250
) {
    private val adapters = adapters.associateBy { it.packageName }

    suspend fun execute(intent: VoiceIntent, onEvent: (String, String) -> Unit) {
        val adapter = adapters[intent.packageName]?.takeIf { it.accepts(intent) }
            ?: throw UiControlException("Comanda nu face parte din acțiunile permise.", "intent_resolved")
        currentCoroutineContext().ensureActive()
        driver.snapshot()?.let {
            if (it.locked) throw UiControlException("Deblochează telefonul înainte de comandă.", "app_opened")
        }
        if (!driver.launch(adapter.packageName)) throw UiControlException("Aplicația țintă nu este instalată sau nu poate fi deschisă.", "app_opened")
        val context = UiExecutionContext(driver, adapter.packageName, timeoutMillis, pollMillis, onEvent)
        context.observe("app_opened", allowLaunchTransition = true) { true }
        onEvent("app_opened", "${adapter.displayName} este deschis.")
        adapter.plan(intent).execute(context, intent)
    }
}

/** Plans use this guarded port instead of raw nodes or an unrestricted Android driver. */
class UiExecutionContext internal constructor(
    private val driver: UiDriver,
    private val targetPackage: String,
    private val timeoutMillis: Long,
    private val pollMillis: Long,
    private val onEvent: (String, String) -> Unit
) {
    suspend fun act(snapshot: UiSnapshot, node: UiNode, action: UiAction, detail: String, text: String? = null) {
        currentCoroutineContext().ensureActive()
        UiSafety.requireSafe(snapshot, targetPackage, "action_executed")
        UiSafety.requireActionSafe(node, "action_executed")
        onEvent("target_found", "Element identificat: $detail")
        currentCoroutineContext().ensureActive()
        if (!node.enabled || !node.visible || node.password || !driver.perform(snapshot, node, action, text))
            throw UiControlException("Elementul s-a schimbat sau acțiunea accesibilă a fost refuzată.", "action_executed")
        currentCoroutineContext().ensureActive()
        onEvent("action_executed", detail)
    }

    suspend fun target(select: (UiSnapshot) -> UiNode?): Pair<UiSnapshot, UiNode> {
        var selected: UiNode? = null
        val snapshot = observe("target_found") { selected = select(it); selected != null }
        return snapshot to selected!!
    }

    suspend fun snapshot(stage: String): UiSnapshot {
        currentCoroutineContext().ensureActive()
        val snapshot = driver.snapshot() ?: throw UiControlException("Arborele de accesibilitate nu este disponibil.", stage)
        UiSafety.requireSafe(snapshot, targetPackage, stage)
        return snapshot
    }

    suspend fun observe(stage: String, allowLaunchTransition: Boolean = false, predicate: (UiSnapshot) -> Boolean): UiSnapshot {
        val deadline = driver.elapsedRealtime() + timeoutMillis
        val maxAttempts = (timeoutMillis / pollMillis.coerceAtLeast(1)).toInt().coerceIn(1, 100) + 1
        repeat(maxAttempts) {
            currentCoroutineContext().ensureActive()
            val snapshot = driver.snapshot()
            if (snapshot != null) {
                if (snapshot.locked || snapshot.blockingWindow) UiSafety.requireSafe(snapshot, targetPackage, stage)
                if (!allowLaunchTransition || snapshot.packageName == targetPackage) {
                    UiSafety.requireSafe(snapshot, targetPackage, stage)
                    if (predicate(snapshot)) return snapshot
                }
            }
            if (driver.elapsedRealtime() >= deadline) throw UiControlException(timeoutReason(stage), stage)
            driver.pause(pollMillis)
        }
        throw UiControlException(timeoutReason(stage), stage)
    }

    suspend fun verified(detail: String) {
        currentCoroutineContext().ensureActive()
        onEvent("result_verified", detail)
    }

    private fun timeoutReason(stage: String): String = when (stage) {
        "target_found" -> "Nu am găsit un element sau un rezultat unic potrivit; nu aleg un rezultat la întâmplare."
        "result_verified" -> "Rezultatul a fost deschis, dar titlul și playerul nu au putut fi verificate."
        else -> "Aplicația nu a răspuns în timpul disponibil."
    }
}

/** Selected by the app adapter. Other commands implement their own UiPlan. */
class SearchAndOpenPlan(private val adapter: AppAdapter) : UiPlan {
    override suspend fun execute(context: UiExecutionContext, intent: VoiceIntent) {
        val initial = context.snapshot("target_found")
        if (adapter.searchField(initial) == null) {
            val (snapshot, search) = context.target(adapter::searchButton)
            context.act(snapshot, search, UiAction.CLICK, "Deschid căutarea")
        }
        val (fieldSnapshot, field) = context.target(adapter::searchField)
        context.act(fieldSnapshot, field, UiAction.SET_TEXT, "Introduc termenii solicitați", intent.query)
        val typed = context.observe("action_executed") { adapter.searchField(it)?.text == intent.query }
        val currentField = adapter.searchField(typed)!!
        val submit = adapter.submitButton(typed)
        when {
            UiAction.IME_ENTER in currentField.actions -> context.act(typed, currentField, UiAction.IME_ENTER, "Trimit căutarea")
            submit != null -> context.act(typed, submit, UiAction.CLICK, "Trimit căutarea")
            else -> throw UiControlException("${adapter.displayName} nu expune o acțiune accesibilă de trimitere a căutării pe acest dispozitiv.", "action_executed")
        }
        var target: VideoTarget? = null
        val results = context.observe("target_found") { target = adapter.result(it, intent); target != null }
        val chosen = target!!
        context.act(results, chosen.node, UiAction.CLICK, "Deschid rezultatul identificat")
        context.observe("result_verified") { adapter.verified(it, chosen) }
        context.verified("Titlul solicitat și controalele playerului sunt vizibile.")
    }
}
