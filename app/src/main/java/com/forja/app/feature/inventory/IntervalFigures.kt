package com.forja.app.feature.inventory

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Exercițiile din „Cât aștepți · Sport”. */
enum class Move(val label: String) {
    Jacks("Sărituri stea"),
    Pushup("Flotări"),
    Squat("Genuflexiuni"),
    Box("Box în aer"),
    Plank("Planșă"),
    Stretch("Întinderi"),
    Lunge("Fandări"),
    Breath("Respirație")
}

// ═════════════════════════════ Figura animată (Interval.dc.html) ═════════════════════════════
// Spațiu 100×100; liniile au 6 dp (vector-effect: non-scaling-stroke în prototip), solul 3 dp; capetele se scalează.
// Ritmurile și unghiurile sunt cele din CSS: sărituri .9 s (brațe ±22°→±160°, picioare ±5°→±24°, salt −4),
// flotări 1,6 s (corp −13° în jurul picioarelor, braț scaleY .36), genuflexiuni 1,8 s (gleznă +30°, genunchi −104°,
// șold +111°, umăr −120°, înlănțuite), box 1,2 s (directă scaleX 1,9 la 18 %, croșeu 2,2 la 68 %; balans 2 în .3 s),
// planșă .45 s (tremur .9). Întinderile, fandările și respirația (fără figură în prototip) urmează același stil.

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Valoarea unei animații cu cadre-cheie (fracțiune → valoare), ease-in-out pe fiecare segment, ca în CSS. */
private fun keyframes(p: Float, vararg frames: Pair<Float, Float>): Float {
    val t = ((p % 1f) + 1f) % 1f
    for (i in 0 until frames.size - 1) {
        val (a, va) = frames[i]
        val (b, vb) = frames[i + 1]
        if (t in a..b) {
            val f = if (b > a) EaseInOut.transform((t - a) / (b - a)) else 1f
            return va + (vb - va) * f
        }
    }
    return frames.last().second
}

/** `alternate` (dus-întors) cu ease-in-out: 0 → 1 → 0 în 2 × durată. */
private fun alternate(seconds: Float, duration: Float): Float {
    val cycle = (seconds / duration)
    val i = cycle.toInt()
    val f = EaseInOut.transform(cycle - i)
    return if (i % 2 == 0) f else 1f - f
}

private fun rot(p: Offset, pivot: Offset, deg: Float): Offset {
    val r = deg * (PI / 180.0).toFloat()
    val c = cos(r)
    val s = sin(r)
    val dx = p.x - pivot.x
    val dy = p.y - pivot.y
    return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
}

private class FigurePen(
    val scope: DrawScope, val unit: Float, val ox: Float, val oy: Float, val limb: Float, val ground: Float, val alpha: Float
) {
    fun o(p: Offset) = Offset(ox + p.x * unit, oy + p.y * unit)
    fun line(a: Offset, b: Offset, color: Color = TextPrimary) =
        scope.drawLine(color, o(a), o(b), strokeWidth = limb, cap = StrokeCap.Round, alpha = alpha)
    fun floor(a: Offset, b: Offset) = scope.drawLine(Rule, o(a), o(b), strokeWidth = ground, cap = StrokeCap.Round, alpha = alpha)
    fun head(c: Offset, r: Float) = scope.drawCircle(TextPrimary, r * unit, o(c), alpha = alpha)
}

/**
 * Figura exercițiului, animată după `seconds` (ceasul figurii: stă pe loc când antrenamentul e pe pauză).
 * `dim` = previzualizare (pauza dintre exerciții): figura stă nemișcată, stinsă.
 */
@Composable
internal fun ExerciseFigure(move: Move, seconds: () -> Float, modifier: Modifier = Modifier, dim: Boolean = false) {
    Canvas(modifier) {
        val side = size.minDimension
        val unit = side / 100f
        val pen = FigurePen(this, unit, (size.width - side) / 2f, (size.height - side) / 2f, 6.dp.toPx(), 3.dp.toPx(), if (dim) 0.45f else 1f)
        val t = if (dim) 0f else seconds()
        when (move) {
            Move.Jacks -> jacks(pen, t)
            Move.Pushup -> pushup(pen, t)
            Move.Squat -> squat(pen, t)
            Move.Box -> boxing(pen, t)
            Move.Plank -> plank(pen, t)
            Move.Stretch -> stretch(pen, t)
            Move.Lunge -> lunge(pen, t)
            Move.Breath -> breath(pen, t)
        }
    }
}

