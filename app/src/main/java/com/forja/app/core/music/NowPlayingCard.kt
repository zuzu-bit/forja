package com.forja.app.core.music

import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.AccentGradient
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.BodySmall
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Springs
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.PulseGlow
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.designsystem.monoLabel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Implicitul lui `onRequestAccess`: cardul deschide singur pagina Android de acces și reverifică la întoarcere. */
private val OpenAccessPage: () -> Unit = {}

/**
 * „Muzica ta”, ca erou de ecran: coperta albumului (sau un egalizator când nu are; static sub mișcare redusă), cu inelul
 * de progres opțional în jurul ei, titlul și artistul, apoi înapoi · play/pauză · înainte (sesiunea media sau tastele media).
 * Fără acces la sesiuni: butonul mare „Pornește muzica” (ultimul player; după 2 s fără sunet, „Melodii apreciate”) și
 * linkul mic „Arată melodia”, care cere accesul. Fără fundal propriu: stă pe ecranul sau cardul care îl găzduiește.
 *
 * @param progress progresul inventarului (0..1), desenat ca inel în jurul copertei; null = fără inel.
 * @param size diametrul eroului (coperta + inelul); sub 170 dp cardul trece pe varianta compactă.
 * @param onRequestAccess ce face „Arată melodia”; lăsat implicit, cardul deschide singur pagina de acces.
 */
