package com.forja.app.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Springs
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.monoLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/*
 * Mascota FORJA — „spiritul de jar”: un cărbune-flacără rotunjit, măsliniu, cu miez de jar amber,
 * contur gros, ochi uriași, gură expresivă, două brațe scurte și tălpi mici.
 * Desenată integral vectorial (Canvas/Path), într-un spațiu de 100×100 unități scalat la dimensiune;
 * conturul are 3 unități (3 % din dimensiune). Animată procedural; sub LocalReducedMotion e statică.
 * Fără alocări în desenare: căile sunt precalculate sau refolosite (reset), pensulele sunt create o dată.
 */

enum class MascotState { Idle, Thinking, Happy, Sorry, Talking, Reading, Wink, Angry }
enum class MascotHat { None, Chef, Helmet }

// ───────────────────────────── Culori proprii mascotei ─────────────────────────────

private val Ink = Surface0                          // contur
private val BodyOlive = Accent                      // corp
private val BodyOliveLight = Accent2                // reflex sus
private val Amber = Color(0xFFF3B952)               // jar
private val AmberLight = Color(0xFFFFE39A)
private val EyeWhite = Color(0xFFFCFBF7)
private val ToqueWhite = Color(0xFFF4F2EE)
private val ToqueShade = Color(0xFFD9D6CE)
private val HelmetOlive = Color(0xFF3B4A2F)
private val HelmetLight = Color(0xFF55673F)
private val SheetYellow = Color(0xFFF3D55B)
private val SheetLine = Color(0x662A2A10)
private val Blush = Color(0x59F3B952)

// ───────────────────────────── Poza (parametrii unui cadru) ─────────────────────────────

/** Toți parametrii care descriu un cadru. Câmpuri mutabile, refolosite cadru de cadru (fără alocări). */
private class Pose {
    var breath = 1f       // scala respirației
    var sway = 0f         // grade
    var tilt = 0f         // grade (Sorry)
    var hop = 0f          // unități în sus
    var squash = 0f       // 0..1 turtire la aterizare
    var blink = 0f        // 0 deschis .. 1 închis
    var lookX = 0f; var lookY = 0f
    var lidL = 0f; var lidR = 0f     // pleoape 0..1
    var arcL = 0f; var arcR = 0f     // ochi „fericit” (arc) 0..1
    var mouthOpen = 0.3f  // 0..1 (implicit: zâmbet ușor deschis, pentru avatarul static)
    var mouthSmile = 0.6f // -1 (trist) .. 1 (zâmbet larg)
    var armLx = 10f; var armLy = 76f
    var armRx = 90f; var armRy = 76f
    var dots = 0f         // punctele de gândire 0..1
    var sheet = 0f        // foaia 0..1
    var glow = 1f         // pulsul aurei 0..1
    var brow = 0f         // sprâncenele încruntate (Angry) 0..1
}

/** Țintele fiecărei stări (interpolate cu animateFloatAsState). */
private class Targets(
    val tilt: Float, val lookX: Float, val lookY: Float,
    val lidL: Float, val lidR: Float, val arcL: Float, val arcR: Float,
    val mouthOpen: Float, val mouthSmile: Float,
    val armLx: Float, val armLy: Float, val armRx: Float, val armRy: Float,
    val dots: Float, val sheet: Float,
    val brow: Float = 0f
)

private val RestArms = floatArrayOf(10f, 76f, 90f, 76f)

