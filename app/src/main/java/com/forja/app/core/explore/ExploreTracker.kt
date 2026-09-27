package com.forja.app.core.explore

import android.location.Location
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ExploreCellEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Grila de explorare: celule de ~150 m în proiecția Web-Mercator.
 * id = "x_y" (întregi), stabil între sesiuni și dispozitive.
 */
object ExploreGrid {
    const val CELL_M = 150.0
    private const val R = 6378137.0

    fun cellOf(lat: Double, lng: Double): String {
        val x = floor(R * Math.toRadians(lng) / CELL_M).toLong()
        val y = floor(R * ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2)) / CELL_M).toLong()
        return "${x}_$y"
    }

    /** [minLat, minLng, maxLat, maxLng] pentru celula dată. */
    fun bounds(id: String): DoubleArray {
        val parts = id.split('_')
        val x = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val y = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val minLng = Math.toDegrees(x * CELL_M / R)
        val maxLng = Math.toDegrees((x + 1) * CELL_M / R)
        val minLat = Math.toDegrees(2 * atan(exp(y * CELL_M / R)) - PI / 2)
        val maxLat = Math.toDegrees(2 * atan(exp((y + 1) * CELL_M / R)) - PI / 2)
        return doubleArrayOf(minLat, minLng, maxLat, maxLng)
    }
}

/**
 * Explorarea: ține minte zonele prin care ai trecut și locurile unde ai STAT.
 * Primește fixuri din toate sursele de locație (prezență, fundal, GO, hartă).
 * SCHELET WP0 — implementarea completă (ședere/loc, notificare, oglinda Firestore) vine în WP2.
 */
class ExploreTracker(private val app: ForjaApp) {

    fun onLocation(loc: Location, source: String = "") {
        onFix(
            lat = loc.latitude, lng = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
            speedMps = if (loc.hasSpeed()) loc.speed else 0f,
            atMs = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            source = source
        )
    }

    fun onFix(
        lat: Double, lng: Double, accuracyM: Float, speedMps: Float,
        atMs: Long = System.currentTimeMillis(), source: String = ""
    ) {
        if (accuracyM > 60f) return
        app.appScope.launch {
            try {
                if (!app.prefs.exploreOn.first()) return@launch
                val id = ExploreGrid.cellOf(lat, lng)
                val dao = app.db.exploreDao()
                val existing = dao.cell(id)
                if (existing == null) {
                    val b = ExploreGrid.bounds(id)
                    dao.upsertCell(ExploreCellEntity(id, b[0], b[1], b[2], b[3], atMs, atMs, 1))
                } else {
                    val revisit = atMs - existing.lastAt > 30 * 60_000L
                    dao.upsertCell(existing.copy(lastAt = atMs, visits = existing.visits + if (revisit) 1 else 0))
                }
            } catch (_: Exception) { }
        }
    }
}
