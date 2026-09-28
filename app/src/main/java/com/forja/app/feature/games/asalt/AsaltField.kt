package com.forja.app.feature.games.asalt

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.EmberDeep
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.games.Rng
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltEngine.Companion.AMMO
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CAP_MULTI
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CAP_SLOW
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CAP_WIDE
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CONCRETE
import com.forja.app.core.games.asalt.AsaltEngine.Companion.CRATE
import com.forja.app.core.games.asalt.AsaltEngine.Companion.MULTI
import com.forja.app.core.games.asalt.AsaltEngine.Companion.SAND
import com.forja.app.core.games.asalt.AsaltEngine.Companion.SLOW
import com.forja.app.core.games.asalt.AsaltEngine.Companion.STEEL
import com.forja.app.core.games.asalt.AsaltEngine.Companion.WIDE
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.feature.games.ASALT_INSET_DP
import com.forja.app.feature.games.AnvilBody
import com.forja.app.feature.games.AnvilFace
import com.forja.app.feature.games.AnvilFaceShade
import com.forja.app.feature.games.AnvilFoot
import com.forja.app.feature.games.AsaltBrickColors
import com.forja.app.feature.games.CratePlank
import com.forja.app.feature.games.Rivet
import com.forja.app.feature.games.Sage
import com.forja.app.feature.inventory.Amber
import com.forja.app.feature.inventory.W06
import com.forja.app.feature.inventory.W09
import kotlin.math.PI
import kotlin.math.sin

/**
 * Efectele ASALT, doar vizuale (oprite sub mișcare redusă): dâra scânteilor (6 cercuri), inelele exploziilor,
 * așchiile cărămizilor, tremurul terenului, pâlpâirea roșie la o viață pierdută, pulsul ultimelor cărămizi.
 * Bazine fixe, fără alocări; coordonatele sunt unități de teren.
 */
internal class AsaltFx {
    val trailX = FloatArray(AsaltEngine.MAX_BALLS * TRAIL)
    val trailY = FloatArray(AsaltEngine.MAX_BALLS * TRAIL)
    var trailCount = 0
        private set
    private var trailHead = 0

    val ringX = FloatArray(RINGS)
    val ringY = FloatArray(RINGS)
    val ringMs = FloatArray(RINGS)          // ms rămase; ≤ 0 = liber

    val chipX = FloatArray(CHIPS)
    val chipY = FloatArray(CHIPS)
    private val chipVx = FloatArray(CHIPS)
    private val chipVy = FloatArray(CHIPS)
    val chipMs = FloatArray(CHIPS)
    val chipColor = IntArray(CHIPS)         // felul cărămizii (pentru culoare)

    var shakeMs = 0f
        private set
    var hurtMs = 0f
        private set
    /** Fața nicovalei se înroșește o clipă la fiecare lovitură. */
    var anvilHotMs = 0f
        private set
    var clockMs = 0L
        private set
    private val rng = Rng(0xA5A17)

    val active: Boolean
        get() {
            if (shakeMs > 0f || hurtMs > 0f || anvilHotMs > 0f) return true
            for (i in 0 until RINGS) if (ringMs[i] > 0f) return true
            for (i in 0 until CHIPS) if (chipMs[i] > 0f) return true
            return false
        }

    /** Dâra: pozițiile scânteilor din ultimele cadre. */
    fun recordTrail(e: AsaltEngine) {
        trailHead = (trailHead + 1) % TRAIL
        for (b in 0 until AsaltEngine.MAX_BALLS) {
            val i = b * TRAIL + trailHead
            if (b < e.ballCount && e.phase == AsaltPhase.Playing) {
                trailX[i] = e.ballX[b].toFloat()
                trailY[i] = e.ballY[b].toFloat()
            } else {
                trailX[i] = Float.NaN
                trailY[i] = Float.NaN
            }
        }
        if (trailCount < TRAIL) trailCount++
    }

