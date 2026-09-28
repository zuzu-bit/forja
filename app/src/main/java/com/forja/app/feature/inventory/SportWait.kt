package com.forja.app.feature.inventory

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.WorkoutSessionEntity
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.PulseGlow
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Inventory
import kotlinx.coroutines.launch

// ═════════════════════════════ Stările și planurile ═════════════════════════════

/** Cum te simți (Sport.dc.html): durata, fața mascotei, nuanța și cele 5 exerciții ale planului. */
enum class Mood(val label: String, val minutes: Int, val pose: MascotState, val tint: Color, val plan: List<Move>) {
    Tired("Obosit", 8, MascotState.Sorry, MoodBlue.copy(alpha = 0.20f), listOf(Move.Stretch, Move.Lunge, Move.Squat, Move.Plank, Move.Breath)),
    Restless("Neliniștit", 10, MascotState.Thinking, MoodViolet.copy(alpha = 0.20f), listOf(Move.Breath, Move.Squat, Move.Plank, Move.Lunge, Move.Stretch)),
    Energized("Plin de energie", 15, MascotState.Happy, Amber.copy(alpha = 0.20f), listOf(Move.Jacks, Move.Pushup, Move.Squat, Move.Box, Move.Plank)),
    Angry("Nervos", 10, MascotState.Angry, MoodRed.copy(alpha = 0.18f), listOf(Move.Box, Move.Jacks, Move.Pushup, Move.Squat, Move.Breath))
}

internal const val WORK_MS = 40_000L
internal const val REST_MS = 20_000L

/** Starea unui antrenament pe intervale (40 s lucru / 20 s pauză), numărată de un ceas pe cadre. */
@Stable
class IntervalRun(val mood: Mood) {
    val total: Int = mood.minutes
    var index by mutableIntStateOf(0)
    var rest by mutableStateOf(false)
    var remainingMs by mutableLongStateOf(WORK_MS)
    var paused by mutableStateOf(false)
    /** Ceasul figurii (ms), oprit pe pauză. */
    var animMs by mutableLongStateOf(0L)
    /** Timpul activ (fără pauzele cerute de om), pentru minutele din final. */
    var activeMs = 0L
    var completed by mutableIntStateOf(0)
    var finished by mutableStateOf(false)
    val startedAt: Long = System.currentTimeMillis()

    fun move(i: Int = index): Move = mood.plan[i % mood.plan.size]

    /** Trece la faza următoare; true dacă antrenamentul s-a terminat. */
    fun advance(): Boolean {
        if (!rest) {
            completed = (completed + 1).coerceAtMost(total)
            if (index >= total - 1) {
                finished = true
                return true
            }
            rest = true
            remainingMs = REST_MS
        } else {
            rest = false
            index += 1
            remainingMs = WORK_MS
            animMs = 0L
        }
        return false
    }

    val phaseMs: Long get() = if (rest) REST_MS else WORK_MS
}

private enum class SportPage { Mood, Interval, Summary }

/**
 * S3b — Sport după stare: „Cum te simți?” (4 fețe) → intervalele (figura animată în inel) → finalul
 * (mascota fericită, „15 MIN | 15 EXERCIȚII”), salvat ca sesiune „Cât aștepți · <stare>”.
 */