private fun targetsOf(state: MascotState): Targets = when (state) {
    MascotState.Idle -> Targets(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.12f, 0.6f, 10f, 76f, 90f, 76f, 0f, 0f)
    MascotState.Thinking -> Targets(2f, 3f, -3.2f, 0.18f, 0.18f, 0f, 0f, 0f, 0.15f, 8f, 78f, 72f, 27f, 1f, 0f)
    MascotState.Happy -> Targets(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0.75f, 1f, 6f, 62f, 94f, 62f, 0f, 0f)
    MascotState.Sorry -> Targets(-7f, 0f, 3.2f, 0.45f, 0.45f, 0f, 0f, 0f, -0.45f, 14f, 80f, 86f, 80f, 0f, 0f)
    MascotState.Talking -> Targets(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0.55f, 10f, 76f, 88f, 70f, 0f, 0f)
    MascotState.Reading -> Targets(0f, -1f, 3.4f, 0.3f, 0.3f, 0f, 0f, 0f, 0.35f, 37f, 74f, 63f, 74f, 0f, 1f)
    MascotState.Wink -> Targets(-3f, 1.5f, 0f, 1f, 0f, 1f, 0f, 0.45f, 0.9f, 10f, 76f, 92f, 52f, 0f, 0f)
    // Nervos (4.3, Mascota.dc.html): privirea puțin în jos, sprâncene în V, gura în jos, brațele pe lângă corp.
    MascotState.Angry -> Targets(0f, 0f, 1.5f, 0f, 0f, 0f, 0f, 0f, -0.75f, 10f, 76f, 90f, 76f, 0f, 0f, brow = 1f)
}

// ───────────────────────────── Geometria (spațiu 100×100) ─────────────────────────────

private const val OUTLINE = 3f
private const val EYE_R = 10.5f
private const val EYE_LX = 39f
private const val EYE_RX = 61f
private const val EYE_Y = 50f
private const val PIVOT_X = 50f
private const val PIVOT_Y = 90f

