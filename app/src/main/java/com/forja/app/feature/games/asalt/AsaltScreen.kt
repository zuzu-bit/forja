package com.forja.app.feature.games.asalt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.components.CoachMarks
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.games.Buzz
import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameOutcome
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.GameSessions
import com.forja.app.core.games.GameStore
import com.forja.app.core.games.Sfx
import com.forja.app.core.games.active
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltEvent
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.feature.games.AutoHide
import com.forja.app.feature.games.CountdownDigit
import com.forja.app.feature.games.GameFeedback
import com.forja.app.feature.games.GameHeader
import com.forja.app.feature.games.GameOverlay
import com.forja.app.feature.games.KeepScreenOn
import com.forja.app.feature.games.LevelMapActions
import com.forja.app.feature.games.LevelMapContent
import com.forja.app.feature.games.OnStop
import com.forja.app.feature.games.PauseActions
import com.forja.app.feature.games.PauseButton
import com.forja.app.feature.games.PauseCard
import com.forja.app.feature.games.PauseUi
import com.forja.app.feature.games.ReadyGlyph
import com.forja.app.feature.games.ResultActions
import com.forja.app.feature.games.ResultCard
import com.forja.app.feature.games.ResultKind
import com.forja.app.feature.games.ResultUi
import com.forja.app.feature.games.ThumbPad
import com.forja.app.feature.games.gameGuideKey
import com.forja.app.feature.games.levelMapUi
import com.forja.app.feature.games.levelMeta
import com.forja.app.feature.games.levelStamp
import com.forja.app.feature.games.rememberForeground
import com.forja.app.feature.games.rememberGameFeedback
import com.forja.app.feature.games.rememberGameLoop
import com.forja.app.feature.games.rememberGameSettings
import com.forja.app.feature.games.rememberInvLink
import com.forja.app.feature.inventory.InvPage
import com.forja.app.feature.inventory.InventoryLinks
import com.forja.app.feature.inventory.PillState
import com.forja.app.feature.inventory.Rule
import com.forja.app.feature.inventory.cond
import com.forja.app.feature.inventory.fmtCount
import com.forja.app.feature.inventory.mono
import kotlinx.coroutines.delay
import kotlin.math.floor

// ═════════════════════════════ Starea de pe ecran ═════════════════════════════

@Stable
internal class AsaltHud {
    var score by mutableIntStateOf(0)
    var lives by mutableIntStateOf(AsaltEngine.LIVES)
    /** Faza, ca stare: degetul din Ready apare după fiecare viață pierdută și dispare la lansare. */
    var phase by mutableStateOf(AsaltPhase.Ready)

    fun sync(e: AsaltEngine) {
        phase = e.phase
        score = e.score
        lives = e.lives
    }
}

/** O partidă pe ecran: motorul, HUD-ul, efectele și contorul de cadre pe care îl citește terenul. */
@Stable
internal class AsaltPlayState(val engine: AsaltEngine) {
    val hud = AsaltHud().also { it.sync(engine) }
    val fx = AsaltFx().also { it.clearTrail() }
    val frame = mutableLongStateOf(0L)
    var drawnVersion = engine.version
    var saveClock = 0
    private var fxWasActive = false

    fun touch(force: Boolean = false) {
        hud.sync(engine)
        val fxNow = fx.active
        if (force || engine.version != drawnVersion || fxNow || fxWasActive || engine.assist) {
            drawnVersion = engine.version
            frame.longValue = frame.longValue + 1
        }
        fxWasActive = fxNow
    }
}

internal class AsaltPlayActions(
    val onPill: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onPause: () -> Unit = {},
    val pause: PauseActions = PauseActions(),
    val result: ResultActions = ResultActions()
)

/** Ghidul ASALT (prima partidă). */
private val AsaltGuide = listOf(
    CoachStep("asalt_field", "Tragi oriunde: muți nicovala."),
    CoachStep("asalt_field", "Atingi: lansezi scânteia."),
    CoachStep("asalt_lives", "Prinde capsulele. Nu lăsa scânteia să cadă.")
)

/** Scara terenului (dp pe unitate), după games.md §4.11: S23 → 0,841 (terenul 328 × 505 dp). */
internal fun asaltUnitDp(widthDp: Float, heightDp: Float): Float =
    minOf((widthDp - 32f) / AsaltEngine.W.toFloat(), (heightDp - 12f - 44f - 8f - 40f - 64f - 12f) / AsaltEngine.H.toFloat()).coerceAtLeast(0.3f)

