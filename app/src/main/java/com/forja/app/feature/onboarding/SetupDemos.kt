package com.forja.app.feature.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.BodySmall
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.SleepBg
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.monoLabel
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotAvatar
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.PopIn
import com.forja.app.core.designsystem.components.ProgressBar
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// ════════════════════════════════════════════════════════════════
//  1. Notificări — 3 imbolduri apar în trepte
// ════════════════════════════════════════════════════════════════

@Composable
fun NotificationDemo(modifier: Modifier = Modifier) {
    val items = remember {
        listOf("Apă, soldat.", "Timp de pauză. O iei cu cap.", "Seria ta e aproape!")
    }
    var shown by remember { mutableIntStateOf(0) }
    val reduced = LocalReducedMotion.current
    if (!reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                shown = 0
                for (i in 1..3) { delay(900); shown = i }
                delay(2400)
            }
        }
    } else shown = 3
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items.forEachIndexed { i, msg ->
            if (i < shown) {
                PopIn(visible = true, delayMs = 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface1)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Mascot(state = MascotState.Talking, hat = MascotHat.Helmet, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(msg, style = BodySmall.copy(color = TextPrimary))
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  2. Harta orașului — grilă 5×5, punctul merge, zonele se aprind
// ════════════════════════════════════════════════════════════════

@Composable
fun CityMapDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "city")
    val idle = remember { mutableStateOf(0f) }
    val progress by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "walk"
    ) ?: idle)
    val p = if (reduced) 0.7f else progress
    val discovered = (p * 25f).toInt().coerceIn(0, 25)
    // poziția Casca pe grilă (același calcul ca în canvas, dar în dp)
    val cellDp = 180f / 5.4f
    val gapDp = cellDp * 0.12f
    val dotIdx = (p * 25f).toInt().coerceIn(0, 24)
    val markerX = (dotIdx % 5) * (cellDp + gapDp) + gapDp / 2f + cellDp / 2f - 7f
    val markerY = (dotIdx / 5) * (cellDp + gapDp) + gapDp / 2f + cellDp / 2f - 7f
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.TopStart) {
        Canvas(Modifier.size(180.dp)) {
            val cell = size.width / 5.4f
            val gap = cell * 0.12f
            for (row in 0 until 5) for (col in 0 until 5) {
                val idx = row * 5 + col
                val lit = idx < discovered
                val x = col * (cell + gap) + gap / 2
                val y = row * (cell + gap) + gap / 2
                drawRoundRect(
                    color = if (lit) Accent2 else Surface2,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    cornerRadius = CornerRadius(cell * 0.2f)
                )
            }
        }
            MascotAvatar(hat = MascotHat.Helmet, size = 14.dp, modifier = Modifier.offset(markerX.dp, markerY.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("${discovered}/25 zone descoperite", style = monoLabel(11, 0.1f).copy(color = TextDim))
    }
}

// ════════════════════════════════════════════════════════════════
//  3. Detox — stânga: telefon agitat, dreapta: studiu calmat
// ════════════════════════════════════════════════════════════════

@Composable
fun DetoxDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "detox")
    val idle = remember { mutableStateOf(0f) }
    val swap by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "swap"
    ) ?: idle)
    val appIndex = if (reduced) 0 else ((swap * 3).toInt() % 3)
    val appIcons = remember { listOf("📱", "🎮", "📸") }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Box(
            Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2)
                .graphicsLayer { alpha = 1f - swap * 0.5f },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(state = MascotState.Angry, hat = MascotHat.Helmet, size = 28.dp)
                Spacer(Modifier.height(4.dp))
                Text(appIcons[appIndex], fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("distragere", style = monoLabel(9, 0.08f).copy(color = TextDim))
            }
        }
        Box(
            Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Accent.copy(alpha = 0.15f))
                .graphicsLayer { alpha = 0.5f + swap * 0.5f },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(state = MascotState.Happy, hat = MascotHat.Helmet, size = 28.dp)
                Spacer(Modifier.height(4.dp))
                Text("📖", fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("concentrare", style = monoLabel(9, 0.08f).copy(color = Accent2))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  4. Somn — noapte, lună, unde sonore
// ════════════════════════════════════════════════════════════════

@Composable
fun SleepDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "sleep")
    val idle = remember { mutableStateOf(0f) }
    val wave by (transition?.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "wave"
    ) ?: idle)
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SleepBg)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFFE39A).copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(13.dp).clip(CircleShape).background(SleepBg))
                }
                Spacer(Modifier.width(8.dp))
                Mascot(state = MascotState.Idle, hat = MascotHat.Helmet, size = 26.dp)
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.size(width = 160.dp, height = 40.dp)) {
                val w = if (reduced) 1.5f else wave
                val path = Path().apply {
                    moveTo(0f, size.height / 2)
                    for (x in 0..size.width.toInt() step 2) {
                        val y = (size.height / 2 + sin((x / size.width) * 4.0 * PI + w.toDouble()) * size.height * 0.3).toFloat()
                        lineTo(x.toFloat(), y)
                    }
                }
                drawPath(path, color = Color(0xFF9DBFE8), style = Stroke(width = 2f, cap = StrokeCap.Round))
            }
            Spacer(Modifier.height(10.dp))
            Text("7h 20m somn · 3 sforăituri · 1 vorbire", style = BodySmall.copy(color = Color(0xFF7E93AC)))
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  5. Jurnal — 3 cadre de poze apar, bară de progres se umple
// ════════════════════════════════════════════════════════════════

