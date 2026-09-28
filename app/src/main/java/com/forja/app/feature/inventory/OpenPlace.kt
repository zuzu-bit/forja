package com.forja.app.feature.inventory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import com.forja.app.core.inventory.InvPlace
import com.forja.app.core.inventory.Landing
import com.forja.app.core.inventory.TreePaths

/**
 * Deschide NOUA locație după aplicare („Fișiere” / „Galerie” și eticheta cu calea de pe ecranul final).
 *
 * Regula de ordine: întâi intențiile DETERMINISTE (fie deschid exact dosarul, fie aruncă o excepție și trecem mai
 * departe), abia apoi cele care pot „reuși” ignorând calea — acelea ar lăsa-o pe Lana în ecranul de start al
 * aplicației de fișiere, exact plângerea din 4.3, chiar dacă exista o variantă mai bună. Ordinea pentru un dosar:
 *
 *  1. Fișiere de sistem (DocumentsUI, pachet explicit: com.google.android.documentsui, apoi com.android.documentsui),
 *     ACTION_VIEW pe URI-ul de document al dosarului, tip `vnd.android.document/directory`. DocumentsUI deschide
 *     chiar dosarul (FilesActivity → „open URI for view”; are MANAGE_DOCUMENTS, deci nu-i trebuie acordul nostru).
 *     Dacă activitatea lipsește sau e dezactivată (unele One UI), startActivity aruncă → pasul 2.
 *  2. Selectorul sistemului în modul „răsfoiește” (ACTION_OPEN_DOCUMENT cu EXTRA_INITIAL_URI = dosarul), lansat de
 *     ecran cu [BrowseAt]: pornește fix în dosar, arată fișierele; un fișier atins se deschide în aplicația lui.
 *     Merge pe orice telefon cu SAF (același selector ca la „Alege folder”).
 *  3. My Files de la Samsung cu START_PATH (doar dacă e instalat și dosarul e pe disc). NEVERIFICAT pe S23: dacă
 *     ignoră calea, se deschide „reușit” în ecranul lui de start — de aceea stă după pașii siguri.
 *  4. ACTION_VIEW implicit pe dosar (orice aplicație care declară tipul), cu acord de citire pentru arborii noștri.
 *  5. Aplicația de fișiere generică (CATEGORY_APP_FILES), la fel ca în 4.3; apoi un toast.
 *
 * Pentru „Galerie” (poze), ordinea din inventar-fixes §2.4: (1) când s-a atins un singur album și Galeria Samsung e
 * instalată, chiar albumul (`?bucketId=`, `vnd.android.cursor.dir/image`) — noua locație, nu o poză din ea;
 * NEVERIFICAT pe S23, e pe lista de verificat cu adb (§2.6: `am start -a android.intent.action.VIEW -t
 * vnd.android.cursor.dir/image -p com.sec.android.gallery3d -d "content://media/external/images/media?bucketId=<ID>"`);
 * dacă Galeria nu declară tipul, startActivity aruncă și trecem mai departe; (2) prima poză mutată, ACTION_VIEW —
 * galeria deschisă chiar pe ea (MediaStore păstrează _ID-ul după mutare), albumul e la o atingere; (3) galeria
 * generică; (4) orice aplicație care arată imagini. Mai multe albume atinse: eticheta cu calea deschide rădăcina.
 */
internal object OpenPlace {
    private const val MY_FILES = "com.sec.android.app.myfiles"
    private const val SAMSUNG_GALLERY = "com.sec.android.gallery3d"
    private val DOCUMENTS_UI = listOf("com.google.android.documentsui", "com.android.documentsui")

    /** Pasul 2 (selectorul „răsfoiește”): îl lansează ecranul; întoarce false dacă nu s-a putut. */
    fun interface Browse { fun open(folder: Uri): Boolean }

    /**
     * Deschide un dosar pe lanțul de mai sus. `browse` = lansatorul [BrowseAt] al ecranului. Întoarce false doar
     * când nimic nu a mers (apelantul arată toast-ul).
     */
    fun folder(ctx: Context, place: InvPlace, browse: Browse): Boolean {
        val folder = place.folder ?: return files(ctx)
        val plain = plainDocument(folder)
        // 1. DocumentsUI, explicit — doar pentru furnizorul memoriei (acolo știe sigur să găsească drumul până la dosar).
        if (plain != null && plain.authority == TreePaths.EXTERNAL) {
            for (pkg in DOCUMENTS_UI) {
                if (start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(plain, DocumentsContract.Document.MIME_TYPE_DIR).setPackage(pkg))) return true
            }
        }
        // 2. Selectorul sistemului, pornit în dosar.
        if (browse.open(plain ?: folder)) return true
        // 3. My Files (Samsung), cu calea pe disc.
        val path = place.storagePath
        if (path != null && installed(ctx, MY_FILES)) {
            val i = Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES").setPackage(MY_FILES)
                .putExtra("samsung.myfiles.intent.extra.START_PATH", path)
            if (start(ctx, i)) return true
        }
        // 4. Orice aplicație care deschide un dosar (cu acord de citire doar pe URI-urile de arbore, pe care le avem).
        if (isTree(folder) && start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
        if (plain != null && start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(plain, DocumentsContract.Document.MIME_TYPE_DIR))) return true
        // 5. Aplicația de fișiere, la pornire.
        return files(ctx)
    }

    /** „Galerie”: albumul nou în Galeria Samsung (un singur album atins), apoi prima poză mutată, apoi galeria. */
    fun gallery(ctx: Context, landing: Landing?): Boolean {
        val bucket = landing?.bucketId
        if (bucket != null && installed(ctx, SAMSUNG_GALLERY)) {
            val album = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().appendQueryParameter("bucketId", bucket.toString()).build()
            if (start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(album, "vnd.android.cursor.dir/image").setPackage(SAMSUNG_GALLERY))) return true
        }
        val first = landing?.first
        if (first != null) {
            val mime = landing.firstMime ?: "image/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(first, mime)
            if (start(ctx, Intent(view).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
            if (start(ctx, view)) return true
        }
        if (start(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))) return true
        return start(ctx, Intent(Intent.ACTION_VIEW).setType("image/*"))
    }

    /** Aplicația de fișiere, la pornire (ultimul pas; și când nu știm locul). */
    fun files(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29 && start(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES))) return true
        return false
    }

    /** `content://…/tree/X/document/Y` → `content://…/document/Y` (ce așteaptă DocumentsUI și EXTRA_INITIAL_URI). */
    private fun plainDocument(uri: Uri): Uri? = try {
        val id = DocumentsContract.getDocumentId(uri)
        DocumentsContract.buildDocumentUri(uri.authority, id)
    } catch (_: Exception) {
        TreePaths.treeDocId(uri)?.let { id -> try { DocumentsContract.buildDocumentUri(uri.authority, id) } catch (_: Exception) { null } }
    }

    private fun isTree(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == "tree"

    private fun installed(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

/** Selectorul sistemului pornit într-un dosar, pentru răsfoire; întoarce fișierul atins (îl deschidem noi). */
internal class BrowseAt : ActivityResultContract<Uri, Uri?>() {
    override fun createIntent(context: Context, input: Uri): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
