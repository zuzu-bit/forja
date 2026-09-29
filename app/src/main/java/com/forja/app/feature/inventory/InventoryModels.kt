package com.forja.app.feature.inventory

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.inventory.BinTick
import com.forja.app.core.inventory.DeleteReason
import com.forja.app.core.inventory.DestNames
import com.forja.app.core.inventory.InvDest
import com.forja.app.core.inventory.InvItem
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvPlan
import com.forja.app.core.inventory.InvProgress
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Landing
import com.forja.app.core.inventory.MediaRoots
import com.forja.app.core.inventory.MoveReason
import com.forja.app.core.inventory.TreePaths

/*
 * Stările ecranelor Inventarului, fără Android în ele (în afară de Uri): fiecare ecran e un `…Content(state, …)`
 * fără stare, ca testele de captură să-l deseneze cu date false (InventorySamples.kt).
 */

/** Paginile fluxului de pe ruta CLEANUP: S1 · S2 · S4 · S5 · aplicarea · finalul S6. */
enum class InvPage { Start, Run, Folders, Folder, Apply, Done }

/** Scopul pozelor: „Tot”, „Ultimele N” (N ales de om, reținut), „Album”. */
enum class ScopeChoice { All, LastN, Album }

/** Faza motorului, văzută de UI. */
enum class EnginePhase { Idle, Running, Ready, Applying, Done, Failed }

internal val RUNNING_STAGES = setOf(InvStage.Scanning, InvStage.Grouping, InvStage.Naming)

internal fun phaseOf(p: InvProgress?, plan: InvPlan?): EnginePhase = when {
    p == null -> if (plan != null) EnginePhase.Ready else EnginePhase.Idle
    p.stage in RUNNING_STAGES -> EnginePhase.Running
    p.stage == InvStage.Ready -> if (plan != null) EnginePhase.Ready else EnginePhase.Running
    p.stage == InvStage.Applying -> EnginePhase.Applying
    p.stage == InvStage.Done -> EnginePhase.Done
    else -> EnginePhase.Failed
}

// ───────────────────────────── S1 ─────────────────────────────

@Immutable
data class StartUiState(
    val kind: InvKind = InvKind.Photos,
    val photoAccess: Boolean = true,
    val photoCount: Int? = null,
    val photoBytes: Long? = null,
    val scope: ScopeChoice = ScopeChoice.All,
    /** N-ul din „Ultimele N” (arătat pe chip și când scopul e altul). */
    val lastN: Int = 500,
    val albumName: String? = null,
    val docFolder: String? = null,
    /** Fișierele „libere” din folder (cele pe care le-ar pune în ordine acum). */
    val docCount: Int? = null,
    val docBytes: Long? = null,
    /** Fișierele deja în dosare (sub „Organizate” sau în destinația aleasă): „În ordine · 4 ÎN DOSARE”. */
    val docOrganized: Int? = null,
    val estimateSec: Int? = null,
    /** Rularea existentă (în curs sau gata); null = nimic pornit. */
    val run: RunSummary? = null,
    /** Mesajul ultimei rulări eșuate. */
    val error: String? = null,
    /** Mutări aprobate din laptop care așteaptă acordul Android. */
    val laptopPending: Int = 0
)

@Immutable
data class RunSummary(val kind: InvKind, val percent: Int, val ready: Boolean, val folders: Int, val etaSec: Int?)

// ───────────────────────────── S2 / aplicare ─────────────────────────────

@Immutable
data class RunUiState(
    val kind: InvKind = InvKind.Photos,
    val stage: InvStage = InvStage.Scanning,
    val done: Int = 0,
    val total: Int = 1,
    val etaSec: Int? = null,
    val recent: List<Uri> = emptyList(),
    val bins: List<BinTick> = emptyList(),
    val folders: Int = 0,
    val musicArt: ImageBitmap? = null,
    val musicPlaying: Boolean = false,
    /** Nivelul curent din ZID și ASALT (cipul „NIV. 4” de pe carduri); null = încă necitit. */
    val zidLevel: Int? = null,
    val asaltLevel: Int? = null
) {
    val percent: Int get() = if (stage == InvStage.Ready) 100 else percentOf(done, total)
    val ready: Boolean get() = stage == InvStage.Ready
}

@Immutable
data class ApplyUiState(
    val kind: InvKind = InvKind.Photos,
    val done: Int = 0,
    val total: Int = 0,
    val recent: List<Uri> = emptyList(),
    val bins: List<BinTick> = emptyList(),
    /** Așteptăm acordul din dialogul sistemului. */
    val waiting: Boolean = false,
    /** Așteptăm, dar fereastra Android nu a apărut nici a doua oară: rândul se schimbă și apar cele două butoane. */
    val stuck: Boolean = false
) {
    val percent: Int get() = percentOf(done, total)
}

