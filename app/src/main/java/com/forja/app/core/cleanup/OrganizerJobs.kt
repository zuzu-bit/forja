package com.forja.app.core.cleanup

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

// ═══════════════ Organizarea pe telefon ȘI pe site — clientul protocolului 4 ═══════════════
// Totul e opt-in: fără comutatorul „Și pe site (copii 24 h)" nimic nu pleacă de pe telefon.
// Cu el pornit: fiecare scanare devine o lucrare în contul online (inventar + verdictele motorului),
// copiile analizate urcă pentru 24 h, iar mutările aprobate din laptop se execută aici, cu același
// acord Android ca orice mutare locală.

/** Comutatoarele și identitatea acestui telefon față de site (SharedPreferences „organizer_v4"). */
object OrganizerSettings {
    private const val FILE = "organizer_v4"
    private val _siteOn = MutableStateFlow(false)
    val siteOn: StateFlow<Boolean> get() = _siteOn
    @Volatile private var loaded = false

    fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        _siteOn.value = prefs(ctx).getBoolean("site_on", false)
        loaded = true
    }

    fun siteOn(ctx: Context): Boolean { load(ctx); return _siteOn.value }

    fun setSiteOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("site_on", on).apply()
        _siteOn.value = on
        loaded = true
    }

    fun currentJob(ctx: Context): String = prefs(ctx).getString("current_job", "") ?: ""
    fun setCurrentJob(ctx: Context, id: String) { prefs(ctx).edit().putString("current_job", id).apply() }

    /** UUID stabil pentru o sursă (arborele SAF ales), cerut de grant.sources și de source_id. */
    fun sourceId(ctx: Context, source: String, tree: String): String {
        val key = "source:$source:" + sha256Hex(tree).take(24)
        val p = prefs(ctx)
        p.getString(key, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(key, id).apply()
        return id
    }
}

/** Starea lucrării curente, pentru linia de stare din ecran. */
data class OrganizerStatus(
    val jobId: String,
    val source: String,
    val origin: String,
    val state: String,
    val message: String,
    val mode: String,
    val total: Int,
    val moved: Int,
    val uploaded: Int,
    val analyzed: Int,
    val review: Int,
    val needsTouch: Int
) {
    val label: String
        get() = when (state) {
            "queued" -> "în așteptare"
            "inventory" -> "inventar"
            "hashing" -> "amprente"
            "publishing" -> "trimit inventarul"
            "uploading" -> "urc copiile"
            "analyzing" -> "analiză pe site"
            "applying" -> "mut"
            "ready" -> "așteaptă aprobarea din laptop"
            "synced" -> "pe site · organizezi de oriunde"
            "needs_permission" -> "așteaptă o atingere"
            "paused" -> "pauză din site"
            "cancelled" -> "oprită"
            "complete" -> "gata"
            "needs_review" -> "de verificat"
            "failed_retryable" -> "reîncerc"
            "needs_access" -> "acces de reactivat"
            else -> state
        }
}

/** Ce știe site-ul despre un element (afișat lângă sugestiile FORJA AI, cu sursa etichetată). */
data class SiteHint(
    val itemId: String,
    val state: String,
    val uploaded: Boolean,
    val destination: String,
    val analysis: SiteAnalysis?,
    val error: String
)

private class Stopped : Exception("oprit")

private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonObject.int(k: String): Int = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

private val CTRL = Regex("[\\p{Cntrl}]")
private val PATH_BAD = Regex("[\\\\:*?\"<>|\\p{Cntrl}]")
private val MIME_OK = Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")

/** Text acceptat de server: fără caractere de control, ≤ max. */
internal fun cleanText(s: String?, max: Int, fallback: String = ""): String =
    (s ?: "").replace(CTRL, " ").replace(Regex("\\s+"), " ").trim().take(max).trim().ifBlank { fallback }

/** Cale relativă acceptată de server (segmente curățate, fără goale, ≤ 8); null când e prea adâncă/lungă. */
internal fun cleanPath(raw: String, max: Int): String? {
    val segs = raw.replace('\\', '/').split('/').map { it.replace(PATH_BAD, " ").replace(Regex("\\s+"), " ").trim().trim('.') }
        .filter { it.isNotBlank() && it != ".." }
    if (segs.size > 8) return null
    val p = segs.joinToString("/")
    return if (p.length > max) null else p
}

internal fun cleanMime(m: String): String {
    val l = m.lowercase().trim()
    return if (l.length <= 120 && MIME_OK.matches(l)) l else "application/octet-stream"
}

object OrganizerJobs {
    const val TAG = "organizer-v4"
    private const val POLL_WORK = "organizer-poll"
    private const val MAX_ITEMS = 500
    private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
    private const val JOB_UPLOAD_BUDGET = 400L * 1024 * 1024
    private const val THUMB_MAX = 160 * 1024
    private const val NOTIF_ID = 71
    const val ROUTE_EXTRA = "forja_route"

    /** Destinația lucrărilor pornite din telefon pentru poze = dosarul motorului („FORJA Curățenie"). */
    val PHOTO_DESTINATION: String = CleanupEngine.ROOT_RELATIVE.removePrefix("Pictures/").trimEnd('/')
    val DOC_DESTINATION: String = DocumentOrganizer.ROOT_FOLDER

    private val mutex = Mutex()
    private val json get() = InsightsApi.json

    /** Ecranul de curățenie e vizibil: mutările de poze aprobate din site se cer pe loc, nu prin notificare. */
    @Volatile var screenVisible: Boolean = false

    fun enabled(app: ForjaApp): Boolean = OrganizerSettings.siteOn(app) && app.auth.currentUid != null

    fun retryable(e: Exception): Boolean =
        if (e is InsightsFailure) e.code in setOf(408, 429, 500, 502, 503, 504) else e is IOException

    private suspend fun device(app: ForjaApp): String = InsightsApi.deviceId(app.prefs)()
    private fun jobsPath(device: String) = "/v2/organizer/devices/$device/jobs"
    private fun jobPath(job: OrgJob) = "${jobsPath(job.device)}/${job.id}"

    // ─────────────────────────── Programare (WorkManager) ───────────────────────────