    /** Poziția k (0 = cea mai nouă) din dâra scânteii b; NaN = nimic. */
    fun trail(b: Int, k: Int, out: FloatArray) {
        val idx = b * TRAIL + ((trailHead - k + TRAIL) % TRAIL)
        out[0] = trailX[idx]
        out[1] = trailY[idx]
    }

    fun clearTrail() {
        trailCount = 0
        for (i in trailX.indices) {
            trailX[i] = Float.NaN
            trailY[i] = Float.NaN
        }
    }

    fun explosion(x: Float, y: Float) {
        for (i in 0 until RINGS) if (ringMs[i] <= 0f) {
            ringX[i] = x; ringY[i] = y; ringMs[i] = RING_MS
            break
        }
        shakeMs = SHAKE_MS
        repeat(8) { chip(x, y, AMMO, 1.6f) }
    }

    fun broken(x: Float, y: Float, kind: Int) {
        repeat(4) { chip(x, y, kind, 1f) }
    }

    fun hurt() {
        hurtMs = HURT_MS
        shakeMs = SHAKE_MS
    }

    fun anvilHit() {
        anvilHotMs = ANVIL_HOT_MS
    }

    private fun chip(x: Float, y: Float, kind: Int, power: Float) {
        for (i in 0 until CHIPS) if (chipMs[i] <= 0f) {
            chipX[i] = x
            chipY[i] = y
            chipVx[i] = (rng.nextFloat() * 2f - 1f) * 120f * power
            chipVy[i] = (-rng.nextFloat() * 110f - 20f) * power
            chipMs[i] = 380f + rng.nextFloat() * 220f
            chipColor[i] = kind
            return
        }
    }

    fun step(dtMs: Int) {
        clockMs += dtMs
        val dt = dtMs / 1000f
        if (shakeMs > 0f) shakeMs = (shakeMs - dtMs).coerceAtLeast(0f)
        if (hurtMs > 0f) hurtMs = (hurtMs - dtMs).coerceAtLeast(0f)
        if (anvilHotMs > 0f) anvilHotMs = (anvilHotMs - dtMs).coerceAtLeast(0f)
        for (i in 0 until RINGS) if (ringMs[i] > 0f) ringMs[i] -= dtMs
        for (i in 0 until CHIPS) {
            if (chipMs[i] <= 0f) continue
            chipMs[i] -= dtMs
            chipVy[i] += 520f * dt
            chipX[i] += chipVx[i] * dt
            chipY[i] += chipVy[i] * dt
        }
    }

    fun clear() {
        shakeMs = 0f
        hurtMs = 0f
        anvilHotMs = 0f
        for (i in 0 until RINGS) ringMs[i] = 0f
        for (i in 0 until CHIPS) chipMs[i] = 0f
        clearTrail()
    }

    companion object {
        const val TRAIL = 6
        const val RINGS = 6
        const val CHIPS = 48
        const val RING_MS = 320f
        const val SHAKE_MS = 180f
        const val HURT_MS = 420f
        const val ANVIL_HOT_MS = 180f
    }
}

/** Crăpăturile betonului și ale sacului (în unități de cărămidă, 30 × 14), desenate peste culoare. */
private fun crack(u: Float, stage: Int): Path = Path().apply {
    if (stage == 1) {
        moveTo(9f * u, 0f); lineTo(12f * u, 5f * u); lineTo(10f * u, 8f * u); lineTo(14f * u, 14f * u)
    } else {
        moveTo(21f * u, 14f * u); lineTo(19f * u, 9f * u); lineTo(23f * u, 6f * u); lineTo(21f * u, 0f)
        moveTo(12f * u, 5f * u); lineTo(17f * u, 7f * u)
    }
}

private fun arrowGlyph(u: Float): Path = Path().apply {
    // ↔ (Lat)
    moveTo(9f * u, 7f * u); lineTo(21f * u, 7f * u)
    moveTo(11.5f * u, 4.5f * u); lineTo(9f * u, 7f * u); lineTo(11.5f * u, 9.5f * u)
    moveTo(18.5f * u, 4.5f * u); lineTo(21f * u, 7f * u); lineTo(18.5f * u, 9.5f * u)
}