/** Textele paginii de aplicare când așteptăm acordul (reguli: fără „!”, ș/ț cu virgulă, butoane ≤ 18 caractere). */
object ApplyCopy {
    const val WAITING = "AȘTEPT ACORDUL TĂU"
    const val NOT_SHOWN = "FEREASTRA ANDROID NU A APĂRUT"
    const val RETRY = "Încearcă din nou"
    const val BACK = "Înapoi la dosare"
}

// ───────────────────────────── S4 ─────────────────────────────

@Immutable
data class ThumbRef(val uri: Uri, val mime: String, val name: String)

@Immutable
data class FolderCardUi(
    val id: String,
    val name: String,
    val count: Int,
    val cover: ThumbRef?,
    /** Tipul dominant la documente („PDF”); null la poze. */
    val ext: String? = null
)

@Immutable
data class TrashCardUi(val id: String, val name: String, val count: Int, val bytes: Long, val fan: List<ThumbRef>)

@Immutable
data class FoldersUiState(
    val kind: InvKind,
    val folders: List<FolderCardUi>,
    val trash: TrashCardUi,
    val itemCount: Int,
    val applyEstimateSec: Int,
    val showSite: Boolean
)

// ───────────────────────────── S5 ─────────────────────────────

/** Chip-urile de motiv din „De aruncat” (DeAruncat.dc.html) și ce motive adună fiecare. */
enum class ReasonFilter(val label: String, val reasons: Set<DeleteReason>) {
    All("Toate", emptySet()),
    Duplicate("Duplicate", setOf(DeleteReason.Duplicate, DeleteReason.Similar)),
    Blur("Neclare", setOf(DeleteReason.Blurry)),
    Screens("Capturi", setOf(DeleteReason.OldScreenshot)),
    Tiny("Mici", setOf(DeleteReason.Tiny)),
    Ai("Sugerate", setOf(DeleteReason.AiSuggested, DeleteReason.Accidental)),
    Temp("Temporare", setOf(DeleteReason.TempFile));

    fun matches(r: DeleteReason?): Boolean = this == All || (r != null && r in reasons)
}

internal fun ReasonFilter.icon(): ImageVector = when (this) {
    ReasonFilter.All -> InvIcons.ReasonAll
    ReasonFilter.Duplicate -> InvIcons.ReasonCopy
    ReasonFilter.Blur -> InvIcons.ReasonBlur
    ReasonFilter.Screens -> InvIcons.ReasonScreen
    ReasonFilter.Tiny -> InvIcons.ReasonTiny
    ReasonFilter.Ai -> InvIcons.ReasonAi
    ReasonFilter.Temp -> InvIcons.ReasonTemp
}

internal fun DeleteReason.icon(): ImageVector = when (this) {
    DeleteReason.Duplicate, DeleteReason.Similar -> InvIcons.ReasonCopy
    DeleteReason.Blurry -> InvIcons.ReasonBlur
    DeleteReason.OldScreenshot -> InvIcons.ReasonScreen
    DeleteReason.Tiny -> InvIcons.ReasonTiny
    DeleteReason.AiSuggested, DeleteReason.Accidental -> InvIcons.ReasonAi
    DeleteReason.TempFile -> InvIcons.ReasonTemp
}

/** Explicația insignei (≤ 4 cuvinte). */
internal fun DeleteReason.tip(): String = when (this) {
    DeleteReason.Duplicate -> "Copie identică"
    DeleteReason.Similar -> "Aproape identică"
    DeleteReason.Blurry -> "Poză neclară"
    DeleteReason.Tiny -> "Imagine prea mică"
    DeleteReason.OldScreenshot -> "Captură veche"
    DeleteReason.Accidental -> "Poză accidentală"
    DeleteReason.AiSuggested -> "Propusă de AI"
    DeleteReason.TempFile -> "Fișier temporar"
}

@Immutable
data class CellUi(
    val id: String,
    val uri: Uri,
    val mime: String,
    val name: String,
    val reason: DeleteReason?,
    val takenAt: Long,
    val bytes: Long
)

@Immutable
data class FolderUiState(
    val id: String,
    val name: String,
    val special: Boolean,
    val kind: InvKind,
    /** Elementele vizibile (după filtru), în ordinea planului. */
    val cells: List<CellUi>,
    val totalCount: Int,
    val totalBytes: Long,
    val dateLabel: String,
    val filter: ReasonFilter = ReasonFilter.All,
    /** Chip-urile arătate (doar motivele prezente), cu numărul lor. */
    val filters: List<Pair<ReasonFilter, Int>> = emptyList(),
    val selected: Set<String> = emptySet(),
    val editing: Boolean = false,
    val monthHeaders: Boolean = false
)

