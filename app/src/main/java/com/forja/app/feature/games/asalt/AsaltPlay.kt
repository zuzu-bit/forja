package com.forja.app.feature.games.asalt

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.games.active
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.feature.games.ASALT_INSET_DP
import com.forja.app.feature.games.CountdownDigit
import com.forja.app.feature.games.GAME_COVER_TOP_DP
import com.forja.app.feature.games.GameHeader
import com.forja.app.feature.games.GameOverlay
import com.forja.app.feature.games.PauseActions
import com.forja.app.feature.games.PauseButton
import com.forja.app.feature.games.PauseCard
import com.forja.app.feature.games.ReadyGlyph
import com.forja.app.feature.games.ResultActions
import com.forja.app.feature.games.ResultCard
import com.forja.app.feature.games.ThumbPad
import com.forja.app.feature.inventory.PillState
import com.forja.app.feature.inventory.Rule
import com.forja.app.feature.inventory.cond
import com.forja.app.feature.inventory.fmtCount
import com.forja.app.feature.inventory.mono
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

/**
 * Scara terenului (dp pe unitate), după games.md §4.11, cu marginea dinăuntru ([ASALT_INSET_DP] pe fiecare latură):
 * S23 → 0,821 (lumea 320 × 492 dp, cardul 328 × 500 dp).
 */
internal fun asaltUnitDp(widthDp: Float, heightDp: Float): Float {
    val inset = 2f * ASALT_INSET_DP
    return minOf(
        (widthDp - 32f - inset) / AsaltEngine.W.toFloat(),
        (heightDp - 12f - 44f - 8f - 40f - 64f - 12f - inset) / AsaltEngine.H.toFloat()
    ).coerceAtLeast(0.3f)
}

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
        val fieldW = (floor(AsaltEngine.W.toFloat() * u) + 2f * ASALT_INSET_DP).dp
        val fieldH = (floor(AsaltEngine.H.toFloat() * u) + 2f * ASALT_INSET_DP).dp
        val unitPx = with(LocalDensity.current) { u.dp.toPx() }
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            GameHeader(
                pill = pill,
                onPill = actions.onPill,
                onClose = actions.onClose,
                center = { Lives(play.hud.lives, Modifier.coachTarget("asalt_lives")) },
                trailing = {
                    if (overlay is GameOverlay.None || overlay is GameOverlay.Ready) PauseButton(actions.onPause)
                    else Spacer(Modifier.size(44.dp))
                }
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
                    if (overlay is GameOverlay.Countdown) CountdownDigit(overlay.n)
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
        // voalul acoperă tot ce e sub antet, de la o margine la alta; cardul își pune singur marginile
        val cover = Modifier.fillMaxSize().padding(top = GAME_COVER_TOP_DP.dp)
        when (overlay) {
            is GameOverlay.Pause -> PauseCard(overlay.ui, actions.pause, cover)
            is GameOverlay.Result -> ResultCard(overlay.ui, actions.result, cover)
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
