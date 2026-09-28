package com.forja.app.core.games

import com.forja.app.core.games.zid.ZidCmd
import com.forja.app.core.games.zid.ZidConfig
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidEvent
import com.forja.app.core.games.zid.ZidLevels
import com.forja.app.core.games.zid.ZidPhase
import com.forja.app.core.games.zid.ZidPieces
import com.forja.app.core.games.zid.ZidSave
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regulile ZID (games.md §4.3, §8): sacul de 7, împingerile, scorul, blocarea, rezerva, victoria, salvarea. */
class ZidEngineTest {

    private val W = ZidEngine.W
    private val H = ZidEngine.H

    /** O tablă din rânduri de jos (ultimul = rândul 19); `#` = moloz, `o` = bloc de piesă, `.` = gol. */
    private fun board(vararg bottomRows: String): String {
        val c = CharArray(W * H) { '0' }
        bottomRows.forEachIndexed { i, row ->
            val y = H - bottomRows.size + i
            for (x in 0 until W) c[y * W + x] = when (row[x]) {
                '#' -> '8'
                'o' -> '3'
                else -> '0'
            }
        }
        return String(c)
    }

    /** O partidă în plin, cu piesa dată exact unde vrem (restul stării e cel al unei partide noi). */
    private fun engineWith(
        kind: Int, rot: Int, x: Int, y: Int, cells: String = board(),
        level: Int = 3, lines: Int = 0, cfg: ZidConfig = ZidConfig(clearDelayMs = 0)
    ): ZidEngine {
        val base = ZidEngine.create(ZidLevels.byId(level), 5L, cfg).save()
        val s = base.copy(cells = cells, phase = ZidPhase.Falling, kind = kind, rot = rot, x = x, y = y, lines = lines, lowestY = y)
        return ZidEngine.restore(s, cfg)
    }

    @Test
    fun everySevenBagHoldsAllKinds() {
        for (seed in 1L..20L) {
            val kinds = ZidBot.play(ZidLevels.endless, seed, maxPieces = 42).kinds
            val bags = kinds.size / 7
            assertTrue("seed $seed played only ${kinds.size} pieces", bags >= 1)
            for (b in 0 until bags) {
                val bag = kinds.subList(b * 7, b * 7 + 7).toSet()
                assertEquals("seed $seed bag $b: $bag", (1..7).toSet(), bag)
            }
        }
    }

    @Test
    fun tRotationKicksOffTheLeftWall() {
        // T spre dreapta lipit de perete (celulele în coloanele 0–1); rotirea spre T-jos iese din tablă → împinsă la dreapta
        val e = engineWith(ZidPieces.T, rot = 1, x = -1, y = 8)
        assertTrue(e.input(ZidCmd.RotateCw))
        assertEquals(2, e.pieceRot)
        assertEquals(0, e.pieceX)
        assertTrue(ZidEvent.Kicked in e.events)
    }

    @Test
    fun iRotationKicksOffTheRightWall() {
        // I vertical în coloana 9; culcat ar ocupa 7..10 → împins o coloană la stânga
        val e = engineWith(ZidPieces.I, rot = 1, x = 7, y = 8)
        assertTrue(e.input(ZidCmd.RotateCw))
        assertEquals(2, e.pieceRot)
        assertEquals(6, e.pieceX)
    }

    @Test
    fun tRotationOnTheFloorKicksUp() {
        // T în sus pe fund (rândurile 18–19): T-dreapta are nevoie de 3 rânduri → urcă unul
        val e = engineWith(ZidPieces.T, rot = 0, x = 3, y = 18)
        assertTrue(e.input(ZidCmd.RotateCw))
        assertEquals(1, e.pieceRot)
        assertEquals(17, e.pieceY)
    }

    @Test
    fun rotationBlockedEverywhereFails() {
        // I culcat într-un puț de 4 coloane, strâns sus și jos: nicio împingere nu încape
        val rows = Array(20) { "######....".replace('#', 'o') }
        rows[19] = "oooooooooo"
        val e = engineWith(ZidPieces.I, rot = 0, x = 6, y = 17, cells = board(*rows.sliceArray(2 until 20)))
        val before = e.save()
        assertFalse(e.input(ZidCmd.RotateCw))
        assertEquals(before.rot, e.pieceRot)
        assertTrue(ZidEvent.Blocked in e.events)
    }

    private fun almostFull(n: Int, hole: Int = 9): Array<String> =
        Array(n) { CharArray(W) { x -> if (x == hole) '.' else 'o' }.concatToString() }

    @Test
    fun singleDoubleQuadScoreTimesLevel() {
        for ((n, pts) in listOf(1 to 100, 2 to 300, 4 to 800)) {
            // I vertical (celulele în coloana 2 a cutiei) exact deasupra puțului din coloana 9, deja la sol
            val rows = almostFull(n)
            val e = engineWith(ZidPieces.I, rot = 1, x = 7, y = H - 4, cells = board(*rows), level = 3)
            val y = e.landingY
            val e2 = engineWith(ZidPieces.I, rot = 1, x = 7, y = y, cells = board(*rows), level = 3)
            e2.input(ZidCmd.HardDrop)
            assertEquals("n=$n", pts * 3, e2.score)
            assertEquals(n, e2.lines)
            assertEquals(n, e2.lastCleared)
        }
    }

