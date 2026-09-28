package com.forja.app.feature.inventory

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.CoachMarks
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Inventory
import com.forja.app.core.music.MediaKind
import com.forja.app.core.music.Music
import com.forja.app.core.music.MusicIcons
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicSource
import com.forja.app.core.music.MusicStarter
import com.forja.app.core.music.StartState
import com.forja.app.core.music.TrackKey
import com.forja.app.core.music.TrackRef
import com.forja.app.core.music.Want
import kotlinx.coroutines.delay

/** Pornirea, cum o vede ecranul: nimic, „Pornește…”, „Deschide Spotify”, „Nu a pornit.”. */
sealed interface StartUi {
    data object Idle : StartUi
    data object Starting : StartUi
    /** Următorul pas aduce playerul în față: îl face o atingere. */
    data class NeedsTap(val label: String) : StartUi
    /** Nu a pornit; butonul deschide playerul. */
    data class Failed(val label: String) : StartUi
}

/** Sesiunea de alături care nu e muzică (carte audio, podcast, video): „Reia” sau „Pauză”. */
data class OtherUi(val title: String, val kind: MediaKind, val playing: Boolean)

/** Starea S3c, fără Android (testabilă). */
data class MusicUiState(
    val pill: PillState? = null,
    /** Progresul inventarului (inelul din jurul copertei). */
    val ring: Float = 0f,
    val access: Boolean = true,
    val title: String? = null,
    val artist: String? = null,
    val app: String? = null,
    val art: ImageBitmap? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val top: Boolean = false,
    val stopAtEnd: Boolean = true,
    val onMap: Boolean = true,
    /** Fără acces: se aude un player media acum (starea sistemului, nu un steag optimist). */
    val musicActive: Boolean = false,
    val start: StartUi = StartUi.Idle,
    val other: OtherUi? = null,
    /** Titlul piesei tale de top (7 zile), pentru cipul „TOP 1” (un singur rând, fără artist: nu se taie în cuvânt). */
    val topChip: String? = null
)

data class MusicActions(
    val onPill: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onAskAccess: () -> Unit = {},
    val onStopAtEnd: (Boolean) -> Unit = {},
    val onMap: (Boolean) -> Unit = {},
    val onTop: () -> Unit = {},
    val onOther: () -> Unit = {},
    /** „Deschide Spotify” / „Deschide playerul”. */
    val onOpen: () -> Unit = {},
    /** „Încearcă din nou”: aceeași intenție (Reia pe carte reia cartea). */
    val onRetry: () -> Unit = {}
)

/** Ghidajul primei vizite: ce face butonul, spus o dată. */
private val MUZICA_STEPS = listOf(
    CoachStep("muzica.porneste", "Pornește muzica: reia muzica ta; altfel piesa ta de top."),
    CoachStep("muzica.acces", "Cu „Arată melodia”, FORJA vede playerul și pornește mai sigur.")
)

/** Starea motorului, pentru butoanele ecranului (doar pornirile cerute de aici). */
internal fun StartState.toUi(): StartUi = when (this) {
    is StartState.Starting -> StartUi.Starting
    is StartState.NeedsTap -> StartUi.NeedsTap(if (step.pkg == MusicKind.SPOTIFY) "Deschide Spotify" else "Deschide playerul")
    // Același nume ca la NeedsTap când pasul de deschidere e cunoscut (butonul deschide chiar playerul acela).
    is StartState.Failed -> StartUi.Failed(if (open?.pkg == MusicKind.SPOTIFY) "Deschide Spotify" else "Deschide playerul")
    else -> StartUi.Idle
}

/**
 * S3c — „Când tace muzica, inventarul e gata.”: coperta în inelul progresului, piesa, comenzile sesiunii media,
 * „Oprește la final” și „Pe hartă”. Pornirea trece prin motorul verificat (MusicStarter): Play reia exact piesa
 * arătată, „Pornește muzica” reia muzica ta sau piesa ta de top, o carte audio nu e niciodată eroul.
 */
