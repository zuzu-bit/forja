package com.forja.app.feature.inventory

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Springs
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextDim2
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.pressable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ───────────────────────────── Butoane ─────────────────────────────

/** Buton-iconiță 44 dp (prototip: fundal #17181C, contur 6 %, rază 8). Ținta de atingere e extinsă la 48 dp de Compose. */
@Composable
internal fun InvIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier
            .pressable(onClick)
            .size(44.dp)
            .clip(R8)
            .background(Raised)
            .border(1.dp, W06, R8)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Butonul „i” (reia ghidajul). */
@Composable
internal fun InfoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .pressable(onClick)
            .size(44.dp)
            .clip(R8)
            .background(Raised)
            .border(1.dp, W06, R8)
            .semantics { contentDescription = "Arată ghidajul"; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text("i", style = mono(15, color = Accent2, bold = true))
    }
}

/** Butonul principal (58 dp, gradient olive, Barlow 22 + meta mono 11). */
@Composable
internal fun InvPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    enabled: Boolean = true,
    height: Dp = 58.dp
) {
    Row(
        modifier
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .fillMaxWidth()
            .height(height)
            .clip(R8)
            .background(CtaBrush)
            .semantics { role = Role.Button; if (meta != null) stateDescription = meta },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val a = if (enabled) 1f else 0.5f
        Text(label, style = cond(22, tracking = 0.04f, color = OnAccent.copy(alpha = a)), maxLines = 1)
        if (meta != null) {
            Spacer(Modifier.width(10.dp))
            Text(meta, style = mono(11, 0.1f, color = OnAccent.copy(alpha = 0.75f * a)), maxLines = 1)
        }
    }
}

/** Butonul secundar conturat (Gata.dc.html: 52 dp, contur 12 %, Barlow 19 gri). */
@Composable
internal fun InvOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 52.dp) {
    Box(
        modifier
            .pressable(onClick)
            .fillMaxWidth()
            .height(height)
            .clip(R8)
            .border(1.dp, W12, R8)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = cond(19, color = TextSecondary), maxLines = 1)
    }
}

/** Chip de scop (Main.dc.html: 44 dp, rază 6, Barlow 17). */
@Composable
internal fun ScopeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: ImageVector? = null
) {
    val reduced = LocalReducedMotion.current
    val bg by animateColorAsState(if (selected) Accent.copy(alpha = 0.35f) else Surface1, if (reduced) snap() else tween(180), label = "chipBg")
    val fg by animateColorAsState(if (selected) OnAccent else TextSecondary, if (reduced) snap() else tween(180), label = "chipFg")
    Row(
        modifier
            .pressable(onClick)
            .height(44.dp)
            .clip(R6)
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Accent2 else W09, R6)
            .semantics { role = Role.Tab; stateDescription = if (selected) "ales" else "neales" }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = cond(17, tracking = 0.02f, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, null, tint = fg, modifier = Modifier.size(14.dp))
        }
    }
}

// ───────────────────────────── Pastila de progres ─────────────────────────────

/** Starea pastilei: procentul, „Gata” la final. */
data class PillState(val percent: Int, val ready: Boolean)

/**
 * Pastila de progres a inventarului (Scroll/Sport/Interval/Muzica.dc.html): inel 26 dp + „34 %” + săgeată; la final
 * devine olive, cu bifă și „Gata”. `overVideo` = fundal translucid (peste clipuri).
 */
@Composable
fun InvProgressPill(state: PillState, onClick: () -> Unit, modifier: Modifier = Modifier, overVideo: Boolean = false, elevated: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val colorSpec: AnimationSpec<Color> = if (reduced) snap() else tween(320)
    val bg by animateColorAsState(
        when {
            state.ready -> Accent
            overVideo -> Surface0.copy(alpha = 0.6f)
            else -> Surface1
        }, colorSpec, label = "pillBg"
    )
    val stroke by animateColorAsState(if (state.ready) Accent2 else if (overVideo) W14 else W12, colorSpec, label = "pillStroke")
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .pressable(onClick)
            .then(if (elevated) Modifier.shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .height(36.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .semantics {
                role = Role.Button
                contentDescription = if (state.ready) "Inventarul e gata" else "Progresul inventarului, ${state.percent} la sută"
            }
            .padding(start = 5.dp, end = 12.dp)
            .animateContentSize(animationSpec = if (reduced) snap<IntSize>() else Springs.snappyVisibility<IntSize>()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = state.ready,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 220)) togetherWith fadeOut(tween(if (reduced) 0 else 160)) },
            label = "pillIcon"
        ) { ready ->
            if (ready) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(OnAccent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(InvIcons.CheckBold, null, tint = OnAccent, modifier = Modifier.size(14.dp))
                }
            } else {
                MiniRing(state.percent / 100f, Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.ready) "Gata" else fmtPercent(state.percent),
            style = mono(12, color = if (state.ready) OnAccent else TextPrimary, bold = true),
            maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        Icon(InvIcons.ChevronDown, null, tint = if (state.ready) OnAccent.copy(alpha = 0.75f) else TextSecondary, modifier = Modifier.size(14.dp))
    }
}

