package com.forja.app.core.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.data.StorageMirror
import com.forja.app.core.inventory.DocDest
import com.forja.app.core.inventory.TreePaths
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import com.forja.app.core.sync.MirrorPlan.Candidate
import com.forja.app.core.sync.MirrorPlan.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Oglinda galeriei și a documentelor pe site (pachetul C, contract v4 — decizia Lanei din 30.09: pozele și documentele
 * însele, nu rezumate). Rulează în lucrătorul galeriei ([GalleryUploadWorker]), deci doar pe Wi-Fi implicit și cu bateria bună.
 *
 * O trecere: citește galeria (poze + video, fără coș) și documentele din folderele alese în Inventar (sursa, „Organizate”,
 * destinația), cere registrul serverului (`GET /v2/mirror/ids`), apoi urcă cele mai noi lipsă, mută pe site ce Inventarul a
 * mutat pe telefon și șterge de pe site ce a dispărut de pe telefon (doar copiile acestui telefon, doar grupurile citite
 * complet). Poza urcă JPEG de cel mult 2048 px pe latura lungă, calitate 85, cu data EXIF păstrată; video-ul, posterul și
 * fișierul întreg când are cel mult 25 MB; documentul, octet cu octet (≤ 25 MB). Plus o miniatură de 384 px pentru grilă.
 * La final, numărătoarea din telefon și cât a urcat ajung în users/{uid}/settings/storage ([StorageMirror]).
 */
object MediaMirror {
    private const val LONG_EDGE = 2048
    private const val QUALITY = 85
    private const val THUMB_EDGE = 384
    private const val THUMB_MAX = 90_000
    private const val POSTER_EDGE = 1280
    private const val POSTER_MAX = 1_900_000
    private const val RUN_BUDGET_MS = 8 * 60_000L
    private const val MAX_PER_RUN = 150
    private const val DOCS_MAX = 15_000

    private fun canReadMedia(c: Context): Boolean {
        fun has(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO) || has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO)
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** Totalurile galeriei pentru numărătoare (fără nume de fișiere). */
    private class Scan(val items: List<Candidate>, val gallery: Boolean, val docs: Boolean, val docsLoose: Int, val docsOrganized: Int, val docsBytes: Long)

