package com.forja.app.core.games

import com.forja.app.core.games.zid.ZidConfig
import com.forja.app.core.games.zid.ZidGoal
import com.forja.app.core.games.zid.ZidLevels
import com.forja.app.core.games.zid.ZidPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nivelurile ZID (games.md §5.1) și dovada că fiecare se poate câștiga (robotul lacom din §8). */
class ZidLevelsTest {

    @Test
    fun fifteenLevelsPlusEndless() {
        assertEquals(15, ZidLevels.all.size)
        assertEquals((1..15).toList(), ZidLevels.all.map { it.id })
        assertEquals(ZidGoal.Endless, ZidLevels.byId(ZID_ENDLESS).goal)
        assertEquals(listOf(5, 10, 15), ZidLevels.all.filter { it.fort }.map { it.id })
        assertEquals(listOf(4, 7, 10, 13), ZidLevels.all.filter { it.goal == ZidGoal.Dig }.map { it.id })
    }

    @Test
    fun rubbleRowsAreTenWideWithHolesAndBlocks() {
        for (l in ZidLevels.all) {
            for (row in l.rubble) {
                assertEquals("L${l.id} '$row'", 10, row.length)
                assertTrue("L${l.id} '$row' has no hole", '.' in row)
                assertTrue("L${l.id} '$row' has no block", '#' in row)
                assertTrue("L${l.id} '$row' bad char", row.all { it == '.' || it == '#' })
            }
            if (l.goal == ZidGoal.Dig) assertTrue("L${l.id} dig without rubble", l.rubble.isNotEmpty())
        }
    }

    @Test
    fun gravityStrictlyDecreases() {
        val g = ZidLevels.all.map { it.gravityMs }
        for (i in 1 until g.size) assertTrue("L${i + 1}", g[i] < g[i - 1])
        assertEquals(900, g.first())
        assertEquals(listOf(900, 796, 705, 624, 552, 489, 432, 383, 339, 300, 265, 235, 208, 184, 163), g)
    }

    @Test
    fun starThresholdsAreOrdered() {
        for (l in ZidLevels.all) {
            assertTrue("L${l.id}", l.par2 > 0)
            if (l.goal == ZidGoal.Lines) assertTrue("L${l.id}", l.par3 > l.par2) else assertTrue("L${l.id}", l.par3 < l.par2)
        }
        assertEquals(780, ZidLevels.byId(1).par2)
        assertEquals(1140, ZidLevels.byId(1).par3)
        assertEquals(71_250, ZidLevels.byId(15).par3)
        assertEquals(90, ZidLevels.byId(4).par2)
        assertEquals(135, ZidLevels.byId(13).par3)
    }

    @Test
    fun greedyBotWinsEveryLevel() {
        val report = StringBuilder()
        for (l in ZidLevels.all) {
            for (seed in 1L..3L) {
                val r = ZidBot.play(l, seed, maxPieces = 1500)
                assertEquals("L${l.id} seed $seed ended ${r.phase} after ${r.pieces} pieces", ZidPhase.Won, r.phase)
                report.append("L${l.id}/$seed: ${r.pieces} piese, ${r.score} p · ")
            }
        }
        println("ZID bot: $report")
    }

    @Test
    fun botAlsoWinsWithTheRealClearDelay() {
        val r = ZidBot.play(ZidLevels.byId(7), 2L, cfg = ZidConfig())
        assertEquals(ZidPhase.Won, r.phase)
    }
}
