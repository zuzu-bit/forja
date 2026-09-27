package com.forja.app.core.cleanup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.forja.app.core.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

// ═══════════════ Documente prin SAF — inventar, detecții, mutare sigură, anulare ═══════════════

data class DocItem(
    val uri: Uri,
    val documentId: String,
    val parentUri: Uri,
    val path: String,          // relativ la folderul ales, ex. "Facturi/enel-03.pdf"
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val flags: Int
) {
    val key: String get() = uri.toString()
}

data class DocDuplicateGroup(val sha256: String, val keeper: DocItem, val copies: List<DocItem>)

data class DocReport(
    val items: List<DocItem>,
    val duplicates: List<DocDuplicateGroup>,
    val large: List<DocItem>,
    val old: List<DocItem>,
    val suspects: List<Pair<DocItem, String>>,   // nume de copie: „(1)", „ copy", „~$"
    val warnings: List<String>
) {
    val isEmpty: Boolean get() = duplicates.isEmpty() && large.isEmpty() && old.isEmpty() && suspects.isEmpty()

    fun without(keys: Set<String>): DocReport = copy(
        items = items.filter { it.key !in keys },
        duplicates = duplicates.mapNotNull { g ->
            val keep = if (g.keeper.key in keys) g.copies.firstOrNull { it.key !in keys } else g.keeper
            val copies = g.copies.filter { it.key !in keys && it.key != keep?.key }
            if (keep == null || copies.isEmpty()) null else DocDuplicateGroup(g.sha256, keep, copies)
        },
        large = large.filter { it.key !in keys },
        old = old.filter { it.key !in keys },
        suspects = suspects.filter { it.first.key !in keys }
    )
}

sealed class MoveOutcome {
    data class Moved(val newUri: Uri) : MoveOutcome()
    data class Copied(val newUri: Uri, val originalKept: Boolean) : MoveOutcome()
    data class Failed(val reason: String) : MoveOutcome()
}

/** O intrare din jurnalul de anulare: de unde a plecat, unde a ajuns. */
@Serializable
data class DocUndoEntry(
    val source: String,        // uri original
    val result: String,        // uri după mutare
    val parent: String,        // folderul original
    val targetParent: String,  // folderul destinație
    val name: String,
    val kind: String,          // "move" | "copy"
    val at: Long
)

class DocumentOrganizer(private val context: Context, private val prefs: Prefs) {