/** Înălțimea desenată a nicovalei (unități): fața, gâtul și talpa; coliziunea rămâne fața de la y = 552. */
private const val ANVIL_DRAW_H = 20f

/** Cornul nicovalei, spre stânga, la nivelul feței (înălțimea feței = `face`). */
internal fun anvilHorn(length: Float, face: Float): Path = Path().apply {
    moveTo(0f, 0f)
    quadraticTo(-length * 0.55f, face * 0.05f, -length, face * 0.32f)
    quadraticTo(-length * 0.5f, face * 0.62f, 0f, face)
    close()
}

/**
 * Nicovala (fața de oțel cu lumină sus, cornul, gâtul îngust, talpa lată), centrată pe `cx`, cu fața la `top`.
 * `hot` (0..1) = fața înroșită după o lovitură. Culori și căi primite gata făcute: nimic alocat.
 */
internal fun DrawScope.drawAnvil(cx: Float, top: Float, halfW: Float, h: Float, horn: Path, hot: Float) {
    val face = h * 0.38f
    val waistTop = top + face
    val footTop = top + h * 0.72f
    translate(cx - halfW, top) { drawPath(horn, AnvilFace) }
    drawRoundRect(AnvilFaceShade, topLeft = Offset(cx - halfW, top), size = Size(2 * halfW, face), cornerRadius = CornerRadius(face * 0.25f))
    drawRoundRect(AnvilFace, topLeft = Offset(cx - halfW, top), size = Size(2 * halfW, face * 0.62f), cornerRadius = CornerRadius(face * 0.25f))
    drawRect(Color.White.copy(alpha = 0.38f), topLeft = Offset(cx - halfW + face * 0.4f, top), size = Size(2 * halfW - face * 0.8f, maxOf(1f, face * 0.12f)))
    if (hot > 0f) drawRoundRect(EmberHot.copy(alpha = 0.75f * hot), topLeft = Offset(cx - halfW, top), size = Size(2 * halfW, face * 0.5f), cornerRadius = CornerRadius(face * 0.25f))
    drawRect(AnvilBody, topLeft = Offset(cx - halfW * 0.36f, waistTop), size = Size(halfW * 0.72f, footTop - waistTop))
    drawRoundRect(AnvilFoot, topLeft = Offset(cx - halfW * 0.64f, footTop), size = Size(halfW * 1.28f, top + h - footTop), cornerRadius = CornerRadius(face * 0.2f))
    drawRect(Color.Black.copy(alpha = 0.3f), topLeft = Offset(cx - halfW * 0.36f, waistTop), size = Size(halfW * 0.72f, maxOf(1f, face * 0.14f)))
}

/**
 * Terenul ASALT (390 × 600 unități, scalat la lățime): zidul, capsulele, nicovala, scânteile cu dâră și strălucire,
 * exploziile. Cardul (fundalul, conturul) ocupă tot; lumea jocului stă înăuntru, la [ASALT_INSET_DP] de contur.
 * Redesenat în faza de desen la fiecare cadru nou (`frame`), fără recompoziție și fără alocări.
 */
