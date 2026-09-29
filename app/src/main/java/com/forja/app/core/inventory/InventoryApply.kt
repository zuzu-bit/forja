package com.forja.app.core.inventory

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.cleanup.MoveOutcome
import com.forja.app.core.cleanup.sanitizeFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// ═══════════════ Inventar — aplicarea planului ═══════════════
// Poze: RELATIVE_PATH → „<rădăcină>/<dosar>/” după acordul de scriere (API 30+: MediaStore.createWriteRequest, în bucăți
// de ≤ 500). Rădăcina e aleasă de om în „Locație” (implicit „Pictures/FORJA/”) și adaptată pe element de MediaRoots
// (un video pe API 29 nu poate sta în Pictures → „Movies/…”), ca nimic să nu eșueze în tăcere. „De aruncat” → coșul
// sistemului (createTrashRequest, recuperabil 30 de zile — sistemul îl execută la acord, aici doar verificăm); sub
// API 30: ștergere directă prin CleanupEngine.deleteLegacy. Mutarea proprie (nu CleanupEngine.moveToRelativePath)
// pentru că acela acceptă doar „Pictures/…”; tehnica e aceeași (coliziune de nume → „nume · id”, verificare).
// Documente: mutări SAF prin DocumentOrganizer.moveToPath în „<destinație>/<sub>/<dosar>” (implicit
// „<folderul ales>/Organizate/<dosar>”); „De aruncat (FORJA)” rămâne mereu în folderul sursă.
// Fiecare aplicare întoarce și un [Landing]: unde au ajuns lucrurile, ca ecranul final să deschidă exact acolo.

internal object InventoryApply {
    /** `moved` = id-urile care stau acum în dosarul lor (mutate sau deja acolo), pentru rezumatul pe dosare. */
    class Outcome(val result: ApplyResult, val removed: Set<String>, val folders: Int, val moved: Set<String> = emptySet())

    private class Move(val item: ItemRec, val path: String, val segment: String)

    /** Un singur segment de dosar, curățat ca în CleanupEngine (fără bare, fără caractere interzise, ≤ 40). */
    fun segment(name: String): String =
        sanitizeFolder(name.replace('/', '-').replace('\\', '-')).trim().trim('.').ifBlank { InvRules.DIVERSE }

    /** Ținta unui element: rădăcina aleasă (adaptată pentru video pe API 29) + dosarul. */
    fun target(r: ItemRec, folderName: String, root: String = MediaRoots.DEFAULT): String =
        MediaRoots.forItem(root, r.video) + segment(folderName) + "/"

    /** Mutările încă de făcut (elementele care nu sunt deja în dosarul lor), în ordinea dosarelor din plan. */
    fun pendingMoves(doc: PlanDoc, items: Map<String, ItemRec>): List<ItemRec> {
        val root = doc.dest.mediaRoot()
        val out = ArrayList<ItemRec>()
        for (f in doc.folders) {
            for (id in f.itemIds) {
                val r = items[id] ?: continue
                if (r.relPath != target(r, f.name, root)) out += r
            }
        }
        return out
    }

    fun pendingTrash(doc: PlanDoc, items: Map<String, ItemRec>): List<ItemRec> = doc.trash.itemIds.mapNotNull { items[it] }

    /** Din bucata coșului, ce mai cere dialogul: fără pozele aflate deja în coș ([trashed] = id-uri MediaStore). */
    fun stillToTrash(chunk: List<ItemRec>, trashed: Set<Long>): List<ItemRec> =
        if (trashed.isEmpty()) chunk else chunk.filter { it.mediaId !in trashed }

