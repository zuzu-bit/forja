package com.forja.app.feature.inventory

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forja.app.core.cleanup.Album
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvDest
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvText

/** Foaia de jos a Inventarului (DeAruncat.dc.html): #121214, rază 12 sus, mâner 40×4, fundal întunecat 60 %. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InvSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetTop,
        containerColor = Surface1,
        contentColor = TextPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            // border-top 1 px la 10 % (prototipul), apoi mânerul
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(W10))
                SheetHandle()
            }
        }
    ) {
        SheetContent(content)
    }
}

@Composable
private fun SheetContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/**
 * Aceeași foaie desenată pe loc, fără fereastra ModalBottomSheet (pe care capturile JVM nu o văd): fundalul întunecat
 * 60 %, #121214, rază 12 sus, linia de 10 %, mânerul. Doar pentru capturi și revizuire.
 */
@Composable
internal fun InvSheetFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(SheetTop).background(Surface1)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(W10))
            SheetHandle()
            SheetContent(content)
        }
    }
}

@Composable
private fun ThumbBox(ref: ThumbRef?, kind: InvKind) {
    Box(Modifier.size(44.dp).clip(R4).background(Surface2)) {
        if (kind == InvKind.Documents) DocSheet(ref?.let { extOf(it.name, it.mime) } ?: "PDF", Modifier.fillMaxSize())
        else InvThumb(ref?.uri, ref?.mime ?: "image/*", Modifier.fillMaxSize(), px = 160, name = ref?.name ?: "")
    }
}

/** Câmpul pentru un nume de dosar (nou sau redenumit), cu bifa de confirmare. */
@Composable
private fun NameField(initial: String, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { value = it.copy(text = it.text.take(40)) },
            singleLine = true,
            textStyle = cond(22, 26),
            cursorBrush = SolidColor(Accent2),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
            modifier = Modifier.weight(1f).height(48.dp).focusRequester(focus).semantics { contentDescription = "Numele dosarului" },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().clip(R8).background(Surface0).border(1.5.dp, Accent2, R8).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) { inner() }
            }
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(48.dp).clip(R8).background(Accent2).pressable({ onDone(value.text) })
                .semantics { contentDescription = "Gata"; role = Role.Button },
            contentAlignment = Alignment.Center
        ) {
            Icon(InvIcons.CheckBold, null, tint = Surface0, modifier = Modifier.size(20.dp))
        }
    }
}

/** „Mută în…”: dosarele (colaj mic + nume + număr) și „Dosar nou”. */
@Composable
internal fun MoveSheet(
    kind: InvKind,
    targets: List<MoveTarget>,
    onPick: (String) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val maxList = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val reduced = LocalReducedMotion.current
    InvSheet(onDismiss) {
        var naming by remember { mutableStateOf(false) }
        AnimatedContent(
            targetState = naming,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 180)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
            label = "moveSheet"
        ) { isNaming ->
            if (isNaming) {
                NameField("Dosar nou") { onNew(it) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LazyColumn(Modifier.heightIn(max = maxList), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(targets, key = { it.id }) { t ->
                            SheetRow(t.name, { onPick(t.id) }, { ThumbBox(t.cover, kind) }, meta = fmtCount(t.count))
                        }
                    }
                    SheetRow("Dosar nou", { naming = true }, { DashedPlus() }, color = Accent2)
                }
            }
        }
    }
}

private enum class FolderMenu { Menu, Rename, Merge }