    @Test
    fun comboAddsFiftyTimesComboTimesLevel() {
        // patru rânduri pline fără coloanele 8–9: un O închide două, al doilea O încă două (combo 1)
        val cells = board("oooooooo..", "oooooooo..", "oooooooo..", "oooooooo..")
        val a = engineWith(ZidPieces.O, rot = 0, x = 8, y = 18, cells = cells, level = 2)
        a.input(ZidCmd.HardDrop)                 // 300 × 2
        assertEquals(0, a.combo)
        assertEquals(600, a.score)
        val s = a.save().copy(kind = ZidPieces.O, rot = 0, x = 8, y = 18, lowestY = 18)
        val b = ZidEngine.restore(s, ZidConfig(clearDelayMs = 0))
        b.input(ZidCmd.HardDrop)                 // încă 300 × 2 + 50 × 1 × 2
        assertEquals(1, b.combo)
        assertEquals(600 + 600 + 100, b.score)
        assertTrue(ZidEvent.Combo in b.events)
        // o blocare fără rânduri rupe combo-ul
        val c = ZidEngine.restore(b.save().copy(kind = ZidPieces.O, rot = 0, x = 0, y = 18, lowestY = 18), ZidConfig(clearDelayMs = 0))
        c.input(ZidCmd.HardDrop)
        assertEquals(-1, c.combo)
    }

    @Test
    fun softAndHardDropPoints() {
        val e = engineWith(ZidPieces.O, rot = 0, x = 4, y = 2, level = 1)
        e.input(ZidCmd.SoftOn)
        e.advance(40)
        e.advance(40)
        e.advance(40)
        assertEquals(5, e.pieceY)
        assertEquals(3, e.score)
        e.input(ZidCmd.SoftOff)
        val dist = e.landingY - e.pieceY
        e.input(ZidCmd.HardDrop)
        assertEquals(3 + 2 * dist, e.score)
    }

    @Test
    fun lockDelayResetsAreCappedAtFifteen() {
        val e = engineWith(ZidPieces.O, rot = 0, x = 4, y = 18)
        for (k in 1..15) {
            e.clearEvents()
            assertTrue(e.input(if (k % 2 == 1) ZidCmd.Left else ZidCmd.Right))
            e.advance(400)
            assertFalse("locked after reset $k", ZidEvent.Locked in e.events)
        }
        e.clearEvents()
        assertTrue(e.input(ZidCmd.Left))          // a 16-a mutare pe sol: nu mai repornește
        e.advance(1)
        assertTrue(ZidEvent.Locked in e.events)
    }

    @Test
    fun lockHappensAfterFiveHundredMsOnTheGround() {
        val e = engineWith(ZidPieces.O, rot = 0, x = 4, y = 18)
        e.advance(499)
        assertFalse(ZidEvent.Locked in e.events)
        e.advance(1)
        assertTrue(ZidEvent.Locked in e.events)
    }

    @Test
    fun holdOncePerPiece() {
        val e = ZidEngine.create(ZidLevels.byId(1), 9L, ZidConfig(clearDelayMs = 0))
        e.start()
        val first = e.pieceKind
        val upcoming = e.next(0)
        assertTrue(e.input(ZidCmd.Hold))
        assertEquals(first, e.hold)
        assertEquals(upcoming, e.pieceKind)
        assertFalse(e.input(ZidCmd.Hold))
        e.input(ZidCmd.HardDrop)
        val cur = e.pieceKind
        assertTrue(e.input(ZidCmd.Hold))          // piesă nouă: rezerva se poate folosi iar (schimb)
        assertEquals(first, e.pieceKind)
        assertEquals(cur, e.hold)
    }

    @Test
    fun spawnCollisionLoses() {
        // rândurile ascunse pline (fără coloana 0); piesa curentă, un I vertical, coboară în coloana 0
        val c = CharArray(W * H) { '0' }
        for (y in 0..1) for (x in 1 until W) c[y * W + x] = '3'
        val e = engineWith(ZidPieces.I, rot = 1, x = -2, y = 14, cells = String(c))
        e.input(ZidCmd.HardDrop)
        assertEquals(ZidPhase.Lost, e.phase)
        assertTrue(ZidEvent.Lost in e.events)
        assertFalse(e.input(ZidCmd.Left))
    }

    @Test
    fun linesGoalWinsExactlyAtTarget() {
        // nivelul 1: 6 linii. Cu 4 făcute, o linie în plus nu ajunge; cu 5, da.
        val rows = almostFull(1)
        val a = engineWith(ZidPieces.I, rot = 1, x = 7, y = 15, cells = board(*rows), level = 1, lines = 4)
        a.input(ZidCmd.HardDrop)
        assertEquals(5, a.lines)
        assertEquals(ZidPhase.Falling, a.phase)
        val b = engineWith(ZidPieces.I, rot = 1, x = 7, y = 15, cells = board(*rows), level = 1, lines = 5)
        b.input(ZidCmd.HardDrop)
        assertEquals(6, b.lines)
        assertEquals(ZidPhase.Won, b.phase)
        assertTrue(ZidEvent.Won in b.events)
        assertTrue(b.stars in 1..3)
    }

