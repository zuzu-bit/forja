package com.forja.app.feature.games.zid

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** Ce a făcut degetul (gazda îl traduce în comenzi: atingerea rotește sau pornește). */
internal enum class ZidGesture { Tap, Left, Right, SoftOn, SoftOff, HardDrop, Hold }

/**
 * Pragurile gesturilor ZID (games.md §4.5), într-un singur loc: se reglează după primul test pe S23.
 * Viteze în dp/ms, distanțe în celule.
 */
internal class ZidInputConfig(
    val tapMs: Long = 220,
    val holdMs: Long = 220,
    val ratchetCells: Float = 0.85f,
    val flickDownDpMs: Float = 1.8f,
    val flickDownCells: Float = 1.5f,
    val flickUpDpMs: Float = 1.2f,
    val flickUpCells: Float = 2f,
    val hardDropLockoutMs: Long = 120
)

private enum class Mode { Undecided, Horizontal, Down, Up, Hold }

/**
 * Gesturile pe tablă și pe placa de dedesubt, cu un singur deget:
 * atingere (< 220 ms, fără mișcare) = rotire · tragere stânga-dreapta = câte o coloană la fiecare 0,85 celule (clichet)
 * · apăsare ținută 220 ms = coborâre ușoară până la ridicare · tragere lentă în jos = coborâre ușoară
 * · aruncare rapidă în jos (≥ 1,8 dp/ms și ≥ 1,5 celule) = cădere bruscă (apoi 120 ms fără comenzi)
 * · aruncare în sus (≤ −1,2 dp/ms și ≥ 2 celule) = rezervă.
 */
internal fun Modifier.zidGestures(
    cellPx: Float,
    enabled: Boolean,
    cfg: ZidInputConfig = ZidInputConfig(),
    onGesture: (ZidGesture) -> Unit
): Modifier = if (!enabled || cellPx <= 0f) this else pointerInput(cellPx) {
    val slop = viewConfiguration.touchSlop
    var lastHardDrop = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val t0 = down.uptimeMillis
        val id = down.id
        // o aruncare tocmai a trântit piesa: degetul care vine imediat nu mută piesa nouă din greșeală
        val locked = SystemClock.uptimeMillis() - lastHardDrop < cfg.hardDropLockoutMs
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val start = down.position
        var lastX = start.x
        var acc = 0f
        var mode = Mode.Undecided
        down.consume()
        while (true) {
            val event = if (mode == Mode.Undecided && !locked) {
                val wait = (t0 + cfg.holdMs - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                withTimeoutOrNull(wait) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (event == null) {
                // ținut pe loc: coborâre ușoară cât stă degetul
                mode = Mode.Hold
                onGesture(ZidGesture.SoftOn)
                continue
            }
            val change = event.changes.firstOrNull { it.id == id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            val dx = change.position.x - start.x
            val dy = change.position.y - start.y
            if (!change.pressed) {
                change.consume()
                if (locked) break
                val v = tracker.calculateVelocity()
                val vy = v.y / density / 1000f
                val cells = dy / cellPx
                when (mode) {
                    Mode.Undecided -> if (change.uptimeMillis - t0 < cfg.tapMs) onGesture(ZidGesture.Tap)
                    Mode.Hold -> onGesture(ZidGesture.SoftOff)
                    Mode.Down -> {
                        onGesture(ZidGesture.SoftOff)
                        if (vy >= cfg.flickDownDpMs && cells >= cfg.flickDownCells) {
                            onGesture(ZidGesture.HardDrop)
                            lastHardDrop = SystemClock.uptimeMillis()
                        }
                    }
                    Mode.Up -> if (vy <= -cfg.flickUpDpMs && -cells >= cfg.flickUpCells) onGesture(ZidGesture.Hold)
                    Mode.Horizontal -> Unit
                }
                break
            }
            if (locked) {
                change.consume()
                continue
            }
            if (mode == Mode.Undecided && (abs(dx) > slop || abs(dy) > slop)) {
                mode = when {
                    abs(dx) >= abs(dy) -> Mode.Horizontal
                    dy > 0 -> Mode.Down
                    else -> Mode.Up
                }
                if (mode == Mode.Down) onGesture(ZidGesture.SoftOn)
                lastX = start.x
            }
            if (mode == Mode.Horizontal) {
                acc += change.position.x - lastX
                lastX = change.position.x
                val step = cfg.ratchetCells * cellPx
                while (acc >= step) {
                    onGesture(ZidGesture.Right)
                    acc -= step
                }
                while (acc <= -step) {
                    onGesture(ZidGesture.Left)
                    acc += step
                }
            }
            change.consume()
        }
        // degetul a plecat altfel (anulare): nimic nu rămâne apăsat
        if (mode == Mode.Hold || mode == Mode.Down) onGesture(ZidGesture.SoftOff)
    }
}
