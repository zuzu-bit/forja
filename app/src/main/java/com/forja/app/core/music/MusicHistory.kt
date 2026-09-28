package com.forja.app.core.music

/*
 * Istoricul local al ascultărilor (doar pe telefon), în formă de rânduri de text — Kotlin pur, testabil pe JVM.
 *
 *   ascultări:  moment⇥titlu⇥artist⇥pachet⇥fel⇥mediaId⇥uri⇥durataS⇥sursă⇥eveniment
 *   bibliotecă: cheie⇥titlu⇥artist⇥pachet⇥mediaId⇥uri⇥fel⇥durataS⇥primaOară⇥ultimaOară⇥ascultări⇥aleUtilizatorului⇥săriri⇥ultimaDatăEa
 *
 * Totul vine din metadatele sesiunii media (MediaSession), nimic din notificări. Liniile vechi (4.3: „moment⇥titlu⇥artist”)
 * rămân valide: fel necunoscut = aplicație de muzică (4.3 număra doar playere de muzică).
 */

/** O ascultare (≥ 30 s) sau o săritură (piesa schimbată după 3–30 s). */
enum class PlayEvent(val code: String) { PLAY("p"), SKIP("s") }

/** Cine a pornit piesa: ea (u) sau lista FORJA (f) — lista nu se hrănește din ea însăși. */
enum class PlaySource(val code: String) { USER("u"), FORJA("f") }

data class PlayRow(
    val at: Long,
    val title: String,
    val artist: String,
    val pkg: String? = null,
    /** null = linie din 4.3 (fără fel): era deja filtrată la aplicații de muzică. */
    val kind: MediaKind? = null,
    val mediaId: String? = null,
    val uri: String? = null,
    val durS: Int = 0,
    val src: PlaySource = PlaySource.USER,
    val event: PlayEvent = PlayEvent.PLAY
) {
    val key: String get() = TrackKey.of(title, artist)
    val isMusic: Boolean get() = kind == null || kind == MediaKind.MUSIC
    val isUserPlay: Boolean get() = event == PlayEvent.PLAY && src == PlaySource.USER
}

/** O piesă în bibliotecă: totalurile ei, păstrate mai mult decât ascultările (pentru „Vechi”). */
data class LibraryEntry(
    val key: String,
    val title: String,
    val artist: String,
    val pkg: String?,
    val mediaId: String?,
    val uri: String?,
    val kind: MediaKind?,
    val durS: Int,
    val firstAt: Long,
    val lastAt: Long,
    val plays: Int,
    val userPlays: Int,
    val skips: Int,
    val lastUserAt: Long
) {
    val isMusic: Boolean get() = kind == null || kind == MediaKind.MUSIC
}

object HistoryCodec {
    const val TITLE_MAX = 120
    const val ID_MAX = 200

    fun clean(s: String?, max: Int = TITLE_MAX): String =
        (s ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(max)

    private fun opt(s: String?, max: Int = ID_MAX): String = clean(s, max)

    fun line(r: PlayRow): String = listOf(
        r.at.toString(),
        clean(r.title),
        clean(r.artist),
        opt(r.pkg),
        r.kind?.code ?: "",
        opt(r.mediaId),
        opt(r.uri),
        r.durS.coerceAtLeast(0).toString(),
        r.src.code,
        r.event.code
    ).joinToString("\t")

    fun parseLine(line: String): PlayRow? {
        val p = line.split('\t')
        if (p.size < 3) return null
        val at = p[0].toLongOrNull() ?: return null
        if (p[1].isBlank()) return null
        fun col(i: Int): String? = p.getOrNull(i)?.takeIf { it.isNotEmpty() }
        return PlayRow(
            at = at,
            title = p[1],
            artist = p[2],
            pkg = col(3),
            kind = MediaKind.ofCode(col(4)),
            mediaId = col(5),
            uri = col(6),
            durS = col(7)?.toIntOrNull() ?: 0,
            src = if (col(8) == PlaySource.FORJA.code) PlaySource.FORJA else PlaySource.USER,
            event = if (col(9) == PlayEvent.SKIP.code) PlayEvent.SKIP else PlayEvent.PLAY
        )
    }

    fun parse(raw: String?): List<PlayRow> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.lineSequence().mapNotNull { parseLine(it) }.toList()
    }

    fun encode(rows: List<PlayRow>): String = rows.joinToString("\n") { line(it) }