@Composable
fun InventorySportScreen(onOpenInventory: (InvPage) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val progress by Inventory.progress.collectAsState()
    val pill = pillStateOf(progress)
    val onPill: () -> Unit = { onOpenInventory(if (progress?.stage == InvStage.Ready) InvPage.Folders else InvPage.Run) }
    var page by rememberSaveable { mutableStateOf(SportPage.Mood) }
    var mood by rememberSaveable { mutableStateOf(Mood.Energized) }
    var run by remember { mutableStateOf<IntervalRun?>(null) }
    var summary by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }   // minute, exerciții
    val reduced = LocalReducedMotion.current

    fun finish(r: IntervalRun) {
        val done = r.completed
        if (done <= 0) {
            run = null
            page = SportPage.Mood
            return
        }
        val end = System.currentTimeMillis()
        val minutes = ((r.activeMs + 30_000) / 60_000).toInt().coerceAtLeast(1)
        summary = minutes to done
        val app = ForjaApp.from(context)
        app.appScope.launch {
            try {
                app.db.workoutDao().insertSession(
                    WorkoutSessionEntity(
                        planId = WAIT_PLAN_ID_BASE - r.mood.ordinal,
                        planName = "Cât aștepți · ${r.mood.label}",
                        startedAt = r.startedAt,
                        endedAt = end,
                        totalSets = done
                    )
                )
            } catch (_: Exception) {
            }
        }
        run = null
        page = SportPage.Summary
    }

    BackHandler(enabled = page == SportPage.Interval) { run?.let { finish(it) } }

    Box(Modifier.fillMaxSize().background(Surface0).statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 240)) togetherWith fadeOut(tween(if (reduced) 0 else 160)) },
            label = "sportPage"
        ) { p ->
            when (p) {
                SportPage.Mood -> SportMoodContent(
                    selected = mood,
                    pill = pill,
                    onPill = onPill,
                    onPick = { mood = it },
                    onStart = {
                        run = IntervalRun(mood)
                        page = SportPage.Interval
                    }
                )
                SportPage.Interval -> {
                    val r = run
                    if (r == null) {
                        LaunchedEffect(Unit) { page = SportPage.Mood }
                    } else {
                        IntervalPlayer(r, pill, onPill, onStop = { finish(r) }, onFinished = { finish(r) })
                    }
                }
                SportPage.Summary -> SportSummaryContent(
                    minutes = summary?.first ?: mood.minutes,
                    exercises = summary?.second ?: 0,
                    pill = pill,
                    onPill = onPill,
                    onClose = onClose
                )
            }
        }
    }
}

/** planId-urile sesiunilor „Cât aștepți” (negative, ca să nu atingă planurile reale). */
private const val WAIT_PLAN_ID_BASE = -1

// ═════════════════════════════ Starea ═════════════════════════════

/** Antetul modurilor de așteptare: pastila (stânga) și ștampila sau altceva (dreapta). */
@Composable
internal fun WaitHeader(pill: PillState?, onPill: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (pill != null) InvProgressPill(pill, onPill) else Spacer(Modifier.size(1.dp))
        trailing()
    }
}

/** S3b (Sport.dc.html): „Cum te simți?”, 4 carduri cu mascota, banda de 5 pictograme, „Pornește · 15 MIN”. */
@Composable
fun SportMoodContent(
    selected: Mood,
    pill: PillState?,
    onPill: () -> Unit,
    onPick: (Mood) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopBottomColumn(
        modifier = modifier.fillMaxSize().background(Surface0),
        padding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp),
        gap = 16.dp,
        top = {
            WaitHeader(pill, onPill) { StampLabel("SPORT", rotationDeg = -4f, appear = false) }
            Text("Cum te simți?", style = cond(44, 44, tracking = 0.005f), modifier = Modifier.padding(top = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Mood.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { m -> MoodCard(m, m == selected, { onPick(m) }, Modifier.weight(1f)) }
                    }
                }
            }
            PlanStrip(selected, Modifier.padding(top = 4.dp))
        },
        bottom = {
            InvPrimaryButton("Pornește", onStart, meta = "${selected.minutes} MIN")
        }
    )
}

