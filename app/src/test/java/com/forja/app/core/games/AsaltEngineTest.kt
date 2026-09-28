package com.forja.app.core.games

import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltEngine.Companion.AMMO
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CAP_MULTI
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CAP_SLOW
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CAP_WIDE
import com.forja.app.core.games.asalt.AsaltEngine.Companion.COLS
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CONCRETE
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CRATE
import com.forja.app.core.games.asalt.AsaltEngine.Companion.PADDLE_Y
import com.forja.app.core.games.asalt.AsaltEngine.Companion.R
import com.forja.app.core.games.asalt.AsaltEngine.Companion.ROWS
import com.forja.app.core.games.asalt.AsaltEngine.Companion.STEEL
import com.forja.app.core.games.asalt.AsaltEvent
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.core.games.asalt.AsaltSave
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Fizica ASALT (games.md §4.4, §8): pereți, nicovală, cărămizi, explozii, capsule, vieți, anti-blocaj, tunelare, salvare. */
class AsaltEngineTest {

    private val BW = AsaltEngine.BW
    private val BH = AsaltEngine.BH
    private val TOP = AsaltEngine.TOP

    /** Un zid din celule (col, rând, fel); restul gol. */
    private fun wall(vararg cells: Triple<Int, Int, Int>): Pair<String, String> {
        val k = CharArray(COLS * ROWS) { '0' }
        val h = CharArray(COLS * ROWS) { '0' }
        for ((c, r, kind) in cells) {
            k[r * COLS + c] = ('0'.code + kind).toChar()
            h[r * COLS + c] = ('0'.code + AsaltEngine.MAX_HP[kind]).toChar()
        }
        return String(k) to String(h)
    }

    /** O partidă în plin cu o singură scânteie unde vrem. */
    private fun playing(
        x: Double, y: Double, deg: Double, bricks: Pair<String, String>,
        paddleX: Double = 195.0, speed: Double = 320.0, level: Int = 1
    ): AsaltEngine {
        val a = deg * PI / 180
        val base = AsaltEngine.create(AsaltLevels.byId(level), 1L).save()
        val s = base.copy(
            bricks = bricks.first, hp = bricks.second, balls = listOf(x, y, sin(a), -cos(a)), speed = speed,
            paddleX = paddleX, paddleTarget = paddleX, phase = AsaltPhase.Playing
        )
        return AsaltEngine.restore(s)
    }

    /** Un zid departe, ca partida să nu se termine singură. */
    private val farCrate = wall(Triple(0, 0, CRATE))

    private fun run(e: AsaltEngine, ms: Int, each: (AsaltEngine) -> Unit = {}) {
        var t = 0
        while (t < ms && e.phase != AsaltPhase.Won && e.phase != AsaltPhase.Lost) {
            e.advance(4)
            each(e)
            t += 4
        }
    }

    @Test
    fun wallsReflect() {
        val e = playing(20.0, 300.0, -70.0, farCrate)          // spre stânga, ușor în sus
        assertTrue(e.ballDx[0] < 0)
        var hit = false
        run(e, 200) { if (it.events.has(AsaltEvent.WallHit)) hit = true; it.events.clear() }
        assertTrue(hit)
        assertTrue(e.ballDx[0] > 0)
        val t = playing(200.0, 30.0, 10.0, farCrate)           // spre tavan
        run(t, 200) { it.events.clear() }
        assertTrue(t.ballDy[0] > 0)
    }

    @Test
    fun paddleEdgeGivesSixtyDegrees() {
        val hw = AsaltEngine.PADDLE_W / 2
        val e = playing(195.0 + hw, PADDLE_Y - 40, 180.0, farCrate)   // cade drept pe marginea dreaptă
        var bounced = false
        run(e, 400) { if (it.events.has(AsaltEvent.PaddleHit) && !bounced) {
            bounced = true
            val angle = Math.toDegrees(kotlin.math.atan2(it.ballDx[0], -it.ballDy[0]))
            assertEquals(60.0, angle, 3.5)                    // nicovala s-a mișcat puțin în pasul acela
        }; it.events.clear() }
        assertTrue(bounced)
        val c = playing(195.0, PADDLE_Y - 40, 180.0, farCrate)
        run(c, 400) { it.events.clear() }
        assertEquals(0.0, c.ballDx[0], 1e-9)
    }

