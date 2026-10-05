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
import kotlinx.coroutines.launch

/**
 * „Respiră" — respirație pătrată (4-4-4-4), calmantă. Nicio legătură cu Focus/Detox:
 * un loc doar al tău, când ai nevoie să te așezi câteva minute.
 * Fiecare sesiune de cel puțin 10 s se scrie în Room (`breath_sessions`) la Oprește sau la plecarea din ecran;
 * cu contractul v4 ajunge pe site (FocusMirror → breath/{zi}).
 */
@Composable
fun BreathScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { com.forja.app.ForjaApp.from(context) }
    var startedAt by remember { mutableStateOf(0L) }
    fun record(completed: Boolean) {
        val start = startedAt
        val elapsed = System.currentTimeMillis() - start
        startedAt = 0L
        if (start == 0L || elapsed < 10_000L) return
        app.appScope.launch {
            try {
                app.db.breathSessionDao().insert(
                    com.forja.app.core.data.db.BreathSessionEntity(
                        startAt = start, endAt = start + elapsed, pattern = "4-4-4-4",
                        cycles = (elapsed / 16_000L).toInt(), durationS = (elapsed / 1000L).toInt(), completed = completed
                    )
                )
            } catch (_: Exception) { }
        }
    }
    // fazele: inspiră, ține, expiră, ține — câte 4 secunde
    val phases = listOf("Inspiră" to 4000, "Ține" to 4000, "Expiră" to 4000, "Ține" to 4000)
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(0) }
    var elapsedMs by remember { mutableStateOf(0L) }
    // Plecarea din ecran în timpul unei sesiuni o scrie și ea (neterminată).
    DisposableEffect(Unit) { onDispose { if (startedAt != 0L) record(false) } }
    // „Hei FORJA, respiră cu mine”: exercițiul pornește singur, la deschidere sau pe loc dacă ecranul e deja deschis.
    val startTick by BreathLinks.start.collectAsState()
    LaunchedEffect(startTick) {
        if (startTick > 0) {
            BreathLinks.consumed()
            if (!running) { elapsedMs = 0L; startedAt = System.currentTimeMillis(); running = true }
        }
    }

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

    Box(Modifier.fillMaxSize()) {
        com.forja.app.core.designsystem.components.VideoSurface(
            url = "https://v.ftcdn.net/04/99/13/67/700_F_499136769_X4Pfv9UFpmLtXcXu0JLdSo80FTPH2BGx_ST.mp4",
            posterUrl = "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg",
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Color(0xA60A0A0B)))
    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = 24.dp).padding(bottom = 120.dp),   // lasă loc barei de jos
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        ModuleHeader(
            stamp = "REPAUS",
            title = "Respiră",
            order = "Urmează cercul: inspiră, ține, expiră, ține. Patru secunde fiecare.",
            titleStyle = TitleModule.copy(fontSize = 26.sp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.weight(1f))

        Box(
            Modifier.size(200.dp).scale(scale).clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x333B6FB0), Color(0x1F6F855A), Color(0x00000000))
                    )
                )
                .border(1.5.dp, Color(0x666F855A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) phases[phase].first else "Gata",
                style = TitleModule.copy(fontSize = 30.sp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            if (running) "%d:%02d".format((elapsedMs / 1000) / 60, (elapsedMs / 1000) % 60) else "Apasă Începe. Restul lumii așteaptă.",
            style = if (running) heroNumeral(28) else BodySmall.copy(color = TextDim),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        if (running) {
            SecondaryButton("Oprește", onClick = { running = false; record(true) }, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Începe", onClick = { elapsedMs = 0L; startedAt = System.currentTimeMillis(); running = true }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(14.dp))
        WarmQuote(
            Tone.ofDay(Tone.breath),
            modifier = Modifier.fillMaxWidth(),
            color = TextSecondary,
            byColor = TextDim2,
            fontSize = 15
        )
        Spacer(Modifier.height(24.dp))
    }
    }
}
