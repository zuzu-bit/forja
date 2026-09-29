package com.forja.app.core.inventory

// ═══════════════ Inventar 4.4.2 — de ce n-a mutat Android o poză (Kotlin pur, testat pe JVM) ═══════════════
// Pe S23-ul Lanei, 6 din 9 poze au eșuat de fiecare dată, în tăcere: MediaMover înghițea excepția. Cauza, în MediaProvider
// (AOSP android16-release, updateInternal → isUpdateAllowedForOwnedPath): o poză din dosarul altei aplicații
// („Android/media/com.whatsapp/…”) nu poate ieși de acolo decât cu „Acces la toate fișierele” (MANAGE_EXTERNAL_STORAGE)
// — „Changing ownership from … to … not allowed”. La fel un dosar ales în afara Pictures/DCIM („Primary directory X not
// allowed”) și o mutare între volume („Changing volume”). De acum fiecare eșec are un motiv: pagina de rezultat îl spune
// într-un rând, iar jurnalul acordului (ConsentLog) îl notează fără nume, titluri, dosare sau căi.

/** Motivul unui eșec. [code] merge în jurnal (M_FAIL / M_SUM). */
enum class MoveReason(val code: String) {
    /** Sursa e în dosarul altei aplicații (Android/media/<pachet>/): Android o mută doar cu acces complet. */
    Owned("owned"),
    /** Dosarul ales nu e permis fără acces complet (poze: doar Pictures și DCIM). */
    Dir("dir"),
    /** Alt volum (card de memorie ↔ memoria internă): RELATIVE_PATH nu poate schimba volumul. */
    Volume("volume"),
    /** SecurityException: fără drept de scriere pe element. */
    Denied("denied"),
    /** Elementul nu mai există (șters între timp). */
    Gone("gone"),
    /** Actualizarea a trecut, dar elementul nu stă unde trebuia. */
    Mismatch("mismatch"),
    /** Altă excepție (clasa ei merge în jurnal). */
    Error("error");

    /** Se rezolvă cu „Acces la toate fișierele”. */
    val needsAccess: Boolean get() = this == Owned || this == Dir || this == Denied
}

/**
 * Un eșec, fără nimic personal: motivul, tipul („image” / „video”), dosarul de sus al sursei și al țintei, dintr-un
 * vocabular fix ([MoveDiag.topDir]), și clasa excepției pentru [MoveReason.Error].
 */
data class MoveFail(val reason: MoveReason, val type: String, val src: String, val dst: String, val error: String? = null)

/** Aplicația care „deține” un loc de pe disc: Android/media/<pachet>/ (și data/obb, pe care MediaStore nu le arată). */
internal object MediaOwners {
    private val OWNED = Regex("^/*Android/(?:media|data|obb)/([^/]+)(?:/.*)?$", RegexOption.IGNORE_CASE)

    /** „Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/” → „com.whatsapp”; oricare alt loc → null. */
    fun ownerOf(relPath: String): String? =
        OWNED.matchEntire(relPath.replace('\\', '/').trim())?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "." && it != ".." }

    /** Proprietarul, dacă e ALTĂ aplicație decât [self] (din propriul dosar FORJA mută și fără acces complet). */
    fun foreignOwner(relPath: String, self: String): String? = ownerOf(relPath)?.takeUnless { it.equals(self, ignoreCase = true) }
}

internal object MoveReasons {
    /**
     * Excepția din ContentResolver.update → motiv. Mesajele MediaProvider (în engleză, cu căi întregi) doar se citesc
     * aici, nu se scriu nicăieri. [ownedByOther] = sursa e în dosarul altei aplicații: fără un mesaj anume, asta e cauza.
     */
    fun classify(e: Throwable, ownedByOther: Boolean): MoveReason {
        val m = e.message.orEmpty()
        return when {
            m.contains("Changing ownership", ignoreCase = true) -> MoveReason.Owned
            m.contains("Changing volume", ignoreCase = true) -> MoveReason.Volume
            m.contains("Primary directory", ignoreCase = true) || m.contains("allowed directories", ignoreCase = true) -> MoveReason.Dir
            ownedByOther -> MoveReason.Owned
            e is SecurityException -> MoveReason.Denied
            else -> MoveReason.Error
        }
    }

    /** Clasa excepției, scurtă și curată (doar litere, cifre, „_$.”), pentru `error:<Clasă>` din jurnal. */
    fun errorClass(e: Throwable): String = e.javaClass.simpleName.filter { it.isLetterOrDigit() || it in "_$." }.take(40).ifBlank { "Exception" }

    /** Ordinea la egalitate: întâi ce se poate repara cu o atingere. */
    private val PRIORITY = listOf(
        MoveReason.Owned, MoveReason.Dir, MoveReason.Denied, MoveReason.Volume, MoveReason.Mismatch, MoveReason.Error, MoveReason.Gone
    )

    /** Motivul arătat (unul singur): cel mai des întâlnit; la egalitate, după [PRIORITY]. Null = niciun eșec. */
    fun main(counts: Map<MoveReason, Int>): MoveReason? =
        counts.filterValues { it > 0 }.entries.minWithOrNull(compareByDescending<Map.Entry<MoveReason, Int>> { it.value }.thenBy { PRIORITY.indexOf(it.key) })?.key
}

