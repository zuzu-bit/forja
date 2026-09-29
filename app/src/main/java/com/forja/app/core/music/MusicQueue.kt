package com.forja.app.core.music

/**
 * Coada FORJA (workout-music.md §3.5, cu corecțiile din „Verificare adversarială”): lista Antrenamentului cântată
 * piesă cu piesă prin sesiunea playerului. Kotlin pur: primește schimbările de piesă și spune ce e de făcut.
 *
 * - Piesa nouă e cea așteptată → confirmă; e altă piesă din listă → sare acolo.
 * - Piesă străină după un final natural (poziția extrapolată la ≤ 13 s de final — Crossfade/Automix schimbă devreme)
 *   → următoarea piesă din listă. O reclamă (sub 45 s, fără artist) → așteaptă, fără comenzi.
 * - Piesă străină după o schimbare devreme → a preluat ea (altă listă în Spotify): coada se retrage, fără luptă.
 * - În fundal, niciodată o comandă către un player care s-a oprit: coada se retrage și lasă playerul în pace.
 * - Aterizarea (4.4.1): o piesă cerută ([issued]) trebuie să cânte în 6 s ([check]); o reclamă pornește așteptarea de la
 *   capăt. Nu a apărut → Spotify nu primește piesele cerute azi: coada se retrage ca „refuzată”, autoplay-ul lui continuă.
 *   Cât o cerere e în așteptare, o piesă străină nu e „a preluat ea”: e răspunsul lui Spotify (o piesă înlocuitoare),
 *   deci tot termenul decide.
 */
class MusicQueue(items: List<PlayItem>, reserve: List<PlayItem>, val targetPkg: String) {

    sealed interface Action {
        data object None : Action
        data class Issue(val item: PlayItem, val index: Int) : Action
        data object SeekZero : Action
        data object Hold : Action
        data class Release(val reason: String) : Action
        /** Piesa cerută a apărut în player (se învață: Spotify primește piesele cerute). */
        data class Landed(val index: Int) : Action
    }

    private val list = ArrayList(items)
    private val spare = ArrayDeque(reserve.filter { r -> items.none { it.key == r.key } })

    var index: Int = 0
        private set
    var released: Boolean = false
        private set
    var releaseReason: String? = null
        private set

    val size: Int get() = list.size
    val current: PlayItem? get() = list.getOrNull(index)
    fun items(): List<PlayItem> = list.toList()

    /** Piesele listei care au cântat cum le-a cerut FORJA: piesa 1 (verificată de pornire), apoi fiecare aterizare. */
    var landed: Int = 1
        private set

    /** Piesa cerută care încă n-a apărut (indexul ei) și momentul cererii (ceasul monoton). */
    private var pending: Int? = null
    private var issuedAt = 0L

    /** Până când trebuie să apară piesa cerută (null = nimic de verificat). */
    val landingDeadline: Long? get() = if (pending != null && !released) issuedAt + LAND_MS else null

    private fun keyOf(title: String?, artist: String?) = TrackKey.of(title ?: "", artist ?: "")

    private fun indexOf(title: String?, artist: String?, mediaId: String?): Int {
        if (mediaId != null) {
            val byId = list.indexOfFirst { it.mediaId != null && it.mediaId == mediaId }
            if (byId >= 0) return byId
        }
        val k = keyOf(title, artist)
        val exact = list.indexOfFirst { it.key == k }
        if (exact >= 0) return exact
        if (title == null) return -1
        return list.indexOfFirst { TrackKey.matches(it.title, title) && TrackKey.artist(it.artist) == TrackKey.artist(artist ?: "") }
    }

