package com.forja.app.core.map

import com.forja.app.core.data.RecommendedPlace
import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.data.db.PlaceEntity
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.pow

/** Id-urile surselor și straturilor FORJA de pe hartă. Create O SINGURĂ DATĂ per stil, apoi doar `setGeoJson`. */
object MapIds {
    const val SRC_CELLS = "forja-cells"
    const val SRC_HEAT = "forja-heat"
    const val SRC_PLACES = "forja-places"
    const val SRC_REC = "forja-rec-places"
    const val SRC_FRIENDS = "forja-friends"
    const val SRC_ME = "forja-me"
    const val SRC_ROUTE = "forja-route-live"
    const val SRC_STREETS = "forja-streets"
    const val SRC_LINK = "forja-link"

    const val L_HEAT = "forja-heat"
    const val L_CELLS = "forja-cells-fill"
    const val L_CELLS_LINE = "forja-cells-line"
    const val L_STREETS_CASING = "forja-streets-casing"
    const val L_STREETS = "forja-streets"
    const val L_ROUTE_GLOW = "forja-route-glow"
    const val L_ROUTE = "forja-route-live"
    const val L_HALOS = "forja-place-halos"
    const val L_PLACES = "forja-places"
    const val L_REC = "forja-rec-places"
    const val L_LINK = "forja-link"
    const val L_FRIENDS = "forja-friends"
    const val L_ME_PULSE = "forja-me-pulse"
    const val L_ME_CONE = "forja-me-cone"
    const val L_ME = "forja-me"

    const val IMG_CONE = "forja-cone"

    /** Ordinea de jos în sus — ca reper la citirea codului. */
    val order = listOf(
        L_HEAT, L_CELLS, L_CELLS_LINE, L_STREETS_CASING, L_STREETS, L_ROUTE_GLOW, L_ROUTE, L_HALOS,
        L_PLACES, L_REC, L_LINK, L_FRIENDS, L_ME_PULSE, L_ME_CONE, L_ME
    )
}

/** Un prieten gata de desenat: poziția-țintă, cheia iconiței, eticheta „Ana · 1,2 km”, ordinea și transparența. */
data class FriendPin(
    val uid: String,
    val lat: Double,
    val lng: Double,
    val icon: String,
    val label: String,
    val sort: Float,
    val alpha: Float
)

/**
 * Stiva de straturi FORJA peste liberty. „Vopseaua de sol” (strălucire, teritorii, străzi, trasee, halouri) intră SUB
 * stratul `building` — în 3D clădirile albe se ridică din ea, ca în Plimb; simbolurile (locuri, prieteni, eu) stau deasupra
 * tuturor etichetelor, ca în Bump.
 */
object MapLayerStack {
    private const val AMBER = "#F3B952"
    private const val OLIVE = "#4A5D3A"
    private const val OLIVE2 = "#6F855A"
    private const val GREEN = "#2FBE71"
    private const val BLUE = "#9DBFE8"
    private const val TEAL = "#4FA3A0"
    private const val FONT_BOLD = "Noto Sans Bold"

    /** 100 m în pixeli la zoom 13, latitudinea României (≈ 45°): 100 / (156543,03 · cos 45° / 2^13). Crește ×2 per nivel. */
    private val haloPxAtZ13: Float = (100.0 / (156543.03 * cos(Math.toRadians(45.0)) / 2.0.pow(13.0))).toFloat()

    private fun groundAnchor(style: Style): String? {
        if (style.getLayer("building") != null) return "building"
        return style.layers.firstOrNull { it is SymbolLayer }?.id
    }

    private fun addGround(style: Style, layer: Layer, anchor: String?) {
        if (anchor != null) style.addLayerBelow(layer, anchor) else style.addLayer(layer)
    }

