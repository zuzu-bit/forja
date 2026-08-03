package com.forja.app.feature.breath

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import kotlinx.coroutines.delay

/**
 * „Respiră" — respirație pătrată (4-4-4-4), calmantă. Nicio legătură cu Focus/Detox:
 * un loc doar al tău, când ai nevoie să te așezi câteva minute.
 */
@Composable
fun BreathScreen() {
    // fazele: inspiră, ține, expiră, ține — câte 4 secunde
    val phases = listOf("Inspiră" to 4000, "Ține" to 4000, "Expiră" to 4000, "Ține" to 4000)
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(0) }
    var elapsedMs by remember { mutableStateOf(0L) }

    LaunchedEffect(running) {
        if (running) {
            phase = 0
            while (running) {
                val dur = phases[phase].second.toLong()
                delay(dur)
                elapsedMs += dur
                phase = (phase + 1) % phases.size
            }
        }
    }

    val expanded = phase == 0 || phase == 1        // după inspiră stă umflat; după expiră stă mic
    val target = if (!running) 0.9f else if (expanded) 1.15f else 0.72f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (running) phases[phase].second else 800, easing = LinearEasing),
        label = "breath"
    )

    Column(
        Modifier.fillMaxSize().background(Surface0).statusBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Respiră", style = TitleModule.copy(fontSize = 26.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            "Câteva minute doar pentru tine. Urmează cercul: inspiră, ține, expiră, ține.",
            style = BodySmall.copy(color = TextSecondary),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        Box(
            Modifier.size(260.dp).scale(scale).clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x333B6FB0), Color(0x1FFFB300), Color(0x00000000))
                    )
                )
                .border(1.5.dp, Color(0x66FFB300), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) phases[phase].first else "Gata?",
                style = TitleModule.copy(fontSize = 30.sp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            if (running) "%d:%02d".format((elapsedMs / 1000) / 60, (elapsedMs / 1000) % 60) else "apasă Începe și lasă restul lumii pe pauză",
            style = if (running) heroNumeral(28) else BodySmall.copy(color = TextDim),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        if (running) {
            SecondaryButton("Oprește", onClick = { running = false }, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Începe", onClick = { elapsedMs = 0L; running = true }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Respirația pătrată liniștește sistemul nervos. Fă-o oriunde, oricând — e mereu aici pentru tine.",
            style = BodyTiny.copy(color = TextDim2),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}
