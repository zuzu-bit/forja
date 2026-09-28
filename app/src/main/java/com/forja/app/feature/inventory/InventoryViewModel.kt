package com.forja.app.feature.inventory

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.Album
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.cleanup.OrgItem
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.core.cleanup.OrganizerLedger
import com.forja.app.core.inventory.ApplyResult
import com.forja.app.core.inventory.DocCounter
import com.forja.app.core.inventory.DocDest
import com.forja.app.core.inventory.InvDest
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvPlan
import com.forja.app.core.inventory.InvProgress
import com.forja.app.core.inventory.InvScope
import com.forja.app.core.inventory.Inventory
import com.forja.app.core.inventory.Landing
import com.forja.app.core.inventory.MediaRoots
import com.forja.app.core.inventory.TreePaths
import com.forja.app.core.inventory.mediaRootFromTree
import com.forja.app.core.music.Music
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ce a ales omul în S1. `lastN` = N-ul din „Ultimele N” (reținut în Prefs). */
data class StartSelection(
    val kind: InvKind = InvKind.Photos,
    val scope: ScopeChoice = ScopeChoice.All,
    val albumId: Long? = null,
    val albumName: String? = null,
    val lastN: Int = 500
)

/** Cum s-a încheiat o aplicare. */
enum class ApplyOutcome { Complete, Partial, Cancelled }

/**
 * Inventarul pe ecran: alegerile din S1 (cu cifrele reale din galerie / folder și estimarea sinceră a motorului),
 * editările din dosare și aplicarea (dialogurile sistemului în bucăți de ≤ 500, apoi Inventory.apply).
 * Analiza propriu-zisă e a motorului (core/inventory), în fundal; aici doar o pornim și o urmărim.
 */
class InventoryViewModel(app: Application) : AndroidViewModel(app) {
    private val forja = app as ForjaApp
    private val ctx: Context get() = getApplication()

    val progress: StateFlow<InvProgress?> = Inventory.progress
    val plan: StateFlow<InvPlan?> = Inventory.plan
    val contractSigned: StateFlow<Boolean> =
        forja.prefs.contractSigned.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ─────────────────────────── S1: alegeri și cifre ───────────────────────────

    private val _selection = MutableStateFlow(StartSelection())
    val selection: StateFlow<StartSelection> = _selection.asStateFlow()

    private data class PhotoStats(val access: Boolean, val count: Int?, val bytes: Long?)
    /** `count`/`bytes` = fișierele libere, `organized` = cele deja în dosare (DocCensus). */
    private data class DocStats(val tree: Uri?, val name: String?, val count: Int?, val bytes: Long?, val organized: Int? = null)

    private val photo = MutableStateFlow(PhotoStats(access = InvPermissions.hasPhotoAccess(app), count = null, bytes = null))
    private val docs = MutableStateFlow(DocStats(null, null, null, null))
    private val estimate = MutableStateFlow<Int?>(null)
    private val laptop = MutableStateFlow<List<OrgItem>>(emptyList())

    private val _albums = MutableStateFlow<List<Album>?>(null)
    val albums: StateFlow<List<Album>?> = _albums.asStateFlow()

