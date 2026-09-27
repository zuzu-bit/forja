package com.forja.app.core.cleanup

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.forja.app.core.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ═══════════════ Curățenie v2 — motorul de scanare al galeriei ═══════════════
// Nimic nu pleacă de pe telefon: hash-urile, dHash-ul și claritatea se calculează local.
// Singura excepție (opt-in explicit, în alt strat) sunt miniaturile pentru sugestiile AI.

enum class ScopeKind { WHOLE_GALLERY, ALBUM, NEXT_BATCH }

/** Alegerea imuabilă din „Ce curățăm azi?". bucketId = MediaStore BUCKET_ID când kind == ALBUM. */
@Serializable
data class CleanupScope(
    val kind: ScopeKind,
    val bucketId: Long? = null,
    val bucketName: String? = null,
    val batchSize: Int = 100,            // 50 | 100 | 300 pentru NEXT_BATCH; ignorat altfel
    val includeVideos: Boolean = false
) {
    /** Hash stabil pe (kind, bucketId, includeVideos) — NU pe batchSize — ca „Următoarele 50" apoi „300" să continue același cursor. */
    fun hash(): String = sha256Hex("${kind.name}|${bucketId ?: -1}|$includeVideos").take(16)

    /** Câte elemente analizăm într-o rulare; 0 = până la capăt. */
    val limit: Int get() = if (kind == ScopeKind.NEXT_BATCH) batchSize.coerceAtLeast(1) else 0

    val title: String
        get() = when (kind) {
            ScopeKind.WHOLE_GALLERY -> "Toată galeria"
            ScopeKind.ALBUM -> bucketName?.takeIf { it.isNotBlank() } ?: "Un album"
            ScopeKind.NEXT_BATCH -> "Următoarele $batchSize"
        }
}

/** Punctul de reluare, persistat în DataStore (cleanup_cursor) ca hartă scopeHash → cursor. */
@Serializable
data class CleanupCursor(
    val scopeHash: String,
    val lastId: Long,          // ultimul _ID procesat (mergem _id ASC, deci „următoarele" = _id > lastId)
    val processed: Int,        // câte elemente au fost procesate până acum în acest scop
    val updatedAt: Long
)

enum class Category(val label: String, val folder: String) {
    DUPLICATE("Duplicate", "Duplicate"),
    SIMILAR("Similare", "Similare"),
    SCREENSHOT("Capturi de ecran", "Capturi de ecran"),
    BLURRY("Neclare", "Neclare"),
    TINY("Mici", "Mici"),
    LARGE("Mari", "Mari")
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,   // "" sub API 29
    val isFavorite: Boolean
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
    /** Momentul „real" al pozei: EXIF dacă există, altfel adăugarea în galerie. */
    val bestTimeMs: Long get() = if (dateTakenMs > 0) dateTakenMs else dateAddedMs
}

data class Finding(val item: MediaItem, val category: Category, val reason: String, val groupKey: String? = null, val score: Float = 0f)
data class DuplicateGroup(val sha256: String, val keeper: MediaItem, val copies: List<MediaItem>)
data class SimilarGroup(val keeper: MediaItem, val others: List<MediaItem>, val maxDistance: Int)

