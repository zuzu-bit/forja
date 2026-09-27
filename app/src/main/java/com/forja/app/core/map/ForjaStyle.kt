package com.forja.app.core.map

import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue

/** Stilul de bază: OpenFreeMap „liberty” (același ca pe site), fără cheie, recolorat la runtime. */
const val FORJA_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Paleta FORJA aplicată peste id-urile de straturi din liberty. Aceleași id-uri zi și noapte —
 * comutarea schimbă doar culorile, niciodată nu reîncarcă stilul.
 */
class ForjaPalette(
    val bg: String,
    val park: String, val parkEdge: String,
    val wood: String, val grass: String,
    val residential: String, val landuse: String,
    val water: String, val waterLine: String,
    val motorway: String, val primary: String, val secondary: String, val minor: String,
    val casingMajor: String, val casingMinor: String, val rail: String,
    val building: String, val buildingEdge: String,
    val building3d: String, val building3dOpacity: Float,
    val boundary: String, val boundaryMinor: String,
    val placeText: String, val roadText: String, val poiText: String, val waterText: String,
    val halo: String,
    val naturalEarthOpacity: Float
)

object ForjaStyle {
    private const val TAG = "ForjaStyle"

    /** „FORJA zi” — caldă, ~15 % desaturată, ca Plimb: hârtie caldă, clădiri aproape albe, apă albastru pal. */
    val Day = ForjaPalette(
        bg = "#f3efe6",
        park = "#d9e3c4", parkEdge = "rgba(140,170,120,0.5)",
        wood = "hsla(88,32%,70%,0.6)", grass = "rgba(190,208,168,1)",
        residential = "hsla(35,30%,90%,0.5)", landuse = "#e6e4d6",
        water = "#b9cde4", waterLine = "#a9bfd9",
        motorway = "#f0c58f", primary = "#f6e5b8", secondary = "#f9edcb", minor = "#ffffff",
        casingMajor = "#d9ab7c", casingMinor = "#d8d3ca", rail = "#c4c0b8",
        building = "#e6e1d6", buildingEdge = "#d5cfc2",
        building3d = "#ece7dc", building3dOpacity = 0.92f,
        boundary = "#8a8a90", boundaryMinor = "#c2c0bb",
        placeText = "#2b2a26", roadText = "#6b675f", poiText = "#7a756c", waterText = "#5a6f8f",
        halo = "rgba(243,239,230,0.9)",
        naturalEarthOpacity = 0.35f
    )

    /** „FORJA noapte” — aliniată la Surface0/1/2, mai sobră decât movul din Bump. */
    val Night = ForjaPalette(
        bg = "#141517",
        park = "#1c231b", parkEdge = "rgba(111,133,90,0.35)",
        wood = "hsla(110,12%,18%,0.6)", grass = "rgba(40,50,38,0.5)",
        residential = "hsla(0,0%,12%,0.6)", landuse = "#1a1c1f",
        water = "#0f1a26", waterLine = "#16243a",
        motorway = "#4a3f2a", primary = "#3a3731", secondary = "#33312c", minor = "#2b2c30",
        casingMajor = "#0f1012", casingMinor = "#0f1012", rail = "#3a3b40",
        building = "#1c1d21", buildingEdge = "#26272c",
        building3d = "#22232a", building3dOpacity = 0.85f,
        boundary = "#4a4c55", boundaryMinor = "#3a3c44",
        placeText = "#c9c6bd", roadText = "#9a978f", poiText = "#87847c", waterText = "#7e93ac",
        halo = "rgba(10,10,11,0.85)",
        naturalEarthOpacity = 0f
    )

    private val placeLabels = listOf(
        "label_city", "label_city_capital", "label_town", "label_village", "label_state",
        "label_country_1", "label_country_2", "label_country_3"
    )
    private val roadLabels = listOf("highway-name-major", "highway-name-minor", "highway-name-path", "airport")
    private val waterLabels = listOf("water_name_point_label", "water_name_line_label", "waterway_line_label")
    private val poiLabels = listOf("poi_r1", "poi_transit")

    /** Straturile cu text pe care le trecem pe „name” (română) în loc de „name:latin/name_en”. */
    private val textLayers = placeLabels + listOf("label_other") + roadLabels + waterLabels + poiLabels

