package com.forja.app.feature.inventory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.coachTarget
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvKind
import kotlinx.coroutines.delay

private val InEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

// ═════════════════════════════ S4 · Dosarele tale ═════════════════════════════

data class FoldersActions(
    val onBack: () -> Unit = {},
    val onSite: () -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onFolderLongPress: (String) -> Unit = {},
    val onApply: () -> Unit = {}
)

/** Ghidajul S4: „De aruncat” și „Aplică” (≤ 60 de caractere). */
internal fun foldersCoachSteps(kind: InvKind): List<CoachStep> = listOf(
    CoachStep("inv_trash", "Aici e ce propun să arunci. Tu decizi."),
    CoachStep(
        "inv_apply",
        if (kind == InvKind.Photos) "Aplică mută pozele în dosare. Gunoiul ține 30 de zile."
        else "Aplică mută fișierele în dosare. Nimic nu se șterge."
    )
)

/**
 * S4 (Dosare.dc.html): eroul „14 dosare”, primul card lat „De aruncat” (evantai de 3 miniaturi), grila cu coperte reale
 * și teancul de foi, „Aplică · ~ 2 MIN” fix jos. Apăsare lungă pe un dosar → Redenumește / Unește cu… / Desfă.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventoryFoldersContent(state: FoldersUiState, actions: FoldersActions, modifier: Modifier = Modifier, playIntro: Boolean = true) {
    val reduced = LocalReducedMotion.current
    // Apariția în trepte (dsIn .4 s, întârziere 40 + i·35 ms), o singură dată pe rulare (nu la fiecare întoarcere).
    val intro = remember { Animatable(if (reduced || !playIntro) 1f else 0f) }
    val introMs = 40 + 35 * (state.folders.size.coerceAtMost(16) + 1) + 400
    LaunchedEffect(Unit) { if (intro.value < 1f) intro.animateTo(1f, tween(introMs, easing = LinearEasing)) }
    fun introOf(index: Int): () -> Float = {
        val elapsed = intro.value * introMs
        InEase.transform(((elapsed - (40 + index * 35)) / 400f).coerceIn(0f, 1f))
    }

    Box(modifier.fillMaxSize().background(Surface0)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    InvIconButton(InvIcons.Back, "Înapoi", actions.onBack)
                    StampLabel("DOSARE", rotationDeg = -4f, appear = false)
                    if (state.showSite) InvIconButton(InvIcons.Globe, "Vezi pe site", actions.onSite, tint = Accent2)
                    else Spacer(Modifier.size(44.dp))
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // contorul crește (P5): 0 → 14 odată cu apariția cardurilor
                    HeroCount(state.folders.size) { InEase.transform((intro.value * introMs / 700f).coerceIn(0f, 1f)) }
                    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (state.folders.size == 1) "dosar" else "dosare", style = cond(26, 26, color = TextSecondary, semi = true))
                        Text(
                            "${fmtCount(state.itemCount)} ${if (state.kind == InvKind.Photos) "POZE" else "DOCUMENTE"}",
                            style = mono(10, 0.14f, color = TextDim)
                        )
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 124.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(key = "trash", span = { GridItemSpan(maxLineSpan) }) {
                    TrashCard(
                        state.trash, state.kind, actions.onOpenTrash,
                        Modifier.coachTarget("inv_trash").introLayer(introOf(0))
                    )
                }
                items(state.folders.size, key = { state.folders[it].id }) { i ->
                    val f = state.folders[i]
                    FolderCard(
                        f, state.kind,
                        onClick = { actions.onOpenFolder(f.id) },
                        onLongClick = { actions.onFolderLongPress(f.id) },
                        modifier = Modifier.introLayer(introOf(i + 1)).animateItem()
                    )
                }
            }
        }
        // bara de jos, fixă, peste un gradient
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Surface0.copy(alpha = 0f), 0.34f to Surface0.copy(alpha = 0.92f), 1f to Surface0))
                .padding(start = 20.dp, top = 30.dp, end = 20.dp, bottom = 24.dp)
        ) {
            InvPrimaryButton("Aplică", actions.onApply, Modifier.coachTarget("inv_apply"), meta = fmtMinutes(state.applyEstimateSec))
        }
    }
}

/** Cifra-erou care crește 0 → n; citește progresul doar aici (recompunere limitată la ea). */
@Composable
private fun HeroCount(n: Int, progress: () -> Float) {
    Text(fmtCount((n * progress()).toInt()), style = hero(72, 60), modifier = Modifier.semantics { contentDescription = "$n dosare" })
}

