package com.forja.app.core.music

import java.text.Normalizer

/**
 * Cheia unei piese — singura regulă după care FORJA spune „e aceeași melodie” (ascultări, TOP, liste, potriviri).
 * Litere mici, fără diacritice, fără sufixe de tip „(feat. …)”, „- Remastered 2011”, „(Live)”; artistul = primul
 * nume dinaintea lui „, ”, „ & ”, „ feat.”, „ x ”. Kotlin pur.
 */
object TrackKey {
    private const val SUFFIX_WORDS =
        "feat\\.?|ft\\.?|featuring|remaster(?:ed)?(?:\\s+\\d{4})?|\\d{4}\\s+remaster(?:ed)?|radio edit|live|version|versiune|explicit|mono|stereo|edit"
    private val BRACKETED = Regex("""\s*[(\[][^)\]]*?\b(?:$SUFFIX_WORDS)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val DASHED = Regex("""\s+[-–—]\s+[^-–—]*\b(?:$SUFFIX_WORDS)\b.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|;|/|\bfeat\.?|\bft\.?|\bfeaturing\b|\s+x\s+|\s+și\s+|\s+and\s+)\s*""", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Litere mici, fără diacritice (NFD: ș și ț, cu virgulă sau sedilă, devin s și t), litere și cifre cu un spațiu. */
    fun norm(s: String): String {
        val plain = MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
        return NON_WORD.replace(plain, " ").trim()
    }

    fun title(title: String): String {
        var t = BRACKETED.replace(title, "")
        t = DASHED.replace(t, "")
        return norm(t).ifEmpty { norm(title) }
    }

    fun artist(artist: String): String {
        val first = artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() } ?: artist
        return norm(first)
    }

    fun of(title: String, artist: String): String = title(title) + "|" + artist(artist)

    /** Eticheta afișată: „Titlu · Artist” (doar titlul, fără artist). */
    fun label(title: String, artist: String): String = if (artist.isBlank()) title else "$title · $artist"

    /**
     * Piesa care cântă e cea cerută? Titlul normalizat egal, sau unul îl conține pe celălalt (≥ 4 litere) — Spotify
     * adaugă uneori „- Remastered” sau „(feat. …)” altfel decât în istoric.
     */
    fun matches(expectedTitle: String, title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val a = title(expectedTitle)
        val b = title(title)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return (a.length >= 4 && b.length >= 4) && (a.contains(b) || b.contains(a))
    }
}
