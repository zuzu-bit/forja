package com.forja.app.feature.games.asalt

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Câștigul tragerii: degetul mută nicovala cu 1,15 × distanța lui (poate sta sub teren, nu pe scânteie). */
internal const val ASALT_DRAG_GAIN = 1.15f

/**
 * Gesturile ASALT, oriunde sub antet: tragere RELATIVĂ = nicovala se mută cu dx × 1,15 (în unități de teren),
 * atingere (< 200 ms, sub pragul de mișcare) = lansarea scânteii. Fără dublă atingere.
 */
internal fun Modifier.asaltGestures(
    unitPx: Float,
    enabled: Boolean,
    onDrag: (dxUnits: Float) -> Unit,
    onTap: () -> Unit
): Modifier = if (!enabled || unitPx <= 0f) this else pointerInput(unitPx) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val id = down.id
        val t0 = down.uptimeMillis
        var lastX = down.position.x
        var travel = 0f
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == id } ?: break
            if (!change.pressed) {
                if (travel < slop && change.uptimeMillis - t0 < 200) onTap()
                change.consume()
                break
            }
            val dx = change.position.x - lastX
            travel += abs(dx) + abs(change.position.y - change.previousPosition.y)
            lastX = change.position.x
            if (dx != 0f) onDrag(dx / unitPx * ASALT_DRAG_GAIN)
            change.consume()
        }
    }
}