    // ───────────────────────────── Biblioteca ─────────────────────────────

    fun libraryLine(e: LibraryEntry): String = listOf(
        e.key, clean(e.title), clean(e.artist), opt(e.pkg), opt(e.mediaId), opt(e.uri), e.kind?.code ?: "",
        e.durS.toString(), e.firstAt.toString(), e.lastAt.toString(), e.plays.toString(), e.userPlays.toString(),
        e.skips.toString(), e.lastUserAt.toString()
    ).joinToString("\t")

    fun parseLibrary(raw: String?): Map<String, LibraryEntry> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, LibraryEntry>()
        for (line in raw.lineSequence()) {
            val p = line.split('\t')
            if (p.size < 14 || p[0].isEmpty()) continue
            fun col(i: Int): String? = p[i].takeIf { it.isNotEmpty() }
            val e = LibraryEntry(
                key = p[0], title = p[1], artist = p[2], pkg = col(3), mediaId = col(4), uri = col(5),
                kind = MediaKind.ofCode(col(6)), durS = p[7].toIntOrNull() ?: 0,
                firstAt = p[8].toLongOrNull() ?: continue, lastAt = p[9].toLongOrNull() ?: continue,
                plays = p[10].toIntOrNull() ?: 0, userPlays = p[11].toIntOrNull() ?: 0, skips = p[12].toIntOrNull() ?: 0,
                lastUserAt = p[13].toLongOrNull() ?: 0L
            )
            out[e.key] = e
        }
        return out
    }

    fun encodeLibrary(lib: Map<String, LibraryEntry>): String = lib.values.joinToString("\n") { libraryLine(it) }

    /** Adaugă un rând în bibliotecă (ascultare sau săritură); metadatele cele mai noi câștigă. */
    fun apply(lib: MutableMap<String, LibraryEntry>, r: PlayRow) {
        val key = r.key
        if (key.startsWith("|")) return
        val old = lib[key]
        val play = r.event == PlayEvent.PLAY
        val user = r.isUserPlay
        lib[key] = if (old == null) {
            LibraryEntry(
                key = key, title = clean(r.title), artist = clean(r.artist), pkg = r.pkg, mediaId = r.mediaId, uri = r.uri,
                kind = r.kind, durS = r.durS, firstAt = r.at, lastAt = r.at,
                plays = if (play) 1 else 0, userPlays = if (user) 1 else 0, skips = if (play) 0 else 1,
                lastUserAt = if (user) r.at else 0L
            )
        } else {
            old.copy(
                title = clean(r.title).ifEmpty { old.title },
                artist = clean(r.artist).ifEmpty { old.artist },
                pkg = r.pkg ?: old.pkg,
                mediaId = r.mediaId ?: old.mediaId,
                uri = r.uri ?: old.uri,
                kind = r.kind ?: old.kind,
                durS = if (r.durS > 0) r.durS else old.durS,
                firstAt = minOf(old.firstAt, r.at),
                lastAt = maxOf(old.lastAt, r.at),
                plays = old.plays + if (play) 1 else 0,
                userPlays = old.userPlays + if (user) 1 else 0,
                skips = old.skips + if (play) 0 else 1,
                lastUserAt = if (user) maxOf(old.lastUserAt, r.at) else old.lastUserAt
            )
        }
    }

    /** Biblioteca din ascultări (prima deschidere după 4.3: se clădește din istoricul existent). */
    fun fold(rows: List<PlayRow>): MutableMap<String, LibraryEntry> {
        val lib = LinkedHashMap<String, LibraryEntry>()
        for (r in rows.sortedBy { it.at }) apply(lib, r)
        return lib
    }

    /**
     * Cel mult [max] piese: pleacă întâi cele uitate de mult (> 400 de zile), apoi cele cu cele mai puține ascultări
     * și cele mai vechi.
     */
    fun trimLibrary(lib: MutableMap<String, LibraryEntry>, now: Long, max: Int = 2_000) {
        val horizon = now - 400L * DAY_MS
        lib.values.filter { it.lastAt < horizon }.forEach { lib.remove(it.key) }
        if (lib.size <= max) return
        val drop = lib.values.sortedWith(compareBy<LibraryEntry> { it.plays }.thenBy { it.lastAt }).take(lib.size - max)
        drop.forEach { lib.remove(it.key) }
    }

    const val DAY_MS = 24L * 3600 * 1000
}