@Composable
fun InventoryMusicScreen(onOpenInventory: (InvPage) -> Unit) {
    val context = LocalContext.current
    val progress by Inventory.progress.collectAsState()
    val track by Music.nowPlaying.collectAsState()
    val otherTrack by Music.other.collectAsState()
    val audible by Music.audible.collectAsState()
    val start by MusicStarter.state.collectAsState()
    val origin by MusicStarter.origin.collectAsState()
    val stopAtEnd by remember { Music.stopWhenDoneFlow(context) }.collectAsState(initial = true)
    val onMap by remember { Music.shareOnMapFlow(context) }.collectAsState(initial = true)

    // Accesul la sesiunile media: reverificat la întoarcerea din Setări.
    var access by remember { mutableStateOf(Music.hasAccess(context)) }
    LaunchedEffect(access) { Music.ensureStarted(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = Music.hasAccess(context) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Ecranul închis: o pornire cerută de aici se oprește (muzica deja pornită rămâne).
    DisposableEffect(Unit) { onDispose { MusicStarter.cancel(MusicSource.INVENTORY) } }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = Music.hasAccess(context)
    }
    // Poziția piesei, extrapolată între anunțurile sesiunii.
    val t = if (access) track else null
    var anchor by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(t) { anchor = SystemClock.elapsedRealtime() }
    val now by produceState(SystemClock.elapsedRealtime(), t?.playing) {
        while (t?.playing == true) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val position = t?.let {
        val p = it.positionMs + if (it.playing) (now - anchor).coerceAtLeast(0L) else 0L
        if (it.durationMs > 0) p.coerceIn(0L, it.durationMs) else p
    } ?: 0L
    val topRef by produceState<TrackRef?>(null, t?.title, t?.artist) { value = Music.topRef(context) }
    val art = t?.art
    val artBitmap = remember(art) { art?.asImageBitmap() }
    val pill = pillStateOf(progress)
    val ring = when (progress?.stage) {
        InvStage.Ready, InvStage.Done -> 1f
        null -> 0f
        else -> progress?.let { it.done / it.total.coerceAtLeast(1).toFloat() } ?: 0f
    }
    val other = if (access) otherTrack else null

    CoachMarks(screen = "inventar_muzica", steps = MUZICA_STEPS) {
        Box(Modifier.fillMaxSize().background(Surface0).statusBarsPadding().navigationBarsPadding()) {
            MusicWaitContent(
                MusicUiState(
                    pill = pill,
                    ring = ring,
                    access = access,
                    title = t?.title,
                    artist = t?.artist,
                    app = t?.app,
                    art = artBitmap,
                    playing = t?.playing ?: (!access && audible),
                    positionMs = position,
                    durationMs = t?.durationMs ?: 0L,
                    top = t != null && topRef?.let { TrackKey.of(it.title, it.artist) == TrackKey.of(t.title, t.artist) } == true,
                    stopAtEnd = stopAtEnd,
                    onMap = onMap,
                    musicActive = !access && audible,
                    start = if (origin == MusicSource.INVENTORY) start.toUi() else StartUi.Idle,
                    other = other?.let { OtherUi(it.title, it.kind, it.playing) },
                    topChip = topRef?.title
                ),
                MusicActions(
                    onPill = { onOpenInventory(if (progress?.stage == InvStage.Ready) InvPage.Folders else InvPage.Run) },
                    onPrevious = { MusicStarter.previous(context) },
                    onToggle = { MusicStarter.toggle(context, MusicSource.INVENTORY) },
                    onNext = { MusicStarter.next(context) },
                    onStart = { MusicStarter.start(context, Want.MyMusic, MusicSource.INVENTORY, tap = true) },
                    onTop = { MusicStarter.start(context, Want.Top, MusicSource.INVENTORY, tap = true) },
                    onOther = {
                        val o = Music.other.value
                        when {
                            o == null -> Unit
                            o.playing -> MusicStarter.pause(context, o.id)
                            o.id != null -> MusicStarter.start(context, Want.Resume(o.id), MusicSource.INVENTORY, tap = true)
                        }
                    },
                    onOpen = { MusicStarter.tap(context) },
                    onRetry = { MusicStarter.retry(context, MusicSource.INVENTORY) },
                    onAskAccess = {
                        try { accessLauncher.launch(Music.accessIntent(context)) } catch (_: Exception) {
                            try { accessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
                        }
                    },
                    onStopAtEnd = { Music.stopWhenDone(it) },
                    onMap = { Music.shareOnMap(context, it) }
                )
            )
        }
    }
}

/**
 * S3c (Muzica.dc.html): fundal radial cald, pastila + egalizatorul mic, eroul, piesa, comenzile, comutatoarele.
 * Pe ecrane joase (S23: 696 dp utili) eroul se strânge la 200 dp, ca totul să încapă fără derulare. Cu piesa arătată,
 * fiecare rând în plus sub comenzi („Nu a pornit.” / „Deschide Spotify”, cartea care cântă) ia 24 dp din inel, ca
 * rândul de jos să nu se lipească de comutatoare (și pe S23, și pe 393 × 851).
 */
@Composable
fun MusicWaitContent(state: MusicUiState, actions: MusicActions, modifier: Modifier = Modifier) {
    val known = state.access && state.title != null
    val starting = state.start == StartUi.Starting
    val reduced = LocalReducedMotion.current
    val extraRows = if (!known) 0 else listOf(
        state.start is StartUi.Failed || state.start is StartUi.NeedsTap,
        state.other?.playing == true
    ).count { it }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .drawBehind {
                // radial-gradient(120% 70% at 50% 30%, #1A1712 0%, #0A0A0B 62%)
                val c = Offset(size.width / 2f, size.height * 0.3f)
                val rx = size.width * 1.2f
                val ry = size.height * 0.7f
                withTransform({ scale(1f, ry / rx, pivot = c) }) {
                    drawCircle(Brush.radialGradient(0f to MusicGlow, 0.62f to Surface0, center = c, radius = rx), radius = rx, center = c)
                }
            }
    ) {
        val compact = maxHeight < 760.dp
        // Pe S23, cu piesa arătată, coloana e plină: spații de 10 dp (altfel 12; pe ecranele înalte 14).
        val gap = when {
            !compact -> 14.dp
            known -> 10.dp
            else -> 12.dp
        }
        val ringTarget = (if (compact) 200.dp else 300.dp) - 24.dp * extraRows
        val ringSize by animateDpAsState(ringTarget, if (reduced) snap() else tween(300), label = "musicRingSize")
        TopBottomColumn(
            padding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = if (compact) 20.dp else 24.dp),
            gap = gap,
            top = {
                WaitHeader(state.pill, actions.onPill) { EqualizerMini(state.playing || starting) }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    CoverRing(state, ringSize)
                }
                if (known) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.top) TopTag()
                        Text(state.title ?: "", style = cond(34, 36), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        val meta = listOfNotNull(state.artist?.takeIf { it.isNotBlank() }, state.app).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = body(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TrackBar(state.positionMs, state.durationMs)
                    Controls(state.playing, busy = starting, actions)
                    (state.start as? StartUi.Failed)?.let { FailLine("Nu a pornit.", it.label, actions.onOpen) }
                    // Pornirea a trezit playerul (piesa lui e arătată, pe pauză), iar pasul următor aduce playerul în față.
                    (state.start as? StartUi.NeedsTap)?.let { FailLine(null, it.label, actions.onOpen) }
                    state.other?.takeIf { it.playing }?.let { OtherRow(it, actions.onOther) }
                    if (!state.access) AccessLink(actions.onAskAccess)
                } else if (!state.access && state.musicActive) {
                    // Fără acces se aude ceva: pauză / înainte / înapoi prin tastele media (Play nu trimite niciodată pauză).
                    Controls(playing = true, busy = false, actions)
                    AccessLink(actions.onAskAccess)
                } else {
                    StartArea(state.start, actions)
                    if (state.start == StartUi.Idle) state.topChip?.let { TopChip(it, actions.onTop) }
                    state.other?.let { OtherRow(it, actions.onOther) }
                    if (!state.access) AccessLink(actions.onAskAccess)
                }
                TogglesCard(state, actions, compact)
            },
            bottom = {
                Text(
                    "Când tace muzica, inventarul e gata.",
                    style = body(14),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

/**
 * Inelul (progresul inventarului, punct amber la capăt) și coperta care bate pe ritm; la pornire, un arc care se rotește.
 * Coperta e rotundă, 82 % din inel (ca discul din Antrenament): un pătrat de 244/300 își scotea colțurile peste inel și
 * ascundea arcul și punctul. Raza copertei e 41 % din inel, aura punctului începe la 43,7 %: rămâne loc liber
 * (19 dp până la inel pe 300 dp, 12 dp pe 200 dp), iar inelul se desenează oricum deasupra.
 */
@Composable
private fun CoverRing(state: MusicUiState, ringSize: Dp) {
    val reduced = LocalReducedMotion.current
    val p by animateFloatAsState(state.ring.coerceIn(0f, 1f), if (reduced) snap() else tween(700), label = "musicRing")
    val starting = state.start == StartUi.Starting
    val inf = if (!reduced) rememberInfiniteTransition(label = "music") else null
    val glow = inf?.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")
    val beat = if (state.playing) inf?.animateFloat(1f, 1.018f, infiniteRepeatable(tween(250), RepeatMode.Reverse), label = "beat") else null
    val spin = if (starting) inf?.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "spin") else null
    val known = state.access && state.title != null
    val cover = ringSize * 0.82f
    Box(Modifier.size(ringSize).semantics { contentDescription = "Progresul inventarului, ${(p * 100).toInt()} la sută" }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(cover)
                .graphicsLayer {
                    val s = beat?.value ?: 1f
                    scaleX = s; scaleY = s
                }
                .shadow(40.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(MediaBg),
            contentAlignment = Alignment.Center
        ) {
            val art = state.art
            if (art != null && known) {
                Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                // Fără muzică arătată: egalizatorul (nu coperta unei cărți). În repaus, cu bare fixe.
                Box(Modifier.graphicsLayer { val k = 1.6f * (cover / 244.dp); scaleX = k; scaleY = k }) {
                    Equalizer5(animate = state.playing || starting)
                }
            }
        }
        // Inelul, peste copertă și peste umbra ei: arcul și punctul amber nu sunt acoperite niciodată.
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 300f
            val r = 144f * u
            val sw = 4f * u
            drawCircle(W08, r, center, style = Stroke(sw))
            if (starting) {
                // „Pornește…”: un arc olive care se rotește pe inel (static sub mișcare redusă).
                val a = spin?.value ?: 300f
                drawArc(Accent2.copy(alpha = 0.55f), a - 90f, 42f, false, topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (p > 0f) {
                drawArc(Amber, -90f, 360f * p, false, topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
                val dot = ringPoint(center, r, p)
                drawCircle(Amber.copy(alpha = 0.18f), 13f * u, dot)
                drawCircle(Amber.copy(alpha = glow?.value ?: 1f), 7f * u, dot)
            }
        }
    }
}

@Composable
private fun TopTag() {
    Box(
        Modifier
            .height(22.dp)
            .clip(R4)
            .background(Amber.copy(alpha = 0.10f))
            .border(1.dp, Amber.copy(alpha = 0.5f), R4)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("TOP 1 · 7 ZILE", style = mono(10, 0.16f, color = Amber, bold = true))
    }
}

@Composable
private fun TrackBar(positionMs: Long, durationMs: Long) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(TrackBar)) {
            if (f > 0f) Box(Modifier.fillMaxWidth(f).height(4.dp).clip(CircleShape).background(TextPrimary))
        }
        if (durationMs > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtClock(positionMs), style = mono(10, color = TextDim))
                Text(fmtClock(durationMs), style = mono(10, color = TextDim))
            }
        }
    }
}

/**
 * ⏮ ⏯ ⏭. Discul arată Pauză doar când sesiunea chiar cântă; cât pornește, un arc se rotește în jurul lui. Rândul are
 * mereu înălțimea discului (80 dp): arcul de 92 dp iese în afară fără să mute ⏮ ⏭ de sub deget.
 */
@Composable
private fun Controls(playing: Boolean, busy: Boolean, actions: MusicActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(MusicIcons.Previous, "Piesa anterioară", actions.onPrevious)
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .then(if (busy) Modifier else Modifier.pressable(actions.onToggle))
                    .size(80.dp)
                    .shadow(30.dp, CircleShape, ambientColor = Amber, spotColor = Amber)
                    .clip(CircleShape)
                    .background(TextPrimary)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (playing) "Pauză" else "Pornește"
                        stateDescription = when {
                            busy -> "pornește"
                            playing -> "cântă"
                            else -> "oprită"
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) MusicIcons.Pause else MusicIcons.Play, null, tint = Surface0.copy(alpha = if (busy) 0.35f else 1f), modifier = Modifier.size(30.dp))
            }
            if (busy) Spinner(Modifier.requiredSize(92.dp), Amber, 3.dp)
        }
        GhostButton(MusicIcons.Next, "Piesa următoare", actions.onNext)
    }
}

