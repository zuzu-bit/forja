package com.forja.app.feature.games

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.EmberField
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.PopIn
import com.forja.app.core.designsystem.components.PulseGlow
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.feature.inventory.Amber
import com.forja.app.feature.inventory.InvIcons
import com.forja.app.feature.inventory.InvOutlineButton
import com.forja.app.feature.inventory.InvPrimaryButton
import com.forja.app.feature.inventory.R6
import com.forja.app.feature.inventory.R8
import com.forja.app.feature.inventory.Raised
import com.forja.app.feature.inventory.Rule
import com.forja.app.feature.inventory.W06
import com.forja.app.feature.inventory.W09
import com.forja.app.feature.inventory.cond
import com.forja.app.feature.inventory.fmtCount
import com.forja.app.feature.inventory.hero
import com.forja.app.feature.inventory.mono
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Suprapunerile jocurilor, toate fără stare (capturile le randează direct): degetul din Ready, numărătoarea 3·2·1,
 * cardul de pauză, cardul de final (victorie / înfrângere / „Fără sfârșit”), stelele, rândul „Dosarele sunt gata”.
 */

// ───────────────────────────── Stelele ─────────────────────────────

/** Steaua cu 5 colțuri, desenată (Barlow poate să nu aibă „★”). */
private fun starPath(d: Float): Path {
    val p = Path()
    val cx = d / 2f
    val cy = d / 2f + d * 0.04f
    val ro = d / 2f
    val ri = ro * 0.46f
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) ro else ri
        val a = (-PI / 2 + k * PI / 5).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

@Composable
internal fun Star(on: Boolean, size: Dp, modifier: Modifier = Modifier, off: Color = Rule) {
    Box(
        modifier
            .size(size)
            .drawWithCache {
                val path = starPath(this.size.minDimension)
                onDrawBehind { drawPath(path, if (on) Amber else off) }
            }
    )
}

/** Trei stele; `pop` = apar pe rând (120 ms), ca la finalul unui nivel. */
@Composable
internal fun StarRow(stars: Int, size: Dp, gap: Dp, modifier: Modifier = Modifier, pop: Boolean = false, off: Color = Rule) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = starsWords(stars) },
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        for (i in 0 until 3) {
            if (pop) PopIn(visible = true, delayMs = 200L + 120L * i) { Star(i < stars, size, off = off) }
            else Star(i < stars, size, off = off)
        }
    }
}

internal fun starsWords(n: Int): String = when (n) {
    0 -> "fără stele"
    1 -> "o stea"
    2 -> "două stele"
    else -> "trei stele"
}

// ───────────────────────────── Ready și numărătoarea ─────────────────────────────

/** Degetul care pulsează peste tablă: prima atingere pornește. Fără text; static sub mișcare redusă. */
@Composable
internal fun ReadyGlyph(modifier: Modifier = Modifier, description: String = "Atinge ca să începi") {
    val reduced = LocalReducedMotion.current
    val t = if (!reduced) {
        rememberInfiniteTransition(label = "ready").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "readyT")
    } else null
    Box(
        modifier
            .size(96.dp)
            .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite }
            .drawWithCache {
                val ringW = 2.dp.toPx()
                val core = 11.dp.toPx()
                val max = size.minDimension / 2f
                val ring = Stroke(ringW)
                onDrawBehind {
                    val p = t?.value ?: 0.45f
                    for (k in 0 until 2) {
                        val q = (p + k * 0.5f) % 1f
                        val r = core + (max - core) * q
                        drawCircle(Amber.copy(alpha = 0.55f * (1f - q)), r, center, style = ring)
                    }
                    drawCircle(Amber.copy(alpha = 0.22f), core * 1.9f, center)
                    drawCircle(TextPrimary, core, center)
                }
            }
    )
}

/** 3 · 2 · 1 după „Continuă”: cifre-erou amber de 96, schimbate la 600 ms (fără scalare sub mișcare redusă). */
@Composable
internal fun CountdownDigit(n: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(GameScrim.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        androidx.compose.runtime.key(n) {
            PopIn(visible = true, fromScale = 1.5f) {
                Text("$n", style = hero(96, color = Amber), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
        }
    }
}

// ───────────────────────────── Rândul inventarului ─────────────────────────────

/** „Dosarele sunt gata · Vezi” în cardurile de pauză și final (jocul nu se oprește singur pentru asta). */
@Composable
internal fun InventoryReadyRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .pressable(onOpen)
            .fillMaxWidth()
            .height(56.dp)
            .clip(R8)
            .background(Accent.copy(alpha = 0.22f))
            .border(1.dp, Accent2.copy(alpha = 0.6f), R8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Dosarele sunt gata. Vezi."
                liveRegion = LiveRegionMode.Polite
            }
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mascot(state = MascotState.Happy, size = 42.dp)
        Spacer(Modifier.width(8.dp))
        Text("Dosarele sunt gata", style = cond(18), maxLines = 1, modifier = Modifier.weight(1f))
        Text("Vezi", style = cond(18, color = Accent2))
        Icon(InvIcons.ChevronRight, null, tint = Accent2, modifier = Modifier.padding(start = 2.dp).size(16.dp))
    }
}

