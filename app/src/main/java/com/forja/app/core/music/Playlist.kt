package com.forja.app.core.music

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

/** Ce listă cere Antrenamentul: amestec, preferatele de acum, cele vechi, Melodii apreciate. */
enum class Mix(val code: String) {
    MIX("mix"), NEW("new"), OLD("old"), LIKED("liked");

    companion object {
        fun of(code: String?): Mix = entries.firstOrNull { it.code == code } ?: MIX
    }
}

/** Treapta unei piese în listă: preferată acum, preferată de demult, constantă (umplutură). */
enum class Tier { NEW, OLD, STEADY }

data class PlayItem(
    val key: String,
    val title: String,
    val artist: String,
    val pkg: String?,
    val mediaId: String?,
    val uri: String?,
    val durS: Int,
    val tier: Tier
) {
    fun ref(): TrackRef = TrackRef(title, artist, pkg, mediaId, uri)
}

/** Câte piese are fiecare treaptă acum (etichetele „MIX · 12 PIESE”, „NOI · 8”, „VECHI · 6”). */
data class TierCounts(val new: Int = 0, val old: Int = 0, val steady: Int = 0) {
    val total: Int get() = new + old + steady
    /** Sub 5 piese nu se face o listă FORJA: pornirea merge direct la Melodii apreciate. */
    val cold: Boolean get() = total < Playlist.COLD_MIN
}

data class FPlaylist(
    val mix: Mix,
    val items: List<PlayItem>,
    /** Următoarele cele mai bune, pentru când sesiunea durează mai mult decât lista. */
    val reserve: List<PlayItem>,
    val playerPkg: String?,
    val counts: TierCounts
) {
    /** Fără listă FORJA: Melodii apreciate (sau ce reia playerul). */
    val liked: Boolean get() = items.isEmpty()
}

/**
 * Lista FORJA pentru Antrenament (workout-music.md §2.2), din istoricul local — Kotlin pur.
 *
 * - Noi: ≥ 2 ascultări ale ei în 7 zile; scor = Σ 0,5^(zile/3), ×1,3 dacă e descoperită în ultimele 14 zile.
 * - Vechi: ≥ 3 ascultări în total, niciuna în ultimele 30 de zile, ultima în 400 de zile; scor = total × 0,5^((zile−30)/180).
 * - Constante: ≥ 2 ascultări în 30 de zile, nu deja în Noi; scor = ascultările din 30 de zile.
 * - Amestec: primul loc = cea mai bună Noi, apoi [Noi, Noi, Vechi]; o treaptă goală se umple din Constante, apoi din cealaltă.
 * - Același artist niciodată la mai puțin de 3 locuri; niciun artist peste ⌈N/4⌉ locuri.
 * - Ordinea într-o treaptă e amestecată determinist (sămânța = ziua), deci o repornire în aceeași zi dă aceeași listă.
 * - Doar muzică: fără cărți/podcasturi, fără piese > 15 min sau < 45 s, fără piesele sărite mai des decât ascultate.
 * - Ascultările pornite de lista FORJA nu contează la scor (lista nu se hrănește din ea însăși).
 */
object Playlist {
    const val MIN_ITEMS = 8
    const val MAX_ITEMS = 30
    const val COLD_MIN = 5
    const val DEFAULT_TRACK_MIN = 3.4
    private const val DAY = HistoryCodec.DAY_MS

    /** Cât ține sesiunea, estimat: Σ serii × (repetări × 3 s + 90 s pauză). */
    fun targetMinutes(setsAndReps: List<Pair<Int, Int>>): Int {
        val sec = setsAndReps.sumOf { (sets, reps) -> sets.coerceAtLeast(1) * (reps.coerceAtLeast(1) * 3 + 90) }
        return ceil(sec / 60.0).toInt().coerceAtLeast(10)
    }

    fun epochDay(now: Long): Long = Math.floorDiv(now, DAY)

    private class Cand(
        val key: String,
        var title: String,
        var artist: String,
        var pkg: String?,
        var mediaId: String?,
        var uri: String?,
        var durS: Int,
        var firstAt: Long = Long.MAX_VALUE
    ) {
        val userPlays = ArrayList<Long>()
        var skips = 0
        var total = 0
    }