    /** Aplică o proprietate pe un strat, dacă există în stilul servit (liberty poate evolua). */
    private fun Style.paint(id: String, vararg p: PropertyValue<*>) {
        val layer = getLayer(id)
        if (layer == null) {
            if (id !in warned) { warned.add(id); Log.w(TAG, "liberty: lipsește stratul $id") }
            return
        }
        try { layer.setProperties(*p) } catch (e: Exception) { Log.w(TAG, "liberty: $id — ${e.message}") }
    }

    private val warned = HashSet<String>()

    /** Recolorarea completă a liberty-ului: fundal, parcuri, apă, drumuri, clădiri, granițe, etichete. */
    fun apply(style: Style, night: Boolean, labelsRo: Boolean) {
        val l = if (night) Night else Day
        style.paint("background", PropertyFactory.backgroundColor(l.bg))
        style.paint("natural_earth", PropertyFactory.rasterOpacity(l.naturalEarthOpacity))
        style.paint("park", PropertyFactory.fillColor(l.park), PropertyFactory.fillOpacity(0.6f), PropertyFactory.fillOutlineColor(l.parkEdge))
        style.paint("park_outline", PropertyFactory.lineColor(l.parkEdge))
        style.paint("landcover_wood", PropertyFactory.fillColor(l.wood))
        style.paint("landcover_grass", PropertyFactory.fillColor(l.grass))
        style.paint("landuse_residential", PropertyFactory.fillColor(l.residential))
        for (id in listOf(
            "landuse_pitch", "landuse_track", "landuse_cemetery", "landuse_hospital", "landuse_school", "aeroway_fill",
            "landcover_sand", "landcover_ice"
        )) {
            style.paint(id, PropertyFactory.fillColor(l.landuse))
        }
        style.paint("water", PropertyFactory.fillColor(l.water))
        for (id in listOf("waterway_river", "waterway_other", "waterway_tunnel")) style.paint(id, PropertyFactory.lineColor(l.waterLine))

        // Drumuri, poduri ȘI tuneluri (Pasajul Unirii, Victoriei, Lujerului…), plus pistele aeroportului: liberty le lasă
        // crem/alb, iar pe harta de noapte ar străluci — aceleași jetoane de culoare pentru toate variantele.
        for (id in listOf("road_motorway", "bridge_motorway", "tunnel_motorway")) style.paint(id, PropertyFactory.lineColor(l.motorway))
        for (id in listOf(
            "road_trunk_primary", "bridge_trunk_primary", "tunnel_trunk_primary", "road_link", "bridge_link", "tunnel_link",
            "road_motorway_link", "bridge_motorway_link", "tunnel_motorway_link"
        )) {
            style.paint(id, PropertyFactory.lineColor(l.primary))
        }
        for (id in listOf("road_secondary_tertiary", "bridge_secondary_tertiary", "tunnel_secondary_tertiary")) {
            style.paint(id, PropertyFactory.lineColor(l.secondary))
        }
        for (id in listOf(
            "road_minor", "bridge_street", "tunnel_minor", "road_service_track", "bridge_service_track", "tunnel_service_track",
            "road_path_pedestrian", "bridge_path_pedestrian", "tunnel_path_pedestrian", "aeroway_runway", "aeroway_taxiway"
        )) {
            style.paint(id, PropertyFactory.lineColor(l.minor))
        }
        for (id in listOf(
            "road_motorway_casing", "road_trunk_primary_casing", "road_secondary_tertiary_casing", "road_link_casing",
            "road_motorway_link_casing", "bridge_motorway_casing", "bridge_trunk_primary_casing",
            "bridge_secondary_tertiary_casing", "bridge_link_casing", "bridge_motorway_link_casing",
            "tunnel_motorway_casing", "tunnel_trunk_primary_casing", "tunnel_secondary_tertiary_casing", "tunnel_link_casing",
            "tunnel_motorway_link_casing"
        )) style.paint(id, PropertyFactory.lineColor(l.casingMajor))
        for (id in listOf(
            "road_minor_casing", "road_service_track_casing", "bridge_street_casing", "bridge_service_track_casing",
            "bridge_path_pedestrian_casing", "tunnel_street_casing", "tunnel_service_track_casing"
        )) {
            style.paint(id, PropertyFactory.lineColor(l.casingMinor))
        }
        for (id in listOf(
            "road_major_rail", "road_transit_rail", "bridge_major_rail", "bridge_transit_rail", "tunnel_major_rail", "tunnel_transit_rail",
            "road_major_rail_hatching", "road_transit_rail_hatching", "bridge_major_rail_hatching", "bridge_transit_rail_hatching",
            "tunnel_major_rail_hatching", "tunnel_transit_rail_hatching"
        )) {
            style.paint(id, PropertyFactory.lineColor(l.rail))
        }

        style.paint("building", PropertyFactory.fillColor(l.building), PropertyFactory.fillOutlineColor(l.buildingEdge))
        // Liberty oprește clădirile plate la zoom 14 (de acolo preia `building-3d`); în 2D le vrem la orice zoom —
        // altfel, de la 14 în sus, orașul e doar străzi pe hârtie. Cu extrudările pornite stau estompate sub ele.
        try { style.getLayer("building")?.setMaxZoom(24f) } catch (e: Exception) { Log.w(TAG, "liberty: building maxzoom — ${e.message}") }
        style.paint(
            "building-3d",
            PropertyFactory.fillExtrusionColor(l.building3d),
            PropertyFactory.fillExtrusionOpacity(l.building3dOpacity),
            PropertyFactory.fillExtrusionVerticalGradient(true)
        )
        // Fără conturul „hide_3d” și fără clădiri de înălțime 0 — altfel piesele se bat cu conturul (z-fighting).
        (style.getLayer("building-3d") as? FillExtrusionLayer)?.setFilter(
            Expression.all(
                Expression.neq(Expression.get("hide_3d"), true),
                Expression.gt(Expression.toNumber(Expression.get("render_height")), 0)
            )
        )

        style.paint("boundary_2", PropertyFactory.lineColor(l.boundary))
        style.paint("boundary_3", PropertyFactory.lineColor(l.boundaryMinor))

        for (id in placeLabels) style.paint(id, PropertyFactory.textColor(l.placeText), PropertyFactory.textHaloColor(l.halo), PropertyFactory.textHaloWidth(1.2f))
        style.paint("label_other", PropertyFactory.textColor(l.roadText), PropertyFactory.textHaloColor(l.halo))
        for (id in roadLabels) style.paint(id, PropertyFactory.textColor(l.roadText), PropertyFactory.textHaloColor(l.halo), PropertyFactory.textHaloWidth(1.2f))
        for (id in waterLabels) style.paint(id, PropertyFactory.textColor(l.waterText), PropertyFactory.textHaloColor(l.halo))
        for (id in poiLabels) style.paint(id, PropertyFactory.textColor(l.poiText), PropertyFactory.textHaloColor(l.halo))
        // Mai puțină aglomerare, ca în Bump/Plimb: rămân doar POI-urile de rang 1..6.
        style.paint("poi_r20", PropertyFactory.visibility(Property.NONE))
        style.paint("poi_r7", PropertyFactory.visibility(Property.NONE))

        applyLabels(style, labelsRo)
    }

