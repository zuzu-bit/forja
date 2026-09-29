package com.forja.app.core.inventory

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.DocumentOrganizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.random.Random

// ═══════════════ Inventar 4.3 — motorul (pachetul B): API-ul folosit de UI (DESIGN-4.3 §2, EXACT) ═══════════════
// Galeria (sau un folder de documente) → dosare cu nume după conținut + „De aruncat” editabil. Analiza rulează în
// fundal (WorkManager, serviciu în prim-plan dataSync); ecranul doar o urmărește prin [Inventory.progress].

enum class InvKind { Photos, Documents }

enum class InvStage { Scanning, Grouping, Naming, Ready, Applying, Done, Failed }

/** O „cutie” de pe banda de sortare. `name == null` = încă nebotezată. */
data class BinTick(val name: String?, val count: Int)

/**
 * Starea rulării. În timpul analizei (Scanning → Grouping → Naming → Ready) `done / total` e progresul GLOBAL al
 * tuturor stagiilor, în unități de lucru (`total` = 1000), deci procentul crește monoton și nu o ia de la 0 la fiecare
 * stagiu; `stage` spune doar care iconiță pulsează. La Applying/Done, `done / total` = elemente aplicate / de aplicat.
 * `recent` = ultimele miniaturi atinse (≤ 8), `bins` = 3–5 cutii (cele mai mari grupuri/dosare), `etaSec` = estimare.
 */
data class InvProgress(val runId: String, val kind: InvKind, val stage: InvStage, val done: Int, val total: Int,
                       val etaSec: Int?, val recent: List<android.net.Uri>, val bins: List<BinTick>, val error: String? = null)

enum class DeleteReason { Duplicate, Similar, Blurry, Tiny, OldScreenshot, Accidental, AiSuggested, TempFile }

/** Un element al planului. `reason` e setat doar în „De aruncat” (null = pus acolo de utilizator). */
data class InvItem(val id: String, val uri: android.net.Uri, val kind: InvKind, val takenAt: Long, val bytes: Long, val width: Int,
                   val height: Int, val mime: String, val name: String, val reason: DeleteReason? = null)

/** `cover` = ≤ 4 id-uri răspândite în timp, pentru colajul 2×2. `special` = „De aruncat”. */
data class InvFolder(val id: String, val name: String, val theme: String, val itemIds: List<String>, val cover: List<String>,
                     val special: Boolean = false)

/**
 * Unde ajung lucrurile la aplicare (ales de om în „Locație”, reținut pentru rularea următoare).
 * Poze: [Media.root] = rădăcina RELATIVE_PATH („Pictures/FORJA/”, „Pictures/”, „DCIM/FORJA/”, „Pictures/Vacanțe/”).
 * Documente: [Tree.tree] = folderul destinație (null = folderul ales la început) și [Tree.sub] = subdosarul din el
 * („Organizate” implicit; "" când omul a ales alt folder, iar dosarele stau direct acolo).
 */
sealed interface InvDest {
    data class Media(val root: String) : InvDest
    data class Tree(val tree: android.net.Uri?, val sub: String) : InvDest

    companion object {
        fun default(kind: InvKind): InvDest =
            if (kind == InvKind.Photos) Media(MediaRoots.DEFAULT) else Tree(null, DocumentOrganizer.ROOT_FOLDER)
    }
}

/** `source` = folderul analizat (documente); `dest` = destinația curentă a planului. */
data class InvPlan(val runId: String, val kind: InvKind, val createdAt: Long, val items: Map<String, InvItem>,
                   val folders: List<InvFolder>, val trash: InvFolder, val provider: String?,
                   val dest: InvDest = InvDest.default(kind), val source: android.net.Uri? = null)

/**
 * Scopul: `tree` (documente); pentru poze `bucketId` (un album) are prioritate față de `lastN` („Ultimele N”, cele
 * mai noi N după ordinea adăugării în galerie; orice N > 0, ales de om), iar fără ele se ia toată galeria (`all`).
 */
data class InvScope(val all: Boolean = true, val lastN: Int? = null, val bucketId: Long? = null, val tree: android.net.Uri? = null)

sealed interface InvEvent { data class Done(val runId: String, val folders: Int, val trashBytes: Long) : InvEvent; data class Failed(val message: String) : InvEvent }

/**
 * Un loc de pe telefon: [folder] = dosarul ca document SAF (ExternalStorage „primary:Pictures/FORJA” la poze, URI de
 * arbore la documente), [label] = calea lizibilă („Documents/Organizate”), [storagePath] = calea pe disc, când e locală.
 */
data class InvPlace(val folder: android.net.Uri?, val label: String, val storagePath: String? = null)

/**
 * Unde au ajuns lucrurile după aplicare: [root] = rădăcina destinației, [single] = dosarul, când s-a atins unul singur
 * ([segments] = dosarele atinse), [first] = primul element mutat (poză înainte de video; MediaStore păstrează _ID-ul
 * după mutare), [bucketId] = albumul lui după mutare (poze, un singur dosar). Rundele se unesc cu [merge].
 */
data class Landing(
    val kind: InvKind,
    val root: InvPlace,
    val single: InvPlace? = null,
    val segments: Set<String> = emptySet(),
    val first: android.net.Uri? = null,
    val firstMime: String? = null,
    val bucketId: Long? = null
) {
    /** Ce se deschide: dosarul unic, altfel rădăcina (care arată toate dosarele noi). */
    val target: InvPlace get() = if (segments.size == 1 && single != null) single else root

    fun merge(next: Landing?): Landing {
        if (next == null) return this
        val all = segments + next.segments
        val one = all.size == 1
        return Landing(
            kind = kind,
            root = next.root,
            single = if (one) single ?: next.single else null,
            segments = all,
            first = first ?: next.first,
            firstMime = if (first != null) firstMime else next.firstMime,
            bucketId = if (one) bucketId ?: next.bucketId else null
        )
    }
}

