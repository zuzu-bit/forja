package com.forja.app.feature.inventory

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvKind

/** Ce poate face omul din S1. */
data class StartActions(
    val onBack: () -> Unit = {},
    val onInfo: () -> Unit = {},
    val onPickKind: (InvKind) -> Unit = {},
    val onPickScope: (ScopeChoice) -> Unit = {},
    val onPickAlbum: () -> Unit = {},
    val onPickFolder: () -> Unit = {},
    /** Atingerea plăcii POZE fără acces la galerie. */
    val onAskPhotos: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onOpenRun: () -> Unit = {},
    val onStopRun: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onAllowLaptop: () -> Unit = {}
)

/** Ghidajul S1 (§11.2), ≤ 60 de caractere pe pas. */
internal val StartCoachSteps = listOf(
    CoachStep("inv_kind", "Alegi: poze sau documente."),
    CoachStep("inv_scope", "Tot telefonul sau doar o parte."),
    CoachStep("inv_start", "Analiza merge în fundal. Nimic nu se șterge fără tine.")
)

/**
 * S1 (Main.dc.html): mascota cu cască și bula, plăcile POZE / DOCUMENTE cu cifrele reale, scopul, „Începe” cu estimarea
 * motorului. Cu o rulare existentă, în locul plăcilor stă cardul ei (în curs → S2, gata → S4).
 */
@Composable
fun InventoryStartContent(state: StartUiState, actions: StartActions, modifier: Modifier = Modifier) {
    val run = state.run
    val bubble = when {
        run?.ready == true -> "Dosarele sunt gata."
        run != null -> "Lucrez în fundal."
        state.error != null -> "Nu a mers. Încearcă din nou."
        else -> "Ce punem în ordine?"
    }
    val pose = when {
        run?.ready == true -> MascotState.Happy
        run != null -> MascotState.Thinking
        state.error != null -> MascotState.Sorry
        state.kind == InvKind.Documents -> MascotState.Thinking
        else -> MascotState.Idle
    }
    Box(modifier.fillMaxSize().background(Surface0)) {
        TopoLines(Modifier.matchParentSize())
        TopBottomColumn(
            top = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    InvIconButton(InvIcons.Back, "Înapoi", actions.onBack)
                    StampLabel("INVENTAR", rotationDeg = -4f)
                    InfoButton(actions.onInfo)
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Bubble(bubble)
                    Mascot(state = pose, hat = MascotHat.Helmet, size = 176.dp)
                }
                if (run == null) {
                    KindTiles(state, actions, Modifier.coachTarget("inv_kind"))
                    ScopeRow(state, actions, Modifier.coachTarget("inv_scope"))
                    // Singurul rând ajutător: de ce s-a oprit ultima rulare (mesajul motorului, scurt).
                    val why = state.error?.trim()?.takeIf { it.isNotEmpty() && it.length <= 70 && it != bubble }
                    if (why != null) {
                        Text(why, style = body(13, TextSecondary, line = 18), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    RunCard(run, actions.onOpenRun)
                    SecondaryAction(
                        label = if (run.ready) "Renunță" else "Oprește",
                        icon = if (run.ready) InvIcons.Trash else InvIcons.Stop,
                        onClick = if (run.ready) actions.onDiscard else actions.onStopRun
                    )
                }
            },
            bottom = {
                if (state.laptopPending > 0) LaptopCard(state.laptopPending, actions.onAllowLaptop)
                when {
                    run == null -> InvPrimaryButton(
                        "Începe", actions.onStart, Modifier.coachTarget("inv_start"),
                        meta = fmtMinutes(state.estimateSec)
                    )
                    run.ready -> InvPrimaryButton(
                        "Vezi dosarele", actions.onOpenRun, Modifier.coachTarget("inv_start"), meta = "${fmtCount(run.folders)} DOSARE"
                    )
                    else -> InvPrimaryButton("Urmărește", actions.onOpenRun, Modifier.coachTarget("inv_start"), meta = fmtMinutes(run.etaSec))
                }
            }
        )
    }
}

