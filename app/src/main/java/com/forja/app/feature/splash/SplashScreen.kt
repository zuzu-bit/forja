package com.forja.app.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.BoxScopeBottomScrim
import com.forja.app.core.designsystem.components.EmberField
import com.forja.app.core.designsystem.components.PopIn
import com.forja.app.core.designsystem.components.PulseGlow
import com.forja.app.core.designsystem.components.Reveal
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.TypewriterText
import com.forja.app.core.designsystem.components.topoBackground
import com.forja.app.core.media.Media
import kotlinx.coroutines.delay

/** Splash „Forjă”: topografie, jar cu scântei, flacără care pulsează, ordin de zi bătut la mașină. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(Unit) {
        delay(if (reduced) 500 else 2600)
        onDone()
    }

    // Linia de încărcare: determinată — la ieșirea din splash se citește „gata”.
    val load = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) load.snapTo(1f)
        else load.animateTo(1f, tween(2200, easing = LinearOutSlowInEasing))
    }

    val backdrop = remember { Media.mediaUrl("snd_fire.jpg") }

    Box(
        Modifier
            .fillMaxSize()
            .topoBackground(decor = false, intensity = 1.4f)
    ) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.22f)
            )
            BoxScopeBottomScrim()
        }
        EmberField(Modifier.fillMaxSize(), count = 48)

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Flacăra — logo-ul, cu puls cald și unda de apă dedesubt
            PopIn(delayMs = 100) {
                PulseGlow(color = EmberWarm, radius = 70.dp) {
                    Icon(
                        Icons.Filled.LocalFireDepartment,
                        contentDescription = "FORJA",
                        tint = Accent2,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.width(84.dp).height(14.dp)) {
                val w = size.width
                val a = size.height * 0.32f
                val mid = size.height * 0.5f
                val p = Path()
                p.moveTo(0f, mid)
                p.cubicTo(w * 0.18f, mid - a, w * 0.32f, mid + a, w * 0.5f, mid)
                p.cubicTo(w * 0.68f, mid - a, w * 0.82f, mid + a, w, mid)
                drawPath(p, Accent2, style = Stroke(width = 2.2f * density))
                drawLine(
                    Accent2.copy(alpha = 0.55f),
                    Offset(w * 0.16f, mid + a * 1.9f), Offset(w * 0.84f, mid + a * 1.9f),
                    strokeWidth = 1.6f * density
                )
            }
            Spacer(Modifier.height(14.dp))
            Reveal(index = 1) {
                Text("FORJA", style = TitleSplash, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            Reveal(index = 2) {
                StampLabel("ORDIN DE ZI · v4.0")
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(150.dp).height(1.dp).background(Accent2.copy(alpha = 0.35f)))
            Spacer(Modifier.height(12.dp))
            TypewriterText(
                "LIVE IT. DISCIPLINĂ. FORJĂ.",
                style = monoLabel(11, 0.30f).copy(color = Accent2),
                startDelayMs = 700,
                charDelayMs = 30
            )
            Spacer(Modifier.height(34.dp))
            // Linia de încărcare — desenată în faza de desen (fără recompunere la fiecare cadru)
            Box(
                Modifier.width(96.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Surface2)
                    .drawBehind {
                        val w = size.width * load.value.coerceIn(0f, 1f)
                        if (w > 0f) {
                            drawRoundRect(
                                AccentGradient,
                                size = Size(w, size.height),
                                cornerRadius = CornerRadius(size.height / 2f)
                            )
                        }
                    }
            )
        }
    }
}
