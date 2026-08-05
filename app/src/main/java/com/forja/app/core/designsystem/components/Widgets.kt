package com.forja.app.core.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import kotlin.math.roundToInt

// Card standard FORJA
@Composable
fun ForjaCard(
    modifier: Modifier = Modifier,
    fill: Color = Surface1,
    stroke: Color = StrokeCardStrong,
    radius: Dp = Radii.card,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .padding(padding),
        content = content
    )
}

// Etichetă de secțiune mono UPPERCASE
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = TextDim) {
    Text(text.uppercase(), style = monoLabel(10, 0.14f).copy(color = color), modifier = modifier)
}

// Badge sursă date: EXACT · COD DE BARE / ESTIMAT / MANUAL — onestitate.
@Composable
fun SourceBadge(text: String, tone: Color = TextDim) {
    Box(
        Modifier
            .clip(ChipShape)
            .background(Surface2)
            .border(1.dp, StrokeCard, ChipShape)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(text.uppercase(), style = monoLabel(8, 0.12f).copy(color = tone))
    }
}

// Numeral-erou cu count-up (natural, overshoot mic)
@Composable
fun CountUpNumeral(
    target: Float,
    size: Int,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
    color: Color = TextPrimary,
    suffix: String = ""
) {
    val reduced = LocalReducedMotion.current
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val v by animateFloatAsState(
        targetValue = if (started || reduced) target else 0f,
        animationSpec = Springs.natural(), label = "countup"
    )
    val shown = if (reduced) target else v
    val text = if (decimals == 0) shown.roundToInt().toString()
    else String.format(java.util.Locale.ROOT, "%.${decimals}f", shown).replace('.', ',')
    Text(text + suffix, style = heroNumeral(size).copy(color = color), modifier = modifier)
}

// Inel de progres (scor somn, pauză antrenament, obiectiv)
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 110.dp,
    strokeWidth: Dp = 8.dp,
    track: Color = Color(0x1AFFFFFF),
    brush: Brush = AccentGradient,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    var started by remember { mutableStateOf(!animated) }
    LaunchedEffect(Unit) { started = true }
    val p by animateFloatAsState(if (started) progress.coerceIn(0f, 1f) else 0f, Springs.natural(), label = "ring")
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val arcSize = Size(size.width - sw, size.height - sw)
            val topLeft = Offset(sw / 2, sw / 2)
            drawArc(track, -90f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(brush, -90f, 360f * p, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

// Avatar cu inel (inel amber pentru „live"/selectat)
@Composable
fun Avatar(
    name: String,
    size: Dp = 44.dp,
    ring: Boolean = false,
    ringColor: Color = Accent2,
    live: Boolean = false,
    bg: Color = Surface2
) {
    val infinite = rememberInfiniteTransition(label = "live")
    val pulse by infinite.animateFloat(
        initialValue = 0.75f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse"
    )
    val initials = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    Box(
        Modifier
            .size(size)
            .then(
                if (ring) Modifier.border(
                    2.dp,
                    if (live) ringColor.copy(alpha = pulse) else ringColor,
                    CircleShape
                ) else Modifier
            )
            .padding(if (ring) 3.dp else 0.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials.ifEmpty { "?" },
            style = Body.copy(
                color = TextPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = (size.value / 2.6f).sp
            )
        )
    }
}

// Toast FORJA — scurt, uman, auto-dismiss 2,6s.
class ToastState {
    var message by mutableStateOf("")
        private set
    var key by mutableStateOf(0)
        private set
    fun show(msg: String) { message = msg; key++ }
    fun clear() { message = "" }
}

val LocalToast = staticCompositionLocalOf { ToastState() }

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.key) {
        if (state.message.isNotEmpty()) {
            kotlinx.coroutines.delay(2600)
            state.clear()
        }
    }
    AnimatedVisibility(
        visible = state.message.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically(Springs.natural()) { it } + fadeIn(),
        exit = slideOutVertically(Springs.natural()) { it } + fadeOut()
    ) {
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF2202127))
                .border(1.dp, StrokeCardStrong, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(state.message, style = BodyStrong.copy(fontSize = 13.sp), textAlign = TextAlign.Center)
        }
    }
}

// Dot „live"
@Composable
fun LiveDotBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(LiveDot)
    )
}
