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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import com.forja.app.core.inventory.InvKind

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
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
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

/** S6 — confirmarea: două rânduri-iconiță, onestitatea (30 de zile), „Aplică”. */
@Composable
internal fun ApplyConfirmSheet(ui: ApplyConfirmUi, onApply: () -> Unit, onDismiss: () -> Unit) {
    InvSheet(onDismiss) {
        val photos = ui.kind == InvKind.Photos
        if (ui.moves > 0) {
            ConfirmRow(
                icon = InvIcons.Folder, tint = Accent2, bg = Accent2.copy(alpha = 0.14f),
                title = "${fmtCount(ui.folders)} ${if (ui.folders == 1) "dosar" else "dosare"} în ${if (photos) "Galerie" else "Organizate"}",
                sub = null
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
