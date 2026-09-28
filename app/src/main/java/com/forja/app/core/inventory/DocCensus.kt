package com.forja.app.core.inventory

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Documentele unui folder, pentru placa DOCUMENTE din S1: [loose] = cele pe care Inventarul le-ar pune în ordine acum,
 * [organized] = cele deja în dosare (sub „Organizate”, sau în destinația aleasă). După o aplicare reușită placa
 * spune „În ordine · 4 ÎN DOSARE”, nu „0 · 0 KB” (care se citea „folderul tău e gol”).
 */
data class DocCensus(val loose: Int, val looseBytes: Long, val organized: Int, val organizedBytes: Long)

/** Destinația implicită a documentelor (Prefs `inventory_docs_dest`) și ce înseamnă ea pentru scanarea sursei. */
internal object DocDest {
    /** Arborele destinație salvat, doar dacă permisiunea persistentă (citire + scriere) mai există; altfel îl uităm. */
    suspend fun savedTree(ctx: Context, prefs: Prefs): Uri? {
        val raw = try { prefs.inventoryDocsDest.first() } catch (_: Exception) { "" }
        if (raw.isBlank()) return null
        val uri = Uri.parse(raw)
        val ok = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            } catch (_: Exception) { false }
        }
        if (!ok) try { prefs.setInventoryDocsDest("") } catch (_: Exception) { }
        return if (ok) uri else null
    }

    /** Dosarele de sărit la scanarea sursei: destinația aleasă, când e în interiorul folderului analizat. */
    fun skips(source: Uri, dest: Uri?): Set<String> =
        if (dest != null && TreePaths.isInside(dest, source)) setOfNotNull(TreePaths.treeDocId(dest)) else emptySet()

    /** Păstrăm permisiunea noii destinații (planul se poate aplica zile mai târziu, după ce procesul a murit). */
    fun take(ctx: Context, tree: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    /** Eliberăm permisiunea unei destinații vechi (nu și când e chiar folderul sursă). */
    fun release(ctx: Context, tree: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
    }
}

internal object DocCounter {
    /**
     * O trecere prin folder (BFS): fișierele libere (fără „Organizate”, fără destinația din folder, fără „De aruncat”)
     * și cele deja în dosare. Null când folderul nu mai e accesibil. Nu e gratis pe un folder mare: apelantul o limitează.
     */
    suspend fun census(context: Context, tree: Uri): DocCensus? {
        val ctx = context.applicationContext ?: context
        val app = ForjaApp.from(ctx)
        val organizer = DocumentOrganizer(ctx, app.prefs)
        return try {
            val dest = DocDest.savedTree(ctx, app.prefs)?.takeUnless { TreePaths.same(it, tree) }
            val (docs, warnings) = organizer.inventory(tree, skipDirIds = DocDest.skips(tree, dest))
            if (docs.isEmpty() && warnings.any { it.startsWith("Folderul nu mai e accesibil") }) return null
            val loose = docs.filter { it.isLoose() }
            val inTree = organizer.childDirId(tree, DocumentOrganizer.ROOT_FOLDER)
                ?.let { id -> organizer.inventory(tree, startDocId = id, limit = 5_000).first } ?: emptyList()
            val custom = dest?.let { organizer.inventory(it, limit = 5_000).first } ?: emptyList()
            val organized = (inTree + custom).filter { !it.name.startsWith(".forja-") }
            DocCensus(loose.size, loose.sumOf { it.sizeBytes }, organized.size, organized.sumOf { it.sizeBytes })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