    fun install(style: Style, night: Boolean, density: Float) {
        if (style.getSource(MapIds.SRC_CELLS) != null) return   // deja instalate pe acest stil
        val empty = FeatureCollection.fromFeatures(emptyList())
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_HEAT, empty))
        style.addSource(
            org.maplibre.android.style.sources.GeoJsonSource(
                MapIds.SRC_CELLS, empty,
                org.maplibre.android.style.sources.GeoJsonOptions().withBuffer(8).withTolerance(0.4f)
            )
        )
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_STREETS, empty))
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_ROUTE, empty))
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_PLACES, empty))
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_REC, empty))
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_LINK, empty))
        // Prietenii și eu: actualizări sincrone — poziția glisată nu rămâne un cadru în urmă.
        val sync = org.maplibre.android.style.sources.GeoJsonOptions().withSynchronousUpdate(true)
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_FRIENDS, empty, sync))
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(MapIds.SRC_ME, empty, sync))

        val anchor = groundAnchor(style)

        // Strălucirea (Bump „scratch map”): centrele celulelor, greutate = vizite; vizibilă sub zoom 13, se stinge spre 13,5.
        val heat = HeatmapLayer(MapIds.L_HEAT, MapIds.SRC_HEAT).withProperties(
            PropertyFactory.heatmapWeight(
                Expression.interpolate(
                    Expression.linear(), Expression.toNumber(Expression.get("visits")),
                    Expression.stop(1, 0.35f), Expression.stop(8, 1f)
                )
            ),
            PropertyFactory.heatmapRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(6, 5f), Expression.stop(10, 16f), Expression.stop(13, 32f)
                )
            ),
            PropertyFactory.heatmapIntensity(0.9f),
            PropertyFactory.heatmapOpacity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(11, 0.9f), Expression.stop(12, 0.75f), Expression.stop(13.5, 0f)
                )
            ),
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.stop(0, Expression.rgba(74, 93, 58, 0f)),
                    Expression.stop(0.25, Expression.rgba(74, 93, 58, 0.35f)),
                    Expression.stop(0.6, Expression.rgba(111, 133, 90, 0.65f)),
                    Expression.stop(1, Expression.rgba(243, 185, 82, 0.9f))
                )
            )
        )
        heat.maxZoom = 14f
        addGround(style, heat, anchor)

        // Teritoriile: celule de 150 m colorate după cum le-ai cucerit (pe jos / alergând / pe bicicletă).
        val cells = FillLayer(MapIds.L_CELLS, MapIds.SRC_CELLS).withProperties(
            PropertyFactory.fillColor(
                Expression.match(
                    Expression.get("mode"), Expression.color(android.graphics.Color.parseColor(OLIVE)),
                    Expression.stop("run", Expression.color(android.graphics.Color.parseColor(GREEN))),
                    Expression.stop("ride", Expression.color(android.graphics.Color.parseColor(TEAL)))
                )
            ),
            PropertyFactory.fillAntialias(false),
            PropertyFactory.fillOpacity(cellsOpacity(night))
        )
        cells.minZoom = 11.5f
        addGround(style, cells, anchor)
        val cellsLine = LineLayer(MapIds.L_CELLS_LINE, MapIds.SRC_CELLS).withProperties(
            PropertyFactory.lineColor(OLIVE2),
            PropertyFactory.lineWidth(1f),
            PropertyFactory.lineOpacity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(12, 0f), Expression.stop(14, 0.55f)
                )
            )
        )
        cellsLine.minZoom = 12f
        addGround(style, cellsLine, anchor)

        // „Străzile tale”: toate traseele salvate — carcasă închisă + linie amber, capete rotunde (ca în Plimb).
        addGround(
            style,
            LineLayer(MapIds.L_STREETS_CASING, MapIds.SRC_STREETS).withProperties(
                PropertyFactory.lineColor("#1A1A1E"),
                PropertyFactory.lineOpacity(if (night) 0.5f else 0.35f),
                PropertyFactory.lineWidth(streetWidth(7f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            ),
            anchor
        )
        addGround(
            style,
            LineLayer(MapIds.L_STREETS, MapIds.SRC_STREETS).withProperties(
                PropertyFactory.lineColor(AMBER),
                PropertyFactory.lineWidth(streetWidth(5f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            ),
            anchor
        )

        // Traseul LIVE (GO): glow olive + linie verde.
        addGround(
            style,
            LineLayer(MapIds.L_ROUTE_GLOW, MapIds.SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(OLIVE2),
                PropertyFactory.lineOpacity(if (night) 0.3f else 0.16f),
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            ),
            anchor
        )
        addGround(
            style,
            LineLayer(MapIds.L_ROUTE, MapIds.SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(GREEN),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            ),
            anchor
        )

        // Haloul locului: 100 m reali — raza în pixeli se dublează cu fiecare nivel de zoom.
        val halos = CircleLayer(MapIds.L_HALOS, MapIds.SRC_PLACES).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(2f), Expression.zoom(),
                    Expression.stop(13, haloPxAtZ13), Expression.stop(19, haloPxAtZ13 * 64f)
                )
            ),
            PropertyFactory.circleColor("rgba(243,185,82,0.08)"),
            PropertyFactory.circleStrokeColor("rgba(243,185,82,0.33)"),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)
        )
        halos.minZoom = 13f
        addGround(style, halos, anchor)

        // Locurile mele (pin amber „★N”) și recomandările (pin albastru) — nume sub pin, cu halo.
        style.addLayer(placesLayer(MapIds.L_PLACES, MapIds.SRC_PLACES, night))
        style.addLayer(placesLayer(MapIds.L_REC, MapIds.SRC_REC, night))

        // Linia punctată eu → prietenul selectat.
        style.addLayer(
            LineLayer(MapIds.L_LINK, MapIds.SRC_LINK).withProperties(
                PropertyFactory.lineColor(BLUE),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(arrayOf(1.5f, 2f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
            )
        )

        // Prietenii: avatar per feature, suprapunere permisă (fără clustering), selectatul deasupra, eticheta pe pastilă.
        style.addLayer(
            SymbolLayer(MapIds.L_FRIENDS, MapIds.SRC_FRIENDS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("alpha"))),
                PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_VIEWPORT_Y),
                PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("sort"))),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf(FONT_BOLD)),
                PropertyFactory.textSize(12f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.55f)),
                PropertyFactory.textOptional(true),
                PropertyFactory.textOpacity(Expression.toNumber(Expression.get("alpha"))),
                PropertyFactory.textColor(if (night) "#F4F2EE" else "#1A1A1E"),
                PropertyFactory.textHaloColor(if (night) "rgba(10,10,11,0.85)" else "#F4F2EE"),
                PropertyFactory.textHaloWidth(2f)
            )
        )

        // Eu: puls (cerc animat), con de direcție când mă mișc, avatarul „Tu”.
        style.addLayer(
            CircleLayer(MapIds.L_ME_PULSE, MapIds.SRC_ME).withProperties(
                PropertyFactory.circleColor(OLIVE2),
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)
            )
        )
        style.addLayer(
            SymbolLayer(MapIds.L_ME_CONE, MapIds.SRC_ME)
                .withFilter(Expression.eq(Expression.get("cone"), true))
                .withProperties(
                    PropertyFactory.iconImage(MapIds.IMG_CONE),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
        )
        style.addLayer(
            SymbolLayer(MapIds.L_ME, MapIds.SRC_ME).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(3f),
                PropertyFactory.textField("Tu"),
                PropertyFactory.textFont(arrayOf(FONT_BOLD)),
                PropertyFactory.textSize(12f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.55f)),
                PropertyFactory.textOptional(true),
                PropertyFactory.textColor(if (night) "#F4F2EE" else "#1A1A1E"),
                PropertyFactory.textHaloColor(if (night) "rgba(10,10,11,0.85)" else "#F4F2EE"),
                PropertyFactory.textHaloWidth(2f)
            )
        )
    }

    private fun placesLayer(id: String, source: String, night: Boolean): SymbolLayer =
        SymbolLayer(id, source).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("sort"))),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf(FONT_BOLD)),
            PropertyFactory.textSize(11f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.4f)),
            PropertyFactory.textOptional(true),
            PropertyFactory.textMaxWidth(9f),
            PropertyFactory.textColor(if (night) "#F4F2EE" else "#2b2a26"),
            PropertyFactory.textHaloColor(if (night) "rgba(10,10,11,0.85)" else "rgba(243,239,230,0.92)"),
            PropertyFactory.textHaloWidth(1.6f)
        )

    private fun cellsOpacity(night: Boolean): Expression =
        Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(12, 0f), Expression.stop(14, if (night) 0.38f else 0.28f)
        )

    private fun streetWidth(base: Float): Expression =
        Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(12, base * 0.45f), Expression.stop(16, base), Expression.stop(20, base * 1.8f)
        )

    /** Comutarea zi/noapte pentru straturile FORJA (culorile textelor, opacitățile) — fără re-adăugare. */
    fun theme(style: Style, night: Boolean) {
        val text = if (night) "#F4F2EE" else "#1A1A1E"
        val halo = if (night) "rgba(10,10,11,0.85)" else "#F4F2EE"
        style.getLayer(MapIds.L_CELLS)?.setProperties(PropertyFactory.fillOpacity(cellsOpacity(night)))
        style.getLayer(MapIds.L_STREETS_CASING)?.setProperties(PropertyFactory.lineOpacity(if (night) 0.5f else 0.35f))
        style.getLayer(MapIds.L_ROUTE_GLOW)?.setProperties(PropertyFactory.lineOpacity(if (night) 0.3f else 0.16f))
        for (id in listOf(MapIds.L_FRIENDS, MapIds.L_ME)) {
            style.getLayer(id)?.setProperties(PropertyFactory.textColor(text), PropertyFactory.textHaloColor(halo))
        }
        for (id in listOf(MapIds.L_PLACES, MapIds.L_REC)) {
            style.getLayer(id)?.setProperties(
                PropertyFactory.textColor(if (night) "#F4F2EE" else "#2b2a26"),
                PropertyFactory.textHaloColor(if (night) "rgba(10,10,11,0.85)" else "rgba(243,239,230,0.92)")
            )
        }
    }

    /** Vizibilitatea după foaia „Straturi” — doar `visibility`, straturile rămân la locul lor. */
    fun visibility(style: Style, layers: MapLayers) {
        fun set(id: String, on: Boolean) {
            style.getLayer(id)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        }
        set(MapIds.L_HEAT, layers.heat)
        set(MapIds.L_CELLS, layers.territories)
        set(MapIds.L_CELLS_LINE, layers.territories)
        set(MapIds.L_STREETS_CASING, layers.streets)
        set(MapIds.L_STREETS, layers.streets)
        set(MapIds.L_HALOS, layers.places)
        set(MapIds.L_PLACES, layers.places)
        set(MapIds.L_REC, layers.places)
        set(MapIds.L_LINK, layers.friends)
        set(MapIds.L_FRIENDS, layers.friends)
    }
}