data class CleanupReport(
    val scope: CleanupScope,
    val scanned: List<MediaItem>,
    val duplicates: List<DuplicateGroup>,
    val similar: List<SimilarGroup>,
    val screenshots: List<MediaItem>,
    val blurry: List<Finding>,
    val tiny: List<MediaItem>,
    val large: List<MediaItem>,
    val cursor: CleanupCursor,
    val hasMore: Boolean,
    val warnings: List<String>
) {
    val allFindings: List<Finding>
        get() {
            val out = ArrayList<Finding>()
            duplicates.forEach { g -> g.copies.forEach { out += Finding(it, Category.DUPLICATE, "duplicat identic", "d:${g.sha256.take(12)}") } }
            similar.forEachIndexed { i, g -> g.others.forEach { out += Finding(it, Category.SIMILAR, "aproape identică", "s:$i") } }
            screenshots.forEach { out += Finding(it, Category.SCREENSHOT, "captură de ecran") }
            out += blurry
            tiny.forEach { out += Finding(it, Category.TINY, if (it.sizeBytes in 1..CleanupEngine.TINY_BYTES) "mică (< 60 KB)" else "rezoluție mică") }
            large.forEach { out += Finding(it, Category.LARGE, "mare · ${fmtBytes(it.sizeBytes)}") }
            return out
        }

    val isEmpty: Boolean
        get() = duplicates.isEmpty() && similar.isEmpty() && screenshots.isEmpty() && blurry.isEmpty() && tiny.isEmpty() && large.isEmpty()

    /** Toate elementele care apar în vreo categorie (fără dubluri). */
    val flaggedItems: List<MediaItem>
        get() = allFindings.map { it.item }.distinctBy { it.id }

    /** Elimină elementele (șterse/mutate) din toate listele; grupurile rămase fără copii dispar. */
    fun without(ids: Set<Long>): CleanupReport = copy(
        scanned = scanned.filter { it.id !in ids },
        duplicates = duplicates.mapNotNull { g ->
            val keep = if (g.keeper.id in ids) g.copies.firstOrNull { it.id !in ids } else g.keeper
            val copies = g.copies.filter { it.id !in ids && it.id != keep?.id }
            if (keep == null || copies.isEmpty()) null else DuplicateGroup(g.sha256, keep, copies)
        },
        similar = similar.mapNotNull { g ->
            val keep = if (g.keeper.id in ids) g.others.firstOrNull { it.id !in ids } else g.keeper
            val others = g.others.filter { it.id !in ids && it.id != keep?.id }
            if (keep == null || others.isEmpty()) null else SimilarGroup(keep, others, g.maxDistance)
        },
        screenshots = screenshots.filter { it.id !in ids },
        blurry = blurry.filter { it.item.id !in ids },
        tiny = tiny.filter { it.id !in ids },
        large = large.filter { it.id !in ids }
    )

    /** Raportul unei rulări anterioare (pauză → continuă) + raportul curent, fără dubluri. */
    fun mergedWith(older: CleanupReport?): CleanupReport {
        if (older == null) return this
        val mineIds = scanned.map { it.id }.toHashSet()
        val flagged = flaggedItems.map { it.id }.toHashSet()
        fun <T> keepOld(list: List<T>, id: (T) -> Long) = list.filter { id(it) !in flagged }
        return copy(
            scanned = older.scanned.filter { it.id !in mineIds } + scanned,
            duplicates = keepOld(older.duplicates) { it.keeper.id } + duplicates,
            similar = keepOld(older.similar) { it.keeper.id } + similar,
            screenshots = keepOld(older.screenshots) { it.id } + screenshots,
            blurry = keepOld(older.blurry) { it.item.id } + blurry,
            tiny = keepOld(older.tiny) { it.id } + tiny,
            large = keepOld(older.large) { it.id } + large,
            warnings = (older.warnings + warnings).distinct().take(20)
        )
    }
}

sealed class ScanProgress {
    data class Inventory(val count: Int) : ScanProgress()
    data class Hashing(val done: Int, val total: Int, val current: String, val currentUri: Uri? = null) : ScanProgress()
    data class Visual(val done: Int, val total: Int, val currentUri: Uri? = null) : ScanProgress()
    data class Done(val report: CleanupReport) : ScanProgress()
}

data class Album(val bucketId: Long, val name: String, val count: Int, val coverUri: Uri?, val bytes: Long)

/**
 * Rezultatul unei mutări. Pe API 29 mutarea poate cere acordul utilizatorului pe loc:
 * [recoverable] e intent-ul de lansat, [remaining] elementele de reluat după acord.
 */
data class MoveResult(
    val moved: Int,
    val skipped: Int,
    val errors: List<String>,
    val recoverable: IntentSender? = null,
    val remaining: List<MediaItem> = emptyList(),
    val movedIds: Set<Long> = emptySet()
)

/** Amprenta vizuală a unei poze: dHash 9×8, media RGB (0..1), contrast (0..1), varianța Laplace. */
private data class VisualPrint(val hash: Long, val r: Double, val g: Double, val b: Double, val contrast: Double, val blurVar: Double)

fun sha256Hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0).replace('.', ',')
    bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0).replace('.', ',')
    bytes >= 1_000 -> "${bytes / 1000} KB"
    else -> "$bytes B"
}

class CleanupEngine(private val context: Context, private val prefs: Prefs) {