    @Test
    fun verticalComponentNeverBelowQuarter() {
        for (l in AsaltLevels.all.take(6)) {
            val e = AsaltEngine.create(l, l.id.toLong())
            var ms = 0
            while (ms < 60_000 && e.phase != AsaltPhase.Won && e.phase != AsaltPhase.Lost) {
                if (e.phase == AsaltPhase.Ready) e.launch()
                if (e.ballCount > 0) e.setPaddleTarget(e.ballX[0])
                e.advance(16)
                e.events.clear()
                if (e.phase == AsaltPhase.Playing) for (i in 0 until e.ballCount) {
                    assertTrue("L${l.id} vy=${e.ballDy[i]}", abs(e.ballDy[i]) >= AsaltEngine.MIN_VY - 1e-9)
                    val len = e.ballDx[i] * e.ballDx[i] + e.ballDy[i] * e.ballDy[i]
                    assertEquals(1.0, len, 1e-6)
                }
                ms += 16
            }
        }
    }

    @Test
    fun hitsDecrementHpAndSteelNeverBreaks() {
        val e = playing(4 * BW + BW / 2, TOP + 5 * BH + 60, 0.0, wall(Triple(4, 5, CONCRETE), Triple(6, 5, STEEL), Triple(0, 0, CRATE)))
        run(e, 300) { it.events.clear() }
        assertEquals(2, e.hpAt(4, 5))
        assertEquals(CONCRETE, e.kind(4, 5))
        val s = playing(6 * BW + BW / 2, TOP + 5 * BH + 60, 0.0, wall(Triple(6, 5, STEEL), Triple(0, 0, CRATE)))
        var steel = 0
        run(s, 3_000) { if (it.events.has(AsaltEvent.SteelHit)) steel++; it.setPaddleTarget(it.ballX[0]); it.events.clear() }
        assertTrue(steel >= 2)
        assertEquals(STEEL, s.kind(6, 5))
    }

    @Test
    fun ammoClearsThreeByThreeButNotSteel() {
        val cells = mutableListOf(Triple(6, 5, AMMO), Triple(0, 0, CRATE))
        for (c in 5..7) for (r in 4..6) if (!(c == 6 && r == 5)) cells += Triple(c, r, if (c == 7 && r == 4) STEEL else CRATE)
        // cărămida de sub muniție e ținta scânteii: o spargem mai întâi ca să ajungem la x
        val e = playing(6 * BW + BW / 2, TOP + 7 * BH + 40, 0.0, wall(*cells.toTypedArray()))
        var boom = false
        run(e, 6_000) {
            if (it.events.has(AsaltEvent.Explosion)) boom = true
            if (!boom) it.setPaddleTarget(it.ballX[0])
            it.events.clear()
        }
        assertTrue(boom)
        for (c in 5..7) for (r in 4..6) {
            if (c == 7 && r == 4) assertEquals(STEEL, e.kind(c, r)) else assertEquals("($c,$r)", 0, e.kind(c, r))
        }
    }

    private fun catchCapsule(kind: Int): AsaltEngine {
        val col = 6
        val e = playing(col * BW + BW / 2, TOP + 3 * BH + 40, 0.0, wall(Triple(col, 3, kind), Triple(0, 0, CRATE)), paddleX = col * BW + BW / 2)
        var ms = 0
        while (ms < 6_000 && e.phase == AsaltPhase.Playing) {
            // nicovala stă sub capsulă (dacă e una), altfel sub scânteie
            var tx = e.ballX[0]
            for (j in 0 until AsaltEngine.MAX_CAPS) if (e.capType[j] != 0) tx = e.capX[j]
            e.setPaddleTarget(tx)
            e.advance(8)
            if (e.events.has(AsaltEvent.CapsuleCaught)) break
            e.events.clear()
            ms += 8
        }
        return e
    }

