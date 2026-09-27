package com.forja.app.core.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Starea sursei de tile-uri după sondare: CARTO (implicit) → OSM (întunecat) → OFFLINE (doar cache-ul). */
enum class TileState { CARTO, OSM, OFFLINE }

/**
 * O singură sursă de adevăr pentru hărțile FORJA: CARTO dark cu tenta caldă a casei,
 * cu rezervă OSM (MAPNIK inversat și desaturat) și, în lipsa netului, tile-urile deja văzute.
 * Folosită și de MapScreen, și de detaliul activității — fără copii.
 */
object ForjaTiles {

    val CartoDark: XYTileSource = XYTileSource(
        "CartoDark", 1, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap contributors © CARTO"
    )

    /** Rezerva: OSM standard, întunecat prin filtru — zero dependențe noi. */
    val OsmFallback: OnlineTileSourceBase = TileSourceFactory.MAPNIK

    private const val PROBE_TIMEOUT_S = 4L
    // Bucureşti — un tile de test la z=14, mereu același.
    private const val PROBE_LAT = 44.4268
    private const val PROBE_LNG = 26.1025
    private const val PROBE_ZOOM = 14

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** Tenta caldă FORJA peste CARTO (echivalentul filtrului CSS din prototip). */
    fun warmFilter(): ColorMatrixColorFilter {
        val warm = ColorMatrix(
            floatArrayOf(
                1.10f, 0f, 0f, 0f, 8f,
                0f, 0.98f, 0f, 0f, 4f,
                0f, 0f, 0.86f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val sat = ColorMatrix().apply { setSaturation(0.82f) }
        warm.postConcat(sat)
        return ColorMatrixColorFilter(warm)
    }

    /** Pentru MAPNIK: inversare + desaturare + o idee mai întunecat — arată ca o hartă de noapte. */
    fun darkFilter(): ColorMatrixColorFilter {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val sat = ColorMatrix().apply { setSaturation(0.12f) }
        invert.postConcat(sat)
        val dim = ColorMatrix(
            floatArrayOf(
                0.80f, 0f, 0f, 0f, 6f,
                0f, 0.78f, 0f, 0f, 4f,
                0f, 0f, 0.72f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        invert.postConcat(dim)
        return ColorMatrixColorFilter(invert)
    }

    /** Configurarea standard a unei hărți FORJA: CARTO + tentă caldă, zoom 3..20, fără butoane de zoom, multitouch. */
    fun setup(map: MapView) {
        map.setTileSource(CartoDark)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0
        map.isTilesScaledToDpi = true
        map.setUseDataConnection(true)
        val tiles = map.overlayManager.tilesOverlay
        tiles.setColorFilter(warmFilter())
        // Cât se încarcă un tile: fundalul casei, nu gri de sistem.
        tiles.loadingBackgroundColor = 0xFF0A0A0B.toInt()
        tiles.loadingLineColor = 0xFF1A1A1E.toInt()
    }

    /** Aplică sursa + filtrul pe firul principal — fără să reseteze provider-ul dacă sursa e deja cea aleasă. */
    private fun apply(map: MapView, source: OnlineTileSourceBase, filter: ColorMatrixColorFilter) {
        if (map.tileProvider.tileSource !== source) {
            map.setTileSource(source)
            map.overlayManager.tilesOverlay.setColorFilter(filter)
        }
        map.setUseDataConnection(true)
        map.invalidate()
    }

    /** Cere un singur tile (z=14, București) prin OkHttp, cu UA FORJA. true = sursa răspunde. */
    suspend fun probe(source: OnlineTileSourceBase): Boolean = withContext(Dispatchers.IO) {
        try {
            val n = 1 shl PROBE_ZOOM
            val x = floor((PROBE_LNG + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRad = Math.toRadians(PROBE_LAT)
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            val url = source.getTileURLString(MapTileIndex.getTileIndex(PROBE_ZOOM, x, y)) ?: return@withContext false
            val ua = Configuration.getInstance().userAgentValue ?: "FORJA"
            val req = Request.Builder().url(url).header("User-Agent", ua).get().build()
            probeClient.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lanțul de rezervă: CARTO → MAPNIK întunecat → OFFLINE (păstrăm sursa curentă și tile-urile din cache,
     * fără conexiune de date). Returnează starea, ca UI-ul să o poată spune onest.
     */
    suspend fun chooseOnline(map: MapView): TileState {
        if (probe(CartoDark)) {
            withContext(Dispatchers.Main) {
                try { apply(map, CartoDark, warmFilter()) } catch (_: Exception) { }
            }
            return TileState.CARTO
        }
        if (probe(OsmFallback)) {
            withContext(Dispatchers.Main) {
                try { apply(map, OsmFallback, darkFilter()) } catch (_: Exception) { }
            }
            return TileState.OSM
        }
        withContext(Dispatchers.Main) {
            try {
                map.setUseDataConnection(false)
                map.invalidate()
            } catch (_: Exception) { }
        }
        return TileState.OFFLINE
    }
}
