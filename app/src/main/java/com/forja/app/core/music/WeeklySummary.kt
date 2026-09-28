package com.forja.app.core.music

import kotlin.math.roundToInt

/** O piesă din topul săptămânii (pe site: users/{uid}/settings/music). */
data class SummaryTrack(val title: String, val artist: String, val plays: Int, val minutes: Int, val app: String?)

/** Rezumatul săptămânii (DESIGN-4.4 §3.3): cel mult 10 piese, minute estimate din durata pieselor. */
data class MusicSummary(val updatedAt: Long, val windowDays: Int, val totalMinutes: Int, val top: List<SummaryTrack>) {
    /** Forma documentului Firestore (camelCase, ms epocă). */
    fun toMap(): Map<String, Any?> = mapOf(
        "updatedAt" to updatedAt,
        "windowDays" to windowDays,
        "totalMinutes" to totalMinutes,
        "top" to top.map { mapOf("title" to it.title, "artist" to it.artist, "plays" to it.plays, "minutes" to it.minutes, "app" to it.app) }
    )
}

/**
 * Topul săptămânii din istoricul local — Kotlin pur. Doar muzică (fără cărți, podcasturi), doar ascultări (nu săriturile).
 * Minutele: durata piesei (cel mult 10 min) pentru fiecare ascultare; fără durată, 3,4 min.
 */
object WeeklySummary {
    const val WINDOW_DAYS = 7
    const val MAX_TOP = 10

    fun build(rows: List<PlayRow>, now: Long, appName: (String?) -> String?): MusicSummary {
        val since = now - WINDOW_DAYS * HistoryCodec.DAY_MS
        val plays = rows.filter { it.at in since..now && it.event == PlayEvent.PLAY && it.isMusic }
        fun minutes(r: PlayRow): Double = if (r.durS > 0) minOf(r.durS, 600) / 60.0 else Playlist.DEFAULT_TRACK_MIN
        val total = plays.sumOf { minutes(it) }
        val top = plays.groupBy { it.key }.values.map { list ->
            val last = list.maxBy { it.at }
            val app = list.mapNotNull { it.pkg }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            Triple(
                SummaryTrack(
                    title = HistoryCodec.clean(last.title),
                    artist = HistoryCodec.clean(last.artist),
                    plays = list.size,
                    minutes = list.sumOf { minutes(it) }.roundToInt().coerceAtLeast(1),
                    app = appName(app)
                ),
                last.at,
                list.sumOf { minutes(it) }
            )
        }.sortedWith(
            compareByDescending<Triple<SummaryTrack, Long, Double>> { it.first.plays }
                .thenByDescending { it.third }
                .thenByDescending { it.second }
        ).take(MAX_TOP).map { it.first }
        return MusicSummary(now, WINDOW_DAYS, total.roundToInt(), top)
    }
}
