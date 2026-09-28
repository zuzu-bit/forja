package com.forja.app.feature.inventory

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextDim2
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.ProgressBar
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.BinTick
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvStage
import com.forja.app.feature.games.AsaltCardArt
import com.forja.app.feature.games.ZidCardArt
import kotlin.math.PI
import kotlin.math.sin

/** Ce poate face omul din S2. */
data class RunActions(
    val onClose: () -> Unit = {},
    val onZid: () -> Unit = {},
    val onAsalt: () -> Unit = {},
    val onMusic: () -> Unit = {},
    val onOpenFolders: () -> Unit = {}
)

// ═════════════════════════════ S2 · Rulare ═════════════════════════════

/** Pașii ghidajului din S2 (≤ 60 de caractere): banda și cardurile de așteptare. */
internal fun runCoachSteps(kind: InvKind): List<CoachStep> = listOf(
    CoachStep("inv_belt", if (kind == InvKind.Photos) "Fiecare poză își găsește dosarul." else "Fiecare fișier își găsește dosarul."),
    CoachStep("inv_wait", "Cât aștepți: un joc sau muzica ta.")
)

/**
 * S2 (Rulare.dc.html): banda de sortare, procentul, pașii, „Cât aștepți” (ZID · ASALT · MUZICĂ). Fereastră spre analiza
 * din fundal. Țintele ghidajului (coachTarget) sunt aici; învelișul CoachMarks îl pune ecranul cu stare.
 * Pe ecranele scunde (S23 cu bara cu 3 butoane, ≈ 695 dp) spațiile și cardurile se strâng: totul încape fără derulare.
 */
@Composable
fun InventoryRunContent(state: RunUiState, actions: RunActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 760.dp
        TopBottomColumn(
            modifier = Modifier.fillMaxSize().background(Surface0),
            padding = PaddingValues(start = 20.dp, top = if (compact) 12.dp else 20.dp, end = 20.dp, bottom = if (compact) 16.dp else 24.dp),
            gap = if (compact) 12.dp else 14.dp,
            top = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StampLabel("INVENTAR", rotationDeg = -4f, appear = false)
                    InvIconButton(InvIcons.Close, "Închide", actions.onClose, iconSize = 18.dp)
                }
                SortingBelt(
                    recent = state.recent,
                    bins = state.bins,
                    animate = !state.ready,
                    sealed = state.ready,
                    modifier = Modifier.coachTarget("inv_belt")
                )
                PercentRow(
                    percent = state.percent,
                    fraction = if (state.ready) 1f else state.done / state.total.coerceAtLeast(1).toFloat(),
                    meta = when {
                        state.ready -> "${fmtCount(state.folders)} DOSARE"
                        else -> fmtMinutes(state.etaSec)
                    },
                    mascot = if (state.ready) MascotState.Happy else MascotState.Thinking
                )
                Stepper(stageIndex(state.stage), done = state.ready)
                AnimatedVisibility(visible = !state.ready, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.coachTarget("inv_wait"), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
                        Text("CÂT AȘTEPȚI", style = mono(10, 0.16f, color = TextDim, bold = true), modifier = Modifier.padding(top = 4.dp))
                        WaitCards(state, actions, cardHeight = if (compact) 140.dp else 170.dp)
                    }
                }
            },
            bottom = {
                if (state.ready) {
                    InvPrimaryButton("Vezi dosarele", actions.onOpenFolders)
                } else {
                    Text(
                        "MERGE ȘI CU APLICAȚIA ÎNCHISĂ",
                        style = mono(11, 0.08f, color = TextDim),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }
}

private fun stageIndex(stage: InvStage): Int = when (stage) {
    InvStage.Scanning -> 0
    InvStage.Grouping -> 1
    InvStage.Naming -> 2
    else -> 3
}

/** Mascota mică + procentul mare + estimarea + bara subțire. */
@Composable
internal fun PercentRow(percent: Int, fraction: Float, meta: String?, mascot: MascotState, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val shown by animateIntAsState(percent.coerceIn(0, 100), if (reduced) snap() else tween(600, easing = FastOutSlowInEasing), label = "pct")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Mascot(state = mascot, hat = MascotHat.Helmet, size = 72.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(fmtPercent(shown), style = hero(56, 52), modifier = Modifier.alignByBaseline().semantics { contentDescription = "$percent la sută" })
                if (meta != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(meta, style = mono(11), modifier = Modifier.alignByBaseline(), maxLines = 1)
                }
            }
            ProgressBar(fraction, height = 6.dp, track = Surface2)
        }
    }
}

