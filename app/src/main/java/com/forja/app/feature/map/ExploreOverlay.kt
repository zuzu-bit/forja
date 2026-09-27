package com.forja.app.feature.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.data.db.PlaceEntity
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Zonele deblocate (celule ~150 m) desenate ca dreptunghiuri rotunjite măsliniu-translucid,
 * sub traseu și sub markeri (index 0 în overlays). Locurile au un halou discret — pinul lor e Marker.
 * Sub zoom 10 nu desenăm nimic: cifrele sunt în chip-urile de sus.
 */
class ExploreOverlay(
    private val cells: () -> List<ExploreCellEntity>,
    private val places: () -> List<PlaceEntity>
) : Overlay() {

    var density: Float = 1f
        set(value) {
            field = value
            stroke.strokeWidth = value
            halo.strokeWidth = 1.5f * value
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x554A5D3A.toInt()         // Accent @ 0x55 — măsliniu
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x886F855A.toInt()         // Accent2 @ 0x88, 1dp
        strokeWidth = 1f
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55F3B952.toInt()         // amber discret în jurul locului (raza de 100 m)
        strokeWidth = 1.5f
    }
    private val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x14F3B952.toInt()
    }

    private val a = Point()
    private val b = Point()
    private val rect = RectF()
    private val g = GeoPoint(0.0, 0.0)

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!isEnabled) return
        val zoom = projection.zoomLevel
        if (zoom < MIN_ZOOM) return
        val bb = projection.boundingBox
        val south = bb.latSouth
        val north = bb.latNorth
        val west = bb.lonWest
        val east = bb.lonEast

        val list = cells()
        if (list.isNotEmpty()) {
            val radius = 2f * density
            val inset = 0.5f * density
            for (c in list) {
                if (c.maxLat < south || c.minLat > north || c.maxLng < west || c.minLng > east) continue
                g.setCoords(c.maxLat, c.minLng)
                projection.toPixels(g, a)
                g.setCoords(c.minLat, c.maxLng)
                projection.toPixels(g, b)
                rect.set(a.x + inset, a.y + inset, b.x - inset, b.y - inset)
                if (rect.width() <= 0f || rect.height() <= 0f) continue
                canvas.drawRoundRect(rect, radius, radius, fill)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
        }

        if (zoom >= HALO_ZOOM) {
            val ps = places()
            if (ps.isNotEmpty()) {
                // raza de 100 m în pixeli, la latitudinea centrului
                val rPx = projection.metersToPixels(100f)
                if (rPx > 4f) {
                    for (p in ps) {
                        if (p.lat < south || p.lat > north || p.lng < west || p.lng > east) continue
                        g.setCoords(p.lat, p.lng)
                        projection.toPixels(g, a)
                        canvas.drawCircle(a.x.toFloat(), a.y.toFloat(), rPx, haloFill)
                        canvas.drawCircle(a.x.toFloat(), a.y.toFloat(), rPx, halo)
                    }
                }
            }
        }
    }

    companion object {
        const val MIN_ZOOM = 10.0
        const val HALO_ZOOM = 13.0
    }
}