internal fun livesWords(n: Int): String = when (n) {
    0 -> "nicio viață"
    1 -> "o viață"
    else -> "$n vieți"
}

// ═════════════════════════════ Conținutul fără stare ═════════════════════════════

/**
 * Suprafața ASALT: antet (pastila · căștile vieților · pauză), terenul scalat la lățime, subsolul de 40 dp (mascota,
 * scorul, nivelul), apoi zona degetului. Tragerea merge oriunde sub antet. Fără buclă aici (capturile o randează).
 */
@Composable
internal fun AsaltPlayContent(
    play: AsaltPlayState,
    overlay: GameOverlay,
    pill: PillState?,
    mascot: MascotState,
    levelLabel: String,
    actions: AsaltPlayActions,
    modifier: Modifier = Modifier,
    input: (unitPx: Float) -> Modifier = { Modifier }
) {
    val reduced = LocalReducedMotion.current
    BoxWithConstraints(modifier.fillMaxSize().background(Surface0)) {
        val u = asaltUnitDp(maxWidth.value, maxHeight.value)
        val fieldW = floor(AsaltEngine.W.toFloat() * u).dp
        val fieldH = floor(AsaltEngine.H.toFloat() * u).dp
        val unitPx = with(LocalDensity.current) { u.dp.toPx() }
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            GameHeader(
                pill = pill,
                onPill = actions.onPill,
                onClose = actions.onClose,
                center = { Lives(play.hud.lives, Modifier.coachTarget("asalt_lives")) },
                trailing = { PauseButton(actions.onPause) }
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().then(input(unitPx)), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(fieldW, fieldH)
                        .coachTarget("asalt_field")
                        .semantics { contentDescription = "Terenul ASALT" }
                ) {
                    AsaltField(play.engine, play.fx, play.frame, reduced, Modifier.fillMaxSize())
                    if (overlay is GameOverlay.Ready) {
                        ReadyGlyph(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = (fieldH.value * 0.2f).dp),
                            description = "Atinge ca să lansezi"
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mascot(state = mascot, hat = MascotHat.Helmet, size = 40.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        fmtCount(play.hud.score), style = cond(22), maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = "Scor ${play.hud.score}" }
                    )
                    Spacer(Modifier.weight(1f))
                    Text(levelLabel, style = mono(10, 0.12f, color = TextDim, bold = true), maxLines = 1)
                }
                ThumbPad(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .systemGestureExclusion()
                        .semantics { contentDescription = "Zona degetului" }
                )
            }
        }
        val cover = Modifier.fillMaxSize().padding(top = 60.dp)
        when (overlay) {
            is GameOverlay.Pause -> PauseCard(overlay.ui, actions.pause, cover.padding(horizontal = 12.dp, vertical = 8.dp))
            is GameOverlay.Result -> ResultCard(overlay.ui, actions.result, cover.padding(horizontal = 12.dp, vertical = 8.dp))
            is GameOverlay.Countdown -> CountdownDigit(overlay.n, cover)
            else -> Unit
        }
    }
}

/** Căștile vieților (3): pline = rămase, conturate = pierdute. */
@Composable
private fun Lives(lives: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = livesWords(lives) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until AsaltEngine.LIVES) Helmet(i < lives)
    }
}

@Composable
private fun Helmet(on: Boolean) {
    Box(
        Modifier.size(width = 22.dp, height = 16.dp).drawWithCache {
            val w = size.width
            val h = size.height
            val dome = Path().apply {
                moveTo(w * 0.12f, h * 0.78f)
                cubicTo(w * 0.12f, h * 0.05f, w * 0.88f, h * 0.05f, w * 0.88f, h * 0.78f)
                close()
            }
            val stroke = Stroke(1.4.dp.toPx())
            onDrawBehind {
                if (on) {
                    drawPath(dome, Accent2)
                    drawRect(Accent2, topLeft = Offset(0f, h * 0.74f), size = Size(w, h * 0.16f))
                    drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f), topLeft = Offset(w * 0.3f, h * 0.3f), size = Size(w * 0.18f, h * 0.1f))
                } else {
                    drawPath(dome, Rule, style = stroke)
                    drawRect(Rule, topLeft = Offset(0f, h * 0.74f), size = Size(w, h * 0.12f))
                }
            }
        }
    )
}

