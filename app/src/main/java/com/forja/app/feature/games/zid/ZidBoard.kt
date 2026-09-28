package com.forja.app.feature.games.zid

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.games.Rng
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidPhase
import com.forja.app.core.games.zid.ZidPieces
import com.forja.app.feature.games.Rivet
import com.forja.app.feature.games.ZidKindColors
import com.forja.app.feature.inventory.W06
import com.forja.app.feature.inventory.W09
import kotlin.math.max

/**
 * Efectele ZID, doar vizuale (oprite sub mișcare redusă): scânteile rândurilor închise (un bazin fix, fără alocări),
 * prăbușirea de 120 ms a rândurilor de deasupra. Pozițiile sunt în celule, deci nu depind de mărimea tablei.
 */
internal class ZidFx {
    private val cap = 64
    val x = FloatArray(cap)
    val y = FloatArray(cap)
    private val vx = FloatArray(cap)
    private val vy = FloatArray(cap)
    val life = FloatArray(cap)          // ms rămase; ≤ 0 = liberă
    val maxLife = FloatArray(cap)
    private val rng = Rng(0x5EED)
    private var lastCollapse = 0L

    /** Prăbușirea: ms rămase (0 = niciuna). */
    var collapseMs = 0
        private set

    val active: Boolean
        get() {
            if (collapseMs > 0) return true
            for (i in 0 until cap) if (life[i] > 0f) return true
            return false
        }

    /** Șase scântei pe rând închis, din rândurile motorului (apelat la evenimentul Cleared). */
    fun onCleared(e: ZidEngine) {
        for (k in 0 until e.clearCount) {
            val row = e.clearRows[k] - ZidEngine.HIDDEN
            repeat(6) { spawn(rng.nextFloat() * ZidEngine.W, row + 0.5f) }
        }
    }

    /** Pornește prăbușirea când motorul a scos rânduri (collapseStamp nou). */
    fun onCollapse(e: ZidEngine) {
        if (e.collapseStamp != lastCollapse) {
            lastCollapse = e.collapseStamp
            collapseMs = COLLAPSE_MS
        }
    }

    fun syncCollapse(e: ZidEngine) {
        lastCollapse = e.collapseStamp
    }

    private fun spawn(sx: Float, sy: Float) {
        for (i in 0 until cap) {
            if (life[i] <= 0f) {
                x[i] = sx
                y[i] = sy
                vx[i] = (rng.nextFloat() * 2f - 1f) * 3f
                vy[i] = -(2f + rng.nextFloat() * 4f)
                maxLife[i] = 480f + rng.nextFloat() * 260f
                life[i] = maxLife[i]
                return
            }
        }
    }

    fun step(dtMs: Int) {
        if (collapseMs > 0) collapseMs = (collapseMs - dtMs).coerceAtLeast(0)
        val dt = dtMs / 1000f
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dtMs
            vy[i] += 14f * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
    }

    fun clear() {
        collapseMs = 0
        for (i in 0 until cap) life[i] = 0f
    }

    companion object {
        const val COLLAPSE_MS = 120
    }
}

/** O cărămidă: fundul în culoarea piesei, mortar de 1,5 dp, lumină sus, umbră jos, pătratul interior mai deschis. */
private fun DrawScope.brick(left: Float, top: Float, cell: Float, color: Color, gap: Float, corner: CornerRadius, bevel: Float, alpha: Float = 1f) {
    val s = cell - 2 * gap
    val l = left + gap
    val t = top + gap
    drawRoundRect(color, topLeft = Offset(l, t), size = Size(s, s), cornerRadius = corner, alpha = alpha)
    val inset = s * 0.26f
    drawRect(Color.White.copy(alpha = 0.08f * alpha), topLeft = Offset(l + inset, t + inset), size = Size(s - 2 * inset, s - 2 * inset))
    drawRect(Color.White.copy(alpha = 0.18f * alpha), topLeft = Offset(l + corner.x * 0.5f, t), size = Size(s - corner.x, bevel))
    drawRect(Color.Black.copy(alpha = 0.25f * alpha), topLeft = Offset(l + corner.x * 0.5f, t + s - bevel), size = Size(s - corner.x, bevel))
}

