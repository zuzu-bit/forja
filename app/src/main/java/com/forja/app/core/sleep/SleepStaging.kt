package com.forja.app.core.sleep

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stadiile somnului din mișcare — funcții PURE, fără Android, testabile mental.
 *
 * Intrare: epoci de 1 minut cu (a) numărul de mișcări „relevante” (accelerometru, delta > 1,2 m/s²,
 * debounce 20 s — deja colectate de serviciu) și (b) numărul de micro-mișcări (delta > 0,35 m/s²).
 * Metodă (stil Cole-Kripke, simplificată):
 *  1. activitate[i] = mișcări[i] + 0,08 × micro[i]
 *  2. scor ponderat pe fereastră ±2 min: D[i] = 0,25·a[i−2] + 0,5·a[i−1] + a[i] + 0,5·a[i+1] + 0,25·a[i+2]
 *  3. treaz dacă D ≥ 1,5; ușor dacă 0 < D < 1,5; candidat profund/REM dacă D = 0
 *  4. adormirea = primul minut din care urmează ≥ 10 minute consecutive fără „treaz” (minim 3 min)
 *  5. profund = ≥ 20 min consecutive fără nicio mișcare relevantă, la > 30 min după adormire,
 *     plafonat pe ciclu (45 min în ciclurile 1–3, 15 min după)
 *  6. REM ≈ ultimele 10/15/20/25 min ale fiecărui ciclu de ~90 min (crește spre dimineață), doar
 *     în minutele fără „treaz”; micro-mișcările spre finalul ciclului confirmă (scot „profund” de
 *     acolo), nu inventează. Cronologia serverului (vorbit/sforăit/tuse) NU intră în stadii: serverul
 *     nu detectează respirația, așa că nu pretindem o rafinare care nu există
 *  7. trezirile = șiruri de ≥ 2 min „treaz” după adormire (fără trezirea finală)
 *  8. scor 0–100 explicat, linie cu linie („−8: 3 treziri”)
 * Etichetele rămân ESTIMATE. Nu e măsurătoare clinică și textele nu o promit.
 */
object SleepStaging {

    /** O epocă de 1 minut. `minute` = index de la începutul sesiunii. */
    data class Epoch(val minute: Int, val movements: Int, val micro: Int = 0)

    /** O linie din explicația scorului: delta (± puncte) și motivul, scurt. */
    @Serializable
    data class ScoreLine(val delta: Int, val reason: String)

    /** Rezultatul stadializării — se scrie în `staging.json` lângă cronologie. */
    @Serializable
    data class Result(
        val phases: String,          // „startMin,endMin,tip;…” (deep|light|rem|awake)
        val deepMin: Int,
        val lightMin: Int,
        val remMin: Int,
        val awakeMin: Int,           // după adormire, fără trezirea finală
        val latencyMin: Int,         // minute până la adormire
        val awakenings: Int,
        val score: Int,
        val lines: List<ScoreLine>,
        val measurement: String = "estimated"
    )

    const val AWAKE_THRESHOLD = 1.5
    const val MICRO_WEIGHT = 0.08
    const val ONSET_RUN_MIN = 10
    const val DEEP_RUN_MIN = 20
    const val DEEP_AFTER_ONSET_MIN = 30
    const val CYCLE_MIN = 90

    /**
     * Construiește epocile din momentele de mișcare (epoch ms) și micro-mișcările pe minut.
     * `microPerMinute`: minut → număr de micro-mișcări (poate lipsi).
     */
    fun epochs(startAt: Long, totalMin: Int, moveTimes: List<Long>, microPerMinute: Map<Int, Int> = emptyMap()): List<Epoch> {
        val moves = IntArray(totalMin.coerceAtLeast(0))
        for (t in moveTimes) {
            val m = ((t - startAt) / 60_000L).toInt()
            if (m in moves.indices) moves[m]++
        }
        return List(totalMin.coerceAtLeast(0)) { i -> Epoch(i, moves[i], microPerMinute[i] ?: 0) }
    }

    /** Scorul ponderat ±2 min pentru fiecare epocă. */
    fun weightedActivity(epochs: List<Epoch>): DoubleArray {
        val a = DoubleArray(epochs.size) { epochs[it].movements + MICRO_WEIGHT * epochs[it].micro }
        val w = doubleArrayOf(0.25, 0.5, 1.0, 0.5, 0.25)
        return DoubleArray(a.size) { i ->
            var s = 0.0
            for (k in -2..2) {
                val j = i + k
                if (j in a.indices) s += w[k + 2] * a[j]
            }
            s
        }
    }

    /** Primul minut de la care urmează ≥ [ONSET_RUN_MIN] minute fără „treaz”; -1 dacă nu există. */
    fun sleepOnset(d: DoubleArray): Int {
        if (d.isEmpty()) return -1
        var run = 0
        for (i in d.indices) {
            if (d[i] < AWAKE_THRESHOLD) {
                run++
                if (run >= minOf(ONSET_RUN_MIN, d.size)) return maxOf(i - run + 1, 0)
            } else run = 0
        }
        // noapte foarte scurtă sau agitată: acceptăm cea mai lungă serie liniștită dacă are ≥ 3 min
        var best = -1; var bestLen = 0; run = 0
        for (i in d.indices) {
            if (d[i] < AWAKE_THRESHOLD) { run++; if (run > bestLen) { bestLen = run; best = i - run + 1 } } else run = 0
        }
        return if (bestLen >= 3) best else -1
    }

