@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.forja.app.feature.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.*
import kotlinx.coroutines.delay

/** Splash: bokeh cald, wordmark cu sweep de lumină (2,6s loop), auto-avans 3,2s. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(Unit) {
        delay(if (reduced) 600 else 3200)
        onDone()
    }
    val infinite = rememberInfiniteTransition(label = "sweep")
    val sweep by infinite.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepx"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .background(
                Brush.radialGradient(
                    listOf(Color(0x33FFB300), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(220f, 480f),
                    radius = 700f
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(Color(0x22FF7A00), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(820f, 1500f),
                    radius = 900f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Text("FORJA", style = TitleSplash, textAlign = TextAlign.Center)
                if (!reduced) {
                    Text(
                        "FORJA",
                        style = TitleSplash.copy(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    (sweep - 0.25f).coerceIn(0f, 1f) to Color.Transparent,
                                    sweep.coerceIn(0f, 1f) to Color(0xFFFFE0A3),
                                    (sweep + 0.25f).coerceIn(0f, 1f) to Color.Transparent
                                )
                            )
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer { alpha = 0.85f }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("REAL & VIU", style = monoLabel(11, 0.18f).copy(color = Accent2))
        }
    }
}
