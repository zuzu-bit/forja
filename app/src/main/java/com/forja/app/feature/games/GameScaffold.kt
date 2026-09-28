package com.forja.app.feature.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.core.games.GameHaptics
import com.forja.app.core.games.GameSettings
import com.forja.app.core.games.GameSfx
import com.forja.app.core.games.GameStore
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Inventory
import com.forja.app.feature.inventory.PillState
import com.forja.app.feature.inventory.RUNNING_STAGES
import com.forja.app.feature.inventory.pillStateOf

/*
 * Schela comună a jocurilor: ceasul pe cadre, pauza automată, ecranul ținut aprins, antetul (pastila sau „Închide”,
 * pauza) și legătura cu inventarul („Dosarele sunt gata”). Capturile nu compun nimic de aici în afară de antet:
 * randează doar conținutul fără stare (…PlayContent).
 */

/**
 * Ceasul unei partide: rulează doar cât `running` (joc, fără pauză, ecranul în față). `step(dtMs)` avansează motorul
 * (dt tăiat la 50 ms după o sughițare; restul sub-milisecundă se păstrează, deci ceasul jocului nu rămâne în urmă la
 * 120 Hz) și întoarce true dacă tabla trebuie redesenată. Rezultatul e un contor de cadre pe care Canvas-ul îl citește
 * în faza de desen (ca EmberField): doar desenul se reface, nu compoziția.
 */
@Composable
internal fun rememberGameLoop(running: Boolean, step: (dtMs: Int) -> Boolean): State<Long> {
    val frame = remember { mutableLongStateOf(0L) }
    val onStep by rememberUpdatedState(step)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        var carry = 0L
        // Lambda cadrului e creată o singură dată (nimic alocat pe cadru de aici).
        val onFrame: (Long) -> Unit = { now ->
            carry += (now - last).coerceAtLeast(0L)
            last = now
            var dt = (carry / 1_000_000L).toInt()
            if (dt > 50) {
                dt = 50
                carry = 0L
            } else {
                carry -= dt * 1_000_000L
            }
            if (onStep(dt)) frame.longValue = now
        }
        while (true) withFrameNanos(onFrame)
    }
    return frame
}

/** Ecranul e în față: activitatea RESUMED și fereastra cu focus (bara de notificări trasă sau un dialog → false). */
@Composable
internal fun rememberForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    return state.isAtLeast(Lifecycle.State.RESUMED) && focused
}

/** La ON_STOP (ecran stins, altă aplicație): salvarea partidei. */
@Composable
internal fun OnStop(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val onStop by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) onStop() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** Ecranul rămâne aprins doar cât partida chiar rulează. */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        if (!on) return@DisposableEffect onDispose { }
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

/** Sunetele și vibrațiile rutei, eliberate la ieșire; urmează setările din „forja_games”. */
@Stable
internal class GameFeedback(val sfx: GameSfx, val haptics: GameHaptics)

@Composable
internal fun rememberGameFeedback(settings: GameSettings): GameFeedback {
    val context = LocalContext.current
    val view = LocalView.current
    val fb = remember(view) { GameFeedback(GameSfx(context), GameHaptics(view)) }
    DisposableEffect(fb) {
        onDispose {
            fb.sfx.release()
            fb.haptics.release()
        }
    }
    fb.sfx.enabled = settings.sfx
    fb.haptics.enabled = settings.haptics
    return fb
}

@Composable
internal fun rememberGameSettings(): GameSettings {
    val context = LocalContext.current
    val flow = remember(context) { GameStore.settings(context) }
    val s by flow.collectAsState(initial = GameSettings())
    return s
}

// ───────────────────────────── Inventarul, din joc ─────────────────────────────

/** Ce știe jocul despre inventar: pastila (null = nimic de arătat) și rularea. */
internal class InvLink(val pill: PillState?, val runId: String?) {
    val ready: Boolean get() = pill?.ready == true
}

/**
 * Pastila inventarului pentru antetul jocului. `onReadyNow` se cheamă o singură dată, când rularea trece din lucru
 * în „gata” cât ești în joc (mascota se bucură; vibrația și sunetul le fac deja pastila globală și motorul).
 */
@Composable
internal fun rememberInvLink(onReadyNow: () -> Unit): InvLink {
    val progress by Inventory.progress.collectAsState()
    val onReady by rememberUpdatedState(onReadyNow)
    var lastStage by remember { mutableStateOf(progress?.stage) }
    LaunchedEffect(progress?.stage, progress?.runId) {
        val now = progress?.stage
        val before = lastStage
        lastStage = now
        if (now == InvStage.Ready && before != null && before in RUNNING_STAGES) onReady()
    }
    return InvLink(pillStateOf(progress), progress?.runId)
}
