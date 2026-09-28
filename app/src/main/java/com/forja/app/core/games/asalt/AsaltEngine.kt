package com.forja.app.core.games.asalt

import com.forja.app.core.games.Rng
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Faza partidei. Pauza e a UI-ului (bucla nu mai avansează). */
@Serializable
enum class AsaltPhase { Ready, Playing, LifeLost, Won, Lost }

enum class AsaltEvent { Launch, PaddleHit, WallHit, BrickHit, BrickBroken, SteelHit, Explosion, Capsule, CapsuleCaught, LifeLost, Won, Lost }

/**
 * Evenimentele unui cadru, fără alocări: tipul, poziția (unități de teren) și un argument (felul cărămizii,
 * numărul victimelor exploziei, tipul capsulei).
 */
class AsaltEvents(private val capacity: Int = 96) {
    private val types = arrayOfNulls<AsaltEvent>(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val args = IntArray(capacity)
    var size = 0
        private set

    fun type(i: Int): AsaltEvent = types[i]!!
    fun x(i: Int): Float = xs[i]
    fun y(i: Int): Float = ys[i]
    fun arg(i: Int): Int = args[i]

    fun has(t: AsaltEvent): Boolean {
        for (i in 0 until size) if (types[i] == t) return true
        return false
    }

    internal fun add(t: AsaltEvent, x: Double = 0.0, y: Double = 0.0, arg: Int = 0) {
        if (size >= capacity) return
        types[size] = t
        xs[size] = x.toFloat()
        ys[size] = y.toFloat()
        args[size] = arg
        size++
    }

    fun clear() {
        size = 0
    }
}

/** Starea completă (DataStore „forja_games”, cheia `asalt_save`). */
@Serializable
data class AsaltSave(
    val v: Int = 1,
    val level: Int,
    val rng: Long,
    val bricks: String,
    val hp: String,
    val balls: List<Double>,
    val speed: Double,
    val paddleHits: Int,
    val paddleX: Double,
    val paddleTarget: Double,
    val wideSteps: Int,
    val slowSteps: Int,
    val caps: List<Double>,
    val lives: Int,
    val phase: AsaltPhase,
    val pauseSteps: Int,
    val score: Int,
    val combo: Int,
    val sinceBreak: Int,
    val sinceHit: Int,
    val elapsedSteps: Long,
    val acc: Int,
    val version: Long
)

/**
 * Motorul ASALT: terenul 390 × 600 unități, 13 coloane de cărămizi 30 × 14 de la y = 56, nicovala la y = 552, scânteia cu
 * raza 6. Pas fix de 1/240 s dintr-un acumulator întreg (ms × 240), sub-pași ≤ R/2 (fără tunelare), coliziuni pe cele
 * 3×3 celule vecine, unghiul de pe nicovală = 60° × abaterea, |vy| ≥ 0,25 × viteza, anti-blocaj la 10 s, ajutorul
 * pentru ultimele ≤ 5 cărămizi. Determinist: aceleași intrări dau aceeași partidă.
 */
class AsaltEngine private constructor(val level: AsaltLevel, private val rng: Rng) {

    // ── zidul ──
    val bricks = ByteArray(COLS * ROWS)
    val hp = ByteArray(COLS * ROWS)
    var destructibleLeft = 0
        private set

    // ── scânteile (cele vii ocupă 0 until ballCount) ──
    val ballX = DoubleArray(MAX_BALLS)
    val ballY = DoubleArray(MAX_BALLS)
    val ballDx = DoubleArray(MAX_BALLS)
    val ballDy = DoubleArray(MAX_BALLS)
    var ballCount = 1
        private set

    var speed = level.baseSpeed
        private set
    private var paddleHits = 0

    // ── nicovala ──
    var paddleX = W / 2
        private set
    private var paddleTarget = W / 2
    var wideSteps = 0
        private set
    var slowSteps = 0
        private set

    // ── capsulele ──
    val capX = DoubleArray(MAX_CAPS)
    val capY = DoubleArray(MAX_CAPS)
    /** 0 = liber, altfel [CAP_WIDE] / [CAP_MULTI] / [CAP_SLOW]. */
    val capType = IntArray(MAX_CAPS)