/** O țintă din „Mută în…” / „Unește cu…”. */
@Immutable
data class MoveTarget(val id: String, val name: String, val count: Int, val cover: ThumbRef?)

// ───────────────────────────── S6 ─────────────────────────────

/** Confirmarea: `destLabel` = „FORJA” / „Organizate” (rândul atingibil), `destPath` = „PICTURES/FORJA” (meta mono). */
@Immutable
data class ApplyConfirmUi(
    val kind: InvKind,
    val folders: Int,
    val moves: Int,
    val trashCount: Int,
    val trashBytes: Long,
    val destLabel: String = "",
    val destPath: String = ""
)

/** Iconița unui rând din „Locație”. */
enum class DestIcon { Gallery, Pictures, Camera, Folder }

/** Un rând din foaia „Locație”: destinația, numele (≈ 3 cuvinte), calea mono, dacă e cea aleasă. */
@Immutable
data class DestOption(val dest: InvDest, val label: String, val path: String, val selected: Boolean, val icon: DestIcon)

/** Foaia „Locație”: rândurile + ultimul rând „Alt dosar…” (poze și documente: un singur cuvânt în tot fluxul). */
@Immutable
data class LocationUi(val kind: InvKind, val options: List<DestOption>) {
    val otherLabel: String get() = "Alt dosar…"
}

/**
 * Finalul: `place` = unde au ajuns lucrurile (se deschide din buton și din eticheta cu calea), `runId` = rularea
 * (pentru „Pe site” → /insights#inventar/<runId>), `showSite` = contract semnat (rezumatul urcă doar atunci).
 * 4.4.2: `reason` = rândul cu motivul nemutatelor (unul), `ownerApp` / `ownerApps` = a cui sunt pozele (motivul
 * „owned”), `fix` = pagina de rezultat, când planul n-a fost aplicat tot: o singură acțiune („Permite accesul” sau
 * „Încearcă din nou”) și „Înapoi la dosare”, în locul toast-ului „Nu s-a aplicat tot.” de până acum.
 */
@Immutable
data class DoneUiState(
    val kind: InvKind = InvKind.Photos,
    val folders: Int = 0,
    val items: Int = 0,
    val freedBytes: Long = 0L,
    val failed: Int = 0,
    val musicStopped: Boolean = true,
    val place: Landing? = null,
    val runId: String? = null,
    val showSite: Boolean = false,
    val trashed: Int = 0,
    val reason: MoveReason? = null,
    val ownerApp: String? = null,
    val ownerApps: Int = 1,
    val fix: DoneFix? = null
) {
    /** Pagina de rezultat (nu s-a aplicat tot), nu finalul „Gata”. */
    val result: Boolean get() = fix != null
    /** Mutate cu adevărat: [items] numără și pe cele trimise la coș. */
    val moved: Int get() = (items - trashed).coerceAtLeast(0)
}

// ───────────────────────────── Mapări din plan ─────────────────────────────

private fun InvItem.ref() = ThumbRef(uri, mime, name)

internal fun InvPlan.foldersUi(showSite: Boolean): FoldersUiState {
    val cards = folders.map { f ->
        val coverItem = (f.cover.asSequence() + f.itemIds.asSequence()).mapNotNull { items[it] }.firstOrNull()
        FolderCardUi(
            id = f.id,
            name = f.name,
            count = f.itemIds.size,
            cover = coverItem?.ref(),
            ext = if (kind == InvKind.Documents) dominantExt(f.itemIds.mapNotNull { items[it] }) else null
        )
    }
    val trashItems = trash.itemIds.mapNotNull { items[it] }
    val fan = (trash.cover.mapNotNull { items[it] } + trashItems).distinctBy { it.id }.take(3).map { it.ref() }
    val moves = folders.sumOf { it.itemIds.size }
    return FoldersUiState(
        kind = kind,
        folders = cards,
        trash = TrashCardUi(trash.id, trash.name, trashItems.size, trashItems.sumOf { it.bytes }, fan),
        itemCount = moves + trashItems.size,
        applyEstimateSec = applyEstimateSec(kind, moves, trashItems.size),
        showSite = showSite
    )
}

/** Estimarea aplicării (euristică onestă, rotunjită în sus): mutările MediaStore ~30/s, SAF ~6/s, + dialogurile. */
internal fun applyEstimateSec(kind: InvKind, moves: Int, trash: Int): Int {
    if (moves + trash == 0) return 0
    return if (kind == InvKind.Photos) {
        val rounds = ((moves + 499) / 500).coerceAtLeast(1) + if (trash > 0) 1 else 0
        moves / 30 + rounds * 4 + 5
    } else {
        (moves + trash) / 6 + 5
    }
}

internal fun dominantExt(items: List<InvItem>): String? =
    items.groupingBy { extOf(it.name, it.mime) }.eachCount().maxByOrNull { it.value }?.key