    suspend fun run(app: ForjaApp, uid: String): GalleryUploader.Outcome {
        val t0 = SystemClock.elapsedRealtime()
        val device = try { InsightsApi.deviceId(app.prefs)() } catch (_: Exception) { return GalleryUploader.Outcome.RETRY }
        // Acordul oglinzii pe server (șters de revocare): la fiecare trecere, ieftin și idempotent.
        try {
            InsightsApi.json("/v2/mirror/consent", buildJsonObject { put("on", true); put("contract", 4) }, "POST")
        } catch (e: CancellationException) { throw e } catch (e: InsightsFailure) {
            GalleryUploader.setStatus(app, e.message ?: "Site-ul nu a răspuns."); return if (e.code == 401) GalleryUploader.Outcome.SKIPPED else GalleryUploader.Outcome.RETRY
        } catch (_: Exception) { GalleryUploader.setStatus(app, "Fără conexiune. Reluăm pe Wi-Fi."); return GalleryUploader.Outcome.RETRY }

        val scan = scan(app)
        if (!scan.gallery && !scan.docs) {
            GalleryUploader.setStatus(app, "Oglinda așteaptă permisiunea pozelor din Echipare.")
            publish(app, uid, scan, null, "permission")
            return GalleryUploader.Outcome.SKIPPED
        }
        val (remote, gone) = try { registry(device) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            GalleryUploader.setStatus(app, "Site-ul nu a răspuns. Reluăm cu net."); return GalleryUploader.Outcome.RETRY
        }
        val scanned = buildSet { if (scan.gallery) add("gallery"); if (scan.docs) add("docs") }
        val plan = MirrorPlan.plan(scan.items, remote, gone, scanned)

        // Întâi ce e ieftin: mutările din Inventar și ștergerile, apoi urcările.
        for ((id, album) in plan.move) patch(device, id, album)
        for (id in plan.claim) patch(device, id, null)
        for (id in plan.delete) try { InsightsApi.json("/v2/mirror/$id", null, "DELETE") } catch (e: CancellationException) { throw e } catch (_: Exception) { }

        val byId = remote.associateBy { it.id }
        var done = 0
        var state = "done"
        var outcome = GalleryUploader.Outcome.DONE
        for (c in plan.upload) {
            if (!GalleryUploader.on(app) || !CollectionSettings.contractAtLeast(app, 4)) return GalleryUploader.Outcome.SKIPPED
            if (done >= MAX_PER_RUN || SystemClock.elapsedRealtime() - t0 > RUN_BUDGET_MS) { outcome = GalleryUploader.Outcome.MORE; break }
            val have = byId[c.id]?.parts.orEmpty()
            try {
                if (upload(app, device, c, have)) done++
            } catch (e: CancellationException) { throw e } catch (e: InsightsFailure) {
                when (e.code) {
                    507 -> { state = "full"; outcome = GalleryUploader.Outcome.DONE; break }
                    429 -> { state = "limit"; outcome = GalleryUploader.Outcome.RETRY; break }
                    401, 403 -> { outcome = GalleryUploader.Outcome.SKIPPED; state = "off"; break }
                    400, 404, 409, 410, 413, 422 -> { }        // refuzată pentru această copie — nu blocăm restul
                    else -> { outcome = GalleryUploader.Outcome.RETRY; state = "retry"; break }
                }
            } catch (_: Exception) { outcome = GalleryUploader.Outcome.RETRY; state = "retry"; break }
        }
        val waiting = (plan.waiting - done).coerceAtLeast(0)
        if (state == "done" && waiting > 0) state = if (GalleryUploader.cellularAllowed(app)) "uploading" else "wifi"
        publish(app, uid, scan, plan.copy(mirrored = plan.mirrored + done, waiting = waiting), state)
        GalleryUploader.setStatus(app, when (state) {
            "full" -> "Oglinda de pe site e plină. Restul rămâne pe telefon."
            "limit" -> "Limita zilnică a oglinzii. Continuă mâine."
            "retry" -> "Site-ul nu a răspuns. Reluăm pe Wi-Fi."
            "off" -> "Oglinda e oprită pe site. Semnează din nou."
            else -> if (waiting > 0) "Oglinda urcă: ${plan.mirrored + done} pe site, $waiting în așteptare." else "Oglinda e la zi: ${plan.mirrored + done} pe site."
        })
        return if (outcome == GalleryUploader.Outcome.DONE && waiting > 0 && state != "full") GalleryUploader.Outcome.MORE else outcome
    }