    var lives = LIVES
        private set
    var phase = AsaltPhase.Ready
        private set
    private var pauseSteps = 0

    var score = 0
        private set
    private var comboK = 0
    private var sinceBreak = 0
    private var sinceHit = 0
    var elapsedSteps = 0L
        private set
    private var acc = 0
    var version = 0L
        private set

    val events = AsaltEvents()
    private val boomStack = IntArray(COLS * ROWS)

    // ───────────────────────────── Ce vede UI-ul ─────────────────────────────

    val halfWidth: Double get() = (if (wideSteps > 0) PADDLE_WIDE else PADDLE_W) / 2

    val elapsedMs: Long get() = elapsedSteps * 1000 / STEP_HZ

    /** Ajutorul pentru ultimele cărămizi e pornit (UI-ul le face să pulseze). */
    val assist: Boolean get() = phase == AsaltPhase.Playing && destructibleLeft in 1..ASSIST_BRICKS && sinceHit >= ASSIST_AFTER

    val slowed: Boolean get() = slowSteps > 0

    /** Stelele după victorie = viețile rămase. */
    val stars: Int get() = if (phase == AsaltPhase.Won) lives.coerceIn(1, 3) else 0

    fun kind(col: Int, row: Int): Int = bricks[row * COLS + col].toInt()

    fun hpAt(col: Int, row: Int): Int = hp[row * COLS + col].toInt()

    // ───────────────────────────── Comenzi ─────────────────────────────

    /** Nicovala pornește spre x (unități), cu cel mult 1600 u/s. */
    fun setPaddleTarget(x: Double) {
        val hw = halfWidth
        paddleTarget = x.coerceIn(hw, W - hw)
    }

    val target: Double get() = paddleTarget

    /** Lansează scânteia (doar în Ready). */
    fun launch(): Boolean {
        if (phase != AsaltPhase.Ready) return false
        val deg = (8.0 + rng.nextDouble() * 12.0) * (if (rng.nextInt(2) == 0) -1.0 else 1.0)
        val a = deg * PI / 180.0
        ballCount = 1
        ballDx[0] = sin(a)
        ballDy[0] = -cos(a)
        phase = AsaltPhase.Playing
        sinceBreak = 0
        sinceHit = 0
        events.add(AsaltEvent.Launch, ballX[0], ballY[0])
        version++
        return true
    }

    /** Avansează cu dtMs (acumulatorul întreg: fiecare pas = 1/240 s). Întoarce evenimentele adunate. */
    fun advance(dtMs: Int): AsaltEvents {
        if (dtMs <= 0 || phase == AsaltPhase.Won || phase == AsaltPhase.Lost) return events
        acc += dtMs * STEP_HZ
        while (acc >= 1000) {
            acc -= 1000
            step()
            if (phase == AsaltPhase.Won || phase == AsaltPhase.Lost) {
                acc = 0
                break
            }
        }
        return events
    }

    // ───────────────────────────── Pasul ─────────────────────────────

    private fun step() {
        movePaddle()
        when (phase) {
            AsaltPhase.Ready -> stickBall()
            AsaltPhase.LifeLost -> {
                pauseSteps--
                if (pauseSteps <= 0) toReady()
            }
            AsaltPhase.Playing -> physics()
            else -> Unit
        }
    }

    private fun movePaddle() {
        val hw = halfWidth
        if (paddleTarget < hw) paddleTarget = hw
        if (paddleTarget > W - hw) paddleTarget = W - hw
        val d = paddleTarget - paddleX
        val max = PADDLE_SPEED / STEP_HZ
        val before = paddleX
        paddleX += d.coerceIn(-max, max)
        paddleX = paddleX.coerceIn(hw, W - hw)
        if (paddleX != before) version++
    }

    private fun stickBall() {
        ballCount = 1
        ballX[0] = paddleX
        ballY[0] = PADDLE_Y - R - 0.01
        ballDx[0] = 0.0
        ballDy[0] = -1.0
    }