    /** Etichete în română: „București”, nu „Bucharest”. Oprit → ordinea liberty (latin / englez / local). */
    fun applyLabels(style: Style, ro: Boolean) {
        val field = if (ro) {
            Expression.coalesce(Expression.get("name"), Expression.get("name:latin"), Expression.get("name_en"))
        } else {
            Expression.coalesce(Expression.get("name:latin"), Expression.get("name_en"), Expression.get("name"))
        }
        for (id in textLayers) style.paint(id, PropertyFactory.textField(field))
    }

    /**
     * Clădirile: în 3D arătăm extrudările (dacă stratul e pornit) și estompăm clădirile plate;
     * în 2D (sau în 3D cu „Clădiri 3D” oprit) extrudările sunt ascunse și clădirile plate revin la opacitate întreagă,
     * la orice zoom. Opacitatea extrudărilor revine la valoarea paletei — după [fadeOutExtrusions] reapar cu o estompare de 300 ms.
     */
    fun setBuildings(style: Style, extrusions: Boolean, threeD: Boolean, night: Boolean) {
        val l = if (night) Night else Day
        style.paint(
            "building-3d",
            PropertyFactory.visibility(if (extrusions) Property.VISIBLE else Property.NONE),
            PropertyFactory.fillExtrusionOpacity(if (extrusions) l.building3dOpacity else 0f)
        )
        style.paint("building", PropertyFactory.fillOpacity(if (threeD && extrusions) 0.35f else 1f))
        try { style.getLayer("building")?.setMaxZoom(24f) } catch (_: Exception) { }
    }

    /** La ieșirea din 3D: extrudările se estompează (tranziția de vopsea, 300 ms) cât camera se aplatizează; le ascunde [setBuildings] la final. */
    fun fadeOutExtrusions(style: Style) {
        style.paint("building-3d", PropertyFactory.fillExtrusionOpacity(0f))
    }
}