@Composable
internal fun AsaltField(engine: AsaltEngine, fx: AsaltFx, frame: State<Long>, reduced: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val inset = ASALT_INSET_DP.dp.toPx()
            val u = (size.width - 2f * inset) / AsaltEngine.W.toFloat()
            val bw = AsaltEngine.BW.toFloat() * u
            val bh = AsaltEngine.BH.toFloat() * u
            val top0 = AsaltEngine.TOP.toFloat() * u
            val gap = 1f * u
            val fieldCorner = CornerRadius(8.dp.toPx())
            val brickCorner = CornerRadius(1.5f * u)
            val sandCorner = CornerRadius(4.5f * u)
            val border = Stroke(1.dp.toPx())
            val crackStroke = Stroke(1.2f * u, cap = StrokeCap.Round)
            val glyphStroke = Stroke(1.6f * u, cap = StrokeCap.Round)
            val ringStroke = Stroke(2.2f * u)
            val crack1 = crack(u, 1)
            val crack2 = crack(u, 2)
            val arrow = arrowGlyph(u)
            // la desen nicovala e mai înaltă decât cutia de coliziune (doar fața ei lovește scânteia)
            val anvilH = ANVIL_DRAW_H * u
            val horn = anvilHorn(14f * u, anvilH * 0.38f)
            val forge = Brush.radialGradient(
                listOf(EmberDeep.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 1.02f), radius = size.width * 0.8f
            )
            val r = AsaltEngine.R.toFloat() * u
            val glow = Brush.radialGradient(
                listOf(EmberHot.copy(alpha = 0.55f), EmberWarm.copy(alpha = 0.18f), Color.Transparent),
                center = Offset.Zero, radius = r * 3.2f
            )
            val pos = FloatArray(2)
            val paddleY = AsaltEngine.PADDLE_Y.toFloat() * u
            val paddleH = AsaltEngine.PADDLE_H.toFloat() * u
            val guideW = 1.dp.toPx()
            onDrawBehind {
                frame.value // redesenare la fiecare cadru nou (fără recompoziție)
                val e = engine
                val shake = if (!reduced && fx.shakeMs > 0f) {
                    val k = fx.shakeMs / AsaltFx.SHAKE_MS
                    3.dp.toPx() * k * sin(fx.clockMs * 0.09f)
                } else 0f
                drawRoundRect(Surface1, cornerRadius = fieldCorner)
                drawRect(forge)
                // linia de pericol sub nicovală
                val dangerY = inset + paddleY + anvilH + 5f * u
                drawLine(W06, Offset(0f, dangerY), Offset(size.width, dangerY), guideW)
                translate(inset + shake, inset) {
                    // ── zidul ──
                    val pulse = if (e.assist && !reduced) 0.55f + 0.45f * (0.5f + 0.5f * sin(fx.clockMs / 1000f * 2f * PI.toFloat() * 1.4f)) else 1f
                    for (row in 0 until AsaltEngine.ROWS) {
                        for (col in 0 until AsaltEngine.COLS) {
                            val k = e.kind(col, row)
                            if (k == 0) continue
                            val l = col * bw + gap
                            val t = top0 + row * bh + gap
                            val w = bw - 2 * gap
                            val h = bh - 2 * gap
                            val a = if (k == STEEL) 1f else pulse
                            drawBrick(k, e.hpAt(col, row), l, t, w, h, u, a, brickCorner, sandCorner, crack1, crack2, crackStroke, arrow, glyphStroke)
                        }
                    }
                    // ── capsulele ──
                    for (j in 0 until AsaltEngine.MAX_CAPS) {
                        val type = e.capType[j]
                        if (type == 0) continue
                        val cx = e.capX[j].toFloat() * u
                        val cy = e.capY[j].toFloat() * u
                        val cw = AsaltEngine.CAP_W.toFloat() * u
                        val ch = AsaltEngine.CAP_H.toFloat() * u
                        drawRoundRect(Sage, topLeft = Offset(cx - cw / 2, cy - ch / 2), size = Size(cw, ch), cornerRadius = CornerRadius(ch / 2))
                        drawRoundRect(EmberHot, topLeft = Offset(cx - cw / 2, cy - ch / 2), size = Size(cw, ch), cornerRadius = CornerRadius(ch / 2), style = glyphStroke)
                        translate(cx - 15f * u, cy - 7f * u) {
                            capsuleGlyph(type, u, arrow, glyphStroke)
                        }
                    }
                    // ── nicovala ──
                    val hw = e.halfWidth.toFloat() * u
                    val px = e.paddleX.toFloat() * u
                    if (e.wideSteps > 0) {
                        drawRoundRect(Accent2.copy(alpha = 0.22f), topLeft = Offset(px - hw - 3f * u, paddleY - 3f * u), size = Size(2 * hw + 6f * u, anvilH * 0.5f + 6f * u), cornerRadius = CornerRadius(4f * u))
                    }
                    val hot = if (!reduced) fx.anvilHotMs / AsaltFx.ANVIL_HOT_MS else 0f
                    drawAnvil(px, paddleY, hw, anvilH, horn, hot)
                    // ── scânteile ──
                    val playing = e.phase == AsaltPhase.Playing || e.phase == AsaltPhase.Ready
                    if (playing) {
                        if (!reduced) {
                            for (b in 0 until e.ballCount) {
                                for (k in 1 until minOf(AsaltFx.TRAIL, fx.trailCount)) {
                                    fx.trail(b, k, pos)
                                    if (pos[0].isNaN()) continue
                                    val a = 0.34f * (1f - k / AsaltFx.TRAIL.toFloat())
                                    drawCircle(EmberWarm.copy(alpha = a), r * (1f - k * 0.1f), Offset(pos[0] * u, pos[1] * u))
                                }
                            }
                        }
                        for (b in 0 until e.ballCount) {
                            val bx = e.ballX[b].toFloat() * u
                            val by = e.ballY[b].toFloat() * u
                            translate(bx, by) { drawCircle(glow, r * 3.2f, Offset.Zero) }
                            drawCircle(EmberHot, r, Offset(bx, by))
                            drawCircle(Color.White.copy(alpha = 0.55f), r * 0.45f, Offset(bx - r * 0.25f, by - r * 0.25f))
                        }
                    }
                    // ── inelele și așchiile ──
                    for (i in 0 until AsaltFx.RINGS) {
                        val left = fx.ringMs[i]
                        if (left <= 0f) continue
                        val p = 1f - left / AsaltFx.RING_MS
                        drawCircle(Amber.copy(alpha = 0.8f * (1f - p)), (8f + 46f * p) * u, Offset(fx.ringX[i] * u, fx.ringY[i] * u), style = ringStroke)
                        drawCircle(EmberHot.copy(alpha = 0.25f * (1f - p)), (6f + 30f * p) * u, Offset(fx.ringX[i] * u, fx.ringY[i] * u))
                    }
                    for (i in 0 until AsaltFx.CHIPS) {
                        val left = fx.chipMs[i]
                        if (left <= 0f) continue
                        val a = (left / 400f).coerceIn(0f, 1f)
                        val c = if (fx.chipColor[i] == AMMO) EmberHot else AsaltBrickColors[fx.chipColor[i]]
                        drawRect(c.copy(alpha = a), topLeft = Offset(fx.chipX[i] * u - 1.5f * u, fx.chipY[i] * u - 1.5f * u), size = Size(3f * u, 3f * u))
                    }
                }
                if (!reduced && fx.hurtMs > 0f) drawRoundRect(Error.copy(alpha = 0.16f * fx.hurtMs / AsaltFx.HURT_MS), cornerRadius = fieldCorner)
                drawRoundRect(W09, cornerRadius = fieldCorner, style = border)
            }
        }
    )
}

