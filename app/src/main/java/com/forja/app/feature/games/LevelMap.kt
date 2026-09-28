package com.forja.app.feature.games

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim2
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.ZID_ENDLESS
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.core.games.zid.ZidGoal
import com.forja.app.core.games.zid.ZidLevels
import com.forja.app.feature.inventory.Amber
import com.forja.app.feature.inventory.InfoButton
import com.forja.app.feature.inventory.InvPrimaryButton
import com.forja.app.feature.inventory.PillState
import com.forja.app.feature.inventory.R6
import com.forja.app.feature.inventory.Rule
import com.forja.app.feature.inventory.TopoLines
import com.forja.app.feature.inventory.cond
import com.forja.app.feature.inventory.fmtCount
import com.forja.app.feature.inventory.pulseRing
import kotlinx.coroutines.launch

// ───────────────────────────── Starea hărții ─────────────────────────────

enum class NodeState { Done, Current, Locked }

/** Un nod al drumului: numărul (sau ∞), starea, stelele, fortul (pătrat), numele și eticheta butonului. */
@Immutable
data class LevelNodeUi(
    val id: Int,
    val state: NodeState,
    val stars: Int,
    val fort: Boolean,
    val endless: Boolean,
    val name: String,
    val meta: String?
)

@Immutable
data class LevelMapUi(
    val game: GameId,
    /** De jos în sus: nivelul 1 primul. */
    val nodes: List<LevelNodeUi>,
    val selected: Int,
    /** Nivelul unei partide neterminate (butonul devine „Continuă”). */
    val resume: Int?,
    val pill: PillState?,
    /** Ștampila „NIVEL 3 · POARTA” (apăsare lungă pe un nod); null = ascunsă. */
    val stamp: String? = null
) {
    val selectedNode: LevelNodeUi? get() = nodes.firstOrNull { it.id == selected }
}

class LevelMapActions(
    val onPill: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onInfo: () -> Unit = {},
    val onSelect: (Int) -> Unit = {},
    val onLongPress: (Int) -> Unit = {},
    val onPlay: (Int) -> Unit = {}
)

/** Nivelul ales implicit: partida neterminată, altfel primul nejucat (sau „Fără sfârșit” când campania e gata). */
fun defaultSelection(game: GameId, p: GameProgress, resume: Int?): Int = when {
    resume != null -> resume
    p.unlocked <= game.levels -> p.unlocked.coerceAtLeast(1)
    game == GameId.Zid -> ZID_ENDLESS
    else -> game.levels
}

/** Eticheta de sub buton: ținta nivelului ZID sau numele zidului ASALT. */
fun levelMeta(game: GameId, id: Int, p: GameProgress): String? = when (game) {
    GameId.Zid -> {
        val l = ZidLevels.byId(id)
        when (l.goal) {
            ZidGoal.Lines -> "${l.target} LINII"
            ZidGoal.Dig -> "SAPĂ"
            ZidGoal.Endless -> if (p.endlessBest > 0) "RECORD ${fmtCount(p.endlessBest)}" else null
        }
    }
    GameId.Asalt -> AsaltLevels.byId(id).name.uppercase()
}

fun levelName(game: GameId, id: Int): String = when (game) {
    GameId.Zid -> ZidLevels.byId(id).name
    GameId.Asalt -> AsaltLevels.byId(id).name
}

/** „NIVEL 3 · POARTA” (ștampila unui nivel). */
fun levelStamp(game: GameId, id: Int): String =
    if (game == GameId.Zid && id == ZID_ENDLESS) "FĂRĂ SFÂRȘIT" else "NIVEL $id · ${levelName(game, id).uppercase()}"

/** Harta unui joc, din progres. */
fun levelMapUi(game: GameId, p: GameProgress, selected: Int?, resume: Int?, pill: PillState?, stamp: String? = null): LevelMapUi {
    val nodes = ArrayList<LevelNodeUi>(game.levels + 1)
    for (id in 1..game.levels) {
        val state = when {
            id < p.unlocked -> NodeState.Done
            id == p.unlocked -> NodeState.Current
            else -> NodeState.Locked
        }
        val fort = when (game) {
            GameId.Zid -> ZidLevels.byId(id).fort
            GameId.Asalt -> AsaltLevels.byId(id).fort
        }
        nodes += LevelNodeUi(id, state, p.starsOf(id), fort, endless = false, name = levelName(game, id), meta = levelMeta(game, id, p))
    }
    if (game == GameId.Zid) {
        val open = p.isUnlocked(game, ZID_ENDLESS)
        val state = when {
            !open -> NodeState.Locked
            p.unlocked > game.levels -> NodeState.Current
            else -> NodeState.Done
        }
        nodes += LevelNodeUi(ZID_ENDLESS, state, 0, fort = false, endless = true, name = "Fără sfârșit", meta = levelMeta(game, ZID_ENDLESS, p))
    }
    return LevelMapUi(game, nodes, selected ?: defaultSelection(game, p, resume), resume, pill, stamp)
}