/** Inelul mic al pastilei: pistă 16 % + arc amber, din vârf, capăt rotunjit. */
@Composable
internal fun MiniRing(fraction: Float, modifier: Modifier = Modifier, color: Color = Amber, track: Color = W16) {
    val reduced = LocalReducedMotion.current
    val p by animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduced) snap() else Springs.natural(), label = "miniRing")
    Canvas(modifier) {
        val sw = size.minDimension * (3f / 26f)
        val r = size.minDimension * (10f / 26f)
        val tl = Offset(center.x - r, center.y - r)
        drawCircle(track, r, center, style = Stroke(sw))
        if (p > 0f) drawArc(color, -90f, 360f * p, false, topLeft = tl, size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Antetul modurilor de așteptare: pastila (stânga) și ștampila sau altceva (dreapta). */
@Composable
internal fun WaitHeader(pill: PillState?, onPill: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (pill != null) InvProgressPill(pill, onPill) else Spacer(Modifier.size(1.dp))
        trailing()
    }
}

// ───────────────────────────── Egalizatoare ─────────────────────────────

private val EqEase = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Valoarea unei bare (0.25 ↔ 1, ease-in-out pe fiecare jumătate), ca `@keyframes forjaEq`. */
private fun eqLevel(phase: Float): Float {
    val p = ((phase % 1f) + 1f) % 1f
    return if (p < 0.5f) 0.25f + 0.75f * EqEase.transform(p / 0.5f) else 1f - 0.75f * EqEase.transform((p - 0.5f) / 0.5f)
}

/**
 * Egalizatorul din cardul MUZICĂ (Rulare.dc.html): 5 bare 8×44, amber/jar/amber/olive/amber, 0,9 s, decalaj 0,15 s.
 * Sub mișcare redusă: nivele fixe.
 */
@Composable
internal fun Equalizer5(modifier: Modifier = Modifier, animate: Boolean = true) {
    val colors = remember { listOf(Amber, EmberWarm, Amber, Accent2, Amber) }
    val reduced = LocalReducedMotion.current
    val t = if (animate && !reduced) {
        rememberInfiniteTransition(label = "eq5").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "eq5t")
    } else null
    val rest = remember { floatArrayOf(0.5f, 0.8f, 1f, 0.65f, 0.4f) }
    Canvas(modifier.size(width = 60.dp, height = 44.dp)) {
        val bw = 8.dp.toPx()
        val gap = 5.dp.toPx()
        val h = size.height
        for (i in 0 until 5) {
            val lv = t?.let { eqLevel(it.value - i * (0.15f / 0.9f)) } ?: rest[i]
            val bh = h * lv
            drawRoundRect(colors[i], topLeft = Offset(i * (bw + gap), h - bh), size = Size(bw, bh), cornerRadius = CornerRadius(2.dp.toPx()))
        }
    }
}

/** Egalizatorul mic din antetul Muzică: 4 bare 4×18, 0,8 s; se oprește (îngheață) când nu cântă. */
@Composable
internal fun EqualizerMini(playing: Boolean, modifier: Modifier = Modifier) {
    val colors = remember { listOf(Amber, EmberWarm, Amber, Accent2) }
    val reduced = LocalReducedMotion.current
    val t = if (playing && !reduced) {
        rememberInfiniteTransition(label = "eq4").animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "eq4t")
    } else null
    Canvas(modifier.size(width = 25.dp, height = 18.dp).semantics { contentDescription = if (playing) "Muzica cântă" else "Muzica e oprită" }) {
        val bw = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val h = size.height
        for (i in 0 until 4) {
            val lv = t?.let { eqLevel(it.value - i * (0.12f / 0.8f)) } ?: 0.25f
            val bh = h * lv
            drawRoundRect(colors[i], topLeft = Offset(i * (bw + gap), h - bh), size = Size(bw, bh), cornerRadius = CornerRadius(2.dp.toPx()))
        }
    }
}

/**
 * Egalizatorul turtit (Gata.dc.html): muzica s-a oprit. Nota stinsă în față îl face să se citească „muzică”;
 * singure, cele 4 puncte gri păreau un indicator de pagină rătăcit.
 */
