package com.forja.app.feature.games.zid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.forja.app.core.games.ZID_ENDLESS
import com.forja.app.core.games.active
import com.forja.app.core.games.resumable
import com.forja.app.core.games.zid.ZidCmd
import com.forja.app.core.games.zid.ZidConfig
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidEvent
import com.forja.app.core.games.zid.ZidGoal
import com.forja.app.core.games.zid.ZidLevels
import com.forja.app.core.games.zid.ZidPhase
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

/** Ghidul ZID (prima partidă): tabla, placa, rezerva. ≤ 60 de caractere pe pas. */
private val ZidGuide = listOf(
    CoachStep("zid_board", "Atingi: piesa se rotește."),
    CoachStep("zid_board", "Tragi stânga-dreapta: o muți."),
    CoachStep("zid_pad", "Ții apăsat: coboară. Tragi jos repede: cade."),
    CoachStep("zid_hold", "Tragi în sus: o pui în rezervă.")
)

// ═════════════════════════════ Ruta, cu stare ═════════════════════════════

private enum class ZidPage { Loading, Map, Play }

/**
 * „Cât aștepți” · ZID: harta nivelurilor ↔ partida. Deschisă cu o partidă neterminată, merge direct la joc, cu pauza.
 * Bucla rulează doar în joc, fără pauză, cu ecranul în față; la ieșire, ON_STOP, pauză și la 15 s partida se salvează.
 */