    companion object {
        const val ROOT_RELATIVE = "Pictures/FORJA Curățenie/"
        const val TINY_BYTES = 60_000L
        const val TINY_PX = 300
        const val LARGE_IMAGE_BYTES = 8_000_000L
        const val LARGE_VIDEO_BYTES = 200_000_000L
        const val SIMILAR_MAX_DISTANCE = 8
        const val SIMILAR_RGB_GATE = 0.08
        const val BLUR_THRESHOLD = 60.0
        const val VISUAL_MAX_BYTES = 30_000_000L
        const val PAGE = 500
        const val PERSIST_EVERY = 25
        const val REQUEST_CHUNK = 500
        private const val SIMILAR_WINDOW_MS = 24L * 3600_000L
        private const val SIMILAR_WINDOW_ITEMS = 200
    }

    private val cr: ContentResolver get() = context.contentResolver
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cursorMapSerializer = MapSerializer(String.serializer(), CleanupCursor.serializer())

    // ─────────────────────────── Cursor (reluare) ───────────────────────────

    private suspend fun readCursors(): Map<String, CleanupCursor> {
        val raw = try { prefs.cleanupCursor.first() } catch (_: Exception) { "" }
        if (raw.isBlank()) return emptyMap()
        return try { json.decodeFromString(cursorMapSerializer, raw) } catch (_: Exception) { emptyMap() }
    }

    private suspend fun writeCursor(cursor: CleanupCursor) {
        val all = readCursors().toMutableMap()
        all[cursor.scopeHash] = cursor
        // Ținem doar ultimele 12 scopuri, ca DataStore-ul să nu crească la nesfârșit.
        val trimmed = all.entries.sortedByDescending { it.value.updatedAt }.take(12).associate { it.key to it.value }
        try { prefs.setCleanupCursor(json.encodeToString(cursorMapSerializer, trimmed)) } catch (_: Exception) { }
    }

    /** Cursorul pentru scope.hash(); null când nu există sau scopul s-a terminat. */
    suspend fun resumePoint(scope: CleanupScope): CleanupCursor? = readCursors()[scope.hash()]

    suspend fun resetCursor(scope: CleanupScope) {
        val all = readCursors().toMutableMap()
        if (all.remove(scope.hash()) != null) {
            try { prefs.setCleanupCursor(json.encodeToString(cursorMapSerializer, all)) } catch (_: Exception) { }
        }
    }

    suspend fun lastScope(): CleanupScope? {
        val raw = try { prefs.cleanupLastScope.first() } catch (_: Exception) { "" }
        if (raw.isBlank()) return null
        return try { json.decodeFromString(CleanupScope.serializer(), raw) } catch (_: Exception) { null }
    }

    suspend fun rememberScope(scope: CleanupScope) {
        try { prefs.setCleanupLastScope(json.encodeToString(CleanupScope.serializer(), scope)) } catch (_: Exception) { }
    }

    // ─────────────────────────── Albume ───────────────────────────

