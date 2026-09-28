package com.forja.app.feature.workout

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.music.Music
import com.forja.app.core.music.MusicSource
import com.forja.app.core.music.MusicStarter
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.delay

/** Sesiunea live: video demo, unghi frontal/lateral, serii mari, inel pauză 90s, auto-avans. */
@Composable
fun WorkoutLiveScreen(onExit: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val vm: WorkoutViewModel = viewModel(viewModelStoreOwner = activity)
    val live by vm.live.collectAsState()
    val toast = LocalToast.current

    LaunchedEffect(live.toastKey) {
        if (live.toast.isNotEmpty()) toast.show(live.toast)
    }
    LaunchedEffect(live.finished) {
        if (live.finished) {
            delay(700)
            onExit()
        }
    }

    // Cronometru sesiune
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(live.startedAt) {
        while (true) {
            elapsed = if (live.startedAt > 0) (System.currentTimeMillis() - live.startedAt) / 1000 else 0
            delay(1000)
        }
    }

    // Ecranul rămâne aprins cât ține sesiunea: între serii te uiți la demonstrație, iar coada muzicii merge sigur.
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }

    // Muzica: discul de pe video (cât faci seria) și banda de sub inelul pauzei.
    val context = LocalContext.current
    val music by vm.music.collectAsState()
    val track by Music.nowPlaying.collectAsState()
    val start by MusicStarter.state.collectAsState()
    val origin by MusicStarter.origin.collectAsState()
    val queue by MusicStarter.queue.collectAsState()
    var anchor by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(track) { anchor = android.os.SystemClock.elapsedRealtime() }
    val clock by produceState(android.os.SystemClock.elapsedRealtime(), track?.playing) {
        while (track?.playing == true) {
            value = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val art = track?.art
    val artBitmap = remember(art) { art?.asImageBitmap() }
    val position = track?.let { it.positionMs + if (it.playing) (clock - anchor).coerceAtLeast(0L) else 0L } ?: 0L
    val disc = discUi(start, origin == MusicSource.WORKOUT, track, artBitmap, position, queue != null)
    val showMusic = music.on || track != null || disc.phase != DiscPhase.IDLE
    val onDisc: () -> Unit = {
        when (disc.phase) {
            DiscPhase.NEEDS_TAP, DiscPhase.FAILED -> MusicStarter.tap(context)
            DiscPhase.IDLE -> vm.startMusicNow()
            DiscPhase.STARTING -> Unit
            DiscPhase.PLAYING, DiscPhase.PAUSED -> MusicStarter.toggle(context, MusicSource.WORKOUT) { vm.startMusicNow() }
        }
    }

    val ex = live.current
    if (ex == null) {
        Box(Modifier.fillMaxSize().background(Surface0), contentAlignment = Alignment.Center) {
            Text("Nicio sesiune pornită. Alege un plan.", style = Body)
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .verticalScroll(rememberScrollState())
    ) {
        // Video demo 392dp + unghi comutabil
        Box(Modifier.fillMaxWidth().height(392.dp)) {
            VideoSurface(
                url = if (live.angleFront) ex.videoFront else ex.videoSide,
                posterUrl = ex.thumb,
                modifier = Modifier.fillMaxSize()
            )
            BoxScopeBottomScrim()
            TopScrim()

            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SESIUNE LIVE · ${Fmt.durationMs(elapsed)}",
                    style = monoLabel(9, 0.14f).copy(color = Accent2)
                )
                OverVideoButton("Încheie", onClick = { vm.endEarly(); onExit() })
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (showMusic) 84.dp else 16.dp)
            ) {
                Text(
                    "EXERCIȚIUL ${live.exPos + 1} / ${live.exercises.size}",
                    style = monoLabel(9, 0.14f).copy(color = TextSecondary)
                )
                Spacer(Modifier.height(4.dp))
                Text(ex.name, style = TitleModule.copy(fontSize = 24.sp, lineHeight = 27.sp))
                Spacer(Modifier.height(10.dp))
                MonoButton(
                    text = if (live.angleFront) "UNGHI: FRONTAL · atinge" else "UNGHI: LATERAL · atinge",
                    onClick = { vm.toggleAngle() }
                )
            }

            if (showMusic) {
                VideoMusicDisc(
                    ui = disc,
                    onTap = onDisc,
                    onLongPress = { MusicStarter.openPlayer(context) },
                    onNext = { MusicStarter.next(context) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 14.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (!live.resting) {
            // Numerale mari: SERIA n/total · REPETĂRI · SARCINĂ — pe un singur rând, fără rupere.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Seria")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${live.setNo}", style = heroNumeral(42), maxLines = 1, softWrap = false)
                        Text(
                            "/${ex.sets}",
                            style = heroNumeral(20).copy(color = TextDim),
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    SectionLabel("Repetări")
                    Text("${ex.reps}", style = heroNumeral(42), maxLines = 1, softWrap = false)
                }
                Column(Modifier.weight(1.15f)) {
                    SectionLabel(ex.loadLabel)
                    Text(
                        ex.load,
                        style = heroNumeral(if (ex.load.length > 3) 26 else 42),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
        } else {
            // Inel pauză 90s (echivalentul dashoffset 339 din prototip)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val progress by animateFloatAsState(
                        targetValue = live.restLeft / 90f.coerceAtLeast(1f),
                        animationSpec = tween(1000), label = "rest"
                    )
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(124.dp)) {
                            val sw = 8.dp.toPx()
                            val arc = Size(size.width - sw, size.height - sw)
                            val tl = Offset(sw / 2, sw / 2)
                            drawArc(Color(0x1AFFFFFF), -90f, 360f, false, topLeft = tl, size = arc, style = Stroke(sw, cap = StrokeCap.Round))
                            drawArc(
                                brush = AccentGradient, startAngle = -90f,
                                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                                useCenter = false, topLeft = tl, size = arc,
                                style = Stroke(sw, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${live.restLeft}", style = heroNumeral(46))
                            Text("PAUZĂ — 90 S", style = monoLabel(8, 0.14f))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        SecondaryButton("+15 s", onClick = { vm.addRest() })
                        Spacer(Modifier.width(10.dp))
                        SecondaryButton("Sari pauza", onClick = { vm.skipRest() })
                    }
                }
            }
            if (showMusic) {
                Spacer(Modifier.height(16.dp))
                RestMusicStrip(
                    ui = disc,
                    onPrevious = { MusicStarter.previous(context) },
                    onToggle = onDisc,
                    onNext = { MusicStarter.next(context) },
                    onOpen = { MusicStarter.tap(context) },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            Spacer(Modifier.height(22.dp))
        }

        AnimatedVisibility(visible = !live.resting, enter = fadeIn(), exit = fadeOut()) {
            PrimaryButton(
                text = "Termină seria",
                onClick = { vm.finishSet() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        // Strip URMEAZĂ
        SectionLabel("Urmează", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        ForjaCard(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            padding = 10.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = live.next?.thumb
                        ?: "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 54.dp)
                        .clip(ThumbShape)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        live.next?.name ?: "Stretching și respirație · 5 min",
                        style = BodyStrong.copy(fontSize = 14.sp)
                    )
                    live.next?.let {
                        Text(
                            "${it.sets} ${if (it.sets == 1) "SERIE" else "SERII"} × ${it.reps} REP",
                            style = monoLabel(9, 0.10f).copy(color = TextSecondary)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