@Composable
fun ZidGameScreen(onOpenInventory: (InvPage) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val cfg = remember(reduced) { ZidConfig(clearDelayMs = if (reduced) 0 else 260) }
    val settings = rememberGameSettings()
    val fb = rememberGameFeedback(settings)
    val progressFlow = remember(context) { GameStore.progress(context, GameId.Zid) }
    val progress by progressFlow.collectAsState(initial = GameProgress())

    var page by rememberSaveable { mutableStateOf(ZidPage.Loading) }
    var play by remember { mutableStateOf<ZidPlayState?>(null) }
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
    var quadKey by remember { mutableIntStateOf(0) }
    var quad by remember { mutableStateOf(false) }

    fun react(state: MascotState, ms: Long = 1_500L) {
        transient = state
        transientMs = ms
        transientKey++
    }

    val inv = rememberInvLink(onReadyNow = { react(MascotState.Happy, 2_000L) })
    val foreground = rememberForeground()
    val fg by rememberUpdatedState(foreground)

    // ── partida neterminată: din memorie sau de pe disc ──
    LaunchedEffect(Unit) {
        val e = GameSessions.loadZid(context, cfg)
        if (e != null) {
            play = ZidPlayState(e)
            paused = e.phase != ZidPhase.Ready
            page = ZidPage.Play
        } else {
            page = ZidPage.Map
        }
    }

    fun persist() {
        play?.engine?.let { GameSessions.persist(context, it) }
    }

    fun pause() {
        val p = play ?: return
        if (paused || result != null || !p.engine.phase.active) return
        p.engine.input(ZidCmd.SoftOff)
        p.engine.clearEvents()
        paused = true
        countdown = 0
        persist()
    }

    fun startLevel(id: Int) {
        val old = GameSessions.zid
        if (old != null && old.level.id != id) GameSessions.finish(context, GameId.Zid)
        val e = ZidEngine.create(ZidLevels.byId(id), System.nanoTime() xor (id.toLong() shl 40), cfg)
        GameSessions.begin(e)
        play = ZidPlayState(e)
        paused = false
        countdown = 0
        result = null
        quad = false
        transient = null
        mapSel = null
        page = ZidPage.Play
    }

    fun resumeWithCountdown() {
        if (play?.engine?.phase == ZidPhase.Ready) {
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
    LaunchedEffect(quadKey) {
        if (quadKey > 0) {
            quad = true
            delay(900)
            quad = false
        }
    }

    fun openInventory(ready: Boolean) {
        pause()
        persist()
        if (ready) InventoryLinks.markReadySeen(inv.runId)
        onOpenInventory(if (ready) InvPage.Folders else InvPage.Run)
    }

    fun finishLevel(e: ZidEngine, outcome: GameOutcome) {
        val level = e.level.id
        val prevBest = progress.bestOf(level)
        val stars = if (outcome == GameOutcome.Won) e.stars else 0
        GameStore.recordResult(context, GameId.Zid, level, outcome, stars, e.score)
        GameSessions.finish(context, GameId.Zid)
        val next = if (outcome == GameOutcome.Won) {
            if (level < GameId.Zid.levels) level + 1 else ZID_ENDLESS
        } else level
        val primary = when (outcome) {
            GameOutcome.Won -> if (next == ZID_ENDLESS) "Fără sfârșit" else "Nivelul $next"
            else -> "Reia"
        }
        result = ResultUi(
            kind = when (outcome) {
                GameOutcome.Won -> ResultKind.Won
                GameOutcome.Lost -> ResultKind.Lost
                GameOutcome.EndlessOver -> ResultKind.EndlessOver
            },
            stars = stars,
            score = e.score,
            best = maxOf(prevBest, e.score),
            newBest = prevBest > 0 && e.score > prevBest,
            lostLine = "Zidul a căzut.",
            stamp = "MISIUNE ÎNDEPLINITĂ",
            primary = primary,
            primaryMeta = if (outcome == GameOutcome.Won && next != ZID_ENDLESS) levelMeta(GameId.Zid, next, progress) else null,
            secondary = "Harta",
            inventoryReady = false
        )
    }

    /** Evenimentele de după o comandă sau un pas: sunet, vibrație, mascota, efecte, finalul. */
    fun handle(p: ZidPlayState, fbk: GameFeedback) {
        val e = p.engine
        val ev = e.events
        for (i in ev.indices) {
            when (ev[i]) {
                ZidEvent.Moved -> { fbk.sfx.play(Sfx.Move); fbk.haptics.buzz(Buzz.Move) }
                ZidEvent.Rotated -> { fbk.sfx.play(Sfx.Rotate); fbk.haptics.buzz(Buzz.Rotate) }
                ZidEvent.HardDropped -> fbk.haptics.buzz(Buzz.HardDrop)
                ZidEvent.Locked -> fbk.sfx.play(Sfx.Lock, 0.8f)
                ZidEvent.Held -> fbk.sfx.play(Sfx.Hold)
                ZidEvent.Cleared -> {
                    val n = e.lastCleared
                    fbk.sfx.play(
                        when (n) {
                            1 -> Sfx.Clear1
                            2 -> Sfx.Clear2
                            3 -> Sfx.Clear3
                            else -> Sfx.Clear4
                        }
                    )
                    fbk.haptics.buzz(if (n >= 4) Buzz.Quad else Buzz.Clear)
                    if (!reduced) p.fx.onCleared(e)
                    if (n >= 4) {
                        react(MascotState.Wink)
                        if (!reduced) quadKey++
                    } else if (n >= 2) react(MascotState.Happy)
                }
                ZidEvent.Won -> {
                    fbk.sfx.play(Sfx.Win)
                    fbk.haptics.buzz(Buzz.Win)
                    finishLevel(e, GameOutcome.Won)
                }
                ZidEvent.Lost -> {
                    fbk.sfx.play(Sfx.Lose)
                    fbk.haptics.buzz(Buzz.Lose)
                    finishLevel(e, if (e.isEndless) GameOutcome.EndlessOver else GameOutcome.Lost)
                }
                else -> Unit
            }
        }
        e.clearEvents()
        if (!reduced) p.fx.onCollapse(e) else p.fx.syncCollapse(e)
        p.touch()
    }

    // ── bucla ──
    val cur = play
    val running = page == ZidPage.Play && cur != null && !paused && countdown == 0 && result == null && foreground &&
        (cur.hud.phase == ZidPhase.Falling || cur.hud.phase == ZidPhase.Clearing)
    val fbState by rememberUpdatedState(fb)
    rememberGameLoop(running) { dt ->
        val p = play ?: return@rememberGameLoop false
        p.engine.advance(dt)
        p.fx.step(dt)
        handle(p, fbState)
        p.saveClock += dt
        if (p.saveClock >= 15_000) {
            p.saveClock = 0
            GameSessions.persist(context, p.engine)
        }
        false
    }
    KeepScreenOn(running)

    // ── pauza automată (ecran stins, bara de notificări, alt ecran); nu se reia singură ──
    LaunchedEffect(foreground) {
        if (!foreground) {
            val p = play
            if (p != null && page == ZidPage.Play && p.engine.phase != ZidPhase.Ready) pause()
        }
    }
    OnStop { persist() }
    val playRef by rememberUpdatedState(play)
    DisposableEffect(Unit) {
        onDispose {
            playRef?.engine?.let {
                it.input(ZidCmd.SoftOff)
                it.clearEvents()
                GameSessions.persist(context, it)
            }
        }
    }

    BackHandler(enabled = page == ZidPage.Play) {
        val p = play
        when {
            result != null -> { result = null; page = ZidPage.Map }
            countdown > 0 -> Unit
            p != null && !paused && p.engine.phase.active && p.engine.phase != ZidPhase.Ready -> pause()
            else -> page = ZidPage.Map
        }
    }

    // Gesturile ajung la ultima versiune a acestui cod (pointerInput își păstrează corutina între recompoziții).
    val onGesture = rememberUpdatedState<(ZidGesture) -> Unit> { g ->
        val pp = play
        if (pp != null && result == null && !paused && countdown == 0) {
            val en = pp.engine
            if (en.phase == ZidPhase.Ready) {
                if (g == ZidGesture.Tap) {
                    en.start()
                    handle(pp, fb)
                }
            } else {
                val cmd = when (g) {
                    ZidGesture.Tap -> ZidCmd.RotateCw
                    ZidGesture.Left -> ZidCmd.Left
                    ZidGesture.Right -> ZidCmd.Right
                    ZidGesture.SoftOn -> ZidCmd.SoftOn
                    ZidGesture.SoftOff -> ZidCmd.SoftOff
                    ZidGesture.HardDrop -> ZidCmd.HardDrop
                    ZidGesture.Hold -> ZidCmd.Hold
                }
                en.input(cmd)
                handle(pp, fb)
            }
        }
    }

    val resumeLevel = play?.engine?.takeIf { it.resumable && result == null }?.level?.id
    AutoHide(stamp) { stamp = null }

    // Ca modurile de așteptare din 4.3: sub bara de stare și deasupra barei de navigare (ecranul e edge-to-edge).
    Box(Modifier.fillMaxSize().background(Surface0).statusBarsPadding().navigationBarsPadding()) {
        when (page) {
            ZidPage.Loading -> Box(Modifier.fillMaxSize().background(Surface0))
            ZidPage.Map -> {
                val ui = levelMapUi(GameId.Zid, progress, mapSel, resumeLevel, inv.pill, stamp)
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
                                    page = ZidPage.Play
                                }
                                progress.isUnlocked(GameId.Zid, sel) -> startLevel(sel)
                                else -> startLevel(defaultSelection(GameId.Zid, progress, null))
                            }
                        },
                        onSelect = { mapSel = it },
                        onLongPress = {
                            if (progress.isUnlocked(GameId.Zid, it)) mapSel = it
                            stamp = levelStamp(GameId.Zid, it)
                        },
                        onPlay = { id ->
                            if (id == resumeLevel) {
                                page = ZidPage.Play
                                resumeWithCountdown()
                            } else if (progress.isUnlocked(GameId.Zid, id)) startLevel(id)
                        }
                    )
                )
            }
            ZidPage.Play -> {
                val p = play
                if (p == null) {
                    LaunchedEffect(Unit) { page = ZidPage.Map }
                    Box(Modifier.fillMaxSize().background(Surface0))
                } else {
                    val e = p.engine
                    val level = e.level
                    val base = when {
                        result?.kind == ResultKind.Won -> MascotState.Happy
                        result != null -> MascotState.Sorry
                        paused -> MascotState.Thinking
                        p.hud.danger -> MascotState.Angry
                        else -> MascotState.Idle
                    }
                    val overlay = when {
                        result != null -> GameOverlay.Result(result!!.copy(inventoryReady = inv.ready))
                        countdown > 0 -> GameOverlay.Countdown(countdown)
                        paused -> GameOverlay.Pause(
                            PauseUi(
                                levelMeta = if (level.goal == ZidGoal.Endless) "FĂRĂ SFÂRȘIT" else "NIVELUL ${level.id}",
                                inventoryReady = inv.ready,
                                sfx = settings.sfx,
                                haptics = settings.haptics
                            )
                        )
                        p.hud.phase == ZidPhase.Ready -> GameOverlay.Ready
                        else -> GameOverlay.None
                    }
                    val label = if (level.goal == ZidGoal.Endless) "RANG ${p.hud.rank}" else "NIV. ${level.id}"
                    CoachMarks(screen = gameGuideKey("joc_zid", guideReplay), steps = ZidGuide) {
                        ZidPlayContent(
                            play = p,
                            overlay = overlay,
                            pill = inv.pill,
                            mascot = transient ?: base,
                            levelLabel = label,
                            clearDelayMs = cfg.clearDelayMs,
                            quad = quad,
                            actions = ZidPlayActions(
                                onPill = { openInventory(inv.ready) },
                                onClose = { persist(); onClose() },
                                onPause = { pause() },
                                pause = PauseActions(
                                    onResume = { resumeWithCountdown() },
                                    onRestart = { startLevel(level.id) },
                                    onMap = { page = ZidPage.Map },
                                    onInventory = { openInventory(true) },
                                    onSfx = { GameStore.setSfx(context, !settings.sfx) },
                                    onHaptics = { GameStore.setHaptics(context, !settings.haptics) }
                                ),
                                result = ResultActions(
                                    onPrimary = {
                                        val r = result
                                        if (r?.kind == ResultKind.Won) {
                                            startLevel(if (level.id < GameId.Zid.levels) level.id + 1 else ZID_ENDLESS)
                                        } else startLevel(level.id)
                                    },
                                    onSecondary = { result = null; page = ZidPage.Map },
                                    onInventory = { openInventory(true) }
                                )
                            ),
                            input = { cellPx ->
                                Modifier.zidGestures(cellPx, enabled = result == null && !paused && countdown == 0) { g -> onGesture.value(g) }
                            }
                        )
                    }
                }
            }
        }
    }
}