/** Căi, pensule și stiluri precalculate; un singur exemplar per compozabil. */
private class MascotRig {
    val stroke = Stroke(width = OUTLINE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val armInk = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val armFill = Stroke(width = 13f - 2 * OUTLINE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val strapStroke = Stroke(width = 2.6f, cap = StrokeCap.Round)

    val body = Path().apply {
        moveTo(50f, 90f)
        cubicTo(28f, 90f, 14f, 76f, 16f, 56f)
        cubicTo(18f, 40f, 30f, 28f, 42f, 24f)
        cubicTo(44f, 18f, 44f, 12f, 50f, 8f)
        cubicTo(58f, 12f, 60f, 20f, 58f, 24f)
        cubicTo(70f, 28f, 82f, 40f, 84f, 56f)
        cubicTo(86f, 76f, 72f, 90f, 50f, 90f)
        close()
    }
    val core = Path().apply {
        moveTo(50f, 82f)
        cubicTo(36f, 82f, 28f, 72f, 30f, 60f)
        cubicTo(32f, 50f, 42f, 44f, 46f, 36f)
        cubicTo(48f, 30f, 49f, 26f, 50f, 22f)
        cubicTo(51f, 26f, 52f, 30f, 54f, 36f)
        cubicTo(58f, 44f, 68f, 50f, 70f, 60f)
        cubicTo(72f, 72f, 64f, 82f, 50f, 82f)
        close()
    }
    val eyeL = Path().apply { addOval(Rect(Offset(EYE_LX, EYE_Y), EYE_R)) }
    val eyeR = Path().apply { addOval(Rect(Offset(EYE_RX, EYE_Y), EYE_R)) }
    val sheet = Path().apply {
        moveTo(31f, 56f); lineTo(69f, 54f); lineTo(70f, 94f); lineTo(30f, 96f); close()
    }
    // căi refolosite (reset în fiecare cadru)
    val mouth = Path()
    val armL = Path()
    val armR = Path()
    val clip = Path()
    var clipD = -1f
    val lidStroke = Stroke(OUTLINE)
    val arcStroke = Stroke(OUTLINE * 1.1f, cap = StrokeCap.Round)

    val bodyBrush = Brush.verticalGradient(0f to BodyOliveLight, 0.45f to BodyOlive, 1f to BodyOlive, startY = 8f, endY = 90f)
    val coreBrush = Brush.radialGradient(0f to AmberLight, 0.5f to Amber, 1f to EmberWarm, center = Offset(50f, 72f), radius = 34f)
    val glowBrush = Brush.radialGradient(0f to Amber.copy(alpha = 0.45f), 0.55f to Amber.copy(alpha = 0.12f), 1f to Amber.copy(alpha = 0f), center = Offset(50f, 60f), radius = 46f)
}

// ───────────────────────────── Desenarea ─────────────────────────────

/** Desenează mascota cu originea (0,0) a spațiului de 100 unități în colțul din stânga-sus, `unit` px pe unitate. */
private fun DrawScope.drawMascot(rig: MascotRig, p: Pose, hat: MascotHat, unit: Float) {
    scale(unit, unit, pivot = Offset.Zero) {
        translate(0f, -p.hop) {
            rotate(p.sway + p.tilt, pivot = Offset(PIVOT_X, PIVOT_Y)) {
                val sx = p.breath * (1f + 0.08f * p.squash)
                val sy = p.breath * (1f - 0.08f * p.squash)
                scale(sx, sy, pivot = Offset(PIVOT_X, PIVOT_Y)) {
                    drawFigure(rig, p, hat)
                }
            }
        }
    }
}

private fun DrawScope.drawFigure(rig: MascotRig, p: Pose, hat: MascotHat) {
    // aura de jar
    drawCircle(rig.glowBrush, radius = 46f, center = Offset(50f, 60f), alpha = 0.7f + 0.3f * p.glow)

    // tălpi
    drawOval(Ink, topLeft = Offset(30f, 83.5f), size = Size(20f, 12f))
    drawOval(Ink, topLeft = Offset(50f, 83.5f), size = Size(20f, 12f))
    drawOval(BodyOlive, topLeft = Offset(33f, 86.5f), size = Size(14f, 6f))
    drawOval(BodyOlive, topLeft = Offset(53f, 86.5f), size = Size(14f, 6f))

    // corp + miez
    drawPath(rig.body, rig.bodyBrush)
    drawPath(rig.core, rig.coreBrush)
    drawPath(rig.body, Ink, style = rig.stroke)

    // obraji
    drawOval(Blush, topLeft = Offset(24f, 58f), size = Size(10f, 5f))
    drawOval(Blush, topLeft = Offset(66f, 58f), size = Size(10f, 5f))

    // ochi
    drawEye(rig, rig.eyeL, EYE_LX, p, p.lidL, p.arcL)
    drawEye(rig, rig.eyeR, EYE_RX, p, p.lidR, p.arcR)

    // gură
    drawMouth(rig, p)

    // sprâncene încruntate (Angry): coboară spre nas, în V
    if (p.brow > 0.01f) drawBrows(p.brow)

    // pălăria
    when (hat) {
        MascotHat.None -> Unit
        MascotHat.Chef -> drawToque(rig)
        MascotHat.Helmet -> drawHelmet(rig)
    }

    // foaia (Reading) — alunecă de jos, sub brațe
    if (p.sheet > 0.01f) {
        translate(0f, (1f - p.sheet) * 30f) {
            drawPath(rig.sheet, SheetYellow, alpha = p.sheet)
            drawPath(rig.sheet, Ink, alpha = p.sheet, style = rig.stroke)
            var y = 64f
            while (y < 90f) {
                drawLine(SheetLine, Offset(38f, y), Offset(62f, y - 0.6f), strokeWidth = 1.6f, cap = StrokeCap.Round, alpha = p.sheet)
                y += 7f
            }
        }
    }

    // brațe (deasupra corpului și foii)
    drawArm(rig.armL, rig, 20f, 64f, p.armLx, p.armLy, outward = -1f)
    drawArm(rig.armR, rig, 80f, 64f, p.armRx, p.armRy, outward = 1f)

    // punctele de gândire
    if (p.dots > 0.01f) {
        drawDot(70f, 26f, 2.4f, p.dots)
        drawDot(78f, 17f, 3.4f, p.dots)
        drawDot(87f, 7f, 4.4f, p.dots)
    }
}

/** Sprâncenele în V (Mascota.dc.html, „angry”), scalate la ochii mai mari ai mascotei din aplicație. */
private fun DrawScope.drawBrows(b: Float) {
    val slant = 6.7f * b
    drawLine(Ink, Offset(27.8f, 36.4f - 1f * b), Offset(46.9f, 36.4f + slant), strokeWidth = 4f, cap = StrokeCap.Round, alpha = b)
    drawLine(Ink, Offset(72.2f, 36.4f - 1f * b), Offset(53.1f, 36.4f + slant), strokeWidth = 4f, cap = StrokeCap.Round, alpha = b)
}

private fun DrawScope.drawDot(x: Float, y: Float, r: Float, a: Float) {
    drawCircle(Ink, radius = r + OUTLINE * 0.8f, center = Offset(x, y), alpha = a)
    drawCircle(Amber, radius = r, center = Offset(x, y), alpha = a)
}

private fun DrawScope.drawEye(rig: MascotRig, clip: Path, cx: Float, p: Pose, lid: Float, arc: Float) {
    val open = (1f - p.blink).coerceAtLeast(0.08f)
    val c = Offset(cx, EYE_Y)
    scale(1f, open, pivot = c) {
        drawCircle(Ink, radius = EYE_R + OUTLINE * 0.9f, center = c)
        clipPath(clip) {
            drawCircle(EyeWhite, radius = EYE_R, center = c)
            // pupila + reflexe
            val px = cx + p.lookX
            val py = EYE_Y + p.lookY
            drawCircle(Ink, radius = 5.4f, center = Offset(px, py))
            drawCircle(EyeWhite, radius = 2.1f, center = Offset(px - 1.9f, py - 1.9f))
            drawCircle(EyeWhite, radius = 1.0f, center = Offset(px + 1.7f, py + 1.6f))
            // pleoapa (coboară de sus)
            if (lid > 0.005f) {
                val lr = EYE_R * 1.3f
                val ly = EYE_Y - 2.3f * EYE_R + lid * 2.6f * EYE_R
                drawCircle(BodyOlive, radius = lr, center = Offset(cx, ly))
                drawCircle(Ink, radius = lr, center = Offset(cx, ly), style = rig.lidStroke)
            }
        }
    }
    // ochi „fericit”: arc peste pleoapa închisă
    if (arc > 0.005f) {
        drawArc(
            Ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(cx - EYE_R * 0.85f, EYE_Y - EYE_R * 0.55f),
            size = Size(EYE_R * 1.7f, EYE_R * 1.5f),
            alpha = arc, style = rig.arcStroke
        )
    }
}

private fun DrawScope.drawMouth(rig: MascotRig, p: Pose) {
    val o = p.mouthOpen
    val s = p.mouthSmile
    val cy = 69f
    val hw = 7f + 3f * o + 1.5f * abs(s)           // semilățime
    val cornerY = cy - s * 2.2f                    // colțuri mai sus când zâmbește
    val bottomY = cy + s * 3.5f + o * 10f          // buza de jos
    val topY = cy - o * 2.5f - (if (s < 0) s * 2.5f else 0f)
    val m = rig.mouth
    m.reset()
    m.moveTo(50f - hw, cornerY)
    m.cubicTo(50f - hw * 0.5f, bottomY, 50f + hw * 0.5f, bottomY, 50f + hw, cornerY)
    if (o > 0.04f) {
        m.cubicTo(50f + hw * 0.5f, topY, 50f - hw * 0.5f, topY, 50f - hw, cornerY)
        m.close()
        drawPath(m, Ink)
        clipPath(m) {
            drawOval(EmberWarm, topLeft = Offset(50f - hw * 0.7f, cy + o * 4f + 1f), size = Size(hw * 1.4f, 6f + o * 6f))
        }
        drawPath(m, Ink, style = rig.stroke)
    } else {
        drawPath(m, Ink, style = rig.stroke)
    }
}

private fun DrawScope.drawArm(path: Path, rig: MascotRig, rx: Float, ry: Float, ex: Float, ey: Float, outward: Float) {
    val mx = (rx + ex) * 0.5f
    val my = (ry + ey) * 0.5f
    // cotul iese spre exterior și puțin în jos — braț ușor arcuit
    val dx = ex - rx
    val dy = ey - ry
    val cx = mx + outward * (-dy * 0.35f).coerceAtLeast(0f) + outward * 3f
    val cy = my + abs(dx) * 0.15f
    path.reset()
    path.moveTo(rx, ry)
    path.cubicTo(cx, cy, cx, cy, ex, ey)
    drawPath(path, Ink, style = rig.armInk)
    drawPath(path, BodyOlive, style = rig.armFill)
}

private fun DrawScope.drawToque(rig: MascotRig) {
    // conturul: aceleași forme, mărite cu grosimea conturului
    val o = OUTLINE
    drawCircle(Ink, 9.5f + o, Offset(37f, 19f))
    drawCircle(Ink, 11f + o, Offset(50f, 14f))
    drawCircle(Ink, 9.5f + o, Offset(63f, 19f))
    drawRoundRect(Ink, Offset(31f - o, 23f - o), Size(38f + 2 * o, 11f + 2 * o), CornerRadius(4f + o))
    drawCircle(ToqueWhite, 9.5f, Offset(37f, 19f))
    drawCircle(ToqueWhite, 11f, Offset(50f, 14f))
    drawCircle(ToqueWhite, 9.5f, Offset(63f, 19f))
    drawRoundRect(ToqueShade, Offset(31f, 23f), Size(38f, 11f), CornerRadius(4f))
    drawRoundRect(ToqueWhite, Offset(31f, 23f), Size(38f, 6f), CornerRadius(3f))
    drawLine(Ink, Offset(33f, 29.5f), Offset(67f, 29.5f), strokeWidth = 1.4f, cap = StrokeCap.Round)
}

private fun DrawScope.drawHelmet(rig: MascotRig) {
    val o = OUTLINE
    // cureaua sub bărbie (în spatele gurii — trece pe sub corp)
    drawArc(
        HelmetOlive, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(27f, 8f), size = Size(46f, 74f), style = rig.strapStroke
    )
    // cupola: jumătatea de sus a unui oval
    drawArc(Ink, 180f, 180f, true, Offset(22f - o, 4f - o), Size(56f + 2 * o, 44f + 2 * o))
    drawRoundRect(Ink, Offset(18f - o, 23f - o), Size(64f + 2 * o, 7f + 2 * o), CornerRadius(3.5f + o))
    drawArc(HelmetOlive, 180f, 180f, true, Offset(22f, 4f), Size(56f, 44f))
    drawArc(HelmetLight, 200f, 60f, false, Offset(28f, 9f), Size(44f, 34f), style = rig.thinStroke)
    drawRoundRect(HelmetOlive, Offset(18f, 23f), Size(64f, 7f), CornerRadius(3.5f))
    drawLine(Ink, Offset(22f, 23.5f), Offset(78f, 23.5f), strokeWidth = 1.4f, cap = StrokeCap.Round)
    // catarama
    drawRoundRect(Ink, Offset(46.5f, 74f), Size(7f, 5f), CornerRadius(1.5f))
    drawRoundRect(Amber, Offset(47.5f, 75f), Size(5f, 3f), CornerRadius(1f))
}

// ───────────────────────────── Animația ─────────────────────────────

/** Toate valorile animate ale unui exemplar; citite doar în faza de desenare. */
private class Anim(
    val reduced: Boolean,
    val breath: State<Float>?, val sway: State<Float>?, val tick: State<Float>?,
    val blink: Animatable<Float, *>, val glanceX: Animatable<Float, *>, val glanceY: Animatable<Float, *>,
    val hop: Animatable<Float, *>, val wave: Animatable<Float, *>,
    val tilt: State<Float>, val lookX: State<Float>, val lookY: State<Float>,
    val lidL: State<Float>, val lidR: State<Float>, val arcL: State<Float>, val arcR: State<Float>,
    val mouthOpen: State<Float>, val mouthSmile: State<Float>,
    val armLx: State<Float>, val armLy: State<Float>, val armRx: State<Float>, val armRy: State<Float>,
    val dots: State<Float>, val sheet: State<Float>,
    val state: State<MascotState>,
    val brow: State<Float>? = null
)

@Composable
private fun rememberAnim(state: MascotState): Anim {
    val reduced = LocalReducedMotion.current
    val t = remember(state) { targetsOf(state) }
    val spec = remember(reduced) { if (reduced) snap<Float>() else Springs.natural() }
    val gentle = remember(reduced) { if (reduced) snap<Float>() else Springs.gentle() }

    var breath: State<Float>? = null
    var sway: State<Float>? = null
    var tick: State<Float>? = null
    if (!reduced) {
        val inf = rememberInfiniteTransition(label = "mascot")
        breath = inf.animateFloat(
            1f, 1.03f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath"
        )
        sway = inf.animateFloat(
            -3f, 3f, infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway"
        )
        tick = inf.animateFloat(
            0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "tick"
        )
    }

    val blink = remember { Animatable(0f) }
    val glanceX = remember { Animatable(0f) }
    val glanceY = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    val rnd = remember { Random(System.nanoTime().toInt()) }

    // clipit la 3–6 s, aleator (din când în când dublu)
    LaunchedEffect(reduced) {
        if (reduced) { blink.snapTo(0f); return@LaunchedEffect }
        while (true) {
            delay(rnd.nextLong(3000L, 6000L))
            blink.animateTo(1f, tween(70))
            blink.animateTo(0f, tween(120))
            if (rnd.nextFloat() < 0.22f) {
                delay(140)
                blink.animateTo(1f, tween(60))
                blink.animateTo(0f, tween(110))
            }
        }
    }
    // privire fugară în Idle
    LaunchedEffect(reduced, state) {
        if (reduced || state != MascotState.Idle) {
            glanceX.animateTo(0f, gentle); glanceY.animateTo(0f, gentle); return@LaunchedEffect
        }
        while (true) {
            delay(rnd.nextLong(2500L, 5500L))
            val gx = rnd.nextFloat() * 6f - 3f
            val gy = rnd.nextFloat() * 2.5f - 1f
            launch { glanceX.animateTo(gx, gentle) }
            glanceY.animateTo(gy, gentle)
            delay(rnd.nextLong(600L, 1300L))
            launch { glanceX.animateTo(0f, gentle) }
            glanceY.animateTo(0f, gentle)
        }
    }
    // Happy: săritură cu arc la intrare
    LaunchedEffect(state, reduced) {
        if (state == MascotState.Happy && !reduced) {
            hop.animateTo(9f, Springs.snappy())
            hop.animateTo(0f, Springs.natural())
        }
    }

    val stateS = rememberUpdatedState(state)
    return Anim(
        reduced, breath, sway, tick, blink, glanceX, glanceY, hop, wave,
        tilt = animateFloatAsState(t.tilt, spec, label = "tilt"),
        lookX = animateFloatAsState(t.lookX, spec, label = "lookX"),
        lookY = animateFloatAsState(t.lookY, spec, label = "lookY"),
        lidL = animateFloatAsState(t.lidL, spec, label = "lidL"),
        lidR = animateFloatAsState(t.lidR, spec, label = "lidR"),
        arcL = animateFloatAsState(t.arcL, spec, label = "arcL"),
        arcR = animateFloatAsState(t.arcR, spec, label = "arcR"),
        mouthOpen = animateFloatAsState(t.mouthOpen, spec, label = "mouthOpen"),
        mouthSmile = animateFloatAsState(t.mouthSmile, spec, label = "mouthSmile"),
        armLx = animateFloatAsState(t.armLx, spec, label = "armLx"),
        armLy = animateFloatAsState(t.armLy, spec, label = "armLy"),
        armRx = animateFloatAsState(t.armRx, spec, label = "armRx"),
        armRy = animateFloatAsState(t.armRy, spec, label = "armRy"),
        dots = animateFloatAsState(t.dots, spec, label = "dots"),
        sheet = animateFloatAsState(t.sheet, spec, label = "sheet"),
        state = stateS,
        brow = animateFloatAsState(t.brow, spec, label = "brow")
    )
}

/** Umple poza din valorile animate (apelat în faza de desenare; doar citiri de stare, fără alocări). */
private fun Anim.fill(p: Pose) {
    val st = state.value
    val tk = tick?.value ?: 0f
    val ph = tk * 2f * PI.toFloat()

    p.breath = breath?.value ?: 1f
    p.sway = sway?.value ?: 0f
    p.glow = if (breath != null) ((p.breath - 1f) / 0.03f) else 1f
    p.tilt = tilt.value
    p.blink = blink.value
    p.lookX = lookX.value + glanceX.value
    p.lookY = lookY.value + glanceY.value
    p.lidL = lidL.value; p.lidR = lidR.value
    p.arcL = arcL.value; p.arcR = arcR.value
    p.mouthSmile = mouthSmile.value
    p.dots = dots.value
    p.sheet = sheet.value
    p.brow = brow?.value ?: 0f

    // gura: vorbire ritmică (două frecvențe, ca un ritm de vorbă)
    p.mouthOpen = if (st == MascotState.Talking && tick != null) {
        val a = abs(sin(ph * 2.5f))
        val b = 0.55f + 0.45f * sin(ph)
        (0.12f + 0.88f * a * b).coerceIn(0f, 1f) * mouthOpen.value * 2f
    } else mouthOpen.value

    // brațe
    var rx = armRx.value; var ry = armRy.value
    if (st == MascotState.Thinking && tick != null) { rx += sin(ph * 3f) * 2.2f; ry += abs(sin(ph * 3f)) * 1.2f }
    if (st == MascotState.Happy && tick != null) { ry -= abs(sin(ph)) * 4f }
    // salut la atingere: brațul drept sus, fluturat
    val w = wave.value
    if (w > 0.001f) {
        val env = sin(w * PI.toFloat())
        rx = rx + (94f - rx) * env + sin(w * PI.toFloat() * 5f) * 7f * env
        ry = ry + (40f - ry) * env
    }
    p.armRx = rx; p.armRy = ry
    var ly = armLy.value
    if (st == MascotState.Happy && tick != null) { ly -= abs(sin(ph)) * 4f }
    p.armLx = armLx.value; p.armLy = ly

    // săritura: arcul la intrare + ritmul vesel
    var h = hop.value
    if (st == MascotState.Happy && tick != null) h += abs(sin(ph)) * 5f
    p.hop = h
    p.squash = (h / 9f).coerceIn(0f, 1f) * 0.6f
}

// ───────────────────────────── API public ─────────────────────────────

/**
 * Mascota întreagă, animată. `onTap` (opțional) se apelează la atingere; mascota face oricum cu mâna și
 * sare puțin, cu feedback haptic.
 */
@Composable
fun Mascot(
    state: MascotState = MascotState.Idle,
    hat: MascotHat = MascotHat.None,
    size: Dp = 120.dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val rig = remember { MascotRig() }
    val pose = remember { Pose() }
    val anim = rememberAnim(state)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val reduced = anim.reduced
    Canvas(
        modifier
            .size(size)
            .pointerInput(Unit) {
                detectTapGestures {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    tap?.invoke()
                    if (!reduced) scope.launch {
                        launch {
                            anim.hop.animateTo(6f, Springs.snappy())
                            anim.hop.animateTo(0f, Springs.natural())
                        }
                        anim.wave.snapTo(0f)
                        anim.wave.animateTo(1f, tween(700, easing = LinearEasing))
                        anim.wave.snapTo(0f)
                    }
                }
            }
    ) {
        anim.fill(pose)
        drawMascot(rig, pose, hat, this.size.minDimension / 100f)
    }
}

/** Avatar static: capul mascotei într-un cerc, pentru liste și antete. */
@Composable
fun MascotAvatar(
    hat: MascotHat = MascotHat.None,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val rig = remember { MascotRig() }
    val pose = remember { Pose() }
    Canvas(modifier.size(size)) {
        drawHeadInCircle(rig, pose, hat, this.size.minDimension)
    }
}

/** Desenează cercul de fundal și mascota mărită, centrată pe față, decupată de cerc. */
private fun DrawScope.drawHeadInCircle(rig: MascotRig, p: Pose, hat: MascotHat, d: Float) {
    val r = d / 2f
    drawCircle(Surface2, radius = r, center = Offset(r, r))
    drawCircle(Accent.copy(alpha = 0.35f), radius = r, center = Offset(r, r))
    // zoom: fața (centrul ~ (50, 52)) umple cercul; corpul ocupă 100 unități → 1.55× cerc
    val unit = d * (if (hat == MascotHat.None) 1.55f else 1.42f) / 100f
    val ox = r - 50f * unit
    val oy = r - (if (hat == MascotHat.None) 50f else 44f) * unit
    clipPath(rig.circleClip(d)) {
        translate(ox, oy) {
            drawMascot(rig, p, hat, unit)
        }
    }
    drawCircle(StrokeCardStrong, radius = r - 0.5f, center = Offset(r, r), style = Stroke(1f))
}

/** Cerc de decupare în pixeli, refolosit cât timp dimensiunea nu se schimbă. */
private fun MascotRig.circleClip(d: Float): Path {
    if (clipD != d) {
        clip.reset()
        clip.addOval(Rect(0f, 0f, d, d))
        clipD = d
    }
    return clip
}

/**
 * Avatar + bulă de vorbire (stil BitePal): capul rotund în stânga, bula mare rotunjită cu o codiță mică la dreapta.
 * Avatarul e animat după `state` (implicit vorbește).
 */
@Composable
fun MascotSays(
    text: String,
    modifier: Modifier = Modifier,
    state: MascotState = MascotState.Talking,
    hat: MascotHat = MascotHat.None,
    size: Dp = 56.dp
) {
    val rig = remember { MascotRig() }
    val pose = remember { Pose() }
    val anim = rememberAnim(state)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Canvas(Modifier.size(size)) {
            anim.fill(pose)
            // în cerc, fără legănat/săritură (capul stă pe loc, doar fața trăiește)
            pose.sway = 0f; pose.hop = 0f; pose.squash = 0f; pose.tilt = pose.tilt * 0.5f
            drawHeadInCircle(rig, pose, hat, this.size.minDimension)
        }
        Spacer(Modifier.width(10.dp))
        val tailPath = remember { Path() }
        Box(
            Modifier
                .weight(1f)
                .drawBehind {
                    val d = density
                    val ty = 18f * d
                    val tw = 9f * d
                    val th = 7f * d
                    tailPath.reset()
                    tailPath.moveTo(0.5f * d, ty - th)
                    tailPath.lineTo(-tw, ty)
                    tailPath.lineTo(0.5f * d, ty + th)
                    tailPath.close()
                    drawPath(tailPath, Surface1)
                    drawLine(StrokeCardStrong, Offset(0f, ty - th), Offset(-tw, ty), strokeWidth = 1f * d)
                    drawLine(StrokeCardStrong, Offset(-tw, ty), Offset(0f, ty + th), strokeWidth = 1f * d)
                }
                .background(Surface1, RoundedCornerShape(20.dp))
                .drawBehind {
                    drawRoundRect(
                        StrokeCardStrong,
                        cornerRadius = CornerRadius(20.dp.toPx()),
                        style = Stroke(1.dp.toPx())
                    )
                }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text,
                style = Body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

// ───────────────────────────── Vitrina (revizuire vizuală) ─────────────────────────────

/** Toate stările, pălăriile și dimensiunile, pentru revizuire vizuală. Fără preview — se montează într-un ecran. */
@Composable
fun MascotShowcase(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("DIMENSIUNI", style = monoLabel(10).copy(color = TextDim))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mascot(size = 40.dp)
            Mascot(size = 56.dp, hat = MascotHat.Chef)
            Mascot(size = 120.dp, hat = MascotHat.Helmet)
        }
        Mascot(size = 200.dp, state = MascotState.Happy)

        Text("STĂRI (atinge pentru salut)", style = monoLabel(10).copy(color = TextDim))
        MascotState.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { s ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Mascot(state = s, size = 96.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(s.name.uppercase(), style = monoLabel(9).copy(color = TextDim))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Text("PĂLĂRII", style = monoLabel(10).copy(color = TextDim))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MascotHat.entries.forEach { h -> Mascot(hat = h, size = 96.dp, state = MascotState.Wink) }
        }

        Text("AVATAR", style = monoLabel(10).copy(color = TextDim))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MascotAvatar(size = 40.dp)
            MascotAvatar(size = 40.dp, hat = MascotHat.Chef)
            MascotAvatar(size = 56.dp, hat = MascotHat.Helmet)
        }

        Text("BULĂ", style = monoLabel(10).copy(color = TextDim))
        MascotSays("Unde mănânci de obicei? Spune-mi și adaptez rația.")
        MascotSays("Nu a mers. Încearcă din nou.", state = MascotState.Sorry, hat = MascotHat.Chef)
        MascotSays("Misiune îndeplinită.", state = MascotState.Happy, hat = MascotHat.Helmet)
        Spacer(Modifier.height(24.dp))
    }
}