/** Rândurile de jurnal ale mutărilor (ConsentLog): coduri și vocabular fix, niciodată nume, titluri, dosare sau căi. */
internal object MoveDiag {
    /** Cel mult atâtea rânduri M_FAIL pe aplicare (jurnalul acordului are 40–60 de locuri; restul e în M_SUM). */
    const val MAX_FAIL_ROWS = 12

    /** Ținta unui element trimis la coș. */
    const val TRASH = "trash"

    /** Dosarele standard Android: singurele nume de dosar care pot ajunge în jurnal (orice alt dosar → „other”). */
    private val STANDARD = listOf(
        "DCIM", "Pictures", "Movies", "Download", "Documents", "Music", "Recordings", "Podcasts", "Audiobooks",
        "Ringtones", "Alarms", "Notifications", "Screenshots"
    )

    /** „DCIM/Camera/” → „DCIM”; „Android/media/com.whatsapp/…” → „Android/media”; „Vacanțe 2023/” → „other”. */
    fun topDir(relPath: String): String {
        val segs = relPath.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val a = segs.firstOrNull() ?: return "root"
        if (a.equals("Android", ignoreCase = true)) {
            val b = segs.getOrNull(1)?.lowercase()
            return if (b == "media" || b == "data" || b == "obb") "Android/$b" else "Android"
        }
        return STANDARD.firstOrNull { it.equals(a, ignoreCase = true) } ?: "other"
    }

    /** „image/jpeg” → „image”; video → „video”; restul → „other”. */
    fun type(mime: String, video: Boolean): String = when {
        video || mime.startsWith("video/", ignoreCase = true) -> "video"
        mime.startsWith("image/", ignoreCase = true) -> "image"
        else -> "other"
    }

    /** Eșecul unei mutări, construit direct din căi (care nu ies din funcție: rămân doar dosarele de sus). */
    fun fail(reason: MoveReason, mime: String, video: Boolean, srcRel: String, dstRel: String, error: String? = null): MoveFail =
        MoveFail(reason, type(mime, video), topDir(srcRel), topDir(dstRel), error?.takeIf { reason == MoveReason.Error })

    /** Eșecul coșului: ținta e „trash” (cuvântul fix, nu un dosar). */
    fun failTrash(reason: MoveReason, mime: String, video: Boolean, srcRel: String, error: String? = null): MoveFail =
        MoveFail(reason, type(mime, video), topDir(srcRel), TRASH, error?.takeIf { reason == MoveReason.Error })

    /** `why=owned t=image src=Android/media dst=Pictures mgr=0` (≤ 120 caractere). */
    fun failNote(f: MoveFail, mgr: Boolean): String {
        val why = if (f.reason == MoveReason.Error && !f.error.isNullOrBlank()) "error:${f.error}" else f.reason.code
        return "why=$why t=${f.type} src=${f.src} dst=${f.dst} mgr=${if (mgr) 1 else 0}".take(120)
    }

    /** Rândurile M_FAIL ale unei aplicări: cel mult [MAX_FAIL_ROWS]. */
    fun failNotes(fails: Collection<MoveFail>, mgr: Boolean): List<String> = fails.take(MAX_FAIL_ROWS).map { failNote(it, mgr) }

    /** Numărul eșecurilor pe motiv. */
    fun counts(fails: Collection<MoveFail>): Map<MoveReason, Int> = fails.groupingBy { it.reason }.eachCount()

    /** `moved=3 trash=1 owned=6 mgr=0`: totalurile și eșecurile pe motiv (doar cele întâlnite), în ordinea motivelor. */
    fun sumNote(moved: Int, trashed: Int, reasons: Map<MoveReason, Int>, mgr: Boolean): String {
        val parts = ArrayList<String>()
        parts += "moved=$moved"
        parts += "trash=$trashed"
        for (r in MoveReason.entries) reasons[r]?.takeIf { it > 0 }?.let { parts += "${r.code}=$it" }
        parts += "mgr=${if (mgr) 1 else 0}"
        return parts.joinToString(" ").take(120)
    }
}

/** Ce aplică o rundă (pur: tipul elementelor e generic, ca regula să se testeze fără Android). */
internal object ApplySelect {
    /**
     * Mutările rundei. API 30+: cu acces complet ([manager]) toate cele rămase, fără acord; altfel doar bucata acceptată
     * în dialogul de scriere ([grant]; fără dialog, nimic). Sub API 30: toate (API 29 mută direct ce îi aparține FORJA).
     */
    fun <T> moves(todo: List<T>, id: (T) -> String, grant: List<String>?, manager: Boolean, sdk: Int): List<T> = when {
        sdk < 30 || manager -> todo
        grant != null -> { val g = grant.toHashSet(); todo.filter { id(it) in g } }
        else -> emptyList()
    }

    /**
     * Coșul rundei, ca id-uri din „De aruncat”. API 30+: cu acces complet toate (prin IS_TRASHED = 1, fără dialog);
     * altfel doar bucata trimisă la coș în dialog ([grant]). Sub API 30: toate (ștergere directă).
     */
    fun trash(inTrash: List<String>, grant: List<String>?, manager: Boolean, sdk: Int): List<String> = when {
        sdk < 30 || manager -> inTrash
        else -> { val t = inTrash.toHashSet(); grant.orEmpty().filter { it in t } }
    }
}
