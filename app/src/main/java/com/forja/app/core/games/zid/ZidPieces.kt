package com.forja.app.core.games.zid

/**
 * Piesele ZID: cele șapte tetromino-uri (termenul matematic generic), notate I O T S Z J L, codurile 1–7 pe tablă;
 * 8 = moloz. Fiecare piesă are rotația 0 într-o cutie de 4 (I), 2 (O) sau 3 (restul) celule; rotația în sensul acelor
 * de ceasornic e (x, y) → (n−1−y, x), cu y în jos. Împingerile (kicks) sunt simple, nu tabelele „oficiale”.
 */
object ZidPieces {
    const val I = 1
    const val O = 2
    const val T = 3
    const val S = 4
    const val Z = 5
    const val J = 6
    const val L = 7
    const val RUBBLE = 8

    const val KINDS = 7

    /** Latura cutiei fiecărei piese (indexat pe cod). */
    val box = intArrayOf(0, 4, 2, 3, 3, 3, 3, 3)

    private val base: Array<IntArray> = arrayOf(
        IntArray(0),
        intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),     // I
        intArrayOf(0, 0, 1, 0, 0, 1, 1, 1),     // O
        intArrayOf(1, 0, 0, 1, 1, 1, 2, 1),     // T (vârful în sus)
        intArrayOf(1, 0, 2, 0, 0, 1, 1, 1),     // S
        intArrayOf(0, 0, 1, 0, 1, 1, 2, 1),     // Z
        intArrayOf(0, 0, 0, 1, 1, 1, 2, 1),     // J
        intArrayOf(2, 0, 0, 1, 1, 1, 2, 1)      // L
    )

    /** `cells[kind][rot]` = x0, y0, x1, y1, x2, y2, x3, y3 în cutie. Precalculat, fără alocări în joc. */
    val cells: Array<Array<IntArray>> = Array(8) { k ->
        if (k == 0) Array(4) { IntArray(8) } else Array(4) { r -> rotated(base[k], box[k], r) }
    }

    /** Împingerile încercate la rotire, în ordine: (0,0) (−1,0) (+1,0) (0,−1)↑ (−1,−1) (+1,−1) (−2,0) (+2,0). */
    val KICKS = intArrayOf(0, 0, -1, 0, 1, 0, 0, -1, -1, -1, 1, -1, -2, 0, 2, 0)

    private fun rotated(src: IntArray, n: Int, times: Int): IntArray {
        var cur = src.copyOf()
        repeat(times) {
            val nxt = IntArray(8)
            for (i in 0 until 4) {
                val x = cur[2 * i]
                val y = cur[2 * i + 1]
                nxt[2 * i] = n - 1 - y
                nxt[2 * i + 1] = x
            }
            cur = nxt
        }
        return cur
    }

    /** Coloana de apariție a cutiei (O la 4, restul la 3). */
    fun spawnX(kind: Int): Int = if (kind == O) 4 else 3

    /** Marginile celulelor unei rotații (minX, maxX, minY, maxY) în cutie. */
    fun bounds(kind: Int, rot: Int, out: IntArray) {
        val c = cells[kind][rot and 3]
        var minX = 9; var maxX = -1; var minY = 9; var maxY = -1
        for (i in 0 until 4) {
            val x = c[2 * i]; val y = c[2 * i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        out[0] = minX; out[1] = maxX; out[2] = minY; out[3] = maxY
    }
}
