package com.forja.app.core.music

/**
 * Rândul ENV al jurnalului (4.4.1): ce vedea FORJA chiar înaintea unei porniri, ca un singur rând să explice o pornire
 * ratată (29.09: FORJA nu credea că Spotify e instalat). Kotlin pur.
 *
 *   v1 la:0 pi:1 inst:sp ses:yt key:yt hist:sp last:sp pref:sp top:q idsh:spotify:track:22 fg:1 ex:-
 *
 * - la / pi: Spotify, după cele două întrebări către PackageManager (intrarea de lansare / informațiile pachetului);
 * - inst: playerele văzute pe telefon; ses: pachetele sesiunilor media de acum; key: cine primește tasta media;
 *   hist: playerele din istoric; last: ultimul player văzut cântând; pref: playerul folosit de plan;
 * - top: piesa cerută (prima din listă sau piesa de top): id = are ID Spotify (saltul spotify:track e posibil),
 *   q = doar căutare, - = niciuna;
 * - idsh: FORMA ID-ului media din Spotify (prefixul + lungimea), niciodată ID-ul;
 * - fg: FORJA în față; ex: clasa primei erori din citirea istoricului / a playerelor, - = niciuna.
 *
 * Doar coduri: sp Spotify, ytm YouTube Music, sm Samsung Music, yt YouTube, o altul, - nimic; cel mult 3 pe listă.
 * Niciun titlu, niciun artist, niciun ID. Cel mult [MAX] caractere (cât ține câmpul `err` al jurnalului).
 */
object EnvLine {
    const val MAX = 120
    const val RUNG = "ENV"

    data class Facts(
        /** Spotify: getLaunchIntentForPackage a găsit intrarea de lansare. */
        val launch: Boolean,
        /** Spotify: getPackageInfo a răspuns. */
        val info: Boolean,
        val installed: Collection<String> = emptyList(),
        /** Pachetele sesiunilor media, în ordinea sistemului (cea mai recentă întâi). */
        val sessions: List<String> = emptyList(),
        val keyTarget: String? = null,
        val history: Collection<String> = emptyList(),
        val last: String? = null,
        val preferred: String? = null,
        /** Piesa cerută de pornire (doar dacă are ID Spotify contează; titlul nu se scrie niciodată). */
        val track: TrackRef? = null,
        /** ID-ul media văzut la Spotify (sesiunea lui sau istoricul): din el se scrie doar forma. */
        val spotifyMediaId: String? = null,
        val fg: Boolean = true,
        /** Clasa primei excepții din pregătire (simpleName), null = niciuna. */
        val error: String? = null
    )

    /** Codul unui pachet: sp, ytm, sm, yt, o (altul), - (niciunul). */
    fun code(pkg: String?): String = when (pkg) {
        null, "" -> "-"
        MusicKind.SPOTIFY -> "sp"
        MusicKind.YT_MUSIC -> "ytm"
        MusicKind.SAMSUNG_MUSIC -> "sm"
        MusicKind.YOUTUBE -> "yt"
        else -> "o"
    }

    /** O listă de pachete ca și coduri distincte, cel mult [max] (3), în ordinea dată („sp,yt”); goală = „-”. */
    fun codes(pkgs: Collection<String>, max: Int = 3): String =
        pkgs.map { code(it) }.filter { it != "-" }.distinct().take(max.coerceIn(1, 3)).joinToString(",").ifEmpty { "-" }

    /** „id” = piesa are ID Spotify; „q” = doar titlu și artist (căutare); „-” = nicio piesă cerută. */
    fun trackCode(t: TrackRef?): String = when {
        t == null -> "-"
        MusicKind.spotifyTrackId(t.mediaId) != null && (t.pkg == null || t.pkg == MusicKind.SPOTIFY) -> "id"
        else -> "q"
    }

    fun line(f: Facts): String {
        val idsh = safe(MusicKind.idShape(f.spotifyMediaId)) ?: "-"
        val ex = safe(f.error) ?: "-"
        // Cel mult 120 de caractere. Întâi se strâng listele (3 → 1 cod), apoi numele excepției: la, pi, ex și idsh
        // sunt răspunsurile căutate, deci rămân întregi cât se poate.
        var s = ""
        for (n in 3 downTo 1) {
            s = compose(f, n, idsh, ex)
            if (s.length <= MAX) return s
        }
        val keep = (ex.length - (s.length - MAX)).coerceAtLeast(8)
        return compose(f, 1, idsh, ex.take(keep)).take(MAX)
    }

    private fun compose(f: Facts, n: Int, idsh: String, ex: String): String = listOf(
        "v1",
        "la:${bit(f.launch)}",
        "pi:${bit(f.info)}",
        "inst:${codes(f.installed, n)}",
        "ses:${codes(f.sessions, n)}",
        "key:${code(f.keyTarget)}",
        "hist:${codes(f.history, n)}",
        "last:${code(f.last)}",
        "pref:${code(f.preferred)}",
        "top:${trackCode(f.track)}",
        "idsh:$idsh",
        "fg:${bit(f.fg)}",
        "ex:$ex"
    ).joinToString(" ")

    private fun bit(b: Boolean) = if (b) "1" else "0"

    /** Un singur cuvânt, fără spații sau caractere de control (numele unei clase, forma unui ID). */
    private fun safe(s: String?): String? = s?.trim()?.replace(Regex("[^A-Za-z0-9_:.$-]+"), "_")?.takeIf { it.isNotEmpty() }
}