    @Test
    fun digWinsWhenRubbleIsGone() {
        val e = engineWith(ZidPieces.I, rot = 1, x = 7, y = 15, cells = board("#########."), level = 4)
        assertEquals(1, e.rubbleLeft)
        e.input(ZidCmd.HardDrop)
        assertEquals(0, e.rubbleLeft)
        assertEquals(ZidPhase.Won, e.phase)
        assertEquals(3, e.stars)
    }

    @Test
    fun gravityFollowsTheTable() {
        val e = ZidEngine.create(ZidLevels.byId(1), 3L)
        e.start()
        val y0 = e.pieceY
        e.advance(899)
        assertEquals(y0, e.pieceY)
        e.advance(1)
        assertEquals(y0 + 1, e.pieceY)
        assertEquals(900, e.gravityMs)
        assertEquals(163, ZidLevels.byId(15).gravityMs)
        for (l in ZidLevels.all) assertEquals(ZidLevels.gravityFor(l.id), l.gravityMs)
    }

    @Test
    fun endlessRanksUpEveryTenLines() {
        val e = engineWith(ZidPieces.I, rot = 1, x = 7, y = 15, cells = board(*almostFull(1)), level = ZID_ENDLESS, lines = 9)
        assertEquals(1, e.rank)
        e.input(ZidCmd.HardDrop)
        assertEquals(10, e.lines)
        assertEquals(2, e.rank)
        assertEquals(ZidLevels.gravityFor(2), e.gravityMs)
        assertEquals(60, ZidLevels.gravityFor(40))
    }

    @Test
    fun zeroClearDelayRemovesRowsInTheSameCall() {
        val rows = almostFull(1)
        val instant = engineWith(ZidPieces.I, rot = 1, x = 7, y = 15, cells = board(*rows), cfg = ZidConfig(clearDelayMs = 0))
        instant.input(ZidCmd.HardDrop)
        assertEquals(ZidPhase.Falling, instant.phase)
        // rândul de jos s-a dus; au rămas cele trei celule ale lui I, coborâte cu un rând
        assertEquals(1, (0 until W).count { instant.cell(it, H - 1) != 0 })
        assertEquals(ZidPieces.I, instant.cell(9, H - 3))

        val slow = engineWith(ZidPieces.I, rot = 1, x = 7, y = 15, cells = board(*rows), cfg = ZidConfig())
        slow.input(ZidCmd.HardDrop)
        assertEquals(ZidPhase.Clearing, slow.phase)
        assertEquals(1, slow.clearCount)
        slow.advance(259)
        assertEquals(ZidPhase.Clearing, slow.phase)
        slow.advance(1)
        assertEquals(ZidPhase.Falling, slow.phase)
        assertTrue(slow.hasPiece)
    }

    @Test
    fun dangerFlagWhenStackReachesTopFourRows() {
        val rows = Array(15) { "ooooo....o" }
        val e = engineWith(ZidPieces.O, rot = 0, x = 6, y = 2, cells = board(*rows))
        e.input(ZidCmd.HardDrop)
        assertTrue(e.danger)
    }

    @Test
    fun saveRoundTripContinuesIdentically() {
        val json = Json { encodeDefaults = true }
        for (seed in 1L..4L) {
            val cmds = ZidCmd.entries
            val script = Rng(seed * 31)
            val steps = List(600) { cmds[script.nextInt(cmds.size)] to (1 + script.nextInt(120)) }
            val a = ZidEngine.create(ZidLevels.byId(1 + (seed.toInt() % 15)), seed, ZidConfig())
            a.start()
            fun play(e: ZidEngine, i: Int) {
                if (i % 5 == 0) ZidBot.place(e) else e.input(steps[i].first)
                e.advance(steps[i].second)
                e.clearEvents()
            }
            for (i in 0 until 300) play(a, i)
            val snap = json.encodeToString(ZidSave.serializer(), a.save())
            val b = ZidEngine.restore(json.decodeFromString(ZidSave.serializer(), snap), ZidConfig())
            assertEquals(a.save(), b.save())
            for (i in 300 until 600) {
                play(a, i)
                play(b, i)
            }
            assertEquals("seed $seed", json.encodeToString(ZidSave.serializer(), a.save()), json.encodeToString(ZidSave.serializer(), b.save()))
        }
    }

    @Test
    fun sameSeedSameInputsSameGame() {
        val one = ZidBot.play(ZidLevels.byId(6), 11L, maxPieces = 120)
        val two = ZidBot.play(ZidLevels.byId(6), 11L, maxPieces = 120)
        assertEquals(one.kinds, two.kinds)
        assertEquals(one.score, two.score)
        val other = ZidBot.play(ZidLevels.byId(6), 12L, maxPieces = 120)
        assertNotEquals(one.kinds, other.kinds)
    }
}