// ═════════════════════════════ Ruta, cu stare ═════════════════════════════

private enum class AsaltPage { Loading, Map, Play }

/**
 * „Cât aștepți” · ASALT: harta celor 12 ziduri ↔ partida. Aceleași reguli ca ZID: partida neterminată se reia din pauză,
 * bucla rulează doar în față, salvarea la ieșire, ON_STOP, pauză și la 15 s. Lansarea e doar prin atingere.
 */
@Composable
fun AsaltGameScreen(onOpenInventory: (InvPage) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val settings = rememberGameSettings()
    val fb = rememberGameFeedback(settings)
    val progressFlow = remember(context) { GameStore.progress(context, GameId.Asalt) }
    val progress by progressFlow.collectAsState(initial = GameProgress())

    var page by rememberSaveable { mutableStateOf(AsaltPage.Loading) }
    var play by remember { mutableStateOf<AsaltPlayState?>(null) }
    var paused by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ResultUi?>(null) }
    var mapSel by remember { mutableStateOf<Int?>(null) }
    var stamp by remember { mutableStateOf<String?>(null) }
    var guideReplay by rememberSaveable { mutableLongStateOf(0L) }
    var transient by remember { mutableStateOf<MascotState?>(null) }
    var transientMs by remember { mutableLongStateOf(1_500L) }
    var transientKey by remember { mutableIntStateOf(0) }

    fun react(state: MascotState, ms: Long = 1_500L) {
        transient = state
        transientMs = ms
        transientKey++
    }

    val inv = rememberInvLink(onReadyNow = { react(MascotState.Happy, 2_000L) })
    val foreground = rememberForeground()
    val fg by rememberUpdatedState(foreground)

    LaunchedEffect(Unit) {
        val e = GameSessions.loadAsalt(context)
        if (e != null) {
            play = AsaltPlayState(e)
            paused = e.phase != AsaltPhase.Ready
            page = AsaltPage.Play
        } else {
            page = AsaltPage.Map
        }
    }

    fun persist() {
        play?.engine?.let { GameSessions.persist(context, it) }
    }

    fun pause() {
        val p = play ?: return
        if (paused || result != null || !p.engine.phase.active) return
        paused = true
        countdown = 0
        persist()
    }

    fun startLevel(id: Int) {
        val old = GameSessions.asalt
        if (old != null && old.level.id != id) GameSessions.finish(context, GameId.Asalt)
        val e = AsaltEngine.create(AsaltLevels.byId(id), System.nanoTime() xor (id.toLong() shl 40))
        GameSessions.begin(e)
        play = AsaltPlayState(e)
        paused = false
        countdown = 0
        result = null
        transient = null
        mapSel = null
        page = AsaltPage.Play
    }

    fun resumeWithCountdown() {
        if (play?.engine?.phase == AsaltPhase.Ready) {
            paused = false
            return
        }
        resumeTick++
    }

    LaunchedEffect(resumeTick) {
        if (resumeTick == 0) return@LaunchedEffect
        for (n in 3 downTo 1) {
            if (!fg) {
                countdown = 0
                return@LaunchedEffect
            }
            countdown = n
            delay(600)
        }
        countdown = 0
        paused = false
    }

    LaunchedEffect(transientKey) {
        if (transient != null) {
            delay(transientMs)
            transient = null
        }
    }

    fun openInventory(ready: Boolean) {
        pause()
        persist()
        if (ready) InventoryLinks.markReadySeen(inv.runId)
        onOpenInventory(if (ready) InvPage.Folders else InvPage.Run)
    }

    fun finishLevel(e: AsaltEngine, won: Boolean) {
        val level = e.level.id
        val prevBest = progress.bestOf(level)
        val stars = e.stars
        GameStore.recordResult(context, GameId.Asalt, level, if (won) GameOutcome.Won else GameOutcome.Lost, stars, e.score)
        GameSessions.finish(context, GameId.Asalt)
        val next = level + 1
        val hasNext = won && level < GameId.Asalt.levels
        result = ResultUi(
            kind = if (won) ResultKind.Won else ResultKind.Lost,
            stars = stars,
            score = e.score,
            best = maxOf(prevBest, e.score),
            newBest = prevBest > 0 && e.score > prevBest,
            lostLine = "Scânteia s-a stins.",
            stamp = "MISIUNE ÎNDEPLINITĂ",
            primary = when {
                hasNext -> "Nivelul $next"
                won -> "Harta"
                else -> "Reia"
            },
            primaryMeta = if (hasNext) levelMeta(GameId.Asalt, next, progress) else null,
            secondary = if (won && !hasNext) "Reia" else "Harta",
            inventoryReady = false
        )
    }

    fun handle(p: AsaltPlayState, fbk: GameFeedback) {
        val e = p.engine
        val ev = e.events
        var force = false
        for (i in 0 until ev.size) {
            when (ev.type(i)) {
                AsaltEvent.Launch -> fbk.sfx.play(Sfx.Paddle, 0.6f)
                AsaltEvent.PaddleHit -> { fbk.sfx.play(Sfx.Paddle); fbk.haptics.buzz(Buzz.Paddle) }
                AsaltEvent.BrickHit -> fbk.sfx.play(Sfx.Brick, 0.8f)
                AsaltEvent.BrickBroken -> {
                    fbk.sfx.play(Sfx.Break)
                    if (!reduced) p.fx.broken(ev.x(i), ev.y(i), ev.arg(i))
                }
                AsaltEvent.SteelHit -> fbk.sfx.play(Sfx.Steel)
                AsaltEvent.Explosion -> {
                    fbk.sfx.play(Sfx.Boom)
                    fbk.haptics.buzz(Buzz.Explosion)
                    if (!reduced) p.fx.explosion(ev.x(i), ev.y(i))
                    if (ev.arg(i) >= 3) react(MascotState.Happy)
                }
                AsaltEvent.CapsuleCaught -> {
                    fbk.sfx.play(Sfx.Capsule)
                    fbk.haptics.buzz(Buzz.Capsule)
                    react(MascotState.Happy)
                }
                AsaltEvent.LifeLost -> {
                    if (e.phase != AsaltPhase.Lost) {
                        fbk.sfx.play(Sfx.Life)
                        fbk.haptics.buzz(Buzz.LifeLost)
                        react(MascotState.Sorry)
                    }
                    if (!reduced) p.fx.hurt()
                    p.fx.clearTrail()
                    force = true
                }
                AsaltEvent.Won -> {
                    fbk.sfx.play(Sfx.Win)
                    fbk.haptics.buzz(Buzz.Win)
                    finishLevel(e, won = true)
                }
                AsaltEvent.Lost -> {
                    fbk.sfx.play(Sfx.Lose)
                    fbk.haptics.buzz(Buzz.Lose)
                    finishLevel(e, won = false)
                }
                else -> Unit
            }
        }
        ev.clear()
        p.touch(force)
    }

    val cur = play
    val running = page == AsaltPage.Play && cur != null && !paused && countdown == 0 && result == null && foreground &&
        cur.hud.phase.active
    val fbState by rememberUpdatedState(fb)
    rememberGameLoop(running) { dt ->
        val p = play ?: return@rememberGameLoop false
        p.engine.advance(dt)
        p.fx.step(dt)
        if (!reduced) p.fx.recordTrail(p.engine)
        handle(p, fbState)
        if (p.engine.phase == AsaltPhase.Playing) {
            p.saveClock += dt
            if (p.saveClock >= 15_000) {
                p.saveClock = 0
                GameSessions.persist(context, p.engine)
            }
        }
        false
    }
    KeepScreenOn(running)

    LaunchedEffect(foreground) {
        if (!foreground) {
            val p = play
            if (p != null && page == AsaltPage.Play && p.engine.phase != AsaltPhase.Ready) pause()
        }
    }
    OnStop { persist() }
    val playRef by rememberUpdatedState(play)
    DisposableEffect(Unit) {
        onDispose { playRef?.engine?.let { GameSessions.persist(context, it) } }
    }

    BackHandler(enabled = page == AsaltPage.Play) {
        val p = play
        when {
            result != null -> { result = null; page = AsaltPage.Map }
            countdown > 0 -> Unit
            p != null && !paused && p.engine.phase.active && p.engine.phase != AsaltPhase.Ready -> pause()
            else -> page = AsaltPage.Map
        }
    }

    val onDrag = rememberUpdatedState<(Float) -> Unit> { dx ->
        val p = play
        if (p != null && result == null && !paused && countdown == 0) {
            p.engine.setPaddleTarget(p.engine.target + dx)
        }
    }
    val onTap = rememberUpdatedState<() -> Unit> {
        val p = play
        if (p != null && result == null && !paused && countdown == 0 && p.engine.launch()) handle(p, fb)
    }

    val resumeLevel = play?.engine?.takeIf { it.phase.active && result == null }?.level?.id
    AutoHide(stamp) { stamp = null }

    // Ca modurile de așteptare din 4.3: sub bara de stare și deasupra barei de navigare (ecranul e edge-to-edge).
    Box(Modifier.fillMaxSize().background(Surface0).statusBarsPadding().navigationBarsPadding()) {
        when (page) {
            AsaltPage.Loading -> Box(Modifier.fillMaxSize().background(Surface0))
            AsaltPage.Map -> {
                val ui = levelMapUi(GameId.Asalt, progress, mapSel, resumeLevel, inv.pill, stamp)
                LevelMapContent(
                    state = ui,
                    actions = LevelMapActions(
                        onPill = { openInventory(inv.ready) },
                        onClose = onClose,
                        onInfo = {
                            guideReplay = System.currentTimeMillis()
                            val id = ui.selected
                            if (id == resumeLevel) { page = AsaltPage.Play; resumeWithCountdown() } else startLevel(id)
                        },
                        onSelect = { mapSel = it },
                        onLongPress = { mapSel = it; stamp = levelStamp(GameId.Asalt, it) },
                        onPlay = { id ->
                            if (id == resumeLevel) {
                                page = AsaltPage.Play
                                resumeWithCountdown()
                            } else startLevel(id)
                        }
                    )
                )
            }
            AsaltPage.Play -> {
                val p = play
                if (p == null) {
                    LaunchedEffect(Unit) { page = AsaltPage.Map }
                    Box(Modifier.fillMaxSize().background(Surface0))
                } else {
                    val e = p.engine
                    val level = e.level
                    val base = when {
                        result?.kind == ResultKind.Won -> MascotState.Happy
                        result != null -> MascotState.Sorry
                        paused -> MascotState.Thinking
                        p.hud.lives == 1 -> MascotState.Angry
                        else -> MascotState.Idle
                    }
                    val overlay = when {
                        result != null -> GameOverlay.Result(result!!.copy(inventoryReady = inv.ready))
                        countdown > 0 -> GameOverlay.Countdown(countdown)
                        paused -> GameOverlay.Pause(
                            PauseUi(levelMeta = "NIVELUL ${level.id}", inventoryReady = inv.ready, sfx = settings.sfx, haptics = settings.haptics)
                        )
                        p.hud.phase == AsaltPhase.Ready -> GameOverlay.Ready
                        else -> GameOverlay.None
                    }
                    CoachMarks(screen = gameGuideKey("joc_asalt", guideReplay), steps = AsaltGuide) {
                        AsaltPlayContent(
                            play = p,
                            overlay = overlay,
                            pill = inv.pill,
                            mascot = transient ?: base,
                            levelLabel = "NIV. ${level.id} · ${level.name.uppercase()}",
                            actions = AsaltPlayActions(
                                onPill = { openInventory(inv.ready) },
                                onClose = { persist(); onClose() },
                                onPause = { pause() },
                                pause = PauseActions(
                                    onResume = { resumeWithCountdown() },
                                    onRestart = { startLevel(level.id) },
                                    onMap = { page = AsaltPage.Map },
                                    onInventory = { openInventory(true) },
                                    onSfx = { GameStore.setSfx(context, !settings.sfx) },
                                    onHaptics = { GameStore.setHaptics(context, !settings.haptics) }
                                ),
                                result = ResultActions(
                                    onPrimary = {
                                        val r = result
                                        when {
                                            r?.kind == ResultKind.Won && level.id < GameId.Asalt.levels -> startLevel(level.id + 1)
                                            r?.kind == ResultKind.Won -> { result = null; page = AsaltPage.Map }
                                            else -> startLevel(level.id)
                                        }
                                    },
                                    onSecondary = {
                                        val r = result
                                        if (r?.kind == ResultKind.Won && level.id >= GameId.Asalt.levels) startLevel(level.id)
                                        else { result = null; page = AsaltPage.Map }
                                    },
                                    onInventory = { openInventory(true) }
                                )
                            ),
                            input = { unitPx ->
                                Modifier.asaltGestures(
                                    unitPx,
                                    enabled = result == null && !paused && countdown == 0,
                                    onDrag = { onDrag.value(it) },
                                    onTap = { onTap.value() }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