    private suspend fun patch(device: String, id: String, album: String?) {
        try {
            InsightsApi.json("/v2/mirror/$id", buildJsonObject { if (album != null) put("album", album); put("claim", true) }, "PATCH", mapOf("X-Device-ID" to device))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun registry(device: String): Pair<List<MirrorPlan.Remote>, Set<String>> {
        val o = InsightsApi.json("/v2/mirror/ids", null, "GET", mapOf("X-Device-ID" to device))
        val rows = (o["ids"] as? JsonArray).orEmpty().mapNotNull { el ->
            try {
                val a = el.jsonArray
                MirrorPlan.Remote(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content, a[2].jsonPrimitive.content, a[3].jsonPrimitive.content, a[4].jsonPrimitive.int == 1)
            } catch (_: Exception) { null }
        }
        val gone = (o["gone"] as? JsonArray).orEmpty().mapNotNull { try { it.jsonPrimitive.content } catch (_: Exception) { null } }.toSet()
        return rows to gone
    }

    // ─────────────────────────── citirea telefonului ───────────────────────────

    private suspend fun scan(app: ForjaApp): Scan = withContext(Dispatchers.IO) {
        val out = ArrayList<Candidate>()
        val gallery = canReadMedia(app) && (scanMedia(app, Kind.Photo, out) and scanMedia(app, Kind.Video, out))
        var loose = 0; var organized = 0; var bytes = 0L
        val docs = try {
            val (ok, l, o, b) = scanDocs(app, out)
            loose = l; organized = o; bytes = b; ok
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        Scan(out, gallery, docs, loose, organized, bytes)
    }

    /** true = citită complet (fără excepții), ca lipsa unei poze să poată însemna „ștearsă”. */
    private fun scanMedia(c: Context, kind: Kind, out: MutableList<Candidate>): Boolean {
        val video = kind == Kind.Video
        val collection = when {
            Build.VERSION.SDK_INT >= 29 && video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            Build.VERSION.SDK_INT >= 29 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        if (video) cols += MediaStore.MediaColumns.DURATION
        val selection = if (Build.VERSION.SDK_INT >= 30) "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else null
        return try {
            c.contentResolver.query(collection, cols.toTypedArray(), selection, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val name = cur.getString(1) ?: (if (video) "video" else "poză")
                    val album = cur.getString(2)?.takeIf { it.isNotBlank() } ?: "Galerie"
                    val mime = cur.getString(3) ?: if (video) "video/mp4" else "image/jpeg"
                    val size = if (cur.isNull(4)) 0L else cur.getLong(4)
                    val taken = if (!cur.isNull(5) && cur.getLong(5) > 0) cur.getLong(5) else (if (cur.isNull(6)) 0L else cur.getLong(6) * 1000L)
                    val uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    out += Candidate(MirrorPlan.mediaKey(kind, name, taken, size), kind, name.take(200), album.take(160), mime, taken, size, uri.toString(),
                        if (cur.isNull(7)) 0 else cur.getInt(7), if (cur.isNull(8)) 0 else cur.getInt(8), if (video && !cur.isNull(9)) cur.getLong(9) else 0L)
                }
            } != null
        } catch (_: Exception) { false }
    }

    private data class DocScan(val ok: Boolean, val loose: Int, val organized: Int, val bytes: Long)

    /** Folderele alese în Inventar: sursa (cu „Organizate”) și destinația, când e în altă parte. Coșul FORJA nu urcă. */
    private suspend fun scanDocs(app: ForjaApp, out: MutableList<Candidate>): DocScan {
        val organizer = DocumentOrganizer(app, app.prefs)
        val source = app.prefs.cleanupDocsTree.first().takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?.takeIf { t -> app.contentResolver.persistedUriPermissions.any { it.uri == t && it.isReadPermission } }
        val dest = DocDest.savedTree(app, app.prefs)?.takeUnless { d -> source != null && TreePaths.same(d, source) }
        if (source == null && dest == null) return DocScan(false, 0, 0, 0L)
        val seen = HashSet<String>()
        var ok = true; var loose = 0; var organized = 0; var bytes = 0L
        fun add(tree: Uri, items: List<com.forja.app.core.cleanup.DocItem>, prefix: String, isOrganized: Boolean) {
            val label = TreePaths.label(tree)
            for (d in items) {
                val path = prefix + d.path
                if ("De aruncat" in path || d.name.startsWith(".forja-") || d.name.startsWith(".")) continue
                val key = MirrorPlan.docKey(d.name, d.sizeBytes, d.lastModified)
                if (!seen.add(key)) continue
                if (isOrganized) organized++ else loose++
                bytes += d.sizeBytes
                out += Candidate(key, Kind.File, d.name.take(200), MirrorPlan.docAlbum(label, path), d.mime.ifBlank { "application/octet-stream" },
                    d.lastModified, d.sizeBytes, d.uri.toString())
            }
        }
        if (source != null) {
            val (items, warn) = organizer.inventory(source, limit = DOCS_MAX, skipDirIds = DocDest.skips(source, dest))
            if (warn.any { it.contains("Exception") || it.contains("accesibil") || it.startsWith("Am oprit") }) ok = false
            add(source, items, "", false)
            organizer.childDirId(source, DocumentOrganizer.ROOT_FOLDER)?.let { id ->
                val (org, w2) = organizer.inventory(source, startDocId = id, limit = DOCS_MAX)
                if (w2.isNotEmpty()) ok = false
                add(source, org, DocumentOrganizer.ROOT_FOLDER + "/", true)
            }
        }
        if (dest != null) {
            val (items, warn) = organizer.inventory(dest, limit = DOCS_MAX)
            if (warn.isNotEmpty()) ok = false
            add(dest, items, "", true)
        }
        return DocScan(ok, loose, organized, bytes)
    }

    // ─────────────────────────── urcarea unei copii ───────────────────────────

    /** Părțile care lipsesc de pe server. true = a urcat ceva. */
    private suspend fun upload(app: ForjaApp, device: String, c: Candidate, have: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(c.uri)
        val base = mapOf(
            "X-Device-ID" to device, "X-Mirror-Kind" to c.kind.code,
            "X-File-Name" to InsightsApi.header(c.name.replace(Regex("[\\p{Cntrl}]"), "_")),
            "X-File-Album" to InsightsApi.header(c.album.replace(Regex("[\\p{Cntrl}]"), "_")),
            "X-Taken-At" to c.takenAt.coerceAtLeast(0).toString(), "X-Orig-Bytes" to c.size.coerceAtLeast(0).toString()
        ) + (if (c.durationMs > 0) mapOf("X-Duration-Ms" to c.durationMs.toString()) else emptyMap())
        var sent = false
        suspend fun put(part: String, bytes: ByteArray, mime: String, extra: Map<String, String> = emptyMap()) {
            val path = "/v2/mirror/${c.id}" + (if (part == "file") "" else "/$part")
            InsightsApi.upload(path, bytes, "application/octet-stream", "PUT", base + extra + mapOf("X-Media-Type" to mime, "X-File-Sha256" to SyncTransport.sha256(bytes)))
            sent = true
        }
        when (c.kind) {
            Kind.Photo -> {
                val bmp = decodeUpright(app, uri, LONG_EDGE) ?: return@withContext false
                try {
                    if ('f' !in have) {
                        val jpeg = withExifDate(app, uri, jpeg(bmp, QUALITY))
                        put("file", jpeg, "image/jpeg", mapOf("X-Width" to bmp.width.toString(), "X-Height" to bmp.height.toString()))
                    }
                    if ('t' !in have) thumb(bmp)?.let { put("thumb", it, "image/jpeg") }
                } finally { bmp.recycle() }
            }
            Kind.Video -> {
                val poster = videoFrame(app, uri)
                try {
                    if (poster != null && 'p' !in have) fitJpeg(poster, POSTER_EDGE, POSTER_MAX)?.let { put("poster", it, c.mime) }
                    if (poster != null && 't' !in have) thumb(poster)?.let { put("thumb", it, c.mime) }
                } finally { poster?.recycle() }
                if ('f' !in have && c.size in 1..MirrorPlan.FILE_MAX && (poster != null || 'p' in have)) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext sent
                    put("file", bytes, c.mime, dims(c))
                }
            }
            Kind.File -> {
                if ('f' !in have) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
                    if (bytes.size > MirrorPlan.FILE_MAX) return@withContext false
                    put("file", bytes, c.mime)
                }
                if ('t' !in have) docThumb(app, uri, c.mime)?.let { put("thumb", it, c.mime) }
            }
        }
        sent
    }

    private fun dims(c: Candidate): Map<String, String> =
        if (c.width > 0 && c.height > 0) mapOf("X-Width" to c.width.toString(), "X-Height" to c.height.toString()) else emptyMap()

    /** Decodare cu eșantionare (≥ [edge] pe latura lungă), rotită după EXIF, apoi scalată la [edge]. */
    private fun decodeUpright(c: Context, uri: Uri, edge: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
            val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            raw?.let { orient(c, uri, it) }?.let { scaleTo(it, edge) }
        }
    } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }

    private fun orient(c: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val o = try { c.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } } catch (_: Exception) { null }
            ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun scaleTo(bmp: Bitmap, edge: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= edge) return bmp
        val s = edge.toFloat() / longest
        val out = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun jpeg(bmp: Bitmap, q: Int): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /** Scalează o copie (originalul rămâne) și coboară calitatea până încape în [maxBytes]. */
    private fun fitJpeg(bmp: Bitmap, edge: Int, maxBytes: Int): ByteArray? {
        var side = edge
        var q = 80
        repeat(5) {
            val longest = max(bmp.width, bmp.height)
            val s = if (longest > side) side.toFloat() / longest else 1f
            val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true) else bmp
            val bytes = jpeg(small, q)
            if (small !== bmp) small.recycle()
            if (bytes.size <= maxBytes) return bytes
            q = max(45, q - 12); side = max(160, side * 3 / 4)
        }
        return null
    }

    private fun thumb(bmp: Bitmap): ByteArray? = fitJpeg(bmp, THUMB_EDGE, THUMB_MAX)

    /** Data făcută (și aparatul) din EXIF-ul originalului trec în JPEG-ul de pe site; locația nu. */
    private fun withExifDate(c: Context, uri: Uri, jpeg: ByteArray): ByteArray {
        val tags = listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)
        return try {
            val src = c.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return jpeg
            val values = tags.mapNotNull { t -> src.getAttribute(t)?.let { t to it } }
            if (values.isEmpty()) return jpeg
            val tmp = File.createTempFile("mirror", ".jpg", c.cacheDir)
            try {
                tmp.writeBytes(jpeg)
                val dst = ExifInterface(tmp.absolutePath)
                for ((t, v) in values) dst.setAttribute(t, v)
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.saveAttributes()
                tmp.readBytes()
            } finally { tmp.delete() }
        } catch (_: Exception) { jpeg }
    }

    private fun videoFrame(c: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) try { return c.contentResolver.loadThumbnail(uri, Size(POSTER_EDGE, POSTER_EDGE), null) } catch (_: Exception) { }
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(c, uri); r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleTo(it, POSTER_EDGE) } }
        catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) { } }
    }

    /** Miniatura unui document: prima pagină a unui PDF sau imaginea însăși; altfel niciuna (site-ul arată iconița). */
    private fun docThumb(c: Context, uri: Uri, mime: String): ByteArray? = try {
        when {
            mime == "application/pdf" -> c.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount == 0) null else r.openPage(0).use { p ->
                        val s = THUMB_EDGE.toFloat() / max(p.width, p.height)
                        val bmp = Bitmap.createBitmap(max(1, (p.width * s).roundToInt()), max(1, (p.height * s).roundToInt()), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        thumb(bmp).also { bmp.recycle() }
                    }
                }
            }
            mime.startsWith("image/") -> decodeUpright(c, uri, THUMB_EDGE)?.let { b -> thumb(b).also { b.recycle() } }
            else -> null
        }
    } catch (_: Exception) { null }

    // ─────────────────────────── numărătoarea pentru site ───────────────────────────

    private suspend fun publish(app: ForjaApp, uid: String, scan: Scan, plan: MirrorPlan.Plan?, state: String) {
        val photos = scan.items.filter { it.kind == Kind.Photo }
        val videos = scan.items.filter { it.kind == Kind.Video }
        val docAlbums = scan.items.filter { it.kind == Kind.File }.map { it.album }.toSet().size
        StorageMirror.publish(app, uid, mapOf(
            "photos" to (if (scan.gallery) mapOf("count" to photos.size, "bytes" to photos.sumOf { it.size }) else null),
            "videos" to (if (scan.gallery) mapOf("count" to videos.size, "bytes" to videos.sumOf { it.size }) else null),
            "docs" to (if (scan.docs) mapOf("loose" to scan.docsLoose, "organized" to scan.docsOrganized, "bytes" to scan.docsBytes, "folders" to docAlbums) else null),
            "gallery" to mapOf(
                "total" to scan.items.size, "mirrored" to (plan?.mirrored ?: 0), "waiting" to (plan?.waiting ?: 0), "tooBig" to (plan?.tooBig ?: 0),
                "state" to state, "cellular" to GalleryUploader.cellularAllowed(app), "lastAt" to System.currentTimeMillis()
            ),
            "updatedAt" to System.currentTimeMillis()
        ))
    }
}