    /** Starea S1, fără partea „rulare” (o adaugă ecranul din progres). */
    val start: StateFlow<StartUiState> = combine(_selection, photo, docs, estimate, laptop) { s, p, d, e, l ->
        StartUiState(
            kind = s.kind,
            photoAccess = p.access,
            photoCount = p.count,
            photoBytes = p.bytes,
            scope = s.scope,
            lastN = s.lastN,
            albumName = s.albumName,
            docFolder = d.name,
            docCount = d.count,
            docBytes = d.bytes,
            docOrganized = d.organized,
            estimateSec = e,
            laptopPending = l.size
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartUiState())

    private val organizer = DocumentOrganizer(app, forja.prefs)

    init {
        // Motorul face și muncă de CPU (plan, grupare) pe firul apelantului: nimic din el pe firul principal.
        viewModelScope.launch(Dispatchers.Default) { try { Inventory.load(ctx) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
        refreshPhotoStats()
        viewModelScope.launch { loadDocTree(organizer.persistedTree()) }
        // „Ultimele N”: ultima alegere.
        viewModelScope.launch {
            val n = try { forja.prefs.inventoryLastN.first() } catch (e: CancellationException) { throw e } catch (_: Exception) { 0 }
            if (n > 0) _selection.value = _selection.value.copy(lastN = n)
        }
        // Estimarea sinceră a motorului, la fiecare schimbare de alegere (cu o mică întârziere, ca să nu alerge la fiecare
        // atingere) și când se schimbă ce e de făcut (fișiere libere după o aplicare, poze aruncate).
        viewModelScope.launch {
            combine(_selection, photo, docs) { s, p, d -> EstimateKey(s, p.access, p.count, d.tree, d.count) }
                .distinctUntilChanged()
                .collectLatest { (s, access, _, tree) ->
                    delay(220)
                    val scope = scopeOf(s, tree)
                    val sec = when {
                        s.kind == InvKind.Photos && !access -> null
                        s.kind == InvKind.Documents && tree == null -> null
                        else -> try {
                            withContext(Dispatchers.Default) { Inventory.estimateSec(ctx, s.kind, scope) }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    }
                    estimate.value = sec?.takeIf { it > 0 }
                }
        }
        // Mutările aprobate din laptop (organizarea de pe site) care așteaptă acordul Android.
        viewModelScope.launch { OrganizerLedger.changed.collect { refreshLaptop() } }
    }

    private data class EstimateKey(val s: StartSelection, val access: Boolean, val photos: Int?, val tree: Uri?, val docs: Int?)

    fun pickKind(kind: InvKind) { _selection.value = _selection.value.copy(kind = kind) }

    /**
     * „Ultimele N” din foaie: N ≥ câte poze are galeria înseamnă de fapt „Tot” (și nu se reține); altfel scopul trece
     * pe „Ultimele N” și N se ține minte pentru data viitoare.
     */
    fun pickLastN(n: Int) {
        if (n <= 0) return
        val total = photo.value.count
        if (total != null && total > 0 && n >= total) {
            _selection.value = _selection.value.copy(scope = ScopeChoice.All)
            return
        }
        val v = n.coerceIn(1, 999_999)
        _selection.value = _selection.value.copy(scope = ScopeChoice.LastN, lastN = v)
        viewModelScope.launch { try { forja.prefs.setInventoryLastN(v) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    fun pickScope(scope: ScopeChoice) {
        val s = _selection.value
        _selection.value = if (scope == ScopeChoice.Album && s.albumId == null) s else s.copy(scope = scope)
    }

    fun pickAlbum(album: Album) {
        _selection.value = _selection.value.copy(scope = ScopeChoice.Album, albumId = album.bucketId, albumName = album.name)
    }

    fun loadAlbums() {
        if (_albums.value != null) return
        viewModelScope.launch {
            _albums.value = try {
                CleanupEngine(ctx, forja.prefs).albums(includeVideos = true)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
    }

    /** Recitește accesul la galerie și cifrele (după dialogul de permisiune sau la revenirea în ecran). */
    fun refreshPhotoStats() {
        viewModelScope.launch {
            val access = InvPermissions.hasPhotoAccess(ctx)
            photo.value = photo.value.copy(access = access)
            if (!access) return@launch
            val (count, bytes) = withContext(Dispatchers.IO) { galleryStats(ctx) }
            photo.value = PhotoStats(true, count, bytes)
        }
    }

    /** Folderul ales din selectorul sistemului; `startAfter` = „Începe” a cerut folderul, deci pornim imediat. */
    fun onTreePicked(tree: Uri, startAfter: Boolean = false, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            organizer.rememberTree(tree)
            docs.value = DocStats(tree, organizer.treeName(tree), null, null)
            if (startAfter && _selection.value.kind == InvKind.Documents && start()) onStarted()
            loadDocTree(tree)
        }
    }

    private var docsAt = 0L
    private var docsJob: Job? = null

    /**
     * Numărătoarea folderului (fișiere libere + în dosare). La același folder cifrele vechi rămân pe placă până vin
     * cele noi (fără „—” la fiecare revenire în ecran).
     */
    private suspend fun loadDocTree(tree: Uri?) {
        if (tree == null) return
        val name = organizer.treeName(tree)
        if (docs.value.tree != tree) docs.value = DocStats(tree, name, null, null)
        val c = try { DocCounter.census(ctx, tree) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        docsAt = SystemClock.elapsedRealtime()
        if (docs.value.tree == tree) docs.value = DocStats(tree, name, c?.loose, c?.looseBytes, c?.organized)
    }

    /** Recitește folderul: după o aplicare ([force]) sau la revenirea în ecran (cel mult o dată la 30 s — BFS-ul costă). */
    fun refreshDocs(force: Boolean) {
        val tree = docs.value.tree ?: return
        if (!force && (docsJob?.isActive == true || SystemClock.elapsedRealtime() - docsAt < DOCS_REFRESH_MS)) return
        docsJob?.cancel()
        docsJob = viewModelScope.launch { loadDocTree(tree) }
    }

    /** Ecranul revine în față: galeria și folderul se pot fi schimbat între timp. */
    fun onResume() {
        refreshPhotoStats()
        refreshDocs(force = false)
    }

    val hasDocTree: Boolean get() = docs.value.tree != null

    // ─────────────────────────── Destinația („Locație”) ───────────────────────────

    /** Rândul ales în „Locație”. Se reține pentru rularea următoare. */
    fun setDestination(dest: InvDest) {
        when (dest) {
            is InvDest.Media -> setPhotoRoot(dest.root)
            is InvDest.Tree -> setDocsDest(dest.tree)
        }
    }

    private fun setPhotoRoot(root: String) {
        val r = MediaRoots.normalize(root) ?: return
        edit { Inventory.setDestination(InvDest.Media(r)) }
        viewModelScope.launch { try { forja.prefs.setInventoryPhotoRoot(r) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    /** „Alt dosar…” (poze): folderul ales în selector, tradus în RELATIVE_PATH. False = nu e în Pictures sau DCIM. */
    fun pickPhotoFolder(tree: Uri): Boolean {
        val root = mediaRootFromTree(tree) ?: return false
        setPhotoRoot(root)
        return true
    }

    /**
     * Documente: alt folder (`tree`, dosarele direct în el) sau `null` = „Organizate” în folderul ales. Permisiunea
     * noii destinații se păstrează (planul se poate aplica după zile); a celei vechi se eliberează, dacă nu e și sursa.
     */
    fun setDocsDest(tree: Uri?) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val source = plan.value?.source ?: docs.value.tree
                val chosen = tree?.takeUnless { source != null && TreePaths.same(it, source) }
                val old = DocDest.savedTree(ctx, forja.prefs)
                if (chosen != null) DocDest.take(ctx, chosen)
                forja.prefs.setInventoryDocsDest(chosen?.toString() ?: "")
                val savedSource = try { forja.prefs.cleanupDocsTree.first() } catch (_: Exception) { "" }
                if (old != null && (chosen == null || !TreePaths.same(old, chosen)) && (source == null || !TreePaths.same(old, source)) &&
                    old.toString() != savedSource) DocDest.release(ctx, old)
                Inventory.setDestination(InvDest.Tree(chosen, if (chosen == null) DocumentOrganizer.ROOT_FOLDER else ""))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Unde pornește selectorul „Alt folder…”: destinația curentă, altfel folderul analizat. */
    fun docsPickerStart(): Uri? {
        val p = plan.value
        val tree = (p?.dest as? InvDest.Tree)?.tree ?: p?.source ?: docs.value.tree ?: return null
        val id = TreePaths.treeDocId(tree) ?: return null
        return try { android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id) } catch (_: Exception) { null }
    }

    /** Pornește analiza în fundal. False dacă lipsește folderul (documente). */
    fun start(): Boolean {
        val s = _selection.value
        val tree = docs.value.tree
        if (s.kind == InvKind.Documents && tree == null) return false
        Inventory.start(ctx, s.kind, scopeOf(s, tree))
        return true
    }

    fun cancelRun() = Inventory.cancel(ctx)

    fun discardPlan() {
        viewModelScope.launch(Dispatchers.Default) { try { Inventory.clear(ctx) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    private fun scopeOf(s: StartSelection, tree: Uri?): InvScope = when {
        s.kind == InvKind.Documents -> InvScope(all = true, tree = tree)
        s.scope == ScopeChoice.LastN -> InvScope(all = false, lastN = s.lastN)
        s.scope == ScopeChoice.Album && s.albumId != null -> InvScope(all = false, bucketId = s.albumId)
        else -> InvScope(all = true)
    }

    // ─────────────────────────── Editări în plan ───────────────────────────

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    fun rename(folderId: String, name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) edit { Inventory.rename(folderId, n) }
    }

    fun move(ids: Collection<String>, toFolderId: String) = edit { Inventory.move(ids.toList(), toFolderId) }
    fun toTrash(ids: Collection<String>) = edit { Inventory.toTrash(ids.toList()) }
    fun keep(ids: Collection<String>) = edit { Inventory.keep(ids.toList()) }
    fun merge(fromId: String, intoId: String) = edit { Inventory.merge(fromId, intoId) }
    fun dissolve(folderId: String) = edit { Inventory.dissolve(folderId) }

    fun newFolder(name: String, ids: Collection<String>) {
        val n = name.trim().ifEmpty { "Dosar nou" }
        edit { Inventory.newFolder(n, ids.toList()) }
    }

    // ─────────────────────────── Dialogurile sistemului ───────────────────────────

    private val _sender = MutableStateFlow<IntentSender?>(null)
    /** Dialogul de sistem de lansat acum (scriere / coș); ecranul îl lansează și raportează rezultatul. */
    val sender: StateFlow<IntentSender?> = _sender.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null

    fun onSenderLaunched() { _sender.value = null }

    fun onDialogResult(ok: Boolean) {
        answer?.complete(ok)
        answer = null
    }

    private suspend fun ask(sender: IntentSender): Boolean {
        val d = CompletableDeferred<Boolean>()
        answer?.complete(false)
        answer = d
        _sender.value = sender
        return d.await()
    }

    // ─────────────────────────── Aplicarea (S6) ───────────────────────────

    private val _applyWaiting = MutableStateFlow(false)
    /** Așteptăm răspunsul la un dialog de sistem. */
    val applyWaiting: StateFlow<Boolean> = _applyWaiting.asStateFlow()

    private val _applyProgress = MutableStateFlow(0 to 0)
    val applyProgress: StateFlow<Pair<Int, Int>> = _applyProgress.asStateFlow()

    private val _outcome = MutableStateFlow<ApplyOutcome?>(null)
    val outcome: StateFlow<ApplyOutcome?> = _outcome.asStateFlow()
    fun consumeOutcome() { _outcome.value = null }

    private val _done = MutableStateFlow<DoneUiState?>(null)
    val done: StateFlow<DoneUiState?> = _done.asStateFlow()

    private var applyJob: Job? = null
    private val _applying = MutableStateFlow(false)
    /** Aplicarea rulează (dialoguri + mutări). */
    val applyingFlow: StateFlow<Boolean> = _applying.asStateFlow()
    val applying: Boolean get() = applyJob?.isActive == true

    /**
     * Bucla din motor: dialogul de scriere (dacă e), dialogul coșului (dacă e), apoi apply(); din nou, până când
     * ambele cereri întorc null. Un dialog refuzat oprește tot (nimic pierdut) și ecranul revine la dosare.
     */
    fun apply() {
        if (applying) return
        val p = plan.value ?: return
        val confirm = p.confirmUi()
        _done.value = null
        _applyProgress.value = 0 to (confirm.moves + confirm.trashCount)
        _applying.value = true
        applyJob = viewModelScope.launch {
            var total = ApplyResult(0, 0, 0, 0L)
            var landing: Landing? = null
            var rounds = 0
            try {
                while (rounds < 64) {
                    val w = Inventory.writeRequest(ctx)
                    if (w != null) {
                        _applyWaiting.value = true
                        val ok = ask(w)
                        _applyWaiting.value = false
                        if (!ok) { _outcome.value = ApplyOutcome.Cancelled; return@launch }
                    }
                    val t = Inventory.trashRequest(ctx)
                    if (t != null) {
                        _applyWaiting.value = true
                        val ok = ask(t)
                        _applyWaiting.value = false
                        if (!ok) { _outcome.value = ApplyOutcome.Cancelled; return@launch }
                    }
                    if (rounds > 0 && w == null && t == null) break
                    val r = runApply()
                    total = ApplyResult(total.moved + r.moved, total.trashed + r.trashed, total.failed + r.failed, total.freedBytes + r.freedBytes)
                    landing = landing?.merge(r.landing) ?: r.landing
                    rounds++
                    if (w == null && t == null) break
                    if (r.moved + r.trashed == 0) break
                }
                val complete = plan.value == null
                val stopped = try { Music.stopWhenDoneFlow(ctx).first() } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
                _done.value = DoneUiState(
                    kind = confirm.kind,
                    folders = confirm.folders,
                    items = total.moved + total.trashed,
                    freedBytes = if (total.freedBytes > 0) total.freedBytes else if (total.trashed > 0) confirm.trashBytes else 0L,
                    failed = total.failed,
                    musicStopped = stopped,
                    place = landing,
                    runId = p.runId,
                    showSite = contractSigned.value
                )
                // Rezultatul se publică ÎNAINTE ca „applying” să cadă: ecranul trece direct la sigilare / final.
                _outcome.value = if (complete) ApplyOutcome.Complete else ApplyOutcome.Partial
            } finally {
                _applyWaiting.value = false
                _applying.value = false
                // Cifrele din S1 după mutări: pozele aruncate au plecat din galerie, documentele stau acum în dosare.
                refreshPhotoStats()
                if (confirm.kind == InvKind.Documents) refreshDocs(force = true)
            }
        }
    }

    /** Inventory.apply rulează în scopul aplicației: dacă ecranul dispare, aplicarea bucății curente se termină oricum. */
    private suspend fun runApply(): ApplyResult {
        val job = forja.appScope.async(Dispatchers.Default) {
            Inventory.apply(ctx) { d, t -> _applyProgress.value = d to t }
        }
        return job.await()
    }

    // ─────────────────────────── Mutările aprobate din laptop ───────────────────────────

    fun setScreenVisible(on: Boolean) { OrganizerJobs.screenVisible = on }

    private fun refreshLaptop() {
        viewModelScope.launch(Dispatchers.IO) {
            laptop.value = try {
                if (forja.auth.currentUid != null) OrganizerJobs.pendingTouch(forja) else emptyList()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
    }

    /** „Permite”: acordul Android pentru pozele aprobate din laptop, apoi mutările urmărite. */
    fun allowLaptop() {
        val items = laptop.value.take(CleanupEngine.REQUEST_CHUNK)
        if (items.isEmpty() || applying) return
        viewModelScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val s = CleanupEngine(ctx, forja.prefs).writeRequest(items.map { Uri.parse(it.uri) }) ?: return@launch
                    if (!ask(s)) {
                        OrganizerJobs.declinePermission(forja, items)
                        return@launch
                    }
                }
                OrganizerJobs.applyAfterPermission(forja, items)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                refreshLaptop()
            }
        }
    }

    override fun onCleared() {
        answer?.complete(false)
        super.onCleared()
    }
}

/** Cel mult o numărătoare a folderului de documente la 30 s, la revenirea în ecran. */
private const val DOCS_REFRESH_MS = 30_000L

/** Permisiunile de galerie (aceleași reguli ca în Echipare) + locația din poze (API 29+) + notificările (33+). */
internal object InvPermissions {
    fun hasPhotoAccess(ctx: Context): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Ce cerem la „Începe” (poze): galeria dacă lipsește, locația din poze (29+), notificările (33+) — doar ce lipsește. */
    fun forPhotoStart(ctx: Context): Array<String> {
        val out = ArrayList<String>()
        if (!hasPhotoAccess(ctx)) {
            when {
                Build.VERSION.SDK_INT >= 34 -> out += listOf(
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                Build.VERSION.SDK_INT >= 33 -> out += listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else -> out += Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)) out += Manifest.permission.ACCESS_MEDIA_LOCATION
        out += forNotifications(ctx)
        return out.toTypedArray()
    }

    /** Doar accesul la galerie (atingerea plăcii POZE fără acces). */
    fun forGallery(ctx: Context): Array<String> = forPhotoStart(ctx).filter { it != Manifest.permission.POST_NOTIFICATIONS }.toTypedArray()

    fun forNotifications(ctx: Context): List<String> =
        if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
}

/** Câte poze și videoclipuri are galeria și cât ocupă (o trecere, doar coloana SIZE). */
private fun galleryStats(ctx: Context): Pair<Int, Long> {
    var count = 0
    var bytes = 0L
    val selection = if (Build.VERSION.SDK_INT >= 30) "${MediaStore.MediaColumns.IS_PENDING} = 0" else null
    val collections = listOf(
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
    for (c in collections) {
        try {
            ctx.contentResolver.query(c, arrayOf(MediaStore.MediaColumns.SIZE), selection, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    count++
                    if (!cur.isNull(0)) bytes += cur.getLong(0)
                }
            }
        } catch (_: Exception) {
        }
    }
    return count to bytes
}