    private fun physics() {
        elapsedSteps++
        if (wideSteps > 0) wideSteps--
        if (slowSteps > 0) slowSteps--
        sinceBreak++
        sinceHit++
        val eff = speed * (if (slowSteps > 0) SLOW_FACTOR else 1.0)
        var i = 0
        while (i < ballCount) {
            if (moveBall(i, eff)) i++ else removeBall(i)
            if (destructibleLeft == 0) break
        }
        version++
        if (destructibleLeft == 0) {
            win()
            return
        }
        if (ballCount == 0) {
            loseLife()
            return
        }
        if (sinceBreak >= STUCK_STEPS) {
            for (b in 0 until ballCount) {
                rotateBall(b, if (rng.nextInt(2) == 0) -STUCK_DEG else STUCK_DEG)
                enforceVertical(b)
            }
            sinceBreak = 0
        }
        moveCapsules()
    }

    /** Mută scânteia i un pas (în sub-pași ≤ R/2); false = a căzut sub nicovală. */
    private fun moveBall(i: Int, eff: Double): Boolean {
        val dist = eff / STEP_HZ
        val n = ceil(dist / (R / 2)).toInt().coerceAtLeast(1)
        val sd = dist / n
        for (k in 0 until n) {
            val prevY = ballY[i]
            ballX[i] += ballDx[i] * sd
            ballY[i] += ballDy[i] * sd
            walls(i)
            collideBricks(i)
            if (destructibleLeft == 0) return true
            collidePaddle(i, prevY)
            if (ballY[i] - R > H) return false
        }
        if (destructibleLeft in 1..ASSIST_BRICKS && sinceHit >= ASSIST_AFTER && ballDy[i] < 0) steer(i)
        return true
    }

    private fun walls(i: Int) {
        if (ballX[i] < R) {
            ballX[i] = R
            if (ballDx[i] < 0) ballDx[i] = -ballDx[i]
            events.add(AsaltEvent.WallHit, ballX[i], ballY[i])
        } else if (ballX[i] > W - R) {
            ballX[i] = W - R
            if (ballDx[i] > 0) ballDx[i] = -ballDx[i]
            events.add(AsaltEvent.WallHit, ballX[i], ballY[i])
        }
        if (ballY[i] < R) {
            ballY[i] = R
            if (ballDy[i] < 0) ballDy[i] = -ballDy[i]
            events.add(AsaltEvent.WallHit, ballX[i], ballY[i])
        }
    }

    /** Cel mult o cărămidă pe sub-pas: cea mai apropiată dintre cele 3×3 vecine; rezolvarea pe axa cu |d| mai mare. */
    private fun collideBricks(i: Int) {
        val x = ballX[i]
        val y = ballY[i]
        val cx = floor(x / BW).toInt()
        val cy = floor((y - TOP) / BH).toInt()
        var best = -1
        var bestD = R * R
        var bdx = 0.0
        var bdy = 0.0
        for (r in cy - 1..cy + 1) {
            if (r < 0 || r >= ROWS) continue
            for (c in cx - 1..cx + 1) {
                if (c < 0 || c >= COLS) continue
                val idx = r * COLS + c
                if (bricks[idx].toInt() == 0) continue
                val left = c * BW
                val top = TOP + r * BH
                val px = x.coerceIn(left, left + BW)
                val py = y.coerceIn(top, top + BH)
                val ddx = x - px
                val ddy = y - py
                val d2 = ddx * ddx + ddy * ddy
                if (d2 < bestD) {
                    bestD = d2
                    best = idx
                    bdx = ddx
                    bdy = ddy
                }
            }
        }
        if (best < 0) return
        val c = best % COLS
        val r = best / COLS
        val left = c * BW
        val right = left + BW
        val top = TOP + r * BH
        val bottom = top + BH
        if (bdx == 0.0 && bdy == 0.0) {
            // centrul a intrat în cărămidă: înapoi pe axa cu pătrunderea cea mai mică
            val ox = if (ballDx[i] > 0) x - left else right - x
            val oy = if (ballDy[i] > 0) y - top else bottom - y
            if (ox < oy) {
                ballX[i] = if (ballDx[i] > 0) left - R else right + R
                ballDx[i] = -ballDx[i]
            } else {
                ballY[i] = if (ballDy[i] > 0) top - R else bottom + R
                ballDy[i] = -ballDy[i]
            }
        } else if (abs(bdx) > abs(bdy)) {
            if (bdx > 0) {
                ballX[i] = right + R
                if (ballDx[i] < 0) ballDx[i] = -ballDx[i]
            } else {
                ballX[i] = left - R
                if (ballDx[i] > 0) ballDx[i] = -ballDx[i]
            }
        } else {
            if (bdy > 0) {
                ballY[i] = bottom + R
                if (ballDy[i] < 0) ballDy[i] = -ballDy[i]
            } else {
                ballY[i] = top - R
                if (ballDy[i] > 0) ballDy[i] = -ballDy[i]
            }
        }
        hitBrick(i, best)
    }