/** Molozul: bloc de oțel stins cu două nituri. */
private fun DrawScope.rubble(left: Float, top: Float, cell: Float, gap: Float, corner: CornerRadius, alpha: Float = 1f) {
    val s = cell - 2 * gap
    val l = left + gap
    val t = top + gap
    drawRoundRect(ZidKindColors[ZidPieces.RUBBLE], topLeft = Offset(l, t), size = Size(s, s), cornerRadius = corner, alpha = alpha)
    val r = max(1f, s * 0.07f)
    drawCircle(Rivet, r, Offset(l + s * 0.27f, t + s * 0.27f), alpha = alpha)
    drawCircle(Rivet, r, Offset(l + s * 0.73f, t + s * 0.73f), alpha = alpha)
    drawRect(Color.Black.copy(alpha = 0.22f * alpha), topLeft = Offset(l, t + s - s * 0.08f), size = Size(s, s * 0.08f))
}

/**
 * Tabla ZID (10 × 18 celule vizibile), desenată doar în faza de desen: citește `frame` (contorul de cadre) ca să se
 * redeseneze, fără recompoziție. Nimic alocat în desen: căile, pensulele și punctele grilei stau în drawWithCache.
 */
@Composable
internal fun ZidBoard(engine: ZidEngine, fx: ZidFx, frame: State<Long>, clearDelayMs: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val w = ZidEngine.W
            val vis = ZidEngine.VISIBLE
            val hidden = ZidEngine.HIDDEN
            val cell = size.width / w
            val gap = 0.75.dp.toPx()
            val corner = CornerRadius(2.dp.toPx())
            val boardCorner = CornerRadius(8.dp.toPx())
            val bevel = max(1f, 1.5.dp.toPx())
            val border = Stroke(1.dp.toPx())
            val dashW = 1.5.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            val dotW = 2.dp.toPx()
            val dots = ArrayList<Offset>((w - 1) * (vis - 1))
            for (gy in 1 until vis) for (gx in 1 until w) dots += Offset(gx * cell, gy * cell)
            val sparkR = 1.6.dp.toPx()
            onDrawBehind {
                frame.value // redesenare la fiecare cadru nou (fără recompoziție)
                val e = engine
                drawRoundRect(Surface1, cornerRadius = boardCorner)
                drawPoints(dots, PointMode.Points, W06, strokeWidth = dotW, cap = StrokeCap.Round)
                if (e.danger) {
                    drawRect(Error.copy(alpha = 0.06f), size = Size(size.width, cell * 3))
                }
                // prăbușirea: rândurile coboară 120 ms spre locul nou
                val collapse = if (fx.collapseMs > 0) fx.collapseMs / ZidFx.COLLAPSE_MS.toFloat() else 0f
                val clearing = e.phase == ZidPhase.Clearing
                for (y in hidden until ZidEngine.H) {
                    val shiftPx = if (collapse > 0f) e.shift[y] * cell * collapse * collapse else 0f
                    val top = (y - hidden) * cell - shiftPx
                    var flash = false
                    if (clearing) for (k in 0 until e.clearCount) if (e.clearRows[k] == y) flash = true
                    val o = y * w
                    for (x in 0 until w) {
                        val v = e.cells[o + x].toInt()
                        if (v == 0) continue
                        if (v == ZidPieces.RUBBLE) rubble(x * cell, top, cell, gap, corner)
                        else brick(x * cell, top, cell, ZidKindColors[v], gap, corner, bevel)
                    }
                    if (flash) {
                        val p = if (clearDelayMs > 0) (e.clearMsLeft.toFloat() / clearDelayMs).coerceIn(0f, 1f) else 0f
                        drawRect(EmberHot.copy(alpha = 0.25f + 0.6f * p), topLeft = Offset(0f, top), size = Size(size.width, cell))
                    }
                }
                // piesa curentă și locul unde aterizează (contur punctat)
                if (e.hasPiece && e.phase == ZidPhase.Falling) {
                    val c = ZidPieces.cells[e.pieceKind][e.pieceRot]
                    if (e.landingY > e.pieceY) {
                        val ghostColor = Accent2.copy(alpha = 0.45f)
                        for (i in 0 until 4) {
                            val cx = e.pieceX + c[2 * i]
                            val cy = e.landingY + c[2 * i + 1]
                            if (cy < hidden) continue
                            val l = cx * cell + gap
                            val t = (cy - hidden) * cell + gap
                            val r = l + cell - 2 * gap
                            val b = t + cell - 2 * gap
                            // doar marginile care nu se lipesc de altă celulă a piesei: un singur contur
                            var up = false
                            var down = false
                            var left = false
                            var right = false
                            for (j in 0 until 4) {
                                val ox = c[2 * j] - c[2 * i]
                                val oy = c[2 * j + 1] - c[2 * i + 1]
                                if (ox == 0 && oy == -1) up = true
                                if (ox == 0 && oy == 1) down = true
                                if (ox == -1 && oy == 0) left = true
                                if (ox == 1 && oy == 0) right = true
                            }
                            if (!up) drawLine(ghostColor, Offset(l, t), Offset(r, t), dashW, pathEffect = dash)
                            if (!down) drawLine(ghostColor, Offset(l, b), Offset(r, b), dashW, pathEffect = dash)
                            if (!left) drawLine(ghostColor, Offset(l, t), Offset(l, b), dashW, pathEffect = dash)
                            if (!right) drawLine(ghostColor, Offset(r, t), Offset(r, b), dashW, pathEffect = dash)
                        }
                    }
                    val color = ZidKindColors[e.pieceKind]
                    for (i in 0 until 4) {
                        val cx = e.pieceX + c[2 * i]
                        val cy = e.pieceY + c[2 * i + 1]
                        if (cy < hidden) continue
                        brick(cx * cell, (cy - hidden) * cell, cell, color, gap, corner, bevel)
                    }
                }
                // scânteile rândurilor închise
                for (i in fx.life.indices) {
                    val l = fx.life[i]
                    if (l <= 0f) continue
                    val a = (l / fx.maxLife[i]).coerceIn(0f, 1f)
                    val center = Offset(fx.x[i] * cell, fx.y[i] * cell)
                    drawCircle(EmberHot.copy(alpha = 0.25f * a), sparkR * 3f, center)
                    drawCircle(EmberHot.copy(alpha = a), sparkR, center)
                }
                drawRoundRect(W09, cornerRadius = boardCorner, style = border)
            }
        }
    )
}

