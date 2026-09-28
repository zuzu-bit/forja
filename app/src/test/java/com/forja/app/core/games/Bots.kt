package com.forja.app.core.games

import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltEvent
import com.forja.app.core.games.asalt.AsaltLevel
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.core.games.zid.ZidCmd
import com.forja.app.core.games.zid.ZidConfig
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidLevel
import com.forja.app.core.games.zid.ZidPhase
import com.forja.app.core.games.zid.ZidPieces

/**
 * Roboții din teste: dovada că fiecare nivel se poate câștiga. Nu țintesc perfect (un om care țintește e mai rapid);
 * folosesc doar comenzile publice ale motoarelor, ca degetul.
 */
internal object ZidBot {
    private const val W = ZidEngine.W
    private const val H = ZidEngine.H

    private fun collides(b: ByteArray, kind: Int, rot: Int, x: Int, y: Int): Boolean {
        val c = ZidPieces.cells[kind][rot]
        for (i in 0 until 4) {
            val cx = x + c[2 * i]
            val cy = y + c[2 * i + 1]
            if (cx < 0 || cx >= W || cy >= H) return true
            if (cy >= 0 && b[cy * W + cx].toInt() != 0) return true
        }
        return false
    }

    /**
     * Evaluarea lui Pierre Dellacherie (înălțimea aterizării, celule erodate, tranziții pe rânduri și coloane,
     * goluri, puțuri) — simplă și foarte solidă, deci o dovadă bună că nivelul se poate câștiga.
     */
    private fun evaluate(b: ByteArray, landingRow: Double, cleared: Int, pieceCellsCleared: Int): Double {
        fun filled(x: Int, y: Int): Boolean = x < 0 || x >= W || y >= H || (y >= 0 && b[y * W + x].toInt() != 0)
        var rowT = 0
        for (y in 0 until H) {
            var prev = true
            for (x in 0 until W) {
                val f = filled(x, y)
                if (f != prev) rowT++
                prev = f
            }
            if (!prev) rowT++
        }
        var colT = 0
        var holes = 0
        for (x in 0 until W) {
            var prev = false
            var seen = false
            for (y in 0 until H) {
                val f = filled(x, y)
                if (f != prev) colT++
                if (f) seen = true else if (seen) holes++
                prev = f
            }
            if (!prev) colT++
        }
        var wells = 0
        for (x in 0 until W) {
            var depth = 0
            for (y in 0 until H) {
                if (!filled(x, y) && filled(x - 1, y) && filled(x + 1, y)) {
                    depth++
                    wells += depth
                } else if (filled(x, y)) {
                    depth = 0
                }
            }
        }
        val landingHeight = H - landingRow
        return -1.0 * landingHeight + 1.0 * cleared * pieceCellsCleared - 1.0 * rowT - 1.0 * colT - 4.0 * holes - 1.0 * wells
    }