// ───────────────────────────── Ecranul ─────────────────────────────

private val FRACTIONS = floatArrayOf(0.50f, 0.72f, 0.80f, 0.62f, 0.38f, 0.20f, 0.28f)
private val NODE_STEP = 88.dp
private val PATH_PAD_BOTTOM = 70.dp
private val PATH_PAD_TOP = 70.dp

/**
 * Harta nivelurilor: un drum pe liniile de nivel, nivelul 1 jos și urcând; fortul (boss) e pătrat; nodul curent pulsează,
 * cu mascota în cască alături; cele blocate tremură la atingere. Jos, fix: butonul nivelului ales („Nivelul 4 · 10 LINII”).
 */
@Composable
fun LevelMapContent(state: LevelMapUi, actions: LevelMapActions, modifier: Modifier = Modifier) {
    val sel = state.selectedNode
    Column(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
    ) {
        GameHeader(
            pill = state.pill,
            onPill = actions.onPill,
            onClose = actions.onClose,
            trailing = {
                StampLabel(if (state.game == GameId.Zid) "ZID" else "ASALT", rotationDeg = -4f, appear = false)
                Spacer(Modifier.size(2.dp))
                InfoButton(actions.onInfo)
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            TopoLines(Modifier.matchParentSize())
            LevelPath(state, actions)
            // capetele drumului se sting în fundal
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
                    .background(Brush.verticalGradient(listOf(Surface0, Color.Transparent)))
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Surface0)))
            )
            val stampText = state.stamp
            if (stampText != null) {
                // ștampila „lovește” la apariție (StampLabel), apoi dispare singură
                androidx.compose.runtime.key(stampText) {
                    StampLabel(
                        stampText, rotationDeg = -3f, color = Amber, fontSize = 12,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (sel != null) {
            val resume = state.resume == sel.id
            val label = when {
                resume -> "Continuă"
                sel.endless -> "Fără sfârșit"
                else -> "Nivelul ${sel.id}"
            }
            val meta = when {
                resume && sel.endless -> "FĂRĂ SFÂRȘIT"
                resume -> "NIVELUL ${sel.id}"
                else -> sel.meta
            }
            InvPrimaryButton(label, { actions.onPlay(sel.id) }, meta = meta, enabled = sel.state != NodeState.Locked)
        }
    }
}

@Composable
private fun LevelPath(state: LevelMapUi, actions: LevelMapActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewH = maxHeight
        val w = maxWidth
        val n = state.nodes.size
        val contentH = maxOf(viewH, PATH_PAD_BOTTOM + PATH_PAD_TOP + NODE_STEP * (n - 1))
        fun nodeY(i: Int): Dp = contentH - PATH_PAD_BOTTOM - NODE_STEP * i
        fun nodeX(i: Int): Dp = (w * FRACTIONS[i % FRACTIONS.size]).coerceIn(44.dp, w - 44.dp)
        // derulată de la început la nodul ales (fără animație: e prima imagine a ecranului)
        val selIndex = state.nodes.indexOfFirst { it.id == state.selected }.coerceAtLeast(0)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val initial = with(density) {
            (nodeY(selIndex) - viewH / 2).coerceIn(0.dp, contentH - viewH).roundToPx()
        }
        val scroll = rememberScrollState(initial)
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(contentH)
                    .drawWithCache {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                        val sw = 2.dp.toPx()
                        val pts = Array(n) { i -> Offset(nodeX(i).toPx(), nodeY(i).toPx()) }
                        onDrawBehind {
                            for (i in 0 until n - 1) {
                                val reached = state.nodes[i + 1].state != NodeState.Locked
                                drawLine(
                                    if (reached) Accent2.copy(alpha = 0.7f) else Accent2.copy(alpha = 0.4f),
                                    pts[i], pts[i + 1], strokeWidth = sw, cap = StrokeCap.Round,
                                    pathEffect = if (reached) null else dash
                                )
                            }
                        }
                    }
            ) {
                state.nodes.forEachIndexed { i, node ->
                    val cx = nodeX(i)
                    val cy = nodeY(i)
                    LevelNode(
                        node = node,
                        selected = node.id == state.selected,
                        onTap = { actions.onSelect(node.id) },
                        onLongPress = { actions.onLongPress(node.id) },
                        modifier = Modifier.offset(x = cx - 36.dp, y = cy - 36.dp)
                    )
                    if (node.state == NodeState.Current) {
                        // mascota în cască, de partea cu loc
                        val left = cx > w / 2
                        Mascot(
                            state = MascotState.Idle,
                            hat = MascotHat.Helmet,
                            size = 48.dp,
                            modifier = Modifier.offset(x = if (left) cx - 44.dp - 48.dp else cx + 44.dp, y = cy - 28.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Un nod: 72 × 72 (ținta de atingere), cu cercul/pătratul desenat în centru și stelele dedesubt. */
@Composable
private fun LevelNode(node: LevelNodeUi, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val d = when (node.state) {
        NodeState.Current -> 60.dp
        NodeState.Done -> 52.dp
        NodeState.Locked -> 48.dp
    }
    val shape: Shape = if (node.fort) R6 else CircleShape
    val pulse = if (node.state == NodeState.Current && !reduced) {
        rememberInfiniteTransition(label = "node").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "nodeT")
    } else null
    val desc = when {
        node.endless -> if (node.state == NodeState.Locked) "Fără sfârșit, blocat" else "Fără sfârșit"
        node.state == NodeState.Locked -> "Nivelul ${node.id}, blocat"
        node.state == NodeState.Current && node.stars == 0 -> "Nivelul ${node.id}, următorul"
        else -> "Nivelul ${node.id}, ${starsWords(node.stars)}"
    }
    fun rejectLocked() {
        try {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Exception) { }
        if (!reduced) scope.launch {
            shake.snapTo(0f)
            shake.animateTo(0f, keyframes {
                durationMillis = 320
                -7f at 50
                6f at 110
                -4f at 170
                3f at 230
                0f at 320
            })
        }
    }
    Box(
        modifier
            .size(72.dp)
            .graphicsLayer { translationX = shake.value * density }
            .semantics {
                role = Role.Button
                contentDescription = desc
                if (selected) stateDescription = "ales"
                onClick { if (node.state == NodeState.Locked) rejectLocked() else tap(); true }
            }
            .pointerInput(node.id, node.state) {
                detectTapGestures(
                    onTap = {
                        if (node.state == NodeState.Locked) rejectLocked()
                        else {
                            try { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) { }
                            tap()
                        }
                    },
                    onLongPress = {
                        try { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) { }
                        longPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val (bg, border, bw) = when (node.state) {
            NodeState.Current -> Triple(Amber.copy(alpha = 0.14f), Amber, 2.dp)
            NodeState.Done -> Triple(Accent.copy(alpha = 0.35f), Accent2, 1.5.dp)
            NodeState.Locked -> Triple(Surface1, Rule, 1.dp)
        }
        Box(
            Modifier
                .size(d + 10.dp)
                .drawBehind {
                    if (selected && node.state != NodeState.Current) {
                        val r = (d / 2 + 5.dp).toPx()
                        if (node.fort) {
                            drawRoundRect(
                                Amber.copy(alpha = 0.8f), topLeft = Offset(center.x - r, center.y - r),
                                size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()), style = Stroke(1.5.dp.toPx())
                            )
                        } else drawCircle(Amber.copy(alpha = 0.8f), r, center, style = Stroke(1.5.dp.toPx()))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(d)
                    .pulseRing(pulse != null, { pulse?.value ?: 0f }, color = Amber, radius = d / 2)
                    .clip(shape)
                    .background(bg)
                    .border(bw, border, shape),
                contentAlignment = Alignment.Center
            ) {
                if (node.endless) {
                    Infinity(
                        color = if (node.state == NodeState.Locked) TextDim2 else if (node.state == NodeState.Current) Amber else TextPrimary,
                        modifier = Modifier.size(width = 28.dp, height = 16.dp)
                    )
                } else {
                    Text(
                        "${node.id}",
                        style = cond(
                            if (node.state == NodeState.Current) 26 else 22,
                            color = when (node.state) {
                                NodeState.Locked -> TextDim2
                                else -> TextPrimary
                            }
                        )
                    )
                }
            }
        }
        if (!node.endless && node.state != NodeState.Locked && (node.stars > 0 || node.state == NodeState.Done)) {
            StarRow(
                node.stars, size = 10.dp, gap = 2.dp, off = Rule,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = if (node.state == NodeState.Current) 6.dp else 2.dp)
            )
        }
    }
}

/** „∞” desenat (lemniscata), ca să nu depindem de font. */
@Composable
private fun Infinity(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val p = androidx.compose.ui.graphics.Path()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val a = size.width / 2f * 0.92f
            val steps = 64
            for (k in 0..steps) {
                val t = (k.toFloat() / steps) * 2f * Math.PI.toFloat()
                val s = kotlin.math.sin(t)
                val c = kotlin.math.cos(t)
                val den = 1f + s * s
                val x = cx + a * c / den
                val y = cy + a * s * c / den * 1.25f
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            val stroke = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind { drawPath(p, color, style = stroke) }
        }
    )
}

/** Ștampila de la apăsarea lungă dispare singură după 2,2 s. */
@Composable
internal fun AutoHide(key: Any?, onHide: () -> Unit) {
    LaunchedEffect(key) {
        if (key != null) {
            kotlinx.coroutines.delay(2_200)
            onHide()
        }
    }
}
