package com.forja.app.feature.cleanup

import android.Manifest
import android.app.Application
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.Album
import com.forja.app.core.cleanup.Category
import com.forja.app.core.cleanup.CleanupCursor
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.CleanupOnlineSettings
import com.forja.app.core.cleanup.CleanupReport
import com.forja.app.core.cleanup.CleanupScope
import com.forja.app.core.cleanup.DocItem
import com.forja.app.core.cleanup.DocReport
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.cleanup.MediaItem
import com.forja.app.core.cleanup.MoveOutcome
import com.forja.app.core.cleanup.OrgItem
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.core.cleanup.OrganizerLedger
import com.forja.app.core.cleanup.OrganizerSettings
import com.forja.app.core.cleanup.OrganizerStatus
import com.forja.app.core.cleanup.SiteHint
import com.forja.app.core.cleanup.ScanProgress
import com.forja.app.core.cleanup.ScopeKind
import com.forja.app.core.cleanup.fmtBytes
import com.forja.app.core.cleanup.sanitizeFolder
import com.forja.app.core.cleanup.sha256Hex
import com.forja.app.core.network.ForjaApi
import com.forja.app.core.network.OrganizeApi
import com.forja.app.core.network.OrganizeItemV2
import com.forja.app.core.network.OrganizeResponseV2
import com.forja.app.core.network.OrganizeVerdictV2
import com.forja.app.core.network.PdfSource
import com.forja.app.core.network.providerLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ═══════════════ Starea ecranului de curățenie ═══════════════

/**
 * Panoul de sugestii AI (poze sau documente). Cheia = id-ul trimis („m:<id>" / „d:<sha>").
 * `status` e linia onestă de progres („Analizez 48 din 210…", „Analizate 210 din 210 · Gemini", eroarea).
 * `done`/`total` = câte elemente din raport au trecut prin model; `failedCount` = câte n-au putut fi analizate.
 */
data class AiPanel(
    val loading: Boolean = false,
    val suggestions: Map<String, OrganizeVerdictV2> = emptyMap(),
    val summary: String = "",
    val provider: String = "",
    val requested: Boolean = false,
    val status: String = "",
    val failed: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failedCount: Int = 0
) {
    /** Mai sunt elemente neanalizate (și nu lucrăm acum) — „Reia analiza” are sens. */
    val unfinished: Boolean get() = !loading && total > 0 && done < total

    /** Dosarele propuse de model (v2 `dosar`, altfel `folder`), fără elementele recomandate la ștergere. */
    val moveFolders: Map<String, List<OrganizeVerdictV2>>
        get() = suggestions.values.filter { !it.deleteRecommended && it.targetFolder != null }
            .groupBy { sanitizeFolder(it.targetFolder ?: "") }.filterKeys { it.isNotBlank() }

    val deleteCount: Int get() = suggestions.values.count { it.deleteRecommended }
}

sealed class CleanupUiState {
    data class Choose(
        val scope: CleanupScope,
        val resume: CleanupCursor?,
        val albums: List<Album>,
        val albumsLoaded: Boolean,
        val hasPermission: Boolean
    ) : CleanupUiState()

    data class Scanning(
        val scope: CleanupScope,
        val progress: ScanProgress?,
        val paused: Boolean,
        val resume: CleanupCursor?
    ) : CleanupUiState()

    data class Results(
        val scope: CleanupScope,
        val report: CleanupReport,
        val selected: Set<Long>,
        val ai: AiPanel,
        val busy: Boolean,
        val jobId: String? = null,                    // lucrarea de pe site pornită din această scanare
        val site: Map<String, SiteHint> = emptyMap()  // uri → ce știe site-ul (copie, analiză, stare)
    ) : CleanupUiState()
}

data class DocsUiState(
    val tree: Uri? = null,
    val treeName: String = "",
    val loading: Boolean = false,
    val report: DocReport? = null,
    val selected: Set<String> = emptySet(),
    val undoCount: Int = 0,
    val ai: AiPanel = AiPanel(),
    val busy: Boolean = false,
    val jobId: String? = null,
    val site: Map<String, SiteHint> = emptyMap()
)

/** Propunerile site-ului grupate pe dosar (relativ la destinația lucrării), pentru butoanele „Mută N în …". */
fun siteFolders(site: Map<String, SiteHint>, root: String): Map<String, List<String>> =
    site.entries
        .filter { it.value.analysis != null && it.value.destination.isNotBlank() && it.value.state !in setOf("moved", "skipped") }
        .groupBy({ e -> sanitizeFolder(e.value.destination.removePrefix("$root/").removePrefix(root)) }, { it.key })
        .filterKeys { it.isNotBlank() }

class CleanupViewModel(app: Application) : AndroidViewModel(app) {
    private val forja = app as ForjaApp
    private val prefs = forja.prefs
    val engine = CleanupEngine(app, prefs)
    val organizer = DocumentOrganizer(app, prefs)
    private val api: ForjaApi = forja.forjaApi
    private val organizeApi = OrganizeApi(api)
    private val online = CleanupOnlineSettings(app)

    val aiAvailable: Boolean get() = api.available
    /** „Sugestii AI" — implicit pornit (DataStore „forja_cleanup_online", cheia `ai_on`). */
    val aiOn: StateFlow<Boolean> = online.aiOn.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ─────────────────────────── „Și pe site" (implicit pornit; se poate opri) ───────────────────────────

    val siteOn: StateFlow<Boolean> = OrganizerSettings.siteOn.also { OrganizerSettings.load(app) }
    val loggedIn: Boolean get() = forja.auth.currentUid != null
    /** v4.2: contractul de securitate semnat forțează „Și pe site” pornit; comutatorul și textele repetate dispar din ecran. */
    val contractSigned: StateFlow<Boolean> = prefs.contractSigned.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _siteStatus = MutableStateFlow<OrganizerStatus?>(null)
    /** Lucrarea curentă de pe site (linia de stare). */
    val siteStatus: StateFlow<OrganizerStatus?> = _siteStatus.asStateFlow()

    private val _pendingTouch = MutableStateFlow<List<OrgItem>>(emptyList())
    /** Poze aprobate din laptop care așteaptă acordul Android de pe acest ecran. */
    val pendingTouch: StateFlow<List<OrgItem>> = _pendingTouch.asStateFlow()

    private val _state = MutableStateFlow<CleanupUiState>(
        CleanupUiState.Choose(CleanupScope(ScopeKind.NEXT_BATCH, batchSize = 100), null, emptyList(), false, hasPhotoPermission())
    )
    val state: StateFlow<CleanupUiState> = _state.asStateFlow()

    private val _docs = MutableStateFlow(DocsUiState())
    val docs: StateFlow<DocsUiState> = _docs.asStateFlow()

