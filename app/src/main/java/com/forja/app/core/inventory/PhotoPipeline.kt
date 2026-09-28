package com.forja.app.core.inventory

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.location.Address
import android.location.Geocoder
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import com.forja.app.core.cleanup.CleanupCursor
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.CleanupReport
import com.forja.app.core.cleanup.CleanupScope
import com.forja.app.core.cleanup.ScanProgress
import com.forja.app.core.cleanup.ScopeKind
import com.forja.app.core.data.Prefs
import com.forja.app.core.network.providerLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

// ═══════════════ Inventar 4.3 — pozele (+ video) din galerie ═══════════════
// 1. Scanare: interogarea MediaStore pe scop + detectorii CleanupEngine (duplicate SHA-256, similare dHash, capturi,
//    neclare, mici) → DeleteReason; din duplicate/similare, cea mai bună rămâne în dosar.
// 2. Grupare: capturi / primite (pe lună) / camera pe evenimente (pauză > 4 h, salt GPS > 25 km pe ≤ 3 mostre EXIF),
//    evenimentele mici unite pe lună; locul prin Geocoder pe centru.
// 3–4. Reprezentanți ≤ 6 (384 px) → /v1/organize/clusters (≤ 4 grupuri / apel, 2 în paralel, o reîncercare);
//    fără AI sau la eșec: nume de rezervă „<Temă|Diverse> · <lună an>”, rularea merge mai departe.
// 5. Planul (InvAssemble.photos) + Inventory.ready(). Punct de reluare după fiecare stagiu (InventoryStore).

internal object PhotoPipeline {

    suspend fun run(ctl: RunCtl) {
        val ctx = ctl.ctx
        val zone = ZoneId.systemDefault()
        if (!MediaAccess.canRead(ctx)) throw InvFailure("Dă acces la galerie, apoi pornește din nou.")

        val items: List<ItemRec> = withContext(Dispatchers.IO) { InventoryStore.readItems(ctx, ctl.runId) } ?: run {
            ctl.enter(InvStage.Scanning, "Citesc galeria")
            val scanned = scan(ctl, zone)
            ctl.checkAlive()
            if (scanned.isNotEmpty()) withContext(Dispatchers.IO) { InventoryStore.writeItems(ctx, ctl.runId, scanned) }
            scanned
        }
        if (items.isEmpty()) throw InvFailure("Nu am găsit poze de pus în ordine.")
        val byId = items.associateBy { it.id }

        val clusters: List<ClusterRec> = withContext(Dispatchers.IO) { InventoryStore.readClusters(ctx, ctl.runId) } ?: run {
            ctl.enter(InvStage.Grouping, "Grupez pe evenimente")
            val grouped = group(ctl, items, zone)
            ctl.checkAlive()
            withContext(Dispatchers.IO) { InventoryStore.writeClusters(ctx, ctl.runId, grouped) }
            grouped
        }

        ctl.enter(InvStage.Naming, "Dau nume dosarelor")
        val names = name(ctl, byId, clusters, zone)
        ctl.checkAlive()

        val doc = InvAssemble.photos(ctl.meta.createdAt, byId, clusters, names.clusters, zone).copy(provider = names.provider)
        Inventory.ready(ctl, doc, byId)
    }

    // ─────────────────────────── 1. Scanare ───────────────────────────

