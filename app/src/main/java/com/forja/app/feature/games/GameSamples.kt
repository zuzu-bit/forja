package com.forja.app.feature.games

import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.ZID_ENDLESS
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltLevels
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.core.games.asalt.AsaltSave
import com.forja.app.core.games.zid.ZidConfig
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidLevels
import com.forja.app.core.games.zid.ZidPhase
import com.forja.app.core.games.zid.ZidPieces
import com.forja.app.core.games.zid.ZidSave
import com.forja.app.feature.inventory.PillState

/**
 * Date false pentru capturile jocurilor (Roborazzi): o partidă ZID la nivelul 6 (un puț în coloana din dreapta și un I
 * care vine exact pentru el), un zid ASALT „Poarta” pe jumătate spart, progresul de pe hartă, cardurile de final.
 */
internal object GameSamples {

    val pill = PillState(34, ready = false)
    val pillReady = PillState(100, ready = true)

    // ───────────────────────────── ZID ─────────────────────────────

    /** Tabla de la nivelul 6: J, S, T, O, I, L așezate curat, o gaură sub J, puțul în coloana 9. */
    private val zidRows = listOf(
        "6000000000",
        "6660443000",
        "2204433370",
        "2211117770"
    )

    private fun board(rows: List<String>): String {
        val c = CharArray(ZidEngine.W * ZidEngine.H) { '0' }
        rows.forEachIndexed { i, row ->
            val y = ZidEngine.H - rows.size + i
            for (x in 0 until ZidEngine.W) c[y * ZidEngine.W + x] = row[x]
        }
        return String(c)
    }

    val zidMid: ZidSave by lazy {
        ZidEngine.create(ZidLevels.byId(6), 42L).save().copy(
            cells = board(zidRows),
            phase = ZidPhase.Falling,
            kind = ZidPieces.I, rot = 1, x = 7, y = 6, lowestY = 6,
            queue = listOf(ZidPieces.T, ZidPieces.O, ZidPieces.Z, ZidPieces.L, ZidPieces.S, ZidPieces.J, ZidPieces.I),
            hold = ZidPieces.S,
            holdUsed = false,
            score = 5_740,
            lines = 9,
            elapsedMs = 83_000L
        )
    }

    fun zidPlay(): ZidEngine = ZidEngine.restore(zidMid, ZidConfig())

    /** Aceeași tablă în „Fără sfârșit”: 37 de linii → RANG 4, scorul din cardul de final. */
    fun zidEndlessPlay(): ZidEngine = ZidEngine.restore(zidMid.copy(level = ZID_ENDLESS, lines = 37, score = 24_600), ZidConfig())

    /** Nivelul 1, neînceput: tabla goală și degetul care pulsează. */
    fun zidReady(): ZidEngine = ZidEngine.create(ZidLevels.byId(1), 7L)

    val zidProgress = GameProgress(
        unlocked = 7,
        stars = mapOf(1 to 3, 2 to 3, 3 to 2, 4 to 3, 5 to 2, 6 to 1),
        best = mapOf(1 to 1_320, 2 to 3_140, 3 to 4_460, 4 to 1_550, 5 to 8_020, 6 to 11_300),
        endlessBest = 0
    )

    val zidMap: LevelMapUi get() = levelMapUi(GameId.Zid, zidProgress, selected = null, resume = null, pill = pill)

    val zidPause = PauseUi(levelMeta = "NIVELUL 6", inventoryReady = false, sfx = true, haptics = true)
    val zidPauseReady = zidPause.copy(inventoryReady = true)

    val zidWon = ResultUi(
        kind = ResultKind.Won, stars = 2, score = 12_480, best = 12_480, newBest = true,
        lostLine = "Zidul a căzut.", stamp = "MISIUNE ÎNDEPLINITĂ",
        primary = "Nivelul 7", primaryMeta = "SAPĂ", secondary = "Harta", inventoryReady = false
    )

    val zidLost = ResultUi(
        kind = ResultKind.Lost, stars = 0, score = 6_210, best = 11_300, newBest = false,
        lostLine = "Zidul a căzut.", stamp = "MISIUNE ÎNDEPLINITĂ",
        primary = "Reia", primaryMeta = null, secondary = "Harta", inventoryReady = false
    )

    val zidEndless = ResultUi(
        kind = ResultKind.EndlessOver, stars = 0, score = 24_600, best = 24_600, newBest = true,
        lostLine = "Zidul a căzut.", stamp = "MISIUNE ÎNDEPLINITĂ",
        primary = "Reia", primaryMeta = null, secondary = "Harta", inventoryReady = false
    )

    // ───────────────────────────── ASALT ─────────────────────────────

    /** „Poarta” pe jumătate spartă: bolta deschisă, doi saci crăpați, capsula Calm în cădere. */
    val asaltMid: AsaltSave by lazy {
        val level = AsaltLevels.byId(3)
        val base = AsaltEngine.create(level, 9L)
        val kinds = CharArray(AsaltEngine.COLS * AsaltEngine.ROWS) { '0' }
        val hp = CharArray(AsaltEngine.COLS * AsaltEngine.ROWS) { '0' }
        for (r in 0 until AsaltEngine.ROWS) for (c in 0 until AsaltEngine.COLS) {
            val k = base.kind(c, r)
            kinds[r * AsaltEngine.COLS + c] = '0' + k
            hp[r * AsaltEngine.COLS + c] = '0' + base.hpAt(c, r)
        }
        fun gone(c: Int, r: Int) { kinds[r * AsaltEngine.COLS + c] = '0'; hp[r * AsaltEngine.COLS + c] = '0' }
        fun cracked(c: Int, r: Int) { hp[r * AsaltEngine.COLS + c] = '1' }
        gone(5, 1); gone(6, 1); gone(7, 1)
        gone(3, 3); gone(6, 4); gone(3, 5); gone(9, 5); gone(12, 2)
        cracked(5, 0); cracked(6, 0); cracked(0, 5); cracked(10, 5)
        base.save().copy(
            bricks = String(kinds),
            hp = String(hp),
            balls = listOf(252.0, 356.0, -0.45, -0.893),
            paddleX = 212.0,
            paddleTarget = 212.0,
            caps = listOf(195.0, 268.0, AsaltEngine.CAP_SLOW.toDouble()),
            lives = 2,
            score = 2_310,
            phase = AsaltPhase.Playing,
            elapsedSteps = 240L * 64
        )
    }

    fun asaltPlay(): AsaltEngine = AsaltEngine.restore(asaltMid)

    val asaltProgress = GameProgress(
        unlocked = 4,
        stars = mapOf(1 to 3, 2 to 2, 3 to 1),
        best = mapOf(1 to 3_900, 2 to 5_120, 3 to 4_300)
    )

    val asaltMap: LevelMapUi get() = levelMapUi(GameId.Asalt, asaltProgress, selected = null, resume = null, pill = pill)

    val asaltWon = ResultUi(
        kind = ResultKind.Won, stars = 3, score = 6_840, best = 6_840, newBest = true,
        lostLine = "Scânteia s-a stins.", stamp = "MISIUNE ÎNDEPLINITĂ",
        primary = "Nivelul 4", primaryMeta = "TURNURI", secondary = "Harta", inventoryReady = false
    )

    val asaltLost = ResultUi(
        kind = ResultKind.Lost, stars = 0, score = 2_310, best = 4_300, newBest = false,
        lostLine = "Scânteia s-a stins.", stamp = "MISIUNE ÎNDEPLINITĂ",
        primary = "Reia", primaryMeta = null, secondary = "Harta", inventoryReady = false
    )
}
