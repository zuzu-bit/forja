package com.forja.app.core.sync

import java.security.MessageDigest

/**
 * Oglinda (pachetul C, contract v4) — partea fără Android, testată pe JVM: id-ul stabil al unei copii, albumul unui
 * document și planul unei treceri (ce urcă, ce se mută, ce se șterge de pe site), din ce e pe telefon și ce spune
 * serverul (`GET /v2/mirror/ids`). Serverul e registrul: după o reinstalare telefonul nu urcă nimic de două ori.
 */
object MirrorPlan {
    const val FILE_MAX = 25L * 1024 * 1024
    const val MAX_DELETES = 200
    /** Paza ștergerilor: peste atât dintr-un grup (și peste [GUARD_MIN] copii) într-o trecere, nu ștergem nimic din el. */
    const val GUARD_SHARE = 0.2
    const val GUARD_MIN = 25

    enum class Kind(val code: String) { Photo("photo"), Video("video"), File("file") }

    /** Ce e pe telefon: [key] nu conține albumul, ca o mutare din Inventar să păstreze id-ul. */
    data class Candidate(
        val key: String, val kind: Kind, val name: String, val album: String, val mime: String,
        val takenAt: Long, val size: Long, val uri: String, val width: Int = 0, val height: Int = 0, val durationMs: Long = 0,
        /** În coșul sistemului sau în „De aruncat”: nu urcă, dar copia de pe site rămâne cât se poate recupera. */
        val trashed: Boolean = false
    ) {
        val id: String get() = stableId(key)
        /** Un document mai mare de 25 MB nu are ce urca (niciun poster): rămâne doar pe telefon. */
        val mirrorable: Boolean get() = kind != Kind.File || size <= FILE_MAX
    }

    /** Un rând al registrului de pe server: id, album, fel, părțile („f” fișier, „t” miniatură, „p” poster), al acestui telefon. */
    data class Remote(val id: String, val album: String, val kind: String, val parts: String, val mine: Boolean)

    data class Plan(val upload: List<Candidate>, val move: List<Pair<String, String>>, val claim: List<String>, val delete: List<String>,
                    val mirrored: Int, val waiting: Int, val tooBig: Int, val held: Int = 0)

    /** Cheia unei poze sau a unui video: fel, nume, data făcută, mărime (aceeași după o mutare, după o reinstalare, pe alt telefon). */
    fun mediaKey(kind: Kind, name: String, takenAt: Long, size: Long): String = "${kind.code}|$name|$takenAt|$size"

    /** Numele unei poze din coșul sistemului, fără prefixul „.trashed-{expirare}-” (Android 11+). */
    fun untrashedName(name: String): String = name.replaceFirst(Regex("^\\.trashed-\\d+-"), "")

    /** Cheia unui document: nume, mărime, ultima modificare (SAF le păstrează la mutarea în dosare). */
    fun docKey(name: String, size: Long, modified: Long): String = "file|$name|$size|$modified"

    /** UUID v4 ca formă (serverul cere acest tipar), derivat din SHA-256 al cheii. */
    fun stableId(key: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(("forja-mirror|" + key).toByteArray(Charsets.UTF_8))
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.take(16).joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    /** Albumul unui document: eticheta folderului ales + dosarul din el („Documents/Organizate/Facturi”). */
    fun docAlbum(treeLabel: String, path: String): String {
        val dir = path.trim('/').substringBeforeLast('/', "")
        return listOf(treeLabel.trim('/'), dir).filter { it.isNotBlank() }.joinToString("/").take(160).ifBlank { "Documente" }
    }

    /** Ce părți cere o copie completă. Video: poster + miniatură, și fișierul când are cel mult 25 MB. */
    fun needs(c: Candidate): String = when (c.kind) {
        Kind.Photo -> "ft"
        Kind.Video -> if (c.size in 1..FILE_MAX) "ftp" else "tp"
        Kind.File -> "f"
    }

    fun complete(c: Candidate, r: Remote?): Boolean = r != null && needs(c).all { it in r.parts }

    /**
     * Planul unei treceri. [scanned] = grupurile de galerie citite complet acum („photo”, „video”: permisiunea întreagă,
     * nu doar pozele alese, toate volumele), [docTrees] = etichetele folderelor de documente citite complet, [knownTrees] =
     * toate folderele din care a urcat vreodată ceva. Doar acolo lipsa de pe telefon înseamnă „șters”, doar pentru copiile
     * acestui telefon, iar un document dintr-un folder care nu s-a putut citi acum (permisiune pierdută) nu se șterge.
     * Paza: când ar dispărea dintr-un grup mai mult de [GUARD_SHARE] din copiile telefonului, nu ștergem nimic din el.
     * Urcă întâi cele mai noi (viața recentă apare prima).
     */
    fun plan(local: List<Candidate>, remote: List<Remote>, gone: Set<String>, scanned: Set<String>,
             docTrees: Set<String> = emptySet(), knownTrees: Set<String> = emptySet()): Plan {
        val byId = remote.associateBy { it.id }
        val here = HashSet<String>(local.size * 2)
        val upload = ArrayList<Candidate>()
        val move = ArrayList<Pair<String, String>>()
        val claim = ArrayList<String>()
        var mirrored = 0
        var tooBig = 0
        for (c in local) {
            val id = c.id
            here += id
            if (c.trashed) continue            // în coș: copia rămâne, nimic nu urcă
            if (!c.mirrorable) { tooBig++; continue }
            if (id in gone) continue           // șters de pe site: nu mai urcă
            val r = byId[id]
            if (complete(c, r)) mirrored++ else upload += c
            if (r != null && r.album != c.album) move += id to c.album
            else if (r != null && !r.mine) claim += id
        }
        val trees = knownTrees + docTrees
        fun deletable(r: Remote): Boolean = when (val g = groupOf(r.kind)) {
            "docs" -> treeOf(r.album, trees)?.let { it in docTrees } ?: false
            else -> g in scanned
        }
        val mine = remote.filter { it.mine }
        val missing = mine.filter { it.id !in here && deletable(it) }
        val mineBy = mine.groupingBy { groupOf(it.kind) }.eachCount()
        val missingBy = missing.groupingBy { groupOf(it.kind) }.eachCount()
        val (hold, delete) = missing.partition { r ->
            val g = groupOf(r.kind); val n = missingBy[g] ?: 0
            n > GUARD_MIN && n > (mineBy[g] ?: 0) * GUARD_SHARE
        }
        upload.sortByDescending { it.takenAt }
        return Plan(upload, move, claim, delete.map { it.id }.take(MAX_DELETES), mirrored, upload.size, tooBig, hold.size)
    }

    /** Folderul de documente al unui album: cea mai lungă etichetă cunoscută care îl începe („Documents” pentru „Documents/Facturi”). */
    fun treeOf(album: String, trees: Set<String>): String? =
        trees.filter { it.isNotBlank() && (album == it || album.startsWith("$it/")) }.maxByOrNull { it.length }

    /** photo · video · docs (fiecare grup se citește și se șterge separat). */
    fun groupOf(kind: String): String = when (kind) { "file" -> "docs"; "video" -> "video"; else -> "photo" }
}