// ───────────────────────────── Iconițele setărilor ─────────────────────────────

internal object GameIcons {
    private const val SPEAKER = "M4 9.5h3.5L12 5.5v13l-4.5-4H4z"
    private const val PHONE = "M8.5 3.5h7a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 19V5a1.5 1.5 0 0 1 1.5-1.5z"
    val SoundOn = stroke(SPEAKER, "M15.5 9.5a3.5 3.5 0 0 1 0 5", "M18 7a7 7 0 0 1 0 10")
    val SoundOff = stroke(SPEAKER, "M15.5 9.5l5 5", "M20.5 9.5l-5 5")
    val VibrateOn = stroke(PHONE, "M3.5 9v6", "M20.5 9v6")
    val VibrateOff = stroke(PHONE, "M4 4l16 16")

    private fun stroke(vararg d: String): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in d) {
            b.addPath(
                pathData = PathParser().parsePathString(p).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }
}

/** Comutator-iconiță 48 dp (Sunet / Vibrații). */
@Composable
internal fun ToggleIcon(on: Boolean, iconOn: ImageVector, iconOff: ImageVector, label: String, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .pressable(onToggle)
                .size(48.dp)
                .clip(R8)
                .background(if (on) Accent.copy(alpha = 0.35f) else Raised)
                .border(if (on) 1.5.dp else 1.dp, if (on) Accent2 else W09, R8)
                .semantics {
                    role = Role.Switch
                    contentDescription = label
                    stateDescription = if (on) "pornit" else "oprit"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (on) iconOn else iconOff, null, tint = if (on) OnAccent else TextDim, modifier = Modifier.size(22.dp))
        }
        Text(label.uppercase(), style = mono(9, 0.14f, color = if (on) TextSecondary else TextDim, bold = true))
    }
}

// ───────────────────────────── Cardurile ─────────────────────────────

/** Voalul și cardul centrat (Surface1, R8). Atingerile nu trec la tablă. */
@Composable
internal fun GameCardScrim(modifier: Modifier = Modifier, behind: @Composable () -> Unit = {}, card: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(GameScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        behind()
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(R8)
                .background(Surface1)
                .border(1.dp, W09, R8)
                .padding(18.dp)
        ) { card() }
    }
}

/** Ce afișează cardul de pauză. */
internal data class PauseUi(
    val levelMeta: String,
    val inventoryReady: Boolean,
    val sfx: Boolean,
    val haptics: Boolean
)

internal class PauseActions(
    val onResume: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onMap: () -> Unit = {},
    val onInventory: () -> Unit = {},
    val onSfx: () -> Unit = {},
    val onHaptics: () -> Unit = {}
)

/** „Pauză.” · rândul inventarului · „Continuă” (NIVELUL 6) · „Reia nivelul” · „Harta” · Sunet / Vibrații. */
@Composable
internal fun PauseCard(ui: PauseUi, actions: PauseActions, modifier: Modifier = Modifier) {
    GameCardScrim(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pauză.", style = cond(44, 44), modifier = Modifier.weight(1f))
                Mascot(state = MascotState.Thinking, hat = MascotHat.Helmet, size = 64.dp)
            }
            if (ui.inventoryReady) InventoryReadyRow(actions.onInventory)
            InvPrimaryButton("Continuă", actions.onResume, meta = ui.levelMeta)
            InvOutlineButton("Reia nivelul", actions.onRestart)
            InvOutlineButton("Harta", actions.onMap)
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)
            ) {
                ToggleIcon(ui.sfx, GameIcons.SoundOn, GameIcons.SoundOff, "Sunet", actions.onSfx)
                ToggleIcon(ui.haptics, GameIcons.VibrateOn, GameIcons.VibrateOff, "Vibrații", actions.onHaptics)
            }
        }
    }
}

/** Felul finalului. */
internal enum class ResultKind { Won, Lost, EndlessOver }

/** Ce afișează cardul de final. `primary` / `secondary` = etichetele butoanelor. */
internal data class ResultUi(
    val kind: ResultKind,
    val stars: Int,
    val score: Int,
    val best: Int,
    val newBest: Boolean,
    val lostLine: String,
    val stamp: String,
    val primary: String,
    val primaryMeta: String?,
    val secondary: String,
    val inventoryReady: Boolean
)

internal class ResultActions(
    val onPrimary: () -> Unit = {},
    val onSecondary: () -> Unit = {},
    val onInventory: () -> Unit = {}
)

