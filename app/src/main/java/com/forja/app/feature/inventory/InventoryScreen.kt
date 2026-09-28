package com.forja.app.feature.inventory

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forja.app.BuildConfig
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.components.CoachMarks
import com.forja.app.core.designsystem.components.LocalToast
import com.forja.app.core.inventory.BinTick
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.music.Music
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Modurile „Cât aștepți” (jocurile ZID și ASALT, muzica), rute separate în MainActivity. */
enum class InvWait { Zid, Asalt, Music }

private fun initialStack(link: InvPage?, phase: EnginePhase): List<InvPage> {
    val target = link ?: when (phase) {
        EnginePhase.Running -> InvPage.Run
        EnginePhase.Ready -> InvPage.Folders
        EnginePhase.Applying -> InvPage.Apply
        else -> null
    }
    return if (target == null || target == InvPage.Start) listOf(InvPage.Start) else listOf(InvPage.Start, target)
}

/**
 * Inventarul (ruta CLEANUP): S1 → S2 → S4 → S5 → S6, într-o singură rută cu o stivă proprie de pagini.
 * Starea adevărată e a motorului (Inventory.progress / plan); pagina afișată se potrivește mereu cu ea.
 */
@Composable
fun InventoryScreen(onBack: () -> Unit, onOpenWait: (InvWait) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val reduced = LocalReducedMotion.current
    val vm: InventoryViewModel = viewModel()

    val progress by vm.progress.collectAsState()
    val plan by vm.plan.collectAsState()
    val base by vm.start.collectAsState()
    val selection by vm.selection.collectAsState()
    val contract by vm.contractSigned.collectAsState()
    val albums by vm.albums.collectAsState()
    val done by vm.done.collectAsState()
    val outcome by vm.outcome.collectAsState()
    val applyWaiting by vm.applyWaiting.collectAsState()
    val applyProgress by vm.applyProgress.collectAsState()
    val sender by vm.sender.collectAsState()
    val applying by vm.applyingFlow.collectAsState()
    val track by Music.nowPlaying.collectAsState()
    val phase = phaseOf(progress, plan)

    // ── stiva de pagini (salvată) ──
    var stackKey by rememberSaveable { mutableStateOf(initialStack(InventoryLinks.peek(), phase).joinToString("|")) }
    val stack = remember(stackKey) { stackKey.split('|').mapNotNull { runCatching { InvPage.valueOf(it) }.getOrNull() }.ifEmpty { listOf(InvPage.Start) } }
    fun setStack(vararg pages: InvPage) { stackKey = pages.joinToString("|") }
    fun push(p: InvPage) { if (stack.last() != p) stackKey = (stack + p).joinToString("|") }
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stackKey = stack.dropLast(1).joinToString("|")
        return true
    }
    LaunchedEffect(Unit) {
        InventoryLinks.open.collect { p ->
            if (p != null) {
                InventoryLinks.consume()
                if (p == InvPage.Start) setStack(InvPage.Start) else setStack(InvPage.Start, p)
            }
        }
    }

    // ── starea S5 ──
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ReasonFilter.All) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf(false) }
    fun leaveFolder() { selected = emptySet(); editing = false; filter = ReasonFilter.All }

    // ── foi și suprapuneri ──
    var albumSheet by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var sealed by remember { mutableStateOf(false) }
    var guideReplay by rememberSaveable { mutableLongStateOf(0L) }
    var foldersIntroFor by rememberSaveable { mutableStateOf<String?>(null) }

    // ── lansatoare ──
    var pendingStart by rememberSaveable { mutableStateOf(false) }
    fun startNow() {
        if (vm.start()) push(InvPage.Run)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPhotoStats()
        if (pendingStart) {
            pendingStart = false
            // Locația din poze și notificările sunt opționale: pornim oricum, doar galeria e obligatorie.
            if (InvPermissions.hasPhotoAccess(context)) startNow() else toast.show("Fără acces la galerie.")
        }
    }
    var startAfterTree by rememberSaveable { mutableStateOf(false) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val andStart = startAfterTree
        startAfterTree = false
        if (uri != null) vm.onTreePicked(uri, startAfter = andStart) { push(InvPage.Run) }
    }
    fun pickFolder(andStart: Boolean) {
        startAfterTree = andStart
        try { treeLauncher.launch(null) } catch (_: Exception) { startAfterTree = false; toast.show("Nu pot deschide folderele.") }
    }
    val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onDialogResult(res.resultCode == Activity.RESULT_OK)
    }
    val senderLifecycle = LocalLifecycleOwner.current
    LaunchedEffect(sender) {
        val s = sender ?: return@LaunchedEffect
        // Dialogul sistemului se cere doar cu ecranul în față (din fundal Android nu îl arată).
        senderLifecycle.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        vm.onSenderLaunched()
        try { senderLauncher.launch(IntentSenderRequest.Builder(s).build()) } catch (_: Exception) { vm.onDialogResult(false) }
    }

    // ── viața ecranului ──
    DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshPhotoStats() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ── rezultatul aplicării ──
    LaunchedEffect(outcome) {
        when (outcome) {
            ApplyOutcome.Complete -> {
                sealed = true
                if (!reduced) delay(1_100)
                sealed = false
                setStack(InvPage.Start, InvPage.Done)
                vm.consumeOutcome()
            }
            ApplyOutcome.Cancelled -> {
                setStack(InvPage.Start, InvPage.Folders)
                vm.consumeOutcome()
            }
            ApplyOutcome.Partial -> {
                toast.show("Nu s-a aplicat tot.")
                setStack(InvPage.Start, if (vm.plan.value != null) InvPage.Folders else InvPage.Start)
                vm.consumeOutcome()
            }
            null -> Unit
        }
    }

    // ── datele paginilor ──
    val runSummary = when (phase) {
        EnginePhase.Running -> progress?.let { RunSummary(it.kind, percentOf(it.done, it.total), false, 0, it.etaSec) }
        EnginePhase.Ready -> plan?.let { RunSummary(it.kind, 100, true, it.folders.size, null) }
        EnginePhase.Applying -> progress?.let { RunSummary(it.kind, percentOf(it.done, it.total), false, 0, it.etaSec) }
        else -> null
    }
    val startState = base.copy(
        run = runSummary,
        error = if (phase == EnginePhase.Failed) progress?.error ?: "" else null
    )
    val gameLevels = com.forja.app.feature.games.rememberWaitLevels()
    val art = track?.art
    val artBitmap = remember(art) { art?.asImageBitmap() }
    // Binele cunoscute ale planului (pentru banda de la aplicare, și după ce planul dispare).
    var planBins by remember { mutableStateOf<List<BinTick>>(emptyList()) }
    LaunchedEffect(plan) {
        plan?.let { p -> planBins = p.folders.sortedByDescending { it.itemIds.size }.take(4).map { BinTick(it.name, it.itemIds.size) } }
    }

    val folderUi = remember(plan, openFolder, filter, selected, editing) {
        val pl = plan
        val id = openFolder
        if (pl != null && id != null) pl.folderUi(id, filter, selected, editing) else null
    }

    // pagina afișată: vârful stivei, potrivit cu starea motorului
    val top = stack.last()
    val page = when (top) {
        InvPage.Start -> InvPage.Start
        InvPage.Run -> when (phase) {
            EnginePhase.Running, EnginePhase.Ready -> InvPage.Run
            EnginePhase.Applying -> InvPage.Apply
            else -> InvPage.Start
        }
        InvPage.Folders -> when {
            applying || sealed -> InvPage.Apply
            phase == EnginePhase.Ready -> InvPage.Folders
            phase == EnginePhase.Applying -> InvPage.Apply
            phase == EnginePhase.Running -> InvPage.Run
            else -> InvPage.Start
        }
        InvPage.Folder -> when {
            applying || sealed -> InvPage.Apply
            phase == EnginePhase.Ready && openFolder != null && plan?.let { p -> p.trash.id == openFolder || p.folders.any { it.id == openFolder } } == true -> InvPage.Folder
            phase == EnginePhase.Ready -> InvPage.Folders
            else -> InvPage.Start
        }
        InvPage.Apply -> when {
            applying || sealed || applyWaiting || outcome != null || phase == EnginePhase.Applying -> InvPage.Apply
            done != null -> InvPage.Done
            phase == EnginePhase.Ready -> InvPage.Folders
            else -> InvPage.Start
        }
        InvPage.Done -> if (done != null) InvPage.Done else InvPage.Start
    }

    // Dosarele văzute: pastila globală „Gata” se retrage.
    LaunchedEffect(page, plan?.runId) {
        if (page == InvPage.Folders || page == InvPage.Folder) InventoryLinks.markReadySeen(plan?.runId)
    }

    BackHandler(enabled = applying) { /* aplicarea are nevoie de ecran pentru dialogurile următoare */ }
    BackHandler(enabled = !applying && (stack.size > 1 || editing || selected.isNotEmpty())) {
        when {
            editing -> editing = false
            selected.isNotEmpty() -> selected = emptySet()
            else -> {
                if (top == InvPage.Folder) leaveFolder()
                if (!pop()) onBack()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { pageTransition(initialState, targetState, reduced) },
            label = "inventoryPage",
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) { p ->
            when (p) {
                InvPage.Start -> CoachMarks(
                    screen = if (guideReplay == 0L) "inventar_start" else "inventar_start~$guideReplay",
                    steps = StartCoachSteps
                ) {
                    InventoryStartContent(
                        startState,
                        StartActions(
                            onBack = onBack,
                            onInfo = { guideReplay = System.currentTimeMillis() },
                            onPickKind = { vm.pickKind(it) },
                            onPickScope = { vm.pickScope(it) },
                            onPickAlbum = { vm.loadAlbums(); albumSheet = true },
                            onPickFolder = { pickFolder(andStart = false) },
                            onAskPhotos = { permLauncher.launch(InvPermissions.forGallery(context)) },
                            onStart = {
                                when {
                                    selection.kind == InvKind.Documents && !vm.hasDocTree -> pickFolder(andStart = true)
                                    selection.kind == InvKind.Photos -> {
                                        val ask = InvPermissions.forPhotoStart(context)
                                        if (ask.isNotEmpty()) {
                                            pendingStart = true
                                            permLauncher.launch(ask)
                                        } else startNow()
                                    }
                                    else -> {
                                        val ask = InvPermissions.forNotifications(context)
                                        if (ask.isNotEmpty()) {
                                            pendingStart = true
                                            permLauncher.launch(ask.toTypedArray())
                                        } else startNow()
                                    }
                                }
                            },
                            onOpenRun = { push(if (phase == EnginePhase.Ready) InvPage.Folders else InvPage.Run) },
                            onStopRun = { confirmStop = true },
                            onDiscard = { confirmDiscard = true },
                            onAllowLaptop = { vm.allowLaptop() }
                        )
                    )
                }

                InvPage.Run -> {
                    val pr = progress
                    LaunchedEffect(Unit) { if (Music.hasAccess(context)) Music.ensureStarted(context) }
                    val runState = RunUiState(
                        kind = pr?.kind ?: selection.kind,
                        stage = if (phase == EnginePhase.Ready) InvStage.Ready else pr?.stage ?: InvStage.Scanning,
                        done = pr?.done ?: 0,
                        total = pr?.total ?: 1,
                        etaSec = pr?.etaSec,
                        recent = pr?.recent ?: emptyList(),
                        bins = pr?.bins ?: emptyList(),
                        folders = plan?.folders?.size ?: 0,
                        musicArt = artBitmap,
                        musicPlaying = track?.playing == true,
                        zidLevel = gameLevels.zid,
                        asaltLevel = gameLevels.asalt
                    )
                    CoachMarks(screen = "inventar_rulare_44", steps = remember(runState.kind) { runCoachSteps(runState.kind) }) {
                        InventoryRunContent(
                            runState,
                            RunActions(
                                onClose = { if (!pop()) onBack() },
                                onZid = { onOpenWait(InvWait.Zid) },
                                onAsalt = { onOpenWait(InvWait.Asalt) },
                                onMusic = { onOpenWait(InvWait.Music) },
                                onOpenFolders = { setStack(InvPage.Start, InvPage.Folders) }
                            )
                        )
                    }
                }

                InvPage.Folders -> {
                    val pl = plan
                    if (pl != null) {
                        val ui = remember(pl, contract) { pl.foldersUi(showSite = contract) }
                        val playIntro = remember { foldersIntroFor != pl.runId }
                        LaunchedEffect(pl.runId) { foldersIntroFor = pl.runId }
                        CoachMarks(screen = "inventar_dosare", steps = remember(pl.kind) { foldersCoachSteps(pl.kind) }) {
                            InventoryFoldersContent(
                                playIntro = playIntro,
                                state = ui,
                                actions = FoldersActions(
                                    onBack = { if (!pop()) onBack() },
                                    onSite = { openSite(context) { toast.show(it) } },
                                    onOpenTrash = { leaveFolder(); openFolder = pl.trash.id; push(InvPage.Folder) },
                                    onOpenFolder = { id -> leaveFolder(); openFolder = id; push(InvPage.Folder) },
                                    onFolderLongPress = { id -> folderMenu = id },
                                    onApply = { confirmApply = true }
                                )
                            )
                        }
                    }
                }

                InvPage.Folder -> {
                    val ui = folderUi
                    if (ui != null) {
                        InventoryFolderContent(
                            ui,
                            FolderActions(
                                onBack = { leaveFolder(); if (!pop()) onBack() },
                                onSelectAll = {
                                    val visible = ui.cells.map { it.id }.toSet()
                                    selected = if (visible.isNotEmpty() && selected.containsAll(visible)) selected - visible else selected + visible
                                },
                                onStartEdit = { editing = true },
                                onRename = { name -> vm.rename(ui.id, name); editing = false },
                                onFilter = { filter = it },
                                onToggle = { cid -> selected = if (cid in selected) selected - cid else selected + cid },
                                onOpen = { cid -> preview = cid },
                                onKeep = { vm.keep(selected); selected = emptySet() },
                                onMove = { moveSheet = true },
                                onTrash = { vm.toTrash(selected); selected = emptySet() }
                            )
                        )
                    }
                }

                InvPage.Apply -> {
                    val pr = progress
                    val live = pr?.stage == InvStage.Applying
                    InventoryApplyContent(
                        ApplyUiState(
                            kind = pr?.kind ?: plan?.kind ?: selection.kind,
                            done = if (live) pr!!.done else applyProgress.first,
                            total = if (live) pr!!.total else applyProgress.second,
                            recent = if (live) pr!!.recent else emptyList(),
                            bins = planBins,
                            waiting = applyWaiting
                        ),
                        sealed = sealed
                    )
                }

                InvPage.Done -> {
                    val d = done
                    if (d != null) {
                        InventoryDoneContent(
                            d,
                            DoneActions(
                                onGallery = { openResult(context, d.kind) { toast.show(it) } },
                                onClose = onBack
                            )
                        )
                    }
                }
            }
        }
    }

    // ── previzualizarea (peste tot ecranul, și peste barele sistemului) ──
    val cid = preview
    val fu = folderUi
    if (cid != null && fu != null && page == InvPage.Folder) {
        Box(Modifier.fillMaxSize()) {
            InvPreview(
                cells = fu.cells,
                startId = cid,
                special = fu.special,
                onClose = { preview = null },
                onAction = { c -> if (fu.special) vm.keep(listOf(c.id)) else vm.toTrash(listOf(c.id)) },
                onOpenExternal = { c -> openExternal(context, c.uri, c.mime) { toast.show(it) } }
            )
        }
    }

    // ── foile ──
    if (albumSheet) {
        AlbumSheet(
            albums = albums,
            selectedId = if (selection.scope == ScopeChoice.Album) selection.albumId else null,
            onPick = { vm.pickAlbum(it); albumSheet = false },
            onDismiss = { albumSheet = false }
        )
    }
    val pl = plan
    if (moveSheet && pl != null) {
        val exclude = openFolder
        MoveSheet(
            kind = pl.kind,
            targets = remember(pl, exclude) { pl.moveTargets(exclude) },
            onPick = { to -> vm.move(selected, to); selected = emptySet(); moveSheet = false },
            onNew = { name -> vm.newFolder(name, selected); selected = emptySet(); moveSheet = false },
            onDismiss = { moveSheet = false }
        )
    }
    val menuId = folderMenu
    if (menuId != null && pl != null) {
        val all = remember(pl) { pl.moveTargets(null) }
        val target = all.firstOrNull { it.id == menuId }
        if (target == null) {
            LaunchedEffect(menuId) { folderMenu = null }
        } else {
            FolderActionsSheet(
                kind = pl.kind,
                folder = target,
                others = all.filter { it.id != menuId },
                onRename = { vm.rename(menuId, it); folderMenu = null },
                onMerge = { into -> vm.merge(menuId, into); folderMenu = null },
                onDissolve = { vm.dissolve(menuId); folderMenu = null },
                onDismiss = { folderMenu = null }
            )
        }
    }
    if (confirmApply && pl != null) {
        ApplyConfirmSheet(
            ui = remember(pl) { pl.confirmUi() },
            onApply = {
                confirmApply = false
                vm.apply()
                push(InvPage.Apply)
            },
            onDismiss = { confirmApply = false }
        )
    }
    if (confirmStop) {
        ConfirmSheet("Oprești analiza?", "Oprește", onConfirm = { vm.cancelRun(); confirmStop = false; setStack(InvPage.Start) }, onDismiss = { confirmStop = false })
    }
    if (confirmDiscard) {
        ConfirmSheet("Renunți la dosare?", "Renunță", onConfirm = { vm.discardPlan(); confirmDiscard = false; setStack(InvPage.Start) }, onDismiss = { confirmDiscard = false })
    }
}

