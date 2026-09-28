package com.forja.app.feature.probe

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/** Intrarea ascunsă, fără import în ecranul care o găzduiește: `Modifier.then(ProbeEntry.taps(onOpen))`. */
object ProbeEntry {
    fun taps(onOpen: () -> Unit): Modifier = Modifier.secretTaps(onTrigger = onOpen)
}

/**
 * Intrarea ascunsă în Probă: [count] atingeri în [windowMs] pe textul versiunii din Profil. Nimic vizibil, nicio vibrație
 * (nu e un buton). Folosit o singură dată de Lana, ca telefonul ei să spună ce trepte de pornire merg.
 */
fun Modifier.secretTaps(count: Int = 5, windowMs: Long = 3_000L, onTrigger: () -> Unit): Modifier = composed {
    val trigger by rememberUpdatedState(onTrigger)
    val taps = remember { ArrayDeque<Long>() }
    pointerInput(count, windowMs) {
        detectTapGestures {
            val now = SystemClock.elapsedRealtime()
            taps.addLast(now)
            while (taps.isNotEmpty() && now - taps.first() > windowMs) taps.removeFirst()
            if (taps.size >= count) {
                taps.clear()
                trigger()
            }
        }
    }
}