/**
 * O rundă de aplicare. [failed] = eșecurile rundei (unele se reîncearcă în runda următoare); [lost] = cele ieșite din
 * plan fără să ajungă la loc în runda asta, [pending] = cele rămase în plan după ea (de reîncercat). Nemutatele unei
 * aplicări cu mai multe runde = suma lui [lost] + [pending] din ultima rundă (ca `AppliedRec.failed` de pe site).
 * [failures] (4.4.2) = motivul fiecărui eșec al rundei, pe id-ul elementului (poze; documentele n-au motive).
 */
data class ApplyResult(
    val moved: Int,
    val trashed: Int,
    val failed: Int,
    val freedBytes: Long,
    val landing: Landing? = null,
    val lost: Int = 0,
    val pending: Int = 0,
    val failures: Map<String, MoveFail> = emptyMap()
)

/**
 * Mutările rămase din plan ([pending]) și câte dintre ele stau în dosarul altei aplicații, pe pachet ([owners],
 * „com.whatsapp” → 6): pe acestea Android le mută doar cu „Acces la toate fișierele”.
 */
data class PendingOwners(val pending: Int = 0, val owners: Map<String, Int> = emptyMap()) {
    val owned: Int get() = owners.values.sum()
}

/** Ce cere coșul pentru bucata curentă din „De aruncat” ([Inventory.trashRequest]). */
sealed interface TrashAsk {
    /** Dialogul sistemului, pentru pozele din bucată care nu sunt încă la coș. */
    class Dialog(val sender: IntentSender) : TrashAsk
    /** Toată bucata e deja la coș (acordul dat într-o aplicare întreruptă): fără dialog, dar [Inventory.apply] o numără. */
    data object AlreadyTrashed : TrashAsk
    /** Nimic de cerut (nimic în „De aruncat”, sub API 30, documente) sau cererea n-a putut fi făcută. */
    data object None : TrashAsk
}

/** Eșec explicat utilizatorului (fără permisiune, galerie goală, folder inaccesibil). */
internal class InvFailure(message: String) : Exception(message)

/**
 * Inventarul. Fluxul pentru UI:
 * 1. [estimateSec] sub „Începe”; [start] pornește lucrătorul unic „inventory” (o pornire nouă o înlocuiește pe cea veche);
 * 2. [progress] se actualizează din lucrător (același proces); după o repornire a aplicației, [load] reface starea de pe
 *    disc și, dacă analiza nu s-a terminat, repune lucrătorul la coadă (KEEP);
 * 3. la Ready: [plan] + [events] `Done`, notificarea „Dosarele sunt gata · N dosare”; editările ([rename], [move],
 *    [toTrash], [keep], [newFolder], [merge], [dissolve]) se salvează imediat și actualizează [plan];
 * 4. aplicarea pozelor (API 30+): lansează [writeRequest] și [trashRequest] (dialogurile sistemului), apoi [apply].
 *    Cererile sunt în bucăți de ≤ 500 de elemente (limita Binder/MediaStore, ca CleanupEngine.REQUEST_CHUNK): după
 *    [apply], dacă planul mai are elemente, [writeRequest]/[trashRequest] întorc următoarea bucată — se repetă până
 *    nu mai cer nimic (null / [TrashAsk.None]; planul se golește și dispare singur când totul e aplicat). O bucată a
 *    coșului aflată deja toată la coș ([TrashAsk.AlreadyTrashed]) nu cere dialog, dar runda tot rulează [apply].
 *    Oprește bucla când utilizatorul anulează un dialog (bucata refuzată rămâne în plan și ar reveni). Fără dialog
 *    acceptat, [apply] nu mută nimic pe API 30+. Sub API 30 nu se cere nimic și [apply] lucrează direct (API 29: se
 *    mută doar ce aparține FORJA; sub 29 mutarea nu e posibilă; „De aruncat” se șterge direct, fără coș).
 *    Documentele nu au dialoguri: [apply] mută prin SAF în „<destinație>/<dosar>” (implicit „Organizate/<dosar>”)
 *    și „De aruncat (FORJA)”. Destinația se schimbă înainte de aplicare cu [setDestination]; fiecare [apply]
 *    întoarce în [ApplyResult.landing] locul unde au ajuns lucrurile, iar rezumatul rulării urcă pe site
 *    (users/{uid}/inventory/{runId}, doar cu contractul semnat, ultimele 20);
 * 5. [clear] uită tot (plan, rulare, fișiere).
 */
object Inventory {
    private val _progress = MutableStateFlow<InvProgress?>(null)
    private val _plan = MutableStateFlow<InvPlan?>(null)
    private val _events = MutableSharedFlow<InvEvent>(replay = 0, extraBufferCapacity = 16)

    val progress: StateFlow<InvProgress?> = _progress.asStateFlow()
    val plan: StateFlow<InvPlan?> = _plan.asStateFlow()
    val events: SharedFlow<InvEvent> = _events.asSharedFlow()

    private const val SCALE = InvRules.PROGRESS_SCALE
    private val RUNNING = setOf(InvStage.Scanning, InvStage.Grouping, InvStage.Naming)

    /** Planul gata, în memorie: documentul editabil + elementele + obiectele publice deja construite. */
    private class Snapshot(val meta: RunMeta, val doc: PlanDoc, val items: Map<String, ItemRec>, val base: Map<String, InvItem>, val plan: InvPlan)

    /** Bucata expusă în ultimul dialog de sistem (id-uri), ca [apply] să lucreze exact pe ce a acceptat utilizatorul. */
    private class Grant(val runId: String, val ids: List<String>)

    private val lock = Mutex()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null
    @Volatile private var currentRunId: String? = null
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var activeWorker: String? = null
    @Volatile private var moveGrant: Grant? = null
    @Volatile private var trashGrant: Grant? = null

    private fun bind(context: Context): Context = (context.applicationContext ?: context).also { appContext = it }

