package com.forja.app.core.games

import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.core.games.asalt.AsaltPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zidurile ASALT (games.md §5.2) și robotul care trebuie să le spargă pe toate, pentru fiecare sămânță 0–5. */
class AsaltLevelsTest {

    @Test
    fun twelveLevelsWithValidRows() {
        assertEquals(12, AsaltLevels.all.size)
        assertEquals((1..12).toList(), AsaltLevels.all.map { it.id })
        assertEquals(listOf(4, 8, 12), AsaltLevels.all.filter { it.fort }.map { it.id })
        for (l in AsaltLevels.all) {
            assertTrue("L${l.id} too many rows", l.rows.size <= AsaltEngine.ROWS)
            for (row in l.rows) {
                assertEquals("L${l.id} '$row'", AsaltEngine.COLS, row.length)
                assertTrue("L${l.id} '$row' bad char", row.all { it in AsaltLevels.CHARSET })
            }
            val destructible = l.rows.sumOf { r -> r.count { it != '.' && it != '#' } }
            assertTrue("L${l.id} has nothing to break", destructible >= 1)
            assertEquals(destructible, AsaltEngine.create(l, 0L).destructibleLeft)
        }
    }

    @Test
    fun speedGrowsWithTheLevel() {
        assertEquals(320.0, AsaltLevels.byId(1).baseSpeed, 1e-9)
        assertEquals(430.0, AsaltLevels.byId(12).baseSpeed, 1e-9)
    }

    @Test
    fun trackingBotClearsEveryLevel() {
        val medians = StringBuilder()
        for (l in AsaltLevels.all) {
            val times = ArrayList<Double>()
            for (seed in 0L..5L) {
                val r = AsaltBot.play(l, seed, maxSeconds = 600)
                assertEquals("L${l.id} seed $seed: ${r.phase} after ${r.seconds} s, lives ${r.lives}", AsaltPhase.Won, r.phase)
                assertTrue(r.seconds < 600)
                times += r.seconds
            }
            times.sort()
            medians.append("L${l.id} ${((times[2] + times[3]) / 2).toInt()} s · ")
        }
        println("ASALT bot medians: $medians")
    }
}
