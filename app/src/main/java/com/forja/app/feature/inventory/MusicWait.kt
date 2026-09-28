package com.forja.app.feature.inventory

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Inventory
import com.forja.app.core.music.Music
import kotlinx.coroutines.delay

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
    /** Fără acces: se aude muzică (AudioManager), deci arătăm comenzile. */
    val musicActive: Boolean = false
)

data class MusicActions(
    val onPill: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onAskAccess: () -> Unit = {},
    val onStopAtEnd: (Boolean) -> Unit = {},
    val onMap: (Boolean) -> Unit = {}
)

/** Eticheta cu care Music.topTrack numără o piesă („Titlu · Artist”). */
private fun trackLabel(title: String, artist: String): String {
    fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(120)
    val t = clean(title)
    val a = clean(artist)
    return if (a.isBlank()) t else "$t · $a"
}

/**
 * S3c — „Când tace muzica, inventarul e gata.”: coperta în inelul progresului, piesa, comenzile sesiunii media,
 * „Oprește la final” și „Pe hartă”. Fără „Acces la notificări”: „Pornește muzica” + „Arată melodia”.
 */
@Composable
fun InventoryMusicScreen(onOpenInventory: (InvPage) -> Unit) {
    val context = LocalContext.current
    val progress by Inventory.progress.collectAsState()
    val track by Music.nowPlaying.collectAsState()
    val stopAtEnd by remember { Music.stopWhenDoneFlow(context) }.collectAsState(initial = true)
    val onMap by remember { Music.shareOnMapFlow(context) }.collectAsState(initial = true)

    // Accesul la sesiunile media (ca în NowPlayingCard): reverificat la întoarcerea din Setări.
    var access by remember { mutableStateOf(Music.hasAccess(context)) }
    LaunchedEffect(access) { if (access) Music.ensureStarted(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = Music.hasAccess(context) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = Music.hasAccess(context)
    }
    // Fără acces vedem doar DACĂ se aude muzică.
    var musicActive by remember { mutableStateOf(false) }
    LaunchedEffect(access) {
        if (access) return@LaunchedEffect
        val am = context.getSystemService(AudioManager::class.java)
        while (true) {
            musicActive = try { am?.isMusicActive == true } catch (_: Exception) { false }
            delay(1_500)
        }
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
    val top by produceState<Pair<String, Int>?>(null, t?.title, t?.artist) { value = Music.topTrack(context) }
    val art = t?.art
    val artBitmap = remember(art) { art?.asImageBitmap() }
    val pill = pillStateOf(progress)
    val ring = when (progress?.stage) {
        InvStage.Ready, InvStage.Done -> 1f
        null -> 0f
        else -> progress?.let { it.done / it.total.coerceAtLeast(1).toFloat() } ?: 0f
    }

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
                playing = t?.playing ?: (!access && musicActive),
                positionMs = position,
                durationMs = t?.durationMs ?: 0L,
                top = t != null && top?.first == trackLabel(t.title, t.artist),
                stopAtEnd = stopAtEnd,
                onMap = onMap,
                musicActive = musicActive
            ),
            MusicActions(
                onPill = { onOpenInventory(if (progress?.stage == InvStage.Ready) InvPage.Folders else InvPage.Run) },
                onPrevious = { Music.previous(context) },
                onToggle = {
                    val playing = t?.playing ?: musicActive
                    if (playing) { Music.pause(context); if (!access) musicActive = false }
                    else { Music.play(context); if (!access) musicActive = true }
                },
                onNext = { Music.next(context) },
                onStart = { Music.play(context); musicActive = true },
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

/** S3c (Muzica.dc.html): fundal radial cald, pastila + egalizatorul mic, eroul 300 dp, piesa, comenzile, comutatoarele. */
@Composable
fun MusicWaitContent(state: MusicUiState, actions: MusicActions, modifier: Modifier = Modifier) {
    val known = state.access && state.title != null
    Box(
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
        TopBottomColumn(
            padding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp),
            top = {
                WaitHeader(state.pill, actions.onPill) { EqualizerMini(state.playing) }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    CoverRing(state)
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
                }
                if (known || state.musicActive) {
                    Controls(state.playing, actions)
                    if (!state.access) AccessLink(actions.onAskAccess)
                } else {
                    InvPrimaryButton("Pornește muzica", actions.onStart)
                    if (!state.access) AccessLink(actions.onAskAccess)
                }
                TogglesCard(state, actions)
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

/** Inelul de 300 dp (progresul inventarului, punct amber la capăt) și coperta de 244 dp care bate pe ritm. */
@Composable
private fun CoverRing(state: MusicUiState) {
    val reduced = LocalReducedMotion.current
    val p by animateFloatAsState(state.ring.coerceIn(0f, 1f), if (reduced) snap() else tween(700), label = "musicRing")
    val inf = if (!reduced) rememberInfiniteTransition(label = "music") else null
    val glow = inf?.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")
    val beat = if (state.playing) inf?.animateFloat(1f, 1.018f, infiniteRepeatable(tween(250), RepeatMode.Reverse), label = "beat") else null
    Box(Modifier.size(300.dp).semantics { contentDescription = "Progresul inventarului, ${(p * 100).toInt()} la sută" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 300f
            val r = 144f * u
            val sw = 4f * u
            drawCircle(W08, r, center, style = Stroke(sw))
            if (p > 0f) {
                drawArc(Amber, -90f, 360f * p, false, topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
                val dot = ringPoint(center, r, p)
                drawCircle(Amber.copy(alpha = 0.18f), 13f * u, dot)
                drawCircle(Amber.copy(alpha = glow?.value ?: 1f), 7f * u, dot)
            }
        }
        Box(
            Modifier
                .size(244.dp)
                .graphicsLayer {
                    val s = beat?.value ?: 1f
                    scaleX = s; scaleY = s
                }
                .shadow(40.dp, R8, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(R8)
                .background(MediaBg),
            contentAlignment = Alignment.Center
        ) {
            val art = state.art
            if (art != null) {
                Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.graphicsLayer { scaleX = 1.6f; scaleY = 1.6f }) {
                    Equalizer5(animate = state.playing)
                }
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

@Composable
private fun Controls(playing: Boolean, actions: MusicActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(InvIcons.Previous, "Piesa anterioară", actions.onPrevious)
        Box(
            Modifier
                .pressable(actions.onToggle)
                .size(80.dp)
                .shadow(30.dp, CircleShape, ambientColor = Amber, spotColor = Amber)
                .clip(CircleShape)
                .background(TextPrimary)
                .semantics { role = Role.Button; contentDescription = if (playing) "Pauză" else "Pornește muzica" },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (playing) InvIcons.Pause else InvIcons.Play, null, tint = Surface0, modifier = Modifier.size(30.dp))
        }
        GhostButton(InvIcons.Next, "Piesa următoare", actions.onNext)
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

/** „Arată melodia”: cere accesul la sesiunile media (Acces la notificări). */
@Composable
private fun AccessLink(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Arată melodia",
            style = body(14, Accent2),
            modifier = Modifier
                .pressable(onClick, haptic = false)
                .clip(R6)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun TogglesCard(state: MusicUiState, actions: MusicActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W08, R8)
    ) {
        ToggleRow(InvIcons.StopAtEnd, "Oprește la final", state.stopAtEnd) { actions.onStopAtEnd(!state.stopAtEnd) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(W06))
        ToggleRow(InvIcons.MapPin, "Pe hartă", state.onMap) { actions.onMap(!state.onMap) }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, on: Boolean, onFlip: () -> Unit) {
    Row(
        Modifier
            .pressable(onFlip, scaleDown = 0.99f)
            .fillMaxWidth()
            .height(56.dp)
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
