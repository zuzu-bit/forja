package com.forja.app.core.games

import kotlinx.serialization.Serializable

/**
 * SplitMix64: un singur Long de stare, identic pe orice JVM/ART și orice versiune Kotlin
 * (`kotlin.random.Random(seed)` promite asta doar pentru aceeași versiune a bibliotecii).
 * Starea se salvează cu jocul, deci o partidă reluată continuă exact la fel.
 *
 * Vectorul de test (seed 0): 0xE220A8397B1DCDAF, 0x6E789E6AA1B965F4, 0x06C45D188009454F.
 */
@Serializable
class Rng(var state: Long) {

    fun nextLong(): Long {
        state += -0x61C8864680B583EBL                          // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L          // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L          // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** 0 ≤ rezultat < bound (bound > 0). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** 0 ≤ rezultat < 1, cu 24 de biți. */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()

    /** 0 ≤ rezultat < 1, cu 53 de biți. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun copy(): Rng = Rng(state)
}