/** Bula mascotei (fără codiță, ca în prototip): Barlow 24 pe #121214. */
@Composable
private fun Bubble(text: String) {
    val reduced = LocalReducedMotion.current
    Box(
        Modifier
            .clip(R8)
            .background(Surface1)
            .border(1.dp, W09, R8)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 220)) togetherWith fadeOut(tween(if (reduced) 0 else 140)) },
            label = "bubble"
        ) { t ->
            Text(t, style = cond(24, 26, tracking = 0.01f), maxLines = 1)
        }
    }
}

// ───────────────────────────── Plăcile POZE / DOCUMENTE ─────────────────────────────

@Composable
private fun KindTiles(state: StartUiState, actions: StartActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val photosOn = state.kind == InvKind.Photos
        KindTile(
            icon = InvIcons.Photos,
            value = when {
                !state.photoAccess -> null
                state.photoCount == null -> "—"
                else -> fmtCount(state.photoCount)
            },
            word = if (!state.photoAccess) "Dă acces" else null,
            meta = if (state.photoAccess && state.photoBytes != null) "POZE · ${fmtSize(state.photoBytes)}" else "POZE",
            selected = photosOn,
            description = "Poze",
            onClick = { if (photosOn && !state.photoAccess) actions.onAskPhotos() else actions.onPickKind(InvKind.Photos) },
            modifier = Modifier.weight(1f)
        )
        val docsOn = state.kind == InvKind.Documents
        KindTile(
            icon = InvIcons.Folder,
            value = when {
                state.docFolder == null -> null
                state.docCount == null -> "—"
                else -> fmtCount(state.docCount)
            },
            word = if (state.docFolder == null) "Alege folder" else null,
            meta = if (state.docBytes != null) "DOCUMENTE · ${fmtSize(state.docBytes)}" else "DOCUMENTE",
            selected = docsOn,
            description = "Documente",
            onClick = { if (docsOn && state.docFolder == null) actions.onPickFolder() else actions.onPickKind(InvKind.Documents) },
            modifier = Modifier.weight(1f)
        )
    }
}