    @Test
    fun capsulesDropAndTimersExpire() {
        val wide = catchCapsule(AsaltEngine.WIDE)
        assertTrue(wide.events.has(AsaltEvent.CapsuleCaught))
        assertEquals(AsaltEngine.WIDE_STEPS, wide.wideSteps)
        assertEquals(AsaltEngine.PADDLE_WIDE / 2, wide.halfWidth, 1e-9)
        // timpul trece doar în joc: o secundă rămasă, apoi nicovala revine la 64
        val soon = AsaltEngine.restore(wide.save().copy(wideSteps = AsaltEngine.STEP_HZ))
        var i = 0
        while (i < 1_100 && soon.phase == AsaltPhase.Playing) { soon.setPaddleTarget(soon.ballX[0]); soon.advance(10); soon.events.clear(); i += 10 }
        assertEquals(AsaltPhase.Playing, soon.phase)
        assertEquals(0, soon.wideSteps)
        assertEquals(AsaltEngine.PADDLE_W / 2, soon.halfWidth, 1e-9)

        val slow = catchCapsule(AsaltEngine.SLOW)
        assertEquals(AsaltEngine.SLOW_STEPS, slow.slowSteps)
        assertTrue(slow.slowed)

        val multi = catchCapsule(AsaltEngine.MULTI)
        assertEquals(3, multi.ballCount)
        for (b in 1 until 3) assertNotEquals(multi.ballDx[0], multi.ballDx[b], 1e-6)
    }

    @Test
    fun capsuleTypesMatchBricks() {
        for ((kind, cap) in listOf(AsaltEngine.WIDE to CAP_WIDE, AsaltEngine.MULTI to CAP_MULTI, AsaltEngine.SLOW to CAP_SLOW)) {
            val e = playing(6 * BW + BW / 2, TOP + 3 * BH + 40, 0.0, wall(Triple(6, 3, kind), Triple(0, 0, CRATE)))
            var seen = 0
            run(e, 600) {
                val ev = it.events
                for (k in 0 until ev.size) if (ev.type(k) == AsaltEvent.Capsule) seen = ev.arg(k)
                ev.clear()
            }
            assertEquals(cap, seen)
        }
    }

    @Test
    fun lifeLostOnlyWhenAllBallsAreOut() {
        val bricks = farCrate
        val base = AsaltEngine.create(AsaltLevels.byId(1), 1L).save()
        val two = base.copy(
            bricks = bricks.first, hp = bricks.second, phase = AsaltPhase.Playing,
            balls = listOf(20.0, 580.0, 0.25, 0.968, 370.0, 300.0, 0.0, -1.0), paddleX = 195.0, paddleTarget = 195.0
        )
        val e = AsaltEngine.restore(two)
        run(e, 300) { it.setPaddleTarget(it.ballX[0]); it.events.clear() }
        assertEquals(3, e.lives)
        assertEquals(1, e.ballCount)
        val one = AsaltEngine.restore(two.copy(balls = listOf(20.0, 590.0, 0.25, 0.968), paddleX = 350.0, paddleTarget = 350.0))
        run(one, 300) { it.events.clear() }
        assertEquals(2, one.lives)
        assertEquals(AsaltPhase.LifeLost, one.phase)
        run(one, 800) { it.events.clear() }
        assertEquals(AsaltPhase.Ready, one.phase)
    }

    @Test
    fun threeLivesThenLost() {
        val e = playing(20.0, 590.0, 170.0, farCrate, paddleX = 350.0)
        var guard = 0
        while (e.phase != AsaltPhase.Lost && guard++ < 50) {
            if (e.phase == AsaltPhase.Ready) {
                e.launch()
            }
            e.setPaddleTarget(if (e.ballX[0] < 195) 360.0 else 30.0)   // mereu în partea cealaltă
            run(e, 2_000) { it.setPaddleTarget(if (it.ballX[0] < 195) 360.0 else 30.0); it.events.clear() }
        }
        assertEquals(AsaltPhase.Lost, e.phase)
        assertEquals(0, e.lives)
    }

    @Test
    fun noDestructibleBricksWins() {
        val e = playing(6 * BW + BW / 2, TOP + 3 * BH + 40, 0.0, wall(Triple(6, 3, CRATE), Triple(2, 2, STEEL)))
        run(e, 1_000) { it.events.clear() }
        assertEquals(AsaltPhase.Won, e.phase)
        assertEquals(3, e.stars)
        assertTrue(e.score >= 500 + 900)
    }

    @Test
    fun antiStuckTurnsAPerfectlyVerticalLoop() {
        // scânteia urcă drept, lovește tavanul, cade drept pe mijlocul nicovalei, urcă drept… fără nicio cărămidă atinsă
        // șase lăzi departe, în colț: ajutorul pentru ultimele ≤ 5 cărămizi nu pornește
        val crates = Array(6) { Triple(it % 2, 9 + it / 2, CRATE) }
        val e = playing(195.0, 400.0, 0.0, wall(*crates), paddleX = 195.0)
        var t = 0
        while (t < 9_900) { e.advance(4); e.events.clear(); t += 4 }
        assertEquals(AsaltPhase.Playing, e.phase)
        assertEquals(0.0, e.ballDx[0], 1e-12)
        var turnedAt = -1
        while (t < 10_400 && turnedAt < 0) {
            e.advance(4)
            e.events.clear()
            t += 4
            if (abs(e.ballDx[0]) > 0.05) turnedAt = t
        }
        assertTrue("turned at $turnedAt", turnedAt in 9_990..10_020)
    }