private fun pageTransition(from: InvPage, to: InvPage, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val forward = to.ordinal >= from.ordinal
    val slide = tween<androidx.compose.ui.unit.IntOffset>(340, easing = FastOutSlowInEasing)
    return if (forward) {
        (fadeIn(tween(260)) + slideInHorizontally(slide) { it / 5 }) togetherWith (fadeOut(tween(200)) + slideOutHorizontally(slide) { -it / 8 })
    } else {
        (fadeIn(tween(260)) + slideInHorizontally(slide) { -it / 8 }) togetherWith (fadeOut(tween(200)) + slideOutHorizontally(slide) { it / 5 })
    }
}

/** „Pe site”: panoul online (organizarea din laptop), cu același cont. */
private fun openSite(context: android.content.Context, onFail: (String) -> Unit) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.INSIGHTS_URL.trimEnd('/') + "/insights")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        onFail("Nu am găsit un browser.")
    }
}

/** Un element în aplicația telefonului (video, PDF, alt document). */
private fun openExternal(context: android.content.Context, uri: Uri, mime: String, onFail: (String) -> Unit) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        onFail("Nicio aplicație nu îl poate deschide.")
    }
}

/** „Galerie” (poze) / „Fișiere” (documente) după aplicare. */
private fun openResult(context: android.content.Context, kind: InvKind, onFail: (String) -> Unit) {
    val intents = if (kind == InvKind.Photos) listOf(
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        Intent(Intent.ACTION_VIEW).setType("image/*")
    ) else buildList {
        if (Build.VERSION.SDK_INT >= 29) add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES))
        add(Intent(Intent.ACTION_VIEW).setType(DocumentsContract.Document.MIME_TYPE_DIR))
    }
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
    onFail("Nu am găsit aplicația.")
}