/** Acțiunile rapide ale unui dosar (apăsare lungă în S4): Redenumește / Unește cu… / Desfă. */
@Composable
internal fun FolderActionsSheet(
    kind: InvKind,
    folder: MoveTarget,
    others: List<MoveTarget>,
    onRename: (String) -> Unit,
    onMerge: (String) -> Unit,
    onDissolve: () -> Unit,
    onDismiss: () -> Unit
) {
    val maxList = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val reduced = LocalReducedMotion.current
    InvSheet(onDismiss) {
        var mode by remember { mutableStateOf(FolderMenu.Menu) }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ThumbBox(folder.cover, kind)
            Spacer(Modifier.width(12.dp))
            Text(folder.name, style = cond(22, 26), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(fmtCount(folder.count), style = mono(10, color = TextDim))
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 180)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
            label = "folderMenu"
        ) { m ->
            when (m) {
                FolderMenu.Menu -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SheetRow("Redenumește", { mode = FolderMenu.Rename }, { MenuIcon(InvIcons.Pencil) })
                    if (others.isNotEmpty()) SheetRow("Unește cu…", { mode = FolderMenu.Merge }, { MenuIcon(InvIcons.Merge) })
                    SheetRow("Desfă", onDissolve, { MenuIcon(InvIcons.Split) })
                }
                FolderMenu.Rename -> NameField(folder.name) { onRename(it) }
                FolderMenu.Merge -> LazyColumn(Modifier.heightIn(max = maxList), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(others, key = { it.id }) { t ->
                        SheetRow(t.name, { onMerge(t.id) }, { ThumbBox(t.cover, kind) }, meta = fmtCount(t.count))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuIcon(icon: ImageVector) {
    Box(Modifier.size(44.dp).clip(R4).background(Raised).border(1.dp, W06, R4), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Accent2, modifier = Modifier.size(20.dp))
    }
}

/** Albumele galeriei (scopul „Album”). */
@Composable
internal fun AlbumSheet(albums: List<Album>?, selectedId: Long?, onPick: (Album) -> Unit, onDismiss: () -> Unit) {
    val maxList = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    InvSheet(onDismiss) {
        if (albums == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Mascot(state = MascotState.Thinking, size = 72.dp)
            }
        } else if (albums.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Mascot(state = MascotState.Sorry, size = 72.dp)
            }
        } else {
            LazyColumn(Modifier.heightIn(max = maxList), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(albums, key = { it.bucketId }) { a ->
                    val sel = a.bucketId == selectedId
                    SheetRow(
                        a.name, { onPick(a) },
                        {
                            Box(Modifier.size(44.dp).clip(R4).background(Surface2)) {
                                InvThumb(a.coverUri, "image/*", Modifier.fillMaxSize(), px = 160)
                                if (sel) Box(Modifier.fillMaxSize().border(2.dp, Accent2, R4))
                            }
                        },
                        meta = fmtCount(a.count),
                        color = if (sel) Accent2 else TextPrimary
                    )
                }
            }
        }
    }
}

/**
 * S6 — confirmarea: rândul destinației (atingibil → „Locație”), gunoiul cu onestitatea (30 de zile), „Aplică”.
 * Aceeași foaie comută între confirmare și „Locație” ([showLocation]), fără să se închidă și să se redeschidă.
 */
@Composable
internal fun ApplyConfirmSheet(
    ui: ApplyConfirmUi,
    location: LocationUi,
    showLocation: Boolean,
    onShowLocation: (Boolean) -> Unit,
    onPickDest: (InvDest) -> Unit,
    onOther: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val reduced = LocalReducedMotion.current
    InvSheet(onDismiss) {
        AnimatedContent(
            targetState = showLocation,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 180)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
            label = "applySheet"
        ) { loc ->
            if (loc) LocationBody(location, onBack = { onShowLocation(false) }, onPick = onPickDest, onOther = onOther)
            else ApplyConfirmBody(ui, onApply = onApply, onDest = { onShowLocation(true) })
        }
    }
}

/** Conținutul confirmării (și în capturi, prin [InvSheetFrame]). */
@Composable
internal fun ApplyConfirmBody(ui: ApplyConfirmUi, onApply: () -> Unit, onDest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val photos = ui.kind == InvKind.Photos
        if (ui.moves > 0) {
            DestRow(
                title = "${InvText.count(ui.folders, "dosar", "dosare")} în ${ui.destLabel.ifBlank { if (photos) "Galerie" else "Organizate" }}",
                path = ui.destPath,
                onClick = onDest
            )
        }
        if (ui.trashCount > 0) {
            ConfirmRow(
                icon = InvIcons.Trash, tint = Error, bg = Error.copy(alpha = 0.12f),
                title = if (photos) "${fmtCount(ui.trashCount)} la gunoi · ${fmtSize(ui.trashBytes)}" else "${fmtCount(ui.trashCount)} deoparte",
                sub = if (photos) "Se recuperează 30 de zile" else "Nimic nu se șterge"
            )
        }
        Spacer(Modifier.height(10.dp))
        InvPrimaryButton("Aplică", onApply)
    }
}