private fun Modifier.introLayer(p: () -> Float): Modifier = graphicsLayer {
    val v = p()
    alpha = v
    translationY = (1f - v) * 10.dp.toPx()
}

/** Cardul lat „De aruncat”: evantai de 3 miniaturi (una stinsă, una neclară, una în față), coș, „212 · 1,2 GB”. */
@Composable
private fun TrashCard(t: TrashCardUi, kind: InvKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .pressable(onClick)
            .fillMaxWidth()
            .clip(R8)
            .background(Error.copy(alpha = 0.06f))
            .border(1.dp, Error.copy(alpha = 0.45f), R8)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "${t.name}, ${t.count}, ${fmtSize(t.bytes)}" }
            .padding(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 86.dp, height = 66.dp)) {
            if (kind == InvKind.Documents) {
                DocSheet("PDF", Modifier.fillMaxSize())
            } else {
                val fan = t.fan
                fan.getOrNull(1)?.let { r ->
                    FanThumb(r, Modifier.offset(x = 0.dp, y = 8.dp).size(50.dp).rotate(-9f), dim = true)
                }
                fan.getOrNull(2)?.let { r ->
                    FanThumb(r, Modifier.offset(x = 34.dp, y = 10.dp).size(50.dp).rotate(8f).blur(1.8.dp))
                }
                fan.getOrNull(0)?.let { r ->
                    FanThumb(r, Modifier.offset(x = 17.dp, y = 4.dp).size(54.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(InvIcons.Trash, null, tint = Error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t.name, style = cond(23, 24), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${fmtCount(t.count)} · ${fmtSize(t.bytes)}", style = mono(11, 0.06f))
        }
        Icon(InvIcons.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FanThumb(r: ThumbRef, modifier: Modifier, dim: Boolean = false) {
    Box(modifier.clip(R4)) {
        InvThumb(r.uri, r.mime, Modifier.fillMaxSize(), px = 160, name = r.name)
        if (dim) Box(Modifier.fillMaxSize().background(Surface0.copy(alpha = 0.35f)))
    }
}

/** Un dosar: copertă 122 dp cu teancul de 2 foi în spate + insigna cu numărul; numele dedesubt. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(f: FolderCardUi, kind: InvKind, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "${f.name}, ${f.count}" },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            Box(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(20.dp)
                    .clip(R5).background(Stack1).border(1.dp, W06, R5)
            )
            Box(
                Modifier.padding(start = 7.dp, end = 7.dp, top = 5.dp).fillMaxWidth().height(20.dp)
                    .clip(R5).background(Stack2).border(1.dp, W08, R5)
            )
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(122.dp)
                    .clip(R5)
                    .background(MediaBg)
                    .border(1.dp, W08, R5)
            ) {
                if (kind == InvKind.Documents) {
                    DocSheet(f.ext ?: "PDF", Modifier.fillMaxSize())
                } else {
                    InvThumb(f.cover?.uri, f.cover?.mime ?: "image/*", Modifier.fillMaxSize(), px = 512, name = f.cover?.name ?: "")
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(R4)
                        .background(Surface0.copy(alpha = 0.74f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(fmtCount(f.count), style = mono(11, color = TextPrimary, bold = true))
                }
            }
        }
        Text(f.name, style = cond(18, 20), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ═════════════════════════════ S5 · Dosarul ═════════════════════════════

data class FolderActions(
    val onBack: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onStartEdit: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onFilter: (ReasonFilter) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    /** „Păstrează” în „De aruncat”. */
    val onKeep: () -> Unit = {},
    val onMove: () -> Unit = {},
    /** „La gunoi” într-un dosar obișnuit. */
    val onTrash: () -> Unit = {}
)

private sealed interface GridRow {
    data class Header(val label: String, val key: Int) : GridRow
    data class Cell(val cell: CellUi) : GridRow
}

/**
 * S5 (DeAruncat.dc.html): titlul editabil pe loc (creion → câmp + bifă), meta mono, chip-urile de motiv (doar în
 * „De aruncat”), grila de 3 cu insigna motivului și cercul de selecție, bara „Păstrează [n]” / „Mută în…”.
 * Atingere → previzualizare; apăsare lungă sau cercul → selecție.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventoryFolderContent(state: FolderUiState, actions: FolderActions, modifier: Modifier = Modifier) {
    val selecting = state.selected.isNotEmpty()
    val allSelected = state.cells.isNotEmpty() && state.cells.all { it.id in state.selected }
    val rows: List<GridRow> = remember(state.cells, state.monthHeaders) {
        if (!state.monthHeaders) state.cells.map<CellUi, GridRow> { GridRow.Cell(it) }
        else buildList<GridRow> {
            var last = Int.MIN_VALUE
            for (c in state.cells) {
                val k = if (c.takenAt > 0) monthKey(c.takenAt) else 0
                if (k != last) {
                    add(GridRow.Header(if (c.takenAt > 0) fmtMonth(c.takenAt).uppercase() else "FĂRĂ DATĂ", k))
                    last = k
                }
                add(GridRow.Cell(c))
            }
        }
    }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().background(Surface0)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    InvIconButton(InvIcons.Back, "Înapoi la dosare", actions.onBack)
                    if (state.special || selecting) {
                        InvIconButton(InvIcons.SelectAll, if (allSelected) "Deselectează tot" else "Selectează tot", actions.onSelectAll, tint = if (allSelected) Accent2 else TextSecondary)
                    }
                }
                TitleRow(state, actions)
                Text(
                    if (state.special) "${fmtCount(state.totalCount)} · ${fmtSize(state.totalBytes)}"
                    else listOf(fmtCount(state.totalCount), state.dateLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = mono(11, 0.08f)
                )
            }
            if (state.special && state.filters.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.filters, key = { it.first.name }) { (f, n) ->
                        ReasonChip(f, n, f == state.filter) { actions.onFilter(f) }
                    }
                }
            }
            val cellPx = with(density) { 118.dp.roundToPx() }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 124.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(
                    rows,
                    key = { r -> if (r is GridRow.Header) "h${r.key}" else (r as GridRow.Cell).cell.id },
                    span = { r -> if (r is GridRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    contentType = { r -> if (r is GridRow.Header) 0 else 1 }
                ) { r ->
                    when (r) {
                        is GridRow.Header -> Text(r.label, style = mono(10, 0.14f, color = TextDim, bold = true), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        is GridRow.Cell -> ItemCell(
                            cell = r.cell,
                            selected = r.cell.id in state.selected,
                            showCircle = state.special || selecting,
                            showReason = state.special,
                            px = cellPx,
                            onTap = { if (selecting) actions.onToggle(r.cell.id) else actions.onOpen(r.cell.id) },
                            onToggle = { actions.onToggle(r.cell.id) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Surface0.copy(alpha = 0f), 0.34f to Surface0.copy(alpha = 0.92f), 1f to Surface0))
                        .padding(start = 20.dp, top = 30.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.special) {
                    SelPrimary("Păstrează", state.selected.size, actions.onKeep, Modifier.weight(1f))
                    SelSecondary("Mută în…", actions.onMove, Modifier.weight(1f))
                } else {
                    SelSecondary("Mută în…", actions.onMove, Modifier.weight(1f))
                    SelSecondary("La gunoi", actions.onTrash, Modifier.weight(1f), color = Error)
                }
            }
        }
    }
}

@Composable
private fun TitleRow(state: FolderUiState, actions: FolderActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.special) {
            Icon(InvIcons.TrashLined, null, tint = Error, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
        }
        if (!state.editing) {
            Text(state.name, style = cond(34, 36), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(R8).pressable(actions.onStartEdit).semantics { contentDescription = "Redenumește"; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(InvIcons.Pencil, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        } else {
            var value by remember(state.id) { mutableStateOf(TextFieldValue(state.name, TextRange(state.name.length))) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
            BasicTextField(
                value = value,
                onValueChange = { value = it.copy(text = it.text.take(40)) },
                singleLine = true,
                textStyle = cond(26, 30),
                cursorBrush = SolidColor(Accent2),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.onRename(value.text) }),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = "Numele dosarului" },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(R8)
                            .background(Surface1)
                            .border(1.5.dp, Accent2, R8)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) { inner() }
                }
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(44.dp).clip(R8).background(Accent2).pressable({ actions.onRename(value.text) })
                    .semantics { contentDescription = "Gata"; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(InvIcons.CheckBold, null, tint = Surface0, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ReasonChip(f: ReasonFilter, count: Int, selected: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val fg by animateColorAsState(if (selected) OnAccent else TextSecondary, if (reduced) snap() else tween(160), label = "rcFg")
    Row(
        Modifier
            .pressable(onClick)
            .height(36.dp)
            .clip(R4)
            .background(if (selected) Accent2.copy(alpha = 0.22f) else Surface1)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Accent2 else W08, R4)
            .semantics { role = Role.Tab; this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(f.icon(), null, tint = fg, modifier = Modifier.size(14.dp))
        Text(f.label, style = cond(16, color = fg), maxLines = 1)
        Text(fmtCount(count), style = mono(10, color = TextDim))
    }
}

/** O miniatură din grilă: văl + inel când e aleasă, insigna motivului (atingere → explicație), cercul de selecție. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCell(
    cell: CellUi,
    selected: Boolean,
    showCircle: Boolean,
    showReason: Boolean,
    px: Int,
    onTap: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val reduced = LocalReducedMotion.current
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(tip) { if (tip) { delay(1600); tip = false } }
    val ring by animateColorAsState(if (selected) Accent2 else Color.Transparent, if (reduced) snap() else tween(140), label = "cellRing")
    Box(
        modifier
            .aspectRatio(1f)
            .clip(R4)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                }
            )
            .semantics {
                this.selected = selected
                contentDescription = (cell.reason?.tip() ?: cell.name.ifBlank { "Element" })
            }
    ) {
        InvThumb(cell.uri, cell.mime, Modifier.fillMaxSize(), px = px, name = cell.name)
        if (selected) Box(Modifier.fillMaxSize().background(Surface0.copy(alpha = 0.45f)))
        Box(Modifier.fillMaxSize().border(2.5.dp, ring, R4))
        if (showReason && cell.reason != null) {
            val r = cell.reason
            Box(
                Modifier
                    .pressable({ tip = !tip }, haptic = false)
                    .align(Alignment.BottomStart)
                    .size(36.dp)
                    .semantics { contentDescription = r.tip(); role = Role.Button },
                contentAlignment = Alignment.BottomStart
            ) {
                Box(
                    Modifier.padding(start = 6.dp, bottom = 6.dp).size(24.dp).clip(CircleShape).background(Surface0.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(r.icon(), null, tint = TextPrimary, modifier = Modifier.size(13.dp))
                }
            }
            AnimatedVisibility(
                visible = tip,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 34.dp),
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(160))
            ) {
                Box(
                    Modifier
                        .widthIn(max = 100.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surface1.copy(alpha = 0.96f))
                        .border(1.dp, W12, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(r.tip(), style = body(11, TextPrimary, line = 14), maxLines = 2)
                }
            }
        }
        if (showCircle) {
            Box(
                Modifier
                    .pressable(onToggle, haptic = true)
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .semantics { contentDescription = if (selected) "Deselectează" else "Selectează"; role = Role.Checkbox },
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    Modifier
                        .padding(top = 6.dp, end = 6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (selected) Accent2 else Surface0.copy(alpha = 0.35f))
                        .then(if (selected) Modifier else Modifier.border(1.5.dp, TextPrimary.copy(alpha = 0.8f), CircleShape)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(InvIcons.CheckBold, null, tint = Surface0, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun SelPrimary(label: String, count: Int, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .pressable(onClick)
            .height(58.dp)
            .clip(R8)
            .background(CtaBrush)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = cond(21, tracking = 0.03f, color = OnAccent))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .heightIn(min = 22.dp)
                .widthIn(min = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Surface0.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(fmtCount(count), style = mono(11, color = OnAccent, bold = true))
        }
    }
}

@Composable
private fun SelSecondary(label: String, onClick: () -> Unit, modifier: Modifier, color: Color = OnAccent) {
    Box(
        modifier
            .pressable(onClick)
            .height(58.dp)
            .clip(R8)
            .background(Raised)
            .border(1.5.dp, if (color == OnAccent) Accent2 else color.copy(alpha = 0.7f), R8)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = cond(21, tracking = 0.03f, color = color), maxLines = 1)
    }
}