    /** Mesaje scurte pentru toast. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Dialogurile de sistem (scriere/ștergere MediaStore) pe care ecranul le lansează. */
    private val _intentRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 4)
    val intentRequests: SharedFlow<IntentSender> = _intentRequests.asSharedFlow()

    private var scanJob: Job? = null
    private var pending: Pending? = null
    private var previousReport: CleanupReport? = null
    private var aiJob: Job? = null                        // analiza cu model pentru poze (se anulează la o scanare nouă / „Alt scop")
    private var docsAiJob: Job? = null                    // analiza cu model pentru documente (se anulează la recitirea folderului)
    private var scanGeneration = 0                        // crește la fiecare scanare / „Alt scop": un răspuns vechi nu atinge rezultate noi
    /**
     * Verdictele primite vreodată (id → verdict), încărcate din DataStore la pornire și scrise la fiecare lot: la o
     * redeschidere nu retrimitem miniaturi/PDF-uri deja analizate, iar etichetele sunt la loc. Se atinge doar pe Main.
     */
    private val known = LinkedHashMap<String, OrganizeVerdictV2>()
    private val knownReady = CompletableDeferred<Unit>()

    private sealed class Pending {
        data class Move(val items: List<MediaItem>, val folder: String, val rest: List<List<MediaItem>>, val movedSoFar: Int) : Pending()
        data class Delete(val items: List<MediaItem>, val rest: List<List<MediaItem>>, val freedSoFar: Long, val countSoFar: Int) : Pending()
        /** Mutări aprobate din laptop: după acordul Android le executăm și trimitem chitanțele. */
        data class SiteMove(val items: List<OrgItem>) : Pending()
    }

    init {
        viewModelScope.launch {
            try { known.putAll(online.verdictsNow()) } finally { knownReady.complete(Unit) }
        }
        viewModelScope.launch {
            val scope = engine.lastScope() ?: CleanupScope(ScopeKind.NEXT_BATCH, batchSize = 100)
            showChoose(scope)
        }
        viewModelScope.launch { loadDocsTree() }
        viewModelScope.launch { OrganizerLedger.changed.collect { refreshOrganizer() } }
        // Implicit pornit: sondarea site-ului și lucrările rămase pornesc fără să fi atins comutatorul.
        if (siteOn.value && loggedIn) {
            OrganizerJobs.schedulePolling(app)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { OrganizerJobs.resumeActive(forja) } catch (_: Exception) { } }
        }
        // Contract semnat: „Și pe site” e pornit obligatoriu (comutatorul nu mai există în ecran).
        viewModelScope.launch {
            prefs.contractSigned.collect { signed ->
                if (signed && loggedIn && !siteOn.value) {
                    OrganizerSettings.setSiteOn(app, true)
                    OrganizerJobs.schedulePolling(app)
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        try { OrganizerJobs.ensureGrant(forja); OrganizerJobs.resumeActive(forja) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                    refreshOrganizer()
                }
            }
        }
    }

    private fun toast(msg: String) { _events.tryEmit(msg) }

    // ─────────────────────────── Site: comutator, stare, sondare ───────────────────────────

    fun setSiteOn(on: Boolean) {
        if (on && !loggedIn) { toast("Conectează-te în FORJA ca să vezi curățenia și pe site."); return }
        OrganizerSettings.setSiteOn(getApplication(), on)
        if (on) {
            OrganizerJobs.schedulePolling(getApplication())
            viewModelScope.launch {
                try {
                    OrganizerJobs.ensureGrant(forja)
                    toast("Telefonul e legat de panoul online. Copiile expiră după 24 h.")
                    OrganizerJobs.resumeActive(forja)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    toast(e.message ?: "Site-ul nu răspunde acum. Reîncerc la următoarea scanare.")
                }
            }
        } else {
            OrganizerJobs.stopPolling(getApplication())
            // „Sugestii AI" e alt comutator: cât e pornit, miniaturile/PDF-urile tot pleacă la analiza cu model.
            val modelSends = aiOn.value && api.available && loggedIn
            toast(
                if (modelSends) "Oprit. Nimic nu mai urcă în cont; copiile de pe site expiră singure."
                else "Oprit. Analiza rămâne doar pe telefon; copiile de pe site expiră singure."
            )
        }
        refreshOrganizer()
    }

    /** La 15 s cât timp ecranul e vizibil: comenzi din laptop, aprobări, lucrări noi. */
    fun tickSite() {
        if (!siteOn.value || !loggedIn) return
        viewModelScope.launch {
            try { OrganizerJobs.poll(forja) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            refreshOrganizer()
        }
    }

    fun setScreenVisible(on: Boolean) {
        OrganizerJobs.screenVisible = on
        if (on) refreshOrganizer()
    }

    fun cancelSiteJob() {
        val id = _siteStatus.value?.jobId ?: return
        viewModelScope.launch {
            OrganizerJobs.cancel(forja, id)
            toast("Lucrarea de pe site e oprită. Originalele rămân neatinse.")
        }
    }

    private fun refreshOrganizer() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!loggedIn) { _siteStatus.value = null; _pendingTouch.value = emptyList(); return@launch }
            val status = try { OrganizerJobs.status(forja) } catch (_: Exception) { null }
            _siteStatus.value = status
            _pendingTouch.value = try { OrganizerJobs.pendingTouch(forja) } catch (_: Exception) { emptyList() }
            // Suntem pe IO: scrierile în stare sunt atomice (update), ca să nu pierdem o bifă dată între timp pe Main.
            val s = _state.value
            if (s is CleanupUiState.Results && s.jobId != null) {
                val hints = try { OrganizerJobs.hints(forja, s.jobId) } catch (_: Exception) { emptyMap() }
                _state.update { now -> if (now is CleanupUiState.Results && now.jobId == s.jobId) now.copy(site = hints) else now }
            }
            val d = _docs.value
            if (d.jobId != null) {
                val hints = try { OrganizerJobs.hints(forja, d.jobId) } catch (_: Exception) { emptyMap() }
                _docs.update { now -> if (now.jobId == d.jobId) now.copy(site = hints) else now }
            }
        }
    }

    /** Pozele aprobate din laptop: cerem acordul Android (dialogul de sistem), apoi mutăm și raportăm. */
    fun allowPendingMoves() {
        val items = _pendingTouch.value.take(CleanupEngine.REQUEST_CHUNK)
        if (items.isEmpty()) return
        if (pending != null) { toast("Așteaptă să se termine acțiunea curentă."); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = engine.writeRequest(items.map { Uri.parse(it.uri) }) ?: return
            pending = Pending.SiteMove(items)
            _intentRequests.tryEmit(sender)
        } else {
            viewModelScope.launch { runSiteMoves(items) }
        }
    }

    private suspend fun runSiteMoves(items: List<OrgItem>) {
        setBusy(true)
        try { OrganizerJobs.applyAfterPermission(forja, items) } finally { setBusy(false) }
        refreshOrganizer()
        val moved = items.count { OrganizerLedger.get(getApplication()).item(it.job, it.id)?.state == "moved" }
        toast(if (moved > 0) "Mutat $moved din laptop. Site-ul a primit confirmarea." else "Nicio poză nu s-a mutat; vezi lucrarea pe site.")
        removeFromReport(items.map { it.mediaId }.filter { it > 0 }.toSet())
    }

    /** „Mută N în <dosar>" din propunerile site-ului (poze). */
    fun applySiteFolder(folder: String) {
        val cur = _state.value as? CleanupUiState.Results ?: return
        val uris = siteFolders(cur.site, OrganizerJobs.PHOTO_DESTINATION)[folder] ?: return
        val items = cur.report.scanned.filter { it.uri.toString() in uris }
        moveItems(items, folder)
    }

    fun applyDocsSiteFolder(folder: String) {
        val d = _docs.value
        val report = d.report ?: return
        val uris = siteFolders(d.site, OrganizerJobs.DOC_DESTINATION)[folder] ?: return
        moveDocs(report.items.filter { it.key in uris }, folder)
    }

    // ─────────────────────────── Permisiuni ───────────────────────────

    /**
     * Pe 33+ cerem și READ_MEDIA_VIDEO (același grup „Poze și videoclipuri" — un singur dialog), altfel
     * „Include videoclipurile" n-ar întoarce niciun videoclip; pe 34+ și „Selectează poze" e acces valid.
     */
    fun photoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Pe Android 14+ „Selectează poze" e tot acces: oricare dintre cele două permisiuni ajunge. */
    fun hasPhotoPermission(): Boolean {
        val ctx = getApplication<Application>()
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun onPermissionResult() {
        val s = _state.value
        val scope = when (s) {
            is CleanupUiState.Choose -> s.scope
            is CleanupUiState.Scanning -> s.scope
            is CleanupUiState.Results -> s.scope
        }
        if (s is CleanupUiState.Choose) viewModelScope.launch { showChoose(scope) }
    }

    // ─────────────────────────── Alegerea scopului ───────────────────────────

    private suspend fun showChoose(scope: CleanupScope) {
        val perm = hasPhotoPermission()
        _state.value = CleanupUiState.Choose(scope, if (perm) engine.resumePoint(scope) else null, emptyList(), false, perm)
        if (!perm) return
        val albums = try { engine.albums(scope.includeVideos) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val cur = _state.value
        if (cur is CleanupUiState.Choose && cur.scope == scope) _state.value = cur.copy(albums = albums, albumsLoaded = true)
    }

    fun setScope(scope: CleanupScope) {
        val cur = _state.value as? CleanupUiState.Choose ?: return
        _state.value = cur.copy(scope = scope, resume = null)
        viewModelScope.launch {
            engine.rememberScope(scope)
            val resume = if (cur.hasPermission) engine.resumePoint(scope) else null
            val now = _state.value
            if (now is CleanupUiState.Choose && now.scope == scope) _state.value = now.copy(resume = resume)
        }
    }

    fun resetProgress() {
        val cur = _state.value as? CleanupUiState.Choose ?: return
        viewModelScope.launch {
            engine.resetCursor(cur.scope)
            val now = _state.value
            if (now is CleanupUiState.Choose) _state.value = now.copy(resume = null)
            toast("Am uitat progresul. O luăm de la început.")
        }
    }

    fun backToChoose() {
        scanJob?.cancel()
        scanJob = null
        aiJob?.cancel()
        aiJob = null
        scanGeneration++
        previousReport = null
        val s = _state.value
        val scope = when (s) {
            is CleanupUiState.Choose -> s.scope
            is CleanupUiState.Scanning -> s.scope
            is CleanupUiState.Results -> s.scope
        }
        viewModelScope.launch { showChoose(scope) }
    }

    // ─────────────────────────── Scanarea ───────────────────────────

    fun startScan(fromStart: Boolean = false) {
        val s = _state.value
        val scope = when (s) {
            is CleanupUiState.Choose -> s.scope
            is CleanupUiState.Scanning -> s.scope
            is CleanupUiState.Results -> s.scope
        }
        if (!hasPhotoPermission()) { toast("Dă întâi accesul la galerie."); return }
        if (s is CleanupUiState.Results) previousReport = s.report
        if (s is CleanupUiState.Choose) previousReport = null
        launchScan(scope, resume = !fromStart)
    }

    private fun launchScan(scope: CleanupScope, resume: Boolean) {
        scanJob?.cancel()
        // O analiză cu model încă pe drum ar răspunde pentru un raport care nu mai există: o oprim.
        aiJob?.cancel()
        aiJob = null
        scanGeneration++
        _state.value = CleanupUiState.Scanning(scope, null, paused = false, resume = null)
        scanJob = viewModelScope.launch {
            try {
                engine.scan(scope, resume).collect { p ->
                    when (p) {
                        is ScanProgress.Done -> {
                            val merged = p.report.mergedWith(previousReport)
                            previousReport = null
                            val preselect = HashSet<Long>()
                            merged.duplicates.forEach { g -> g.copies.forEach { preselect += it.id } }
                            merged.similar.forEach { g -> g.others.forEach { preselect += it.id } }
                            // Verdictele memorate (DataStore) pentru pozele din raport sunt la loc imediat; la analiză pleacă doar restul.
                            knownReady.await()
                            val remembered = HashMap<String, OrganizeVerdictV2>()
                            for (item in merged.scanned) known["m:${item.id}"]?.let { remembered[it.id] = it }
                            remembered.values.filter { it.deleteRecommended }.forEach { v -> v.id.removePrefix("m:").toLongOrNull()?.let { preselect += it } }
                            val ai = AiPanel(
                                suggestions = remembered, requested = remembered.isNotEmpty(),
                                done = remembered.size, total = merged.scanned.size
                            )
                            _state.value = CleanupUiState.Results(scope, merged, preselect, ai, busy = false)
                            // „Și pe site" (implicit pornit): scanarea devine o lucrare în cont (inventar + verdicte + copii 24 h).
                            if (siteOn.value && loggedIn) {
                                OrganizerJobs.schedulePolling(getApplication())
                                val jobId = try {
                                    OrganizerJobs.startFromPhotos(forja, scope, p.report, onlineAi = online.aiOnNow() && api.available)
                                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                                val now = _state.value
                                if (jobId != null && now is CleanupUiState.Results && now.report === merged) _state.value = now.copy(jobId = jobId)
                                refreshOrganizer()
                            }
                            // Analiza cu model pornește singură, pe TOT ce s-a scanat (doar sugestii; nimic nu se șterge).
                            startPhotoAnalysis()
                        }
                        else -> {
                            val cur = _state.value
                            if (cur is CleanupUiState.Scanning) _state.value = cur.copy(progress = p)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Scanarea s-a oprit: ${e.javaClass.simpleName}. Mai încearcă.")
                showChoose(scope)
            }
        }
    }

    fun pause() {
        val cur = _state.value as? CleanupUiState.Scanning ?: return
        val job = scanJob
        scanJob = null
        _state.value = cur.copy(paused = true)
        viewModelScope.launch {
            // Așteptăm ca motorul să-și scrie cursorul (NonCancellable) înainte să-l citim, altfel afișăm un număr vechi.
            job?.cancelAndJoin()
            val resume = engine.resumePoint(cur.scope)
            val now = _state.value
            if (now is CleanupUiState.Scanning && now.paused) _state.value = now.copy(resume = resume)
        }
    }

    fun resumeScan() {
        val cur = _state.value as? CleanupUiState.Scanning ?: return
        if (!cur.paused) return
        launchScan(cur.scope, resume = true)
    }

    fun nextBatch() {
        val cur = _state.value as? CleanupUiState.Results ?: return
        previousReport = cur.report
        launchScan(cur.scope, resume = true)
    }

    override fun onCleared() {
        scanJob?.cancel()
        aiJob?.cancel()
        docsAiJob?.cancel()
        super.onCleared()
    }

    // ─────────────────────────── Selecție ───────────────────────────

    fun toggleSelect(id: Long) {
        val cur = _state.value as? CleanupUiState.Results ?: return
        _state.value = cur.copy(selected = if (id in cur.selected) cur.selected - id else cur.selected + id)
    }

    fun selectAll(ids: Collection<Long>, on: Boolean) {
        val cur = _state.value as? CleanupUiState.Results ?: return
        _state.value = cur.copy(selected = if (on) cur.selected + ids else cur.selected - ids.toSet())
    }

    /** Elementele unei categorii (fără păstrate). */
    fun categoryItems(report: CleanupReport, category: Category): List<MediaItem> = when (category) {
        Category.DUPLICATE -> report.duplicates.flatMap { it.copies }
        Category.SIMILAR -> report.similar.flatMap { it.others }
        Category.SCREENSHOT -> report.screenshots
        Category.BLURRY -> report.blurry.map { it.item }
        Category.TINY -> report.tiny
        Category.LARGE -> report.large
    }

    /** Selecția din categorie; dacă nu e nimic selectat acolo, toată categoria. */
    fun actionTargets(category: Category): List<MediaItem> {
        val cur = _state.value as? CleanupUiState.Results ?: return emptyList()
        val all = categoryItems(cur.report, category)
        val sel = all.filter { it.id in cur.selected }
        return if (sel.isNotEmpty()) sel else all
    }

    fun selectedItems(): List<MediaItem> {
        val cur = _state.value as? CleanupUiState.Results ?: return emptyList()
        return (cur.report.flaggedItems + aiDeleteExtras(cur)).filter { it.id in cur.selected }
    }

    private fun removeFromReport(ids: Set<Long>) {
        val cur = _state.value as? CleanupUiState.Results ?: return
        _state.value = cur.copy(report = cur.report.without(ids), selected = cur.selected - ids)
    }

    private fun setBusy(b: Boolean) {
        val cur = _state.value as? CleanupUiState.Results ?: return
        _state.value = cur.copy(busy = b)
    }

    // ─────────────────────────── Mutare în dosare ───────────────────────────

    fun moveCategory(category: Category) = moveItems(actionTargets(category), category.folder)

    fun moveItems(items: List<MediaItem>, folder: String) {
        if (items.isEmpty()) { toast("Nimic de mutat."); return }
        if (!engine.canMove) { toast("Mutarea în dosare cere Android 10 sau mai nou. Poți doar șterge."); return }
        if (pending != null) { toast("Așteaptă să se termine acțiunea curentă."); return }
        val chunks = items.chunked(CleanupEngine.REQUEST_CHUNK)
        launchMove(chunks.first(), chunks.drop(1), sanitizeFolder(folder), 0)
    }

    private fun launchMove(chunk: List<MediaItem>, rest: List<List<MediaItem>>, folder: String, movedSoFar: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = engine.writeRequest(chunk.map { it.uri }) ?: return
            pending = Pending.Move(chunk, folder, rest, movedSoFar)
            _intentRequests.tryEmit(sender)
        } else {
            viewModelScope.launch { performMove(chunk, folder, rest, movedSoFar) }
        }
    }

    private suspend fun performMove(chunk: List<MediaItem>, folder: String, rest: List<List<MediaItem>>, movedSoFar: Int) {
        setBusy(true)
        // Lucrarea de pe site (dacă există) primește aprobarea + intenția înainte, chitanțele după.
        val jobId = (_state.value as? CleanupUiState.Results)?.jobId
        val tracked = if (jobId != null) {
            try { OrganizerJobs.beforeUserMove(forja, jobId, chunk.map { it.uri.toString() }, folder) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
        } else emptyMap()
        val result = try { engine.moveToFolder(chunk, folder) } finally { setBusy(false) }
        if (jobId != null && tracked.isNotEmpty()) {
            // Cele mutate primesc chitanța „moved"; cele oprite de dialogul Android (API 29) rămân „applying" până la reluare.
            val pendingRetry = if (result.recoverable != null) result.remaining.map { it.uri.toString() }.toSet() else emptySet()
            val outcomes = HashMap<String, String?>()
            for (uri in tracked.keys) {
                val movedNow = chunk.any { it.uri.toString() == uri && it.id in result.movedIds }
                if (movedNow) outcomes[uri] = uri else if (uri !in pendingRetry) outcomes[uri] = null
            }
            try { OrganizerJobs.afterUserMove(forja, jobId, tracked, outcomes) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        removeFromReport(result.movedIds)
        val moved = movedSoFar + result.moved
        val recoverable = result.recoverable
        if (recoverable != null && result.remaining.isNotEmpty()) {
            // API 29: sistemul cere acordul; după el reluăm exact de la elementul oprit.
            pending = Pending.Move(result.remaining, folder, rest, moved)
            _intentRequests.tryEmit(recoverable)
            return
        }
        if (rest.isNotEmpty()) { launchMove(rest.first(), rest.drop(1), folder, moved); return }
        pending = null
        when {
            moved > 0 -> toast("Mutat $moved în FORJA Curățenie/$folder.")
            result.errors.isNotEmpty() -> toast(result.errors.first())
            else -> toast("Nimic nu s-a mutat.")
        }
    }

    // ─────────────────────────── Ștergere ───────────────────────────

    fun deleteCategory(category: Category) = deleteItems(actionTargets(category))
    fun deleteSelected() = deleteItems(selectedItems())

    fun deleteItems(items: List<MediaItem>) {
        if (items.isEmpty()) { toast("Nimic de șters."); return }
        if (pending != null) { toast("Așteaptă să se termine acțiunea curentă."); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val chunks = items.chunked(CleanupEngine.REQUEST_CHUNK)
            launchDelete(chunks.first(), chunks.drop(1), 0L, 0)
        } else {
            viewModelScope.launch {
                setBusy(true)
                val n = try { engine.deleteLegacy(items.map { it.uri }) } finally { setBusy(false) }
                val freed = items.sumOf { it.sizeBytes }
                removeFromReport(items.map { it.id }.toSet())
                toast(if (n > 0) "Curat. Ai eliberat ${fmtBytes(freed)}." else "Nu s-a putut șterge.")
            }
        }
    }

    private fun launchDelete(chunk: List<MediaItem>, rest: List<List<MediaItem>>, freedSoFar: Long, countSoFar: Int) {
        val sender = engine.deleteRequest(chunk.map { it.uri }) ?: return
        pending = Pending.Delete(chunk, rest, freedSoFar, countSoFar)
        _intentRequests.tryEmit(sender)
    }

    /** Rezultatul dialogului de sistem (scriere/ștergere). */
    fun onIntentResult(ok: Boolean) {
        val p = pending ?: return
        pending = null
        when (p) {
            is Pending.Delete -> {
                if (!ok) { toast("Nu s-a șters nimic."); return }
                val freed = p.freedSoFar + p.items.sumOf { it.sizeBytes }
                val count = p.countSoFar + p.items.size
                removeFromReport(p.items.map { it.id }.toSet())
                if (p.rest.isNotEmpty()) launchDelete(p.rest.first(), p.rest.drop(1), freed, count)
                else toast("Curat. Ai eliberat ${fmtBytes(freed)}. Telefon mai ușor.")
            }
            is Pending.Move -> {
                if (!ok) {
                    toast("Mutarea a fost refuzată.")
                    // Reluare refuzată (API 29): intenția a plecat deja pe site — elementele devin de verificat acolo.
                    val jobId = (_state.value as? CleanupUiState.Results)?.jobId
                    if (jobId != null) viewModelScope.launch {
                        try { OrganizerJobs.reportUnmoved(forja, jobId, p.items.map { it.uri.toString() }, "Ai refuzat acordul Android pentru această mutare.") }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                    return
                }
                viewModelScope.launch { performMove(p.items, p.folder, p.rest, p.movedSoFar) }
            }
            is Pending.SiteMove -> {
                if (!ok) {
                    toast("Mutarea din laptop a fost refuzată. Site-ul află că trebuie verificată.")
                    viewModelScope.launch { OrganizerJobs.declinePermission(forja, p.items); refreshOrganizer() }
                    return
                }
                viewModelScope.launch { runSiteMoves(p.items) }
            }
        }
    }

    // ─────────────────────────── Sugestii AI (poze) ───────────────────────────

    fun setAiOn(on: Boolean) {
        if (!on) {
            // Oprit: cererile în curs se opresc; ce a venit rămâne, restul nu mai pleacă.
            aiJob?.cancel(); aiJob = null
            docsAiJob?.cancel(); docsAiJob = null
            _state.update { s -> (s as? CleanupUiState.Results)?.let { it.copy(ai = it.ai.copy(loading = false, status = "Sugestii AI oprite: rămân verdictele telefonului.")) } ?: s }
            _docs.update { d -> d.copy(ai = d.ai.copy(loading = false, status = "Sugestii AI oprite: rămân verdictele telefonului.")) }
        }
        viewModelScope.launch {
            online.setAiOn(on)
            // Pornit din nou: ce e neanalizat pleacă acum, fără altă apăsare.
            if (on) { startPhotoAnalysis(); startDocsAnalysis() }
        }
    }

    /** De ce nu poate pleca analiza pe server acum — sau null când poate. `subject` = „pozele" / „fișierele". */
    private fun aiBlocker(on: Boolean, subject: String): String? = when {
        !api.available -> "Serverul FORJA nu e configurat în această versiune; rămân verdictele telefonului."
        !on -> "Sugestii AI oprite: rămân verdictele telefonului."
        !loggedIn -> "Analiza rămâne locală: intră în cont ca modelul să vadă $subject."
        else -> null
    }

    // Liniile de progres vin din corutinele analizei (pe Main), iar scrierea e atomică (update):
    // o bifă dată exact atunci nu se pierde.
    private fun setAiStatus(status: String, failed: Boolean = false) {
        _state.update { s -> (s as? CleanupUiState.Results)?.let { it.copy(ai = it.ai.copy(status = status, failed = failed)) } ?: s }
    }

    private fun setDocsAiStatus(status: String, failed: Boolean = false) {
        _docs.update { d -> d.copy(ai = d.ai.copy(status = status, failed = failed)) }
    }

    private fun hintsFor(report: CleanupReport): Map<Long, MutableList<String>> {
        val hints = HashMap<Long, MutableList<String>>()
        fun add(id: Long, h: String) { hints.getOrPut(id) { ArrayList() } += h }
        report.duplicates.forEach { g -> g.copies.forEach { add(it.id, "duplicate_of:m:${g.keeper.id}") } }
        report.similar.forEach { g -> g.others.forEach { add(it.id, "similar_to:m:${g.keeper.id}") } }
        report.screenshots.forEach { add(it.id, "screenshot") }
        report.blurry.forEach { add(it.item.id, "blurry:${it.score.toInt()}") }
        report.tiny.forEach { add(it.id, "tiny") }
        report.large.forEach { add(it.id, "large") }
        return hints
    }

    /** Ce a ieșit dintr-o rundă de loturi: câte elemente au trecut, câte n-au putut, ultimul motiv, modelul. */
    private class RunOutcome(val done: Int, val failed: Int, val error: String?, val provider: String, val summary: String)

    /**
     * Motorul comun al analizei automate: loturile pleacă cu cel mult [MAX_IN_FLIGHT] cereri în paralel, un lot picat se
     * reîncearcă o dată, apoi se numără (eroarea se raportează o singură dată, la final — nu per element).
     * `build` pregătește sarcina unui lot chiar înainte de trimitere (miniaturile/PDF-urile nu stau toate în memorie);
     * `onBatch` primește verdictele fiecărui lot, pe Main, ca să apară pe măsură ce vin. `alive` oprește totul când
     * raportul pentru care lucrăm nu mai există.
     */
    private suspend fun <T> runBatches(
        batches: List<List<T>>,
        alive: () -> Boolean,
        build: suspend (List<T>) -> List<OrganizeItemV2>,
        onBatch: suspend (sent: List<OrganizeItemV2>, response: OrganizeResponseV2, doneSoFar: Int) -> Unit
    ): RunOutcome {
        var done = 0
        var failed = 0
        var error: String? = null
        var provider = ""
        var summary = ""
        coroutineScope {
            val gate = Semaphore(MAX_IN_FLIGHT)
            batches.map { batch ->
                async {
                    gate.withPermit {
                        if (!alive()) return@withPermit
                        val payload = build(batch)
                        if (!alive() || payload.isEmpty()) return@withPermit
                        var r = organizeApi.organize(payload)
                        if (r is OrganizeApi.Result.Fail && alive()) {
                            delay(RETRY_DELAY_MS)
                            r = organizeApi.organize(payload)
                        }
                        if (!alive()) return@withPermit
                        when (r) {
                            is OrganizeApi.Result.Ok -> {
                                done += payload.size
                                if (provider.isBlank()) provider = providerLabel(r.response.provider, r.response.model)
                                if (summary.isBlank()) summary = r.response.summary
                                onBatch(payload, r.response, done)
                            }
                            is OrganizeApi.Result.Fail -> { failed += payload.size; error = r.message }
                        }
                    }
                }
            }.awaitAll()
        }
        return RunOutcome(done, failed, error, provider, summary)
    }

    /** Verdictele unui lot + elementele trimise fără verdict întors (memorate goale: nu se retrimit). */
    private fun verdictsOf(sent: List<OrganizeItemV2>, response: OrganizeResponseV2): Map<String, OrganizeVerdictV2> {
        val map = LinkedHashMap<String, OrganizeVerdictV2>()
        response.items.forEach { map[it.id] = it }
        sent.forEach { if (it.id !in map) map[it.id] = OrganizeVerdictV2(id = it.id) }
        return map
    }

    private fun progressLine(done: Int, total: Int, what: String) = "Analizez $done din $total $what…"

    private fun finalLine(o: RunOutcome, total: Int, what: String, deletes: Int): String = buildString {
        val done = total - o.failed
        append("Analizate $done din $total $what")
        if (o.provider.isNotBlank()) append(" · ").append(o.provider)
        if (deletes > 0) append(" · $deletes ${if (deletes == 1) "bifată de aruncat" else "bifate de aruncat"}")
        if (o.failed > 0) {
            append(". ${o.failed} ${if (o.failed == 1) "nu a putut fi analizată" else "nu au putut fi analizate"}")
            o.error?.let { append(": ").append(it.trimEnd('.')) }
            append(".")
        }
    }

    /**
     * Analiza cu model pentru TOATE pozele scanate, pornită singură după scanare (și la „Reia analiza”). Pleacă doar
     * cele fără verdict memorat, în loturi de ≤ 24 miniaturi (≤ 120 KB JPEG → ≤ 160 KB base64), cel mult două cereri
     * deodată. Verdictele se adună pe măsură ce vin; cele de ștergere DOAR pre-bifează — ștergerea trece prin dialogul
     * de sistem. Un răspuns venit după o scanare nouă sau după „Alt scop" nu mai atinge starea (`scanGeneration`).
     */
    fun startPhotoAnalysis() {
        val cur = _state.value as? CleanupUiState.Results ?: return
        if (aiJob?.isActive == true) return
        val generation = scanGeneration
        val report = cur.report
        aiJob = viewModelScope.launch {
            knownReady.await()
            val blocker = aiBlocker(online.aiOnNow(), "pozele")
            if (scanGeneration != generation) return@launch
            val total = report.scanned.size
            if (blocker != null) { setAiStatus(blocker); return@launch }
            val todo = report.scanned.filter { "m:${it.id}" !in known }
            val doneAlready = total - todo.size
            if (todo.isEmpty()) {
                _state.update { s -> if (s is CleanupUiState.Results) s.copy(ai = s.ai.copy(loading = false, failed = false, done = total, total = total,
                    status = if (total == 0) "Nimic de analizat." else "Analizate $total din $total ${if (total == 1) "poză" else "poze"}.")) else s }
                return@launch
            }
            _state.update { s ->
                if (s is CleanupUiState.Results) s.copy(ai = s.ai.copy(loading = true, failed = false, failedCount = 0, done = doneAlready, total = total, status = progressLine(doneAlready, total, "poze"))) else s
            }
            val hints = hintsFor(report)
            val alive = { scanGeneration == generation && _state.value is CleanupUiState.Results }
            val outcome = runBatches(
                batches = todo.chunked(OrganizeApi.MAX_IMAGES),
                alive = alive,
                build = { batch ->
                    batch.map { item ->
                        val thumb = if (item.isVideo) null else engine.thumbnailJpeg(item.uri)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                        OrganizeItemV2(
                            id = "m:${item.id}", kind = "image", name = item.name, size = item.sizeBytes, mime = item.mime,
                            width = item.width.takeIf { it > 0 }, height = item.height.takeIf { it > 0 },
                            bucket = item.bucketName.ifBlank { null }, takenAt = item.bestTimeMs.takeIf { it > 0 },
                            thumbnail = thumb, localHints = hints[item.id] ?: emptyList()
                        )
                    }
                },
                onBatch = { sent, response, doneNow ->
                    val map = verdictsOf(sent, response)
                    known.putAll(map)
                    // Recomandările de ștergere DOAR pre-selectează; nimic nu se șterge fără dialogul de sistem.
                    val preselect = map.values.filter { it.deleteRecommended }.mapNotNull { it.id.removePrefix("m:").toLongOrNull() }
                    _state.update { s ->
                        if (s is CleanupUiState.Results) s.copy(
                            selected = s.selected + preselect,
                            ai = s.ai.copy(
                                suggestions = s.ai.suggestions + map, requested = true, done = doneAlready + doneNow,
                                provider = providerLabel(response.provider, response.model),
                                summary = response.summary.ifBlank { s.ai.summary },
                                status = progressLine(doneAlready + doneNow, total, "poze")
                            )
                        ) else s
                    }
                    online.rememberVerdicts(map)
                }
            )
            if (!alive()) return@launch
            _state.update { s ->
                if (s is CleanupUiState.Results) {
                    val deletes = s.ai.deleteCount
                    val allFailed = outcome.done == 0 && outcome.failed > 0
                    s.copy(ai = s.ai.copy(
                        loading = false, failed = outcome.failed > 0, failedCount = outcome.failed,
                        done = total - outcome.failed, total = total,
                        provider = outcome.provider.ifBlank { s.ai.provider }, summary = outcome.summary.ifBlank { s.ai.summary },
                        status = if (allFailed) (outcome.error ?: "Serverul nu răspunde; sugestiile locale rămân.") else finalLine(outcome, total, "poze", deletes)
                    ))
                } else s
            }
        }
    }

    /** „Reia analiza”: pozele fără verdict (inclusiv cele din loturile picate) pleacă din nou. */
    fun retryAi() {
        val cur = _state.value as? CleanupUiState.Results ?: return
        if (cur.ai.loading) return
        startPhotoAnalysis()
    }

    /** Pozele scanate fără nicio categorie locală, dar pe care modelul le recomandă la ștergere — secțiunea „AI: de aruncat”. */
    fun aiDeleteExtras(state: CleanupUiState.Results): List<MediaItem> {
        val flagged = state.report.flaggedItems.map { it.id }.toHashSet()
        return state.report.scanned.filter { it.id !in flagged && state.ai.suggestions["m:${it.id}"]?.deleteRecommended == true }
    }

    /** „Mută N în <dosar>" din panoul AI. */
    fun applyAiFolder(folder: String) {
        val cur = _state.value as? CleanupUiState.Results ?: return
        val ids = cur.ai.moveFolders[folder]?.mapNotNull { it.id.removePrefix("m:").toLongOrNull() }?.toSet() ?: return
        val items = cur.report.scanned.filter { it.id in ids }
        moveItems(items, folder)
    }

    // ─────────────────────────── Documente ───────────────────────────

    /** Folderul reamintit la pornire: îl recitim; la analiză pleacă doar fișierele fără verdict memorat (PDF-urile nu urcă de două ori). */
    private suspend fun loadDocsTree() {
        val tree = organizer.persistedTree() ?: return
        scanDocs(tree)
    }

    fun onTreePicked(tree: Uri) {
        viewModelScope.launch {
            organizer.rememberTree(tree)
            scanDocs(tree)
        }
    }

    /** „rescanează": recitire cerută de tine — la analiză pleacă doar fișierele fără verdict. */
    fun rescanDocs() {
        val tree = _docs.value.tree ?: return
        viewModelScope.launch { scanDocs(tree) }
    }

    /**
     * Recitirea folderului. Verdictele memorate (DataStore) pentru fișierele încă prezente sunt la loc imediat, apoi
     * analiza cu model pornește singură pentru restul — la alegerea folderului, la rescanare și la pornirea aplicației,
     * pentru că nimic analizat nu se retrimite.
     */
    private suspend fun scanDocs(tree: Uri) {
        docsAiJob?.cancel()
        docsAiJob = null
        _docs.value = _docs.value.copy(tree = tree, treeName = organizer.treeName(tree), loading = true, selected = emptySet(), ai = AiPanel(), jobId = null, site = emptyMap())
        try {
            val (items, warnings) = organizer.inventory(tree)
            val report = organizer.detect(items)
            val undo = organizer.undoJournal().size
            knownReady.await()
            val byAiId = report.items.associateBy { docAiId(it) }
            val kept = HashMap<String, OrganizeVerdictV2>()
            for (id in byAiId.keys) known[id]?.let { kept[id] = it }
            val ai = AiPanel(suggestions = kept, requested = kept.isNotEmpty(), done = kept.size, total = report.items.size)
            val preselect = report.duplicates.flatMap { g -> g.copies.map { it.key } }.toSet() +
                kept.values.filter { it.deleteRecommended }.mapNotNull { byAiId[it.id]?.key }
            _docs.value = _docs.value.copy(loading = false, report = report.copy(warnings = report.warnings + warnings), selected = preselect, undoCount = undo, ai = ai)
            if (siteOn.value && loggedIn && report.items.isNotEmpty()) {
                OrganizerJobs.schedulePolling(getApplication())
                val jobId = try {
                    OrganizerJobs.startFromDocs(forja, tree, organizer.treeName(tree), report, onlineAi = online.aiOnNow() && api.available)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                val now = _docs.value
                if (jobId != null && now.tree == tree) _docs.value = now.copy(jobId = jobId)
                refreshOrganizer()
            }
            if (report.items.isNotEmpty()) startDocsAnalysis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _docs.value = _docs.value.copy(loading = false, report = null)
            toast("Nu am putut citi folderul. Alege-l din nou.")
        }
    }

    fun toggleDoc(key: String) {
        val d = _docs.value
        _docs.value = d.copy(selected = if (key in d.selected) d.selected - key else d.selected + key)
    }

    fun selectDocs(keys: Collection<String>, on: Boolean) {
        val d = _docs.value
        _docs.value = d.copy(selected = if (on) d.selected + keys else d.selected - keys.toSet())
    }

    /** Selecția din listă; dacă nu e nimic selectat acolo, toată lista. */
    fun docTargets(all: List<DocItem>): List<DocItem> {
        val sel = all.filter { it.key in _docs.value.selected }
        return if (sel.isNotEmpty()) sel else all
    }

    fun moveDocs(items: List<DocItem>, category: String) {
        val d = _docs.value
        val tree = d.tree ?: return
        if (items.isEmpty()) { toast("Nimic de mutat."); return }
        if (d.busy) return
        viewModelScope.launch {
            _docs.value = _docs.value.copy(busy = true)
            var moved = 0; var copied = 0; var failed: String? = null
            val gone = HashSet<String>()
            val jobId = d.jobId
            val tracked = if (jobId != null) {
                try { OrganizerJobs.beforeUserMove(forja, jobId, items.map { it.key }, sanitizeFolder(category)) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
            } else emptyMap()
            val outcomes = HashMap<String, String?>()
            val copiedKeys = HashSet<String>()
            try {
                for (item in items) {
                    when (val out = organizer.move(tree, item, category)) {
                        is MoveOutcome.Moved -> { moved++; gone += item.key; outcomes[item.key] = out.newUri.toString() }
                        is MoveOutcome.Copied -> {
                            copied++; if (!out.originalKept) gone += item.key
                            outcomes[item.key] = out.newUri.toString()
                            if (out.originalKept) copiedKeys += item.key   // copie verificată, originalul rămâne → copied_pending_removal
                        }
                        is MoveOutcome.Failed -> { failed = failed ?: out.reason; outcomes[item.key] = null }
                    }
                }
            } finally {
                if (jobId != null && tracked.isNotEmpty()) {
                    try { OrganizerJobs.afterUserMove(forja, jobId, tracked, outcomes, copiedKeys) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                val now = _docs.value
                _docs.value = now.copy(
                    busy = false,
                    report = now.report?.without(gone),
                    selected = now.selected - gone,
                    undoCount = organizer.undoJournal().size
                )
            }
            val f = failed
            toast(
                when {
                    moved + copied > 0 && copied > 0 -> "Mutat $moved, copiat $copied în ${DocumentOrganizer.ROOT_FOLDER}/${sanitizeFolder(category)} (originalele rămân până le ștergi)."
                    moved > 0 -> "Mutat $moved în ${DocumentOrganizer.ROOT_FOLDER}/${sanitizeFolder(category)}."
                    f != null -> f
                    else -> "Nimic nu s-a mutat."
                }
            )
        }
    }

    fun deleteDocs(items: List<DocItem>) {
        val d = _docs.value
        if (items.isEmpty()) { toast("Nimic de șters."); return }
        if (d.busy) return
        viewModelScope.launch {
            _docs.value = _docs.value.copy(busy = true)
            var freed = 0L
            val gone = HashSet<String>()
            try {
                for (item in items) if (organizer.delete(item)) { freed += item.sizeBytes; gone += item.key }
            } finally {
                val now = _docs.value
                _docs.value = now.copy(busy = false, report = now.report?.without(gone), selected = now.selected - gone)
            }
            toast(if (gone.isNotEmpty()) "Curat. Ai eliberat ${fmtBytes(freed)}." else "Nu s-a putut șterge.")
        }
    }

    fun undoLastDocMove() {
        if (_docs.value.busy) return
        viewModelScope.launch {
            _docs.value = _docs.value.copy(busy = true)
            val (ok, msg) = try { organizer.undoLast() } finally { _docs.value = _docs.value.copy(busy = false) }
            toast(msg)
            val tree = _docs.value.tree
            // Recitim lista, dar fără să re-trimitem folderul la analiză: fișierul adus înapoi nu are verdict, restul îl păstrează.
            if (ok && tree != null) scanDocs(tree)
            else _docs.value = _docs.value.copy(undoCount = organizer.undoJournal().size)
        }
    }

    /**
     * Analiza cu model pentru TOATE fișierele din folder, pornită singură după inventar. PDF-urile pleacă întregi
     * (≤ 4 MB, ≤ 6 pe lot, citite și codificate abia la scrierea cererii — unul o dată în memorie), fișierele text cu
     * un fragment, restul doar cu nume + metadate; cel mult două cereri deodată. Pleacă doar fișierele fără verdict
     * memorat. Un răspuns venit după schimbarea folderului nu atinge starea.
     */
    fun startDocsAnalysis() {
        val d = _docs.value
        val report = d.report ?: return
        val tree = d.tree ?: return
        if (docsAiJob?.isActive == true) return
        docsAiJob = viewModelScope.launch {
            knownReady.await()
            val blocker = aiBlocker(online.aiOnNow(), "fișierele")
            if (_docs.value.tree != tree) return@launch
            val total = report.items.size
            if (blocker != null) { setDocsAiStatus(blocker); return@launch }
            val todo = report.items.filter { docAiId(it) !in known }
            val doneAlready = total - todo.size
            if (todo.isEmpty()) {
                _docs.update { s -> if (s.tree == tree) s.copy(ai = s.ai.copy(loading = false, failed = false, done = total, total = total,
                    status = if (total == 0) "Nimic de analizat." else "Analizate $total din $total ${if (total == 1) "fișier" else "fișiere"}.")) else s }
                return@launch
            }
            _docs.update { s -> if (s.tree == tree) s.copy(ai = s.ai.copy(loading = true, failed = false, failedCount = 0, done = doneAlready, total = total, status = progressLine(doneAlready, total, "fișiere"))) else s }
            val dupKeys = report.duplicates.flatMap { g -> g.copies.map { it.key } }.toHashSet()
            val byAiId = todo.associateBy { docAiId(it) }
            // Sarcina e ieftină de construit (PDF-ul se citește la trimitere), deci împărțim în loturi de la început.
            val payload = todo.map { item ->
                val hints = ArrayList<String>()
                if (item.key in dupKeys) hints += "duplicate"
                if (item.sizeBytes > DocumentOrganizer.LARGE_BYTES) hints += "large"
                if (item.lastModified > 0 && System.currentTimeMillis() - item.lastModified > DocumentOrganizer.OLD_MS) hints += "old"
                val sendPdf = organizer.pdfSendable(item, OrganizeApi.MAX_PDF_BYTES)
                if (!sendPdf && organizer.isPdf(item)) hints += if (item.sizeBytes > OrganizeApi.MAX_PDF_BYTES) "pdf_too_large" else "pdf_not_sent"
                OrganizeItemV2(
                    id = docAiId(item), kind = "document", name = item.name, size = item.sizeBytes,
                    mime = item.mime.ifBlank { if (organizer.isPdf(item)) "application/pdf" else "application/octet-stream" },
                    bucket = item.path.substringBeforeLast('/', "").ifBlank { null },
                    takenAt = item.lastModified.takeIf { it > 0 },
                    text = if (sendPdf) null else organizer.textSnippet(item),
                    localHints = hints,
                    pdfSource = if (sendPdf) PdfSource(item.sizeBytes) { organizer.readPdf(item, OrganizeApi.MAX_PDF_BYTES) } else null
                )
            }
            val alive = { _docs.value.tree == tree }
            val outcome = runBatches(
                batches = organizeApi.batches(payload),
                alive = alive,
                build = { it },
                onBatch = { sent, response, doneNow ->
                    val map = verdictsOf(sent, response)
                    known.putAll(map)
                    val preselect = map.values.filter { it.deleteRecommended }.mapNotNull { byAiId[it.id]?.key }
                    _docs.update { s ->
                        if (s.tree == tree) s.copy(
                            selected = s.selected + preselect,
                            ai = s.ai.copy(
                                suggestions = s.ai.suggestions + map, requested = true, done = doneAlready + doneNow,
                                provider = providerLabel(response.provider, response.model),
                                summary = response.summary.ifBlank { s.ai.summary },
                                status = progressLine(doneAlready + doneNow, total, "fișiere")
                            )
                        ) else s
                    }
                    online.rememberVerdicts(map)
                }
            )
            if (!alive()) return@launch
            _docs.update { s ->
                if (s.tree == tree) {
                    val deletes = s.ai.deleteCount
                    val allFailed = outcome.done == 0 && outcome.failed > 0
                    s.copy(ai = s.ai.copy(
                        loading = false, failed = outcome.failed > 0, failedCount = outcome.failed,
                        done = total - outcome.failed, total = total,
                        provider = outcome.provider.ifBlank { s.ai.provider }, summary = outcome.summary.ifBlank { s.ai.summary },
                        status = if (allFailed) (outcome.error ?: "Serverul nu răspunde; sugestiile locale rămân.") else finalLine(outcome, total, "fișiere", deletes)
                    ))
                } else s
            }
        }
    }

    /** „Reia analiza”: fișierele fără verdict (inclusiv cele din loturile picate) pleacă din nou. */
    fun retryDocsAi() {
        if (_docs.value.ai.loading) return
        startDocsAnalysis()
    }

    /** Fișierele fără categorie locală pe care modelul le recomandă la ștergere — secțiunea „AI: de aruncat”. */
    fun aiDocDeleteExtras(docs: DocsUiState): List<DocItem> {
        val report = docs.report ?: return emptyList()
        val flagged = HashSet<String>()
        report.duplicates.forEach { g -> g.copies.forEach { flagged += it.key } }
        report.large.forEach { flagged += it.key }
        report.old.forEach { flagged += it.key }
        report.suspects.forEach { flagged += it.first.key }
        return report.items.filter { it.key !in flagged && docs.ai.suggestions[docAiId(it)]?.deleteRecommended == true }
    }

    fun docAiId(item: DocItem): String = "d:" + sha256Hex(item.uri.toString()).take(24)

    fun applyDocsAiFolder(folder: String) {
        val d = _docs.value
        val report = d.report ?: return
        val ids = d.ai.moveFolders[folder]?.map { it.id }?.toSet() ?: return
        val items = report.items.filter { docAiId(it) in ids }
        moveDocs(items, folder)
    }

    /** Sugestia AI pentru un element (poză sau document). */
    fun suggestionFor(ai: AiPanel, id: String): OrganizeVerdictV2? = ai.suggestions[id]

    companion object {
        /** Cereri /v1/organize în paralel (loturi de ≤ 24 miniaturi sau ≤ 6 PDF-uri). */
        const val MAX_IN_FLIGHT = 2
        /** Pauza înaintea singurei reîncercări a unui lot picat. */
        const val RETRY_DELAY_MS = 1_500L
    }
}