private fun jacks(g: FigurePen, s: Float) {
    val p = s / 0.9f
    val hop = keyframes(p, 0f to 0f, 0.25f to -4f, 0.75f to -4f, 1f to 0f)
    val arm = keyframes(p, 0f to 22f, 0.5f to 160f, 1f to 22f)
    val leg = keyframes(p, 0f to 5f, 0.5f to 24f, 1f to 5f)
    g.floor(Offset(14f, 93f), Offset(86f, 93f))
    fun y(v: Float) = v + hop
    val sh = Offset(50f, y(34f))
    val hip = Offset(50f, y(58f))
    g.line(Offset(50f, y(30f)), hip)
    g.line(sh, rot(Offset(50f, y(54f)), sh, arm))
    g.line(sh, rot(Offset(50f, y(54f)), sh, -arm))
    g.line(hip, rot(Offset(50f, y(88f)), hip, leg))
    g.line(hip, rot(Offset(50f, y(88f)), hip, -leg))
    g.head(Offset(50f, y(20f)), 7.5f)
}

private fun pushup(g: FigurePen, s: Float) {
    val p = s / 1.6f
    val body = keyframes(p, 0f to 0f, 0.5f to -13f, 1f to 0f)
    val arm = keyframes(p, 0f to 1f, 0.5f to 0.36f, 1f to 1f)
    g.floor(Offset(8f, 86f), Offset(94f, 86f))
    g.line(Offset(28f, 82f), Offset(28f, 82f + (61f - 82f) * arm))
    val feet = Offset(86f, 80f)
    g.line(rot(Offset(24f, 60f), feet, body), rot(Offset(86f, 80f), feet, body))
    g.head(rot(Offset(17f, 56f), feet, body), 7f)
}

private fun squat(g: FigurePen, s: Float) {
    val p = s / 1.8f
    val a1 = keyframes(p, 0f to 0f, 0.5f to 30f, 1f to 0f)
    val a2 = keyframes(p, 0f to 0f, 0.5f to -104f, 1f to 0f)
    val a3 = keyframes(p, 0f to 0f, 0.5f to 111f, 1f to 0f)
    val a4 = keyframes(p, 0f to 0f, 0.5f to -120f, 1f to 0f)
    val ankle = Offset(54f, 86f)
    val knee = Offset(54f, 70f)
    val hipP = Offset(52f, 55f)
    val shoulder = Offset(52f, 34f)
    fun shin(q: Offset) = rot(q, ankle, a1)
    fun thigh(q: Offset) = shin(rot(q, knee, a2))
    fun torso(q: Offset) = thigh(rot(q, hipP, a3))
    fun arm(q: Offset) = torso(rot(q, shoulder, a4))
    g.floor(Offset(14f, 92f), Offset(86f, 92f))
    g.line(Offset(50f, 88f), Offset(62f, 88f))
    g.line(shin(Offset(54f, 86f)), shin(Offset(54f, 70f)))
    g.line(thigh(Offset(54f, 70f)), thigh(Offset(52f, 55f)))
    g.line(torso(Offset(52f, 55f)), torso(Offset(52f, 31f)))
    g.head(torso(Offset(52f, 22f)), 7f)
    g.line(arm(Offset(52f, 34f)), arm(Offset(54f, 50f)))
}

private fun boxing(g: FigurePen, s: Float) {
    val p = s / 1.2f
    val bounce = -2f * alternate(s, 0.3f)
    val jab = keyframes(p, 0f to 1f, 0.18f to 1.9f, 0.4f to 1f, 1f to 1f)
    val cross = keyframes(p, 0f to 1f, 0.5f to 1f, 0.68f to 2.2f, 0.9f to 1f, 1f to 1f)
    g.floor(Offset(14f, 92f), Offset(86f, 92f))
    fun b(x: Float, y: Float) = Offset(x, y + bounce)
    g.line(b(46f, 30f), b(48f, 58f))
    g.line(b(48f, 58f), b(40f, 88f))
    g.line(b(48f, 58f), b(60f, 88f))
    // croșeul (spate, gri), apoi directa (față)
    g.line(b(47f, 38f), b(47f + (58f - 47f) * cross, 42f), TextSecondary)
    g.line(b(47f, 36f), b(47f + (62f - 47f) * jab, 36f))
    g.head(b(46f, 22f), 7f)
}

private fun plank(g: FigurePen, s: Float) {
    val d = 0.9f * alternate(s, 0.45f)
    g.floor(Offset(8f, 84f), Offset(94f, 84f))
    fun b(x: Float, y: Float) = Offset(x, y + d)
    g.line(b(23f, 64f), b(86f, 76f))
    g.line(b(26f, 66f), b(26f, 80f))
    g.line(b(26f, 80f), b(38f, 80f))
    g.line(b(86f, 76f), b(88f, 80f))
    g.head(b(16f, 60f), 7f)
}