@Composable
fun NowPlayingCard(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    size: Dp = 240.dp,
    onRequestAccess: () -> Unit = OpenAccessPage
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current

    var access by remember { mutableStateOf(Music.hasAccess(context)) }
    LaunchedEffect(Unit) { Music.ensureStarted(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                access = Music.hasAccess(context)
                if (access) Music.ensureStarted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = Music.hasAccess(context)
        if (access) Music.ensureStarted(context)
    }
    val requestAccess: () -> Unit = if (onRequestAccess === OpenAccessPage) {
        {
            try {
                accessLauncher.launch(Music.accessIntent(context))
            } catch (_: Exception) {
                try { accessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
            }
        }
    } else onRequestAccess

    val track by Music.nowPlaying.collectAsState()
    // Fără acces vedem doar DACĂ cântă ceva (fluxul de muzică al telefonului), nu și ce.
    var musicActive by remember { mutableStateOf(false) }
    LaunchedEffect(access) {
        if (access) return@LaunchedEffect
        val am = context.getSystemService(AudioManager::class.java)
        while (true) {
            musicActive = try { am?.isMusicActive == true } catch (_: Exception) { false }
            delay(1_500)
        }
    }
    // „Pornește muzica” apăsat: egalizatorul se mișcă cât pornește playerul.
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(starting) {
        if (starting) {
            delay(2_500)
            starting = false
        }
    }

    val t = if (access) track else null
    val playing = t?.playing ?: (!access && musicActive)
    val compact = size < 170.dp
    val textMax = size + 56.dp

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        NowPlayingHero(
            size = size,
            art = t?.art,
            playing = playing,
            animate = (playing || starting) && !reduced,
            progress = progress
        )
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))

        if (t != null) {
            Text(
                t.title,
                style = TitleModule.copy(
                    fontSize = if (compact) 17.sp else 23.sp,
                    lineHeight = if (compact) 20.sp else 26.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = textMax)
            )
            if (t.artist.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    t.artist,
                    style = Body.copy(fontSize = if (compact) 12.sp else 14.sp, color = TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = textMax)
                )
            }
            if (!compact) {
                Spacer(Modifier.height(6.dp))
                Text(t.app.uppercase(), style = monoLabel(8, 0.16f), maxLines = 1)
            }
            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        }

        if (t != null || (!access && musicActive)) {
            MusicControls(
                playing = playing,
                compact = compact,
                onPrevious = { Music.previous(context) },
                onToggle = {
                    if (playing) {
                        Music.pause(context)
                        if (!access) musicActive = false
                    } else {
                        Music.play(context)
                        if (!access) musicActive = true
                    }
                },
                onNext = { Music.next(context) }
            )
        } else {
            PrimaryButton(
                "Pornește muzica",
                onClick = {
                    starting = true
                    Music.play(context)
                },
                small = compact,
                modifier = Modifier.widthIn(min = if (compact) 0.dp else 200.dp)
            )
        }

        if (!access) {
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Text(
                "Arată melodia",
                style = BodySmall.copy(color = Accent2, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .pressable(requestAccess, haptic = false)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** Coperta rotundă (sau egalizatorul), inelul de progres și strălucirea care respiră cât cântă. */
@Composable
private fun NowPlayingHero(size: Dp, art: android.graphics.Bitmap?, playing: Boolean, animate: Boolean, progress: Float?) {
    val reduced = LocalReducedMotion.current
    val ring = (size * 0.034f).coerceIn(3.dp, 8.dp)
    val gap = ring * 0.9f
    val inner = if (progress != null) size - (ring + gap) * 2 else size

    val body: @Composable () -> Unit = {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (progress != null) {
                val p by animateFloatAsState(
                    progress.coerceIn(0f, 1f),
                    if (reduced) snap() else Springs.natural(),
                    label = "musicRing"
                )
                Canvas(Modifier.fillMaxSize()) {
                    val sw = ring.toPx()
                    val d = this.size.minDimension - sw
                    val tl = Offset(sw / 2f, sw / 2f)
                    drawArc(Color(0x1FFFFFFF), -90f, 360f, false, topLeft = tl, size = Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                    if (p > 0f) {
                        drawArc(AccentGradient, -90f, 360f * p, false, topLeft = tl, size = Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                    }
                }
            }
            Box(
                Modifier
                    .size(inner)
                    .clip(CircleShape)
                    .background(Surface2)
                    .border(1.dp, StrokeCardStrong, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) {
                    val img = remember(art) { art.asImageBitmap() }
                    Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Equalizer(Modifier.size(inner * 0.44f), animate)
                }
            }
        }
    }

    if (playing) {
        PulseGlow(radius = size * 0.56f, color = Accent2, minAlpha = 0.08f, maxAlpha = 0.24f, periodMs = 2_200) { body() }
    } else {
        body()
    }
}

private val EQ_REST = floatArrayOf(0.38f, 0.64f, 0.92f, 0.56f, 0.30f)
private val EQ_SPEED = intArrayOf(2, 3, 1, 4, 3)
private val EQ_PHASE = floatArrayOf(0f, 1.3f, 2.1f, 0.7f, 2.9f)

/** Egalizator cu cinci bare; animat doar cât cântă (bucla de 2,4 s se închide perfect), altfel silueta în repaus. */
@Composable
private fun Equalizer(modifier: Modifier, animated: Boolean) {
    val phase: State<Float>? = if (animated) {
        rememberInfiniteTransition(label = "eq").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
            label = "eqPhase"
        )
    } else null
    Canvas(modifier) {
        val n = EQ_REST.size
        val gap = this.size.width * 0.07f
        val bw = (this.size.width - gap * (n - 1)) / n
        val h = this.size.height
        val brush = Brush.verticalGradient(listOf(OnAccent, Accent2, Accent), startY = 0f, endY = h)
        val t = phase?.value
        val twoPi = 2f * PI.toFloat()
        for (i in 0 until n) {
            val level = if (t == null) EQ_REST[i] else {
                val a = sin(twoPi * EQ_SPEED[i] * t + EQ_PHASE[i])
                val b = sin(twoPi * (EQ_SPEED[i] + 2) * t + 2f * EQ_PHASE[i])
                (0.22f + 0.78f * (0.5f + 0.5f * (0.65f * a + 0.35f * b))).coerceIn(0.12f, 1f)
            }
            val bh = h * level
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (bw + gap), h - bh),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2f)
            )
        }
    }
}

/** Înapoi · play/pauză · înainte: butonul din mijloc e mare, olive; celelalte, discrete. */
@Composable
private fun MusicControls(
    playing: Boolean,
    compact: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    val big = if (compact) 48.dp else 64.dp
    val small = if (compact) 36.dp else 46.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp)
    ) {
        RoundControl(Icons.Filled.SkipPrevious, "Melodia anterioară", small, filled = false, onClick = onPrevious)
        RoundControl(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (playing) "Pauză" else "Pornește",
            big, filled = true, onClick = onToggle
        )
        RoundControl(Icons.Filled.SkipNext, "Melodia următoare", small, filled = false, onClick = onNext)
    }
}

@Composable
private fun RoundControl(icon: ImageVector, description: String, size: Dp, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(AccentGradient)
                else Modifier.background(Surface2).border(1.dp, StrokeCardStrong, CircleShape)
            )
            .pressable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (filled) OnAccent else TextPrimary, modifier = Modifier.size(size * 0.46f))
    }
}