    private fun eligibleDuration(durS: Int) = durS == 0 || durS in 45..900

    private fun candidates(rows: List<PlayRow>, library: Map<String, LibraryEntry>): Map<String, Cand> {
        val out = LinkedHashMap<String, Cand>()
        for (e in library.values) {
            if (!e.isMusic) continue
            out[e.key] = Cand(e.key, e.title, e.artist, e.pkg, e.mediaId, e.uri, e.durS, e.firstAt).also {
                it.skips = e.skips
                it.total = e.userPlays
            }
        }
        for (r in rows.sortedBy { it.at }) {
            if (!r.isMusic) continue
            val c = out.getOrPut(r.key) { Cand(r.key, r.title, r.artist, r.pkg, r.mediaId, r.uri, r.durS) }
            // Metadatele cele mai noi câștigă (un ID media apărut mai târziu).
            if (r.pkg != null) c.pkg = r.pkg
            if (r.mediaId != null) c.mediaId = r.mediaId
            if (r.uri != null) c.uri = r.uri
            if (r.durS > 0) c.durS = r.durS
            c.firstAt = minOf(c.firstAt, r.at)
            if (r.isUserPlay) c.userPlays += r.at
            if (library.isEmpty()) {
                if (r.event == PlayEvent.SKIP) c.skips++ else if (r.isUserPlay) c.total++
            }
        }
        return out.filterValues { c ->
            val skipHeavy = c.total + c.skips >= 3 && c.skips.toDouble() / (c.total + c.skips) >= 0.5
            eligibleDuration(c.durS) && !skipHeavy && !c.key.startsWith("|")
        }
    }

    private data class Scored(val c: Cand, val score: Double, val tier: Tier)

    private fun tiers(rows: List<PlayRow>, library: Map<String, LibraryEntry>, now: Long): Triple<List<Scored>, List<Scored>, List<Scored>> {
        val cands = candidates(rows, library)
        val weekAgo = now - 7 * DAY
        val monthAgo = now - 30 * DAY
        val newT = ArrayList<Scored>()
        val oldT = ArrayList<Scored>()
        val steadyT = ArrayList<Scored>()
        for (c in cands.values) {
            val week = c.userPlays.filter { it >= weekAgo }
            val month = c.userPlays.count { it >= monthAgo }
            val lastUser = maxOf(c.userPlays.maxOrNull() ?: 0L, library[c.key]?.lastUserAt ?: 0L)
            if (week.size >= 2) {
                var score = week.sumOf { 0.5.pow(((now - it).toDouble() / DAY) / 3.0) }
                if (c.firstAt >= now - 14 * DAY) score *= 1.3
                newT += Scored(c, score, Tier.NEW)
            } else if (month >= 2) {
                steadyT += Scored(c, month.toDouble(), Tier.STEADY)
            } else if (c.total >= 3 && lastUser in 1 until monthAgo && lastUser >= now - 400 * DAY) {
                val since = (now - lastUser).toDouble() / DAY
                oldT += Scored(c, c.total * 0.5.pow(maxOf(0.0, since - 30) / 180.0), Tier.OLD)
            }
        }
        val order = compareByDescending<Scored> { it.score }.thenBy { it.c.key }
        return Triple(newT.sortedWith(order), oldT.sortedWith(order), steadyT.sortedWith(order))
    }

    fun counts(rows: List<PlayRow>, library: Map<String, LibraryEntry>, now: Long): TierCounts {
        val (n, o, s) = tiers(rows, library, now)
        return TierCounts(n.size, o.size, s.size)
    }

    /** Durata medie a unei piese din istoricul ei (mediana), altfel 3,4 min. */
    private fun avgTrackMin(rows: List<PlayRow>): Double {
        val d = rows.filter { it.isMusic && it.durS in 45..900 }.map { it.durS }.sorted()
        if (d.size < 5) return DEFAULT_TRACK_MIN
        return d[d.size / 2] / 60.0
    }

