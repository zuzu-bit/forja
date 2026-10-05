package com.forja.app.feature.breath

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** „Hei FORJA, respiră cu mine”: ecranul Respiră pornește exercițiul (și dacă e deja deschis), o dată pe cerere. */
object BreathLinks {
    private val _start = MutableStateFlow(0)
    /** Crește la fiecare cerere; ecranul o consumă. */
    val start: StateFlow<Int> = _start.asStateFlow()

    fun requestStart() { _start.value = _start.value + 1 }
    fun consumed() { _start.value = 0 }
}
