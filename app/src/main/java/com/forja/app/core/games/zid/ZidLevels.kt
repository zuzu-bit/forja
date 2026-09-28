package com.forja.app.core.games.zid

import com.forja.app.core.games.ZID_ENDLESS
import kotlin.math.pow
import kotlin.math.round

/** Ținta unui nivel ZID: câte linii, tot molozul (Sapă) sau fără sfârșit. */
enum class ZidGoal { Lines, Dig, Endless }

/**
 * Un nivel ZID. `rubble` = rândurile de moloz de sus în jos (10 caractere, `#` = bloc, `.` = gol), așezate pe fundul
 * tablei. `par2` / `par3`: la Linii, scorul minim pentru ★★ / ★★★; la Sapă, secundele maxime.
 */
data class ZidLevel(
    val id: Int,
    val name: String,
    val gravityMs: Int,
    val goal: ZidGoal,
    val target: Int,
    val rubble: List<String> = emptyList(),
    val fort: Boolean = false
) {
    val par2: Int
        get() = when (goal) {
            ZidGoal.Lines -> (STAR2 * 100 * target * id).toInt()
            ZidGoal.Dig -> DIG_STAR2_S * rubble.size
            ZidGoal.Endless -> 0
        }
    val par3: Int
        get() = when (goal) {
            ZidGoal.Lines -> (STAR3 * 100 * target * id).toInt()
            ZidGoal.Dig -> DIG_STAR3_S * rubble.size
            ZidGoal.Endless -> 0
        }

    /** Stelele unui nivel câștigat (1–3). Fără sfârșit: 0. */
    fun stars(score: Int, elapsedMs: Long): Int = when (goal) {
        ZidGoal.Lines -> when {
            score >= par3 -> 3
            score >= par2 -> 2
            else -> 1
        }
        ZidGoal.Dig -> {
            val s = elapsedMs / 1000.0
            when {
                s <= par3 -> 3
                s <= par2 -> 2
                else -> 1
            }
        }
        ZidGoal.Endless -> 0
    }

    companion object {
        /** Pragurile stelelor (butoane de reglaj). O linie din 4 plătește dublu, deci ★★★ cere cvadruple sau combo-uri. */
        const val STAR2 = 1.3
        const val STAR3 = 1.9
        const val DIG_STAR2_S = 30
        const val DIG_STAR3_S = 15
    }
}

/** Cele 15 niveluri + „Fără sfârșit”. Gravitația = round(900 × 0,885^(L−1)) ms pe rând (tabelul din games.md §5.1). */
object ZidLevels {
    const val GRAVITY_BASE = 900
    const val GRAVITY_FACTOR = 0.885
    const val GRAVITY_FLOOR_MS = 60
    const val LINES_PER_RANK = 10

    /** Gravitația (ms/rând) pentru un nivel sau rang; rotunjire „la par”, ca tabelul. */
    fun gravityFor(step: Int): Int =
        round(GRAVITY_BASE * GRAVITY_FACTOR.pow((step - 1).coerceAtLeast(0))).toInt().coerceAtLeast(GRAVITY_FLOOR_MS)

    val all: List<ZidLevel> = listOf(
        ZidLevel(1, "Temelia", 900, ZidGoal.Lines, 6),
        ZidLevel(2, "Mortar", 796, ZidGoal.Lines, 8),
        ZidLevel(3, "Cărămida", 705, ZidGoal.Lines, 10),
        ZidLevel(
            4, "Moloz", 624, ZidGoal.Dig, 0, listOf(
                "####.#####",
                "##.#######",
                "#######.##"
            )
        ),
        ZidLevel(
            5, "Schela", 552, ZidGoal.Lines, 12, listOf(
                "#.#####.##",
                "####.#####"
            ), fort = true
        ),
        ZidLevel(6, "Parapet", 489, ZidGoal.Lines, 14),
        ZidLevel(
            7, "Ruine", 432, ZidGoal.Dig, 0, listOf(
                "###.######",
                "#.########",
                "#####.##.#",
                "######.###",
                ".#########"
            )
        ),
        ZidLevel(
            8, "Bastion", 383, ZidGoal.Lines, 15, listOf(
                "##.####.##",
                "#.######.#",
                "####..####"
            )
        ),
        ZidLevel(9, "Contrafort", 339, ZidGoal.Lines, 16),
        ZidLevel(
            10, "Pivnița", 300, ZidGoal.Dig, 0, listOf(
                "#######.##",
                "###.######",
                "######.###",
                "#.########",
                "####.#####",
                "########.#",
                "##.#######"
            ), fort = true
        ),
        ZidLevel(
            11, "Metereze", 265, ZidGoal.Lines, 18, listOf(
                "#.########",
                "#####.####",
                "##.####.##",
                "########.#"
            )
        ),
        ZidLevel(12, "Turnul", 235, ZidGoal.Lines, 20),
        ZidLevel(
            13, "Catacombe", 208, ZidGoal.Dig, 0, listOf(
                "#####.####",
                "##.#######",
                "#######.##",
                ".#########",
                "####.#####",
                "######.###",
                "#.########",
                "###.######",
                "########.#"
            )
        ),
        ZidLevel(
            14, "Zidul mare", 184, ZidGoal.Lines, 22, listOf(
                "###.######",
                "#######.##",
                "#.#####.##",
                "####.#####",
                "########.#"
            )
        ),
        ZidLevel(
            15, "Citadela", 163, ZidGoal.Lines, 25, listOf(
                "##.#######",
                "######.###",
                "#.########",
                "#####.##.#",
                "###.######",
                "#######.##"
            ), fort = true
        )
    )

    val endless = ZidLevel(ZID_ENDLESS, "Fără sfârșit", GRAVITY_BASE, ZidGoal.Endless, 0)

    fun byId(id: Int): ZidLevel = if (id == ZID_ENDLESS) endless else all.getOrNull(id - 1) ?: all[0]
}
