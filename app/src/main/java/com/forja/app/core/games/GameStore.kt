package com.forja.app.core.games

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.gamesStore by preferencesDataStore(name = "forja_games")

/** Setările jocurilor (Pauză → Sunet / Vibrații). Implicit: amândouă pornite, sunetul încet. */
data class GameSettings(val sfx: Boolean = true, val haptics: Boolean = true)

/**
 * DataStore „forja_games”: progresul (`zid_progress`, `asalt_progress`, JSON), partida neterminată (`zid_save`,
 * `asalt_save`, JSON) și setările (`sfx`, `haptics`). Progresul e al persoanei: [reset] la ieșirea din cont.
 *
 * Scrierile pleacă în fundal, într-o coadă cu un singur consumator: ordinea în care au fost cerute se păstrează
 * (o salvare veche nu o poate suprascrie pe una nouă). Un disc plin nu strică jocul.
 */
object GameStore {
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val SFX = booleanPreferencesKey("sfx")
    private val HAPTICS = booleanPreferencesKey("haptics")
    private fun progressKey(game: GameId) = stringPreferencesKey("${game.key}_progress")
    private fun saveKey(game: GameId) = stringPreferencesKey("${game.key}_save")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<Context, suspend (MutablePreferences) -> Unit>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((ctx, block) in queue) {
                try {
                    ctx.gamesStore.edit { block(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun write(context: Context, block: suspend (MutablePreferences) -> Unit) {
        queue.trySend(context.applicationContext to block)
    }

    private fun data(context: Context): Flow<Preferences> =
        context.applicationContext.gamesStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    private fun decodeProgress(raw: String?): GameProgress =
        if (raw.isNullOrBlank()) GameProgress() else try { json.decodeFromString(GameProgress.serializer(), raw) } catch (_: Exception) { GameProgress() }

    // ───────────────────────────── Progresul ─────────────────────────────

    fun progress(context: Context, game: GameId): Flow<GameProgress> =
        data(context).map { decodeProgress(it[progressKey(game)]) }.distinctUntilChanged()

    suspend fun progressNow(context: Context, game: GameId): GameProgress = progress(context, game).first()

    /**
     * Un nivel câștigat (stele, scor) sau pierdut (doar recordul). Mirror (pachetul C): jocul intră și în jurnalul Room
     * `game_plays` (ultimele 200), din care [com.forja.app.core.data.GamesMirror] scrie pagina de pe site (contract v4).
     */
    fun recordResult(context: Context, game: GameId, level: Int, outcome: GameOutcome, stars: Int, score: Int, durationS: Int = 0) {
        scope.launch {
            try {
                val dao = com.forja.app.ForjaApp.from(context).db.gamePlayDao()
                dao.insert(com.forja.app.core.data.db.GamePlayEntity(at = System.currentTimeMillis(), game = game.key, level = level,
                    outcome = if (outcome == GameOutcome.Won) "won" else "lost", stars = stars.coerceIn(0, 3), score = score.coerceAtLeast(0), durationS = durationS.coerceIn(0, 86_400)))
                dao.trim(200)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        write(context) { prefs ->
            val cur = decodeProgress(prefs[progressKey(game)])
            val next = when (outcome) {
                GameOutcome.Won -> cur.withWin(game, level, stars, score)
                GameOutcome.Lost, GameOutcome.EndlessOver -> cur.withScore(level, score)
            }
            prefs[progressKey(game)] = json.encodeToString(GameProgress.serializer(), next)
        }
    }

    // ───────────────────────────── Partida neterminată ─────────────────────────────

    /** JSON-ul partidei (ZidSave / AsaltSave); null o șterge. */
    fun writeSave(context: Context, game: GameId, save: String?) {
        write(context) { prefs -> if (save == null) prefs.remove(saveKey(game)) else prefs[saveKey(game)] = save }
    }

    suspend fun readSave(context: Context, game: GameId): String? =
        try { data(context).first()[saveKey(game)] } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    // ───────────────────────────── Setările ─────────────────────────────

    fun settings(context: Context): Flow<GameSettings> =
        data(context).map { GameSettings(sfx = it[SFX] ?: true, haptics = it[HAPTICS] ?: true) }.distinctUntilChanged()

    fun setSfx(context: Context, on: Boolean) = write(context) { it[SFX] = on }

    fun setHaptics(context: Context, on: Boolean) = write(context) { it[HAPTICS] = on }

    /** Ieșirea din cont: alt om pe același telefon începe de la nivelul 1. */
    suspend fun reset(context: Context) {
        GameSessions.forget()
        // Tot prin coadă, după scrierile deja cerute (o salvare de acum o clipă nu reapare după ștergere).
        val done = CompletableDeferred<Unit>()
        write(context) { prefs ->
            try { prefs.clear() } finally { done.complete(Unit) }
        }
        withTimeoutOrNull(1_500) { done.await() }
    }
}
