package com.forja.app.core.music

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-ul PROPRIU al muzicii („forja_music”) — ascultările, biblioteca, comutatoarele, tabelul învățat,
 * jurnalul de pornire; nu stau în Prefs.kt.
 */
internal val Context.musicStore by preferencesDataStore(name = "forja_music")

/**
 * Ascultările tale, doar pe telefon: o melodie cântată cel puțin 30 s = o ascultare (numărată de [Music]); o piesă
 * schimbată după 3–30 s = o săritură. Rândurile ([PlayRow], format în [HistoryCodec]) țin 90 de zile, cel mult 3 000;
 * biblioteca (totaluri per piesă, pentru „Vechi”) ține 400 de zile, cel mult 2 000 de piese.
 * Din 4.4 se păstrează și pachetul playerului, ID-ul/URI-ul media, durata, felul (muzică/vorbit/video) și cine a
 * pornit piesa — toate din metadatele sesiunii media, nimic din notificări.
 */
internal object MusicStats {
    private val PLAYS = stringPreferencesKey("plays")
    private val LIBRARY = stringPreferencesKey("library")
    private val STOP_WHEN_DONE = booleanPreferencesKey("stop_when_done")
    private val SHARE_ON_MAP = booleanPreferencesKey("share_on_map")
    private val WORKOUT_MUSIC = booleanPreferencesKey("workout_music")
    private val WORKOUT_MIX = stringPreferencesKey("workout_mix")
    private val WORKOUT_STOP = booleanPreferencesKey("workout_stop_at_end")
    private val LEARNED = stringPreferencesKey("learned")
    private val DIAG = stringPreferencesKey("diag")
    private val DIAG_PENDING = stringPreferencesKey("diag_pending")
    private val SUMMARY_AT = longPreferencesKey("summary_at")
    private val LAST_MUSIC_PKG = stringPreferencesKey("last_music_pkg")

    private const val KEEP_MS = 90L * 24 * 3600 * 1000
    private const val MAX_LINES = 3_000
    private const val DAY_MS = HistoryCodec.DAY_MS

    // ───────────────────────────── Ascultări ─────────────────────────────

    suspend fun record(context: Context, row: PlayRow) {
        if (HistoryCodec.clean(row.title).isEmpty()) return
        context.musicStore.edit { p ->
            val old = HistoryCodec.parse(p[PLAYS])
            val keep = old.filter { row.at - it.at < KEEP_MS }.takeLast(MAX_LINES - 1)
            p[PLAYS] = HistoryCodec.encode(keep + row)
            // Biblioteca: la prima scriere după 4.3 se clădește din ascultările existente.
            val lib = p[LIBRARY]?.let { HistoryCodec.parseLibrary(it).toMutableMap() } ?: HistoryCodec.fold(old)
            HistoryCodec.apply(lib, row)
            HistoryCodec.trimLibrary(lib, row.at)
            p[LIBRARY] = HistoryCodec.encodeLibrary(lib)
        }
    }

    /** Alt cont a intrat pe telefon (MindOwner): ascultările celui dinainte nu trec la el. */
    suspend fun clearHistory(context: Context) { context.musicStore.edit { it.remove(PLAYS); it.remove(LIBRARY) } }

    suspend fun rows(context: Context): List<PlayRow> = HistoryCodec.parse(context.musicStore.data.first()[PLAYS])

    /** Ascultările, live (mirror D: ListenMirror le trimite pe zile cu contractul v4). */
    fun rowsFlow(context: Context): Flow<List<PlayRow>> =
        context.musicStore.data.map { it[PLAYS] }.distinctUntilChanged().map { HistoryCodec.parse(it) }

    suspend fun library(context: Context): Map<String, LibraryEntry> {
        val p = context.musicStore.data.first()
        return p[LIBRARY]?.let { HistoryCodec.parseLibrary(it) } ?: HistoryCodec.fold(HistoryCodec.parse(p[PLAYS]))
    }

    /** Cea mai ascultată melodie din ultimele [days] zile (doar muzică): („Titlu · Artist”, ascultări). */
    suspend fun top(context: Context, days: Int): Pair<String, Int>? {
        val best = topPlays(rows(context), days) ?: return null
        return TrackKey.label(best.first.title, best.first.artist) to best.second
    }

    /** Aceeași piesă, de cerut playerului: titlu, artist, pachet, ID media (când au fost văzute). */
    suspend fun topRef(context: Context, days: Int = 7): TrackRef? {
        val best = topPlays(rows(context), days) ?: return null
        val r = best.first
        return TrackRef(r.title, r.artist, r.pkg, r.mediaId, r.uri)
    }