    private suspend fun scan(ctl: RunCtl, zone: ZoneId): List<ItemRec> {
        val ctx = ctl.ctx
        val meta = ctl.meta
        val lastN = meta.lastN?.takeIf { it > 0 && meta.bucketId == null }
        val base = MediaQuery.query(ctx, meta.bucketId, lastN)
        ctl.checkAlive()
        if (base.isEmpty()) return base

        // Estimarea (ponderile procentului global) și „cutiile” provizorii, din metadate, înainte de analiza grea.
        val ai = AiGate.likely(ctl.app)
        val prov = InvAssemble.provisional(base, meta.createdAt, zone)
        ctl.setEstimates(
            InvRules.scanSec(base.size),
            InvRules.groupSec(base.size),
            if (ai) InvRules.aiSec(InvRules.photoCalls(prov.count { it.needsAi })) else 1
        )
        val top = prov.sortedWith(compareByDescending<ProvGroup> { it.ids.size }).take(5)
        val binOf = HashMap<Long, Int>()
        top.forEachIndexed { i, g -> g.ids.forEach { id -> mediaIdOf(id)?.let { binOf[it] = i } } }
        val binCounts = IntArray(top.size)
        ctl.bins = List(top.size) { BinTick(null, 0) }
        ctl.meta = ctl.meta.copy(scanAttempts = ctl.meta.scanAttempts + 1)
        ctl.saveMeta()

        val prefs = ctl.app.prefs
        val engine = CleanupEngine(ctx, prefs)
        val scope = when {
            meta.bucketId != null -> CleanupScope(ScopeKind.ALBUM, bucketId = meta.bucketId, includeVideos = true)
            lastN != null -> CleanupScope(ScopeKind.NEXT_BATCH, batchSize = lastN, includeVideos = true)
            else -> CleanupScope(ScopeKind.WHOLE_GALLERY, includeVideos = true)
        }
        // „Ultimele N”: motorul merge pe _ID crescător de la cursorul lui; îl punem chiar înaintea celor N și îl
        // punem la loc după (cursorul vechiului ecran de curățenie nu se pierde).
        val backup = if (lastN != null) EngineCursor.prime(prefs, scope, base.first().mediaId - 1) else null
        // A treia pornire a aceleiași scanări (proces ucis de două ori): continuăm de la cursorul motorului în loc să
        // luăm totul de la capăt; detectorii acoperă atunci doar partea rămasă, restul se grupează fără verdict local.
        val resume = backup != null || ctl.meta.scanAttempts >= 3
        val t0 = SystemClock.elapsedRealtime()
        var report: CleanupReport? = null

        fun tick(done: Int, total: Int, uri: Uri?) {
            if (uri != null && !isVideoUri(uri)) ctl.addRecent(uri)
            val bin = uri?.let { safeId(it) }?.let { binOf[it] }
            if (bin != null) {
                binCounts[bin]++
                ctl.bins = List(top.size) { BinTick(null, binCounts[it]) }
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            val eta = if (done >= 20 && elapsed >= 4_000) {
                val perSec = done * 1000.0 / elapsed
                ((total - done) / perSec).roundToInt() + ctl.meta.estGroupSec + ctl.meta.estAiSec
            } else null
            ctl.report(
                InvStage.Scanning, if (total > 0) done.toDouble() / total else 0.0, eta,
                "Scanez · ${InvText.thousands(done)} din ${InvText.thousands(total)}"
            )
        }

        try {
            engine.scan(scope, resume).collect { p ->
                when (p) {
                    is ScanProgress.Inventory -> ctl.report(InvStage.Scanning, 0.0, null, "Citesc galeria · ${InvText.thousands(p.count)}")
                    is ScanProgress.Hashing -> tick(p.done, p.total, p.currentUri)
                    is ScanProgress.Visual -> tick(p.done, p.total, p.currentUri)
                    is ScanProgress.Done -> report = p.report
                }
            }
        } finally {
            if (backup != null) withContext(NonCancellable) { EngineCursor.restore(prefs, backup) }
        }
        val r = report ?: throw InvFailure("Scanarea nu s-a terminat. Încearcă din nou.")
        ctl.report(InvStage.Scanning, 1.0, ctl.meta.estGroupSec + ctl.meta.estAiSec, null, force = true)
        return mapReport(base, r)
    }

    /** Raportul CleanupEngine → DeleteReason pe element; păstrătorii grupurilor și favoritele nu merg la gunoi. */
    private fun mapReport(base: List<ItemRec>, r: CleanupReport): List<ItemRec> {
        val reason = HashMap<Long, DeleteReason>()
        val keepers = HashSet<Long>()
        r.duplicates.forEach { g -> keepers += g.keeper.id; g.copies.forEach { reason[it.id] = DeleteReason.Duplicate } }
        r.similar.forEach { g -> keepers += g.keeper.id; g.others.forEach { if (it.id !in reason) reason[it.id] = DeleteReason.Similar } }
        r.blurry.forEach { f -> if (f.item.id !in reason) reason[f.item.id] = DeleteReason.Blurry }
        r.tiny.forEach { if (it.id !in reason) reason[it.id] = DeleteReason.Tiny }
        keepers.forEach { reason.remove(it) }
        val covered = r.scanned.mapTo(HashSet()) { it.id }
        val shots = r.screenshots.mapTo(HashSet()) { it.id }
        return base.map { rec ->
            if (rec.mediaId !in covered) rec
            else rec.copy(
                screenshot = rec.screenshot || rec.mediaId in shots,
                localReason = if (rec.favorite) null else reason[rec.mediaId]
            )
        }
    }

    // ─────────────────────────── 2. Grupare ───────────────────────────

    private suspend fun group(ctl: RunCtl, items: List<ItemRec>, zone: ZoneId): List<ClusterRec> {
        val ctx = ctl.ctx
        val streams = InvAssemble.streams(items)
        val events = Clustering.splitByGap(streams.camera, { it.takenAt }, InvRules.EVENT_GAP_MS)
        val gps = MediaAccess.canReadLocation(ctx)
        val work = if (gps) events.count { InvAssemble.live(it) >= InvRules.SMALL_EVENT } else 0
        val placed = ArrayList<Placed>(events.size)
        var done = 0
        val t0 = SystemClock.elapsedRealtime()
        ctl.report(InvStage.Grouping, 0.0, ctl.meta.estGroupSec + ctl.meta.estAiSec, "Grupez pe evenimente", force = true)
        for (ev in events) {
            ctl.checkAlive()
            if (!gps || InvAssemble.live(ev) < InvRules.SMALL_EVENT) { placed += Placed(ev, null); continue }
            // ≤ 3 mostre (poze, nu video), răspândite în timp: EXIF prin setRequireOriginal + ACCESS_MEDIA_LOCATION.
            val imageIdx = ev.indices.filter { !ev[it].video }
            val picks = Clustering.spread(imageIdx.size, InvRules.GPS_SAMPLES).map { imageIdx[it] }
            val located = ArrayList<Pair<Int, LatLon>>()
            for (i in picks) {
                val loc = withContext(Dispatchers.IO) { MediaAccess.location(ctx, ev[i]) }
                if (loc != null) located += i to loc
                ctl.addRecent(Uri.parse(ev[i].uri))
            }
            val cuts = Clustering.jumpCuts(LongArray(ev.size) { ev[it].takenAt }, located, InvRules.JUMP_KM)
            var start = 0
            for (part in Clustering.splitAt(ev, cuts)) {
                val end = start + part.size
                placed += Placed(part, Clustering.centroid(located.filter { it.first in start until end }.map { it.second }))
                start = end
            }
            done++
            val elapsed = SystemClock.elapsedRealtime() - t0
            val eta = if (done >= 5) ((work - done) * elapsed / done / 1000).toInt() + ctl.meta.estAiSec else null
            ctl.report(InvStage.Grouping, done.toDouble() / (work + 1), eta, "Grupez · $done din $work")
        }
        // Locul: Geocoder-ul Android pe centrul evenimentelor mari (poate lipsi — atunci grupul rămâne fără loc).
        val geo = Geo(ctx)
        val named = ArrayList<Placed>(placed.size)
        for (p in placed) {
            val center = p.center
            if (center != null && InvAssemble.live(p.items) >= InvRules.SMALL_EVENT) {
                ctl.checkAlive()
                named += p.copy(place = geo.place(center))
            } else {
                named += p
            }
        }
        val clusters = InvAssemble.clusters(named, streams.received, streams.screens, ctl.meta.createdAt, zone)
        ctl.report(InvStage.Grouping, 1.0, ctl.meta.estAiSec, "${InvText.count(clusters.size, "grup", "grupuri")}", force = true)
        return clusters
    }

    // ─────────────────────────── 3–4. Nume de la AI ───────────────────────────

    private class BatchResult(val names: Map<String, NameRec>, val provider: String?)

    private suspend fun name(ctl: RunCtl, items: Map<String, ItemRec>, clusters: List<ClusterRec>, zone: ZoneId): NamesFile {
        val ctx = ctl.ctx
        val now = ctl.meta.createdAt
        var names = withContext(Dispatchers.IO) { InventoryStore.readNames(ctx, ctl.runId) } ?: NamesFile()
        // Cele mai mari grupuri se botează primele: „cutiile” de pe ecran primesc nume repede.
        val order = clusters.sortedWith(compareByDescending<ClusterRec> { it.count }.thenBy { it.from }.thenBy { it.id })
        val top = order.take(5)
        fun bins(): List<BinTick> = top.map { c ->
            BinTick(names.clusters[c.id]?.let { if (c.kind == InvRules.KIND_SCREENS) InvRules.SCREENS_NAME else it.nume }, c.count)
        }
        ctl.bins = bins()

        val pending = order.filter { it.id !in names.clusters }
        val toAsk = pending.filter { InvAssemble.needsAi(it) }
        val local = pending.filter { !InvAssemble.needsAi(it) }
        if (local.isNotEmpty()) names = names.copy(clusters = names.clusters + local.associate { it.id to InvAssemble.fallbackName(it, zone) })
        val auth = if (toAsk.isEmpty()) null else AiGate.auth(ctl.app)
        if (auth == null) {
            names = names.copy(clusters = names.clusters + toAsk.associate { it.id to InvAssemble.fallbackName(it, zone) })
            saveNames(ctl, names)
            ctl.bins = bins()
            ctl.report(InvStage.Naming, 1.0, 0, null, force = true)
            return names
        }
        saveNames(ctl, names)

        val batches = InvAssemble.pack(toAsk, items, now)
        val engine = CleanupEngine(ctx, ctl.app.prefs)
        val gate = Semaphore(InvRules.AI_PARALLEL)
        val mtx = Mutex()
        val stop = AtomicBoolean(false)
        val streak = AtomicInteger(0)
        val total = batches.size
        var finished = 0
        val t0 = SystemClock.elapsedRealtime()
        ctl.report(InvStage.Naming, 0.0, InvRules.aiSec(total), "Dau nume · 0 din $total", force = true)
        coroutineScope {
            batches.map { batch ->
                async {
                    gate.withPermit {
                        ctl.checkAlive()
                        val got = if (stop.get()) null else askBatch(ctl, engine, batch, items, now, zone, stop, streak)
                        mtx.withLock {
                            val fresh = batch.associate { c -> c.id to (got?.names?.get(c.id) ?: InvAssemble.fallbackName(c, zone)) }
                            names = names.copy(clusters = names.clusters + fresh, provider = names.provider ?: got?.provider)
                            saveNames(ctl, names)
                            finished++
                            ctl.bins = bins()
                            val elapsed = SystemClock.elapsedRealtime() - t0
                            val eta = ((total - finished) * elapsed / finished / 1000).toInt()
                            ctl.report(InvStage.Naming, finished.toDouble() / total, eta, "Dau nume · $finished din $total")
                        }
                    }
                }
            }.awaitAll()
        }
        return names
    }

    private suspend fun saveNames(ctl: RunCtl, names: NamesFile) {
        if (!ctl.alive()) return
        withContext(Dispatchers.IO) { try { InventoryStore.writeNames(ctl.ctx, ctl.runId, names) } catch (_: Exception) { } }
    }

    /**
     * Un lot: miniaturile se fac chiar acum (în memorie stă cel mult un lot: ≤ 24 × 80 KB), apoi un apel cu o
     * reîncercare (429 / 5xx / timp depășit / 413 cu mai puține miniaturi). 401/403/404 opresc AI-ul pentru toată
     * rularea; trei loturi eșuate la rând la fel. Orice eșec → null → numele de rezervă, rularea continuă.
     */
    private suspend fun askBatch(
        ctl: RunCtl,
        engine: CleanupEngine,
        batch: List<ClusterRec>,
        items: Map<String, ItemRec>,
        now: Long,
        zone: ZoneId,
        stop: AtomicBoolean,
        streak: AtomicInteger
    ): BatchResult? {
        val ctx = ctl.ctx
        val reqs = ArrayList<ClustersApi.ClusterIn>()
        for (c in batch) {
            ctl.checkAlive()
            val thumbs = ArrayList<String>()
            for (r in InvAssemble.representatives(c, items, now)) {
                val t = MediaThumbs.base64(ctx, engine, r) ?: continue
                thumbs += t
                if (!r.video) ctl.addRecent(Uri.parse(r.uri))
            }
            if (thumbs.isEmpty()) continue   // nimic de arătat → numele de rezervă
            reqs += ClustersApi.ClusterIn(c.id, c.count, c.from, c.to, c.place, InvAssemble.hints(c, zone), thumbs)
        }
        if (reqs.isEmpty()) return null
        var send: List<ClustersApi.ClusterIn> = reqs
        var attempt = 0
        while (true) {
            ctl.checkAlive()
            if (stop.get()) return null
            val auth = AiGate.auth(ctl.app) ?: run { stop.set(true); return null }
            when (val out = ClustersApi.name(auth, send)) {
                is ClustersApi.Outcome.Ok -> {
                    streak.set(0)
                    val resp = out.response
                    val label = if (resp.provider.isBlank() && resp.model.isBlank()) null else providerLabel(resp.provider, resp.model)
                    return BatchResult(parse(resp, batch, zone), label)
                }
                is ClustersApi.Outcome.Fail -> {
                    if (ClustersApi.fatal(out.code)) { stop.set(true); return null }
                    val again = attempt == 0 && (ClustersApi.retryable(out.code) || out.code == 413)
                    if (!again) {
                        if (streak.incrementAndGet() >= 3) stop.set(true)
                        return null
                    }
                    attempt++
                    if (out.code == 413) send = send.map { it.copy(thumbs = it.thumbs.take(3)) }
                    delay(if (out.code == 429) 8_000L else 3_000L)
                }
            }
        }
    }

    private fun parse(resp: ClustersApi.ClustersResponse, batch: List<ClusterRec>, zone: ZoneId): Map<String, NameRec> {
        val byId = batch.associateBy { it.id }
        val out = HashMap<String, NameRec>()
        for (o in resp.clusters) {
            val c = byId[o.id] ?: continue
            val fb = InvAssemble.fallbackName(c, zone)
            out[c.id] = NameRec(
                nume = InvText.cleanName(o.nume).ifBlank { fb.nume },
                tema = InvText.cleanName(o.tema).ifBlank { fb.tema },
                categorie = InvText.cleanName(o.categorie),
                pastrare = InvText.pastrare(o.pastrare),
                motiv = o.motiv.take(160),
                ai = true
            )
        }
        return out
    }

    private fun mediaIdOf(id: String): Long? = if (id.startsWith("m:")) id.substring(2).toLongOrNull() else null
    /** `recent` alimentează animația cu miniaturi; video (…/video/media/ID) nu se decodează ca poză, așa că nu intră. */
    private fun isVideoUri(uri: Uri): Boolean = uri.pathSegments.contains("video")
    private fun safeId(uri: Uri): Long? = try { ContentUris.parseId(uri) } catch (_: Exception) { null }
}

// ─────────────────────────── Accesul la galerie ───────────────────────────

internal object MediaAccess {
    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun canRead(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> has(ctx, Manifest.permission.READ_MEDIA_IMAGES) ||
            has(ctx, Manifest.permission.READ_MEDIA_VIDEO) || has(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> has(ctx, Manifest.permission.READ_MEDIA_IMAGES) || has(ctx, Manifest.permission.READ_MEDIA_VIDEO)
        else -> has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Sub API 29 locul vine din coloanele MediaStore; de la 29, doar cu ACCESS_MEDIA_LOCATION (EXIF-ul original). */
    fun canReadLocation(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 || has(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** Coordonatele unei poze: coloanele (API < 29) sau EXIF prin MediaStore.setRequireOriginal (API 29+). Blocant. */
    fun location(ctx: Context, r: ItemRec): LatLon? {
        val lat = r.lat
        val lon = r.lon
        if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) return LatLon(lat, lon)
        if (Build.VERSION.SDK_INT < 29 || r.video) return null
        return try {
            val original = MediaStore.setRequireOriginal(Uri.parse(r.uri))
            ctx.contentResolver.openInputStream(original)?.use { s ->
                val ll = FloatArray(2)
                if (!ExifInterface(s).getLatLong(ll)) return@use null
                val la = ll[0].toDouble()
                val lo = ll[1].toDouble()
                if (!(la == 0.0 && lo == 0.0) && la in -90.0..90.0 && lo in -180.0..180.0) LatLon(la, lo) else null
            }
        } catch (_: Exception) { null }
    }
}

/** Interogarea MediaStore pe scopul inventarului (aceleași filtre ca CleanupEngine, ca id-urile să se potrivească). */
internal object MediaQuery {
    private const val PAGE = 1000

    fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Files.getContentUri("external")

    /** Același tip de URI ca în CleanupEngine (…/images/media/ID sau …/video/media/ID). */
    fun itemUri(id: Long, video: Boolean): Uri = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
    )

    /** Poze + video din scop: albumul [bucketId], ultimele [lastN] (după _ID) sau toată galeria. Sortate după _ID. */
    suspend fun query(ctx: Context, bucketId: Long?, lastN: Int?): List<ItemRec> = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val withLoc = Build.VERSION.SDK_INT < 29
        try {
            scanAll(cr, bucketId, lastN, withLoc)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (withLoc) try { scanAll(cr, bucketId, lastN, false) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            else emptyList()
        }
    }

    private suspend fun scanAll(cr: ContentResolver, bucketId: Long?, lastN: Int?, withLoc: Boolean): List<ItemRec> {
        val out = ArrayList<ItemRec>()
        if (lastN != null && lastN > 0) {
            page(cr, bucketId, null, lastN, desc = true, withLoc = withLoc, out = out)
            out.sortBy { it.mediaId }
            return out
        }
        var lastId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = out.size
            page(cr, bucketId, lastId, PAGE, desc = false, withLoc = withLoc, out = out)
            val got = out.size - before
            if (got == 0) break
            lastId = out.last().mediaId
            if (got < PAGE) break
        }
        return out
    }

    private fun page(
        cr: ContentResolver, bucketId: Long?, afterId: Long?, limit: Int, desc: Boolean, withLoc: Boolean, out: MutableList<ItemRec>
    ) {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        if (Build.VERSION.SDK_INT >= 29) cols += MediaStore.MediaColumns.RELATIVE_PATH
        if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.MediaColumns.IS_FAVORITE
        if (withLoc) { cols += "latitude"; cols += "longitude" }   // coloanele vechi (API < 29) ale pozelor
        var selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        if (afterId != null) { selection += " AND ${MediaStore.MediaColumns._ID} > ?"; args += afterId.toString() }
        if (Build.VERSION.SDK_INT >= 30) selection += " AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        if (bucketId != null) { selection += " AND ${MediaStore.Images.Media.BUCKET_ID} = ?"; args += bucketId.toString() }
        val sort = "${MediaStore.MediaColumns._ID} ${if (desc) "DESC" else "ASC"}"
        val projection = cols.toTypedArray()
        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            cr.query(collection(), projection, bundle, null)
        } else {
            try {
                cr.query(collection(), projection, selection, args.toTypedArray(), "$sort LIMIT $limit")
            } catch (_: Exception) {
                cr.query(collection(), projection, selection, args.toTypedArray(), sort)
            }
        }
        cursor?.use { c ->
            val idC = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nmC = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mmC = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val szC = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dmC = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val daC = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val dtC = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val wC = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val hC = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val bkC = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bnC = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val mtC = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val rpC = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val favC = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            val latC = if (withLoc) c.getColumnIndex("latitude") else -1
            val lonC = if (withLoc) c.getColumnIndex("longitude") else -1
            fun long(i: Int) = if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
            fun int(i: Int) = if (i >= 0 && !c.isNull(i)) c.getInt(i) else 0
            fun str(i: Int): String? = if (i >= 0) c.getString(i) else null
            fun dbl(i: Int): Double? = if (i >= 0 && !c.isNull(i)) c.getDouble(i) else null
            var read = 0
            while (read < limit && c.moveToNext()) {
                read++
                val id = c.getLong(idC)
                val mime = str(mmC) ?: "image/*"
                val video = mime.startsWith("video/") || int(mtC) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val name = str(nmC) ?: "IMG_$id"
                val taken = long(dtC)
                val modified = long(dmC) * 1000L
                val added = long(daC) * 1000L
                val bucket = str(bnC) ?: ""
                val rel = str(rpC) ?: ""
                out += ItemRec(
                    id = "m:$id",
                    uri = itemUri(id, video).toString(),
                    kind = InvKind.Photos,
                    takenAt = when { taken > 0 -> taken; modified > 0 -> modified; else -> added },
                    bytes = long(szC),
                    width = int(wC),
                    height = int(hC),
                    mime = mime,
                    name = name,
                    mediaId = id,
                    video = video,
                    bucket = bucket,
                    relPath = rel,
                    favorite = favC >= 0 && int(favC) == 1,
                    lat = dbl(latC),
                    lon = dbl(lonC),
                    screenshot = !video && InvAssemble.looksLikeScreenshot(name, bucket, rel)
                )
            }
        }
    }
}

/** Miniaturile reprezentanților: 384 px, JPEG q70 (coborât până la q40), ≤ 60 KiB → ≤ 81 920 caractere base64. */
internal object MediaThumbs {
    suspend fun base64(ctx: Context, engine: CleanupEngine, r: ItemRec): String? {
        val bytes = if (r.video) videoJpeg(ctx, Uri.parse(r.uri))
        else engine.thumbnailJpeg(Uri.parse(r.uri), InvRules.THUMB_SIDE, InvRules.THUMB_MAX_BYTES)
        val b64 = Base64.encodeToString(bytes ?: return null, Base64.NO_WRAP)
        return b64.takeIf { it.length <= InvRules.THUMB_MAX_B64 }
    }

