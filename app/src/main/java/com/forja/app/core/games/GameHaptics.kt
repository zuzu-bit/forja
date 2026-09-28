package com.forja.app.core.games

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** Momentele care vibrează în jocuri (games.md §4.9). */
enum class Buzz { Move, Rotate, HardDrop, Clear, Quad, Paddle, Explosion, Capsule, LifeLost, Lose, Win, Reject }

/**
 * Vibrațiile jocurilor prin `view.performHapticFeedback` (respectă setarea sistemului „vibrații la atingere”),
 * cu rezervă pe versiunile vechi, cel mult una la 40 ms. Mutarea vibrează doar la fiecare al doilea pas.
 */
class GameHaptics(private val view: View) {
    /** Setarea „Vibrații” din Pauză. */
    var enabled: Boolean = true
    private var last = 0L
    private var moveTick = false
    private val confirm = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
    private val reject = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    private val secondPulse = Runnable { try { view.performHapticFeedback(confirm) } catch (_: Exception) { } }

    fun buzz(b: Buzz) {
        if (!enabled) return
        val c = when (b) {
            Buzz.Move -> {
                moveTick = !moveTick
                if (!moveTick) return
                HapticFeedbackConstants.CLOCK_TICK
            }
            Buzz.Rotate -> HapticFeedbackConstants.KEYBOARD_TAP
            Buzz.HardDrop -> HapticFeedbackConstants.CONTEXT_CLICK
            Buzz.Clear -> confirm
            Buzz.Quad -> HapticFeedbackConstants.LONG_PRESS
            Buzz.Paddle -> HapticFeedbackConstants.CLOCK_TICK
            Buzz.Explosion -> HapticFeedbackConstants.CONTEXT_CLICK
            Buzz.Capsule -> confirm
            Buzz.LifeLost, Buzz.Lose, Buzz.Reject -> reject
            Buzz.Win -> confirm
        }
        val now = SystemClock.uptimeMillis()
        val important = b == Buzz.Win || b == Buzz.Lose || b == Buzz.Quad || b == Buzz.LifeLost
        if (!important && now - last < 40) return
        last = now
        try { view.performHapticFeedback(c) } catch (_: Exception) { }
        if (b == Buzz.Quad) {
            view.removeCallbacks(secondPulse)
            view.postDelayed(secondPulse, 80)
        }
    }

    fun release() {
        view.removeCallbacks(secondPulse)
    }
}