private fun DrawScope.drawBrick(
    k: Int, hp: Int, l: Float, t: Float, w: Float, h: Float, u: Float, alpha: Float,
    corner: CornerRadius, sandCorner: CornerRadius, crack1: Path, crack2: Path, crackStroke: Stroke, arrow: Path, glyphStroke: Stroke
) {
    val color = AsaltBrickColors[k]
    when (k) {
        CRATE -> {
            drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = corner, alpha = alpha)
            drawRect(CratePlank, topLeft = Offset(l, t + h * 0.33f), size = Size(w, 1f * u), alpha = alpha)
            drawRect(CratePlank, topLeft = Offset(l, t + h * 0.66f), size = Size(w, 1f * u), alpha = alpha)
            drawRect(CratePlank, topLeft = Offset(l + 2f * u, t), size = Size(1.2f * u, h), alpha = alpha)
            drawRect(CratePlank, topLeft = Offset(l + w - 3.2f * u, t), size = Size(1.2f * u, h), alpha = alpha)
        }
        SAND -> {
            drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = sandCorner, alpha = alpha)
            drawRect(Color.White.copy(alpha = 0.22f * alpha), topLeft = Offset(l + 4f * u, t + 1f * u), size = Size(w - 8f * u, 1f * u))
            if (hp <= 1) translate(l - 1f * u, t - 1f * u) { drawPath(crack1, Color.Black.copy(alpha = 0.4f * alpha), style = crackStroke) }
        }
        CONCRETE -> {
            drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = corner, alpha = alpha)
            drawRect(Color.Black.copy(alpha = 0.18f * alpha), topLeft = Offset(l, t + h - 1.5f * u), size = Size(w, 1.5f * u))
            if (hp <= 2) translate(l - 1f * u, t - 1f * u) { drawPath(crack1, Surface0.copy(alpha = 0.6f * alpha), style = crackStroke) }
            if (hp <= 1) translate(l - 1f * u, t - 1f * u) { drawPath(crack2, Surface0.copy(alpha = 0.6f * alpha), style = crackStroke) }
        }
        STEEL -> {
            drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = corner, alpha = alpha)
            val rr = 1.3f * u
            drawCircle(Rivet, rr, Offset(l + 3.5f * u, t + 3.2f * u), alpha = alpha)
            drawCircle(Rivet, rr, Offset(l + w - 3.5f * u, t + 3.2f * u), alpha = alpha)
            drawCircle(Rivet, rr, Offset(l + 3.5f * u, t + h - 3.2f * u), alpha = alpha)
            drawCircle(Rivet, rr, Offset(l + w - 3.5f * u, t + h - 3.2f * u), alpha = alpha)
            drawRect(Color.White.copy(alpha = 0.10f * alpha), topLeft = Offset(l, t), size = Size(w, 1f * u))
        }
        AMMO -> {
            drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = corner, alpha = alpha)
            val ink = Color.Black.copy(alpha = 0.5f * alpha)
            drawLine(ink, Offset(l + 7f * u, t + h), Offset(l + 12f * u, t), 2.6f * u)
            drawLine(ink, Offset(l + 14f * u, t + h), Offset(l + 19f * u, t), 2.6f * u)
            drawLine(ink, Offset(l + 21f * u, t + h), Offset(l + 26f * u, t), 2.6f * u)
        }
        WIDE, MULTI, SLOW -> {
            drawRoundRect(color, topLeft = Offset(l, t), size = Size(w, h), cornerRadius = corner, alpha = alpha)
            translate(l - 1f * u, t - 1f * u) {
                capsuleGlyph(if (k == WIDE) CAP_WIDE else if (k == MULTI) CAP_MULTI else CAP_SLOW, u, arrow, glyphStroke, Surface0.copy(alpha = 0.75f * alpha))
            }
        }
    }
}

/** Semnul unei capsule / cărămizi speciale, într-o cutie de 30 × 14 unități: ↔ Lat, ••• Schije, ◷ Calm. */
private fun DrawScope.capsuleGlyph(type: Int, u: Float, arrow: Path, stroke: Stroke, color: Color = Surface0.copy(alpha = 0.8f)) {
    when (type) {
        CAP_WIDE -> drawPath(arrow, color, style = stroke)
        CAP_MULTI -> {
            drawCircle(color, 1.6f * u, Offset(10f * u, 7f * u))
            drawCircle(color, 1.6f * u, Offset(15f * u, 7f * u))
            drawCircle(color, 1.6f * u, Offset(20f * u, 7f * u))
        }
        CAP_SLOW -> {
            drawCircle(color, 4.2f * u, Offset(15f * u, 7f * u), style = stroke)
            drawLine(color, Offset(15f * u, 7f * u), Offset(15f * u, 4.2f * u), stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(15f * u, 7f * u), Offset(17f * u, 7.8f * u), stroke.width, cap = StrokeCap.Round)
        }
    }
}