    /**
     * Pozele. API 30+: se mută doar bucata acceptată în dialogul de scriere ([grantMoves]; fără dialog nu se mută
     * nimic — altfel ar eșua sute de elemente fără acord), iar din „De aruncat” se numără doar bucata trimisă la coș
     * ([grantTrash]: cele din dialog plus cele găsite deja în coș). Elementele deja aflate în dosarul lor se numără
     * mutate oricum.
     * API 29: mutări directe (reușesc doar elementele FORJA; restul cer acord per element → eșuate). Sub 29 nu există
     * RELATIVE_PATH: mutările eșuează, gunoiul se șterge direct (legacy).
     */
    suspend fun photos(
        ctx: Context,
        doc: PlanDoc,
        items: Map<String, ItemRec>,
        grantMoves: List<String>?,
        grantTrash: List<String>?,
        step: suspend (Int, Int, Uri?) -> Unit
    ): Outcome {
        val sdk = Build.VERSION.SDK_INT
        val root = doc.dest.mediaRoot()
        val removed = HashSet<String>()
        val placed = LinkedHashSet<String>()
        val touched = LinkedHashSet<String>()
        var firstImage: ItemRec? = null
        var firstVideo: ItemRec? = null
        fun landed(r: ItemRec, seg: String) {
            placed += r.id
            touched += seg
            if (r.video) { if (firstVideo == null) firstVideo = r } else if (firstImage == null) firstImage = r
        }
        var moved = 0
        var trashed = 0
        var failed = 0
        var freed = 0L

        val todo = ArrayList<Move>()
        for (f in doc.folders) {
            val seg = segment(f.name)
            for (id in f.itemIds) {
                val r = items[id] ?: continue
                val path = target(r, f.name, root)
                if (r.relPath == path) { moved++; removed += id; landed(r, seg) } else todo += Move(r, path, seg)
            }
        }
        val moves = when {
            sdk >= 30 && grantMoves != null -> { val g = grantMoves.toHashSet(); todo.filter { it.item.id in g } }
            sdk >= 30 -> emptyList()
            else -> todo
        }
        val trashTodo = if (sdk >= 30) {
            val inTrash = doc.trash.itemIds.toHashSet()
            grantTrash.orEmpty().filter { it in inTrash }.mapNotNull { items[it] }
        } else pendingTrash(doc, items)

        val total = moves.size + trashTodo.size
        var done = 0
        step(0, total, null)
        for (m in moves) {
            currentCoroutineContext().ensureActive()
            when (withContext(Dispatchers.IO) { MediaMover.move(ctx, m.item, m.path) }) {
                MediaMover.Result.Moved -> { moved++; removed += m.item.id; landed(m.item, m.segment) }
                MediaMover.Result.Gone -> { failed++; removed += m.item.id }   // șters între timp: iese din plan
                MediaMover.Result.Denied, MediaMover.Result.Failed -> failed++
            }
            done++
            step(done, total, Uri.parse(m.item.uri))
        }
        if (trashTodo.isNotEmpty()) {
            if (sdk >= 30) {
                // Coșul l-a făcut deja sistemul (la acordul din dialog): ce nu se mai vede în MediaStore e la gunoi.
                val visible = withContext(Dispatchers.IO) { MediaMover.visibleIds(ctx, trashTodo.map { it.mediaId }) }
                for (r in trashTodo) {
                    if (visible == null || r.mediaId in visible) failed++
                    else { trashed++; freed += r.bytes; removed += r.id }
                    done++
                }
                step(done, total, null)
            } else {
                val engine = CleanupEngine(ctx, ForjaApp.from(ctx).prefs)
                for (r in trashTodo) {
                    currentCoroutineContext().ensureActive()
                    if (engine.deleteLegacy(listOf(Uri.parse(r.uri))) > 0) { trashed++; freed += r.bytes; removed += r.id } else failed++
                    done++
                    step(done, total, Uri.parse(r.uri))
                }
            }
        }
        val first = firstImage ?: firstVideo
        val landing = if (first != null) withContext(Dispatchers.IO) { photoLanding(ctx, root, touched, first) } else null
        return Outcome(ApplyResult(moved, trashed, failed, freed, landing), removed, touched.size, placed)
    }

    /**
     * Locul pozelor după mutare: rădăcina („Pictures/FORJA”) și, dacă s-a atins un singur dosar, dosarul lui — ca
     * documente ExternalStorage („primary:Pictures/FORJA”), pe volumul primului element (memoria internă sau cardul).
     */
    private fun photoLanding(ctx: Context, root: String, touched: Set<String>, first: ItemRec): Landing {
        val uri = Uri.parse(first.uri)
        val (volume, bucket) = MediaMover.volumeAndBucket(ctx, uri)
        val docVolume = if (volume == null || volume == "external_primary" || volume == "external") "primary" else volume.uppercase()
        val itemRoot = MediaRoots.forItem(root, first.video)
        fun place(rel: String): InvPlace {
            val clean = rel.trim('/')
            val docId = "$docVolume:$clean"
            val folder = try { DocumentsContract.buildDocumentUri(TreePaths.EXTERNAL, docId) } catch (_: Exception) { null }
            return InvPlace(folder, clean, TreePaths.storagePath(TreePaths.EXTERNAL, docId))
        }
        val single = touched.singleOrNull()?.let { place(itemRoot + it) }
        return Landing(
            kind = InvKind.Photos, root = place(itemRoot), single = single, segments = touched.toSet(),
            first = uri, firstMime = first.mime.ifBlank { if (first.video) "video/*" else "image/*" },
            bucketId = if (single != null) bucket else null
        )
    }