    /** Lungimea ferestrei REM la finalul ciclului `cycleNo` (0-based): 10, 15, 20, 25, 25… */
    fun remWindowMin(cycleNo: Int): Int = minOf(10 + 5 * cycleNo, 25)

    /** Stadializarea completă, doar din epocile de mișcare (pașii din antet). */
    fun stage(epochs: List<Epoch>): Result {
        val n = epochs.size
        if (n == 0) return Result("", 0, 0, 0, 0, 0, 0, 0, listOf(ScoreLine(0, "fără date")))
        val d = weightedActivity(epochs)
        val onsetRaw = sleepOnset(d)
        val onset = if (onsetRaw < 0) n else maxOf(onsetRaw, minOf(3, n))
        val stages = Array(n) { "light" }

        // treaz / ușor din scorul ponderat; înainte de adormire totul e „treaz”
        for (i in 0 until n) {
            stages[i] = when {
                i < onset -> "awake"
                d[i] >= AWAKE_THRESHOLD -> "awake"
                else -> "light"
            }
        }

        // profund: serii de ≥ 20 min fără nicio mișcare relevantă, după 30 min de la adormire, plafonate pe ciclu
        val deepUsedPerCycle = HashMap<Int, Int>()
        var i = onset + DEEP_AFTER_ONSET_MIN
        while (i < n) {
            if (epochs[i].movements == 0 && stages[i] != "awake") {
                var j = i
                while (j < n && epochs[j].movements == 0 && stages[j] != "awake") j++
                if (j - i >= DEEP_RUN_MIN) {
                    for (k in i until j) {
                        val cycle = (k - onset) / CYCLE_MIN
                        val cap = if (cycle < 3) 45 else 15
                        val used = deepUsedPerCycle[cycle] ?: 0
                        if (used < cap) { stages[k] = "deep"; deepUsedPerCycle[cycle] = used + 1 }
                    }
                }
                i = j
            } else i++
        }

        // REM: fereastra de la finalul fiecărui ciclu; profund nu poate fi acolo
        for (k in onset until n) {
            val pos = (k - onset) % CYCLE_MIN
            val cycle = (k - onset) / CYCLE_MIN
            val win = remWindowMin(cycle)
            val inWindow = pos >= CYCLE_MIN - win
            val hint = epochs[k].micro > 0
            if (stages[k] == "awake") continue
            if (inWindow) stages[k] = "rem"
            else if (hint && stages[k] == "deep" && pos >= CYCLE_MIN - win - 5) stages[k] = "light" // micro-mișcări spre finalul ciclului: nu e profund
        }

        // treziri: serii de ≥ 2 min „treaz” după adormire, fără cea finală
        var awakenings = 0
        var awakeMin = 0
        var k = onset
        while (k < n) {
            if (stages[k] == "awake") {
                var j = k
                while (j < n && stages[j] == "awake") j++
                val finalWake = j == n
                if (!finalWake) {
                    awakeMin += j - k
                    if (j - k >= 2) awakenings++
                }
                k = j
            } else k++
        }

        var deep = 0; var light = 0; var rem = 0
        for (s in stages) when (s) { "deep" -> deep++; "light" -> light++; "rem" -> rem++ }

        // hipnograma în segmente
        val segs = StringBuilder()
        var segStart = 0
        for (m in 1..n) {
            if (m == n || stages[m] != stages[segStart]) {
                segs.append("$segStart,$m,${stages[segStart]};")
                segStart = m
            }
        }

        val latency = if (onsetRaw < 0) n else onset
        val (score, lines) = score(totalMin = n, latencyMin = latency, awakeMin = awakeMin, awakenings = awakenings, deepMin = deep)
        return Result(segs.toString(), deep, light, rem, awakeMin, latency, awakenings, score, lines)
    }

    /** Scorul 0–100 cu explicație. Timpul dormit = total − latență − treaz. */
    fun score(totalMin: Int, latencyMin: Int, awakeMin: Int, awakenings: Int, deepMin: Int): Pair<Int, List<ScoreLine>> {
        val lines = ArrayList<ScoreLine>()
        val slept = (totalMin - latencyMin - awakeMin).coerceAtLeast(0)
        val base = when {
            slept >= 450 -> 90
            slept >= 420 -> 84
            slept >= 390 -> 76
            slept >= 360 -> 66
            slept >= 300 -> 52
            else -> 40
        }
        lines += ScoreLine(base, "${slept / 60} h ${"%02d".format(slept % 60)} dormite")
        var total = base
        if (awakenings > 1) {
            val p = minOf(4 * (awakenings - 1), 16)
            total -= p
            lines += ScoreLine(-p, "$awakenings treziri")
        }
        if (latencyMin > 30) {
            val p = minOf((latencyMin - 30) / 5 + 1, 10)
            total -= p
            lines += ScoreLine(-p, "adormit în $latencyMin min")
        }
        if (slept > 0 && deepMin * 10 < slept) {
            total -= 6
            lines += ScoreLine(-6, "puțin somn profund")
        }
        if (totalMin > 0 && slept * 100 / totalMin < 85) {
            total -= 5
            lines += ScoreLine(-5, "eficiență sub 85 %")
        }
        return total.coerceIn(15, 98) to lines
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    fun toJson(r: Result): String = json.encodeToString(Result.serializer(), r)
    fun fromJson(s: String): Result? = try { json.decodeFromString(Result.serializer(), s) } catch (_: Exception) { null }
}
