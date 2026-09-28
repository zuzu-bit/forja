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
 */
class MusicQueue(items: List<PlayItem>, reserve: List<PlayItem>, val targetPkg: String) {

    sealed interface Action {
        data object None : Action
        data class Issue(val item: PlayItem, val index: Int) : Action
        data object SeekZero : Action
        data object Hold : Action
        data class Release(val reason: String) : Action
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
        return Action.Release(reason)
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

        /** Reclamă (Spotify Free): scurtă și fără artist, sau titlul tipic. */
        fun isAd(title: String?, artist: String?, durationMs: Long): Boolean {
            val t = title?.trim()?.lowercase().orEmpty()
            if (t == "advertisement" || t == "reclamă" || t == "spotify" && artist.isNullOrBlank()) return true
            return durationMs in 1 until 45_000L && artist.isNullOrBlank()
        }
    }
}
