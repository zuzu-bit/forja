package com.forja.app.feature.games.zid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.PopIn
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.games.active
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidGoal
import com.forja.app.core.games.zid.ZidPhase
import com.forja.app.feature.games.CountdownDigit
import com.forja.app.feature.games.GameHeader
import com.forja.app.feature.games.GameOverlay
import com.forja.app.feature.games.HudLabel
import com.forja.app.feature.games.PauseActions
import com.forja.app.feature.games.PauseButton
import com.forja.app.feature.games.PauseCard
import com.forja.app.feature.games.PreviewShape
import com.forja.app.feature.games.ReadyGlyph
import com.forja.app.feature.games.ResultActions
import com.forja.app.feature.games.ResultCard
import com.forja.app.feature.games.ThumbPad
import com.forja.app.feature.games.ZID_GAP_DP
import com.forja.app.feature.games.ZID_SIDE_DP
import com.forja.app.feature.inventory.Amber
import com.forja.app.feature.inventory.PillState
import com.forja.app.feature.inventory.W06
import com.forja.app.feature.inventory.fmtCount
import com.forja.app.feature.inventory.hero
import com.forja.app.feature.inventory.mono

// ═════════════════════════════ Starea de pe ecran ═════════════════════════════

/** HUD-ul ZID ca stare Compose: bucla scrie în el; o valoare egală nu invalidează, deci recompune doar la schimbare. */
@Stable
internal class ZidHud {
    var remaining by mutableIntStateOf(0)
    var score by mutableIntStateOf(0)
    var next0 by mutableIntStateOf(0)
    var next1 by mutableIntStateOf(0)
    var hold by mutableIntStateOf(0)
    var holdUsed by mutableStateOf(false)
    var danger by mutableStateOf(false)
    var rank by mutableIntStateOf(1)
    /** Faza, ca stare: ecranul (bucla, degetul din Ready) se recompune când partida pornește sau se termină. */
    var phase by mutableStateOf(ZidPhase.Ready)

    fun sync(e: ZidEngine) {
        phase = e.phase
        remaining = e.remaining
        score = e.score
        next0 = e.next(0)
        next1 = e.next(1)
        hold = e.hold
        holdUsed = e.holdUsed
        danger = e.danger
        rank = e.rank
    }
}

/** O partidă pe ecran: motorul, HUD-ul, efectele și contorul de cadre pe care îl citește tabla. */
@Stable
internal class ZidPlayState(val engine: ZidEngine) {
    val hud = ZidHud().also { it.sync(engine) }
    val fx = ZidFx().also { it.syncCollapse(engine) }
    val frame = mutableLongStateOf(0L)
    var drawnVersion = engine.version
    var saveClock = 0
    private var fxWasActive = false

    /** După o comandă sau un pas: HUD, efecte, redesenare dacă s-a schimbat ceva (și un cadru după ultimul efect). */
    fun touch() {
        hud.sync(engine)
        val fxNow = fx.active
        if (engine.version != drawnVersion || fxNow || fxWasActive || engine.phase == ZidPhase.Clearing) {
            drawnVersion = engine.version
            frame.longValue = frame.longValue + 1
        }
        fxWasActive = fxNow
    }
}

internal class ZidPlayActions(
    val onPill: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onPause: () -> Unit = {},
    val pause: PauseActions = PauseActions(),
    val result: ResultActions = ResultActions()
)

// ═════════════════════════════ Conținutul fără stare ═════════════════════════════

/**
 * Suprafața de joc ZID pentru S23 (≈ 360 × 695 dp): antet 44, tabla 10 × 18 cu coloana de 64 dp alături, apoi placa
 * degetului (restul, ≥ 120 dp). Fără derulare. Mărimea celulei vine din spațiul real (BoxWithConstraints).
 * Nicio buclă aici: capturile o randează direct; gazda dă gesturile prin `input`.
 */