    /**
     * Documentele: mutări SAF (întâi mutare nativă, altfel copie verificată + ștergerea originalului) în destinația
     * aleasă; „De aruncat (FORJA)” rămâne în folderul sursă („pus deoparte”, nu „organizat”).
     */
    suspend fun documents(
        ctx: Context,
        doc: PlanDoc,
        items: Map<String, ItemRec>,
        tree: String?,
        step: suspend (Int, Int, Uri?) -> Unit
    ): Outcome {
        val source = tree?.let { Uri.parse(it) } ?: return Outcome(ApplyResult(0, 0, PlanEdits.count(doc), 0L), emptySet(), 0)
        val destTree = doc.dest.docsTree(source) ?: source
        val sub = doc.dest.docsSub().trim('/')
        val organizer = DocumentOrganizer(ctx, ForjaApp.from(ctx).prefs)
        val removed = HashSet<String>()
        val placed = LinkedHashSet<String>()
        val touched = LinkedHashSet<String>()
        var moved = 0
        var trashed = 0
        var failed = 0
        var freed = 0L
        val moves = ArrayList<Move>()
        for (f in doc.folders) {
            val seg = segment(f.name)
            val path = if (sub.isBlank()) seg else "$sub/$seg"
            for (id in f.itemIds) items[id]?.let { moves += Move(it, path, seg) }
        }
        val trash = pendingTrash(doc, items)
        val total = moves.size + trash.size
        var done = 0
        step(0, total, null)
        for (m in moves) {
            currentCoroutineContext().ensureActive()
            when (organizer.moveToPath(destTree, m.item.toDocItem(), m.path)) {
                is MoveOutcome.Moved, is MoveOutcome.Copied -> { moved++; removed += m.item.id; placed += m.item.id; touched += m.segment }
                is MoveOutcome.Failed -> { failed++; if (gone(ctx, m.item)) removed += m.item.id }
            }
            done++
            step(done, total, Uri.parse(m.item.uri))
        }
        for (r in trash) {
            currentCoroutineContext().ensureActive()
            when (organizer.moveToPath(source, r.toDocItem(), DOC_TRASH_DIR)) {
                is MoveOutcome.Moved -> { trashed++; freed += r.bytes; removed += r.id }
                // Copia a ajuns în „De aruncat”, dar originalul a rămas: nu mai încercăm (ar face încă o copie).
                is MoveOutcome.Copied -> { failed++; removed += r.id }
                is MoveOutcome.Failed -> { failed++; if (gone(ctx, r)) removed += r.id }
            }
            done++
            step(done, total, Uri.parse(r.uri))
        }
        val landing = if (placed.isNotEmpty()) withContext(Dispatchers.IO) { docLanding(ctx, destTree, sub, touched) } else null
        return Outcome(ApplyResult(moved, trashed, failed, freed, landing), removed, touched.size, placed)
    }

    /** Locul documentelor: „<destinație>/<sub>” și dosarul unic, dacă s-a atins unul singur (URI-uri de arbore SAF). */
    private fun docLanding(ctx: Context, destTree: Uri, sub: String, touched: Set<String>): Landing? = try {
        val top = DocumentFile.fromTreeUri(ctx, destTree)
        val base = if (sub.isBlank()) top else top?.findFile(sub)?.takeIf { it.isDirectory } ?: top
        val treeLabel = TreePaths.label(destTree)
        val baseLabel = if (sub.isBlank() || base == top) treeLabel else "$treeLabel/$sub"
        fun place(dir: DocumentFile?, label: String): InvPlace {
            val uri = dir?.uri ?: destTree
            return InvPlace(uri, label, TreePaths.storagePath(uri.authority, TreePaths.docId(uri)))
        }
        val single = touched.singleOrNull()?.let { seg ->
            base?.findFile(seg)?.takeIf { it.isDirectory }?.let { place(it, "$baseLabel/$seg") }
        }
        Landing(kind = InvKind.Documents, root = place(base, baseLabel), single = single, segments = touched.toSet())
    } catch (_: Exception) {
        null
    }

