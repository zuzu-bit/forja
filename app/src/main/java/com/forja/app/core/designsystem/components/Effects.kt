package com.forja.app.core.designsystem.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.AccentGradient
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Springs
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.SwitchOff
import com.forja.app.core.designsystem.monoLabel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/*
 * Efectele „forjei” — scântei, dezvăluiri în trepte, mașină de scris, puls, ștampilă.
 * Toate se opresc sub LocalReducedMotion: starea finală, fără bucle.
 */

// ───────────────────────────── Scântei ─────────────────────────────

/** O scânteie în spațiu normalizat 0..1 (x, y), rază în dp. */
private class Ember(
    var x: Float, var y: Float, var r: Float,
    var vx: Float, var vy: Float,
    var life: Float, var maxLife: Float,
    var phase: Float, var color: Color
)

private fun randomEmber(rnd: Random, colors: List<Color>, maxRadiusDp: Float, spawnAnywhere: Boolean): Ember {
    val maxLife = 3f + rnd.nextFloat() * 4f
    return Ember(
        x = rnd.nextFloat(),
        y = if (spawnAnywhere) rnd.nextFloat() * 1.1f else 1.05f,
        r = 0.6f + rnd.nextFloat() * (maxRadiusDp - 0.6f).coerceAtLeast(0.1f),
        vx = (rnd.nextFloat() - 0.5f) * 0.02f,
        vy = -(0.06f + rnd.nextFloat() * 0.08f),
        life = if (spawnAnywhere) rnd.nextFloat() * maxLife else 0f,
        maxLife = maxLife,
        phase = rnd.nextFloat() * (2f * PI.toFloat()),
        color = colors[rnd.nextInt(colors.size)]
    )
}

private fun respawn(e: Ember, rnd: Random, colors: List<Color>, maxRadiusDp: Float) {
    val n = randomEmber(rnd, colors, maxRadiusDp, spawnAnywhere = false)
    e.x = n.x; e.y = n.y; e.r = n.r; e.vx = n.vx; e.vy = n.vy
    e.life = 0f; e.maxLife = n.maxLife; e.phase = n.phase; e.color = n.color
}

private fun step(embers: Array<Ember>, dt: Float, t: Float, rnd: Random, colors: List<Color>, maxRadiusDp: Float) {
    for (e in embers) {
        e.life += dt
        e.x += (e.vx + 0.02f * sin(t * 0.8f + e.phase)) * dt
        e.y += e.vy * dt
        if (e.life >= e.maxLife || e.y < -0.05f || e.x < -0.05f || e.x > 1.05f) {
            respawn(e, rnd, colors, maxRadiusDp)
        }
    }
}

/** Scântei de forjă: urcă încet, plutesc lateral, pâlpâie, mor și reapar jos. */
@Composable
fun EmberField(
    modifier: Modifier = Modifier,
    count: Int = 42,
    colors: List<Color> = listOf(EmberHot, EmberWarm, Accent2),
    speed: Float = 1f,            // multiplicator al vitezei verticale
    maxRadiusDp: Float = 2.6f,
    alpha: Float = 0.85f,
    seed: Int = 7
) {
    val reduced = LocalReducedMotion.current
    val palette = remember(colors) { colors.ifEmpty { listOf(Accent2) } }
    val rnd = remember(seed) { Random(seed) }
    val embers = remember(seed, count, palette, maxRadiusDp) {
        Array(count.coerceAtLeast(0)) { randomEmber(rnd, palette, maxRadiusDp, spawnAnywhere = true) }
    }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(reduced, embers) {
        if (reduced) return@LaunchedEffect
        var last = withFrameNanos { it }
        val start = last
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                step(embers, dt * speed, (now - start) / 1e9f, rnd, palette, maxRadiusDp)
                frame = now
            }
        }
    }

    Canvas(modifier) {
        val now = frame // citire → redesenare la fiecare cadru
        val flickerT = now / 1e8f
        val w = size.width
        val h = size.height
        for (e in embers) {
            val lifeArc = sin(PI.toFloat() * (e.life / e.maxLife).coerceIn(0f, 1f))
            val a = (alpha * lifeArc * (0.7f + 0.3f * sin(e.phase + flickerT))).coerceIn(0f, 1f)
            if (a <= 0.005f) continue
            val c = Offset(e.x * w, e.y * h)
            val r = e.r * density
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(e.color.copy(alpha = a * 0.55f), e.color.copy(alpha = 0f)),
                    center = c, radius = r * 3f
                ),
                radius = r * 3f, center = c
            )
            drawCircle(e.color.copy(alpha = a), r, c)
        }
    }
}