@Composable
private fun GhostButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 52.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).pressable(onClick).semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
    }
}

/** Un arc care se rotește (sub mișcare redusă: fix). */
@Composable
private fun Spinner(modifier: Modifier, color: Color, stroke: Dp) {
    val reduced = LocalReducedMotion.current
    val a = if (!reduced) {
        rememberInfiniteTransition(label = "spinner").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinnerA").value
    } else 300f
    Canvas(modifier) {
        val sw = stroke.toPx()
        drawArc(color, a - 90f, 70f, false, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/**
 * Butonul mare când nu e nicio muzică arătată: „Pornește muzica” → „Pornește…” (ocupat) → „Deschide Spotify” (o atingere
 * face saltul) sau „Nu a pornit.” + „Deschide Spotify” / „Deschide playerul”.
 */
@Composable
private fun StartArea(start: StartUi, actions: MusicActions) {
    when (start) {
        StartUi.Idle -> StartButton("Pornește muzica", busy = false, stateText = null, onClick = actions.onStart, modifier = Modifier.coachTarget("muzica.porneste"))
        StartUi.Starting -> StartButton("Pornește…", busy = true, stateText = "pornește", onClick = {})
        is StartUi.NeedsTap -> StartButton(start.label, busy = false, stateText = null, onClick = actions.onOpen, icon = MusicIcons.Open)
        is StartUi.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nu a pornit.", style = body(15, TextSecondary), textAlign = TextAlign.Center)
            StartButton(start.label, busy = false, stateText = "nu a pornit", onClick = actions.onOpen, icon = MusicIcons.Open)
            Text(
                "Încearcă din nou",
                style = body(14, Accent2),
                modifier = Modifier
                    .pressable(actions.onRetry, haptic = false)
                    .clip(R6)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            )
        }
    }
}

/** Butonul principal al ecranului (58 dp, gradient olive, Barlow 22), cu roată de încărcare când e ocupat. */
@Composable
private fun StartButton(
    label: String,
    busy: Boolean,
    stateText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier
            .then(if (busy) Modifier else Modifier.pressable(onClick))
            .fillMaxWidth()
            .height(58.dp)
            .clip(R8)
            .background(CtaBrush)
            .semantics {
                role = Role.Button
                if (stateText != null) stateDescription = stateText
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val a = if (busy) 0.8f else 1f
        if (busy) {
            Spinner(Modifier.size(18.dp), OnAccent, 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = cond(22, tracking = 0.04f, color = OnAccent.copy(alpha = a)), maxLines = 1)
        if (icon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, tint = OnAccent.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Cipul „TOP 1”: piesa ta cea mai ascultată în 7 zile, pornită direct (autoplay-ul playerului continuă). Doar titlul,
 * pe un rând (music-start.md §6.6): cu artistul alături, pe 360 dp rândul tăia numele în mijlocul unui cuvânt.
 */
@Composable
private fun TopChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick, scaleDown = 0.98f)
            .fillMaxWidth()
            .height(48.dp)
            .clip(R8)
            .border(1.dp, W12, R8)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Piesa ta de top: $label" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.height(20.dp).clip(R4).background(Amber.copy(alpha = 0.10f)).border(1.dp, Amber.copy(alpha = 0.5f), R4).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("TOP 1", style = mono(10, 0.14f, color = Amber, bold = true))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = body(14, TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(MusicIcons.Play, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

/** Rândul subțire pentru cartea audio / podcastul / videoul de alături: iconița, titlul, „REIA” (sau „PAUZĂ”). */
@Composable
private fun OtherRow(other: OtherUi, onClick: () -> Unit) {
    val verb = if (other.playing) "PAUZĂ" else "REIA"
    Row(
        Modifier
            .pressable(onClick, scaleDown = 0.98f)
            .fillMaxWidth()
            .height(48.dp)
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W08, R8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = (if (other.playing) "Pauză: " else "Reia: ") + other.title
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(MusicIcons.of(other.kind), null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(other.title, style = body(14, TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(verb, style = mono(10, 0.16f, color = Accent2, bold = true))
    }
}

/**
 * Sub comenzi: „Nu a pornit.” și deschiderea playerului (Play pe piesa arătată n-a pornit), sau doar „Deschide
 * Spotify” (pasul următor aduce playerul în față și îl face o atingere).
 */
@Composable
private fun FailLine(prefix: String?, label: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (prefix != null) Text(prefix, style = body(14, TextSecondary))
        Text(
            label,
            style = body(14, Accent2),
            modifier = Modifier
                .pressable(onOpen, haptic = false)
                .clip(R6)
                .semantics {
                    role = Role.Button
                    if (prefix != null) stateDescription = "nu a pornit"
                }
                .padding(horizontal = 10.dp, vertical = 14.dp)
        )
    }
}

/** „Arată melodia”: cere accesul la sesiunile media (Acces la notificări). */
@Composable
private fun AccessLink(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Arată melodia",
            style = body(14, Accent2),
            modifier = Modifier
                .coachTarget("muzica.acces")
                .pressable(onClick, haptic = false)
                .clip(R6)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun TogglesCard(state: MusicUiState, actions: MusicActions, compact: Boolean) {
    val h = if (compact) 48.dp else 56.dp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W08, R8)
    ) {
        ToggleRow(InvIcons.StopAtEnd, "Oprește la final", state.stopAtEnd, h) { actions.onStopAtEnd(!state.stopAtEnd) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(W06))
        ToggleRow(InvIcons.MapPin, "Pe hartă", state.onMap, h) { actions.onMap(!state.onMap) }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, on: Boolean, height: Dp, onFlip: () -> Unit) {
    Row(
        Modifier
            .pressable(onFlip, scaleDown = 0.99f)
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = label
                stateDescription = if (on) "pornit" else "oprit"
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (on) Amber else TextDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = cond(19), modifier = Modifier.weight(1f))
        InvSwitch(on)
    }
}

/** Mostrele ecranului S3c, pentru capturi (toate stările pornirii). */
object MusicWaitSamples {
    private val pill = PillState(34, ready = false)

    /** Cântă „Marș de dimineață”, top 1 în 7 zile, 1:12 din 3:40. */
    val playing = MusicUiState(
        pill = pill, ring = 0.34f, access = true, title = "Marș de dimineață", artist = "Fanfara FORJA", app = "Spotify",
        playing = true, positionMs = 72_000, durationMs = 220_000, top = true
    )

    /** Aceeași piesă pe pauză (Play reia exact piesa asta). */
    val paused = playing.copy(playing = false, top = false)

    /** Nicio muzică arătată; ultima sesiune e o carte audio (nu devine eroul): „Pornește muzica”, TOP 1, rândul „Reia”. */
    val idleBook = MusicUiState(
        pill = pill, ring = 0.34f, access = true,
        topChip = "Marș de dimineață",
        other = OtherUi("Fetele care ard", MediaKind.SPOKEN, playing = false)
    )

    /** Nimic de reluat, fără top încă. */
    val idle = MusicUiState(pill = pill, ring = 0.34f, access = true)

    val starting = idleBook.copy(start = StartUi.Starting)
    val needsTap = idleBook.copy(start = StartUi.NeedsTap("Deschide Spotify"))
    /** „Pornește muzica” n-a pornit, iar pasul de deschidere e în Spotify (același nume ca la „Deschide Spotify”). */
    val failed = idleBook.copy(start = StartUi.Failed("Deschide Spotify"))

    /** Nu a pornit și nu se știe playerul: „Deschide playerul” (17 caractere, cel mai lung buton). */
    val failedUnknown = idleBook.copy(start = StartUi.Failed("Deschide playerul"))

    /** Play pe piesa arătată n-a pornit (Resume): „Nu a pornit.” sub comenzi. */
    val resumeFailed = paused.copy(start = StartUi.Failed("Deschide Spotify"))

    /** Cel mai plin caz obișnuit: piesa de top, pe pauză, Play n-a pornit (eticheta TOP + „Nu a pornit.”). */
    val resumeFailedTop = resumeFailed.copy(top = true)

    /** Și mai plin: pe lângă „Deschide Spotify”, cartea audio cântă alături (inelul se strânge de două ori). */
    val pausedNeedsTapBook = paused.copy(
        start = StartUi.NeedsTap("Deschide Spotify"),
        other = OtherUi("Fetele care ard", MediaKind.SPOKEN, playing = true)
    )

    /** „Pornește muzica” a trezit Spotify (piesa lui, pe pauză), iar pasul următor e saltul în Spotify. */
    val pausedNeedsTap = paused.copy(start = StartUi.NeedsTap("Deschide Spotify"))

    /** Pornește o reluare: arcul în jurul discului. */
    val resumeStarting = paused.copy(start = StartUi.Starting)

    /** Cartea audio cântă (ea a pornit-o): rămâne alături, cu „Pauză”; eroul e egalizatorul. */
    val bookPlaying = MusicUiState(
        pill = pill, ring = 0.34f, access = true, topChip = "Marș de dimineață",
        other = OtherUi("Fetele care ard", MediaKind.SPOKEN, playing = true)
    )

    /** Fără acces, nimic nu se aude. */
    val noAccess = MusicUiState(pill = pill, ring = 0.34f, access = false)

    /** Fără acces, se aude muzică (playerele sistemului): pauză / înainte / înapoi. */
    val noAccessPlaying = noAccess.copy(playing = true, musicActive = true)

    val noAccessNeedsTap = noAccess.copy(start = StartUi.NeedsTap("Deschide Spotify"))
}