    private suspend fun gone(ctx: Context, r: ItemRec): Boolean = withContext(Dispatchers.IO) {
        try { DocumentFile.fromSingleUri(ctx, Uri.parse(r.uri))?.exists() == false } catch (_: Exception) { false }
    }
}

/** Actualizarea RELATIVE_PATH a unui element MediaStore (API 29+), cu verificare. Blocant: se cheamă de pe IO. */
internal object MediaMover {
    enum class Result { Moved, Gone, Denied, Failed }

    fun move(ctx: Context, r: ItemRec, relPath: String): Result {
        if (Build.VERSION.SDK_INT < 29) return Result.Failed
        val cr = ctx.contentResolver
        val uri = Uri.parse(r.uri)
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                if (nameTaken(ctx, r, relPath)) put(MediaStore.MediaColumns.DISPLAY_NAME, collisionName(r.name, r.mediaId))
            }
            val n = cr.update(uri, values, null, null)
            when {
                n >= 1 -> { val now = pathOf(ctx, uri); if (now == null || now == relPath) Result.Moved else Result.Failed }
                exists(ctx, uri) -> Result.Failed
                else -> Result.Gone
            }
        } catch (_: SecurityException) {
            Result.Denied
        } catch (_: Exception) {
            Result.Failed
        }
    }

    private fun collection(video: Boolean): Uri =
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun nameTaken(ctx: Context, r: ItemRec, relPath: String): Boolean = try {
        ctx.contentResolver.query(
            collection(r.video), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns._ID} != ?",
            arrayOf(relPath, r.name, r.mediaId.toString()), null
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) { false }

    private fun pathOf(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun exists(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    } catch (_: Exception) { true }

    /** „nume · id.ext” — ca în CleanupEngine, când dosarul are deja un fișier cu același nume. */
    private fun collisionName(name: String, id: Long): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} · $id${name.substring(dot)}" else "$name · $id"
    }

    /** Volumul MediaStore („external_primary” sau id-ul cardului) și albumul (BUCKET_ID) unui element, după mutare. */
    fun volumeAndBucket(ctx: Context, uri: Uri): Pair<String?, Long?> {
        if (Build.VERSION.SDK_INT < 29) return null to null
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.BUCKET_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getLong(1)) else null to null
            } ?: (null to null)
        } catch (_: Exception) { null to null }
    }

    /** Care dintre [ids] încă se văd în MediaStore (cele din coș nu apar în interogarea implicită); null la eroare. */
    fun visibleIds(ctx: Context, ids: List<Long>): Set<Long>? = try {
        val out = HashSet<Long>()
        for (chunk in ids.chunked(400)) {
            val sel = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})"
            ctx.contentResolver.query(MediaQuery.collection(), arrayOf(MediaStore.MediaColumns._ID), sel, chunk.map { it.toString() }.toTypedArray(), null)
                ?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
        }
        out
    } catch (_: Exception) { null }

    /**
     * Care dintre [ids] stau deja în coșul MediaStore (API 30+). Doar rândurile cu IS_TRASHED = 1 citit din cursor: un
     * furnizor care ar ignora argumentul de coș nu poate face o poză vizibilă să pară aruncată. Goală sub API 30 sau la
     * eroare — atunci dialogul le cere pe toate, ca înainte. Blocant (≤ 500 de id-uri, două interogări): Inventory.
     * trashRequest o cheamă pe IO.
     */
    fun trashedIds(ctx: Context, ids: List<Long>): Set<Long> {
        if (Build.VERSION.SDK_INT < 30 || ids.isEmpty()) return emptySet()
        return try {
            val out = HashSet<Long>()
            for (chunk in ids.chunked(400)) {
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, chunk.map { it.toString() }.toTypedArray())
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                ctx.contentResolver.query(
                    MediaQuery.collection(), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED), args, null
                )?.use { c -> while (c.moveToNext()) if (!c.isNull(1) && c.getInt(1) == 1) out += c.getLong(0) }
            }
            out
        } catch (_: Exception) {
            emptySet()
        }
    }
}
