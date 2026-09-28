package com.forja.app.core.music

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore-ul PROPRIU al muzicii („forja_music”) — ascultările, „Oprește la final” și „Pe hartă”; nu stau în Prefs.kt. */
private val Context.musicStore by preferencesDataStore(name = "forja_music")

/**
 * Ascultările tale, doar pe telefon: o melodie cântată cel puțin 30 s = o ascultare (numărată de [Music]).
 * Fiecare ascultare e o linie „moment⇥titlu⇥artist”; se păstrează 90 de zile, cel mult 3 000 de linii.
 */
internal object MusicStats {
    private val PLAYS = stringPreferencesKey("plays")
    private val STOP_WHEN_DONE = booleanPreferencesKey("stop_when_done")
    private val SHARE_ON_MAP = booleanPreferencesKey("share_on_map")
    private const val KEEP_MS = 90L * 24 * 3600 * 1000
    private const val MAX_LINES = 3_000
    private const val DAY_MS = 24L * 3600 * 1000

    private class Play(val at: Long, val title: String, val artist: String)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(120)

    private fun parse(raw: String?): List<Play> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            Play(at, parts[1], parts[2])
        }.toList()
    }

    private fun label(title: String, artist: String) = if (artist.isBlank()) title else "$title · $artist"

    suspend fun record(context: Context, title: String, artist: String, at: Long) {
        val t = clean(title)
        if (t.isEmpty()) return
        val a = clean(artist)
        context.musicStore.edit { p ->
            val keep = parse(p[PLAYS]).filter { at - it.at < KEEP_MS }.takeLast(MAX_LINES - 1)
            p[PLAYS] = (keep.map { "${it.at}\t${it.title}\t${it.artist}" } + "$at\t$t\t$a").joinToString("\n")
        }
    }

    /** Cea mai ascultată melodie din ultimele [days] zile; la egalitate, cea ascultată mai recent. */
    suspend fun top(context: Context, days: Int): Pair<String, Int>? {
        val since = System.currentTimeMillis() - days.coerceAtLeast(1) * DAY_MS
        val plays = parse(context.musicStore.data.first()[PLAYS]).filter { it.at >= since }
        if (plays.isEmpty()) return null
        val byTrack = plays.groupBy { label(it.title, it.artist) }
        val best = byTrack.maxWithOrNull(
            compareBy<Map.Entry<String, List<Play>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.at } }
        ) ?: return null
        return best.key to best.value.size
    }

    suspend fun stopWhenDone(context: Context): Boolean = context.musicStore.data.first()[STOP_WHEN_DONE] ?: true

    suspend fun setStopWhenDone(context: Context, enabled: Boolean) {
        context.musicStore.edit { it[STOP_WHEN_DONE] = enabled }
    }

    fun stopWhenDoneFlow(context: Context): Flow<Boolean> =
        context.musicStore.data.map { it[STOP_WHEN_DONE] ?: true }.distinctUntilChanged()

    /** „Pe hartă”: prietenii văd ce asculți (implicit da, după ce ai dat accesul la Muzică; se oprește dintr-o atingere). */
    fun shareOnMapFlow(context: Context): Flow<Boolean> =
        context.musicStore.data.map { it[SHARE_ON_MAP] ?: true }.distinctUntilChanged()

    suspend fun shareOnMap(context: Context): Boolean = context.musicStore.data.first()[SHARE_ON_MAP] ?: true

    suspend fun setShareOnMap(context: Context, enabled: Boolean) {
        context.musicStore.edit { it[SHARE_ON_MAP] = enabled }
    }
}