    /** Albumele pentru alegerea scopului: (BUCKET_ID, nume, număr, copertă, octeți). O trecere peste Images (+Video). */
    suspend fun albums(includeVideos: Boolean = false): List<Album> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<Long, Album>()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE
        )
        val selection = if (Build.VERSION.SDK_INT >= 30)
            "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else null
        val collections = ArrayList<Pair<Uri, Boolean>>()
        collections += imagesCollection() to false
        if (includeVideos) collections += videosCollection() to true
        for ((collection, video) in collections) {
            try {
                cr.query(collection, cols, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val idC = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    val szC = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val bkC = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                    val bnC = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = c.getLong(idC)
                        val size = if (szC >= 0 && !c.isNull(szC)) c.getLong(szC) else 0L
                        val bucket = if (bkC >= 0 && !c.isNull(bkC)) c.getLong(bkC) else 0L
                        val name = (if (bnC >= 0) c.getString(bnC) else null) ?: "Fără album"
                        val prev = map[bucket]
                        map[bucket] = if (prev == null) {
                            Album(bucket, name, 1, itemUri(id, video), size)
                        } else prev.copy(count = prev.count + 1, bytes = prev.bytes + size)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        map.values.sortedByDescending { it.count }
    }

    // ─────────────────────────── Scanare ───────────────────────────

    /**
     * Emite progresul; rulează pe Dispatchers.IO; anulare cooperativă (ensureActive în bucle).
     * Cursorul se persistă la fiecare 25 de elemente și la pauză, ca un proces ucis să reia de unde a rămas.
     */
    fun scan(scope: CleanupScope, resume: Boolean = true): Flow<ScanProgress> = flow {
        val start = if (resume) resumePoint(scope) else null
        if (!resume) resetCursor(scope)
        var lastId = start?.lastId ?: 0L
        var processedBefore = start?.processed ?: 0
        val warnings = ArrayList<String>()
        val limit = scope.limit

        // 1) Inventar paginat, _id ASC, de la cursor.
        val items = ArrayList<MediaItem>()
        var hasMore = false
        emit(ScanProgress.Inventory(0))
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = queryPage(scope, lastId)
            if (page.isEmpty()) { hasMore = false; break }
            var consumed = 0
            for (item in page) {
                items += item
                lastId = item.id
                consumed++
                if (limit > 0 && items.size >= limit) break
            }
            emit(ScanProgress.Inventory(items.size))
            if (limit > 0 && items.size >= limit) {
                hasMore = consumed < page.size || page.size >= PAGE
                break
            }
            if (page.size < PAGE) { hasMore = false; break }
        }

        // 2) Analiza element cu element, în ordinea _id (cursorul avansează doar peste elemente terminate).
        val sizeCount = HashMap<Long, Int>()
        items.forEach { if (it.sizeBytes > 0) sizeCount[it.sizeBytes] = (sizeCount[it.sizeBytes] ?: 0) + 1 }
        val displaySizes = displaySizes()

        val screenshots = ArrayList<MediaItem>()
        val tiny = ArrayList<MediaItem>()
        val large = ArrayList<MediaItem>()
        val shaOf = HashMap<Long, String>()
        val prints = HashMap<Long, VisualPrint>()
        val screenshotIds = HashSet<Long>()

        val total = items.size
        var done = 0
        var doneId = start?.lastId ?: 0L
        var sinceFlush = 0
        val scopeHash = scope.hash()

        suspend fun persist(idNow: Long, extra: Int) {
            writeCursor(CleanupCursor(scopeHash, idNow, processedBefore + extra, System.currentTimeMillis()))
        }

        try {
            for (item in items) {
                currentCoroutineContext().ensureActive()
                val needsSha = (sizeCount[item.sizeBytes] ?: 0) > 1
                if (needsSha) emit(ScanProgress.Hashing(done, total, item.name, item.uri))
                else emit(ScanProgress.Visual(done, total, item.uri))

                try {
                    // a) captură de ecran
                    if (!item.isVideo && isScreenshot(item, displaySizes)) { screenshots += item; screenshotIds += item.id }
                    // b) mică
                    val tinyByBytes = item.sizeBytes in 1..TINY_BYTES
                    val tinyByPx = item.width > 0 && item.height > 0 && min(item.width, item.height) < TINY_PX
                    if (tinyByBytes || (!item.isVideo && tinyByPx)) tiny += item
                    // c) mare
                    val largeLimit = if (item.isVideo) LARGE_VIDEO_BYTES else LARGE_IMAGE_BYTES
                    if (item.sizeBytes > largeLimit) large += item
                    // d) SHA-256 doar pentru coliziuni de mărime
                    if (needsSha) shaOf[item.id] = sha256(item.uri)
                    // e) amprentă vizuală (doar poze rezonabile)
                    if (!item.isVideo && item.sizeBytes in 1..VISUAL_MAX_BYTES) {
                        visualPrint(item.uri)?.let { prints[item.id] = it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (warnings.size < 20) warnings += "${item.name}: ${e.javaClass.simpleName}"
                }

                done++
                doneId = item.id
                sinceFlush++
                if (sinceFlush >= PERSIST_EVERY) {
                    persist(doneId, done)
                    sinceFlush = 0
                }
            }
        } catch (e: CancellationException) {
            // Pauză: salvăm exact unde am rămas, ca „Continuă" să nu refacă munca.
            withContext(NonCancellable) { if (done > 0) persist(doneId, done) }
            throw e
        }

        // 3) Grupări.
        val duplicates = buildDuplicateGroups(items, shaOf)
        val dupCopyIds = duplicates.flatMap { g -> g.copies.map { it.id } }.toHashSet()
        val similar = buildSimilarGroups(items, prints, exclude = screenshotIds + dupCopyIds)
        val blurry = ArrayList<Finding>()
        for (item in items) {
            val p = prints[item.id] ?: continue
            if (item.id in screenshotIds || item.id in dupCopyIds) continue
            if (p.contrast < 0.05) continue
            if (item.sizeBytes in 1..TINY_BYTES) continue
            if (p.blurVar < BLUR_THRESHOLD) {
                val score = (BLUR_THRESHOLD / max(p.blurVar, 0.5)).toFloat()
                blurry += Finding(item, Category.BLURRY, "neclară (var ${p.blurVar.toInt()})", score = score)
            }
        }
        blurry.sortByDescending { it.score }

        // 4) Cursor final: scopul epuizat → cursorul dispare (data viitoare o luăm de la capăt).
        val finalCursor = CleanupCursor(scopeHash, if (items.isEmpty()) lastId else doneId, processedBefore + done, System.currentTimeMillis())
        if (hasMore) writeCursor(finalCursor) else resetCursor(scope)

        val newestFirst = compareByDescending<MediaItem> { it.bestTimeMs }
        emit(
            ScanProgress.Done(
                CleanupReport(
                    scope = scope,
                    scanned = items,
                    duplicates = duplicates,
                    similar = similar,
                    screenshots = screenshots.sortedWith(newestFirst),
                    blurry = blurry,
                    tiny = tiny.sortedWith(newestFirst),
                    large = large.sortedByDescending { it.sizeBytes },
                    cursor = finalCursor,
                    hasMore = hasMore,
                    warnings = warnings
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────── Interogări MediaStore ───────────────────────────

    private fun imagesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun videosCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun filesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Files.getContentUri("external")

    private fun itemUri(id: Long, video: Boolean): Uri = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
    )

    private fun projection(): Array<String> {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        if (Build.VERSION.SDK_INT >= 29) cols += MediaStore.MediaColumns.RELATIVE_PATH
        if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.MediaColumns.IS_FAVORITE
        return cols.toTypedArray()
    }

    /** O pagină de cel mult PAGE rânduri cu _id > lastId, în ordine crescătoare. */
    private fun queryPage(scope: CleanupScope, lastId: Long): List<MediaItem> {
        val useFiles = scope.includeVideos
        val collection = if (useFiles) filesCollection() else imagesCollection()
        var selection = "${MediaStore.MediaColumns._ID} > ?"
        val args = mutableListOf(lastId.toString())
        if (useFiles) {
            selection += " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        }
        if (Build.VERSION.SDK_INT >= 30) selection += " AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        if (scope.kind == ScopeKind.ALBUM && scope.bucketId != null) {
            selection += " AND ${MediaStore.Images.Media.BUCKET_ID} = ?"
            args += scope.bucketId.toString()
        }
        val sort = "${MediaStore.MediaColumns._ID} ASC"
        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE)
            }
            cr.query(collection, projection(), bundle, null)
        } else {
            // Providerul vechi acceptă LIMIT în sortare; dacă nu, citim doar PAGE rânduri din cursorul întreg.
            try {
                cr.query(collection, projection(), selection, args.toTypedArray(), "$sort LIMIT $PAGE")
            } catch (_: Exception) {
                cr.query(collection, projection(), selection, args.toTypedArray(), sort)
            }
        }
        val out = ArrayList<MediaItem>(PAGE)
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
            val rpC = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val favC = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            while (out.size < PAGE && c.moveToNext()) {
                val id = c.getLong(idC)
                val mime = (if (mmC >= 0) c.getString(mmC) else null) ?: "image/*"
                val video = mime.startsWith("video/")
                out += MediaItem(
                    id = id,
                    uri = itemUri(id, video),
                    name = (if (nmC >= 0) c.getString(nmC) else null) ?: "IMG_$id",
                    mime = mime,
                    sizeBytes = if (szC >= 0 && !c.isNull(szC)) c.getLong(szC) else 0L,
                    width = if (wC >= 0 && !c.isNull(wC)) c.getInt(wC) else 0,
                    height = if (hC >= 0 && !c.isNull(hC)) c.getInt(hC) else 0,
                    dateTakenMs = if (dtC >= 0 && !c.isNull(dtC)) c.getLong(dtC) else 0L,
                    dateAddedMs = if (daC >= 0 && !c.isNull(daC)) c.getLong(daC) * 1000L else 0L,
                    dateModifiedMs = if (dmC >= 0 && !c.isNull(dmC)) c.getLong(dmC) * 1000L else 0L,
                    bucketId = if (bkC >= 0 && !c.isNull(bkC)) c.getLong(bkC) else 0L,
                    bucketName = (if (bnC >= 0) c.getString(bnC) else null) ?: "",
                    relativePath = (if (rpC >= 0) c.getString(rpC) else null) ?: "",
                    isFavorite = favC >= 0 && !c.isNull(favC) && c.getInt(favC) == 1
                )
            }
        }
        return out
    }

    // ─────────────────────────── Detecții ───────────────────────────

    /** Mărimile posibile ale ecranului (px), pentru capturi de ecran fără nume/album explicit. */
    private fun displaySizes(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        fun add(w: Int, h: Int) { if (w > 0 && h > 0) out += w to h }
        try { val dm = context.resources.displayMetrics; add(dm.widthPixels, dm.heightPixels) } catch (_: Exception) { }
        try { val dm = android.content.res.Resources.getSystem().displayMetrics; add(dm.widthPixels, dm.heightPixels) } catch (_: Exception) { }
        try {
            val dmgr = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display: Display? = dmgr?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val real = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(real)
                add(real.widthPixels, real.heightPixels)
            }
        } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm != null) {
                    val b = wm.maximumWindowMetrics.bounds
                    add(b.width(), b.height())
                    val c = wm.currentWindowMetrics.bounds
                    add(c.width(), c.height())
                }
            } catch (_: Exception) { }
        }
        return out.distinct()
    }

    private fun isScreenshot(item: MediaItem, displaySizes: List<Pair<Int, Int>>): Boolean {
        val bucket = item.bucketName.lowercase()
        val path = item.relativePath.lowercase()
        val name = item.name.lowercase()
        if (bucket.contains("screenshot") || path.contains("screenshots") || name.startsWith("screenshot")) return true
        if (item.width <= 0 || item.height <= 0) return false
        for ((w, h) in displaySizes) {
            val direct = abs(item.width - w) <= 2 && abs(item.height - h) <= 2
            val rotated = abs(item.width - h) <= 2 && abs(item.height - w) <= 2
            if (direct || rotated) return true
        }
        return false
    }

    /** SHA-256 în flux (64 KB), anulabil între blocuri. */
    suspend fun sha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        val stream = cr.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        stream.use { s ->
            val buf = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Decodează o poză la cel mult [maxSide] px pe latura mare, direct din flux (fără să încarce tot fișierul). */
    fun decodeSmall(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = cr.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val scale = maxSide.toFloat() / longest
        val w = max(1, (bmp.width * scale).toInt())
        val h = max(1, (bmp.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun visualPrint(uri: Uri): VisualPrint? {
        val bmp = decodeSmall(uri, 256) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            if (w < 3 || h < 3) return null
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val gray = FloatArray(w * h)
            var rs = 0.0; var gs = 0.0; var bs = 0.0
            var gsum = 0.0; var gsq = 0.0
            for (i in px.indices) {
                val p = px[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val gr = 0.299f * r + 0.587f * g + 0.114f * b
                gray[i] = gr
                rs += r; gs += g; bs += b
                gsum += gr; gsq += gr * gr
            }
            val n = px.size.toDouble()
            val mean = gsum / n
            val contrast = sqrt(max(0.0, gsq / n - mean * mean)) / 255.0

            // Laplace 3×3 [0 1 0; 1 -4 1; 0 1 0] pe pixelii interiori → varianța răspunsului.
            var lsum = 0.0; var lsq = 0.0; var cnt = 0
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    val l = gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4f * gray[i]
                    lsum += l; lsq += l * l; cnt++
                }
            }
            val blurVar = if (cnt > 0) max(0.0, lsq / cnt - (lsum / cnt) * (lsum / cnt)) else 0.0

            // dHash 9×8: bit (y*8+x) = gray[y][x] > gray[y][x+1]
            val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            val sp = IntArray(72)
            small.getPixels(sp, 0, 9, 0, 0, 9, 8)
            if (small !== bmp) small.recycle()
            var hash = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val a = luma(sp[y * 9 + x]); val b = luma(sp[y * 9 + x + 1])
                    if (a > b) hash = hash or (1L shl (y * 8 + x))
                }
            }
            return VisualPrint(hash, rs / n / 255.0, gs / n / 255.0, bs / n / 255.0, contrast, blurVar)
        } finally {
            bmp.recycle()
        }
    }

    private fun luma(p: Int): Float {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun buildDuplicateGroups(items: List<MediaItem>, shaOf: Map<Long, String>): List<DuplicateGroup> {
        val bySha = HashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            val sha = shaOf[item.id] ?: continue
            bySha.getOrPut(sha) { ArrayList() } += item
        }
        val out = ArrayList<DuplicateGroup>()
        for ((sha, group) in bySha) {
            if (group.size < 2) continue
            // Păstrăm originalul: cel mai vechi (EXIF, apoi adăugare); la egalitate, cel din afara dosarului FORJA cu calea cea mai lungă.
            val sorted = group.sortedWith(
                compareBy<MediaItem> { it.bestTimeMs }
                    .thenBy { if (it.relativePath.startsWith(ROOT_RELATIVE)) 1 else 0 }
                    .thenByDescending { it.relativePath.length }
                    .thenBy { it.id }
            )
            out += DuplicateGroup(sha, sorted.first(), sorted.drop(1))
        }
        return out.sortedByDescending { g -> g.copies.sumOf { it.sizeBytes } }
    }

    private fun buildSimilarGroups(items: List<MediaItem>, prints: Map<Long, VisualPrint>, exclude: Set<Long>): List<SimilarGroup> {
        val cand = items.filter { it.id in prints && it.id !in exclude && it.sizeBytes > TINY_BYTES }
            .sortedBy { it.bestTimeMs }
        if (cand.size < 2) return emptyList()
        val parent = IntArray(cand.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }
        val dist = HashMap<Long, Int>()   // key = (i shl 32) or j, distanța Hamming dintre perechile unite
        for (i in cand.indices) {
            val pi = prints[cand[i].id] ?: continue
            var j = i + 1
            while (j < cand.size && j - i <= SIMILAR_WINDOW_ITEMS) {
                if (cand[j].bestTimeMs - cand[i].bestTimeMs > SIMILAR_WINDOW_MS) break
                val pj = prints[cand[j].id]
                if (pj != null) {
                    val d = java.lang.Long.bitCount(pi.hash xor pj.hash)
                    if (d <= SIMILAR_MAX_DISTANCE) {
                        val rgb = sqrt((pi.r - pj.r) * (pi.r - pj.r) + (pi.g - pj.g) * (pi.g - pj.g) + (pi.b - pj.b) * (pi.b - pj.b))
                        if (rgb < SIMILAR_RGB_GATE) {
                            union(i, j)
                            dist[(i.toLong() shl 32) or j.toLong()] = d
                        }
                    }
                }
                j++
            }
        }
        val groups = HashMap<Int, MutableList<Int>>()
        for (i in cand.indices) groups.getOrPut(find(i)) { ArrayList() } += i
        val out = ArrayList<SimilarGroup>()
        for (idx in groups.values) {
            if (idx.size < 2) continue
            val members = idx.map { cand[it] }
            // Păstrăm cea mai clară; la egalitate cea mai mare.
            val keeper = members.maxWithOrNull(
                compareBy<MediaItem> { prints[it.id]?.blurVar ?: 0.0 }.thenBy { it.sizeBytes }
            ) ?: members.first()
            val kp = prints[keeper.id]!!
            val others = members.filter { it.id != keeper.id }
            val maxD = others.maxOfOrNull { java.lang.Long.bitCount(kp.hash xor (prints[it.id]?.hash ?: 0L)) } ?: 0
            out += SimilarGroup(keeper, others, maxD)
        }
        return out.sortedByDescending { g -> g.others.sumOf { it.sizeBytes } }
    }

    // ─────────────────────────── Acțiuni ───────────────────────────

    /** Mutarea în dosare cere RELATIVE_PATH (API 29+). */
    val canMove: Boolean get() = Build.VERSION.SDK_INT >= 29

    fun writeRequest(uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createWriteRequest(cr, uris).intentSender else null

    fun deleteRequest(uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createDeleteRequest(cr, uris).intentSender else null

    fun trashRequest(uris: List<Uri>, trash: Boolean): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createTrashRequest(cr, uris, trash).intentSender else null

    /** După acordul de scriere: actualizează RELATIVE_PATH (+DISPLAY_NAME la coliziune) în dosarul categoriei. */
    suspend fun moveToCategoryFolder(items: List<MediaItem>, category: Category): MoveResult =
        moveToFolder(items, category.folder)

    /** Dosarul relativ e sub „Pictures/FORJA Curățenie/"; segmentele sunt curățate (fără „..", separatoare, caractere interzise). */
    suspend fun moveToFolder(items: List<MediaItem>, folder: String): MoveResult {
        val cleanFolder = sanitizeFolder(folder)
        if (cleanFolder.isBlank()) return MoveResult(0, items.size, listOf("Dosar invalid."))
        return moveToRelativePath(items, ROOT_RELATIVE + cleanFolder + "/")
    }

    /**
     * Mutare într-o cale RELATIVE_PATH completă (ex. „Pictures/FORJA/Vacanță/") — folosită de organizarea
     * de pe site, unde destinația o alege utilizatorul din laptop. Segmentele sunt curățate ca la [sanitizeFolder].
     */
    suspend fun moveToRelativePath(items: List<MediaItem>, relativePath: String): MoveResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) {
            return@withContext MoveResult(0, items.size, listOf("Mutarea în dosare cere Android 10 sau mai nou."))
        }
        val bad = Regex("[\\\\:*?\"<>|\\p{Cntrl}]")
        val segments = relativePath.replace('\\', '/').split('/').map { it.replace(bad, "").trim().trim('.') }.filter { it.isNotBlank() && it != ".." }
        if (segments.isEmpty() || segments.first() != "Pictures") return@withContext MoveResult(0, items.size, listOf("Dosar invalid."))
        val relPath = segments.joinToString("/") + "/"
        var moved = 0
        var skipped = 0
        val errors = ArrayList<String>()
        val movedIds = HashSet<Long>()
        for ((index, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (item.relativePath == relPath) { skipped++; continue }
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    if (nameExists(item, relPath, item.name)) {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, collisionName(item.name, item.id.toString()))
                    }
                }
                val n = cr.update(item.uri, values, null, null)
                if (n == 1 && verifyPath(item.uri, relPath)) { moved++; movedIds += item.id }
                else { skipped++; if (errors.size < 10) errors += "${item.name}: nu s-a mutat" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    // API 29: sistemul cere acordul pe loc; reluăm de la acest element după acord.
                    return@withContext MoveResult(
                        moved, skipped, errors,
                        recoverable = e.userAction.actionIntent.intentSender,
                        remaining = items.subList(index, items.size),
                        movedIds = movedIds
                    )
                }
                skipped++
                if (errors.size < 10) errors += "${item.name}: fără permisiune"
            } catch (e: Exception) {
                skipped++
                if (errors.size < 10) errors += "${item.name}: ${e.javaClass.simpleName}"
            }
        }
        MoveResult(moved, skipped, errors, movedIds = movedIds)
    }

    private fun nameExists(item: MediaItem, relPath: String, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val collection = if (item.isVideo) videosCollection() else imagesCollection()
        return try {
            cr.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relPath, name), null
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
    }

    private fun verifyPath(uri: Uri, relPath: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return try {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                c.moveToFirst() && (c.getString(0) ?: "") == relPath
            } ?: false
        } catch (_: Exception) { true }
    }

    private fun collisionName(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} · $suffix${name.substring(dot)}" else "$name · $suffix"
    }

    /** API < 30: ștergere directă (fără dialog de sistem). Întoarce câte au reușit. */
    suspend fun deleteLegacy(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (u in uris) {
            currentCoroutineContext().ensureActive()
            try { if (cr.delete(u, null, null) > 0) n++ } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        n
    }

    /** Miniatură JPEG ≤ maxSide px, calitate ~70, ≤ maxBytes — singurul lucru care poate pleca spre AI (opt-in). */
    suspend fun thumbnailJpeg(uri: Uri, maxSide: Int = 512, maxBytes: Int = 120_000): ByteArray? = withContext(Dispatchers.IO) {
        try {
            var side = maxSide
            var quality = 70
            repeat(4) {
                val bmp = decodeSmall(uri, side) ?: return@withContext null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                val bytes = out.toByteArray()
                if (bytes.size <= maxBytes) return@withContext bytes
                quality = max(40, quality - 15)
                side = max(256, side * 3 / 4)
            }
            null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}

/** Curăță un dosar relativ propus (de AI sau de utilizator): ≤ 3 segmente, ≤ 40 caractere fiecare, fără caractere interzise. */
fun sanitizeFolder(raw: String): String {
    val bad = Regex("[\\\\:*?\"<>|\\p{Cntrl}]")
    return raw.replace('\\', '/').split('/')
        .map { it.replace(bad, "").trim().trim('.') }
        .filter { it.isNotBlank() && it != ".." }
        .take(3)
        .joinToString("/") { it.take(40) }
}