    private suspend fun videoJpeg(ctx: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val frame = videoFrame(ctx, uri) ?: return@withContext null
        try {
            val longest = max(frame.width, frame.height)
            val bmp = if (longest > InvRules.THUMB_SIDE) {
                val s = InvRules.THUMB_SIDE.toFloat() / longest
                Bitmap.createScaledBitmap(frame, max(1, (frame.width * s).roundToInt()), max(1, (frame.height * s).roundToInt()), true)
            } else frame
            try {
                for (q in intArrayOf(70, 60, 50, 40)) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                    val bytes = out.toByteArray()
                    if (bytes.size <= InvRules.THUMB_MAX_BYTES) return@withContext bytes
                }
                null
            } finally {
                if (bmp !== frame) bmp.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            frame.recycle()
        }
    }

    private fun videoFrame(ctx: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return try { ctx.contentResolver.loadThumbnail(uri, Size(InvRules.THUMB_SIDE, InvRules.THUMB_SIDE), null) } catch (_: Exception) { null }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }
}

/**
 * Numele locului prin Geocoder-ul Android (pe dispozitiv; poate lipsi), cu cache pe celule de ~5 km. Limite, ca gruparea
 * să nu stea după rețea: ≤ [MAX_LOOKUPS] cereri, ≤ [BUDGET_MS] în total, oprire după [MAX_FAILURES] eșecuri la rând.
 */
internal class Geo(private val ctx: Context) {
    private val cache = HashMap<String, String?>()
    private var calls = 0
    private var failures = 0
    private var spentMs = 0L
    private val present: Boolean = try { Geocoder.isPresent() } catch (_: Exception) { false }

