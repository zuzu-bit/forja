package com.forja.app.feature.sleep

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.core.util.Fmt

/** Somn — raport calm în albastrul nopții. Date reale din sesiunile tale. */
@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val active by app.db.sleepDao().activeSession().collectAsState(initial = null)
    val last by app.db.sleepDao().lastFinished().collectAsState(initial = null)
    val week by app.db.sleepDao().finishedSince(Fmt.startOfDayMillis(6)).collectAsState(initial = emptyList())
    val toast = LocalToast.current

    Column(
        Modifier
            .fillMaxSize()
            .background(SleepBg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 120.dp)
    ) {
        // Header video dimineață 252dp + inel scor
        Box(Modifier.fillMaxWidth().height(252.dp)) {
            VideoSurface(
                url = "https://v.ftcdn.net/11/26/44/56/700_F_1126445619_bJBEc25rOq3b1ofF41h2oJgHrEOy7kVy_ST.mp4",
                posterUrl = "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg",
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.2f to Color.Transparent,
                            0.7f to Color(0xB30B111C),
                            1f to Color(0xFF0B111C)
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(20.dp)
            ) {
                Text("Somn", style = TitleModule)
                Text("RAPORT DE DIMINEAȚĂ", style = monoLabel(9, 0.16f).copy(color = SleepRem))
            }

            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val score = last?.score ?: 0
                ProgressRing(
                    progress = score / 100f,
                    ringSize = 96.dp,
                    strokeWidth = 7.dp,
                    track = Color(0x2E7896BE),
                    brush = Brush.linearGradient(listOf(SleepDeep, SleepRem))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$score", style = heroNumeral(30))
                        Text(
                            when {
                                score >= 80 -> "ODIHNIT"
                                score >= 60 -> "DECENT"
                                score > 0 -> "OBOSIT"
                                else -> "—"
                            },
                            style = monoLabel(7, 0.14f).copy(color = SleepTextDim)
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    last?.let { s ->
                        val min = (((s.endAt ?: s.startAt) - s.startAt) / 60000).toInt()
                        Text(Fmt.durationHm(min), style = heroNumeral(30))
                        Text(
                            "${Fmt.clock(s.startAt)} → ${Fmt.clock(s.endAt ?: s.startAt)}",
                            style = monoLabel(9, 0.10f).copy(color = SleepTextDim)
                        )
                    } ?: Text(
                        "Prima noapte cu FORJA\nte așteaptă.",
                        style = Body.copy(color = SleepTextDim)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Buton pornire/oprire sesiune — fallback manual onest din handoff
        if (active != null) {
            ForjaCard(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                fill = SleepCard, stroke = SleepStroke
            ) {
                Text("Sesiune de somn activă", style = BodyStrong)
                Spacer(Modifier.height(2.dp))
                Text(
                    "De la ${Fmt.clock(active!!.startAt)} · mișcările se numără local, fără microfon.",
                    style = BodySmall.copy(color = SleepTextDim)
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "M-am trezit",
                    onClick = {
                        SleepTrackService.stop(context)
                        toast.show("Raportul se pregătește…")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            PrimaryButton(
                text = "Încep să dorm",
                onClick = {
                    SleepTrackService.start(context)
                    toast.show("Noapte bună. Lasă telefonul lângă tine.")
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        // Fazele somnului — bară stratificată
        SectionLabel("Fazele somnului", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        ForjaCard(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fill = SleepCard, stroke = SleepStroke
        ) {
            val s = last
            if (s == null || (s.deepMin + s.lightMin + s.remMin) == 0) {
                Text("Fazele apar după prima noapte înregistrată.", style = BodySmall.copy(color = SleepTextDim))
            } else {
                val total = (s.deepMin + s.lightMin + s.remMin).toFloat()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape)
                ) {
                    Box(Modifier.weight((s.deepMin / total).coerceAtLeast(0.01f)).fillMaxHeight().background(SleepDeep))
                    Box(Modifier.weight((s.lightMin / total).coerceAtLeast(0.01f)).fillMaxHeight().background(SleepLight))
                    Box(Modifier.weight((s.remMin / total).coerceAtLeast(0.01f)).fillMaxHeight().background(SleepRem))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PhaseLegend("Profund", Fmt.durationHm(s.deepMin), SleepDeep)
                    PhaseLegend("Ușor", Fmt.durationHm(s.lightMin), SleepLight)
                    PhaseLegend("REM", Fmt.durationHm(s.remMin), SleepRem)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Estimare din mișcare · ${s.movements} mișcări detectate",
                    style = monoLabel(8, 0.10f).copy(color = SleepTextDim)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Tendința săptămânii — 7 bare reale
        SectionLabel("Săptămâna ta", Modifier.padding(horizontal = 20.dp), color = SleepTextDim)
        Spacer(Modifier.height(10.dp))
        ForjaCard(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            fill = SleepCard, stroke = SleepStroke
        ) {
            val byDay = (0..6).map { ago ->
                val dayStart = Fmt.startOfDayMillis((6 - ago).toLong())
                val dayEnd = dayStart + 24 * 3600_000
                week.filter { it.startAt in dayStart until dayEnd }
                    .sumOf { (((it.endAt ?: it.startAt) - it.startAt) / 60000).toInt() }
            }
            val maxMin = (byDay.maxOrNull() ?: 0).coerceAtLeast(480)
            val avg = byDay.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                byDay.forEachIndexed { i, min ->
                    val isToday = i == 6
                    val h by animateFloatAsState(
                        (min.toFloat() / maxMin).coerceIn(0.04f, 1f),
                        Springs.natural(), label = "bar$i"
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .width(22.dp)
                                .fillMaxHeight(h)
                                .clip(CircleShape)
                                .background(
                                    if (isToday) Brush.verticalGradient(listOf(Accent, Accent2))
                                    else Brush.verticalGradient(listOf(SleepLight, SleepDeep))
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        val dayIdx = (java.time.LocalDate.now().dayOfWeek.value - 1 - (6 - i) + 7) % 7
                        Text(
                            if (isToday) "azi" else Fmt.dayLetters[dayIdx],
                            style = monoLabel(8, 0.08f).copy(color = if (isToday) Accent2 else SleepTextDim)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (avg > 0) "media ${Fmt.durationHm(avg)}" else "încă fără date",
                style = monoLabel(8, 0.10f).copy(color = SleepTextDim)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "FORJA nu pune diagnostice. Dacă te trezești des obosit, vorbește cu un medic — ai istoricul în aplicație.",
            style = BodyTiny.copy(color = SleepTextDim),
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
private fun PhaseLegend(name: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(name, style = BodyTiny.copy(color = TextSecondary))
            Text(value, style = BodyStrong.copy(fontSize = 13.sp))
        }
    }
}