    /** Așază piesa curentă în locul cel mai bun (rotiri, mutări, cădere bruscă). false = nicio piesă. */
    fun place(e: ZidEngine): Boolean {
        if (!e.hasPiece) return false
        val kind = e.pieceKind
        val board = e.cells.copyOf()
        var best = Double.NEGATIVE_INFINITY
        var bestRot = 0
        var bestX = e.pieceX
        val y0 = e.pieceY
        for (rot in 0 until 4) {
            if (kind == ZidPieces.O && rot > 0) break
            for (x in -3..W) {
                if (collides(board, kind, rot, x, y0)) continue
                // drumul pe orizontală, la înălțimea de apariție, trebuie să fie liber
                var free = true
                val step = if (x >= e.pieceX) 1 else -1
                var cx = e.pieceX
                while (cx != x) {
                    cx += step
                    if (collides(board, kind, rot, cx, y0)) { free = false; break }
                }
                if (!free) continue
                var y = y0
                while (!collides(board, kind, rot, x, y + 1)) y++
                val b2 = board.copyOf()
                val c = ZidPieces.cells[kind][rot]
                for (i in 0 until 4) {
                    val py = y + c[2 * i + 1]
                    if (py >= 0) b2[py * W + x + c[2 * i]] = kind.toByte()
                }
                var cleared = 0
                var pieceCells = 0
                var w = H - 1
                for (r in H - 1 downTo 0) {
                    var full = true
                    for (k in 0 until W) if (b2[r * W + k].toInt() == 0) { full = false; break }
                    if (full) {
                        cleared++
                        for (i in 0 until 4) if (y + c[2 * i + 1] == r) pieceCells++
                        continue
                    }
                    if (w != r) System.arraycopy(b2, r * W, b2, w * W, W)
                    w--
                }
                while (w >= 0) { for (k in 0 until W) b2[w * W + k] = 0; w-- }
                var minY = 9
                var maxY = 0
                for (i in 0 until 4) { minY = minOf(minY, c[2 * i + 1]); maxY = maxOf(maxY, c[2 * i + 1]) }
                val s = evaluate(b2, y + (minY + maxY) / 2.0, cleared, pieceCells)
                if (s > best) {
                    best = s
                    bestRot = rot
                    bestX = x
                }
            }
        }
        repeat(bestRot) { e.input(ZidCmd.RotateCw) }
        var guard = 0
        while (e.hasPiece && e.pieceX < bestX && guard++ < 12) if (!e.input(ZidCmd.Right)) break
        guard = 0
        while (e.hasPiece && e.pieceX > bestX && guard++ < 12) if (!e.input(ZidCmd.Left)) break
        if (e.hasPiece) e.input(ZidCmd.HardDrop)
        return true
    }

    class Result(val phase: ZidPhase, val pieces: Int, val score: Int, val elapsedMs: Long, val kinds: List<Int>)

    /** Joacă nivelul până la capăt (sau maxPieces). */
    fun play(level: ZidLevel, seed: Long, maxPieces: Int = 1500, cfg: ZidConfig = ZidConfig(clearDelayMs = 0)): Result {
        val e = ZidEngine.create(level, seed, cfg)
        e.start()
        var pieces = 0
        val kinds = ArrayList<Int>()
        var frames = 0
        while (e.phase != ZidPhase.Won && e.phase != ZidPhase.Lost && pieces < maxPieces && frames < 2_000_000) {
            if (e.hasPiece && e.phase == ZidPhase.Falling) {
                kinds += e.pieceKind
                place(e)
                pieces++
            }
            e.advance(16)
            e.clearEvents()
            frames++
        }
        return Result(e.phase, pieces, e.score, e.elapsedMs, kinds)
    }
}

internal object AsaltBot {
    class Result(val phase: AsaltPhase, val seconds: Double, val lives: Int, val score: Int)

    /**
     * Robotul din games.md §5.2: urmărește scânteia cea mai de jos care coboară și o lovește la o abatere ÎNTÂMPLĂTOARE
     * (±0,8 din jumătatea nicovalei); nu țintește. Lansează imediat.
     */
    fun play(level: AsaltLevel, seed: Long, maxSeconds: Int = 600): Result {
        val e = AsaltEngine.create(level, seed)
        val rnd = Rng(seed * 7919 + 17)
        var offset = 0.0
        var ms = 0L
        val limit = maxSeconds * 1000L
        while (e.phase != AsaltPhase.Won && e.phase != AsaltPhase.Lost && ms < limit) {
            if (e.phase == AsaltPhase.Ready) e.launch()
            var pick = -1
            var lowest = -1.0
            for (i in 0 until e.ballCount) {
                if (e.ballDy[i] > 0 && e.ballY[i] > lowest) { lowest = e.ballY[i]; pick = i }
            }
            if (pick < 0) for (i in 0 until e.ballCount) if (e.ballY[i] > lowest) { lowest = e.ballY[i]; pick = i }
            if (pick >= 0) e.setPaddleTarget(e.ballX[pick] + offset)
            val dt = if (ms % 50L == 0L) 17 else 16
            val ev = e.advance(dt)
            if (ev.has(AsaltEvent.PaddleHit)) offset = (rnd.nextDouble() * 2 - 1) * 0.8 * e.halfWidth
            ev.clear()
            ms += dt
        }
        return Result(e.phase, ms / 1000.0, e.lives, e.score)
    }
}