    // ─────────────────────────── Pornire / oprire ───────────────────────────

    fun start(context: android.content.Context, kind: InvKind, scope: InvScope): String {
        val ctx = bind(context)
        val runId = "r" + java.lang.Long.toString(System.currentTimeMillis(), 36) + Random.nextInt(46_656).toString(36).padStart(3, '0')
        val tree = if (kind == InvKind.Documents) scope.tree else null
        if (tree != null) {
            // Permisiunea persistentă: lucrătorul poate porni după ce ecranul (și permisiunea temporară) au dispărut.
            try {
                ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        val meta = RunMeta(
            runId = runId,
            kind = kind,
            createdAt = System.currentTimeMillis(),
            lastN = if (kind == InvKind.Photos && scope.bucketId == null) scope.lastN?.takeIf { it > 0 } else null,
            bucketId = if (kind == InvKind.Photos) scope.bucketId else null,
            tree = tree?.toString()
        )
        currentRunId = runId
        snapshot = null
        moveGrant = null
        trashGrant = null
        _plan.value = null
        _progress.value = InvProgress(runId, kind, InvStage.Scanning, 0, SCALE, null, emptyList(), emptyList())
        InventoryNotify.cancelResult(ctx)
        io.launch {
            lock.withLock {
                if (currentRunId != runId) return@withLock
                InventoryStore.deleteAllExcept(ctx, runId)
                InventoryStore.setLatest(ctx, runId)
                if (tree != null) try { ForjaApp.from(ctx).prefs.setCleanupDocsTree(tree.toString()) } catch (_: Exception) { }
            }
        }
        InventoryWorker.enqueue(ctx, meta, replace = true)
        return runId
    }

    /** Oprește analiza în curs și îi șterge urmele. Un plan deja gata rămâne (îl șterge [clear]). */
    fun cancel(context: android.content.Context) {
        val ctx = bind(context)
        val runId = currentRunId
        val ready = snapshot?.takeIf { it.meta.runId == runId }
        InventoryWorker.cancelWork(ctx)
        InventoryNotify.cancelProgress(ctx)
        if (ready != null) return
        currentRunId = null
        _progress.value = null
        io.launch {
            lock.withLock {
                val id = runId ?: InventoryStore.latest(ctx) ?: return@withLock
                if (id == currentRunId || snapshot?.meta?.runId == id) return@withLock
                val stage = InventoryStore.readRun(ctx, id)?.meta?.stage
                if (stage == null || stage in RUNNING || stage == InvStage.Failed) {
                    InvMirror.discard(ctx, id)
                    InventoryStore.deleteRun(ctx, id)
                    if (InventoryStore.latest(ctx) == id) InventoryStore.setLatest(ctx, null)
                }
            }
        }
    }

    /**
     * Reface starea de pe disc (după o repornire): planul gata (și [plan]), sau progresul unei analize neterminate
     * (și repune lucrătorul la coadă, KEEP), sau eroarea ultimei rulări. Întoarce planul, dacă există.
     */
    suspend fun load(context: android.content.Context): InvPlan? {
        val ctx = bind(context)
        var resume: RunMeta? = null
        val result = lock.withLock {
            val runId = currentRunId ?: InventoryStore.latest(ctx) ?: return@withLock null
            snapshot?.let { if (it.meta.runId == runId) return@withLock it.plan }
            val run = withContext(Dispatchers.IO) { InventoryStore.readRun(ctx, runId) }
            if (run == null) {
                // Rularea abia pornită nu are încă fișier (îl scrie lucrătorul): starea din memorie e cea bună.
                if (currentRunId != runId) InventoryStore.setLatest(ctx, null)
                return@withLock null
            }
            currentRunId = runId
            val meta = run.meta
            when (meta.stage) {
                InvStage.Ready, InvStage.Applying -> {
                    val doc = run.plan?.let { d -> if (d.dest == null) d.copy(dest = defaultDest(ctx, meta.kind, meta.tree)) else d }
                    val items = withContext(Dispatchers.IO) { InventoryStore.readItems(ctx, runId) }
                    if (doc == null || items == null) {
                        _progress.value = InvProgress(runId, meta.kind, InvStage.Failed, 0, 1, null, emptyList(), emptyList(),
                            "Planul nu s-a putut citi. Pornește din nou.")
                        null
                    } else {
                        val map = HashMap<String, ItemRec>(items.size * 2)
                        items.forEach { map[it.id] = it }
                        val snap = snapshotOf(meta.copy(stage = InvStage.Ready), doc, map, baseOf(map))
                        snapshot = snap
                        _plan.value = snap.plan
                        _progress.value = readyProgress(snap)
                        snap.plan
                    }
                }
                InvStage.Scanning, InvStage.Grouping, InvStage.Naming -> {
                    if (activeWorker != runId) {
                        _progress.value = InvProgress(runId, meta.kind, meta.stage, meta.done.coerceIn(0, meta.total), meta.total.coerceAtLeast(1),
                            null, emptyList(), emptyList())
                        resume = meta
                    }
                    null
                }
                InvStage.Failed -> {
                    _progress.value = InvProgress(runId, meta.kind, InvStage.Failed, meta.done.coerceIn(0, meta.total), meta.total.coerceAtLeast(1),
                        null, emptyList(), emptyList(), meta.error ?: "Nu a mers. Încearcă din nou.")
                    null
                }
                InvStage.Done -> {
                    withContext(Dispatchers.IO) { InventoryStore.deleteRun(ctx, runId) }
                    InventoryStore.setLatest(ctx, null)
                    currentRunId = null
                    null
                }
            }
        }
        resume?.let { InventoryWorker.enqueue(ctx, it, replace = false) }
        return result
    }

    /**
     * Estimarea sinceră din §2: scanare ≈ 800 elemente/min + AI ≈ ceil(grupuri / 4) apeluri × 12 s / 2 în paralel.
     * Grupurile se numără din metadate (evenimente la pauze de 4 h, cele mici pe lună, primite pe lună); fără AI
     * (neconectat / oprit) rămâne doar scanarea. Documente: loturi de ≤ 24 de fișiere (≤ 6 PDF) în loc de grupuri.
     */
    suspend fun estimateSec(context: android.content.Context, kind: InvKind, scope: InvScope): Int {
        val ctx = bind(context)
        val app = ForjaApp.from(ctx)
        return try {
            val ai = AiGate.likely(app)
            when (kind) {
                InvKind.Photos -> {
                    if (!MediaAccess.canRead(ctx)) return 0
                    val lastN = if (scope.bucketId == null) scope.lastN?.takeIf { it > 0 } else null
                    val items = MediaQuery.query(ctx, scope.bucketId, lastN)
                    val groups = if (ai) InvAssemble.provisional(items, System.currentTimeMillis(), ZoneId.systemDefault()).count { it.needsAi } else 0
                    InvRules.estimateSec(items.size, InvRules.photoCalls(groups), ai)
                }
                InvKind.Documents -> {
                    val organizer = DocumentOrganizer(ctx, app.prefs)
                    val tree = scope.tree ?: organizer.persistedTree() ?: return 0
                    val (docs, _) = organizer.inventory(tree, skipDirIds = DocDest.skips(tree, DocDest.savedTree(ctx, app.prefs)))
                    val recs = docs.filter { it.isLoose() }.map { d ->
                        ItemRec(id = d.key, uri = d.uri.toString(), kind = InvKind.Documents, takenAt = d.lastModified,
                            bytes = d.sizeBytes, mime = d.mime, name = d.name)
                    }
                    InvRules.estimateSec(recs.size, InvAssemble.docBatches(recs).size, ai)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    // ─────────────────────────── Editări (se salvează imediat) ───────────────────────────

    suspend fun rename(folderId: String, name: String) {
        edit(Unit) { d, _ -> PlanEdits.rename(d, folderId, name) to Unit }
    }

    suspend fun move(itemIds: List<String>, toFolderId: String) {
        edit(Unit) { d, items -> PlanEdits.move(d, items, itemIds, toFolderId) to Unit }
    }

    suspend fun toTrash(itemIds: List<String>) {
        edit(Unit) { d, items -> PlanEdits.toTrash(d, items, itemIds) to Unit }
    }

    /** Scoate din „De aruncat”, înapoi în dosarul de origine (urmând unirile, reînviat dacă se golise) sau „Diverse”. */
    suspend fun keep(itemIds: List<String>) {
        edit(Unit) { d, items -> PlanEdits.keep(d, items, itemIds) to Unit }
    }

    /** Dosar nou (rămâne și gol, până la [dissolve]); întoarce id-ul lui, sau "" când nu există plan. */
    suspend fun newFolder(name: String, itemIds: List<String>): String =
        edit("") { d, items -> PlanEdits.newFolder(d, items, name, itemIds) }

    suspend fun merge(fromId: String, intoId: String) {
        edit(Unit) { d, items -> PlanEdits.merge(d, items, fromId, intoId) to Unit }
    }

    /** Elementele merg în „Diverse · <an>” (după anul fiecăruia). */
    suspend fun dissolve(folderId: String) {
        edit(Unit) { d, items -> PlanEdits.dissolve(d, items, folderId, ZoneId.systemDefault()) to Unit }
    }

    /**
     * Schimbă destinația planului (foaia „Locație”). Poze: o rădăcină acceptată de MediaStore (altfel nu se schimbă
     * nimic). Documente: alt folder (dosarele stau direct în el) sau `tree = null` („În folderul ales/Organizate”).
     * Uită acordul de scriere deja primit: o bucată acordată ar fi mutată în vechiul loc. Elementele mutate într-o
     * rundă anterioară au ieșit deja din plan — schimbarea privește doar ce a rămas.
     */
    suspend fun setDestination(dest: InvDest) {
        lock.withLock {
            val s = snapshot ?: return@withLock
            val ctx = appContext ?: return@withLock
            val rec = when (dest) {
                is InvDest.Media -> {
                    if (s.meta.kind != InvKind.Photos) return@withLock
                    // Orice dosar (4.4.2): cel din afara Pictures/DCIM se aplică doar cu acces complet (îl cere confirmarea).
                    DestRec(mediaRoot = MediaRoots.normalize(dest.root, anyTop = true) ?: return@withLock)
                }
                is InvDest.Tree -> {
                    if (s.meta.kind != InvKind.Documents) return@withLock
                    val source = s.meta.tree?.let { Uri.parse(it) }
                    val tree = dest.tree
                    if (tree == null || (source != null && TreePaths.same(tree, source))) DestRec()
                    else DestRec(tree = tree.toString(), sub = dest.sub.trim('/'))
                }
            }
            if (rec == s.doc.dest) return@withLock
            moveGrant = null
            commit(ctx, s, s.doc.copy(dest = rec))
        }
    }

    private suspend fun <T> edit(fallback: T, block: (PlanDoc, Map<String, ItemRec>) -> Pair<PlanDoc, T>): T = lock.withLock {
        val s = snapshot ?: return@withLock fallback
        val ctx = appContext ?: return@withLock fallback
        val (doc, out) = block(s.doc, s.items)
        if (doc !== s.doc) commit(ctx, s, doc)
        out
    }

    // ─────────────────────────── Aplicare ───────────────────────────

    /**
     * Acordul de scriere pentru mutări (API 30+, poze): prima bucată de ≤ 500 dintre elementele încă nemutate; null
     * când nu mai e nimic de mutat, sub API 30 sau la documente. Vezi fluxul din KDoc-ul obiectului. [skip] = elementele
     * care au eșuat deja în aplicarea asta dintr-un motiv pe care un acord nou nu-l schimbă (dosarul altei aplicații,
     * dosar nepermis, alt volum): nu se mai cer încă o dată, în runda următoare (4.4.1 le cerea din nou, degeaba).
     */
    fun writeRequest(context: android.content.Context, skip: Set<String> = emptySet()): android.content.IntentSender? {
        if (Build.VERSION.SDK_INT < 30) return null
        val ctx = bind(context)
        val s = snapshot ?: return null
        if (s.meta.kind != InvKind.Photos) return null
        val chunk = InventoryApply.pendingMoves(s.doc, s.items).let { all -> if (skip.isEmpty()) all else all.filter { it.id !in skip } }
            .take(CleanupEngine.REQUEST_CHUNK)
        if (chunk.isEmpty()) { moveGrant = null; return null }
        return try {
            CleanupEngine(ctx, ForjaApp.from(ctx).prefs).writeRequest(chunk.map { Uri.parse(it.uri) })
                ?.also { moveGrant = Grant(s.meta.runId, chunk.map { it.id }) }
        } catch (e: Exception) {
            // Fără dialog, apply() nu mută nimic: măcar jurnalul spune de ce (URI respins, limita MediaStore…).
            ConsentLog.add(ctx, "W_ASK", com.forja.app.core.music.DiagResult.ERROR, 0, "n=${chunk.size} ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Coșul sistemului pentru „De aruncat” (API 30+, poze; recuperabil 30 de zile): prima bucată de ≤ 500. Pozele deja
     * aflate în coș (de exemplu acordul dat într-o aplicare întreruptă) nu mai apar în dialog, dar rămân în bucată:
     * [apply] le verifică și le numără aruncate. Toată bucata deja în coș → [TrashAsk.AlreadyTrashed], fără dialog.
     *
     * Suspendă: întrebarea „ce e deja la coș” merge pe IO, nu pe firul principal, de unde o cheamă bucla aplicării
     * (înaintea dialogului de scriere) și refacerea cererii (la revenirea ecranului, după un rezultat pierdut).
     */
    suspend fun trashRequest(context: android.content.Context): TrashAsk {
        if (Build.VERSION.SDK_INT < 30) return TrashAsk.None
        val ctx = bind(context)
        val s = snapshot ?: return TrashAsk.None
        if (s.meta.kind != InvKind.Photos) return TrashAsk.None
        val chunk = InventoryApply.pendingTrash(s.doc, s.items).take(CleanupEngine.REQUEST_CHUNK)
        if (chunk.isEmpty()) { trashGrant = null; return TrashAsk.None }
        val trashed = withContext(Dispatchers.IO) { MediaMover.trashedIds(ctx, chunk.map { it.mediaId }) }
        val grant = Grant(s.meta.runId, chunk.map { it.id })
        val ask = InventoryApply.stillToTrash(chunk, trashed)
        if (ask.size < chunk.size) {
            ConsentLog.add(ctx, "T_SKIP", com.forja.app.core.music.DiagResult.OK, 0, "n=${chunk.size - ask.size}/${chunk.size}")
        }
        if (ask.isEmpty()) {
            trashGrant = grant
            return TrashAsk.AlreadyTrashed
        }
        // Același apel ca CleanupEngine.trashRequest (API 30+ și o listă nevidă sunt verificate mai sus).
        val sender = try {
            MediaStore.createTrashRequest(ctx.contentResolver, ask.map { Uri.parse(it.uri) }, true).intentSender
        } catch (e: Exception) {
            ConsentLog.add(ctx, "T_ASK", com.forja.app.core.music.DiagResult.ERROR, 0, "n=${ask.size} ${e.javaClass.simpleName}")
            null
        }
        trashGrant = if (sender != null) grant else null
        return if (sender != null) TrashAsk.Dialog(sender) else TrashAsk.None
    }

    /**
     * După dialoguri: mută (RELATIVE_PATH „<rădăcina aleasă>/<dosar>/”, implicit „Pictures/FORJA/<dosar>/”), verifică
     * coșul, raportează progresul (pe contextul apelantului), scoate din plan ce s-a aplicat și emite Done/Failed. Când
     * planul rămâne gol, dispare (fișiere + pointer) și [progress] trece în Done. După fiecare rundă, rezumatul
     * rulării (totalurile adunate peste runde) urcă pe site, dacă e semnat contractul.
     * [manager] (4.4.2) = „Acces la toate fișierele” e dat: pozele se aplică toate, fără acordurile din dialoguri
     * (mutările rămase + „De aruncat” prin IS_TRASHED = 1). Documentele nu depind de el.
     */
    suspend fun apply(context: android.content.Context, manager: Boolean = false, onProgress: (Int, Int) -> Unit): ApplyResult {
        val ctx = bind(context)
        if (snapshot == null) load(ctx)
        var event: InvEvent? = null
        var summary: InvSummaryDoc? = null
        val result = lock.withLock {
            val s = snapshot ?: return@withLock ApplyResult(0, 0, 0, 0L)
            val runId = s.meta.runId
            val moves = moveGrant?.takeIf { it.runId == runId }?.ids
            val trash = trashGrant?.takeIf { it.runId == runId }?.ids
            moveGrant = null
            trashGrant = null
            withContext(Dispatchers.IO) { InventoryStore.writeRun(ctx, RunFile(s.meta.copy(stage = InvStage.Applying), s.doc)) }
            val t0 = SystemClock.elapsedRealtime()
            val recent = ArrayDeque<Uri>()
            var lastEmit = 0L
            val step: suspend (Int, Int, Uri?) -> Unit = { d, t, uri ->
                if (uri != null) { recent.addLast(uri); while (recent.size > 8) recent.removeFirst() }
                val now = SystemClock.elapsedRealtime()
                if (d == 0 || d >= t || now - lastEmit >= 150) {
                    lastEmit = now
                    val elapsed = now - t0
                    val eta = if (d >= 5 && elapsed > 1_000) ((t - d) * elapsed / d / 1000).toInt() else null
                    _progress.value = InvProgress(runId, s.meta.kind, InvStage.Applying, d, t.coerceAtLeast(1), eta, recent.toList(), emptyList())
                }
                onProgress(d, t)
            }
            val out = try {
                if (s.meta.kind == InvKind.Photos) InventoryApply.photos(ctx, s.doc, s.items, moves, trash, step, manager = manager)
                else InventoryApply.documents(ctx, s.doc, s.items, s.meta.tree, step)
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { InventoryStore.writeRun(ctx, RunFile(s.meta, s.doc)) }
                _progress.value = readyProgress(s)
                throw e
            }
            val applied = tally(s, out)
            val r = out.result.copy(lost = applied.lost - (s.meta.applied?.lost ?: 0), pending = applied.pending)
            val doc = PlanEdits.without(s.doc, s.items, out.removed)
            if (applied.moved + applied.trashed + applied.failed > 0) summary = summaryOf(s, applied).copy(
                state = if (PlanEdits.count(doc) == 0) "done" else "ready",
                failures = out.result.failures.values.groupingBy { it.reason.code }.eachCount())
            if (PlanEdits.count(doc) == 0) {
                withContext(Dispatchers.IO) { InventoryStore.deleteRun(ctx, runId) }
                InventoryStore.setLatest(ctx, null)
                snapshot = null
                _plan.value = null
                val n = r.moved + r.trashed
                _progress.value = InvProgress(runId, s.meta.kind, InvStage.Done, n, n.coerceAtLeast(1), 0, recent.toList(), emptyList())
            } else {
                _progress.value = readyProgress(commit(ctx, Snapshot(s.meta.copy(applied = applied), s.doc, s.items, s.base, s.plan), doc))
            }
            event = if (r.moved + r.trashed > 0 || r.failed == 0) InvEvent.Done(runId, out.folders, r.freedBytes)
            else InvEvent.Failed("Nu am putut aplica nimic. Verifică permisiunile și încearcă din nou.")
            r
        }
        event?.let { _events.tryEmit(it) }
        summary?.let { doc -> io.launch { InventorySummary.publish(ctx, doc) } }
        return result
    }

    /**
     * Mutările rămase ale planului de poze curent și proprietarii lor (Android/media/<pachet>/, alții decât FORJA), pentru
     * rândul „Acces complet” din confirmare și pentru numele aplicației din pagina de rezultat. Doar citire, CPU.
     */
    fun pendingOwners(context: android.content.Context): PendingOwners {
        val s = snapshot ?: return PendingOwners()
        if (s.meta.kind != InvKind.Photos) return PendingOwners()
        val self = (context.applicationContext ?: context).packageName
        val pending = InventoryApply.pendingMoves(s.doc, s.items)
        return PendingOwners(pending.size, pending.mapNotNull { MediaOwners.foreignOwner(it.relPath, self) }.groupingBy { it }.eachCount())
    }

    /** Totalurile rulării după încă o rundă (dosare după nume, eșecurile fără dublări). */
    private fun tally(s: Snapshot, out: InventoryApply.Outcome): AppliedRec {
        val prev = s.meta.applied ?: AppliedRec()
        val r = out.result
        val folders = LinkedHashMap(prev.folders)
        if (out.moved.isNotEmpty()) for (f in s.doc.folders) {
            var count = 0
            var bytes = 0L
            for (id in f.itemIds) if (id in out.moved) { count++; bytes += s.items[id]?.bytes ?: 0L }
            if (count > 0) {
                val t = folders[f.name] ?: FolderTally()
                folders[f.name] = FolderTally(t.count + count, t.bytes + bytes)
            }
        }
        // Ieșite din plan fără să ajungă la loc (șterse între timp, copie rămasă în „De aruncat”) = pierdute definitiv;
        // restul eșecurilor rămân în plan și se pot reîncerca.
        val lost = (out.removed.size - out.moved.size - r.trashed).coerceIn(0, r.failed)
        return AppliedRec(
            moved = prev.moved + r.moved, trashed = prev.trashed + r.trashed, lost = prev.lost + lost,
            pending = (r.failed - lost).coerceAtLeast(0), trashBytes = prev.trashBytes + r.freedBytes,
            folders = folders, lastAt = System.currentTimeMillis()
        )
    }

    /** Rezumatul pentru site (§3.3 din DESIGN-4.4): scopul, destinația, dosarele, gunoiul, totalurile. */
    private fun summaryOf(s: Snapshot, a: AppliedRec): InvSummaryDoc {
        val meta = s.meta
        val photos = meta.kind == InvKind.Photos
        val dest = s.plan.dest
        val source = meta.tree?.let { Uri.parse(it) }
        val mode: String
        val label: String
        when {
            !photos -> { mode = "folder"; label = source?.let { TreePaths.label(it) } ?: "Folder" }
            meta.bucketId != null -> {
                mode = "album"
                label = s.items.values.firstOrNull { it.bucket.isNotBlank() }?.bucket ?: "Album"
            }
            meta.lastN != null -> { mode = "last"; label = "Ultimele ${InvSummaryDoc.count(meta.lastN)}" }
            else -> { mode = "all"; label = "Toată galeria" }
        }
        return InvSummaryDoc(
            id = meta.runId,
            kind = if (photos) "photos" else "docs",
            startedAt = meta.createdAt,
            finishedAt = a.lastAt,
            appVersion = try { com.forja.app.BuildConfig.VERSION_NAME } catch (_: Throwable) { "" },
            scopeMode = mode,
            scopeN = if (mode == "last") meta.lastN else null,
            scopeLabel = label,
            destLabel = if (photos) DestNames.option(dest) else DestNames.short(dest),
            destPath = DestNames.path(dest, source).uppercase(),
            folders = a.folders.map { (name, t) -> InvSummaryDoc.Folder(name, t.count, t.bytes) },
            trashCount = a.trashed,
            trashBytes = a.trashBytes,
            moved = a.moved,
            failed = a.failed,
            freedBytes = if (photos) a.trashBytes else null,
            provider = s.doc.provider,
            trashByReason = trashReasons(s.doc, s.doc.trash.itemIds + s.doc.reasons.keys),
            trashExpiresAt = if (photos && a.trashed > 0) a.lastAt + InvMirror.TRASH_DAYS * 86_400_000L else null,
            updatedAt = System.currentTimeMillis()
        ).let { d ->
            // Temele și coperțile dosarelor (după nume, ca în planul de la „gata”).
            val themes = s.doc.folders.associate { it.name to it.theme }
            val cov = InvMirror.coversOf(meta.runId)
            d.copy(folders = d.folders.map { f -> f.copy(theme = themes[f.name], covers = cov[f.name].orEmpty()) })
        }
    }

    /** „De aruncat” pe motiv (ce a propus analiza; null = pus acolo de om). */
    private fun trashReasons(doc: PlanDoc, ids: Collection<String>): Map<String, Int> =
        ids.toSet().groupingBy { InvMirror.reasonCode(doc.reasons[it]) }.eachCount()

    /** Planul gata, pentru site (mirror C): dosarele planului cu numărul și octeții lor, încă nimic mutat. */
    private fun readySummary(s: Snapshot): InvSummaryDoc {
        val base = summaryOf(s, AppliedRec(lastAt = System.currentTimeMillis()))
        return base.copy(
            folders = s.doc.folders.map { f -> InvSummaryDoc.Folder(f.name, f.itemIds.size, f.itemIds.sumOf { s.items[it]?.bytes ?: 0L }, f.theme) },
            trashCount = s.doc.trash.itemIds.size, trashBytes = s.doc.trash.itemIds.sumOf { s.items[it]?.bytes ?: 0L },
            moved = 0, failed = 0, freedBytes = null, state = "ready", trashExpiresAt = null,
            trashByReason = trashReasons(s.doc, s.doc.trash.itemIds)
        )
    }

    /** Uită tot: oprește analiza, șterge planul, fișierele și pointerul. */
    suspend fun clear(context: android.content.Context) {
        val ctx = bind(context)
        InventoryWorker.cancelWork(ctx)
        lock.withLock {
            // Un plan aruncat fără nicio aplicare nu rămâne pe site; unul aplicat în parte rămâne cu ce s-a mutat.
            (snapshot?.meta ?: currentRunId?.let { InventoryStore.readRun(ctx, it)?.meta })?.takeIf { it.applied == null }?.let { InvMirror.discard(ctx, it.runId) }
            currentRunId = null
            snapshot = null
            moveGrant = null
            trashGrant = null
            _plan.value = null
            _progress.value = null
            withContext(Dispatchers.IO) { InventoryStore.deleteAllExcept(ctx, null) }
            InventoryStore.setLatest(ctx, null)
        }
        InventoryNotify.cancelAll(ctx)
    }

    // ─────────────────────────── Legătura cu lucrătorul (intern) ───────────────────────────

    internal fun isCurrent(runId: String): Boolean = currentRunId == runId

    /** Lucrătorul își revendică rularea: e cea curentă (sau, după o repornire, cea din pointer). */
    internal suspend fun claim(ctx: Context, runId: String): Boolean {
        if (appContext == null) appContext = ctx.applicationContext ?: ctx
        val cur = currentRunId
        val ok = if (cur != null) cur == runId else {
            val latest = InventoryStore.latest(ctx)
            if (latest == null || latest == runId) {
                currentRunId = runId
                if (latest == null) InventoryStore.setLatest(ctx, runId)
                true
            } else false
        }
        if (ok) activeWorker = runId
        return ok
    }

    internal fun release(runId: String) {
        if (activeWorker == runId) activeWorker = null
    }

    internal fun publish(p: InvProgress) {
        if (p.runId == currentRunId && snapshot == null) {
            _progress.value = p
            appContext?.let { InvMirror.stage(it, p) }   // mirror C: starea analizei pe site, o scriere pe etapă
        }
    }

    /** Analiza s-a terminat: planul se salvează, se publică, se anunță (eveniment + notificare). */
    internal suspend fun ready(ctl: RunCtl, plan: PlanDoc, items: Map<String, ItemRec>) {
        val ctx = ctl.ctx
        var done: RunMeta? = null
        var event: InvEvent.Done? = null
        // Destinația implicită (ultima aleasă de om) intră în plan de la început; se schimbă din „Locație” la aplicare.
        val doc = if (plan.dest == null) plan.copy(dest = defaultDest(ctx, ctl.meta.kind, ctl.meta.tree)) else plan
        lock.withLock {
            if (!isCurrent(ctl.runId)) return@withLock
            val trashBytes = doc.trash.itemIds.sumOf { items[it]?.bytes ?: 0L }
            val m = ctl.meta.copy(
                stage = InvStage.Ready, done = SCALE, total = SCALE, error = null, folders = doc.folders.size,
                itemCount = PlanEdits.count(doc), trashCount = doc.trash.itemIds.size, trashBytes = trashBytes,
                updatedAt = System.currentTimeMillis()
            )
            ctl.meta = m
            ctl.finish()
            withContext(Dispatchers.IO) {
                InventoryStore.writeRun(ctx, RunFile(m, doc))
                InventoryStore.dropCheckpoints(ctx, m.runId)
            }
            val snap = snapshotOf(m, doc, items, baseOf(items))
            snapshot = snap
            moveGrant = null
            trashGrant = null
            _plan.value = snap.plan
            _progress.value = readyProgress(snap)
            done = m
            event = InvEvent.Done(m.runId, doc.folders.size, trashBytes)
        }
        val m = done ?: return
        // Mirror C: planul gata apare pe site (dosarele, temele, motivele), apoi coperțile cu contractul v4.
        snapshot?.takeIf { it.meta.runId == m.runId && it.meta.applied == null }?.let { snap ->
            InvMirror.ready(ctx, readySummary(snap), snap.doc.folders.map { f -> Triple(f.name, cover(f.itemIds), f.itemIds.size.toLong()) },
                snap.items.mapValues { it.value.uri }, snap.meta.kind == InvKind.Photos)
        }
        event?.let { _events.tryEmit(it) }
        InventoryNotify.ready(ctx, m.kind, m.folders, m.itemCount, m.trashCount)
        // „Când tace muzica, inventarul e gata”: pauză (dacă „Oprește la final”), sunetul de final și vibrația.
        try { com.forja.app.core.music.Music.onInventoryDone(ctx) } catch (_: Exception) { }
    }

    /** Analiza s-a oprit cu o eroare explicată: stare Failed pe disc și în [progress], eveniment, notificare. */
    internal suspend fun fail(ctl: RunCtl, message: String) {
        ctl.finish()
        val ctx = ctl.ctx
        if (!isCurrent(ctl.runId)) return
        val m = ctl.meta.copy(stage = InvStage.Failed, error = message, updatedAt = System.currentTimeMillis())
        withContext(NonCancellable + Dispatchers.IO) { InventoryStore.writeRun(ctx, RunFile(m)) }
        _progress.value = InvProgress(m.runId, m.kind, InvStage.Failed, m.done.coerceIn(0, m.total), m.total.coerceAtLeast(1),
            null, emptyList(), emptyList(), message)
        _events.tryEmit(InvEvent.Failed(message))
        InventoryNotify.failed(ctx, message)
        InvMirror.failed(ctx, m, message)
    }

    // ─────────────────────────── Construirea stării publice ───────────────────────────

    private suspend fun commit(ctx: Context, s: Snapshot, doc: PlanDoc): Snapshot {
        val trashBytes = doc.trash.itemIds.sumOf { s.items[it]?.bytes ?: 0L }
        val meta = s.meta.copy(
            stage = InvStage.Ready, folders = doc.folders.size, itemCount = PlanEdits.count(doc),
            trashCount = doc.trash.itemIds.size, trashBytes = trashBytes, updatedAt = System.currentTimeMillis()
        )
        val next = snapshotOf(meta, doc, s.items, s.base)
        snapshot = next
        _plan.value = next.plan
        withContext(Dispatchers.IO) { InventoryStore.writeRun(ctx, RunFile(meta, doc)) }
        return next
    }

    private fun baseOf(items: Map<String, ItemRec>): Map<String, InvItem> {
        val out = HashMap<String, InvItem>(items.size * 2)
        for (r in items.values) out[r.id] = InvItem(r.id, Uri.parse(r.uri), r.kind, r.takenAt, r.bytes, r.width, r.height, r.mime, r.name)
        return out
    }

    private fun snapshotOf(meta: RunMeta, doc: PlanDoc, items: Map<String, ItemRec>, base: Map<String, InvItem>): Snapshot {
        val map = HashMap<String, InvItem>(PlanEdits.count(doc) * 2)
        for (f in doc.folders) for (id in f.itemIds) base[id]?.let { map[id] = it }
        for (id in doc.trash.itemIds) base[id]?.let { item ->
            val why = doc.reasons[id]
            map[id] = if (why != null) item.copy(reason = why) else item
        }
        val folders = doc.folders.map { InvFolder(it.id, it.name, it.theme, it.itemIds, cover(it.itemIds)) }
        val trash = InvFolder(doc.trash.id, doc.trash.name, doc.trash.theme, doc.trash.itemIds, cover(doc.trash.itemIds), special = true)
        val source = meta.tree?.let { Uri.parse(it) }
        return Snapshot(meta, doc, items, base, InvPlan(meta.runId, meta.kind, meta.createdAt, map, folders, trash, doc.provider, destOf(meta.kind, doc.dest), source))
    }

    private fun cover(ids: List<String>): List<String> = Clustering.spread(ids.size, 4).map { ids[it] }

    private fun destOf(kind: InvKind, rec: DestRec?): InvDest = when (kind) {
        InvKind.Photos -> InvDest.Media(rec.mediaRoot())
        InvKind.Documents -> InvDest.Tree(rec?.tree?.let { Uri.parse(it) }, rec.docsSub())
    }

    /**
     * Destinația implicită a unui plan nou: ultima aleasă (Prefs `inventory_photo_root` / `inventory_docs_dest`),
     * altfel „Pictures/FORJA/” și „Organizate” în folderul ales. O destinație de documente fără permisiune se uită.
     */
    private suspend fun defaultDest(ctx: Context, kind: InvKind, source: String?): DestRec {
        val prefs = ForjaApp.from(ctx).prefs
        return try {
            when (kind) {
                InvKind.Photos -> DestRec(mediaRoot = MediaRoots.normalize(prefs.inventoryPhotoRoot.first(), anyTop = true) ?: MediaRoots.DEFAULT)
                InvKind.Documents -> {
                    val src = source?.let { Uri.parse(it) }
                    val saved = DocDest.savedTree(ctx, prefs)?.takeUnless { src != null && TreePaths.same(it, src) }
                    if (saved != null) DestRec(tree = saved.toString(), sub = "") else DestRec()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (kind == InvKind.Photos) DestRec(mediaRoot = MediaRoots.DEFAULT) else DestRec()
        }
    }

    private fun readyProgress(s: Snapshot): InvProgress {
        val bins = s.doc.folders.sortedWith(compareByDescending<FolderRec> { it.itemIds.size }.thenBy { it.id })
            .take(5).map { BinTick(it.name, it.itemIds.size) }
        val recent = s.plan.folders.mapNotNull { f -> f.cover.firstOrNull()?.let { s.base[it]?.uri } }.take(6)
        return InvProgress(s.meta.runId, s.meta.kind, InvStage.Ready, SCALE, SCALE, 0, recent, bins)
    }
}