    @Test
    fun noTunnellingAtMaxSpeed() {
        val col = 6
        val row = 5
        val cx = col * BW + BW / 2
        val cy = TOP + row * BH + BH / 2
        for (k in 0 until 20) {
            val deg = -57.0 + k * 6.0                        // de dedesubt, din 20 de unghiuri
            val a = deg * PI / 180
            val dist = 150.0
            val sx = cx - sin(a) * dist
            val sy = cy + cos(a) * dist
            val e = playing(sx, sy, deg, wall(Triple(col, row, CRATE), Triple(0, 11, STEEL)), speed = AsaltEngine.MAX_SPEED)
            var hit = false
            run(e, 600) { if (it.events.has(AsaltEvent.BrickHit) || it.phase == AsaltPhase.Won) hit = true; it.events.clear() }
            assertTrue("angle $deg", hit || e.phase == AsaltPhase.Won)
        }
    }

    @Test
    fun saveRoundTripContinuesIdentically() {
        val json = Json { encodeDefaults = true }
        for (lvl in listOf(1, 4, 6, 9)) {
            val a = AsaltEngine.create(AsaltLevels.byId(lvl), lvl * 13L)
            val script = Rng(lvl.toLong())
            fun frame(e: AsaltEngine, i: Int, offset: Double) {
                if (e.phase == AsaltPhase.Ready) e.launch()
                if (e.ballCount > 0) e.setPaddleTarget(e.ballX[0] + offset)
                e.advance(8 + i % 9)
                e.events.clear()
            }
            val offsets = DoubleArray(600) { (script.nextDouble() - 0.5) * 40 }
            for (i in 0 until 300) frame(a, i, offsets[i])
            val snap = json.encodeToString(AsaltSave.serializer(), a.save())
            val b = AsaltEngine.restore(json.decodeFromString(AsaltSave.serializer(), snap))
            assertEquals(a.save(), b.save())
            for (i in 300 until 600) {
                frame(a, i, offsets[i])
                frame(b, i, offsets[i])
            }
            assertEquals("L$lvl", json.encodeToString(AsaltSave.serializer(), a.save()), json.encodeToString(AsaltSave.serializer(), b.save()))
        }
    }

    @Test
    fun ballFollowsPaddleUntilLaunch() {
        val e = AsaltEngine.create(AsaltLevels.byId(1), 3L)
        e.setPaddleTarget(80.0)
        e.advance(500)
        assertEquals(AsaltPhase.Ready, e.phase)
        assertEquals(e.paddleX, e.ballX[0], 1e-9)
        assertEquals(PADDLE_Y - R, e.ballY[0], 0.1)
        assertTrue(e.launch())
        assertEquals(AsaltPhase.Playing, e.phase)
        assertTrue(e.ballDy[0] < 0)
    }

    /** Un zid doar ales (Ready, fără niciun pas de joc) nu e o partidă de reluat; după o viață pierdută, Ready este. */
    @Test
    fun resumableOnlyAfterTheFirstLaunch() {
        val fresh = AsaltEngine.create(AsaltLevels.byId(1), 1L)
        assertFalse(fresh.resumable)
        run(fresh, 500) { it.events.clear() }                 // nicovala se mișcă, scânteia stă: tot neînceput
        assertFalse(fresh.resumable)
        assertTrue(fresh.launch())
        run(fresh, 100) { it.events.clear() }
        assertTrue(fresh.resumable)

        val base = AsaltEngine.create(AsaltLevels.byId(1), 1L).save()
        val one = AsaltEngine.restore(
            base.copy(
                bricks = farCrate.first, hp = farCrate.second, phase = AsaltPhase.Playing,
                balls = listOf(20.0, 590.0, 0.25, 0.968), paddleX = 350.0, paddleTarget = 350.0
            )
        )
        run(one, 1_100) { it.events.clear() }
        assertEquals(AsaltPhase.Ready, one.phase)
        assertTrue(one.resumable)
    }
}