    /**
     * Playerul a trecut la altă piesă. [prevPosMs]/[prevDurMs] = unde era piesa de dinainte (poziția extrapolată la
     * momentul schimbării); [targetPlaying] = playerul cântă acum; [fg] = FORJA e vizibilă.
     */
    fun onTrack(
        title: String?,
        artist: String?,
        mediaId: String?,
        durationMs: Long,
        prevPosMs: Long,
        prevDurMs: Long,
        targetPlaying: Boolean,
        fg: Boolean
    ): Action {
        if (released) return Action.None
        val j = indexOf(title, artist, mediaId)
        if (j >= 0) {
            index = j
            return Action.None
        }
        if (isAd(title, artist, durationMs)) return Action.Hold
        // O piesă cerută e în așteptare: altă piesă acum e răspunsul playerului la cerere (pe Spotify gratuit, una
        // înlocuitoare), sau piesa cerută venită în doi pași (titlul, apoi artistul) — nu ea care a preluat. Decide
        // termenul aterizării ([check]): piesa cerută → Landed, altceva → „refused”.
        if (pending != null) return Action.None
        val natural = prevDurMs > 0 && prevPosMs >= prevDurMs - NATURAL_END_MS
        if (!natural) return release("takeover")
        if (!fg && !targetPlaying) return release("background-stopped")
        return advance()
    }

    /** ⏭ FORJA: piesa următoare din listă (nu skipToNext, care intră în radioul playerului). */
    fun next(): Action {
        if (released) return Action.None
        return advance()
    }

    /** ⏮ FORJA: după 5 s, de la capăt; altfel piesa dinainte. */
    fun previous(positionMs: Long): Action {
        if (released) return Action.None
        if (positionMs > 5_000L || index == 0) return Action.SeekZero
        index -= 1
        return Action.Issue(list[index], index)
    }

    fun release(reason: String): Action {
        released = true
        releaseReason = reason
        pending = null
        return Action.Release(reason)
    }

    /** Piesa [index] tocmai a fost cerută playerului (comanda a plecat): de acum are [LAND_MS] ca să apară. */
    fun issued(index: Int, now: Long) {
        if (released) return
        pending = index
        issuedAt = now
    }

    /**
     * Ce cântă playerul acum, cât o piesă cerută e în așteptare (la fiecare schimbare de piesă și la termen):
     * piesa cerută → [Action.Landed]; o reclamă → așteptarea o ia de la capăt ([Action.Hold]); altceva după 6 s →
     * „refused” (coada se retrage); înainte de termen → [Action.None].
     */
    fun check(title: String?, artist: String?, mediaId: String?, durationMs: Long, now: Long): Action {
        val p = pending ?: return Action.None
        if (released) return Action.None
        if (!title.isNullOrBlank() && indexOf(title, artist, mediaId) == p) {
            pending = null
            landed += 1
            return Action.Landed(p)
        }
        if (isAd(title, artist, durationMs)) {
            issuedAt = now
            return Action.Hold
        }
        if (now - issuedAt >= LAND_MS) return release(REFUSED)
        return Action.None
    }

    private fun advance(): Action {
        if (index + 1 >= list.size) {
            val more = spare.removeFirstOrNull() ?: return release("end")
            list += more
        }
        index += 1
        return Action.Issue(list[index], index)
    }

    companion object {
        /** Crossfade-ul Spotify merge până la 12 s: o schimbare la ≤ 13 s de final e un final natural. */
        const val NATURAL_END_MS = 13_000L

        /** O piesă cerută trebuie să apară în atât (o reclamă pornește așteptarea de la capăt). */
        const val LAND_MS = 6_000L

        /** Motivul retragerii când piesa cerută n-a apărut: azi, ordinea o alege Spotify. */
        const val REFUSED = "refused"

        /** Rândul spus o dată pe antrenament când playerul n-a primit piesa cerută (toast pe ecranul live). */
        fun refusedNotice(pkg: String): String =
            if (pkg == MusicKind.SPOTIFY) "Azi, ordinea o alege Spotify." else "Azi, ordinea o alege ${MusicKind.MUSIC_APPS[pkg] ?: "playerul"}."

        /** Reclamă (Spotify Free): scurtă și fără artist, sau titlul tipic. */
        fun isAd(title: String?, artist: String?, durationMs: Long): Boolean {
            val t = title?.trim()?.lowercase().orEmpty()
            if (t == "advertisement" || t == "reclamă" || t == "spotify" && artist.isNullOrBlank()) return true
            return durationMs in 1 until 45_000L && artist.isNullOrBlank()
        }
    }
}
