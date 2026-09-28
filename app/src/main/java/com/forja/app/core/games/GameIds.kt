package com.forja.app.core.games

import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.core.games.zid.ZidPhase
import kotlinx.serialization.Serializable

/**
 * Cele două jocuri din „Cât aștepți” (4.4): ZID (piese care cad, rânduri închise) și ASALT (nicovala, scânteia,
 * zidurile fortului). `levels` = nivelurile campaniei; ZID mai are un nivel fără sfârșit ([ZID_ENDLESS]).
 */
enum class GameId(val key: String, val levels: Int) {
    Zid("zid", 15),
    Asalt("asalt", 12)
}

/** Id-ul nivelului „Fără sfârșit” din ZID (nodul ∞ din vârful hărții). */
const val ZID_ENDLESS = 16

/** Nivelul de la care se deschide „Fără sfârșit” (după nivelul 5 câștigat). */
const val ZID_ENDLESS_UNLOCK = 6

/** Cum s-a terminat un nivel. */
enum class GameOutcome { Won, Lost, EndlessOver }

/**
 * Progresul unei persoane într-un joc (DataStore „forja_games”, JSON). `unlocked` = cel mai mare nivel deschis;
 * `stars` / `best` pe nivel; `endlessBest` = recordul din „Fără sfârșit”.
 */
@Serializable
data class GameProgress(
    val unlocked: Int = 1,
    val stars: Map<Int, Int> = emptyMap(),
    val best: Map<Int, Int> = emptyMap(),
    val endlessBest: Int = 0
) {
    /** Nivelul „curent” (primul nejucat până la capăt), în limitele campaniei. */
    fun current(game: GameId): Int = unlocked.coerceIn(1, game.levels)

    fun starsOf(level: Int): Int = stars[level] ?: 0

    fun bestOf(level: Int): Int = if (level == ZID_ENDLESS) endlessBest else best[level] ?: 0

    fun isUnlocked(game: GameId, level: Int): Boolean =
        if (game == GameId.Zid && level == ZID_ENDLESS) unlocked >= ZID_ENDLESS_UNLOCK else level in 1..unlocked.coerceAtMost(game.levels)

    /** Un nivel câștigat: se deschide următorul, stelele și recordul rămân cele mai bune. */
    fun withWin(game: GameId, level: Int, stars: Int, score: Int): GameProgress {
        if (level !in 1..game.levels) return this
        return copy(
            unlocked = maxOf(unlocked, (level + 1).coerceAtMost(game.levels + 1)),
            stars = this.stars + (level to maxOf(starsOf(level), stars.coerceIn(0, 3))),
            best = best + (level to maxOf(best[level] ?: 0, score))
        )
    }

    /** Un nivel pierdut: doar recordul (dacă e cazul). */
    fun withScore(level: Int, score: Int): GameProgress =
        if (level == ZID_ENDLESS) copy(endlessBest = maxOf(endlessBest, score))
        else if (score > (best[level] ?: 0)) copy(best = best + (level to score)) else this

    /** Câte niveluri au măcar o stea. */
    val cleared: Int get() = stars.count { it.value > 0 }
}

/** Partida mai poate continua (Ready inclus: nivelul ales, încă neînceput). */
val ZidPhase.active: Boolean get() = this == ZidPhase.Ready || this == ZidPhase.Falling || this == ZidPhase.Clearing

val AsaltPhase.active: Boolean get() = this == AsaltPhase.Ready || this == AsaltPhase.Playing || this == AsaltPhase.LifeLost