    private fun collidePaddle(i: Int, prevY: Double) {
        if (ballDy[i] <= 0) return
        val hw = halfWidth
        if (prevY + R > PADDLE_Y + 0.5) return            // era deja sub fața nicovalei: ratată
        if (ballY[i] + R < PADDLE_Y) return
        if (abs(ballX[i] - paddleX) > hw + R * 0.5) return
        val off = ((ballX[i] - paddleX) / hw).coerceIn(-1.0, 1.0)
        val a = off * PADDLE_MAX_DEG * PI / 180.0
        ballDx[i] = sin(a)
        ballDy[i] = -cos(a)
        ballY[i] = PADDLE_Y - R
        paddleHits++
        if (paddleHits % 10 == 0) speed = minOf(MAX_SPEED, speed * 1.03)
        comboK = 0
        events.add(AsaltEvent.PaddleHit, ballX[i], ballY[i])
    }

    private fun hitBrick(ball: Int, idx: Int) {
        val kind = bricks[idx].toInt()
        val cx = (idx % COLS) * BW + BW / 2
        val cy = TOP + (idx / COLS) * BH + BH / 2
        if (kind == STEEL) {
            events.add(AsaltEvent.SteelHit, cx, cy)
            rotateBall(ball, (rng.nextDouble() * 2.0 - 1.0) * STEEL_JITTER_DEG)
            enforceVertical(ball)
            return
        }
        score += 10
        sinceBreak = 0
        sinceHit = 0
        hp[idx] = (hp[idx] - 1).toByte()
        events.add(AsaltEvent.BrickHit, cx, cy, kind)
        if (hp[idx] <= 0) destroy(idx)
        enforceVertical(ball)
    }

    private fun destroy(idx: Int) {
        val kind = bricks[idx].toInt()
        bricks[idx] = 0
        hp[idx] = 0
        destructibleLeft--
        comboK++
        score += 40 * MAX_HP[kind] + 10 * comboK
        val cx = (idx % COLS) * BW + BW / 2
        val cy = TOP + (idx / COLS) * BH + BH / 2
        events.add(AsaltEvent.BrickBroken, cx, cy, kind)
        dropCapsule(kind, cx, cy)
        if (kind == AMMO) explodeFrom(idx)
    }

    /** Explozia 3×3 (fără oțel); muniția lovită explodează și ea (lanț, fără recursivitate). */
    private fun explodeFrom(start: Int) {
        var top = 0
        boomStack[top++] = start
        while (top > 0) {
            val at = boomStack[--top]
            val c0 = at % COLS
            val r0 = at / COLS
            var n = 0
            for (r in r0 - 1..r0 + 1) {
                if (r < 0 || r >= ROWS) continue
                for (c in c0 - 1..c0 + 1) {
                    if (c < 0 || c >= COLS) continue
                    val idx = r * COLS + c
                    val k = bricks[idx].toInt()
                    if (k == 0 || k == STEEL) continue
                    bricks[idx] = 0
                    hp[idx] = 0
                    destructibleLeft--
                    score += 50
                    n++
                    val cx = c * BW + BW / 2
                    val cy = TOP + r * BH + BH / 2
                    dropCapsule(k, cx, cy)
                    if (k == AMMO && top < boomStack.size) boomStack[top++] = idx
                }
            }
            events.add(AsaltEvent.Explosion, c0 * BW + BW / 2, TOP + r0 * BH + BH / 2, n)
        }
        sinceBreak = 0
        sinceHit = 0
    }

