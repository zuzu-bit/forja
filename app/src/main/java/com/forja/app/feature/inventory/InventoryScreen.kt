package com.forja.app.feature.inventory

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.components.CoachMarks
import com.forja.app.core.designsystem.components.LocalToast
import com.forja.app.core.inventory.AllFiles
import com.forja.app.core.inventory.BinTick
import com.forja.app.core.inventory.InvDest
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.MediaRoots
import com.forja.app.core.music.Music
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val consent by vm.consent.collectAsState()
    val applying by vm.applyingFlow.collectAsState()
    val access by vm.access.collectAsState()
    val allFiles by vm.allFiles.collectAsState()
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
    // Confirmarea și pagina ei „Locație” supraviețuiesc împreună recreării activității (selectorul „Alt dosar…” /
    // „Alt folder…” e deschis peste ele); o confirmare nouă pornește mereu de la rezumat, nu de la „Locație”.
    var confirmApply by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var sealed by remember { mutableStateOf(false) }
    var guideReplay by rememberSaveable { mutableLongStateOf(0L) }
    var lastNSheet by remember { mutableStateOf(false) }
    var showLocation by rememberSaveable { mutableStateOf(false) }
    // Confirmarea ține doar de pagina cu dosarele: plecată de acolo (plan aplicat, renunțat, alt link), se închide.
    val topPage = stack.last()
    LaunchedEffect(topPage) {
        if (topPage != InvPage.Folders) { confirmApply = false; showLocation = false }
    }
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
        try { treeLauncher.launch(null) } catch (_: Exception) { startAfterTree = false; toast.show("Nu pot deschide dosarele.") }
    }
    // ── „Acces la toate fișierele” (4.4.2) ──
    // `accessAfter` = omul a atins deja „Aplică” (un dosar care cere accesul) sau „Permite accesul” pe pagina de rezultat:
    // întors din setări cu accesul dat, aplicarea pornește singură. „Permite” din rândul confirmării doar dă accesul:
    // foaia rămâne deschisă (fără rând), iar „Aplică” merge apoi fără ferestre de acord.
    var accessAfter by rememberSaveable { mutableStateOf(false) }
    fun applyNow() {
        confirmApply = false
        showLocation = false
        vm.applySoon()
        setStack(InvPage.Start, InvPage.Folders, InvPage.Apply)
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = vm.onAccessReturned()
        val after = accessAfter
        accessAfter = false
        // Fără acces: rămâne ce era (confirmarea cu rândul ei sau pagina de rezultat); „Aplică” merge cu ferestrele de acord.
        if (granted && after) applyNow()
    }
    fun askAccess(from: String) {
        accessAfter = from != "confirm"
        var opened = false
        for (intent in AllFiles.intents(context)) {
            try { allFilesLauncher.launch(intent); opened = true; break } catch (_: Exception) { }
        }
        vm.onAccessAsked(from, opened)
        if (!opened) { accessAfter = false; toast.show("Nu pot deschide setările.") }
    }
    // Plasa: dacă setările nu întorc rezultat (unele versiuni OEM), revenirea în ecran (ON_RESUME recitește accesul) pornește aplicarea.
    LaunchedEffect(allFiles) {
        if (allFiles && accessAfter) { accessAfter = false; applyNow() }
    }
    // „Locație” → „Alt dosar…” (poze): selectorul pornește lângă destinația curentă; alegerea se traduce în RELATIVE_PATH.
    // Orice dosar din memoria internă (4.4.2): în afara Pictures/DCIM, fără acces complet, confirmarea îl cere.
    val photoDestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            when (vm.pickPhotoFolder(uri)) {
                PhotoPick.Set, PhotoPick.NeedsAccess -> showLocation = false
                PhotoPick.OnlyStandard -> toast.show("Alege un dosar din Pictures sau DCIM.")
                PhotoPick.Invalid -> toast.show("Alege alt dosar.")
            }
        }
    }
    // „Locație” → „Alt folder…” (documente): selectorul pornește în destinația curentă (sau în folderul ales).
    val docsDestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.setDocsDest(uri); showLocation = false }
    }
    fun pickOtherDest(kind: InvKind) {
        try {
            if (kind == InvKind.Photos) photoDestLauncher.launch(DocumentsContract.buildDocumentUri(EXTERNAL_DOCS, "primary:" + photoPickerStart(plan?.dest)))
            else docsDestLauncher.launch(vm.docsPickerStart())
        } catch (_: Exception) {
            toast.show("Nu pot deschide dosarele.")
        }
    }
    // Ecranul final: selectorul sistemului în modul „răsfoiește”, pornit în noua locație (pasul 2 din OpenPlace).
    val browseLauncher = rememberLauncherForActivityResult(BrowseAt()) { uri ->
        if (uri != null) openExternal(context, uri, try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "*/*") { toast.show(it) }
    }
    val browse = OpenPlace.Browse { folder -> try { browseLauncher.launch(folder); true } catch (_: Exception) { false } }
    fun openPlace(d: DoneUiState) {
        val place = d.place?.target
        val ok = if (place != null) OpenPlace.folder(context, place, browse) else OpenPlace.files(context)
        if (!ok) toast.show("Nu am găsit aplicația.")
    }
    // Dialogurile de acord (scriere / coș / laptop): lansare cu activitatea în față, plasă, revenire fără răspuns.
    ConsentLauncher(
        consent = vm.consent,
        onLaunched = vm::onConsentLaunched,
        onMissing = vm::onConsentMissing,
        onResult = vm::onDialogResult
    )

    // ── viața ecranului ──
    DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume() }
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
                // Pagina de rezultat (4.4.2), nu toast + dosare: ce s-a aplicat, ce a rămas și de ce, cu o acțiune.
                // „Înapoi” (gest sau buton) duce la dosare.
                if (vm.plan.value != null) setStack(InvPage.Start, InvPage.Folders, InvPage.Done) else setStack(InvPage.Start, InvPage.Done)
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

    // Cât se mută sau cât dialogul e pe drum, „înapoi” nu face nimic (aplicarea are nevoie de ecran). Doar când fereastra
    // Android n-a apărut (butoanele sunt pe ecran) e la fel ca „Înapoi la dosare”.
    BackHandler(enabled = applying) {
        if (consent?.stuck == true || (applyWaiting && consent == null)) vm.cancelConsent("gesture")
    }
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
                            onPickLastN = { lastNSheet = true },
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
                                    onApply = { showLocation = false; confirmApply = true }
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
                            waiting = applyWaiting,
                            stuck = applyWaiting && consent?.stuck == true
                        ),
                        sealed = sealed,
                        onRetry = vm::retryConsent,
                        onBack = { vm.cancelConsent("button") }
                    )
                }

                InvPage.Done -> {
                    val d = done
                    if (d != null) {
                        InventoryDoneContent(
                            d,
                            DoneActions(
                                onGallery = {
                                    if (d.kind == InvKind.Photos) { if (!OpenPlace.gallery(context, d.place)) toast.show("Nu am găsit aplicația.") }
                                    else openPlace(d)
                                },
                                onClose = onBack,
                                onPlace = { openPlace(d) },
                                onSite = { openSite(context, d.runId) { toast.show(it) } },
                                onFix = {
                                    when (d.fix) {
                                        DoneFix.Access -> askAccess("result")
                                        DoneFix.Retry -> applyNow()
                                        null -> Unit
                                    }
                                },
                                onFolders = { setStack(InvPage.Start, InvPage.Folders) }
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
    if (lastNSheet) {
        LastNSheet(
            current = selection.lastN,
            total = base.photoCount,
            onPick = { vm.pickLastN(it); lastNSheet = false },
            onDismiss = { lastNSheet = false }
        )
    }
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
            location = remember(pl) { pl.locationUi() },
            showLocation = showLocation,
            onShowLocation = { showLocation = it },
            onPickDest = { dest -> vm.setDestination(dest); showLocation = false },
            onOther = { pickOtherDest(pl.kind) },
            onApply = {
                // Un dosar din afara Pictures/DCIM nu se poate aplica fără acces complet: „Aplică” îl cere întâi
                // (verificat aici, direct din plan: rândul accesului se calculează în fundal și poate întârzia o clipă).
                val root = (pl.dest as? InvDest.Media)?.root
                if (pl.kind == InvKind.Photos && root != null && !MediaRoots.standard(root) && AllFiles.available && !allFiles) askAccess("dest")
                else applyNow()
            },
            onDismiss = { confirmApply = false; showLocation = false },
            access = access,
            onAllowAccess = { askAccess("confirm") }
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

/** Furnizorul memoriei (ExternalStorageProvider): de aici pornește selectorul „Alt dosar…”. */
private const val EXTERNAL_DOCS = "com.android.externalstorage.documents"

/**
 * Unde pornește selectorul „Alt dosar…” la poze: în dosarul-părinte al destinației curente („Pictures/FORJA/” →
 * „Pictures”, „Documents/Poze/” → „Documents”), ca vecinii ei să se vadă; implicit în Pictures.
 */
internal fun photoPickerStart(dest: InvDest?): String {
    val segs = ((dest as? InvDest.Media)?.root?.let { MediaRoots.normalize(it, anyTop = true) } ?: MediaRoots.DEFAULT)
        .trimEnd('/').split('/').filter { it.isNotBlank() }
    return (if (segs.size > 1) segs.dropLast(1) else segs).joinToString("/").ifBlank { "Pictures" }
}

/** „Pe site”: secțiunea Inventar a site-ului (cu rularea, dacă o știm: /insights#inventar/<runId>). */
private fun openSite(context: android.content.Context, runId: String? = null, onFail: (String) -> Unit) {
    if (!com.forja.app.core.network.SiteLinks.open(context, com.forja.app.core.network.SiteLinks.Section.Inventar, runId)) {
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