    companion object {
        const val ROOT_FOLDER = "Organizate"
        const val LARGE_BYTES = 20_000_000L
        const val OLD_MS = 90L * 86_400_000L
        const val MAX_UNDO = 50
        private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
        private val IGNORED_DIRS = setOf(".git", "node_modules", "android", ".gradle", ".idea", ROOT_FOLDER, ".thumbnails", ".trashed")
        private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "cfg")
    }

    private val cr: ContentResolver get() = context.contentResolver
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val undoSerializer = ListSerializer(DocUndoEntry.serializer())

    // ─────────────────────────── Folderul persistat ───────────────────────────

    /** Folderul ales data trecută, dacă permisiunea persistentă (citire + scriere) mai există. */
    suspend fun persistedTree(): Uri? = withContext(Dispatchers.IO) {
        val perms = try { cr.persistedUriPermissions } catch (_: Exception) { emptyList() }
        val usable = perms.filter { it.isReadPermission && it.isWritePermission }
        if (usable.isEmpty()) return@withContext null
        val saved = try { prefs.cleanupDocsTree.first() } catch (_: Exception) { "" }
        val match = usable.firstOrNull { it.uri.toString() == saved }?.uri
        if (match != null) return@withContext match
        val fallback = usable.maxByOrNull { it.persistedTime }?.uri
        if (fallback != null) try { prefs.setCleanupDocsTree(fallback.toString()) } catch (_: Exception) { }
        fallback
    }

    /** După OpenDocumentTree: păstrăm permisiunea și folderul. */
    suspend fun rememberTree(tree: Uri) {
        try {
            cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        try { prefs.setCleanupDocsTree(tree.toString()) } catch (_: Exception) { }
    }

    fun treeName(tree: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val tail = id.substringAfter(':', id).trimEnd('/')
        when {
            tail.isBlank() -> if (id.startsWith("primary")) "Memoria internă" else "Folder"
            else -> tail.substringAfterLast('/')
        }
    } catch (_: Exception) { "Folder" }

    // ─────────────────────────── Inventar (BFS) ───────────────────────────

    suspend fun inventory(
        tree: Uri, recursive: Boolean = true, maxDepth: Int = 8, limit: Int = 15_000
    ): Pair<List<DocItem>, List<String>> = withContext(Dispatchers.IO) {
        val out = ArrayList<DocItem>()
        val warnings = ArrayList<String>()
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) {
            return@withContext out to listOf("Folderul nu mai e accesibil. Alege-l din nou.")
        }
        val queue = ArrayDeque<Triple<String, String, Int>>()   // (documentId, cale relativă, adâncime)
        queue.add(Triple(rootId, "", 0))
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS
        )
        var truncated = false
        while (queue.isNotEmpty() && !truncated) {
            currentCoroutineContext().ensureActive()
            val (parentId, path, depth) = queue.poll() ?: break
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            try {
                cr.query(childrenUri, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: "fișier"
                        val mime = c.getString(2) ?: ""
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val mod = if (c.isNull(4)) 0L else c.getLong(4)
                        val flags = if (c.isNull(5)) 0 else c.getInt(5)
                        if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                        val childPath = if (path.isEmpty()) name else "$path/$name"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (recursive && depth < maxDepth && name !in IGNORED_DIRS && !name.startsWith(".")) {
                                queue.add(Triple(docId, childPath, depth + 1))
                            }
                            continue
                        }
                        out += DocItem(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            documentId = docId, parentUri = parentUri, path = childPath,
                            name = name, mime = mime, sizeBytes = size, lastModified = mod, flags = flags
                        )
                        if (out.size >= limit) { truncated = true; break }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (warnings.size < 10) warnings += "${path.ifEmpty { "/" }}: ${e.javaClass.simpleName}"
            }
        }
        if (truncated) warnings += "Am oprit la $limit fișiere — alege un folder mai mic pentru restul."
        out to warnings
    }

    // ─────────────────────────── Detecții ───────────────────────────

    suspend fun detect(items: List<DocItem>): DocReport = withContext(Dispatchers.IO) {
        val warnings = ArrayList<String>()
        val now = System.currentTimeMillis()
        val bySize = HashMap<Long, MutableList<DocItem>>()
        items.forEach { if (it.sizeBytes > 0) bySize.getOrPut(it.sizeBytes) { ArrayList() } += it }
        val bySha = HashMap<String, MutableList<DocItem>>()
        for ((_, group) in bySize) {
            if (group.size < 2) continue
            for (item in group) {
                currentCoroutineContext().ensureActive()
                try {
                    bySha.getOrPut(sha256(item.uri)) { ArrayList() } += item
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    if (warnings.size < 10) warnings += "${item.name}: ${e.javaClass.simpleName}"
                }
            }
        }
        val duplicates = bySha.entries.filter { it.value.size >= 2 }.map { (sha, g) ->
            val sorted = g.sortedWith(compareBy<DocItem> { if (it.lastModified > 0) it.lastModified else Long.MAX_VALUE }.thenBy { it.path.length })
            DocDuplicateGroup(sha, sorted.first(), sorted.drop(1))
        }.sortedByDescending { g -> g.copies.sumOf { it.sizeBytes } }
        val dupKeys = duplicates.flatMap { g -> g.copies.map { it.key } + g.keeper.key }.toHashSet()
        val large = items.filter { it.sizeBytes > LARGE_BYTES }.sortedByDescending { it.sizeBytes }
        val old = items.filter { it.lastModified > 0 && now - it.lastModified > OLD_MS && it.key !in dupKeys }
            .sortedBy { it.lastModified }
        val suspects = items.mapNotNull { d ->
            if (d.key in dupKeys) return@mapNotNull null
            val n = d.name
            val reason = when {
                n.startsWith("~$") -> "fișier temporar Office"
                Regex(".*\\(\\d+\\)(\\.[^.]+)?$").matches(n) -> "nume de copie „(1)”"
                n.contains(" copy", ignoreCase = true) || n.contains(" - Copy", ignoreCase = true) -> "nume de copie"
                else -> null
            }
            reason?.let { d to it }
        }
        DocReport(items, duplicates, large, old, suspects, warnings)
    }

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

    /** Fragment de text (≤ maxChars) doar pentru fișiere text; PDF/Office nu se citesc (doar nume + mărime). */
    suspend fun textSnippet(item: DocItem, maxChars: Int = 2000): String? = withContext(Dispatchers.IO) {
        if (!isTextLike(item)) return@withContext null
        try {
            val raw = cr.openInputStream(item.uri)?.use { s ->
                val buf = ByteArray(8192)
                var total = 0
                while (total < buf.size) {
                    val n = s.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                buf.copyOf(total)
            } ?: return@withContext null
            if (raw.isEmpty()) return@withContext null
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            // Ultimul caracter poate fi tăiat la 8 KB: renunțăm la cel mult 3 octeți de la coadă.
            var text: String? = null
            for (trim in 0..3) {
                if (raw.size - trim <= 0) break
                try { text = decoder.decode(ByteBuffer.wrap(raw, 0, raw.size - trim)).toString(); break } catch (_: Exception) { decoder.reset() }
            }
            val t = text ?: return@withContext null
            if (t.any { it == '\u0000' }) return@withContext null
            t.replace(Regex("\\s+"), " ").trim().take(maxChars).ifBlank { null }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** PDF (după MIME sau extensie) — pleacă întreg la analiza pe server, dacă are ≤ maxBytes. */
    fun isPdf(item: DocItem): Boolean =
        item.mime.equals("application/pdf", ignoreCase = true) ||
            (item.name.substringAfterLast('.', "").equals("pdf", ignoreCase = true) &&
                (item.mime.isBlank() || item.mime == "application/octet-stream" || item.mime == "*/*"))

    /** PDF care poate pleca întreg: ≤ maxBytes și cu antetul „%PDF" (citim doar primii octeți, nu tot fișierul). */
    suspend fun pdfSendable(item: DocItem, maxBytes: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isPdf(item) || item.sizeBytes <= 0 || item.sizeBytes > maxBytes) return@withContext false
        try {
            cr.openInputStream(item.uri)?.use { s ->
                val head = ByteArray(5)
                var total = 0
                while (total < head.size) {
                    val n = s.read(head, total, head.size - total)
                    if (n < 0) break
                    total += n
                }
                total >= head.size && isPdfHeader(head)
            } ?: false
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    /**
     * Octeții unui PDF ≤ maxBytes, verificați după antetul „%PDF"; null dacă e prea mare, nu e PDF sau nu se poate citi.
     * Blocant (se apelează de pe firul care scrie cererea): un singur tampon, exact cât spune SAF, fără copie în plus.
     */
    fun readPdf(item: DocItem, maxBytes: Long): ByteArray? {
        if (!isPdf(item) || item.sizeBytes <= 0 || item.sizeBytes > maxBytes) return null
        return try {
            cr.openInputStream(item.uri)?.use { s ->
                val bytes = ByteArray(item.sizeBytes.toInt())
                var total = 0
                while (total < bytes.size) {
                    val n = s.read(bytes, total, bytes.size - total)
                    if (n < 0) break
                    total += n
                }
                // Mai lung decât a spus SAF → nu ne încredem în mărime; mai scurt → doar cât s-a citit.
                val out = if (s.read() != -1) null else if (total < bytes.size) bytes.copyOf(total) else bytes
                out?.takeIf { isPdfHeader(it) }
            }
        } catch (_: Exception) { null }
    }

    /** Ca `readPdf`, din corutine. */
    suspend fun pdfBytes(item: DocItem, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) { readPdf(item, maxBytes) }

    private fun isPdfHeader(b: ByteArray): Boolean =
        b.size >= 5 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() && b[3] == 'F'.code.toByte()

    fun isTextLike(item: DocItem): Boolean {
        val m = item.mime.lowercase()
        if (m.startsWith("text/")) return true
        if (m in setOf("application/json", "application/xml", "application/csv", "application/x-yaml", "application/yaml")) return true
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXT && (m.isBlank() || m == "application/octet-stream" || m == "*/*")
    }

    // ─────────────────────────── Mutare ───────────────────────────

    private fun ensureFolder(tree: Uri, category: String): DocumentFile? = ensureFolderPath(tree, "$ROOT_FOLDER/${sanitizeFolder(category)}")

    /** Creează (dacă lipsește) o cale de dosare relativă la rădăcina aleasă, ex. „FORJA/Facturi"; ≤ 8 segmente curățate. */
    private fun ensureFolderPath(tree: Uri, path: String): DocumentFile? {
        val bad = Regex("[\\\\:*?\"<>|\\p{Cntrl}]")
        val segments = path.replace('\\', '/').split('/').map { it.replace(bad, "").trim().trim('.') }
            .filter { it.isNotBlank() && it != ".." }.take(8)
        if (segments.isEmpty()) return null
        var dir = DocumentFile.fromTreeUri(context, tree) ?: return null
        for (seg in segments) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: dir.createDirectory(seg) ?: return null
        }
        return dir
    }

    /** Mută un fișier sub „Organizate/<categorie>/": întâi moveDocument, apoi copiere verificată + ștergere. */
    suspend fun move(tree: Uri, item: DocItem, category: String): MoveOutcome =
        moveToPath(tree, item, "$ROOT_FOLDER/${sanitizeFolder(category)}")

    /** Mutare într-un dosar relativ la rădăcină (ex. „FORJA/Facturi") — destinații alese din panoul online. */
    suspend fun moveToPath(tree: Uri, item: DocItem, path: String): MoveOutcome = withContext(Dispatchers.IO) {
        val target = ensureFolderPath(tree, path) ?: return@withContext MoveOutcome.Failed("Nu pot crea dosarul „$path”.")
        val targetUri = target.uri
        if (targetUri == item.parentUri) return@withContext MoveOutcome.Failed("Fișierul e deja acolo.")
        val finalName = if (target.findFile(item.name) != null) collisionName(item.name) else item.name

        // 1) Mutare nativă (același provider, flag de mutare).
        if (item.flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0 && item.uri.authority == targetUri.authority && finalName == item.name) {
            try {
                val moved = DocumentsContract.moveDocument(cr, item.uri, item.parentUri, targetUri)
                if (moved != null) {
                    journal(DocUndoEntry(item.uri.toString(), moved.toString(), item.parentUri.toString(), targetUri.toString(), item.name, "move", System.currentTimeMillis()))
                    indexIfExternal(moved)
                    return@withContext MoveOutcome.Moved(moved)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }

        // 2) Copiere în etape: .part → verificare SHA → redenumire → ștergere original.
        val srcSha = try { sha256(item.uri) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return@withContext MoveOutcome.Failed("Nu pot citi „${item.name}”.")
        }
        val partName = ".forja-${UUID.randomUUID().toString().take(8)}.part"
        val mime = item.mime.ifBlank { "application/octet-stream" }
        val staged: Uri = try {
            DocumentsContract.createDocument(cr, targetUri, mime, partName)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: return@withContext MoveOutcome.Failed("Nu pot scrie în dosarul destinație.")
        try {
            cr.openInputStream(item.uri)!!.use { input ->
                cr.openOutputStream(staged, "wt")!!.use { output ->
                    val buf = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            val dstSha = sha256(staged)
            if (dstSha != srcSha) {
                try { DocumentsContract.deleteDocument(cr, staged) } catch (_: Exception) { }
                return@withContext MoveOutcome.Failed("Copia nu s-a verificat — originalul rămâne pe loc.")
            }
        } catch (e: CancellationException) {
            try { DocumentsContract.deleteDocument(cr, staged) } catch (_: Exception) { }
            throw e
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, staged) } catch (_: Exception) { }
            return@withContext MoveOutcome.Failed("Copierea a eșuat: ${e.javaClass.simpleName}.")
        }
        val renamed: Uri = try { DocumentsContract.renameDocument(cr, staged, finalName) ?: staged } catch (e: CancellationException) { throw e } catch (_: Exception) { staged }
        val deleted = try { DocumentsContract.deleteDocument(cr, item.uri) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        journal(DocUndoEntry(item.uri.toString(), renamed.toString(), item.parentUri.toString(), targetUri.toString(), finalName, if (deleted) "move" else "copy", System.currentTimeMillis()))
        indexIfExternal(renamed)
        if (deleted) MoveOutcome.Moved(renamed) else MoveOutcome.Copied(renamed, originalKept = true)
    }

    suspend fun delete(item: DocItem): Boolean = withContext(Dispatchers.IO) {
        try { DocumentsContract.deleteDocument(cr, item.uri) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            try { DocumentFile.fromSingleUri(context, item.uri)?.delete() ?: false } catch (_: Exception) { false }
        }
    }

    private fun collisionName(name: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} · $id${name.substring(dot)}" else "$name · $id"
    }

    /** Galeriile văd pozele mutate pe memoria internă doar după o re-indexare. */
    private fun indexIfExternal(uri: Uri) {
        try {
            if (uri.authority != EXTERNAL_AUTHORITY) return
            val id = DocumentsContract.getDocumentId(uri)
            if (!id.startsWith("primary:")) return
            @Suppress("DEPRECATION")
            val f = File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:"))
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
        } catch (_: Exception) { }
    }

    // ─────────────────────────── Jurnal de anulare ───────────────────────────

    suspend fun undoJournal(): List<DocUndoEntry> {
        val raw = try { prefs.cleanupDocsUndo.first() } catch (_: Exception) { "" }
        if (raw.isBlank()) return emptyList()
        return try { json.decodeFromString(undoSerializer, raw) } catch (_: Exception) { emptyList() }
    }

    private suspend fun journal(entry: DocUndoEntry) {
        val list = (undoJournal() + entry).takeLast(MAX_UNDO)
        try { prefs.setCleanupDocsUndo(json.encodeToString(undoSerializer, list)) } catch (_: Exception) { }
    }

    private suspend fun saveJournal(list: List<DocUndoEntry>) {
        try { prefs.setCleanupDocsUndo(json.encodeToString(undoSerializer, list)) } catch (_: Exception) { }
    }

    /** Anulează ultima mutare: fișierul se întoarce în folderul de unde a plecat. */
    suspend fun undoLast(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val list = undoJournal()
        val last = list.lastOrNull() ?: return@withContext false to "Nimic de anulat."
        val result = Uri.parse(last.result)
        val parent = Uri.parse(last.parent)
        val targetParent = Uri.parse(last.targetParent)
        val ok: Boolean = try {
            if (last.kind == "copy") {
                // Originalul a rămas pe loc: e de ajuns să ștergem copia.
                DocumentsContract.deleteDocument(cr, result)
            } else {
                val back = try { DocumentsContract.moveDocument(cr, result, targetParent, parent) } catch (_: Exception) { null }
                if (back != null) { indexIfExternal(back); true } else copyBack(result, parent, last.name)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        saveJournal(list.dropLast(1))
        if (ok) true to "„${last.name}” s-a întors la locul lui." else false to "Nu am putut anula mutarea pentru „${last.name}”."
    }

    private suspend fun copyBack(src: Uri, parent: Uri, name: String): Boolean {
        val mime = try { cr.getType(src) } catch (_: Exception) { null } ?: "application/octet-stream"
        val srcSha = try { sha256(src) } catch (e: CancellationException) { throw e } catch (_: Exception) { return false }
        val dst = try { DocumentsContract.createDocument(cr, parent, mime, name) } catch (_: Exception) { null } ?: return false
        return try {
            cr.openInputStream(src)!!.use { i -> cr.openOutputStream(dst, "wt")!!.use { o -> i.copyTo(o, 65536) } }
            if (sha256(dst) != srcSha) { try { DocumentsContract.deleteDocument(cr, dst) } catch (_: Exception) { }; false }
            else { try { DocumentsContract.deleteDocument(cr, src) } catch (_: Exception) { }; indexIfExternal(dst); true }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            try { DocumentsContract.deleteDocument(cr, dst) } catch (_: Exception) { }
            false
        }
    }
}