@Composable
private fun MoodCard(m: Mood, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val glow by animateFloatAsState(if (on) 1f else 0f, if (reduced) snap() else tween(200), label = "moodGlow")
    val borderW by animateDpAsState(if (on) 2.dp else 1.dp, if (reduced) snap() else tween(200), label = "moodBorder")
    val borderC by animateColorAsState(if (on) Accent2 else W09, if (reduced) snap() else tween(200), label = "moodBorderC")
    Box(
        modifier
            .pressable(onClick)
            .height(196.dp)
            .drawBehind {
                if (glow > 0.01f) {
                    val sw = 4.dp.toPx()
                    drawRoundRect(
                        Accent2.copy(alpha = 0.14f * glow), topLeft = Offset(-sw / 2, -sw / 2),
                        size = Size(size.width + sw, size.height + sw), cornerRadius = CornerRadius(8.dp.toPx() + sw / 2), style = Stroke(sw)
                    )
                }
            }
            .clip(R8)
            .background(Surface1)
            .border(borderW, borderC, R8)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                contentDescription = "${m.label}, ${m.minutes} minute"
                stateDescription = if (on) "ales" else "neales"
            }
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 14.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(R6)
                    .drawBehind {
                        drawCircle(
                            Brush.radialGradient(listOf(m.tint, Color.Transparent), center = center, radius = size.minDimension / 2f),
                            radius = size.minDimension / 2f, center = center
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Mascot(state = m.pose, size = 100.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(m.label, style = cond(23, 24), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.minutes} MIN", style = mono(11, 0.12f, color = if (on) Amber else TextDim))
            }
        }
        if (on) {
            Box(
                Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(Accent2),
                contentAlignment = Alignment.Center
            ) {
                Icon(InvIcons.CheckBold, null, tint = Surface0, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Banda planului: 5 pictograme pe o linie punctată; apar în trepte (60 ms) la schimbarea stării. */
@Composable
private fun PlanStrip(mood: Mood, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    Box(modifier.fillMaxWidth().height(58.dp)) {
        Canvas(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 28.dp)) {
            drawLine(
                Accent2.copy(alpha = 0.45f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            mood.plan.forEachIndexed { i, mv ->
                val a = remember(mood) { Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(mood) { if (!reduced) { kotlinx.coroutines.delay(i * 60L); a.animateTo(1f, tween(350)) } }
                Box(
                    Modifier
                        .size(58.dp)
                        .graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 6.dp.toPx() }
                        .clip(R6)
                        .background(Surface1)
                        .border(1.dp, W10, R6)
                        .semantics { contentDescription = mv.label },
                    contentAlignment = Alignment.Center
                ) {
                    MovePictogram(mv, Modifier.size(44.dp))
                }
            }
        }
    }
}

// ═════════════════════════════ Intervalele ═════════════════════════════

/** Bip scurt la schimbarea fazei (ToneGenerator; fără sunet dacă sistemul nu îl dă). */
private class Beeper {
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (_: Exception) { null }
    fun beep(ms: Int = 160) { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, ms) } catch (_: Exception) { } }
    fun end() { try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400) } catch (_: Exception) { } }
    fun release() { try { tone?.release() } catch (_: Exception) { } }
}

@Composable
private fun IntervalPlayer(run: IntervalRun, pill: PillState?, onPill: () -> Unit, onStop: () -> Unit, onFinished: () -> Unit) {
    val beeper = remember { Beeper() }
    DisposableEffect(Unit) { onDispose { beeper.release() } }
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    LaunchedEffect(run) {
        var last = withFrameMillis { it }
        while (!run.finished) {
            withFrameMillis { now ->
                val dt = (now - last).coerceIn(0L, 250L)
                last = now
                if (!run.paused) {
                    run.animMs += dt
                    run.activeMs += dt
                    run.remainingMs -= dt
                    if (run.remainingMs <= 0L) {
                        if (run.advance()) beeper.end() else beeper.beep()
                    }
                }
            }
        }
        onFinished()
    }
    IntervalContent(
        run = run,
        pill = pill,
        onPill = onPill,
        onStop = onStop,
        onToggle = { run.paused = !run.paused },
        onSkip = {
            if (run.advance()) beeper.end() else beeper.beep()
        }
    )
}

/** S3b · intervale (Interval.dc.html): inelul de 280 dp care se golește, figura, 0:40, numele, LUCRU / PAUZĂ, 3 / 15. */
@Composable
fun IntervalContent(
    run: IntervalRun,
    pill: PillState?,
    onPill: () -> Unit,
    onStop: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val secs by remember(run) { derivedStateOf { ((run.remainingMs + 999) / 1000).coerceAtLeast(0) } }
    val move = run.move()
    val next = if (run.rest) run.move() else run.move(run.index + 1)
    val isLast = !run.rest && run.index >= run.total - 1
    val phaseColor = if (run.rest) Accent2 else Amber
    // Mișcare redusă: figura stă în poza de start (ca în prototip); inelul și cronometrul merg mai departe.
    val still = LocalReducedMotion.current
    TopBottomColumn(
        modifier = modifier.fillMaxSize().background(Surface0),
        padding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp),
        gap = 12.dp,
        top = {
            WaitHeader(pill, onPill) { DurationChip(run.mood) }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(280.dp), contentAlignment = Alignment.TopStart) {
                    Canvas(Modifier.fillMaxSize()) {
                        val u = size.width / 280f
                        val sw = 10f * u
                        val r = 128f * u
                        val tl = Offset(center.x - r, center.y - r)
                        drawCircle(Surface2, r, center, style = Stroke(sw))
                        val frac = (run.remainingMs.toFloat() / run.phaseMs).coerceIn(0f, 1f)
                        if (frac > 0f) drawArc(phaseColor, -90f, 360f * frac, false, topLeft = tl, size = Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
                    }
                    ExerciseFigure(
                        move = if (run.rest) next else move,
                        seconds = { if (still) 0f else run.animMs / 1000f },
                        dim = run.rest,
                        modifier = Modifier.padding(start = 55.dp, top = 50.dp).size(170.dp)
                    )
                }
            }
            Text(
                fmtClock(secs * 1000L),
                style = hero(72, 66),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (run.paused) 0.5f else 1f }
            )
            Text(
                if (run.rest) "Pauză" else move.label,
                style = cond(34, 34, color = if (run.rest) TextSecondary else TextPrimary),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(24.dp)
                        .clip(R4)
                        .background(phaseColor.copy(alpha = 0.14f))
                        .border(1.dp, phaseColor.copy(alpha = 0.55f), R4)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (run.rest) "PAUZĂ" else "LUCRU", style = mono(10, 0.18f, color = phaseColor, bold = true))
                }
                Spacer(Modifier.width(10.dp))
                Text("${run.index + 1} / ${run.total}", style = mono(12))
            }
            Segments(run.total, run.index, Modifier.fillMaxWidth())
        },
        bottom = {
            if (!isLast || run.rest) NextCard(next)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(InvIcons.Stop, "Oprește antrenamentul", 56.dp, onStop)
                Box(
                    Modifier
                        .pressable(onToggle)
                        .size(84.dp)
                        .shadow(28.dp, CircleShape, ambientColor = Accent, spotColor = Accent)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Accent, Accent2)))
                        .semantics { role = Role.Button; contentDescription = if (run.paused) "Continuă" else "Pauză" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (run.paused) InvIcons.Play else InvIcons.Pause, null, tint = OnAccent, modifier = Modifier.size(30.dp))
                }
                RoundButton(InvIcons.Skip, "Sari la următorul", 56.dp, onSkip)
            }
        }
    )
}