private fun stretch(g: FigurePen, s: Float) {
    val bend = keyframes(s / 3.2f, 0f to -12f, 0.5f to 12f, 1f to -12f)
    val hip = Offset(50f, 58f)
    g.floor(Offset(14f, 93f), Offset(86f, 93f))
    g.line(hip, Offset(43f, 88f))
    g.line(hip, Offset(57f, 88f))
    fun t(q: Offset) = rot(q, hip, bend)
    g.line(t(hip), t(Offset(50f, 30f)))
    g.line(t(Offset(50f, 34f)), t(Offset(43f, 13f)))
    g.line(t(Offset(50f, 34f)), t(Offset(57f, 13f)))
    g.head(t(Offset(50f, 20f)), 7.5f)
}

private fun lunge(g: FigurePen, s: Float) {
    val d = keyframes(s / 1.8f, 0f to 0f, 0.5f to 11f, 1f to 0f)
    g.floor(Offset(14f, 92f), Offset(86f, 92f))
    val hip = Offset(48f, 58f + d)
    g.line(hip, Offset(60f, 72f + d * 0.35f))
    g.line(Offset(60f, 72f + d * 0.35f), Offset(64f, 88f))
    g.line(hip, Offset(38f, 74f + d * 0.75f))
    g.line(Offset(38f, 74f + d * 0.75f), Offset(28f, 88f))
    g.line(hip, Offset(48f, 30f + d))
    g.line(Offset(48f, 34f + d), Offset(57f, 49f + d))
    g.head(Offset(48f, 21f + d), 7.5f)
}

private fun breath(g: FigurePen, s: Float) {
    val arm = keyframes(s / 4f, 0f to 18f, 0.5f to 150f, 1f to 18f)
    val air = keyframes(s / 4f, 0f to 0f, 0.5f to 1f, 1f to 0f)
    g.floor(Offset(14f, 93f), Offset(86f, 93f))
    g.scope.drawCircle(Amber, (9f + 5f * air) * g.unit, g.o(Offset(50f, 44f)), alpha = (0.10f + 0.12f * air) * g.alpha)
    val sh = Offset(50f, 34f)
    val hip = Offset(50f, 58f)
    g.line(Offset(50f, 30f), hip)
    g.line(sh, rot(Offset(50f, 54f), sh, arm))
    g.line(sh, rot(Offset(50f, 54f), sh, -arm))
    g.line(hip, Offset(45f, 88f))
    g.line(hip, Offset(55f, 88f))
    g.head(Offset(50f, 20f), 7.5f)
}

// ═════════════════════════════ Pictogramele planului (Sport.dc.html) ═════════════════════════════

private class Picto(val hx: Float, val hy: Float, val d: String)

private val Pictos = mapOf(
    Move.Jacks to Picto(24f, 8f, "M24 13 L24 28 M24 16 L14 7 M24 16 L34 7 M24 28 L16 42 M24 28 L32 42"),
    Move.Pushup to Picto(9f, 22f, "M13 25 L42 34 M16 26 L16 37 M42 34 L44 37"),
    Move.Squat to Picto(30f, 12f, "M28 16 L20 29 M26 19 L38 21 M20 29 L32 31 L30 42"),
    Move.Plank to Picto(9f, 24f, "M13 27 L43 33 M14 28 L14 36 L22 36"),
    Move.Box to Picto(20f, 9f, "M20 14 L21 29 M20 17 L30 19 L40 17 M20 18 L27 24 L31 21 M21 29 L15 42 M21 29 L28 42"),
    Move.Breath to Picto(24f, 10f, "M24 15 L24 29 M24 19 L15 27 M24 19 L33 27 M11 35 Q24 28 37 35 M11 35 Q24 41 37 35"),
    Move.Stretch to Picto(21f, 9f, "M22 14 Q25 22 24 30 M22 16 Q28 8 34 4 M22 17 Q16 22 17 28 M24 30 L18 43 M24 30 L30 43"),
    Move.Lunge to Picto(22f, 8f, "M22 13 L22 27 M22 17 L28 22 M22 27 L33 29 L33 41 M22 27 L15 35 L9 40")
)

/** Pictograma unui exercițiu (viewBox 48, contur 3, capul r 3,6). */
@Composable
internal fun MovePictogram(move: Move, modifier: Modifier = Modifier, color: Color = TextPrimary) {
    val picto = Pictos.getValue(move)
    val raw = remember(move) { PathParser().parsePathString(picto.d).toPath() }
    Box(
        modifier.drawWithCache {
            val u = size.minDimension / 48f
            val path = Path().apply {
                addPath(raw)
                transform(Matrix().apply { scale(u, u) })
            }
            val stroke = Stroke(3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(path, color, style = stroke)
                drawCircle(color, 3.6f * u, Offset(picto.hx * u, picto.hy * u))
            }
        }
    )
}
