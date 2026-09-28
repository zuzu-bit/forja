package com.forja.app.feature.games

import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.ZID_ENDLESS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Harta nivelurilor, din progres: stările nodurilor, alegerea implicită, etichetele (JUnit simplu, fără randare). */
class LevelMapUiTest {

    @Test
    fun zidNodesFollowProgress() {
        val p = GameProgress(unlocked = 7, stars = mapOf(1 to 3, 2 to 1, 3 to 2, 4 to 3, 5 to 2, 6 to 1))
        val ui = levelMapUi(GameId.Zid, p, selected = null, resume = null, pill = null)
        assertEquals(16, ui.nodes.size)
        assertEquals((1..15).toList() + ZID_ENDLESS, ui.nodes.map { it.id })
        assertTrue(ui.nodes.take(6).all { it.state == NodeState.Done })
        assertEquals(NodeState.Current, ui.nodes[6].state)
        assertTrue(ui.nodes.subList(7, 15).all { it.state == NodeState.Locked })
        assertEquals(NodeState.Done, ui.nodes.last().state)          // „Fără sfârșit” se deschide după nivelul 5
        assertEquals(listOf(5, 10, 15), ui.nodes.filter { it.fort }.map { it.id })
        assertEquals(7, ui.selected)
        assertEquals("SAPĂ", ui.nodes[6].meta)
        assertEquals("10 LINII", ui.nodes[2].meta)
        assertEquals(2, ui.nodes[2].stars)
    }

    @Test
    fun endlessIsLockedUntilLevelFiveAndCurrentAfterTheCampaign() {
        val early = levelMapUi(GameId.Zid, GameProgress(unlocked = 3), null, null, null)
        assertEquals(NodeState.Locked, early.nodes.last().state)
        val done = levelMapUi(GameId.Zid, GameProgress(unlocked = 16, stars = (1..15).associateWith { 1 }), null, null, null)
        assertEquals(NodeState.Current, done.nodes.last().state)
        assertEquals(ZID_ENDLESS, done.selected)
        val best = levelMapUi(GameId.Zid, GameProgress(unlocked = 16, endlessBest = 12_400), null, null, null)
        assertEquals("RECORD 12 400", best.nodes.last().meta)
    }

    @Test
    fun resumeWinsTheDefaultSelection() {
        val ui = levelMapUi(GameId.Asalt, GameProgress(unlocked = 5), selected = null, resume = 3, pill = null)
        assertEquals(3, ui.selected)
        assertEquals(3, ui.resume)
        val chosen = levelMapUi(GameId.Asalt, GameProgress(unlocked = 5), selected = 2, resume = 3, pill = null)
        assertEquals(2, chosen.selected)
    }

    @Test
    fun asaltMapHasTwelveWallsWithNames() {
        val ui = levelMapUi(GameId.Asalt, GameProgress(unlocked = 4, stars = mapOf(1 to 3, 2 to 2, 3 to 1)), null, null, null)
        assertEquals(12, ui.nodes.size)
        assertEquals(listOf(4, 8, 12), ui.nodes.filter { it.fort }.map { it.id })
        assertEquals("POARTA", ui.nodes[2].meta)
        assertEquals("CASCA", ui.nodes[9].meta)
        assertEquals(NodeState.Current, ui.nodes[3].state)
        assertTrue(ui.nodes.none { it.endless })
        val all = levelMapUi(GameId.Asalt, GameProgress(unlocked = 13), null, null, null)
        assertEquals(12, all.selected)
        assertNull(all.nodes.firstOrNull { it.state == NodeState.Current })
    }

    @Test
    fun stampsAndStarWords() {
        assertEquals("NIVEL 3 · POARTA", levelStamp(GameId.Asalt, 3))
        assertEquals("NIVEL 3 · CĂRĂMIDA", levelStamp(GameId.Zid, 3))
        assertEquals("FĂRĂ SFÂRȘIT", levelStamp(GameId.Zid, ZID_ENDLESS))
        assertEquals("două stele", starsWords(2))
        assertEquals("o stea", starsWords(1))
        assertEquals("fără stele", starsWords(0))
    }
}