// ───────────────────────────── Dezvăluire ─────────────────────────────

/** Apariție în trepte: fade + urcare ușoară, întârziată cu index*staggerMs; visible=false ascunde repede. */
@Composable
fun Reveal(
    visible: Boolean = true,
    index: Int = 0,
    staggerMs: Int = 80,
    initialDelayMs: Int = 0,
    offsetY: Dp = 18.dp,
    modifier: Modifier = Modifier,
    key: Any? = null,
    content: @Composable () -> Unit
) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced && visible) }
    LaunchedEffect(visible, key, reduced) {
        if (visible) {
            if (!reduced) {
                // O cheie nouă (pagină/mesaj) reia dezvăluirea de la zero.
                shown = false
                delay(initialDelayMs + index * staggerMs.toLong())
            }
            shown = true
        } else {
            shown = false
        }
    }
    val spec: AnimationSpec<Float> = if (reduced) snap() else Springs.natural()
    val progress by animateFloatAsState(if (shown) 1f else 0f, spec, label = "reveal")
    Box(
        modifier.graphicsLayer {
            this.alpha = progress
            translationY = (1f - progress) * offsetY.toPx()
        }
    ) { content() }
}

// ───────────────────────────── Mașină de scris ─────────────────────────────

/** Text „bătut la mașină”, literă cu literă, cu cursor-bloc „▍” care clipește. */
@Composable
fun TypewriterText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    charDelayMs: Long = 26,
    startDelayMs: Long = 0,
    cursor: Boolean = true,
    cursorColor: Color = Accent2,
    hapticTick: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    var shown by remember(text) { mutableIntStateOf(if (reduced) text.length else 0) }
    var cursorOn by remember(text) { mutableStateOf(cursor && !reduced) }

    LaunchedEffect(text, reduced) {
        if (reduced) {
            shown = text.length
            cursorOn = false
        } else {
            delay(startDelayMs)
            while (shown < text.length) {
                delay(charDelayMs)
                shown++
                if (hapticTick && shown % 3 == 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            delay(600)
            cursorOn = false
        }
        onDone?.invoke()
    }

    // Clipirea cursorului: sub „mișcare redusă” nu pornim nicio buclă.
    val cursorAlpha = if (reduced || !cursorOn) 1f else blinkValue()

    // Textul complet, invizibil, ține locul: layout-ul nu sare cât se scrie.
    Box(modifier) {
        Text(if (cursor) "$text▍" else text, style = style, modifier = Modifier.alpha(0f))
        Text(
            buildAnnotatedString {
                append(text.take(shown))
                if (cursor && cursorOn) {
                    withStyle(SpanStyle(color = cursorColor.copy(alpha = cursorAlpha))) { append("▍") }
                }
            },
            style = style
        )
    }
}

// ───────────────────────────── Puls ─────────────────────────────

/** Strălucire care „respiră” în spatele conținutului (gradient radial, ritm blând). */
@Composable
fun PulseGlow(
    modifier: Modifier = Modifier,
    color: Color = Accent2,
    radius: Dp = 56.dp,
    minAlpha: Float = 0.18f,
    maxAlpha: Float = 0.45f,
    periodMs: Int = 1800,
    content: @Composable BoxScope.() -> Unit
) {
    val reduced = LocalReducedMotion.current
    // Sub „mișcare redusă”: o singură stare, la mijlocul respirației, fără buclă.
    val t = if (reduced) 0.5f else pulseValue(periodMs)
    Box(
        modifier.drawBehind {
            val r = radius.toPx() * (0.85f + 0.3f * t)
            if (r <= 0f) return@drawBehind
            val a = minAlpha + (maxAlpha - minAlpha) * t
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = center, radius = r),
                radius = r, center = center
            )
        },
        contentAlignment = Alignment.Center,
        content = content
    )
}

// ───────────────────────────── Pop ─────────────────────────────