@Composable
internal fun ZidPlayContent(
    play: ZidPlayState,
    overlay: GameOverlay,
    pill: PillState?,
    mascot: MascotState,
    levelLabel: String,
    actions: ZidPlayActions,
    modifier: Modifier = Modifier,
    clearDelayMs: Int = 260,
    quad: Boolean = false,
    input: (cellPx: Float) -> Modifier = { Modifier }
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Surface0)) {
        val cellDp = zidCellDp(maxWidth.value, maxHeight.value)
        val boardW = (cellDp * ZidEngine.W).dp
        val boardH = (cellDp * ZidEngine.VISIBLE).dp
        val cellPx = with(LocalDensity.current) { cellDp.dp.toPx() }
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            GameHeader(
                pill = pill,
                onPill = actions.onPill,
                onClose = actions.onClose,
                trailing = {
                    Text(levelLabel, style = mono(11, 0.12f, color = TextDim, bold = true), maxLines = 1)
                    if (overlay is GameOverlay.None || overlay is GameOverlay.Ready) PauseButton(actions.onPause)
                    else Spacer(Modifier.size(44.dp))
                }
            )
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).fillMaxWidth().then(input(cellPx))) {
                Row(Modifier.fillMaxWidth().height(boardH), horizontalArrangement = Arrangement.Center) {
                    Box(
                        Modifier
                            .size(boardW, boardH)
                            .coachTarget("zid_board")
                            .semantics { contentDescription = "Tabla ZID" },
                        contentAlignment = Alignment.Center
                    ) {
                        ZidBoard(play.engine, play.fx, play.frame, clearDelayMs, Modifier.fillMaxSize())
                        if (overlay is GameOverlay.Ready) ReadyGlyph()
                        if (quad) PopIn(visible = true, fromScale = 0.4f) { Text("×4", style = hero(64, color = Amber)) }
                    }
                    Spacer(Modifier.width(ZID_GAP_DP.dp))
                    ZidSide(play.hud, play.engine.level.goal, mascot, Modifier.width(ZID_SIDE_DP.dp).fillMaxHeight())
                }
                Spacer(Modifier.height(16.dp))
                ThumbPad(
                    down = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .systemGestureExclusion()
                        .coachTarget("zid_pad")
                        .semantics { contentDescription = "Placa degetului" }
                )
            }
        }
        val cover = Modifier.fillMaxSize().padding(top = 62.dp)
        when (overlay) {
            is GameOverlay.Pause -> PauseCard(overlay.ui, actions.pause, cover.padding(horizontal = 12.dp, vertical = 8.dp))
            is GameOverlay.Result -> ResultCard(overlay.ui, actions.result, cover.padding(horizontal = 12.dp, vertical = 8.dp))
            is GameOverlay.Countdown -> CountdownDigit(overlay.n, cover)
            else -> Unit
        }
    }
}

/** Coloana din dreapta: URM. (două piese), REZ., ce mai e de făcut, scorul, mascota în cască. */
@Composable
private fun ZidSide(hud: ZidHud, goal: ZidGoal, mascot: MascotState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.Start) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLabel("URM.")
            PreviewBox(Modifier.height(36.dp)) { PiecePreview(hud.next0, 12.dp, Modifier.fillMaxSize()) }
            PreviewBox(Modifier.height(28.dp)) { PiecePreview(hud.next1, 9.dp, Modifier.fillMaxSize(), alpha = 0.6f) }
        }
        Column(Modifier.coachTarget("zid_hold"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLabel("REZ.")
            PreviewBox(Modifier.height(36.dp)) {
                PiecePreview(hud.hold, 12.dp, Modifier.fillMaxSize(), alpha = if (hud.holdUsed) 0.35f else 1f)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val (label, desc) = when (goal) {
                ZidGoal.Lines -> "LINII" to "linii rămase"
                ZidGoal.Dig -> "MOLOZ" to "rânduri de moloz"
                ZidGoal.Endless -> "LINII" to "linii"
            }
            Text(
                "${hud.remaining}",
                style = hero(34, color = if (goal == ZidGoal.Endless) com.forja.app.core.designsystem.TextPrimary else Amber),
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "${hud.remaining} $desc" }
            )
            HudLabel(label)
            Spacer(Modifier.height(6.dp))
            Text(fmtCount(hud.score), style = mono(11, 0.04f, bold = true), maxLines = 1, modifier = Modifier.semantics { contentDescription = "Scor ${hud.score}" })
        }
        Mascot(state = mascot, hat = MascotHat.Helmet, size = 56.dp)
    }
}

@Composable
private fun PreviewBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(PreviewShape)
            .background(Surface1)
            .border(1.dp, W06, PreviewShape),
        contentAlignment = Alignment.Center
    ) { content() }
}
