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

    enum class Kind(val code: String) { Photo("photo"), Video("video"), File("file") }

    /** Ce e pe telefon: [key] nu conține albumul, ca o mutare din Inventar să păstreze id-ul. */
    data class Candidate(
        val key: String, val kind: Kind, val name: String, val album: String, val mime: String,
        val takenAt: Long, val size: Long, val uri: String, val width: Int = 0, val height: Int = 0, val durationMs: Long = 0
    ) {
        val id: String get() = stableId(key)
        /** Un document mai mare de 25 MB nu are ce urca (niciun poster): rămâne doar pe telefon. */
        val mirrorable: Boolean get() = kind != Kind.File || size <= FILE_MAX
    }

    /** Un rând al registrului de pe server: id, album, fel, părțile („f” fișier, „t” miniatură, „p” poster), al acestui telefon. */
    data class Remote(val id: String, val album: String, val kind: String, val parts: String, val mine: Boolean)

    data class Plan(val upload: List<Candidate>, val move: List<Pair<String, String>>, val claim: List<String>, val delete: List<String>,
                    val mirrored: Int, val waiting: Int, val tooBig: Int)

    /** Cheia unei poze sau a unui video: fel, nume, data făcută, mărime (aceeași după o mutare, după o reinstalare, pe alt telefon). */
    fun mediaKey(kind: Kind, name: String, takenAt: Long, size: Long): String = "${kind.code}|$name|$takenAt|$size"

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
     * Planul unei treceri. [scanned] = grupurile citite complet acum („gallery”, „docs”): doar acolo lipsa de pe telefon
     * înseamnă „șters”, și doar pentru copiile acestui telefon. Urcă întâi cele mai noi (viața recentă apare prima).
     */
    fun plan(local: List<Candidate>, remote: List<Remote>, gone: Set<String>, scanned: Set<String>): Plan {
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
            if (!c.mirrorable) { tooBig++; continue }
            if (id in gone) continue           // șters de pe site: nu mai urcă
            val r = byId[id]
            if (complete(c, r)) mirrored++ else upload += c
            if (r != null && r.album != c.album) move += id to c.album
            else if (r != null && !r.mine) claim += id
        }
        val delete = remote.filter { r -> r.mine && r.id !in here && groupOf(r.kind) in scanned }.map { it.id }.take(MAX_DELETES)
        upload.sortByDescending { it.takenAt }
        return Plan(upload, move, claim, delete, mirrored, upload.size, tooBig)
    }

    fun groupOf(kind: String): String = if (kind == "file") "docs" else "gallery"
}