/** Apariție „pop”: scară 0.6→1 cu depășire snappy + fade; întârziere opțională. */
@Composable
fun PopIn(
    visible: Boolean = true,
    delayMs: Long = 0,
    fromScale: Float = 0.6f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced && visible) }
    LaunchedEffect(visible, reduced) {
        if (visible) {
            if (!reduced) delay(delayMs)
            shown = true
        } else {
            shown = false
        }
    }
    val scaleSpec: AnimationSpec<Float> = if (reduced) snap() else Springs.snappy()
    val alphaSpec: AnimationSpec<Float> = if (reduced) snap() else tween(180)
    val s by animateFloatAsState(if (shown) 1f else fromScale, scaleSpec, label = "popScale")
    val a by animateFloatAsState(if (shown) 1f else 0f, alphaSpec, label = "popAlpha")
    Box(
        modifier.graphicsLayer {
            scaleX = s; scaleY = s; alpha = a
        }
    ) { content() }
}

// ───────────────────────────── Ștampilă ─────────────────────────────

/** Ștampilă militară: mono majuscule, chenar dublu, ușor rotită, „lovită” (1.35→1) la apariție. */
@Composable
fun StampLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Accent2,
    rotationDeg: Float = -6f,
    fontSize: Int = 11,
    tracking: Float = 0.22f,
    appear: Boolean = true,
    delayMs: Long = 0
) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(!appear || reduced) }
    LaunchedEffect(appear, reduced) {
        if (appear && !reduced) {
            delay(delayMs)
        }
        shown = true
    }
    val hitSpec: AnimationSpec<Float> = if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 900f)
    val inkSpec: AnimationSpec<Float> = if (reduced) snap() else tween(120)
    val s by animateFloatAsState(if (shown) 1f else 1.35f, hitSpec, label = "stampScale")
    val a by animateFloatAsState(if (shown) 1f else 0f, inkSpec, label = "stampAlpha")

    Box(
        modifier
            .graphicsLayer {
                rotationZ = rotationDeg
                scaleX = s; scaleY = s
                alpha = a
            }
            .drawBehind {
                val corner = CornerRadius(2.dp.toPx())
                drawRoundRect(color.copy(alpha = 0.9f), cornerRadius = corner, style = Stroke(1.5.dp.toPx()))
                inset(3.dp.toPx()) {
                    drawRoundRect(color.copy(alpha = 0.55f), cornerRadius = CornerRadius(1.dp.toPx()), style = Stroke(0.75.dp.toPx()))
                }
                // „Tuș tocit”: două-trei linii subțiri în culoarea fundalului peste chenar
                val wear = Surface0.copy(alpha = 0.35f)
                val w = size.width
                val h = size.height
                val sw = 0.9f * density
                drawLine(wear, Offset(w * 0.18f, 0f), Offset(w * 0.24f, h), strokeWidth = sw)
                drawLine(wear, Offset(w * 0.61f, 0f), Offset(w * 0.66f, h), strokeWidth = sw)
                drawLine(wear, Offset(w * 0.86f, 0f), Offset(w * 0.90f, h), strokeWidth = sw)
            }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text.uppercase(),
            style = monoLabel(fontSize, tracking).copy(color = color, fontWeight = FontWeight.Bold)
        )
    }
}

/** Valoarea clipirii cursorului (1 → 0 → 1, 500 ms). Compusă doar când clipirea e activă. */
@Composable
private fun blinkValue(): Float {
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "blink"
    )
    return blink
}

/** Valoarea pulsului (0 → 1 → 0), ritm blând. Compusă doar când mișcarea nu e redusă. */
@Composable
private fun pulseValue(periodMs: Int): Float {
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs.coerceAtLeast(200), easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    return pulse
}

// ───────────────────────────── Ajutoare mici ─────────────────────────────

/** Punctele paginilor: activul se alungește (snappy). */
@Composable
fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
    active: Color = Accent2,
    inactive: Color = Color(0x668A8F98)
) {
    val reduced = LocalReducedMotion.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(count.coerceAtLeast(0)) { i ->
            val spec: AnimationSpec<Dp> = if (reduced) snap() else Springs.snappy()
            val w by animateDpAsState(if (i == current) 22.dp else 8.dp, spec, label = "dot$i")
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(width = w, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (i == current) active else inactive)
            )
        }
    }
}

/** Bară de progres orizontală (obiectiv, echipare), animată natural. */
@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = SwitchOff,
    brush: Brush = AccentGradient
) {
    val reduced = LocalReducedMotion.current
    val spec: AnimationSpec<Float> = if (reduced) snap() else Springs.natural()
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spec, label = "bar")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track)
    ) {
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(p.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(brush)
            )
        }
    }
}
