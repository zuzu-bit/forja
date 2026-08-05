package com.forja.app.feature.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.topoBackground
import kotlinx.coroutines.delay

/** Splash „Camuflaj · Topografic": flacără peste undă, FORJA condensat, LIVE IT. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    LaunchedEffect(Unit) {
        delay(if (reduced) 600 else 3000)
        onDone()
    }
    val infinite = rememberInfiniteTransition(label = "load")
    val load by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "loadx"
    )

    Box(
        Modifier
            .fillMaxSize()
            .topoBackground(decor = true, intensity = 1.7f),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Flacăra — logo-ul ales, cu unda de apă dedesubt
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = "FORJA",
                tint = Accent2,
                modifier = Modifier.size(64.dp)
            )
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
            Text("FORJA", style = TitleSplash, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(150.dp).height(1.dp).background(Accent2.copy(alpha = 0.35f)))
            Spacer(Modifier.height(10.dp))
            Text("LIVE IT", style = monoLabel(11, 0.30f).copy(color = Accent2))
            Spacer(Modifier.height(34.dp))
            // Linia de încărcare
            Box(
                Modifier.width(96.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Surface2)
            ) {
                Box(
                    Modifier
                        .width(96.dp * (if (reduced) 1f else load))
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AccentGradient)
                )
            }
        }
    }
}