@Composable
internal fun EqualizerFlat(modifier: Modifier = Modifier) {
    Row(
        modifier.height(18.dp).semantics(mergeDescendants = true) { contentDescription = "Muzica s-a oprit" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(InvIcons.Note, null, tint = TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 18.dp, height = 18.dp)) {
            val bw = 4.dp.toPx()
            val gap = 3.dp.toPx()
            val bh = 3.dp.toPx()
            // pe linia de bază a notei (capul ei stă jos), nu sub ea
            val y = size.height - 3.dp.toPx() - bh
            for (i in 0 until 3) {
                drawRoundRect(TextDim2, topLeft = Offset(i * (bw + gap), y), size = Size(bw, bh), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
    }
}

// ───────────────────────────── Comutator ─────────────────────────────

/** Comutatorul din Muzica.dc.html: 46×28, buton 22; pornit = olive + alb, oprit = #2A2B30 + gri. */
@Composable
internal fun InvSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val x by animateFloatAsState(if (on) 1f else 0f, if (reduced) snap() else Springs.snappy(), label = "switch")
    val track by animateColorAsState(if (on) Accent else Track, if (reduced) snap() else tween(200), label = "switchTrack")
    val knob by animateColorAsState(if (on) TextPrimary else TextSecondary, if (reduced) snap() else tween(200), label = "switchKnob")
    Box(
        modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .offset(x = 18.dp * x)
                .size(22.dp)
                .clip(CircleShape)
                .background(knob)
        )
    }
}

// ───────────────────────────── Fundal și așezare ─────────────────────────────

private val TopoPaths = listOf(
    "M-20 120 C80 90 160 150 250 120 C320 96 380 130 420 110",
    "M-20 160 C90 130 170 190 260 160 C330 136 380 170 420 150",
    "M-20 560 C60 520 150 600 240 560 C320 526 370 580 420 550",
    "M-20 600 C70 560 160 640 250 600 C330 566 380 620 420 590",
    "M-20 640 C80 600 170 680 260 640 C340 606 390 660 420 630"
)

/** Liniile de nivel din Main.dc.html (5 curbe olive la 7 %), întinse pe ecran; căile se refac doar la altă mărime. */
@Composable
internal fun TopoLines(modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val m = Matrix().apply { scale(size.width / 390f, size.height / 844f) }
            val paths = TopoPaths.map { PathParser().parsePathString(it).toPath().apply { transform(m) } }
            val stroke = Stroke(1.2f * density)
            val color = Accent2.copy(alpha = 0.07f)
            onDrawBehind { for (p in paths) drawPath(p, color, style = stroke) }
        }
    )
}

/**
 * Coloană cu conținut sus și acțiunea jos: pe ecrane înalte butonul stă lipit de marginea de jos (zona degetului),
 * pe cele mici totul derulează (nimic nu se taie). Cu [bottom] null, partea de jos lipsește cu tot cu spațiul ei.
 */
@Composable
internal fun TopBottomColumn(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
    gap: Dp = 14.dp,
    top: @Composable ColumnScope.() -> Unit,
    bottom: (@Composable ColumnScope.() -> Unit)?
) {
    BoxWithConstraints(modifier) {
        val minH = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minH)
                .padding(padding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap), content = top)
            if (bottom != null) Column(Modifier.padding(top = gap), verticalArrangement = Arrangement.spacedBy(gap), content = bottom)
        }
    }
}

/** Mânerul foilor de jos (40×4, #3A3D44). */
@Composable
internal fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Rule))
    }
}

/** Rând de foaie (60 dp): pictogramă/miniatură 44 + nume Barlow 20 + contor mono. */
@Composable
internal fun SheetRow(
    label: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    color: Color = TextPrimary
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(R8)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(label, style = cond(20, color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = mono(10, color = TextDim))
    }
}

/** Căsuța „Dosar nou” (44 dp, contur olive punctat, plus). */
@Composable
internal fun DashedPlus() {
    Canvas(Modifier.size(44.dp)) {
        val sw = 1.5.dp.toPx()
        drawRoundRect(
            Accent2, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(sw, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        )
        val c = center
        val l = 7.dp.toPx()
        drawLine(Accent2, Offset(c.x - l, c.y), Offset(c.x + l, c.y), 2.4.dp.toPx(), StrokeCap.Round)
        drawLine(Accent2, Offset(c.x, c.y - l), Offset(c.x, c.y + l), 2.4.dp.toPx(), StrokeCap.Round)
    }
}

private val CssEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * Pulsul pasului activ (`@keyframes forjaPulse`, 1,6 s ease-out): inelul iese 0 → 8 dp stingându-se, apoi revine
 * 8 → 0 aprinzându-se. Desenat în afara cercului (înainte de clip), ca box-shadow-ul din CSS.
 */
internal fun Modifier.pulseRing(active: Boolean, t: () -> Float, color: Color = Amber, radius: Dp, spread: Dp = 8.dp): Modifier =
    if (!active) this else drawBehind {
        val p = t()
        val s: Float
        val a: Float
        if (p < 0.5f) {
            val e = CssEaseOut.transform(p / 0.5f)
            s = e; a = 0.55f * (1f - e)
        } else {
            val e = CssEaseOut.transform((p - 0.5f) / 0.5f)
            s = 1f - e; a = 0.55f * e
        }
        val w = spread.toPx() * s
        if (w > 0.1f && a > 0.005f) drawCircle(color.copy(alpha = a), radius.toPx() + w / 2f, center, style = Stroke(w))
    }

/** Punctul de pe cercul unui inel, la fracțiunea dată (0 = sus). */
internal fun ringPoint(center: Offset, r: Float, fraction: Float): Offset {
    val a = (fraction * 2f * PI - PI / 2).toFloat()
    return Offset(center.x + r * cos(a), center.y + r * sin(a))
}