/** Rândul destinației: dosarul olive, „14 dosare în FORJA”, calea mono dedesubt, chevron — o atingere deschide „Locație”. */
@Composable
private fun DestRow(title: String, path: String, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick)
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(R8)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "$title. Locație: $path. Schimbă" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(R8).background(Accent2.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(InvIcons.Folder, null, tint = Accent2, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = cond(20, 22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (path.isNotBlank()) Text(path, style = mono(10, 0.12f, color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(32.dp).clip(R6).background(Raised).border(1.dp, W06, R6),
            contentAlignment = Alignment.Center
        ) {
            Icon(InvIcons.ChevronDown, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * „Locație”: unde ajung dosarele. Poze: Galerie · FORJA / Direct în Galerie / Lângă Cameră / Alt dosar…;
 * documente: În folderul ales / Alt folder…. Rândul ales poartă bifa; calea stă mono sub nume.
 */
@Composable
internal fun LocationBody(ui: LocationUi, onBack: () -> Unit, onPick: (InvDest) -> Unit, onOther: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            // 48 dp de atins; desenat cu 4 dp mai la stânga, ca săgeata și titlul să stea unde erau (butonul de 40 dp).
            Box(
                Modifier.offset(x = (-4).dp).size(48.dp).clip(R8).pressable(onBack).semantics { contentDescription = "Înapoi"; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(InvIcons.Back, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
            Text("Locație", style = cond(24, 28), modifier = Modifier.offset(x = (-4).dp))
        }
        for (o in ui.options) {
            DestOptionRow(o.label, o.path, o.selected, { DestIconBox(o.icon, o.selected) }) { onPick(o.dest) }
        }
        DestOptionRow(ui.otherLabel, null, false, { DashedPlus() }, color = Accent2, onClick = onOther)
    }
}

@Composable
private fun DestIconBox(icon: DestIcon, selected: Boolean) {
    val vector = when (icon) {
        DestIcon.Gallery -> InvIcons.Photos
        DestIcon.Pictures -> InvIcons.Image
        DestIcon.Camera -> InvIcons.Camera
        DestIcon.Folder -> InvIcons.Folder
    }
    Box(
        Modifier.size(44.dp).clip(R4).background(if (selected) Accent2.copy(alpha = 0.14f) else Raised).border(1.dp, if (selected) Accent2.copy(alpha = 0.5f) else W06, R4),
        contentAlignment = Alignment.Center
    ) {
        Icon(vector, null, tint = Accent2, modifier = Modifier.size(20.dp))
    }
}

/** Rând de 64 dp: iconiță 44 + nume Barlow 20 + cale mono; bifa olive la cel ales. */
@Composable
private fun DestOptionRow(
    label: String,
    path: String?,
    selected: Boolean,
    leading: @Composable () -> Unit,
    color: Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(R8)
            .background(if (selected) Accent2.copy(alpha = 0.06f) else Color.Transparent)
            .pressable(onClick)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                stateDescription = if (selected) "ales" else "neales"
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = cond(20, color = if (selected) Accent2 else color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (path != null) Text(path, style = mono(10, 0.12f, color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(InvIcons.CheckBold, null, tint = Accent2, modifier = Modifier.size(18.dp))
        }
    }
}

/** Presetările din „Ultimele N” (cele ≥ numărul pozelor din galerie nu se arată: ar fi „Tot”). */
internal val LastNPresets = listOf(100, 500, 1_000, 5_000)

/** „Ultimele N”: presetări cu o atingere + un număr scris; sub câmp, câte are galeria („DIN 12 480”). */
@Composable
internal fun LastNSheet(current: Int, total: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    InvSheet(onDismiss) { LastNBody(current, total, onPick) }
}

/** Conținutul foii „Ultimele N” (și în capturi, prin [InvSheetFrame]). */
@Composable
internal fun LastNBody(current: Int, total: Int?, onPick: (Int) -> Unit) {
    val presets = LastNPresets.filter { total == null || it < total }
    val custom = current !in presets
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ultimele", style = cond(24, 28), modifier = Modifier.padding(start = 2.dp))
        if (presets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in presets) ScopeChip(fmtCount(n), n == current, { onPick(n) }, Modifier.weight(1f))
            }
        }
        NumberField(
            placeholder = if (custom) fmtCount(current) else "Alt număr",
            highlight = custom,
            onDone = { text ->
                val n = text.toIntOrNull()
                when {
                    n != null && n > 0 -> onPick(if (total != null && total > 0) n.coerceAtMost(total) else n)
                    custom -> onPick(current)
                }
            }
        )
        if (total != null && total > 0) {
            Text("DIN ${fmtCount(total)}", style = mono(10, 0.14f, color = TextDim), modifier = Modifier.padding(start = 2.dp))
        }
    }
}

/** Câmpul numeric (aspectul lui NameField): doar cifre, ≤ 6; gol = arată valoarea curentă sau „Alt număr”. */
@Composable
private fun NumberField(placeholder: String, highlight: Boolean, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { v -> value = v.filter { it.isDigit() }.take(6) },
            singleLine = true,
            textStyle = cond(22, 26),
            cursorBrush = SolidColor(Accent2),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(value) }),
            modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Câte poze" },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().clip(R8).background(Surface0)
                        .border(1.5.dp, if (highlight || value.isNotEmpty()) Accent2 else W12, R8)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) Text(placeholder, style = cond(22, 26, color = if (highlight) Accent2 else TextDim), maxLines = 1)
                    inner()
                }
            }
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(48.dp).clip(R8).background(Accent2).pressable({ onDone(value) })
                .semantics { contentDescription = "Gata"; role = Role.Button },
            contentAlignment = Alignment.Center
        ) {
            Icon(InvIcons.CheckBold, null, tint = Surface0, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ConfirmRow(icon: ImageVector, tint: Color, bg: Color, title: String, sub: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(R8).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = cond(20, 22))
            if (sub != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(InvIcons.Restore, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sub, style = body(14))
                }
            }
        }
    }
}

/** Confirmarea unei acțiuni care oprește sau aruncă munca (Oprește / Renunță). */
@Composable
internal fun ConfirmSheet(title: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    InvSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Mascot(state = MascotState.Sorry, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = cond(24, 28))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { InvOutlineButton("Înapoi", onDismiss, height = 56.dp) }
            Box(
                Modifier
                    .pressable(onConfirm)
                    .weight(1f)
                    .height(56.dp)
                    .clip(R8)
                    .background(Error.copy(alpha = 0.12f))
                    .border(1.5.dp, Error.copy(alpha = 0.7f), R8)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Text(confirm, style = cond(20, color = Error))
            }
        }
    }
}