// ───────────────────────────── Pașii ─────────────────────────────

private val StepIcons: List<ImageVector> get() = listOf(InvIcons.Search, InvIcons.Layers, InvIcons.Spark, InvIcons.Check)
private val StepLabels = listOf("SCANEZ", "GRUPEZ", "AI", "GATA")

/** Stepper-ul cu 4 iconițe (lupă, straturi, scânteie, bifă); cel activ pulsează (1,6 s). */
@Composable
internal fun Stepper(active: Int, done: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val pulse = if (!reduced && !done) {
        rememberInfiniteTransition(label = "stepPulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "stepPulseT")
    } else null
    val icons = StepIcons
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 4) {
            val isDone = done || i < active
            val isActive = !done && i == active
            val color = when {
                isActive -> Amber
                isDone -> Accent2
                else -> TextDim2
            }
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = StepLabels[i] + if (isActive) ", în lucru" else if (isDone) ", gata" else ""
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .pulseRing(isActive && pulse != null, { pulse?.value ?: 0f }, radius = 18.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isActive -> Amber.copy(alpha = 0.14f)
                                isDone -> Accent.copy(alpha = 0.35f)
                                else -> Color.Transparent
                            }
                        )
                        .border(1.dp, if (isActive) Amber else if (isDone) Accent2 else Rule, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icons[i], null, tint = color, modifier = Modifier.size(18.dp))
                }
                Text(StepLabels[i], style = mono(9, 0.14f, color = color, bold = true), maxLines = 1)
            }
        }
    }
}

// ───────────────────────────── Cardurile „Cât aștepți” ─────────────────────────────

/** ZID · ASALT · MUZICĂ: trei carduri egale (miniatura desenată + eticheta), cipul „NIV. 4” pe jocuri. */
@Composable
private fun WaitCards(state: RunUiState, actions: RunActions, cardHeight: Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WaitCard("ZID", levelDescription("Zid", state.zidLevel), actions.onZid, Modifier.weight(1f), cardHeight) {
            ZidCardArt(state.zidLevel)
        }
        WaitCard("ASALT", levelDescription("Asalt", state.asaltLevel), actions.onAsalt, Modifier.weight(1f), cardHeight) {
            AsaltCardArt(state.asaltLevel)
        }
        WaitCard("MUZICĂ", "Muzică", actions.onMusic, Modifier.weight(1f), cardHeight) {
            Box(Modifier.fillMaxSize().background(MediaBg), contentAlignment = Alignment.Center) {
                val art = state.musicArt
                if (art != null) {
                    Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Equalizer5()
                }
            }
        }
    }
}

private fun levelDescription(name: String, level: Int?): String = if (level == null) "$name, joc" else "$name, joc, nivelul $level"

@Composable
private fun WaitCard(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    media: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Column(
        modifier
            .pressable(onClick)
            .height(height)
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W09, R8)
            .semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }
    ) {
        Box(Modifier.fillMaxWidth().height(height - 42.dp), content = media)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(label, style = cond(18, tracking = 0.04f), maxLines = 1)
        }
    }
}

// ═════════════════════════════ Banda de sortare ═════════════════════════════

private const val PERIOD_MS = 5400L
private const val SLOTS = 6
private const val STAGGER_MS = PERIOD_MS / SLOTS
private val BeltEase = CubicBezierEasing(0.45f, 0.05f, 0.35f, 1f)
/** Cutia în care cade fiecare miniatură (prototipul: tx 72/162/252/72/342/162 → cutiile 0,1,2,0,3,1). */
private val SlotBin = intArrayOf(0, 1, 2, 0, 3, 1)

private const val BELT_H = 232f
private const val LINE_Y = 52f
private const val THUMB = 56f
private const val BIN_ICON_W = 58f
private const val BIN_ICON_H = 44f
private const val BIN_NAME_H = 30f
private const val BIN_COUNT_H = 12f
private const val BIN_PAD = 10f
private const val BIN_GAP = 8f
private const val BIN_BOTTOM = 12f
private const val BIN_COL_H = BIN_ICON_H + 4f + BIN_NAME_H + 4f + BIN_COUNT_H
private const val ICON_CENTER_Y = BELT_H - BIN_BOTTOM - BIN_COL_H + BIN_ICON_H / 2f

private const val FOLDER_PATH = "M2 8a3 3 0 0 1 3-3h14l4 4h30a3 3 0 0 1 3 3v27a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3z"

