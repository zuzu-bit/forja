package com.forja.app.core.inventory

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.forja.app.core.cleanup.DocumentOrganizer

// ═══════════════ Inventar 4.4 — unde ajung lucrurile (destinația aleasă de om) ═══════════════
// Poze: o rădăcină RELATIVE_PATH („Pictures/FORJA/”, „Pictures/”, „DCIM/FORJA/” sau un dosar ales cu selectorul
// sistemului, tradus). Fără acces complet, MediaStore acceptă doar anumite dosare de sus, pe fiecare colecție
// (MediaProvider.ensureFileColumns): imagini: DCIM, Pictures · video: DCIM, Movies (API 29) și + Pictures (API 30+).
// 4.4.2: cu „Acces la toate fișierele” (AllFiles) orice dosar din memoria internă e bun („Documents/Poze/”, un dosar de
// sus al ei), în afară de rădăcină și de Android/…: MediaProvider lasă un „manager” să creeze fișiere oriunde. Fără
// acces, un astfel de dosar nu se mai refuză la alegere: Inventarul cere accesul, iar mutările fără el au motivul `dir`.
// Documente: un arbore SAF + un subdosar („Organizate” în folderul ales, sau nimic când omul a ales alt folder).

internal object MediaRoots {
    const val DEFAULT = "Pictures/FORJA/"
    const val GALLERY = "Pictures/"
    const val CAMERA = "DCIM/FORJA/"

    /** Rădăcinile gata făcute din foaia „Locație”, în ordinea rândurilor. */
    val PRESETS = listOf(DEFAULT, GALLERY, CAMERA)

    /** Dosarele de sus în care MediaStore primește poze și fără acces complet. */
    val STANDARD = setOf("Pictures", "DCIM")

    fun allowed(video: Boolean, sdk: Int = Build.VERSION.SDK_INT): Set<String> = when {
        !video -> setOf("Pictures", "DCIM")
        sdk >= 30 -> setOf("Pictures", "DCIM", "Movies")
        else -> setOf("DCIM", "Movies")
    }

    /**
     * Rădăcina pentru un element: un video pe API 29 nu poate sta în Pictures → „Movies/<restul căii>” (pe API 29 nu
     * există acces complet, deci regula se aplică mereu). Un alt dosar de sus, ales cu acces complet, rămâne cum e:
     * MediaProvider îl primește de la un „manager”, iar fără acces mutarea eșuează cu motivul ei (`dir`), nu în tăcere.
     */
    fun forItem(root: String, video: Boolean, sdk: Int = Build.VERSION.SDK_INT): String {
        val r = normalize(root, anyTop = true) ?: DEFAULT
        val top = r.substringBefore('/')
        return if (video && top == "Pictures" && top !in allowed(true, sdk)) "Movies/" + r.substringAfter('/', "") else r
    }

    /** Rădăcina e în Pictures sau DCIM: merge și fără acces complet. */
    fun standard(root: String): Boolean = (normalize(root, anyTop = true) ?: DEFAULT).substringBefore('/') in STANDARD

    /**
     * „Pictures/Vacanțe” → „Pictures/Vacanțe/”: segmente curățate (fără „..”, fără caractere interzise), ≤ 6, cu primul
     * segment Pictures sau DCIM (scris ca de sistem). Cu [anyTop] (dosarul ales cu acces complet) primul segment poate fi
     * oricare, în afară de Android (Android/data, obb și media nu sunt ale pozelor). Null dacă nu e o rădăcină acceptată.
     */
    fun normalize(root: String?, anyTop: Boolean = false): String? {
        if (root.isNullOrBlank()) return null
        val bad = Regex("[\\\\:*?\"<>|\\p{Cntrl}]")
        val segs = root.replace('\\', '/').split('/').map { it.replace(bad, "").trim().trim('.') }
            .filter { it.isNotBlank() && it != ".." }
        if (segs.isEmpty() || segs.size > 6) return null
        val first = segs[0]
        val top = when {
            first.equals("pictures", ignoreCase = true) -> "Pictures"
            first.equals("dcim", ignoreCase = true) -> "DCIM"
            !anyTop || first.equals("android", ignoreCase = true) -> return null
            else -> first.take(60)
        }
        return (listOf(top) + segs.drop(1).map { it.take(60) }).joinToString("/") + "/"
    }

    /** Eticheta scurtă din rândul de confirmare: „FORJA”, „Galerie”, „Vacanțe”, „Documents”. */
    fun shortLabel(root: String): String {
        val r = normalize(root, anyTop = true) ?: DEFAULT
        val segs = r.trimEnd('/').split('/')
        return when {
            segs.size > 1 -> segs.last()
            segs[0] == "Pictures" -> "Galerie"
            segs[0] == "DCIM" -> "Cameră"
            else -> segs[0]
        }
    }

    /** Numele rândului din „Locație” (și eticheta de pe site). */
    fun optionLabel(root: String): String = when (normalize(root, anyTop = true) ?: DEFAULT) {
        DEFAULT -> "Galerie · FORJA"
        GALLERY -> "Direct în Galerie"
        CAMERA -> "Lângă Cameră"
        else -> shortLabel(root)
    }

    /** Calea pentru meta mono: „PICTURES/FORJA”, „DOCUMENTS/POZE”. */
    fun path(root: String): String = (normalize(root, anyTop = true) ?: DEFAULT).trimEnd('/').uppercase()
}

internal object TreePaths {
    const val EXTERNAL = "com.android.externalstorage.documents"

    /** Id-ul documentului rădăcină al unui arbore SAF (null dacă URI-ul nu e arbore). */
    fun treeDocId(tree: Uri): String? = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { null }