@Composable
private fun DurationChip(mood: Mood) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Surface1)
            .border(1.dp, W12, RoundedCornerShape(18.dp))
            .padding(start = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mascot(state = MascotState.Happy, size = 34.dp)
        Spacer(Modifier.width(6.dp))
        Text("${mood.minutes} MIN", style = mono(11, 0.08f, color = Amber, bold = true))
    }
}

@Composable
private fun Segments(total: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        val w = if (total <= 15) 18.dp else (300 / total).dp
        repeat(total) { k ->
            Box(
                Modifier
                    .size(width = w, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (k < current) Accent2 else if (k == current) Amber else Track)
            )
        }
    }
}

@Composable
private fun NextCard(next: Move) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W08, R8)
            .padding(start = 10.dp, top = 10.dp, end = 14.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Urmează ${next.label}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(R6).background(Raised), contentAlignment = Alignment.Center) {
            MovePictogram(next, Modifier.size(36.dp), color = TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Text(next.label, style = cond(21), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("URMEAZĂ", style = mono(10, 0.16f, color = TextDim, bold = true))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(onClick)
            .size(size)
            .clip(CircleShape)
            .background(Surface1)
            .border(1.dp, W10, CircleShape)
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(if (icon == InvIcons.Skip) 22.dp else 20.dp))
    }
}

// ═════════════════════════════ Finalul ═════════════════════════════

/** Finalul antrenamentului: mascota fericită, „Misiune îndeplinită.”, „10 MIN | 8 EXERCIȚII”, „Închide”. */
@Composable
fun SportSummaryContent(
    minutes: Int,
    exercises: Int,
    pill: PillState?,
    onPill: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopBottomColumn(
        modifier = modifier.fillMaxSize().background(Surface0),
        padding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp),
        gap = 18.dp,
        top = {
            WaitHeader(pill, onPill) { StampLabel("SPORT", rotationDeg = -4f, appear = false) }
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                PulseGlow(radius = 120.dp, color = Amber, minAlpha = 0.10f, maxAlpha = 0.26f) {
                    Mascot(state = MascotState.Happy, size = 190.dp)
                }
            }
            Text("Misiune îndeplinită.", style = cond(34, 38), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.Center) {
                SummaryStat("$minutes", "MIN", TextPrimary)
                Spacer(Modifier.width(22.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(W10))
                Spacer(Modifier.width(22.dp))
                SummaryStat("$exercises", if (exercises == 1) "EXERCIȚIU" else "EXERCIȚII", Amber)
            }
        },
        bottom = {
            InvPrimaryButton("Închide", onClose)
        }
    )
}

@Composable
private fun SummaryStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = hero(38, 38, color = color))
        Text(label, style = mono(10, 0.16f, color = TextDim))
    }
}