/** O piesă mică (URM. / REZ.), centrată în cutia ei; 0 = nimic. */
@Composable
internal fun PiecePreview(kind: Int, cell: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Box(
        modifier.drawWithCache {
            val c = cell.toPx()
            val gap = 0.6.dp.toPx()
            val corner = CornerRadius(1.5.dp.toPx())
            val bevel = max(1f, 1.dp.toPx())
            val b = IntArray(4)
            if (kind in 1..ZidPieces.KINDS) ZidPieces.bounds(kind, 0, b)
            val cells = if (kind in 1..ZidPieces.KINDS) ZidPieces.cells[kind][0] else null
            val pw = (b[1] - b[0] + 1) * c
            val ph = (b[3] - b[2] + 1) * c
            val ox = (size.width - pw) / 2f - b[0] * c
            val oy = (size.height - ph) / 2f - b[2] * c
            onDrawBehind {
                if (cells == null) return@onDrawBehind
                val color = ZidKindColors[kind]
                for (i in 0 until 4) brick(ox + cells[2 * i] * c, oy + cells[2 * i + 1] * c, c, color, gap, corner, bevel, alpha)
            }
        }
    )
}

/** Mărimea unei celule (dp) pentru spațiul dat, după formula din games.md §4.11 (S23: 25 dp → tabla 250 × 450). */
internal fun zidCellDp(widthDp: Float, heightDp: Float): Int {
    val byW = (widthDp - 32f - 64f - 10f) / ZidEngine.W
    val byH = (heightDp - 12f - 44f - 10f - 16f - 120f - 12f) / ZidEngine.VISIBLE
    return kotlin.math.floor(minOf(byW, byH)).toInt().coerceIn(12, 40)
}
