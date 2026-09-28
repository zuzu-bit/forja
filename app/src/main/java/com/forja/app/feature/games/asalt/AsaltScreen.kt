package com.forja.app.feature.games.asalt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.components.CoachMarks
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.MascotState
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
import com.forja.app.core.games.resumable
import com.forja.app.feature.games.AutoHide
import com.forja.app.feature.games.GameFeedback
import com.forja.app.feature.games.GameOverlay
import com.forja.app.feature.games.KeepScreenOn
import com.forja.app.feature.games.LevelMapActions
import com.forja.app.feature.games.LevelMapContent
import com.forja.app.feature.games.OnStop
import com.forja.app.feature.games.PauseActions
import com.forja.app.feature.games.PauseUi
import com.forja.app.feature.games.ResultActions
import com.forja.app.feature.games.ResultKind
import com.forja.app.feature.games.ResultUi
import com.forja.app.feature.games.defaultSelection
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
import kotlinx.coroutines.delay

/** Ghidul ASALT (prima partidă). */
private val AsaltGuide = listOf(
    CoachStep("asalt_field", "Tragi oriunde: muți nicovala."),
    CoachStep("asalt_field", "Atingi: lansezi scânteia."),
    CoachStep("asalt_lives", "Prinde capsulele. Nu lăsa scânteia să cadă.")
)

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
        // focusul pierdut chiar pe ultima cifră: rămâne pauza (nu se reia niciodată singură)
        if (!fg) return@LaunchedEffect
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
                AsaltEvent.PaddleHit -> {
                    fbk.sfx.play(Sfx.Paddle)
                    fbk.haptics.buzz(Buzz.Paddle)
                    if (!reduced) p.fx.anvilHit()
                }
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
    // Bucla merge și în Ready (scânteia stă pe nicovală și o urmează), dar Ready așteaptă o atingere oricât:
    // ecranul rămâne aprins doar cât scânteia chiar zboară (sau în cele 0,7 s după o viață pierdută).
    KeepScreenOn(running && (cur?.hud?.phase == AsaltPhase.Playing || cur?.hud?.phase == AsaltPhase.LifeLost))

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

    val resumeLevel = play?.engine?.takeIf { it.resumable && result == null }?.level?.id
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
                            // „i” = ghidul, peste joc. O partidă neterminată se deschide în pauză (ghidul ține atingerile,
                            // deci nimic nu trebuie să curgă sub el); altfel pornește nivelul ales, în Ready (nimic nu se
                            // mișcă până la prima atingere). Un nod blocat nu pornește niciodată de aici.
                            guideReplay = System.currentTimeMillis()
                            val sel = ui.selected
                            when {
                                resumeLevel != null -> {
                                    paused = true
                                    countdown = 0
                                    page = AsaltPage.Play
                                }
                                progress.isUnlocked(GameId.Asalt, sel) -> startLevel(sel)
                                else -> startLevel(defaultSelection(GameId.Asalt, progress, null))
                            }
                        },
                        onSelect = { mapSel = it },
                        onLongPress = {
                            if (progress.isUnlocked(GameId.Asalt, it)) mapSel = it
                            stamp = levelStamp(GameId.Asalt, it)
                        },
                        onPlay = { id ->
                            if (id == resumeLevel) {
                                page = AsaltPage.Play
                                resumeWithCountdown()
                            } else if (progress.isUnlocked(GameId.Asalt, id)) startLevel(id)
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