    private fun dropCapsule(kind: Int, x: Double, y: Double) {
        val type = when (kind) {
            WIDE -> CAP_WIDE
            MULTI -> CAP_MULTI
            SLOW -> CAP_SLOW
            else -> return
        }
        for (j in 0 until MAX_CAPS) {
            if (capType[j] == 0) {
                capType[j] = type
                capX[j] = x
                capY[j] = y
                events.add(AsaltEvent.Capsule, x, y, type)
                return
            }
        }
    }

    private fun moveCapsules() {
        val hw = halfWidth
        val dy = CAP_SPEED / STEP_HZ
        for (j in 0 until MAX_CAPS) {
            if (capType[j] == 0) continue
            capY[j] += dy
            val caught = capY[j] + CAP_H / 2 >= PADDLE_Y && capY[j] - CAP_H / 2 <= PADDLE_Y + PADDLE_H &&
                capX[j] + CAP_W / 2 >= paddleX - hw && capX[j] - CAP_W / 2 <= paddleX + hw
            if (caught) {
                val t = capType[j]
                capType[j] = 0
                catchCapsule(t, capX[j], capY[j])
            } else if (capY[j] - CAP_H / 2 > H) {
                capType[j] = 0
            }
        }
    }

    private fun catchCapsule(type: Int, x: Double, y: Double) {
        score += 100
        events.add(AsaltEvent.CapsuleCaught, x, y, type)
        when (type) {
            CAP_WIDE -> wideSteps = WIDE_STEPS
            CAP_SLOW -> slowSteps = SLOW_STEPS
            CAP_MULTI -> split()
        }
    }

    /** Schije: până la 3 scântei, la ±20° față de prima. */
    private fun split() {
        if (ballCount == 0) return
        val bx = ballX[0]
        val by = ballY[0]
        val dx = ballDx[0]
        val dy = ballDy[0]
        var sign = 1.0
        while (ballCount < MAX_BALLS) {
            val n = ballCount
            ballX[n] = bx
            ballY[n] = by
            ballDx[n] = dx
            ballDy[n] = dy
            rotateBall(n, SPLIT_DEG * sign)
            enforceVertical(n)
            ballCount++
            sign = -sign
        }
    }

    private fun removeBall(i: Int) {
        val last = ballCount - 1
        if (i != last) {
            ballX[i] = ballX[last]
            ballY[i] = ballY[last]
            ballDx[i] = ballDx[last]
            ballDy[i] = ballDy[last]
        }
        ballCount--
    }

    private fun rotateBall(i: Int, deg: Double) {
        val a = deg * PI / 180.0
        val c = cos(a)
        val s = sin(a)
        val dx = ballDx[i]
        val dy = ballDy[i]
        ballDx[i] = dx * c - dy * s
        ballDy[i] = dx * s + dy * c
    }

    /** |vy| ≥ 0,25 × viteza (fără ping-pong aproape orizontal); vectorul rămâne unitar. */
    private fun enforceVertical(i: Int) {
        val dx = ballDx[i]
        val dy = ballDy[i]
        val len = sqrt(dx * dx + dy * dy)
        var ux = if (len > 0) dx / len else 0.0
        var uy = if (len > 0) dy / len else -1.0
        if (abs(uy) < MIN_VY) {
            uy = if (uy <= 0.0) -MIN_VY else MIN_VY
            ux = (if (ux < 0) -1.0 else 1.0) * sqrt(1.0 - MIN_VY * MIN_VY)
        }
        ballDx[i] = ux
        ballDy[i] = uy
    }