internal fun InvPlan.confirmUi(): ApplyConfirmUi {
    val trashItems = trash.itemIds.mapNotNull { items[it] }
    return ApplyConfirmUi(
        kind, folders.count { it.itemIds.isNotEmpty() }, folders.sumOf { it.itemIds.size }, trashItems.size, trashItems.sumOf { it.bytes },
        destLabel = DestNames.short(dest),
        destPath = TreePaths.tail(DestNames.path(dest, source)).uppercase()
    )
}

/** Rândurile din „Locație”: cele trei rădăcini de poze (sau folderul ales) + destinația proprie, dacă e aleasă. */
internal fun InvPlan.locationUi(): LocationUi {
    val current = dest
    val options = if (kind == InvKind.Photos) {
        val chosen = (current as? InvDest.Media)?.root?.let { MediaRoots.normalize(it, anyTop = true) } ?: MediaRoots.DEFAULT
        val presets = MediaRoots.PRESETS.map { root ->
            val icon = when (root) {
                MediaRoots.DEFAULT -> DestIcon.Gallery
                MediaRoots.GALLERY -> DestIcon.Pictures
                else -> DestIcon.Camera
            }
            DestOption(InvDest.Media(root), MediaRoots.optionLabel(root), MediaRoots.path(root), root == chosen, icon)
        }
        if (chosen in MediaRoots.PRESETS) presets
        else presets + DestOption(InvDest.Media(chosen), MediaRoots.optionLabel(chosen), TreePaths.tail(MediaRoots.path(chosen)), true, DestIcon.Folder)
    } else {
        val tree = current as? InvDest.Tree
        val default = InvDest.Tree(null, DocumentOrganizer.ROOT_FOLDER)
        val base = DestOption(
            default, DestNames.option(default), TreePaths.tail(DestNames.path(default, source)).uppercase(), tree?.tree == null, DestIcon.Folder
        )
        if (tree?.tree == null) listOf(base)
        else listOf(base, DestOption(tree, DestNames.option(tree), TreePaths.tail(DestNames.path(tree, source)).uppercase(), true, DestIcon.Folder))
    }
    return LocationUi(kind, options)
}

internal fun InvPlan.moveTargets(exclude: String?): List<MoveTarget> =
    folders.filter { it.id != exclude }.map { f ->
        val c = (f.cover.asSequence() + f.itemIds.asSequence()).mapNotNull { items[it] }.firstOrNull()
        MoveTarget(f.id, f.name, f.itemIds.size, c?.ref())
    }

/** Starea S5 pentru un dosar (sau „De aruncat”), cu filtrul, selecția și editarea date de ecran. */
internal fun InvPlan.folderUi(
    folderId: String,
    filter: ReasonFilter,
    selected: Set<String>,
    editing: Boolean
): FolderUiState? {
    val special = folderId == trash.id
    val f = if (special) trash else folders.firstOrNull { it.id == folderId } ?: return null
    val all = f.itemIds.mapNotNull { id ->
        items[id]?.let { CellUi(it.id, it.uri, it.mime, it.name, if (special) it.reason else null, it.takenAt, it.bytes) }
    }
    val filters = if (special) {
        val counts = ReasonFilter.entries.filter { it != ReasonFilter.All }
            .map { rf -> rf to all.count { rf.matches(it.reason) } }
            .filter { it.second > 0 }
        listOf(ReasonFilter.All to all.size) + counts
    } else emptyList()
    val active = if (special && filters.any { it.first == filter }) filter else ReasonFilter.All
    val cells = if (active == ReasonFilter.All) all else all.filter { active.matches(it.reason) }
    val times = all.map { it.takenAt }.filter { it > 0 }
    val allIds = all.mapTo(HashSet(all.size * 2)) { it.id }
    return FolderUiState(
        id = f.id,
        name = f.name,
        special = special,
        kind = kind,
        cells = cells,
        totalCount = all.size,
        totalBytes = all.sumOf { it.bytes },
        dateLabel = dateRange(times),
        filter = active,
        filters = filters,
        selected = selected.filterTo(HashSet()) { it in allIds },
        editing = editing,
        monthHeaders = !special && all.size > 60 && times.map { monthKey(it) }.distinct().size > 1
    )
}

/** „aug 2023”, „aug–oct 2023”, „2022–2024”. */
internal fun dateRange(times: List<Long>): String {
    if (times.isEmpty()) return ""
    val lo = times.min()
    val hi = times.max()
    val a = fmtMonth(lo)
    val b = fmtMonth(hi)
    if (a == b) return a
    val ya = a.substringAfter(' ')
    val yb = b.substringAfter(' ')
    return if (ya == yb) "${a.substringBefore(' ')}–${b.substringBefore(' ')} $ya" else "$ya–$yb"
}