/**
 * Finalul unui nivel. Victorie: ștampila „MISIUNE ÎNDEPLINITĂ”, stelele (pe rând), scorul, „RECORD NOU”, mascota
 * fericită în puls și scânteile forjei. Înfrângere: mascota tristă, „Zidul a căzut.” / „Scânteia s-a stins.”, scorul.
 * „Fără sfârșit”: „Marș încheiat.”, scorul și recordul.
 */
@Composable
internal fun ResultCard(ui: ResultUi, actions: ResultActions, modifier: Modifier = Modifier) {
    val won = ui.kind == ResultKind.Won
    val reduced = LocalReducedMotion.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        // pe ecranele scunde (S23) mascota și scorul se strâng puțin
        val compact = maxHeight < 600.dp
        GameCardScrim(
            behind = {
                if (won && !reduced) EmberField(Modifier.fillMaxSize(), count = 36, alpha = 0.7f)
            }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
                val mascotSize = if (compact) 96.dp else 120.dp
                when (ui.kind) {
                    ResultKind.Won -> PulseGlow(radius = mascotSize * 0.72f, color = Amber, minAlpha = 0.10f, maxAlpha = 0.26f) {
                        Mascot(state = MascotState.Happy, hat = MascotHat.Helmet, size = mascotSize)
                    }
                    ResultKind.Lost -> Mascot(state = MascotState.Sorry, hat = MascotHat.Helmet, size = if (compact) 110.dp else 150.dp)
                    ResultKind.EndlessOver -> Mascot(
                        state = if (ui.newBest) MascotState.Happy else MascotState.Idle, hat = MascotHat.Helmet, size = mascotSize
                    )
                }
                when (ui.kind) {
                    ResultKind.Won -> {
                        StampLabel(ui.stamp, rotationDeg = -3f, color = Amber)
                        StarRow(ui.stars, size = if (compact) 30.dp else 36.dp, gap = 10.dp, pop = true, off = Rule)
                    }
                    ResultKind.Lost -> Text(ui.lostLine, style = cond(34, 36), textAlign = TextAlign.Center)
                    ResultKind.EndlessOver -> Text("Marș încheiat.", style = cond(34, 36), textAlign = TextAlign.Center)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(fmtCount(ui.score), style = hero(if (compact) 48 else 56), modifier = Modifier.semantics { contentDescription = "Scor ${ui.score}" })
                    when {
                        ui.newBest -> Text("RECORD NOU", style = mono(11, 0.16f, color = Amber, bold = true))
                        ui.best > 0 -> Text("RECORD ${fmtCount(ui.best)}", style = mono(11, 0.14f, color = TextDim, bold = true))
                    }
                }
                if (ui.inventoryReady) InventoryReadyRow(actions.onInventory, Modifier.padding(top = 2.dp))
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InvPrimaryButton(ui.primary, actions.onPrimary, meta = ui.primaryMeta)
                    InvOutlineButton(ui.secondary, actions.onSecondary)
                }
            }
        }
    }
}

/** Placa texturată cu puncte (zona degetului): aceleași gesturi ca pe tablă, fără nimic scris. */
@Composable
internal fun ThumbPad(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(R8)
            .background(Surface1.copy(alpha = 0.6f))
            .border(1.dp, W06, R8)
            .drawWithCache {
                val step = 14.dp.toPx()
                val r = 1.1.dp.toPx()
                val c = Color.White.copy(alpha = 0.07f)
                val cols = (size.width / step).toInt()
                val rows = (size.height / step).toInt()
                val ox = (size.width - (cols - 1) * step) / 2f
                val oy = (size.height - (rows - 1) * step) / 2f
                val pts = ArrayList<Offset>(cols * rows)
                for (y in 0 until rows) for (x in 0 until cols) pts += Offset(ox + x * step, oy + y * step)
                onDrawBehind {
                    drawPoints(pts, androidx.compose.ui.graphics.PointMode.Points, c, strokeWidth = 2 * r, cap = StrokeCap.Round)
                }
            }
    )
}

/** Eticheta unui HUD (mono 9, majuscule). */
@Composable
internal fun HudLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = mono(9, 0.16f, color = TextDim, bold = true), modifier = modifier, maxLines = 1)
}

/** Cutia unei previzualizări (URM. / REZ.). */
internal val PreviewShape = R6

/** Ce stă peste suprafața de joc. */
internal sealed interface GameOverlay {
    /** Joc în plin: nimic deasupra. */
    data object None : GameOverlay

    /** Prima atingere pornește (degetul care pulsează). */
    data object Ready : GameOverlay

    class Pause(val ui: PauseUi) : GameOverlay

    class Result(val ui: ResultUi) : GameOverlay

    class Countdown(val n: Int) : GameOverlay
}

/** Pasul de ghidaj care arată pe tabla jocului (≤ 60 de caractere). */
internal fun gameGuideKey(base: String, replay: Long): String = if (replay == 0L) base else "$base~$replay"