    /** Id-ul oricărui URI de document (simplu sau „…/tree/…/document/…”). */
    fun docId(uri: Uri): String? = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) {
        treeDocId(uri)
    }

    /** „primary:Documents/Arhivă” → „Documents/Arhivă”; alt furnizor → numele ultimului segment din id, dacă are. */
    fun relative(docId: String): String = docId.substringAfter(':', docId).trim('/')

    /** Calea pe disc pentru un id ExternalStorage („primary:…” sau „ABCD-1234:…”); null pentru alți furnizori. */
    fun storagePath(authority: String?, docId: String?): String? {
        if (authority != EXTERNAL || docId.isNullOrBlank() || !docId.contains(':')) return null
        val volume = docId.substringBefore(':')
        val rel = relative(docId)
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /**
     * Numele arborelui pentru etichete: „Documents”, „Documents/Arhivă”; rădăcina memoriei → „Memoria internă”.
     * Pentru alți furnizori (Drive…) id-ul e opac: rămâne „Folder”.
     */
    fun label(tree: Uri): String {
        val id = treeDocId(tree) ?: return "Folder"
        if (tree.authority != EXTERNAL) return "Folder"
        val rel = relative(id)
        return rel.ifEmpty { if (id.startsWith("primary")) "Memoria internă" else "Card" }
    }

    /** Ultimele [keep] segmente ale unei căi, cu „…/” în față când s-a tăiat. */
    fun tail(path: String, keep: Int = 3): String {
        val segs = path.trim('/').split('/').filter { it.isNotBlank() }
        return if (segs.size <= keep) segs.joinToString("/") else "…/" + segs.takeLast(keep).joinToString("/")
    }

    /**
     * [child] e în interiorul lui [parent] (ambele arbori SAF, același furnizor)? Pentru ExternalStorage, după id-uri
     * („primary:Documents/Arhivă” e sub „primary:Documents”). Egalitatea nu contează ca „în interior”.
     */
    fun isInside(child: Uri, parent: Uri): Boolean {
        if (child.authority != parent.authority) return false
        val c = treeDocId(child) ?: return false
        val p = treeDocId(parent) ?: return false
        if (c == p) return false
        val base = if (p.endsWith(":") || p.endsWith("/")) p else "$p/"
        return c.startsWith(base)
    }

    /** Același dosar (id-ul rădăcinii arborelui), chiar dacă URI-urile diferă ca scriere. */
    fun same(a: Uri, b: Uri): Boolean =
        a == b || (a.authority == b.authority && treeDocId(a) != null && treeDocId(a) == treeDocId(b))
}

/** Destinația documentelor: arborele ales (null = folderul sursă) și subdosarul din el. */
internal fun DestRec?.docsTree(source: Uri?): Uri? = this?.tree?.let { Uri.parse(it) } ?: source

internal fun DestRec?.docsSub(): String = this?.sub ?: DocumentOrganizer.ROOT_FOLDER

/** Rădăcina pozelor din plan (sau implicita), deja validată (și un dosar ales cu acces complet). */
internal fun DestRec?.mediaRoot(): String = MediaRoots.normalize(this?.mediaRoot, anyTop = true) ?: MediaRoots.DEFAULT

/**
 * Arborele ales în selectorul „Alt dosar…” → rădăcina RELATIVE_PATH („Pictures/Vacanțe/”, „Documents/Poze/”): orice
 * dosar din memoria internă, în afară de rădăcina ei și de Android/…. Null pentru card (alt volum MediaStore: nu mutăm
 * între volume), pentru alți furnizori (Drive…) și pentru căi mai adânci de 6 dosare. Pictures și DCIM merg oricum;
 * restul cer acces complet ([MediaRoots.standard]).
 */
internal fun mediaRootFromTree(tree: Uri): String? {
    if (tree.authority != TreePaths.EXTERNAL) return null
    val id = TreePaths.treeDocId(tree) ?: return null          // „primary:Pictures/Vacanțe”
    if (!id.startsWith("primary:")) return null                // card SD = alt volum MediaStore: nu mutăm între volume
    return MediaRoots.normalize(TreePaths.relative(id), anyTop = true)
}

/** Numele destinațiilor, pentru confirmare, foaia „Locație”, ecranul final și rezumatul de pe site. */
internal object DestNames {
    /** Eticheta scurtă: „FORJA”, „Galerie” (poze) / „Organizate”, „Arhivă” (documente). */
    fun short(dest: InvDest): String = when (dest) {
        is InvDest.Media -> MediaRoots.shortLabel(dest.root)
        is InvDest.Tree -> if (dest.tree == null) dest.sub.ifBlank { DocumentOrganizer.ROOT_FOLDER }
            else TreePaths.label(dest.tree).substringAfterLast('/')
    }

    /** Numele rândului din „Locație”: „Galerie · FORJA”, „În dosarul ales”, „Arhivă”. */
    fun option(dest: InvDest): String = when (dest) {
        is InvDest.Media -> MediaRoots.optionLabel(dest.root)
        is InvDest.Tree -> if (dest.tree == null) "În dosarul ales" else short(dest)
    }

    /** Calea (natural, fără majuscule): „Pictures/FORJA”, „Documents/Organizate”, „Documents/Arhivă”. */
    fun path(dest: InvDest, source: Uri?): String = when (dest) {
        is InvDest.Media -> (MediaRoots.normalize(dest.root, anyTop = true) ?: MediaRoots.DEFAULT).trimEnd('/')
        is InvDest.Tree -> {
            val base = TreePaths.label(dest.tree ?: source ?: return dest.sub)
            if (dest.sub.isBlank()) base else "$base/${dest.sub}"
        }
    }
}