    /** Ajutorul: o scânteie care urcă se întoarce cu cel mult 30°/s spre cea mai apropiată cărămidă rămasă. */
    private fun steer(i: Int) {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (idx in 0 until COLS * ROWS) {
            val k = bricks[idx].toInt()
            if (k == 0 || k == STEEL) continue
            val tx = (idx % COLS) * BW + BW / 2
            val ty = TOP + (idx / COLS) * BH + BH / 2
            val d = (tx - ballX[i]) * (tx - ballX[i]) + (ty - ballY[i]) * (ty - ballY[i])
            if (d < bestD) {
                bestD = d
                best = idx
            }
        }
        if (best < 0) return
        val tx = (best % COLS) * BW + BW / 2
        val ty = TOP + (best / COLS) * BH + BH / 2
        val cur = atan2(ballDy[i], ballDx[i])
        val want = atan2(ty - ballY[i], tx - ballX[i])
        var diff = want - cur
        while (diff > PI) diff -= 2 * PI
        while (diff < -PI) diff += 2 * PI
        val maxTurn = ASSIST_DEG_PER_S * PI / 180.0 / STEP_HZ
        val turn = diff.coerceIn(-maxTurn, maxTurn)
        val a = cur + turn
        val ndy = sin(a)
        if (ndy >= 0) return
        ballDx[i] = cos(a)
        ballDy[i] = ndy
        enforceVertical(i)
    }

    private fun loseLife() {
        lives--
        comboK = 0
        for (j in 0 until MAX_CAPS) capType[j] = 0
        wideSteps = 0
        slowSteps = 0
        speed = level.baseSpeed
        paddleHits = 0
        events.add(AsaltEvent.LifeLost, paddleX, PADDLE_Y)
        if (lives <= 0) {
            phase = AsaltPhase.Lost
            events.add(AsaltEvent.Lost)
        } else {
            phase = AsaltPhase.LifeLost
            pauseSteps = LIFE_PAUSE_STEPS
        }
        version++
    }

    private fun toReady() {
        phase = AsaltPhase.Ready
        stickBall()
        version++
    }

    private fun win() {
        score += 500 + 300 * lives
        phase = AsaltPhase.Won
        for (j in 0 until MAX_CAPS) capType[j] = 0
        events.add(AsaltEvent.Won)
        version++
    }

    private fun build() {
        destructibleLeft = 0
        for ((r, row) in level.rows.withIndex()) {
            if (r >= ROWS) break
            for (c in 0 until COLS) {
                val k = when (row.getOrNull(c)) {
                    'a' -> CRATE
                    'b' -> SAND
                    'c' -> CONCRETE
                    '#' -> STEEL
                    'x' -> AMMO
                    'w' -> WIDE
                    'm' -> MULTI
                    's' -> SLOW
                    else -> 0
                }
                val idx = r * COLS + c
                bricks[idx] = k.toByte()
                hp[idx] = MAX_HP[k].toByte()
                if (k != 0 && k != STEEL) destructibleLeft++
            }
        }
        stickBall()
    }

    // ───────────────────────────── Salvare ─────────────────────────────

    fun save(): AsaltSave = AsaltSave(
        level = level.id,
        rng = rng.state,
        bricks = String(CharArray(COLS * ROWS) { ('0'.code + bricks[it]).toChar() }),
        hp = String(CharArray(COLS * ROWS) { ('0'.code + hp[it]).toChar() }),
        balls = buildList { for (i in 0 until ballCount) { add(ballX[i]); add(ballY[i]); add(ballDx[i]); add(ballDy[i]) } },
        speed = speed,
        paddleHits = paddleHits,
        paddleX = paddleX,
        paddleTarget = paddleTarget,
        wideSteps = wideSteps,
        slowSteps = slowSteps,
        caps = buildList { for (j in 0 until MAX_CAPS) if (capType[j] != 0) { add(capX[j]); add(capY[j]); add(capType[j].toDouble()) } },
        lives = lives,
        phase = phase,
        pauseSteps = pauseSteps,
        score = score,
        combo = comboK,
        sinceBreak = sinceBreak,
        sinceHit = sinceHit,
        elapsedSteps = elapsedSteps,
        acc = acc,
        version = version
    )