/**
 * „Banda de sortare” (Rulare.dc.html): miniaturi REALE (InvProgress.recent) intră din stânga pe bandă, alunecă și cad
 * într-o cutie (arc + scară), în bucla de 5,4 s cu 6 locuri decalate cu 0,9 s — aceeași curbă ca în prototip
 * (cubic-bezier .45,.05,.35,1 pe fiecare segment). Cutia care primește tresare. Cutiile nebotezate sunt punctate, cu „…”.
 * Mișcare redusă (sau `animate = false`): doar cutiile, statice. `sealed` = bifă pe fiecare cutie (gata / aplicat).
 */
@Composable
internal fun SortingBelt(
    recent: List<Uri>,
    bins: List<BinTick>,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    sealed: Boolean = false
) {
    val reduced = LocalReducedMotion.current
    val moving = animate && !reduced
    val shown = remember(bins) {
        val top = bins.take(4)
        if (top.isEmpty()) List(4) { BinTick(null, -1) } else top
    }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val t0 = withFrameMillis { it } - clock.longValue
        while (true) withFrameMillis { clock.longValue = it - t0 }
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(BELT_H.dp)
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W06, R8)
            .semantics { contentDescription = "Banda de sortare" }
    ) {
        val w = maxWidth.value
        val binW = (w - 2 * BIN_PAD - 3 * BIN_GAP) / 4f
        val binCount = shown.size
        fun binCenter(b: Int): Float {
            // cutiile ocupă mereu 4 coloane (ca în prototip); mai puține = centrate
            val offset = (4 - binCount) * (binW + BIN_GAP) / 2f
            return BIN_PAD + offset + b * (binW + BIN_GAP) + binW / 2f
        }
        // linia punctată a benzii
        Canvas(Modifier.fillMaxWidth().height(1.dp).offset(y = LINE_Y.dp)) {
            drawLine(
                Accent2.copy(alpha = 0.35f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )
        }
        // miniaturile în zbor (sub cutii, ca în prototip: cad ÎN cutie)
        if (moving) {
            for (slot in 0 until SLOTS) {
                val target = SlotBin[slot].coerceAtMost(binCount - 1)
                val cycle by remember(slot) {
                    derivedStateOf {
                        val t = clock.longValue - slot * STAGGER_MS
                        if (t < 0) -1L else t / PERIOD_MS
                    }
                }
                val uri = if (recent.isEmpty() || cycle < 0) null else recent[((cycle * SLOTS + slot) % recent.size).toInt()]
                val endX = binCenter(target) - THUMB / 2f
                Box(
                    Modifier
                        .size(THUMB.dp)
                        .graphicsLayer {
                            val t = clock.longValue - slot * STAGGER_MS
                            if (t < 0) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            val p = (t % PERIOD_MS) / PERIOD_MS.toFloat()
                            val f = beltFrame(p)
                            val startX = -60f
                            translationX = (startX + (endX - startX) * f.x).dp.toPx()
                            translationY = (24f + (ICON_CENTER_Y - LINE_Y) * f.fall + 8f * f.sink).dp.toPx()
                            scaleX = f.scale
                            scaleY = f.scale
                            alpha = f.alpha
                        }
                        .clip(R4)
                ) {
                    if (uri != null) {
                        InvThumb(uri, "image/*", Modifier.fillMaxSize(), px = 160)
                    } else {
                        Box(Modifier.fillMaxSize().background(Surface2), contentAlignment = Alignment.Center) {
                            Icon(InvIcons.Image, null, tint = TextDim2, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        // cutiile
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = BIN_PAD.dp, end = BIN_PAD.dp, bottom = BIN_BOTTOM.dp),
            horizontalArrangement = Arrangement.spacedBy(BIN_GAP.dp)
        ) {
            shown.forEachIndexed { b, bin ->
                Bin(
                    bin = bin,
                    width = binW.dp,
                    sealed = sealed,
                    sealDelay = b * 90,
                    bump = { if (moving) binBump(clock.longValue, b, binCount) else 0f }
                )
            }
        }
    }
}

private class BeltFrame(val x: Float, val fall: Float, val sink: Float, val scale: Float, val alpha: Float)

/** Cadrul unei miniaturi la faza p (0..1), identic cu `@keyframes forjaBelt`. */
private fun beltFrame(p: Float): BeltFrame {
    fun seg(from: Float, to: Float) = BeltEase.transform(((p - from) / (to - from)).coerceIn(0f, 1f))
    val x = seg(0f, 0.55f)
    val fall = if (p < 0.55f) 0f else seg(0.55f, 0.82f)
    val sink = if (p < 0.82f) 0f else seg(0.82f, 1f)
    val scale = when {
        p < 0.55f -> 1f
        p < 0.82f -> 1f - 0.58f * fall
        else -> 0.42f - 0.12f * sink
    }
    val alpha = when {
        p < 0.08f -> seg(0f, 0.08f)
        p < 0.82f -> 1f
        else -> 1f - sink
    }
    return BeltFrame(x, fall, sink, scale, alpha)
}

/** Tresărirea cutiei b cât cade o miniatură în ea (0..1). */
private fun binBump(clock: Long, b: Int, binCount: Int): Float {
    var best = 0f
    for (slot in 0 until SLOTS) {
        if (SlotBin[slot].coerceAtMost(binCount - 1) != b) continue
        val t = clock - slot * STAGGER_MS
        if (t < 0) continue
        val p = (t % PERIOD_MS) / PERIOD_MS.toFloat()
        val d = p - 0.8f
        if (d in 0f..0.12f) best = maxOf(best, sin(PI.toFloat() * d / 0.12f))
    }
    return best
}

@Composable
private fun Bin(bin: BinTick, width: Dp, sealed: Boolean, sealDelay: Int, bump: () -> Float) {
    val named = bin.name != null
    val reduced = LocalReducedMotion.current
    val count by animateIntAsState(bin.count.coerceAtLeast(0), if (reduced) snap() else tween(600), label = "binCount")
    Column(
        Modifier
            .width(width)
            .semantics(mergeDescendants = true) {
                contentDescription = (bin.name ?: "Dosar fără nume") + if (bin.count >= 0) ", ${bin.count}" else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(width = BIN_ICON_W.dp, height = BIN_ICON_H.dp)
                .graphicsLayer {
                    val s = 1f + 0.07f * bump()
                    scaleX = s; scaleY = s
                }
                .drawWithCache {
                    val m = Matrix().apply { scale(size.width / BIN_ICON_W, size.height / BIN_ICON_H) }
                    val path = PathParser().parsePathString(FOLDER_PATH).toPath().apply { transform(m) }
                    val stroke = if (named) Stroke(1.5.dp.toPx())
                    else Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                    onDrawBehind {
                        if (named) drawPath(path, Accent.copy(alpha = 0.35f))
                        drawPath(path, if (named) Accent2 else TextDim2, style = stroke)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            SealBadge(sealed, sealDelay)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(BIN_NAME_H.dp), contentAlignment = Alignment.Center) {
            Text(
                bin.name ?: "…",
                style = cond(14, 15),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.height(BIN_COUNT_H.dp), contentAlignment = Alignment.Center) {
            if (bin.count >= 0) Text(fmtCount(count), style = mono(10), maxLines = 1)
        }
    }
}

/** Bifa care „închide” cutia (pop cu arc, decalat pe cutii). */
@Composable
private fun SealBadge(sealed: Boolean, delayMs: Int) {
    com.forja.app.core.designsystem.components.PopIn(visible = sealed, delayMs = delayMs.toLong()) {
        if (sealed) {
            Box(Modifier.padding(top = 6.dp).size(18.dp).clip(CircleShape).background(Accent2), contentAlignment = Alignment.Center) {
                Icon(InvIcons.CheckBold, null, tint = Surface0, modifier = Modifier.size(11.dp))
            }
        }
    }
}

// ═════════════════════════════ Aplicarea (progres) ═════════════════════════════

/** Aplicarea în curs: aceeași bandă (miniaturile intră în dosarele cu nume), procentul și „1 092 / 3 214”. */
@Composable
fun InventoryApplyContent(state: ApplyUiState, modifier: Modifier = Modifier, sealed: Boolean = false) {
    TopBottomColumn(
        modifier = modifier.fillMaxSize().background(Surface0),
        padding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        top = {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                StampLabel("INVENTAR", rotationDeg = -4f, appear = false)
            }
            SortingBelt(recent = state.recent, bins = state.bins, animate = !sealed && !state.waiting, sealed = sealed)
            PercentRow(
                percent = if (sealed) 100 else state.percent,
                fraction = if (sealed) 1f else state.done / state.total.coerceAtLeast(1).toFloat(),
                meta = if (state.total > 0) "${fmtCount(state.done)} / ${fmtCount(state.total)}" else null,
                mascot = if (sealed) MascotState.Happy else MascotState.Thinking
            )
        },
        bottom = {
            Text(
                if (state.waiting) "AȘTEPT ACORDUL TĂU" else if (state.kind == InvKind.Photos) "MUT POZELE ÎN DOSARE" else "MUT FIȘIERELE ÎN DOSARE",
                style = mono(11, 0.08f, color = TextDim),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
