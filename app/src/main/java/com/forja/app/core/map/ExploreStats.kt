package com.forja.app.core.map

import com.forja.app.core.data.Friend
import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.explore.ExploreGrid
import com.forja.app.core.util.Fmt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cifrele teritoriului: „41 % din zona ta”, „Loc #2 între prieteni”, distanțe și timp pe jos.
 * Zona ta = un cerc de 5 km în jurul centrului de greutate al celulelor cucerite (fără poligoane de oraș — onest și local).
 */
object ExploreStats {
    const val ZONE_RADIUS_M = 5_000.0
    private const val EARTH_R = 6_371_000.0

    /** Câte celule de 150 m încap într-un cerc de 5 km (aria cercului / aria unei celule). */
    val cellsInZone: Int = floor(PI * ZONE_RADIUS_M * ZONE_RADIUS_M / (ExploreGrid.CELL_M * ExploreGrid.CELL_M)).toInt()

    data class Zone(val centerLat: Double, val centerLng: Double, val inZone: Int, val percent: Int)

    fun zone(cells: List<ExploreCellEntity>): Zone? {
        if (cells.isEmpty()) return null
        var sLat = 0.0
        var sLng = 0.0
        for (c in cells) {
            sLat += (c.minLat + c.maxLat) / 2
            sLng += (c.minLng + c.maxLng) / 2
        }
        val cLat = sLat / cells.size
        val cLng = sLng / cells.size
        var n = 0
        for (c in cells) {
            if (distanceM(cLat, cLng, (c.minLat + c.maxLat) / 2, (c.minLng + c.maxLng) / 2) <= ZONE_RADIUS_M) n++
        }
        val pct = (n * 100.0 / cellsInZone).roundToInt().coerceIn(0, 100)
        return Zone(cLat, cLng, n, pct)
    }

    /** Procentul din zona ta, ca număr întreg (0 când nu ai nicio celulă). */
    fun percentOfZone(cells: List<ExploreCellEntity>): Int = zone(cells)?.percent ?: 0

    /** „41 %” · „sub 1 %” când ai celule, dar prea puține pentru un procent întreg · „0 %” fără celule. */
    fun percentLabel(cells: List<ExploreCellEntity>): String {
        val z = zone(cells) ?: return "0 %"
        return if (z.percent == 0 && z.inZone > 0) "sub 1 %" else "${z.percent} %"
    }

    /** Locul meu între prieteni după celule cucerite (eu inclus): 1 + câți prieteni au mai multe. */
    fun rankAmongFriends(myCells: Int, friends: List<Friend>): Int = 1 + friends.count { it.exploreCells > myCells }

    /** Câte celule ai cucerit pe jos / alergând / pe bicicletă. */
    data class ModeCounts(val walk: Int, val run: Int, val ride: Int)

    fun modeCounts(cells: List<ExploreCellEntity>): ModeCounts {
        var w = 0; var r = 0; var b = 0
        for (c in cells) when (c.mode) { "run" -> r++; "ride" -> b++; else -> w++ }
        return ModeCounts(w, r, b)
    }

    /** Distanța în linie dreaptă (haversine), în metri. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** „350 m” sub un kilometru, altfel „1,2 km”. */
    fun distanceLabel(m: Double): String =
        if (m < 1000) "${m.roundToInt().coerceAtLeast(0)} m" else "${Fmt.km(m, 1)} km"

    /** „~15 min pe jos” la 5 km/h — estimare în linie dreaptă, nu rutare. */
    fun walkEtaLabel(m: Double): String {
        val min = (m / 5000.0 * 60.0).roundToInt().coerceAtLeast(1)
        return if (min >= 60) "~${min / 60} h ${min % 60} min pe jos" else "~$min min pe jos"
    }
}