    companion object {
        const val W = 390.0
        const val H = 600.0
        const val COLS = 13
        const val ROWS = 12
        const val BW = 30.0
        const val BH = 14.0
        const val TOP = 56.0
        const val PADDLE_Y = 552.0
        const val PADDLE_H = 12.0
        const val PADDLE_W = 64.0
        const val PADDLE_WIDE = 96.0
        const val PADDLE_SPEED = 1600.0
        const val PADDLE_MAX_DEG = 60.0
        const val R = 6.0
        const val MAX_SPEED = 600.0
        const val MIN_VY = 0.25
        const val STEP_HZ = 240
        const val MAX_BALLS = 3
        const val MAX_CAPS = 6
        const val CAP_SPEED = 140.0
        const val CAP_W = 26.0
        const val CAP_H = 12.0
        const val LIVES = 3
        const val SLOW_FACTOR = 0.7
        const val WIDE_STEPS = 12 * STEP_HZ
        const val SLOW_STEPS = 10 * STEP_HZ
        const val STUCK_STEPS = 10 * STEP_HZ
        const val STUCK_DEG = 7.0
        const val STEEL_JITTER_DEG = 3.0
        const val SPLIT_DEG = 20.0
        const val ASSIST_BRICKS = 5
        const val ASSIST_AFTER = 4 * STEP_HZ
        const val ASSIST_DEG_PER_S = 30.0
        const val LIFE_PAUSE_STEPS = 700 * STEP_HZ / 1000

        // felurile cărămizilor
        const val CRATE = 1
        const val SAND = 2
        const val CONCRETE = 3
        const val STEEL = 4
        const val AMMO = 5
        const val WIDE = 6
        const val MULTI = 7
        const val SLOW = 8
        val MAX_HP = intArrayOf(0, 1, 2, 3, 0, 1, 1, 1, 1)

        // capsulele
        const val CAP_WIDE = 1
        const val CAP_MULTI = 2
        const val CAP_SLOW = 3

        fun create(level: AsaltLevel, seed: Long): AsaltEngine {
            val e = AsaltEngine(level, Rng(seed))
            e.build()
            e.paddleX = W / 2
            e.paddleTarget = W / 2
            e.stickBall()
            return e
        }

        fun restore(s: AsaltSave): AsaltEngine {
            require(s.v == 1) { "unknown save version ${s.v}" }
            require(s.bricks.length == COLS * ROWS && s.hp.length == COLS * ROWS) { "bad wall" }
            val e = AsaltEngine(AsaltLevels.byId(s.level), Rng(s.rng))
            var left = 0
            for (i in 0 until COLS * ROWS) {
                val k = s.bricks[i] - '0'
                require(k in 0..SLOW) { "bad brick" }
                e.bricks[i] = k.toByte()
                e.hp[i] = (s.hp[i] - '0').coerceIn(0, 3).toByte()
                if (k != 0 && k != STEEL) left++
            }
            e.destructibleLeft = left
            val nb = (s.balls.size / 4).coerceAtMost(MAX_BALLS)
            e.ballCount = nb
            for (i in 0 until nb) {
                e.ballX[i] = s.balls[4 * i]
                e.ballY[i] = s.balls[4 * i + 1]
                e.ballDx[i] = s.balls[4 * i + 2]
                e.ballDy[i] = s.balls[4 * i + 3]
            }
            e.speed = s.speed
            e.paddleHits = s.paddleHits
            e.paddleX = s.paddleX
            e.paddleTarget = s.paddleTarget
            e.wideSteps = s.wideSteps
            e.slowSteps = s.slowSteps
            val nc = (s.caps.size / 3).coerceAtMost(MAX_CAPS)
            for (j in 0 until nc) {
                e.capX[j] = s.caps[3 * j]
                e.capY[j] = s.caps[3 * j + 1]
                e.capType[j] = s.caps[3 * j + 2].toInt().coerceIn(0, CAP_SLOW)
            }
            e.lives = s.lives
            e.phase = s.phase
            e.pauseSteps = s.pauseSteps
            e.score = s.score
            e.comboK = s.combo
            e.sinceBreak = s.sinceBreak
            e.sinceHit = s.sinceHit
            e.elapsedSteps = s.elapsedSteps
            e.acc = s.acc
            e.version = s.version
            if (e.phase == AsaltPhase.Ready && e.ballCount == 0) e.stickBall()
            return e
        }
    }
}