@Composable
fun JournalDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "journal")
    val idle = remember { mutableStateOf(0f) }
    val p by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "fill"
    ) ?: idle)
    val v = if (reduced) 0.8f else p
    val captions = remember { listOf("12 mar", "15 mar", "18 mar") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Mascot(state = MascotState.Wink, hat = MascotHat.Helmet, size = 32.dp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            captions.forEachIndexed { i, date ->
                val visible = v > (i + 1) / 3.8f
                if (visible) {
                    PopIn(delayMs = i * 120L) {
                        Column(
                            Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface2)
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("📷", fontSize = 16.sp)
                            Text(date, style = monoLabel(7, 0.06f).copy(color = TextDim))
                        }
                    }
                } else {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Surface2))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(progress = v, modifier = Modifier.width(140.dp), height = 4.dp)
        Spacer(Modifier.height(6.dp))
        Text("${(v * 12).toInt()} poze organizate", style = monoLabel(10, 0.08f).copy(color = TextDim))
    }
}

// ════════════════════════════════════════════════════════════════
//  6. Fișiere — fișierele se sortează în subfoldere
// ════════════════════════════════════════════════════════════════

@Composable
fun FolderDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "folder")
    val idle = remember { mutableStateOf(0f) }
    val p by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "sort"
    ) ?: idle)
    val v = if (reduced) 0.8f else p
    val folders = remember { listOf("Poze", "Documente", "Alte") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            folders.forEachIndexed { i, name ->
                val fileHere = v > (i + 0.5f) / 3f
                Column(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (fileHere) Accent.copy(alpha = 0.15f) else Surface2)
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(if (fileHere) "📂" else "📁", fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(name, style = monoLabel(8, 0.06f).copy(color = if (fileHere) Accent2 else TextDim))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (v > 0.95f) {
            PopIn {
                Text("✓ Organizate", style = monoLabel(10, 0.08f).copy(color = Positive))
            }
        } else {
            Text("se sortează…", style = monoLabel(10, 0.08f).copy(color = TextDim))
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  7. Baterie — traseul continuă, ecranul se blochează, soarele răsare
// ════════════════════════════════════════════════════════════════

@Composable
fun BatteryDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "battery")
    val idle = remember { mutableStateOf(0f) }
    val p by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "walk"
    ) ?: idle)
    val v = if (reduced) 0.6f else p
    val sunUp = if (v > 0.7f) (v - 0.7f) / 0.3f else 0f
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(EmberHot.copy(alpha = sunUp))
                    .graphicsLayer { translationY = (1f - sunUp) * 30f },
                contentAlignment = Alignment.Center
            ) {
                Text("☀️", fontSize = 14.sp, modifier = Modifier.alpha(sunUp))
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.size(width = 140.dp, height = 36.dp)) {
                // curba de fundal
                val bg = Path().apply {
                    moveTo(0f, size.height * 0.7f)
                    cubicTo(size.width * 0.25f, size.height * 0.3f, size.width * 0.5f, size.height * 0.8f, size.width, size.height * 0.4f)
                }
                drawPath(bg, color = Surface2, style = Stroke(4f, cap = StrokeCap.Round))
                // punctul pe curbă (bezic cubic: P0..P3)
                val t = v
                val oneMinusT = 1f - t
                val cx = oneMinusT * oneMinusT * oneMinusT * 0f +
                        3 * oneMinusT * oneMinusT * t * 0.25f * size.width +
                        3 * oneMinusT * t * t * 0.5f * size.width +
                        t * t * t * size.width
                val cy = oneMinusT * oneMinusT * oneMinusT * size.height * 0.7f +
                        3 * oneMinusT * oneMinusT * t * size.height * 0.3f +
                        3 * oneMinusT * t * t * size.height * 0.8f +
                        t * t * t * size.height * 0.4f
                drawCircle(Accent2, radius = 5f, center = Offset(cx, cy))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (v > 0.4f && v < 0.7f) "ecran blocat — misiunea continuă"
                else if (v >= 0.7f) "soare — ziua e a ta, soldat"
                else "mărsură în desfășurare",
                style = monoLabel(9, 0.08f).copy(color = TextDim)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  8. Social — două mascote, ping, notă muzicală
// ════════════════════════════════════════════════════════════════

@Composable
fun SocialDemo(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = if (reduced) null else rememberInfiniteTransition(label = "social")
    val idle = remember { mutableStateOf(0f) }
    val ping by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "ping"
    ) ?: idle)
    val note by (transition?.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "note"
    ) ?: idle)
    val pv = if (reduced) 0.5f else ping
    val nv = if (reduced) 0.5f else note
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Mascot(state = MascotState.Happy, hat = MascotHat.Helmet, size = 44.dp)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .width(40.dp)
                    .height(2.dp)
                    .background(Accent2.copy(alpha = pv))
                    .graphicsLayer { scaleX = pv },
            )
            Spacer(Modifier.width(8.dp))
            Mascot(state = MascotState.Wink, hat = MascotHat.Helmet, size = 44.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "♪",
                fontSize = (16 + nv * 8).sp,
                color = Accent2,
                modifier = Modifier
                    .alpha(0.5f + nv * 0.5f)
                    .graphicsLayer { translationY = -nv * 8f }
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Camarazi găsiți: 2", style = monoLabel(10, 0.08f).copy(color = TextDim))
    }
}