    /** La egalitate câștigă cea ascultată mai recent; metadatele sunt cele ale ultimei ascultări cu pachet. */
    private fun topPlays(all: List<PlayRow>, days: Int): Pair<PlayRow, Int>? {
        val since = System.currentTimeMillis() - days.coerceAtLeast(1) * DAY_MS
        val plays = all.filter { it.at >= since && it.event == PlayEvent.PLAY && it.isMusic }
        if (plays.isEmpty()) return null
        val best = plays.groupBy { it.key }.values.maxWithOrNull(
            compareBy<List<PlayRow>> { it.size }.thenBy { list -> list.maxOf { it.at } }
        ) ?: return null
        val latest = best.filter { it.pkg != null }.maxByOrNull { it.at } ?: best.maxBy { it.at }
        return latest to best.size
    }

    /** Playerul tău: cel cu cele mai multe ascultări de muzică în 30 de zile, altfel ultimul văzut cântând muzică. */
    suspend fun preferredPkg(context: Context): String? {
        val since = System.currentTimeMillis() - 30 * DAY_MS
        val byPkg = rows(context).filter { it.at >= since && it.event == PlayEvent.PLAY && it.isMusic && it.pkg != null }
            .groupingBy { it.pkg!! }.eachCount()
        return byPkg.maxByOrNull { it.value }?.key ?: context.musicStore.data.first()[LAST_MUSIC_PKG]
    }

    /** Pachetele folosite pentru muzică (istoric). */
    suspend fun historyPkgs(context: Context): Set<String> =
        rows(context).filter { it.isMusic && it.pkg != null }.mapNotNull { it.pkg }.toSet()

    suspend fun setLastMusicPkg(context: Context, pkg: String) {
        context.musicStore.edit { if (it[LAST_MUSIC_PKG] != pkg) it[LAST_MUSIC_PKG] = pkg }
    }

    /** Ultimul player văzut cântând muzică (4.4.1: un player văzut cântând e „instalat”, orice ar spune detecția). */
    suspend fun lastMusicPkg(context: Context): String? = context.musicStore.data.first()[LAST_MUSIC_PKG]

    // ───────────────────────────── Inventar: „Oprește la final”, „Pe hartă” ─────────────────────────────

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

    // ───────────────────────────── Antrenament ─────────────────────────────

    /** „Muzică” la Antrenament: null = nealeasă (pornită dacă FORJA vede muzica, oprită altfel). */
    fun workoutMusicFlow(context: Context): Flow<Boolean?> = context.musicStore.data.map { it[WORKOUT_MUSIC] }.distinctUntilChanged()

    suspend fun workoutMusic(context: Context): Boolean? = context.musicStore.data.first()[WORKOUT_MUSIC]

    suspend fun setWorkoutMusic(context: Context, on: Boolean) {
        context.musicStore.edit { it[WORKOUT_MUSIC] = on }
    }

    fun workoutMixFlow(context: Context): Flow<Mix> = context.musicStore.data.map { Mix.of(it[WORKOUT_MIX]) }.distinctUntilChanged()

    suspend fun workoutMix(context: Context): Mix = Mix.of(context.musicStore.data.first()[WORKOUT_MIX])

    suspend fun setWorkoutMix(context: Context, mix: Mix) {
        context.musicStore.edit { it[WORKOUT_MIX] = mix.code }
    }

    /** „Oprește la final” al Antrenamentului (separat de cel al Inventarului); implicit pornit. */
    fun workoutStopFlow(context: Context): Flow<Boolean> = context.musicStore.data.map { it[WORKOUT_STOP] ?: true }.distinctUntilChanged()

    suspend fun workoutStop(context: Context): Boolean = context.musicStore.data.first()[WORKOUT_STOP] ?: true

    suspend fun setWorkoutStop(context: Context, on: Boolean) {
        context.musicStore.edit { it[WORKOUT_STOP] = on }
    }

    // ───────────────────────────── Motorul de pornire ─────────────────────────────

    suspend fun learned(context: Context): LearnedTable = LearnedTable.decode(context.musicStore.data.first()[LEARNED])

    suspend fun setLearned(context: Context, table: LearnedTable) {
        context.musicStore.edit { if (table.isEmpty()) it.remove(LEARNED) else it[LEARNED] = table.encode() }
    }

    suspend fun diag(context: Context): Pair<String?, String?> {
        val p = context.musicStore.data.first()
        return p[DIAG] to p[DIAG_PENDING]
    }

    suspend fun setDiag(context: Context, recent: String?, pending: String?) {
        context.musicStore.edit {
            if (recent.isNullOrEmpty()) it.remove(DIAG) else it[DIAG] = recent
            if (pending.isNullOrEmpty()) it.remove(DIAG_PENDING) else it[DIAG_PENDING] = pending
        }
    }

    suspend fun summaryAt(context: Context): Long = context.musicStore.data.first()[SUMMARY_AT] ?: 0L

    suspend fun setSummaryAt(context: Context, at: Long) {
        context.musicStore.edit { it[SUMMARY_AT] = at }
    }
}