/** O placă (148 dp): iconiță olive sus, cifra mare + meta mono jos; aleasă = contur olive 2 dp + strălucire. */
@Composable
private fun KindTile(
    icon: ImageVector,
    value: String?,
    word: String?,
    meta: String,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current
    val glow by animateFloatAsState(if (selected) 1f else 0f, if (reduced) snap() else tween(220), label = "tileGlow")
    val borderW by animateDpAsState(if (selected) 2.dp else 1.dp, if (reduced) snap() else tween(220), label = "tileBorder")
    val borderC by animateColorAsState(if (selected) Accent2 else W09, if (reduced) snap() else tween(220), label = "tileBorderC")
    Box(
        modifier
            .pressable(onClick)
            .height(148.dp)
            .drawBehind {
                if (glow > 0.01f) {
                    val sw = 4.dp.toPx()
                    drawRoundRect(
                        Accent2.copy(alpha = 0.14f * glow),
                        topLeft = Offset(-sw / 2, -sw / 2),
                        size = Size(size.width + sw, size.height + sw),
                        cornerRadius = CornerRadius(8.dp.toPx() + sw / 2),
                        style = Stroke(sw)
                    )
                }
            }
            .clip(R8)
            .background(Surface1)
            .border(borderW, borderC, R8)
            .semantics {
                role = Role.RadioButton
                contentDescription = description
                stateDescription = if (selected) "ales" else "neales"
            }
            .padding(14.dp)
    ) {
        Icon(icon, null, tint = Accent2, modifier = Modifier.size(30.dp).align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (value != null) {
                Text(value, style = hero(40, 40), maxLines = 1)
            } else if (word != null) {
                Text(word, style = cond(26, 30), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(meta, style = mono(10, 0.14f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────────── Scopul ─────────────────────────────

@Composable
private fun ScopeRow(state: StartUiState, actions: StartActions, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = state.kind,
        transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
        label = "scopeRow",
        modifier = modifier.fillMaxWidth()
    ) { kind ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == InvKind.Photos) {
                ScopeChip("Tot", state.scope == ScopeChoice.All, { actions.onPickScope(ScopeChoice.All) }, Modifier.weight(1f))
                ScopeChip("Ultimele 500", state.scope == ScopeChoice.Last500, { actions.onPickScope(ScopeChoice.Last500) }, Modifier.weight(1f))
                ScopeChip(
                    state.albumName ?: "Album",
                    state.scope == ScopeChoice.Album,
                    actions.onPickAlbum,
                    Modifier.weight(1f),
                    trailing = InvIcons.ChevronDown
                )
            } else {
                ScopeChip(
                    state.docFolder ?: "Alege folder",
                    selected = state.docFolder != null,
                    onClick = actions.onPickFolder,
                    modifier = Modifier.weight(1f),
                    trailing = InvIcons.ChevronDown
                )
            }
        }
    }
}

// ───────────────────────────── Rularea existentă ─────────────────────────────

/** Cardul rulării (în locul plăcilor): inelul procentului sau bifa, cifra mare, săgeata spre S2 / S4. */
@Composable
private fun RunCard(run: RunSummary, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(onClick)
            .fillMaxWidth()
            .height(148.dp)
            .drawBehind {
                val sw = 4.dp.toPx()
                drawRoundRect(
                    Accent2.copy(alpha = 0.14f), topLeft = Offset(-sw / 2, -sw / 2), size = Size(size.width + sw, size.height + sw),
                    cornerRadius = CornerRadius(8.dp.toPx() + sw / 2), style = Stroke(sw)
                )
            }
            .clip(R8)
            .background(Surface1)
            .border(2.dp, Accent2, R8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (run.ready) "Dosarele sunt gata, ${run.folders}" else "Inventar în curs, ${run.percent} la sută"
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                if (run.ready) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                        Icon(InvIcons.CheckBold, null, tint = OnAccent, modifier = Modifier.size(30.dp))
                    }
                } else {
                    BigRing(run.percent / 100f)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (run.ready) fmtCount(run.folders) else fmtPercent(run.percent), style = hero(40, 40), maxLines = 1)
                Text(
                    if (run.ready) "DOSARE GATA" else "INVENTAR ÎN CURS",
                    style = mono(10, 0.14f), maxLines = 1
                )
            }
            Icon(InvIcons.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BigRing(fraction: Float) {
    val reduced = LocalReducedMotion.current
    val p by animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduced) snap() else tween(500), label = "bigRing")
    Canvas(Modifier.size(64.dp)) {
        val sw = 6.dp.toPx()
        val d = size.minDimension - sw
        val tl = Offset(sw / 2, sw / 2)
        drawArc(W10, -90f, 360f, false, topLeft = tl, size = Size(d, d), style = Stroke(sw))
        if (p > 0f) drawArc(Amber, -90f, 360f * p, false, topLeft = tl, size = Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Acțiunea secundară sub card („Oprește” / „Renunță”), în stilul chip-urilor. */
@Composable
private fun SecondaryAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick)
            .fillMaxWidth()
            .height(44.dp)
            .clip(R6)
            .background(Surface1)
            .border(1.dp, W09, R6)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = cond(17, tracking = 0.02f, color = TextSecondary))
    }
}

/** Mutări aprobate din laptop (organizarea de pe site) care așteaptă acordul Android: o atingere. */
@Composable
private fun LaptopCard(count: Int, onAllow: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(R8)
            .background(Surface1)
            .border(1.dp, Accent2.copy(alpha = 0.5f), R8)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(InvIcons.Laptop, null, tint = Accent2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Din laptop", style = cond(18, color = TextPrimary))
            Text("${fmtCount(count)} ${if (count == 1) "POZĂ" else "POZE"}", style = mono(10, 0.14f))
        }
        Box(
            Modifier
                .pressable(onAllow)
                .height(40.dp)
                .clip(R6)
                .background(CtaBrush)
                .semantics { role = Role.Button }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Permite", style = cond(17, color = OnAccent))
        }
    }
}