/** Construiește colecțiile GeoJSON din datele FORJA. Funcții pure — se pot rula pe Dispatchers.Default. */
object MapGeo {
    private val empty: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    fun empty(): FeatureCollection = empty

    fun cells(cells: List<ExploreCellEntity>): FeatureCollection {
        if (cells.isEmpty()) return empty
        val out = ArrayList<Feature>(cells.size)
        for (c in cells) {
            val ring = listOf(
                Point.fromLngLat(c.minLng, c.minLat), Point.fromLngLat(c.maxLng, c.minLat),
                Point.fromLngLat(c.maxLng, c.maxLat), Point.fromLngLat(c.minLng, c.maxLat),
                Point.fromLngLat(c.minLng, c.minLat)
            )
            val f = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
            f.addStringProperty("id", c.id)
            f.addStringProperty("mode", c.mode)
            f.addNumberProperty("visits", c.visits)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun heat(cells: List<ExploreCellEntity>): FeatureCollection {
        if (cells.isEmpty()) return empty
        val out = ArrayList<Feature>(cells.size)
        for (c in cells) {
            val f = Feature.fromGeometry(Point.fromLngLat((c.minLng + c.maxLng) / 2, (c.minLat + c.maxLat) / 2))
            f.addNumberProperty("visits", c.visits)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun places(places: List<PlaceEntity>, selectedId: Long?, iconOf: (PlaceEntity) -> String): FeatureCollection {
        if (places.isEmpty()) return empty
        val out = ArrayList<Feature>(places.size)
        for (p in places) {
            val f = Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat))
            f.addStringProperty("kind", "place")
            f.addStringProperty("id", p.id.toString())
            f.addStringProperty("icon", iconOf(p))
            f.addStringProperty("label", p.name)
            f.addNumberProperty("sort", if (p.id == selectedId) 2 else 1)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun recommended(recs: List<RecommendedPlace>, selectedId: String?, iconOf: (RecommendedPlace) -> String): FeatureCollection {
        if (recs.isEmpty()) return empty
        val out = ArrayList<Feature>(recs.size)
        for (r in recs) {
            val f = Feature.fromGeometry(Point.fromLngLat(r.lng, r.lat))
            f.addStringProperty("kind", "rec")
            f.addStringProperty("id", r.id)
            f.addStringProperty("icon", iconOf(r))
            f.addStringProperty("label", r.name)
            f.addNumberProperty("sort", if (r.id == selectedId) 2 else 0)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    /** Prietenii, cu pozițiile CURENTE (glisate), nu cu țintele. */
    fun friends(pins: List<FriendPin>, positions: Map<String, DoubleArray>): FeatureCollection {
        if (pins.isEmpty()) return empty
        val out = ArrayList<Feature>(pins.size)
        for (p in pins) {
            val cur = positions[p.uid]
            val lat = cur?.get(0) ?: p.lat
            val lng = cur?.get(1) ?: p.lng
            val f = Feature.fromGeometry(Point.fromLngLat(lng, lat))
            f.addStringProperty("kind", "friend")
            f.addStringProperty("id", p.uid)
            f.addStringProperty("icon", p.icon)
            f.addStringProperty("label", p.label)
            f.addNumberProperty("sort", p.sort)
            f.addNumberProperty("alpha", p.alpha)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun me(lat: Double, lng: Double, icon: String, bearing: Float, cone: Boolean): FeatureCollection {
        val f = Feature.fromGeometry(Point.fromLngLat(lng, lat))
        f.addStringProperty("kind", "me")
        f.addStringProperty("id", "me")
        f.addStringProperty("icon", icon)
        f.addNumberProperty("bearing", bearing)
        f.addBooleanProperty("cone", cone)
        return FeatureCollection.fromFeature(f)
    }

    /** Traseul live (GO) din punctele serviciului (lat, lng). */
    fun route(points: List<Pair<Double, Double>>, kind: String = "live"): FeatureCollection {
        if (points.size < 2) return empty
        val pts = ArrayList<Point>(points.size)
        for ((lat, lng) in points) pts.add(Point.fromLngLat(lng, lat))
        val f = Feature.fromGeometry(LineString.fromLngLats(pts))
        f.addStringProperty("kind", kind)
        return FeatureCollection.fromFeature(f)
    }

    /** „lat,lng;lat,lng;…” → puncte; liniile invalide se ignoră. */
    fun parsePolyline(polyline: String): List<Pair<Double, Double>> {
        if (polyline.isBlank()) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (pair in polyline.split(';')) {
            val i = pair.indexOf(',')
            if (i <= 0) continue
            val lat = pair.substring(0, i).toDoubleOrNull() ?: continue
            val lng = pair.substring(i + 1).toDoubleOrNull() ?: continue
            out.add(lat to lng)
        }
        return out
    }

    /** „Străzile tale”: toate activitățile salvate, câte o linie fiecare. */
    fun streets(activities: List<ActivityEntity>): FeatureCollection {
        if (activities.isEmpty()) return empty
        val out = ArrayList<Feature>(activities.size)
        for (a in activities) {
            val pts = parsePolyline(a.polyline)
            if (pts.size < 2) continue
            val line = ArrayList<Point>(pts.size)
            for ((lat, lng) in pts) line.add(Point.fromLngLat(lng, lat))
            val f = Feature.fromGeometry(LineString.fromLngLats(line))
            f.addStringProperty("kind", "mine")
            f.addNumberProperty("activityId", a.id)
            f.addStringProperty("type", a.type)
            out.add(f)
        }
        return FeatureCollection.fromFeatures(out)
    }

    fun link(from: LatLng, to: LatLng): FeatureCollection {
        val f = Feature.fromGeometry(
            LineString.fromLngLats(listOf(Point.fromLngLat(from.longitude, from.latitude), Point.fromLngLat(to.longitude, to.latitude)))
        )
        return FeatureCollection.fromFeature(f)
    }
}