    private fun net() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context, id: String, append: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<OrganizerJobWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(net())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("$TAG:$id", if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** Sondare la 15 min (site → telefon) cât timp comutatorul e pornit. */
    fun schedulePolling(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<OrganizerPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(net())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(POLL_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun stopPolling(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(POLL_WORK)
    }

    /** Lucrările cu treabă rămasă pornesc din nou (după repornire, după net). */
    fun resumeActive(app: ForjaApp) {
        val uid = app.auth.currentUid ?: return
        val ledger = OrganizerLedger.get(app)
        for (j in ledger.jobs(uid)) {
            if (j.state in OrganizerLedger.ACTIVE_STATES || j.control.isNotBlank() || ledger.withQueue(j.id, 1).isNotEmpty()) schedule(app, j.id)
        }
    }

    // ─────────────────────────── Grant + setările de fișiere (o dată per telefon) ───────────────────────────

    class Grant(val id: String, val revision: Int)

    /**
     * Înregistrează telefonul pe site (protocol 4, organize = acordul din comutator) și pornește
     * sincronizarea de fișiere. Se repetă doar când sursele se schimbă (alt dosar de documente).
     */
    suspend fun ensureGrant(app: ForjaApp): Grant = withContext(Dispatchers.IO) {
        val uid = app.auth.currentUid ?: throw InsightsFailure(401, "Conectează-te în FORJA.")
        val device = device(app)
        val organizer = DocumentOrganizer(app, app.prefs)
        val tree = organizer.persistedTree()
        val label = cleanText(Build.MODEL, 80, "Telefon")
        val sources = buildJsonArray {
            if (tree != null) add(buildJsonObject {
                put("id", OrganizerSettings.sourceId(app, "files", tree.toString()))
                put("source", "files")
                put("label", cleanText(organizer.treeName(tree), 100, "Documente"))
                put("folder", "")
            })
        }
        val signature = sha256Hex("$uid|$device|$label|$sources")
        val p = OrganizerSettings.prefs(app)
        val known = p.getString("grant_id", "") ?: ""
        if (known.isNotBlank() && p.getString("grant_sig", "") == signature) {
            try {
                val d = InsightsApi.json("/v2/cleanup/devices/$device")
                if (d.obj("grant")?.str("id") == known) return@withContext Grant(known, d.int("revision"))
            } catch (e: InsightsFailure) { if (e.code != 404) throw e }
        }
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("id", id); put("enabled", true); put("photos", true); put("files", true)
            put("label", label); put("protocol", 4); put("organize", true); put("sources", sources)
        }
        val d = InsightsApi.json("/v2/cleanup/devices/$device/grant", body)
        InsightsApi.json("/v2/files/settings/$device", buildJsonObject { put("enabled", true); put("photos", true); put("files", true) })
        p.edit().putString("grant_id", id).putString("grant_sig", signature).apply()
        Grant(id, d.int("revision"))
    }

    // ─────────────────────────── Lucrări pornite din telefon ───────────────────────────

    private fun photoReasons(report: CleanupReport): Map<Long, String> {
        val out = HashMap<Long, String>()
        fun add(id: Long, r: String) { if (!out.containsKey(id)) out[id] = r }
        report.duplicates.forEach { g -> g.copies.forEach { add(it.id, "duplicat identic") } }
        report.similar.forEach { g -> g.others.forEach { add(it.id, "aproape identică") } }
        report.screenshots.forEach { add(it.id, "captură de ecran") }
        report.blurry.forEach { add(it.item.id, "neclară") }
        report.tiny.forEach { add(it.id, "mică") }
        report.large.forEach { add(it.id, "mare") }
        return out
    }

    private fun docReasons(report: DocReport): Map<String, String> {
        val out = HashMap<String, String>()
        fun add(k: String, r: String) { if (!out.containsKey(k)) out[k] = r }
        report.duplicates.forEach { g -> g.copies.forEach { add(it.key, "duplicat identic") } }
        report.large.forEach { add(it.key, "mare") }
        report.old.forEach { add(it.key, "veche") }
        report.suspects.forEach { (d, r) -> add(d.key, cleanText(r, 300, "nume de copie")) }
        return out
    }

    private fun photoItem(ledger: OrganizerLedger, job: OrgJob, m: MediaItem, seq: Int, reason: String?): OrgItem? {
        val folder = cleanPath(m.relativePath, 240) ?: return null
        return OrgItem(
            id = UUID.randomUUID().toString(), job = job.id, originalId = ledger.originalId(job.owner, m.uri.toString()),
            uri = m.uri.toString(), name = m.name, folder = folder, mime = cleanMime(m.mime), bytes = m.sizeBytes,
            modifiedAt = m.dateModifiedMs.coerceAtLeast(0L), photo = true, flagged = reason != null, seq = seq,
            mediaId = m.id, relativePath = m.relativePath, reason = reason ?: "ok"
        )
    }

    private fun docItem(ledger: OrganizerLedger, job: OrgJob, d: DocItem, seq: Int, reason: String?): OrgItem? {
        val folder = cleanPath(d.path.substringBeforeLast('/', ""), 240) ?: return null
        return OrgItem(
            id = UUID.randomUUID().toString(), job = job.id, originalId = ledger.originalId(job.owner, d.uri.toString()),
            uri = d.uri.toString(), name = d.name, folder = folder, mime = cleanMime(d.mime), bytes = d.sizeBytes,
            modifiedAt = d.lastModified.coerceAtLeast(0L), photo = false, flagged = reason != null, seq = seq,
            docId = d.documentId, parentUri = d.parentUri.toString(), docPath = d.path, docFlags = d.flags, reason = reason ?: "ok"
        )
    }

    private fun inScope(path: String, folder: String, recursive: Boolean): Boolean {
        val p = path.trim('/'); val f = folder.trim('/')
        return f.isBlank() || p == f || (recursive && p.startsWith("$f/"))
    }

    /** După o scanare a galeriei: lucrare „photos" cu verdictele motorului drept `reason`. */
    suspend fun startFromPhotos(app: ForjaApp, scope: CleanupScope, report: CleanupReport, onlineAi: Boolean): String? = withContext(Dispatchers.IO) {
        val uid = app.auth.currentUid ?: return@withContext null
        val ledger = OrganizerLedger.get(app)
        val reasons = photoReasons(report)
        val ordered = report.scanned
            .filter { it.sizeBytes > 0 && !it.relativePath.startsWith(CleanupEngine.ROOT_RELATIVE) }
            .sortedWith(compareByDescending<MediaItem> { reasons.containsKey(it.id) }.thenByDescending { it.bestTimeMs })
            .take(MAX_ITEMS)
        if (ordered.isEmpty()) return@withContext null
        val job = OrgJob(
            id = UUID.randomUUID().toString(), owner = uid, device = device(app), source = "photos", origin = "phone",
            destination = PHOTO_DESTINATION, mode = if (onlineAi) "online" else "local", upload = true, autoApply = false,
            createdAt = System.currentTimeMillis(), commandId = UUID.randomUUID().toString(),
            scopeJson = try { json.encodeToString(CleanupScope.serializer(), scope) } catch (_: Exception) { "" },
            scannedTotal = report.scanned.size, inventoryDone = true,
            message = "Pregătesc ${ordered.size} ${if (ordered.size == 1) "element" else "elemente"} pentru site."
        )
        val items = ordered.mapIndexedNotNull { i, m -> photoItem(ledger, job, m, i, reasons[m.id]) }
        mutex.withLock { ledger.saveJob(job); ledger.saveItems(items); retireOld(app, ledger, uid, "photos", job.id) }
        OrganizerSettings.setCurrentJob(app, job.id)
        schedule(app, job.id)
        job.id
    }

    /** După citirea folderului de documente: lucrare „files" pe arborele SAF ales. */
    suspend fun startFromDocs(app: ForjaApp, tree: Uri, treeLabel: String, report: DocReport, onlineAi: Boolean): String? = withContext(Dispatchers.IO) {
        val uid = app.auth.currentUid ?: return@withContext null
        val ledger = OrganizerLedger.get(app)
        // Același dosar recitit în 24 h (deschiderea ecranului, „rescanează") reia lucrarea existentă.
        val reuse = ledger.jobs(uid).firstOrNull {
            it.origin == "phone" && it.source == "files" && it.tree == tree.toString() && it.active && !it.stopped &&
                System.currentTimeMillis() - it.createdAt < 24L * 3600_000L && (if (onlineAi) "online" else "local") == it.mode
        }
        if (reuse != null) { OrganizerSettings.setCurrentJob(app, reuse.id); schedule(app, reuse.id); return@withContext reuse.id }
        val reasons = docReasons(report)
        val ordered = report.items
            .filter { it.sizeBytes > 0 && !it.path.startsWith("$DOC_DESTINATION/") }
            .sortedWith(compareByDescending<DocItem> { reasons.containsKey(it.key) }.thenByDescending { it.lastModified })
            .take(MAX_ITEMS)
        if (ordered.isEmpty()) return@withContext null
        val job = OrgJob(
            id = UUID.randomUUID().toString(), owner = uid, device = device(app), source = "files", origin = "phone",
            destination = DOC_DESTINATION, mode = if (onlineAi) "online" else "local", upload = true, autoApply = false,
            createdAt = System.currentTimeMillis(), commandId = UUID.randomUUID().toString(),
            tree = tree.toString(), treeLabel = treeLabel, scannedTotal = report.items.size, inventoryDone = true,
            message = "Pregătesc ${ordered.size} ${if (ordered.size == 1) "fișier" else "fișiere"} pentru site."
        )
        val items = ordered.mapIndexedNotNull { i, d -> docItem(ledger, job, d, i, reasons[d.key]) }
        mutex.withLock { ledger.saveJob(job); ledger.saveItems(items); retireOld(app, ledger, uid, "files", job.id) }
        OrganizerSettings.setCurrentJob(app, job.id)
        schedule(app, job.id)
        job.id
    }

    /** Oprește lucrarea (comandă „cancel" către site; originalele rămân neatinse). */
    suspend fun cancel(app: ForjaApp, jobId: String) {
        val ledger = OrganizerLedger.get(app)
        mutex.withLock {
            val job = ledger.job(jobId) ?: return
            job.control = "cancel"; job.controlRequest = UUID.randomUUID().toString()
            job.state = "cancelled"; job.message = "Oprită de tine. Rezultatele verificate rămân."
            ledger.saveJob(job)
        }
        schedule(app, jobId)
    }

    // ─────────────────────────── Procesarea unei lucrări ───────────────────────────

    /** Rulează pașii protocolului pentru o lucrare. Întoarce true dacă mai e de lucru imediat. */
    suspend fun process(app: ForjaApp, id: String): Boolean = withContext(Dispatchers.IO) {
        val uid = app.auth.currentUid ?: return@withContext false
        if (!OrganizerSettings.siteOn(app)) return@withContext false
        val ledger = OrganizerLedger.get(app)
        val job = ledger.job(id) ?: return@withContext false
        if (job.owner != uid) return@withContext false
        try {
            mutex.withLock { flushQueues(ledger, job); flushControl(ledger, job) }
            if (job.state in setOf("cancelled", "paused", "complete", "needs_access")) return@withContext false
            mutex.withLock { register(app, ledger, job); remoteCheck(ledger, job) }
            inventory(app, ledger, job)
            publish(app, ledger, job)
            mutex.withLock { batch(ledger, job); analyzedReceipts(ledger, job) }
            if (job.upload) uploads(app, ledger, job)
            if (job.mode == "online") mutex.withLock { analyzeAuto(ledger, job) }
            mutex.withLock { approvals(ledger, job) }
            moves(app, ledger, job)
            mutex.withLock { finish(ledger, job) }
            false
        } catch (e: Stopped) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cur = ledger.job(id) ?: return@withContext false
            if (cur.state !in setOf("cancelled", "paused", "needs_access", "complete")) {
                cur.state = when {
                    retryable(e) -> "failed_retryable"
                    e is InsightsFailure && e.code in setOf(401, 403, 423) -> "needs_access"
                    e is SecurityException -> "needs_access"
                    else -> "needs_review"
                }
                cur.message = cleanText(e.message, 300, "Organizarea trebuie reluată.")
                ledger.saveJob(cur)
            }
            if (retryable(e)) throw e
            false
        }
    }

    private suspend fun register(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob) {
        if (job.registered) return
        job.state = "queued"; job.message = "Leg lucrarea de contul tău online."; ledger.saveJob(job)
        val grant = ensureGrant(app)
        job.grantId = grant.id
        var revision = grant.revision
        if (job.source == "files") job.sourceId = OrganizerSettings.sourceId(app, "files", job.tree)
        fun body(rev: Int) = buildJsonObject {
            put("id", job.id); put("grant_id", job.grantId); put("revision", rev); put("source", job.source)
            job.sourceId?.let { put("source_id", it) }
            put("scope", buildJsonObject { put("folder", job.folder); put("recursive", job.recursive) })
            put("destination", job.destination); put("mode", job.mode)
            put("auto_apply", job.autoApply); put("ai_consent", job.mode == "online")
            put("preferences", buildJsonObject { put("protected_folders", buildJsonArray { }) })
        }
        val remote = try {
            InsightsApi.json(jobsPath(job.device), body(revision))
        } catch (e: InsightsFailure) {
            if (e.code == 409 && (e.message ?: "").contains("Accesul s-a schimbat")) {
                revision = InsightsApi.json("/v2/cleanup/devices/${job.device}").int("revision")
                InsightsApi.json(jobsPath(job.device), body(revision))
            } else throw e
        }
        job.remoteRevision = remote.int("revision")
        val cmd = try {
            InsightsApi.json("${jobPath(job)}/command", buildJsonObject {
                put("request_id", job.commandId); put("revision", job.remoteRevision); put("action", "continue"); put("count", job.count)
            })
        } catch (e: InsightsFailure) {
            // Reluare după un răspuns pierdut: comanda există deja pe site (același request_id, altă revizie),
            // sau site-ul a trimis între timp propriul „continue" — o adoptăm în loc să blocăm lucrarea.
            if (e.code != 409) throw e
            val r = InsightsApi.json(jobPath(job))
            val c = r.obj("command")
            if (c == null || c.str("action") != "continue" || c.str("status") == "complete") throw e
            if (c.str("request_id") != job.commandId) { job.commandId = c.str("request_id"); job.count = c.int("count") }
            r
        }
        job.remoteRevision = cmd.int("revision")
        job.syncRevision = cmd.long("sync_revision")
        job.registered = true
        ledger.saveJob(job)
    }

    /** Starea de pe site: pauză/anulare/acces retras opresc lucrul (Stopped). */
    private suspend fun remoteCheck(ledger: OrganizerLedger, job: OrgJob): JsonObject {
        val r = try { InsightsApi.json(jobPath(job)) } catch (e: InsightsFailure) {
            if (e.code == 404 || e.code == 410) { job.state = "cancelled"; job.message = "Lucrarea a fost ștearsă din site."; ledger.saveJob(job); throw Stopped() }
            throw e
        }
        job.remoteRevision = r.int("revision")
        if (r.long("sync_revision") > 0) job.syncRevision = r.long("sync_revision")
        r.obj("counters")?.let { job.remoteReady = it.int("ready") }
        when (r.str("state")) {
            "paused" -> { job.state = "paused"; job.message = "Pusă pe pauză din site."; ledger.saveJob(job); throw Stopped() }
            "cancelled" -> { job.state = "cancelled"; job.message = "Oprită din site."; ledger.saveJob(job); throw Stopped() }
            "access_needed" -> { job.state = "needs_access"; job.message = "Site-ul cere reactivarea accesului de pe telefon."; ledger.saveJob(job); throw Stopped() }
        }
        return r
    }

    private fun applyJobResponse(ledger: OrganizerLedger, job: OrgJob, r: JsonObject?) {
        if (r == null) return
        if (r.int("revision") > 0) job.remoteRevision = r.int("revision")
        if (r.long("sync_revision") > 0) job.syncRevision = r.long("sync_revision")
        r.obj("counters")?.let { job.remoteReady = it.int("ready") }
        when (r.str("state")) {
            "paused" -> { job.state = "paused"; job.message = "Pusă pe pauză din site." }
            "cancelled" -> { job.state = "cancelled"; job.message = "Oprită din site." }
        }
        ledger.saveJob(job)
    }

    /** Lucrările primite din site fac inventarul aici: galeria prin CleanupEngine, documentele prin SAF. */
    private suspend fun inventory(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob) {
        if (job.inventoryDone) return
        job.state = "inventory"
        job.message = if (job.source == "photos") "Inventariez galeria pentru site." else "Inventariez dosarul pentru site."
        ledger.saveJob(job)
        val existing = ledger.items(job.id).map { it.uri }.toHashSet()
        val room = (MAX_ITEMS - existing.size).coerceAtLeast(0)
        val seqBase = ledger.count(job.id)
        val newItems = ArrayList<OrgItem>()
        if (job.source == "photos") {
            val engine = CleanupEngine(app, app.prefs)
            val scope = if (job.count in 1..1000) CleanupScope(ScopeKind.NEXT_BATCH, batchSize = job.count) else CleanupScope(ScopeKind.WHOLE_GALLERY)
            var report: CleanupReport? = null
            engine.scan(scope, resume = true).collect { p -> if (p is ScanProgress.Done) report = p.report }
            val rep = report ?: throw IllegalStateException("Scanarea galeriei nu s-a încheiat.")
            val reasons = photoReasons(rep)
            val ordered = rep.scanned
                .filter {
                    it.sizeBytes > 0 && !it.relativePath.startsWith(CleanupEngine.ROOT_RELATIVE) &&
                        !it.relativePath.startsWith("Pictures/${job.destination}/") &&
                        inScope(it.relativePath, job.folder, job.recursive) && it.uri.toString() !in existing
                }
                .sortedWith(compareByDescending<MediaItem> { reasons.containsKey(it.id) }.thenByDescending { it.bestTimeMs })
                .take(room)
            ordered.forEachIndexed { i, m -> photoItem(ledger, job, m, seqBase + i, reasons[m.id])?.let { newItems += it } }
            job.scannedTotal = rep.scanned.size
        } else {
            val tree = job.tree.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: throw InsightsFailure(403, "Alege dosarul de documente în aplicație, apoi continuă din site.")
            val organizer = DocumentOrganizer(app, app.prefs)
            val (items, _) = organizer.inventory(tree)
            val rep = organizer.detect(items)
            val reasons = docReasons(rep)
            val ordered = items
                .filter {
                    it.sizeBytes > 0 && !it.path.startsWith("${job.destination}/") &&
                        inScope(it.path.substringBeforeLast('/', ""), job.folder, job.recursive) && it.key !in existing
                }
                .sortedWith(compareByDescending<DocItem> { reasons.containsKey(it.key) }.thenByDescending { it.lastModified })
                .take(room)
            ordered.forEachIndexed { i, d -> docItem(ledger, job, d, seqBase + i, reasons[d.key])?.let { newItems += it } }
            job.scannedTotal = items.size
        }
        mutex.withLock {
            ledger.saveItems(newItems)
            job.inventoryDone = true; job.inventorySent = false
            job.message = "${newItems.size} ${if (newItems.size == 1) "element nou" else "elemente noi"} în inventar."
            ledger.saveJob(job)
        }
    }

    private fun itemJson(item: OrgItem): JsonObject = buildJsonObject {
        put("id", item.id); put("original_id", item.originalId); put("version", item.sha); put("sha256", item.sha)
        put("name", cleanText(item.name, 200, "Fișier")); put("folder", item.folder); put("media_type", cleanMime(item.mime))
        put("bytes", item.bytes); put("modified_at", item.modifiedAt.coerceAtLeast(0L))
        item.extraction?.let { raw -> try { put("extraction", json.parseToJsonElement(raw)) } catch (_: Exception) { } }
    }

    private suspend fun postItems(job: OrgJob, items: List<OrgItem>) {
        InsightsApi.json("${jobPath(job)}/items", buildJsonObject {
            put("grant_id", job.grantId)
            put("items", JsonArray(items.map { itemJson(it) }))
        })
    }

    /** Amprente SHA-256 (versiunea fișierului) + inventarul pe site în pagini de ≤ 100. */
    private suspend fun publish(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob) {
        val engine = CleanupEngine(app, app.prefs)
        val organizer = DocumentOrganizer(app, app.prefs)
        val toHash = ledger.items(job.id, setOf("pending")).filter { it.sha.isBlank() }
        if (toHash.isNotEmpty()) {
            job.state = "hashing"; job.message = "Calculez amprentele (${toHash.size})."; mutex.withLock { ledger.saveJob(job) }
            var pending = ArrayList<OrgItem>()
            for (item in toHash) {
                currentCoroutineContext().ensureActive()
                try {
                    item.sha = if (item.photo) engine.sha256(Uri.parse(item.uri)) else organizer.sha256(Uri.parse(item.uri))
                    if (!item.photo && item.extraction == null) {
                        val d = docFrom(item)
                        if (organizer.isTextLike(d)) {
                            val text = organizer.textSnippet(d)
                            if (!text.isNullOrBlank()) {
                                item.extraction = buildJsonObject {
                                    put("source_sha256", item.sha); put("method", "utf8_text")
                                    put("text", cleanText(text, 32000)); put("partial", item.bytes > 8192 || text.length >= 2000)
                                }.toString()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    item.state = "unavailable"; item.error = "Nu pot citi fișierul."
                }
                pending += item
                if (pending.size >= 25) { mutex.withLock { ledger.saveItems(pending) }; pending = ArrayList() }
            }
            mutex.withLock { ledger.saveItems(pending) }
        }
        mutex.withLock {
            val toPublish = ledger.items(job.id, setOf("pending")).filter { it.sha.isNotBlank() && !it.published }
            if (toPublish.isNotEmpty()) { job.state = "publishing"; job.message = "Trimit inventarul (${toPublish.size})."; ledger.saveJob(job) }
            for (page in toPublish.chunked(100)) {
                currentCoroutineContext().ensureActive()
                try {
                    postItems(job, page)
                    page.forEach { it.published = true }
                    ledger.saveItems(page)
                } catch (e: InsightsFailure) {
                    when (e.code) {
                        400, 403 -> for (item in page) {
                            try { postItems(job, listOf(item)); item.published = true } catch (e2: InsightsFailure) {
                                if (e2.code == 400 || e2.code == 403) { item.state = "unavailable"; item.error = cleanText(e2.message, 300) } else throw e2
                            }
                            ledger.saveItem(item)
                        }
                        409 -> { job.state = "paused"; job.message = cleanText(e.message, 300, "Continuă lucrarea din site."); ledger.saveJob(job); throw Stopped() }
                        else -> throw e
                    }
                }
            }
            if (!job.inventorySent && ledger.items(job.id, setOf("pending")).none { !it.published }) {
                val total = ledger.count(job.id)
                val unavailable = ledger.counts(job.id)["unavailable"] ?: 0
                val r = InsightsApi.json("${jobPath(job)}/items", buildJsonObject {
                    put("grant_id", job.grantId); put("items", buildJsonArray { })
                    put("inventory_complete", true); put("inventory_total", total); put("inventory_unavailable", unavailable.coerceAtMost(total))
                })
                job.inventorySent = true
                applyJobResponse(ledger, job, r)
            }
        }
    }

    /** Alocarea lotului: serverul dă în lucru originalele publicate (le blochează pentru alte lucrări). */
    private suspend fun batch(ledger: OrganizerLedger, job: OrgJob) {
        val unbatched = ledger.items(job.id, setOf("pending")).filter { it.published && !it.batched }
        for (page in unbatched.chunked(100)) {
            currentCoroutineContext().ensureActive()
            val requestId = page.first().batchRequest.ifBlank { UUID.randomUUID().toString() }
            page.forEach { it.batchRequest = requestId }
            ledger.saveItems(page)
            val resp = try {
                InsightsApi.json("${jobPath(job)}/batch", buildJsonObject {
                    put("request_id", requestId); put("grant_id", job.grantId); put("limit", page.size)
                    put("ids", JsonArray(page.map { JsonPrimitive(it.id) }))
                })
            } catch (e: InsightsFailure) {
                val msg = e.message ?: ""
                if (e.code == 409 && msg.contains("Lot modificat")) { page.forEach { it.batchRequest = "" }; ledger.saveItems(page); continue }
                if (e.code == 409) { job.state = "paused"; job.message = cleanText(msg, 300, "Continuă lucrarea din site."); ledger.saveJob(job); throw Stopped() }
                throw e
            }
            val ids = resp.arr("ids")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toHashSet() ?: emptySet()
            var cut = false
            for (item in page) {
                if (item.id in ids) { item.batched = true; item.state = "batched" }
                else if (job.count > 0) { item.batchRequest = ""; cut = true }
                else { item.state = "skipped"; item.error = "Ocupat de altă organizare sau deja mutat." }
            }
            ledger.saveItems(page)
            if (cut) break
        }
    }

    // ─────────────────────────── Chitanțe ───────────────────────────

    private fun receipt(
        item: OrgItem, state: String, reason: String? = null, destination: String? = null,
        fileId: String? = null, targetSha: String? = null, message: String? = null
    ): String = buildJsonObject {
        put("id", item.id); put("operation_id", item.operationId); put("state", state); put("source_sha256", item.sha)
        reason?.let { put("reason", cleanText(it, 300, "ok")) }
        destination?.let { put("destination", it) }
        fileId?.let { put("file_id", it) }
        targetSha?.let { put("target_sha256", it) }
        message?.let { m -> cleanText(m, 400).takeIf { it.isNotBlank() }?.let { put("message", it) } }
    }.toString()

    private fun stateOf(receiptJson: String): String = try { json.parseToJsonElement(receiptJson).let { (it as JsonObject).str("state") } } catch (_: Exception) { "" }

    private fun enqueue(item: OrgItem, receiptJson: String) { item.queue = item.queue + receiptJson }

    private fun acknowledge(item: OrgItem, r: String) {
        val st = stateOf(r)
        item.sent = item.sent + (st to r)
        item.queue = item.queue.drop(1)
        when (st) {
            "analyzed" -> if (item.state == "batched") item.state = "analyzed"
            "uploaded" -> if (item.state in setOf("batched", "analyzed", "upload_pending")) item.state = "uploaded"
            "ready" -> item.state = "ready"
            "applying" -> { item.state = "applying"; item.intentSaved = true }
            "moved" -> item.state = "moved"
            "copied_pending_removal" -> item.state = "copied_pending_removal"
            "needs_review" -> {
                if (item.state != "needs_permission") item.state = "needs_review"
                // Înainte de intenție, site-ul a trecut elementul în „de verificat": mutarea de mai târziu
                // trebuie să retrimită ready (aprobarea rămâne pe element) înainte de applying.
                if (!item.intentSaved) item.sent = item.sent - "ready"
            }
            "skipped" -> item.state = "skipped"
        }
    }

    private fun drop(item: OrgItem, r: String, e: InsightsFailure) {
        val st = stateOf(r)
        item.queue = item.queue.drop(1)
        item.error = cleanText(e.message, 300, "Serverul a refuzat chitanța.")
        when (st) {
            "ready", "applying" -> if (!item.intentSaved) item.state = "blocked"
            "moved", "copied_pending_removal" -> item.state = "needs_review"
            "analyzed", "upload_pending", "uploaded", "skipped", "needs_review" -> { }
        }
    }

    private suspend fun postReceipts(job: OrgJob, list: List<String>): JsonObject =
        InsightsApi.json("${jobPath(job)}/receipts", buildJsonObject {
            put("grant_id", job.grantId)
            put("receipts", JsonArray(list.map { json.parseToJsonElement(it) }))
        })

    /** Trimite chitanțele din coadă (≤ 50 pe apel); una refuzată nu blochează restul. */
    private suspend fun flushQueues(ledger: OrganizerLedger, job: OrgJob) {
        if (!job.registered) return
        var rounds = 0
        while (rounds++ < 40) {
            currentCoroutineContext().ensureActive()
            val items = ledger.withQueue(job.id, 50)
            if (items.isEmpty()) return
            val heads = items.map { it to it.queue.first() }
            try {
                val resp = postReceipts(job, heads.map { it.second })
                heads.forEach { (item, r) -> acknowledge(item, r) }
                ledger.saveItems(items)
                applyJobResponse(ledger, job, resp.obj("job"))
            } catch (e: InsightsFailure) {
                when (e.code) {
                    404, 410 -> { job.state = "cancelled"; job.message = "Lucrarea nu mai există pe site."; ledger.saveJob(job); throw Stopped() }
                    423 -> { job.state = "needs_access"; job.message = cleanText(e.message, 300, "Accesul a fost oprit din site."); ledger.saveJob(job); throw Stopped() }
                    400, 403, 409 -> {
                        if (heads.size == 1) { val (item, r) = heads[0]; drop(item, r, e); ledger.saveItem(item) }
                        else for ((item, r) in heads) {
                            try { val resp = postReceipts(job, listOf(r)); acknowledge(item, r); applyJobResponse(ledger, job, resp.obj("job")) }
                            catch (e2: InsightsFailure) { if (e2.code in setOf(400, 403, 409)) drop(item, r, e2) else throw e2 }
                            ledger.saveItem(item)
                        }
                    }
                    else -> throw e
                }
            }
        }
    }

    private suspend fun flushControl(ledger: OrganizerLedger, job: OrgJob) {
        val action = job.control
        if (action.isBlank()) return
        if (!job.registered) { job.control = ""; ledger.saveJob(job); return }
        if (job.controlRequest.isBlank()) job.controlRequest = UUID.randomUUID().toString()
        fun body(rev: Int) = buildJsonObject { put("request_id", job.controlRequest); put("revision", rev); put("action", action); put("count", 0) }
        try {
            InsightsApi.json("${jobPath(job)}/command", body(job.remoteRevision))
        } catch (e: InsightsFailure) {
            if (e.code == 409 && (e.message ?: "").contains("s-a schimbat")) {
                val r = InsightsApi.json(jobPath(job)); job.remoteRevision = r.int("revision")
                InsightsApi.json("${jobPath(job)}/command", body(job.remoteRevision))
            } else if (e.code == 409 || e.code == 404 || e.code == 410) { /* deja anulată / ștearsă */ } else throw e
        }
        job.commandId = job.controlRequest
        job.control = ""; job.controlRequest = ""
        ledger.saveJob(job)
    }

    private suspend fun analyzedReceipts(ledger: OrganizerLedger, job: OrgJob) {
        val items = ledger.items(job.id, setOf("batched")).filter { !it.hasReceipt("analyzed") }
        for (item in items) enqueue(item, receipt(item, "analyzed", reason = item.reason))
        ledger.saveItems(items)
        flushQueues(ledger, job)
    }

    // ─────────────────────────── Copii în cont (24 h) ───────────────────────────

    private fun readBytes(app: ForjaApp, uri: String, max: Long): ByteArray? = try {
        app.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (out.size() + n > max) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    private fun shaOf(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun uploads(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob) {
        val engine = CleanupEngine(app, app.prefs)
        val candidates = ledger.items(job.id, setOf("batched", "analyzed", "upload_pending"))
            .filter { !it.copyReceived && it.error.isBlank() }
            .sortedWith(compareByDescending<OrgItem> { it.flagged }.thenBy { it.seq })
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            if (!OrganizerSettings.siteOn(app)) throw Stopped()
            if (job.uploadedBytes + c.bytes > JOB_UPLOAD_BUDGET) { job.message = "Limita de copii a lucrării e atinsă; restul rămâne pe telefon."; mutex.withLock { ledger.saveJob(job) }; break }
            if (c.bytes > MAX_UPLOAD_BYTES) { c.error = "peste 25 MB — rămâne doar pe telefon"; mutex.withLock { ledger.saveItem(c) }; continue }
            if (c.photo && !c.mime.startsWith("image/")) { c.error = "videoclipurile nu urcă în cont"; mutex.withLock { ledger.saveItem(c) }; continue }
            job.state = "uploading"; job.message = "Urc ${cleanText(c.name, 60, "fișier")}"
            mutex.withLock {
                ledger.saveJob(job)
                if (!c.hasReceipt("upload_pending")) { enqueue(c, receipt(c, "upload_pending")); ledger.saveItem(c); flushQueues(ledger, job) }
            }
            val cur = ledger.item(job.id, c.id) ?: continue
            if (cur.state in setOf("needs_review", "skipped", "blocked")) continue
            val bytes = readBytes(app, cur.uri, MAX_UPLOAD_BYTES)
            if (bytes == null || bytes.isEmpty()) { cur.error = "Nu pot citi fișierul pentru copie."; mutex.withLock { ledger.saveItem(cur) }; continue }
            if (shaOf(bytes) != cur.sha) { cur.error = "Originalul s-a schimbat după analiză; copia nu s-a trimis."; mutex.withLock { ledger.saveItem(cur) }; continue }
            val headers = mapOf(
                "X-Device-ID" to job.device,
                "X-File-Kind" to if (cur.photo) "photo" else "file",
                "X-File-Name" to InsightsApi.header(cleanText(cur.name, 200, "Fișier")),
                "X-File-Folder" to InsightsApi.header(cur.folder.take(120).trimEnd('/', ' ')),
                "X-Media-Type" to cleanMime(cur.mime),
                "X-File-Sha256" to cur.sha,
                "x-organizer-job" to job.id,
                "x-organizer-item" to cur.id,
                "x-original-id" to cur.originalId,
                "x-original-version" to cur.sha
            )
            val resp = try {
                InsightsApi.upload("/v2/files/${cur.copyId}", bytes, "application/octet-stream", "PUT", headers)
            } catch (e: InsightsFailure) {
                when (e.code) {
                    429 -> { job.message = "Spațiul online e plin — reiau după ce expiră copiile vechi."; mutex.withLock { ledger.saveJob(job) }; return }
                    410 -> { cur.copyId = UUID.randomUUID().toString(); mutex.withLock { ledger.saveItem(cur) }; continue }
                    423 -> { job.state = "needs_access"; job.message = cleanText(e.message, 300); mutex.withLock { ledger.saveJob(job) }; throw Stopped() }
                    400, 403, 404, 409, 413, 422 -> { cur.error = cleanText(e.message, 300, "Copia a fost refuzată."); mutex.withLock { ledger.saveItem(cur) }; continue }
                    else -> throw e
                }
            }
            if (resp.str("sha256") != cur.sha || resp.long("bytes") != bytes.size.toLong()) {
                cur.error = "Serverul nu a confirmat copia integral."; mutex.withLock { ledger.saveItem(cur) }; continue
            }
            cur.copyReceived = true; cur.copyExpires = resp.long("expires_at")
            job.uploadedBytes += bytes.size
            mutex.withLock { ledger.saveItem(cur); ledger.saveJob(job) }
            if (cur.photo && !cur.thumbnailReceived) {
                try {
                    val thumb = engine.thumbnailJpeg(Uri.parse(cur.uri), 512, THUMB_MAX)
                    if (thumb != null && thumb.size <= THUMB_MAX) {
                        InsightsApi.upload("/v2/files/${cur.copyId}/thumbnail", thumb, "image/jpeg", "PUT", mapOf("X-Device-ID" to job.device))
                        cur.thumbnailReceived = true
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            mutex.withLock {
                enqueue(cur, receipt(cur, "uploaded", fileId = cur.copyId))
                ledger.saveItem(cur)
                flushQueues(ledger, job)
            }
        }
    }

    // ─────────────────────────── Analiza online (site) ───────────────────────────

    /** Cere site-ului analiza a ≤ 5 elemente cu copie și citește rezultatul. Întoarce câte au primit analiză. */
    suspend fun analyze(app: ForjaApp, jobId: String, ids: List<String>): Int = withContext(Dispatchers.IO) {
        val ledger = OrganizerLedger.get(app)
        val job = ledger.job(jobId) ?: throw InsightsFailure(404, "Lucrarea nu mai există.")
        if (job.mode != "online") throw InsightsFailure(403, "Pornește „Sugestii AI” înainte de scanare ca site-ul să poată analiza.")
        mutex.withLock { analyzeIds(ledger, job, ids.take(5)) }
    }

    private suspend fun analyzeIds(ledger: OrganizerLedger, job: OrgJob, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        InsightsApi.json("/insights/api/organizer-analysis", buildJsonObject {
            put("device", job.device); put("job", job.id); put("ids", JsonArray(ids.map { JsonPrimitive(it) })); put("consent", true)
        })
        val resp = InsightsApi.json("${jobPath(job)}/items?ids=${ids.joinToString(",")}")
        var n = 0
        for (row in resp.arr("items")?.mapNotNull { it as? JsonObject } ?: emptyList()) {
            val item = ledger.item(job.id, row.str("id")) ?: continue
            val a = row.obj("analysis") ?: continue
            item.analysisJson = json.encodeToString(
                SiteAnalysis.serializer(),
                SiteAnalysis(
                    destination = a.str("destination"), reason = a.str("reason"), confidence = a.str("confidence"),
                    status = a.str("status"), coverage = a.obj("coverage")?.str("status") ?: "",
                    deleteSuggested = a.obj("deletion")?.bool("suggested") ?: false
                )
            )
            if (!item.approved && row.str("destination").isNotBlank()) item.destination = row.str("destination")
            ledger.saveItem(item)
            n++
        }
        return n
    }

    private suspend fun analyzeAuto(ledger: OrganizerLedger, job: OrgJob) {
        val items = ledger.items(job.id, setOf("uploaded", "analyzed")).filter { it.copyReceived && it.analysisJson.isBlank() && it.error.isBlank() }.take(10)
        if (items.isEmpty()) return
        job.state = "analyzing"; job.message = "Site-ul analizează conținutul (${items.size})."; ledger.saveJob(job)
        for (page in items.chunked(5)) {
            currentCoroutineContext().ensureActive()
            try { analyzeIds(ledger, job, page.map { it.id }) } catch (e: InsightsFailure) {
                when {
                    e.code == 429 -> { job.message = "Bugetul zilnic de analiză AI e consumat; continui mâine."; ledger.saveJob(job); return }
                    e.code in 400..499 -> { page.forEach { it.error = cleanText(e.message, 300) }; ledger.saveItems(page) }
                    // Modelul indisponibil nu blochează restul lucrării: reluăm la următoarea sondare.
                    else -> { job.message = cleanText(e.message, 300, "Analiza de pe site nu răspunde acum; reiau."); ledger.saveJob(job); return }
                }
            } catch (e: CancellationException) { throw e } catch (e: IOException) {
                job.message = "Analiza de pe site nu răspunde acum; reiau."; ledger.saveJob(job); return
            }
        }
    }

    // ─────────────────────────── Aprobări ───────────────────────────

    private fun categoryFolder(reason: String): String = when (reason) {
        "duplicat identic" -> Category.DUPLICATE.folder
        "aproape identică" -> Category.SIMILAR.folder
        "captură de ecran" -> Category.SCREENSHOT.folder
        "neclară" -> Category.BLURRY.folder
        "mică" -> Category.TINY.folder
        "mare" -> Category.LARGE.folder
        "veche" -> "Vechi"
        else -> Category.DUPLICATE.folder
    }

    private fun evidenceOk(job: OrgJob, item: OrgItem): Boolean =
        if (job.mode == "local") item.sent.containsKey("analyzed") else item.copyReceived

    private suspend fun approvals(ledger: OrganizerLedger, job: OrgJob) {
        if (job.autoApply) {
            val undecided = ledger.items(job.id, setOf("analyzed", "uploaded")).filter { it.destination.isBlank() && it.error.isBlank() }
            for (item in undecided) {
                when {
                    job.mode != "local" -> continue
                    item.flagged -> item.destination = job.destination + "/" + categoryFolder(item.reason)
                    else -> enqueue(item, receipt(item, "skipped", message = "Nimic de mutat: fișierul e în regulă."))
                }
                ledger.saveItem(item)
            }
            val ready = ledger.items(job.id, setOf("analyzed", "uploaded")).filter { it.destination.isNotBlank() && it.error.isBlank() && evidenceOk(job, it) }
            for (item in ready) { item.approved = true; item.state = "approved" }
            ledger.saveItems(ready)
        } else {
            pullApprovals(ledger, job)
        }
        flushQueues(ledger, job)
    }

    /** Aprobările date din laptop (state „ready" cu approval) devin mutări de făcut aici. */
    private suspend fun pullApprovals(ledger: OrganizerLedger, job: OrgJob) {
        if (job.pulledRevision == job.syncRevision) return
        // Fără elemente „ready" pe site nu e nimic de citit (chitanțele noastre schimbă și ele sync_revision).
        if (job.remoteReady <= 0) { job.pulledRevision = job.syncRevision; ledger.saveJob(job); return }
        var after: String? = null
        var pages = 0
        do {
            val resp = InsightsApi.json("${jobPath(job)}/items" + (after?.let { "?after=$it" } ?: ""))
            for (row in resp.arr("items")?.mapNotNull { it as? JsonObject } ?: emptyList()) {
                if (row.str("state") != "ready" || row.obj("approval") == null) continue
                val local = ledger.item(job.id, row.str("id")) ?: continue
                if (local.state !in setOf("batched", "analyzed", "uploaded", "needs_review", "blocked", "upload_pending")) continue
                val dest = row.str("destination")
                if (dest.isBlank()) continue
                local.destination = dest; local.approved = true; local.state = "approved"; local.error = ""
                local.sent = local.sent + ("ready" to "site")
                ledger.saveItem(local)
            }
            after = resp.str("next_cursor").ifBlank { null }
            pages++
        } while (after != null && pages < 10)
        job.pulledRevision = job.syncRevision
        ledger.saveJob(job)
    }

    // ─────────────────────────── Mutări ───────────────────────────

    private fun mediaFrom(item: OrgItem): MediaItem = MediaItem(
        id = item.mediaId, uri = Uri.parse(item.uri), name = item.name, mime = item.mime, sizeBytes = item.bytes,
        width = 0, height = 0, dateTakenMs = 0L, dateAddedMs = 0L, dateModifiedMs = item.modifiedAt,
        bucketId = 0L, bucketName = "", relativePath = item.relativePath, isFavorite = false
    )

    private fun docFrom(item: OrgItem): DocItem = DocItem(
        uri = Uri.parse(item.uri), documentId = item.docId, parentUri = Uri.parse(item.parentUri.ifBlank { item.uri }),
        path = item.docPath.ifBlank { item.name }, name = item.name, mime = item.mime, sizeBytes = item.bytes,
        lastModified = item.modifiedAt, flags = item.docFlags
    )

    private fun currentRelativePath(app: ForjaApp, uri: String): String? = try {
        if (Build.VERSION.SDK_INT < 29) null
        else app.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else null
        }
    } catch (_: Exception) { null }

    private sealed class Physical {
        data class Moved(val uri: String) : Physical()
        data class Copied(val uri: String) : Physical()
        data class Failed(val reason: String) : Physical()
    }

    private suspend fun physicalMove(app: ForjaApp, job: OrgJob, item: OrgItem, dest: String): Physical {
        return if (item.photo) {
            val engine = CleanupEngine(app, app.prefs)
            val rel = "Pictures/$dest/"
            val r = engine.moveToRelativePath(listOf(mediaFrom(item)), rel)
            val now = currentRelativePath(app, item.uri)
            when {
                r.moved == 1 || (now != null && now.trim('/') == rel.trim('/')) -> Physical.Moved(item.uri)
                r.recoverable != null -> Physical.Failed("Android cere acordul pentru această poză.")
                else -> Physical.Failed(r.errors.firstOrNull() ?: "Poza nu s-a mutat.")
            }
        } else {
            val tree = job.tree.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return Physical.Failed("Dosarul de documente nu mai e autorizat.")
            when (val o = DocumentOrganizer(app, app.prefs).moveToPath(tree, docFrom(item), dest)) {
                is MoveOutcome.Moved -> Physical.Moved(o.newUri.toString())
                is MoveOutcome.Copied -> if (o.originalKept) Physical.Copied(o.newUri.toString()) else Physical.Moved(o.newUri.toString())
                is MoveOutcome.Failed -> Physical.Failed(o.reason)
            }
        }
    }

    /**
     * O mutare urmărită de site: ready → applying (intenția) → mutarea fizică → moved/copied/needs_review cu
     * amprenta țintei. Cere destinația și aprobarea (a noastră, a site-ului sau auto_apply).
     */
    private suspend fun trackedMove(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob, itemId: String) {
        var cur = ledger.item(job.id, itemId) ?: return
        val dest = cur.destination
        if (dest.isBlank() || cur.terminal) return
        if (!cur.sent.containsKey("ready")) {
            if (!cur.hasReceipt("ready")) { enqueue(cur, receipt(cur, "ready", destination = dest)); ledger.saveItem(cur) }
            flushQueues(ledger, job)
            cur = ledger.item(job.id, itemId) ?: return
            if (!cur.sent.containsKey("ready")) return
        }
        if (!cur.intentSaved) {
            if (!cur.hasReceipt("applying")) { enqueue(cur, receipt(cur, "applying", destination = dest)); ledger.saveItem(cur) }
            flushQueues(ledger, job)
            cur = ledger.item(job.id, itemId) ?: return
            if (!cur.intentSaved) return
        }
        job.state = "applying"; job.message = "Mut ${cleanText(cur.name, 60, "fișier")} în $dest"; ledger.saveJob(job)
        recordOutcome(app, ledger, job, cur, physicalMove(app, job, cur, dest))
    }

    private suspend fun recordOutcome(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob, cur: OrgItem, outcome: Physical) {
        val dest = cur.destination
        when (outcome) {
            is Physical.Moved, is Physical.Copied -> {
                val uri = if (outcome is Physical.Moved) outcome.uri else (outcome as Physical.Copied).uri
                val sha = try {
                    if (cur.photo) CleanupEngine(app, app.prefs).sha256(Uri.parse(uri)) else DocumentOrganizer(app, app.prefs).sha256(Uri.parse(uri))
                } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                if (sha == cur.sha) {
                    cur.resultUri = uri; cur.targetSha = sha
                    if (!cur.photo) ledger.alias(job.owner, cur.originalId, uri)
                    val st = if (outcome is Physical.Moved) "moved" else "copied_pending_removal"
                    cur.state = st; cur.error = ""
                    enqueue(cur, receipt(cur, st, destination = dest, targetSha = sha))
                } else {
                    cur.state = "needs_review"; cur.error = "Ținta nu are aceeași amprentă; verifică fișierul."
                    enqueue(cur, receipt(cur, "needs_review", message = cur.error))
                }
            }
            is Physical.Failed -> {
                cur.state = "needs_review"; cur.error = outcome.reason
                enqueue(cur, receipt(cur, "needs_review", message = outcome.reason))
            }
        }
        ledger.saveItem(cur)
        flushQueues(ledger, job)
    }

    private suspend fun moves(app: ForjaApp, ledger: OrganizerLedger, job: OrgJob) {
        val pending = ledger.items(job.id, setOf("approved", "ready", "applying", "needs_permission"))
        if (pending.isEmpty()) return
        val photos = pending.filter { it.photo }
        val docs = pending.filter { !it.photo }
        if (photos.isNotEmpty()) {
            // Acordul de scriere în galerie cere un Activity: din fundal doar anunțăm, mutarea se face din ecran.
            mutex.withLock {
                for (item in photos) {
                    if (item.state == "needs_permission") continue
                    item.state = "needs_permission"
                    if (!screenVisible && !item.hasReceipt("needs_review") && !item.intentSaved) {
                        enqueue(item, receipt(item, "needs_review", message = "Așteaptă o atingere pe telefon pentru mutarea pozelor."))
                    }
                    ledger.saveItem(item)
                }
                flushQueues(ledger, job)
                if (!screenVisible && !job.touchNotified) { notifyTouch(app, photos.size); job.touchNotified = true; ledger.saveJob(job) }
            }
        }
        docs.forEachIndexed { i, d ->
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                // Starea de pe site (pauză/anulare) se reverifică la fiecare 10 documente, nu la fiecare mutare.
                if (i % 10 == 0) remoteCheck(ledger, job)
                trackedMove(app, ledger, job, d.id)
            }
        }
    }

    private fun finish(ledger: OrganizerLedger, job: OrgJob) {
        if (job.state in setOf("cancelled", "paused", "needs_access")) return
        val c = ledger.counts(job.id)
        val total = ledger.count(job.id)
        val moved = c["moved"] ?: 0
        val review = (c["needs_review"] ?: 0) + (c["blocked"] ?: 0)
        val touch = c["needs_permission"] ?: 0
        val open = total - moved - (c["skipped"] ?: 0) - (c["unavailable"] ?: 0) - review - touch
        val items = ledger.items(job.id)
        val copiesLeft = if (job.upload) items.count { !it.copyReceived && it.error.isBlank() && it.state in setOf("batched", "analyzed", "upload_pending") } else 0
        job.state = when {
            touch > 0 -> "needs_permission"
            copiesLeft > 0 -> "uploading"
            open > 0 && (job.autoApply || job.mode == "manual") -> "ready"
            open > 0 -> "synced"
            review > 0 -> "needs_review"
            else -> "complete"
        }
        job.message = buildString {
            append("$total ${if (total == 1) "element" else "elemente"} pe site")
            val up = items.count { it.copyReceived }
            if (up > 0) append(" · $up ${if (up == 1) "copie" else "copii"} 24 h")
            if (copiesLeft > 0) append(" · $copiesLeft de urcat (reiau)")
            if (moved > 0) append(" · $moved mutate")
            if (review > 0) append(" · $review de verificat")
            if (touch > 0) append(" · $touch așteaptă atingerea ta")
        }
        ledger.saveJob(job)
    }

    /**
     * Site-ul permite cel mult 10 lucrări active pe telefon: la fiecare scanare nouă păstrăm cele mai
     * recente 3 lucrări pornite din telefon pentru aceeași sursă și le oprim pe celelalte (copiile expiră oricum).
     */
    private fun retireOld(app: ForjaApp, ledger: OrganizerLedger, uid: String, source: String, keepId: String) {
        val mine = ledger.jobs(uid).filter { it.origin == "phone" && it.source == source && it.id != keepId && it.active }
        for (old in mine.drop(2)) {
            if (old.registered) { old.control = "cancel"; old.controlRequest = UUID.randomUUID().toString() }
            old.state = "cancelled"; old.message = "Înlocuită de o scanare mai nouă."
            ledger.saveJob(old)
            if (old.registered) schedule(app, old.id)
        }
    }

    // ─────────────────────────── Mutări pornite din ecran (poze / documente) ───────────────────────────

    /**
     * Înainte ca ecranul să mute local: aprobăm pe site (POST approve) elementele cu dovezi și trimitem
     * ready + applying. Întoarce elementele urmărite (uri → element), pentru [afterUserMove].
     */
    suspend fun beforeUserMove(app: ForjaApp, jobId: String, uris: List<String>, folder: String): Map<String, OrgItem> = withContext(Dispatchers.IO) {
        if (!enabled(app)) return@withContext emptyMap()
        val ledger = OrganizerLedger.get(app)
        mutex.withLock {
            val job = ledger.job(jobId) ?: return@withLock emptyMap()
            if (!job.registered || job.stopped) return@withLock emptyMap()
            val dest = cleanPath("${job.destination}/$folder", 200) ?: return@withLock emptyMap()
            // Elementele cu intenția deja trimisă (reluare după dialogul Android) rămân urmărite pentru chitanța finală.
            val items = uris.mapNotNull { ledger.itemByUri(jobId, it) }.filter { it.batched && !it.terminal && it.error.isBlank() }
            try {
                val fresh = items.filter { !it.intentSaved }
                val approvable = fresh.filter { evidenceOk(job, it) && !(it.approved && it.destination == dest) }
                if (approvable.isNotEmpty()) approve(ledger, job, approvable, dest)
                val tracked = fresh.filter { it.approved && it.destination == dest }
                for (t in tracked) {
                    var cur = ledger.item(jobId, t.id) ?: continue
                    if (!cur.sent.containsKey("ready")) {
                        if (!cur.hasReceipt("ready")) { enqueue(cur, receipt(cur, "ready", destination = dest)); ledger.saveItem(cur) }
                        flushQueues(ledger, job)
                        cur = ledger.item(jobId, t.id) ?: continue
                        if (!cur.sent.containsKey("ready")) continue
                    }
                    if (!cur.intentSaved) {
                        if (!cur.hasReceipt("applying")) { enqueue(cur, receipt(cur, "applying", destination = dest)); ledger.saveItem(cur) }
                        flushQueues(ledger, job)
                    }
                }
            } catch (e: Stopped) {
                return@withLock emptyMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job.message = cleanText(e.message, 200, "Site-ul nu a răspuns; mutarea rămâne locală.")
                ledger.saveJob(job)
            }
            items.mapNotNull { ledger.item(jobId, it.id) }.filter { it.intentSaved && it.destination == dest && !it.terminal }.associateBy { it.uri }
        }
    }

    private suspend fun approve(ledger: OrganizerLedger, job: OrgJob, items: List<OrgItem>, dest: String) {
        for (page in items.chunked(100)) {
            val requestId = UUID.randomUUID().toString()
            fun body(rev: Int) = buildJsonObject {
                put("request_id", requestId); put("revision", rev); put("confirm", true)
                put("items", JsonArray(page.map { buildJsonObject { put("id", it.id); put("destination", dest) } }))
            }
            val r = try {
                InsightsApi.json("${jobPath(job)}/approve", body(job.remoteRevision))
            } catch (e: InsightsFailure) {
                if (e.code == 409) {
                    val remote = InsightsApi.json(jobPath(job)); job.remoteRevision = remote.int("revision"); ledger.saveJob(job)
                    try { InsightsApi.json("${jobPath(job)}/approve", body(job.remoteRevision)) } catch (e2: InsightsFailure) {
                        if (e2.code in setOf(400, 403, 409)) { page.forEach { it.error = cleanText(e2.message, 300) }; ledger.saveItems(page); continue } else throw e2
                    }
                } else if (e.code in setOf(400, 403)) { page.forEach { it.error = cleanText(e.message, 300) }; ledger.saveItems(page); continue } else throw e
            }
            for (item in page) { item.approved = true; item.destination = dest; item.state = "approved"; item.sent = item.sent + ("ready" to "site") }
            ledger.saveItems(page)
            applyJobResponse(ledger, job, r)
        }
    }

    /**
     * După mutarea locală: chitanțe moved (cu amprenta țintei), copied_pending_removal sau needs_review.
     * `results`: uri → uri-ul nou (null = nu s-a mutat); un uri absent nu are încă verdict (ex. așteaptă
     * acordul Android și va fi reluat) și rămâne „applying". `copied`: uri-uri copiate, cu originalul păstrat.
     */
    suspend fun afterUserMove(
        app: ForjaApp, jobId: String, tracked: Map<String, OrgItem>, results: Map<String, String?>, copied: Set<String> = emptySet()
    ) = withContext(Dispatchers.IO) {
        if (tracked.isEmpty()) return@withContext
        val ledger = OrganizerLedger.get(app)
        mutex.withLock {
            val job = ledger.job(jobId) ?: return@withLock
            try {
                for ((uri, t) in tracked) {
                    if (!results.containsKey(uri)) continue
                    val cur = ledger.item(jobId, t.id) ?: continue
                    if (cur.terminal || cur.state == "copied_pending_removal") continue
                    val newUri = results[uri]
                    val outcome = when {
                        newUri == null -> Physical.Failed("Mutarea locală nu a reușit.")
                        uri in copied -> Physical.Copied(newUri)
                        else -> Physical.Moved(newUri)
                    }
                    recordOutcome(app, ledger, job, cur, outcome)
                }
                finish(ledger, job)
            } catch (e: Stopped) { } catch (e: CancellationException) { throw e } catch (e: Exception) {
                job.message = cleanText(e.message, 200, "Chitanțele se trimit la următoarea sincronizare."); ledger.saveJob(job)
            }
        }
    }

    /** Acordul Android refuzat după ce intenția a plecat: elementele rămase „applying" devin de verificat pe site. */
    suspend fun reportUnmoved(app: ForjaApp, jobId: String, uris: List<String>, reason: String) = withContext(Dispatchers.IO) {
        val ledger = OrganizerLedger.get(app)
        mutex.withLock {
            val job = ledger.job(jobId) ?: return@withLock
            try {
                for (uri in uris) {
                    val cur = ledger.itemByUri(jobId, uri) ?: continue
                    if (!cur.intentSaved || cur.state != "applying") continue
                    recordOutcome(app, ledger, job, cur, Physical.Failed(reason))
                }
                finish(ledger, job)
            } catch (e: Stopped) { } catch (e: CancellationException) { throw e } catch (e: Exception) {
                job.message = cleanText(e.message, 200, "Chitanțele se trimit la următoarea sincronizare."); ledger.saveJob(job)
            }
        }
    }

    /** Pozele aprobate din site care așteaptă acordul Android (toate lucrările active ale contului). */
    fun pendingTouch(app: ForjaApp): List<OrgItem> {
        val uid = app.auth.currentUid ?: return emptyList()
        val ledger = OrganizerLedger.get(app)
        return ledger.jobs(uid).filter { it.active && !it.stopped }.flatMap { j -> ledger.items(j.id, setOf("needs_permission")).filter { it.photo } }
    }

    /** După acordul din dialogul de sistem: mutările urmărite pentru pozele date. */
    suspend fun applyAfterPermission(app: ForjaApp, items: List<OrgItem>) = withContext(Dispatchers.IO) {
        val ledger = OrganizerLedger.get(app)
        for ((jobId, group) in items.groupBy { it.job }) {
            mutex.withLock {
                val job = ledger.job(jobId) ?: return@withLock
                try {
                    remoteCheck(ledger, job)
                    for (item in group) {
                        currentCoroutineContext().ensureActive()
                        val cur = ledger.item(jobId, item.id) ?: continue
                        if (cur.state != "needs_permission") continue
                        trackedMove(app, ledger, job, cur.id)
                    }
                    finish(ledger, job)
                } catch (e: Stopped) { } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    job.message = cleanText(e.message, 200, "Reiau la următoarea sincronizare."); ledger.saveJob(job)
                }
            }
        }
    }

    /** Refuz în dialogul de sistem: elementele rămân de verificat (fără mutare). */
    suspend fun declinePermission(app: ForjaApp, items: List<OrgItem>) = withContext(Dispatchers.IO) {
        val ledger = OrganizerLedger.get(app)
        mutex.withLock {
            for (item in items) {
                val cur = ledger.item(item.job, item.id) ?: continue
                if (cur.state != "needs_permission") continue
                cur.state = "needs_review"; cur.error = "Ai refuzat acordul Android pentru această mutare."
                if (!cur.hasReceipt("needs_review")) enqueue(cur, receipt(cur, "needs_review", message = cur.error))
                ledger.saveItem(cur)
            }
        }
        items.map { it.job }.distinct().forEach { schedule(app, it) }
    }

    // ─────────────────────────── Sondarea site-ului ───────────────────────────

    /** GET …/jobs: comenzi noi din laptop (continue/pause/cancel), aprobări (sync_revision), lucrări șterse. */
    suspend fun poll(app: ForjaApp) = withContext(Dispatchers.IO) {
        if (!enabled(app)) return@withContext
        val uid = app.auth.currentUid ?: return@withContext
        val grant = OrganizerSettings.prefs(app).getString("grant_id", "") ?: ""
        if (grant.isBlank()) return@withContext
        val ledger = OrganizerLedger.get(app)
        val device = device(app)
        val resp = try { InsightsApi.json(jobsPath(device)) } catch (e: InsightsFailure) { if (e.code == 409 || e.code == 404) return@withContext else throw e }
        val remoteJobs = resp.arr("jobs")?.mapNotNull { it as? JsonObject } ?: emptyList()
        val seen = HashSet<String>()
        val tree = DocumentOrganizer(app, app.prefs).persistedTree()?.toString() ?: ""
        val toSchedule = HashSet<String>()
        mutex.withLock {
            for (r in remoteJobs) {
                val id = r.str("id"); if (id.isBlank()) continue
                seen += id
                val cmd = r.obj("command")
                val local = ledger.job(id)
                if (local == null) {
                    if (r.str("grant_id") != grant) continue
                    if (cmd == null || cmd.str("action") != "continue" || cmd.str("status") == "complete") continue
                    val source = r.str("source"); if (source !in setOf("photos", "files")) continue
                    val sourceId = r.str("source_id")
                    if (source == "files" && sourceId.isNotBlank() && sourceId != OrganizerSettings.sourceId(app, "files", tree)) continue
                    val scope = r.obj("scope")
                    val mode = r.str("mode").ifBlank { "local" }
                    val job = OrgJob(
                        id = id, owner = uid, device = device, source = source, origin = "site",
                        destination = r.str("destination").ifBlank { "FORJA" }, mode = mode, upload = mode != "local",
                        autoApply = r.bool("auto_apply"), createdAt = System.currentTimeMillis(), grantId = grant,
                        tree = if (source == "files") tree else "", folder = scope?.str("folder") ?: "", recursive = scope?.bool("recursive") ?: true,
                        count = cmd.int("count"), sourceId = sourceId.ifBlank { null }, registered = true, commandId = cmd.str("request_id"),
                        remoteRevision = r.int("revision"), syncRevision = r.long("sync_revision"),
                        message = "Lucrare primită din laptop."
                    )
                    if (source == "files" && tree.isBlank()) { job.state = "needs_access"; job.message = "Alege dosarul de documente în aplicație pentru lucrarea din laptop." }
                    ledger.saveJob(job)
                    OrganizerSettings.setCurrentJob(app, id)
                    toSchedule += id
                    continue
                }
                local.remoteRevision = r.int("revision")
                var changed = false
                when (r.str("state")) {
                    "paused" -> if (local.state != "paused" && local.state != "cancelled") { local.state = "paused"; local.message = "Pusă pe pauză din site."; changed = true }
                    "cancelled" -> if (local.state != "cancelled") { local.state = "cancelled"; local.message = "Oprită din site."; changed = true }
                    "access_needed" -> if (local.state != "needs_access" && local.state != "cancelled") { local.state = "needs_access"; local.message = "Site-ul cere reactivarea accesului."; changed = true }
                }
                if (cmd != null && cmd.str("request_id") != local.commandId && cmd.str("request_id").isNotBlank()) {
                    when (cmd.str("action")) {
                        "continue" -> if (cmd.str("status") != "complete") {
                            local.commandId = cmd.str("request_id"); local.count = cmd.int("count")
                            if (local.origin == "site") { local.inventoryDone = false; local.inventorySent = false }
                            local.state = "queued"; local.message = "Continuare cerută din laptop."; local.touchNotified = false
                            changed = true; toSchedule += id
                        }
                        "pause" -> { local.commandId = cmd.str("request_id"); if (local.state != "cancelled") { local.state = "paused"; local.message = "Pusă pe pauză din site." }; changed = true }
                        "cancel" -> { local.commandId = cmd.str("request_id"); local.state = "cancelled"; local.message = "Oprită din site."; changed = true }
                    }
                }
                val sync = r.long("sync_revision")
                val ready = r.obj("counters")?.int("ready") ?: 0
                if (ready != local.remoteReady) { local.remoteReady = ready; changed = true }
                if (sync != local.syncRevision) {
                    local.syncRevision = sync; changed = true
                    if (local.active && !local.stopped && (ready > 0 || local.autoApply)) toSchedule += id
                }
                if (changed) ledger.saveJob(local)
            }
            for (j in ledger.jobs(uid)) {
                if (j.registered && j.id !in seen && j.active) {
                    j.state = "cancelled"; j.message = "Lucrarea a fost ștearsă din site."; ledger.saveJob(j)
                }
            }
        }
        toSchedule.forEach { schedule(app, it) }
    }

    // ─────────────────────────── Stare pentru ecran ───────────────────────────

    fun status(app: ForjaApp): OrganizerStatus? {
        val uid = app.auth.currentUid ?: return null
        val ledger = OrganizerLedger.get(app)
        val current = OrganizerSettings.currentJob(app)
        val job = (if (current.isNotBlank()) ledger.job(current)?.takeIf { it.owner == uid } else null) ?: ledger.jobs(uid, 1).firstOrNull() ?: return null
        val c = ledger.counts(job.id)
        val items = ledger.items(job.id)
        return OrganizerStatus(
            jobId = job.id, source = job.source, origin = job.origin, state = job.state, message = job.message, mode = job.mode,
            total = items.size, moved = c["moved"] ?: 0, uploaded = items.count { it.copyReceived },
            analyzed = items.count { it.analysisJson.isNotBlank() }, review = (c["needs_review"] ?: 0) + (c["blocked"] ?: 0),
            needsTouch = c["needs_permission"] ?: 0
        )
    }

    /** Ce știe site-ul despre elementele unei lucrări, pe uri (pentru panoul AI și previzualizare). */
    fun hints(app: ForjaApp, jobId: String): Map<String, SiteHint> {
        val ledger = OrganizerLedger.get(app)
        return ledger.items(jobId).associate { it.uri to SiteHint(
            itemId = it.id, state = it.state, uploaded = it.copyReceived, destination = it.destination,
            analysis = it.analysisJson.takeIf { a -> a.isNotBlank() }?.let { a -> try { json.decodeFromString(SiteAnalysis.serializer(), a) } catch (_: Exception) { null } },
            error = it.error
        ) }
    }

    // ─────────────────────────── Notificare ───────────────────────────

    private fun notifyTouch(ctx: Context, count: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(ROUTE_EXTRA, "cleanup")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, NOTIF_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = "$count ${if (count == 1) "poză aprobată" else "poze aprobate"} din panoul online. Deschide Curățenie și permite mutarea."
            val n = NotificationCompat.Builder(ctx, "cleanup")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Organizarea din laptop așteaptă o atingere")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
        } catch (_: Exception) { }
    }
}

/** Procesarea unei lucrări (WorkManager, unic per lucrare). */
class OrganizerJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val app = ForjaApp.from(applicationContext)
        if (!OrganizerJobs.enabled(app)) return Result.success()
        return try {
            if (OrganizerJobs.process(app, id)) OrganizerJobs.schedule(applicationContext, id, append = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (OrganizerJobs.retryable(e) && runAttemptCount < 6) Result.retry() else Result.success()
        }
    }
}

/** Sondarea site-ului la 15 min: comenzi din laptop, aprobări, lucrări noi. */
class OrganizerPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = ForjaApp.from(applicationContext)
        if (!OrganizerJobs.enabled(app)) return Result.success()
        try { OrganizerJobs.poll(app) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { OrganizerJobs.resumeActive(app) } catch (_: Exception) { }
        return Result.success()
    }
}