    suspend fun place(c: LatLon): String? {
        if (!present) return null
        val key = "${(c.lat * 20).roundToInt()}:${(c.lon * 20).roundToInt()}"
        if (cache.containsKey(key)) return cache[key]
        if (calls >= MAX_LOOKUPS || failures >= MAX_FAILURES || spentMs >= BUDGET_MS) return null
        calls++
        val t0 = SystemClock.elapsedRealtime()
        val found = try { lookup(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        spentMs += SystemClock.elapsedRealtime() - t0
        if (found == null) { failures++; return null }   // eroare / timp depășit: nu memorăm, poate merge la alt loc
        failures = 0
        cache[key] = found.second
        return found.second
    }

    /** (true, nume sau null dacă nu există adresă) la un răspuns; null la eroare sau timp depășit. */
    private suspend fun lookup(c: LatLon): Pair<Boolean, String?>? {
        val geocoder = Geocoder(ctx, Locale.forLanguageTag("ro-RO"))
        val list: List<Address>? = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<List<Address>?> { cont ->
                    try {
                        geocoder.getFromLocation(c.lat, c.lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                            override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                        })
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } else {
            withTimeoutOrNull(TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(c.lat, c.lon, 1)
                }
            }
        }
        if (list == null) return null
        val a = list.firstOrNull() ?: return true to null
        return true to listOf(a.locality, a.subAdminArea, a.adminArea, a.countryName)
            .firstOrNull { !it.isNullOrBlank() }?.trim()?.take(20)
    }

    companion object {
        const val MAX_LOOKUPS = 120
        const val MAX_FAILURES = 3
        const val BUDGET_MS = 60_000L
        const val TIMEOUT_MS = 5_000L
    }
}

/**
 * Cursorul CleanupEngine pentru „Ultimele N”: motorul scanează `_ID > cursor` crescător, deci îl punem la
 * (cel mai mic id dintre ultimele N) − 1, apoi punem la loc intrarea veche. Formatul e cel public al motorului
 * (JSON: scopeHash → [CleanupCursor], în Prefs.cleanupCursor); motorul în sine nu se schimbă.
 */
internal object EngineCursor {
    class Backup(val key: String, val previous: CleanupCursor?)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = MapSerializer(String.serializer(), CleanupCursor.serializer())

    private suspend fun read(prefs: Prefs): LinkedHashMap<String, CleanupCursor> = try {
        val raw = prefs.cleanupCursor.first()
        if (raw.isBlank()) LinkedHashMap() else LinkedHashMap(json.decodeFromString(ser, raw))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        LinkedHashMap()
    }

    suspend fun prime(prefs: Prefs, scope: CleanupScope, lastId: Long): Backup? = try {
        val map = read(prefs)
        val key = scope.hash()
        val backup = Backup(key, map[key])
        map[key] = CleanupCursor(key, lastId.coerceAtLeast(0L), 0, System.currentTimeMillis())
        prefs.setCleanupCursor(json.encodeToString(ser, map))
        backup
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    suspend fun restore(prefs: Prefs, backup: Backup) {
        try {
            val map = read(prefs)
            val prev = backup.previous
            if (prev != null) map[backup.key] = prev else map.remove(backup.key)
            prefs.setCleanupCursor(json.encodeToString(ser, map))
        } catch (_: Exception) { }
    }
}
