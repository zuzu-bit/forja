package com.forja.app.core.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SplitMix64: vectorul de referință, intervalele și repetabilitatea. JUnit simplu, fără Android. */
class RngTest {

    @Test
    fun referenceVectorForSeedZero() {
        val r = Rng(0L)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), r.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), r.nextLong())
        assertEquals(0x06C45D188009454FuL.toLong(), r.nextLong())
    }

    @Test
    fun nextIntStaysInRange() {
        val r = Rng(42L)
        for (bound in listOf(1, 2, 3, 7, 10, 13, 1000)) {
            repeat(2_000) {
                val v = r.nextInt(bound)
                assertTrue("$v out of 0..<$bound", v in 0 until bound)
            }
        }
    }

    @Test
    fun floatsAreInUnitInterval() {
        val r = Rng(7L)
        repeat(5_000) {
            val f = r.nextFloat()
            val d = r.nextDouble()
            assertTrue(f >= 0f && f < 1f)
            assertTrue(d >= 0.0 && d < 1.0)
        }
    }

    @Test
    fun sameSeedSameSequence() {
        val a = Rng(123_456L)
        val b = Rng(123_456L)
        repeat(1_000) { assertEquals(a.nextLong(), b.nextLong()) }
        val c = a.copy()
        repeat(100) { assertEquals(a.nextInt(97), c.nextInt(97)) }
    }

    @Test
    fun progressRules() {
        var p = GameProgress()
        assertEquals(1, p.current(GameId.Zid))
        assertTrue(p.isUnlocked(GameId.Zid, 1))
        assertTrue(!p.isUnlocked(GameId.Zid, 2))
        p = p.withWin(GameId.Zid, 1, stars = 2, score = 900)
        assertEquals(2, p.unlocked)
        assertEquals(2, p.starsOf(1))
        p = p.withWin(GameId.Zid, 1, stars = 1, score = 500)
        assertEquals(2, p.starsOf(1))
        assertEquals(900, p.bestOf(1))
        assertTrue(!p.isUnlocked(GameId.Zid, ZID_ENDLESS))
        repeat(5) { p = p.withWin(GameId.Zid, it + 1, 1, 10) }
        assertTrue(p.isUnlocked(GameId.Zid, ZID_ENDLESS))
        p = p.withScore(ZID_ENDLESS, 4_000).withScore(ZID_ENDLESS, 3_000)
        assertEquals(4_000, p.endlessBest)
        // ultimul nivel nu deschide nimic în afara campaniei
        var q = GameProgress(unlocked = 12)
        q = q.withWin(GameId.Asalt, 12, 3, 100)
        assertEquals(13, q.unlocked)
        assertEquals(12, q.current(GameId.Asalt))
    }
}