    fun build(
        rows: List<PlayRow>,
        library: Map<String, LibraryEntry>,
        mix: Mix,
        targetMin: Int,
        now: Long,
        seed: Long = epochDay(now)
    ): FPlaylist {
        val (newT, oldT, steadyT) = tiers(rows, library, now)
        val counts = TierCounts(newT.size, oldT.size, steadyT.size)
        val empty = FPlaylist(mix, emptyList(), emptyList(), null, counts)
        if (mix == Mix.LIKED || counts.cold) return empty
        // „Noi” fără piese noi sau „Vechi” fără piese vechi nu se umplu pe ascuns cu altceva.
        if (mix == Mix.NEW && counts.new == 0 || mix == Mix.OLD && counts.old == 0) return empty

        val n = ceil(targetMin / avgTrackMin(rows)).toInt().coerceIn(MIN_ITEMS, MAX_ITEMS)
        val rnd = Random(seed)
        // Bazinele: cele mai bune ~2N din fiecare treaptă, amestecate determinist (prima Noi rămâne prima).
        fun pool(list: List<Scored>, keepFirst: Boolean): ArrayDeque<Scored> {
            val top = list.take(n * 2)
            if (top.isEmpty()) return ArrayDeque()
            val head = if (keepFirst) listOf(top.first()) else emptyList()
            val rest = (if (keepFirst) top.drop(1) else top).shuffled(rnd)
            return ArrayDeque(head + rest)
        }
        val pools = mapOf(
            Tier.NEW to pool(newT, keepFirst = mix != Mix.OLD),
            Tier.OLD to pool(oldT, keepFirst = false),
            Tier.STEADY to pool(steadyT, keepFirst = false)
        )
        val pattern: (Int) -> Tier = when (mix) {
            Mix.NEW -> { _ -> Tier.NEW }
            Mix.OLD -> { _ -> Tier.OLD }
            else -> { i -> if (i == 0) Tier.NEW else if ((i - 1) % 3 == 2) Tier.OLD else Tier.NEW }
        }
        fun fallbacks(t: Tier): List<Tier> = when (mix) {
            Mix.NEW -> listOf(Tier.NEW, Tier.STEADY)
            Mix.OLD -> listOf(Tier.OLD, Tier.STEADY)
            else -> when (t) {
                Tier.NEW -> listOf(Tier.NEW, Tier.STEADY, Tier.OLD)
                Tier.OLD -> listOf(Tier.OLD, Tier.STEADY, Tier.NEW)
                Tier.STEADY -> listOf(Tier.STEADY, Tier.NEW, Tier.OLD)
            }
        }
        val maxPerArtist = ceil(n / 4.0).toInt().coerceAtLeast(1)
        val picked = ArrayList<Scored>()
        val perArtist = HashMap<String, Int>()
        fun fits(s: Scored): Boolean {
            val a = TrackKey.artist(s.c.artist)
            if (a.isEmpty()) return true
            if ((perArtist[a] ?: 0) >= maxPerArtist) return false
            return picked.takeLast(3).none { TrackKey.artist(it.c.artist) == a }
        }
        var slot = 0
        while (picked.size < n) {
            var chosen: Scored? = null
            for (t in fallbacks(pattern(slot))) {
                val q = pools.getValue(t)
                val hit = q.firstOrNull { fits(it) }
                if (hit != null) {
                    q.remove(hit)
                    chosen = hit
                    break
                }
            }
            if (chosen == null) break
            picked += chosen
            val a = TrackKey.artist(chosen.c.artist)
            if (a.isNotEmpty()) perArtist[a] = (perArtist[a] ?: 0) + 1
            slot++
        }
        if (picked.size < COLD_MIN) return empty

        val reserve = fallbacks(Tier.NEW).flatMap { pools.getValue(it) }.distinctBy { it.c.key }.take(20)
        val items = picked.map { it.item() }
        val player = items.mapNotNull { it.pkg }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return FPlaylist(mix, items, reserve.map { it.item() }, player, counts)
    }

    private fun Scored.item() = PlayItem(c.key, c.title, c.artist, c.pkg, c.mediaId, c.uri, c.durS, tier)
}
