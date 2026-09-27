package com.forja.app.core.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** Ce a fost atins pe hartă: `friend` (uid) · `me` · `place` (id local) · `rec` (id Firestore). */
data class MapHit(val kind: String, val id: String)

/**
 * Toată logica de hartă într-un singur loc: ține MapView + MapLibreMap + Style, creează sursele și straturile o singură
 * dată per stil, primește date (setGeoJson pe firul principal), glisează prietenii, pulsează „Tu”, mută camera.
 * Compose doar pune date aici și desenează cromul. Toate metodele sunt idempotente și sigure înainte ca stilul să fie gata
 * (datele se rețin și se aplică la încărcare, inclusiv după o reîncărcare de stil).
 *
 * `staticMode` = cardul din detaliul activității: fără gesturi în afară de pinch, 2D, fără prieteni.
 */
class MapController(context: Context, private val staticMode: Boolean = false) {
    private val appContext: Context = context.applicationContext
    private val density: Float = appContext.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    val icons = MapIcons(appContext)

    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var destroyed = false
    private var started = false
    private var resumed = false

    /** Mișcare redusă: camera sare (moveCamera), fără puls, fără glisare, fără tranziții de culoare. */
    var reducedMotion: Boolean = false
    var night: Boolean = false
        private set
    var threeD: Boolean = false
        private set
    var layers: MapLayers = MapLayers()
        private set

    val isStyleReady: Boolean get() = style?.isFullyLoaded == true
    val zoom: Double get() = map?.cameraPosition?.zoom ?: 14.5

    var onStyleReady: (() -> Unit)? = null
    var onLoadFailed: ((String) -> Unit)? = null
    var onTap: ((MapHit?) -> Unit)? = null

    /** Ultima colecție per sursă — re-aplicată la fiecare stil nou. */
    private val data = HashMap<String, FeatureCollection>()

    // ── Creare + ciclu de viață ──

    /** Construiește MapView-ul (cu contextul Activity-ului: dialogul de atribuire are nevoie de temă). O singură dată. */
    fun createView(activityContext: Context, bottomInsetDp: Int): MapView {
        view?.let { return it }
        val bg = if (night) 0xFF141517.toInt() else 0xFFF3EFE6.toInt()
        val opts = MapLibreMapOptions.createFromAttributes(activityContext)
            // TextureView: ruta MAP intră/iese cu fade în NavHost și cardul activității e rotunjit — un SurfaceView ar clipi negru
            // și nu s-ar decupa. Costul de GPU e acceptat conștient (decizia Q2 din maplibre-design.md).
            .textureMode(true)
            .foregroundLoadColor(bg)
            .camera(CameraPosition.Builder().target(LatLng(44.4268, 26.1025)).zoom(14.5).build())
            .minZoomPreference(3.0).maxZoomPreference(19.0).maxPitchPreference(60.0)
            .compassEnabled(false).logoEnabled(false)
            .attributionEnabled(true)
            .attributionGravity(Gravity.BOTTOM or Gravity.START)
            .attributionMargins(intArrayOf(dp(10), 0, 0, dp(bottomInsetDp)))
            .attributionTintColor(0xFFA7A9AE.toInt())
            .tiltGesturesEnabled(false)
            .rotateGesturesEnabled(!staticMode)
            .scrollGesturesEnabled(!staticMode)
            .doubleTapGesturesEnabled(!staticMode)
            .quickZoomGesturesEnabled(!staticMode)
            .zoomGesturesEnabled(true)
        val v = MapView(activityContext, opts)
        view = v
        v.onCreate(null)
        v.setMaximumFps(if (reducedMotion) 30 else 60)
        v.addOnDidFailLoadingMapListener { msg -> if (!destroyed) onLoadFailed?.invoke(msg ?: "") }
        v.addOnStyleImageMissingListener { id -> if (!destroyed) icons.provideMissing(id) }
        v.getMapAsync { m -> if (!destroyed) setup(m) }
        return v
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun onLifecycle(event: Lifecycle.Event) {
        val v = view ?: return
        if (destroyed) return
        try {
            when (event) {
                Lifecycle.Event.ON_START -> { if (!started) { v.onStart(); started = true } }
                Lifecycle.Event.ON_RESUME -> { if (!resumed) { v.onResume(); resumed = true } }
                Lifecycle.Event.ON_PAUSE -> { if (resumed) { v.onPause(); resumed = false } }
                Lifecycle.Event.ON_STOP -> { if (started) { v.onStop(); started = false } }
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> Unit
            }
        } catch (_: Exception) { }
    }

    /** Idempotent. După destroy, controllerul nu se mai poate refolosi (MapView-ul e distrus). */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        pulseAnim?.cancel(); pulseAnim = null
        friendAnim?.cancel(); friendAnim = null
        handler.removeCallbacksAndMessages(null)
        icons.detach()
        val v = view
        try {
            if (resumed) v?.onPause()
            if (started) v?.onStop()
            v?.onDestroy()
        } catch (_: Exception) { }
        resumed = false; started = false
        view = null; map = null; style = null
    }

    // ── Hartă + stil ──

    private fun setup(m: MapLibreMap) {
        map = m
        m.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = true
            isTiltGesturesEnabled = false           // butonul 3D/2D rămâne adevărat
            isRotateGesturesEnabled = !staticMode
            isScrollGesturesEnabled = !staticMode
            isDoubleTapGesturesEnabled = !staticMode
            isQuickZoomGesturesEnabled = !staticMode
            isZoomGesturesEnabled = true
        }
        m.addOnMapClickListener { ll ->
            if (destroyed) return@addOnMapClickListener false
            val hit = hitTest(ll)
            onTap?.invoke(hit)
            true
        }
        loadStyle(m)
    }

    private fun loadStyle(m: MapLibreMap) {
        m.setStyle(Style.Builder().fromUri(FORJA_STYLE_URI)) { s -> if (!destroyed) installStyle(s) }
    }

    /** Reîncearcă stilul (după ce revine netul). Fără efect dacă stilul e deja încărcat. */
    fun reload() {
        val m = map ?: return
        if (style?.isFullyLoaded == true) return
        loadStyle(m)
    }

    private fun installStyle(s: Style) {
        style = s
        try {
            if (reducedMotion) s.setTransition(TransitionOptions(0, 0))
            ForjaStyle.apply(s, night, layers.labelsRo)
            MapLayerStack.install(s, night, density)
            icons.attach(s)
            MapLayerStack.visibility(s, layers)
            ForjaStyle.setBuildings(s, threeD && layers.buildings3d, threeD)
            for ((src, fc) in data) s.getSourceAs<GeoJsonSource>(src)?.setGeoJson(fc)
            if (pulseOn) startPulse()
        } catch (_: Exception) { }
        onStyleReady?.invoke()
    }

    private fun push(sourceId: String, fc: FeatureCollection) {
        data[sourceId] = fc
        val s = style ?: return
        if (!s.isFullyLoaded) return
        try { s.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(fc) } catch (_: Exception) { }
    }

    // ── Aspect ──

    fun setNight(on: Boolean) {
        if (night == on) return
        night = on
        val s = style ?: return
        if (!s.isFullyLoaded) return
        try {
            ForjaStyle.apply(s, on, layers.labelsRo)
            MapLayerStack.theme(s, on)
            ForjaStyle.setBuildings(s, threeD && layers.buildings3d, threeD)
        } catch (_: Exception) { }
    }

    fun setLayers(l: MapLayers) {
        val labelsChanged = l.labelsRo != layers.labelsRo
        layers = l
        val s = style ?: return
        if (!s.isFullyLoaded) return
        try {
            MapLayerStack.visibility(s, l)
            ForjaStyle.setBuildings(s, threeD && l.buildings3d, threeD)
            if (labelsChanged) ForjaStyle.applyLabels(s, l.labelsRo)
        } catch (_: Exception) { }
    }

    /** 2D ↔ 3D: pitch 0 ↔ 55 (easeCamera 600 ms), extrudările apar/dispar, clădirile plate se estompează în 3D. */
    fun set3d(on: Boolean, animate: Boolean = true) {
        val changed = threeD != on
        threeD = on
        style?.let { s -> if (s.isFullyLoaded) try { ForjaStyle.setBuildings(s, on && layers.buildings3d, on) } catch (_: Exception) { } }
        val m = map ?: return
        if (!changed && m.cameraPosition.tilt == (if (on) 55.0 else 0.0)) return
        val cp = CameraPosition.Builder(m.cameraPosition).tilt(if (on) 55.0 else 0.0).build()
        val update = CameraUpdateFactory.newCameraPosition(cp)
        if (reducedMotion || !animate) m.moveCamera(update) else m.easeCamera(update, 600)
    }

    // ── Date ──

    private var lastCellsAt = 0L
    private var pendingCells: FeatureCollection? = null
    private val flushCells = Runnable {
        val fc = pendingCells ?: return@Runnable
        pendingCells = null
        lastCellsAt = SystemClock.uptimeMillis()
        push(MapIds.SRC_CELLS, fc)
    }

    /** Celulele: cel mult o actualizare pe secundă (ultima câștigă). */
    fun setCells(fc: FeatureCollection) {
        val now = SystemClock.uptimeMillis()
        val wait = 1000 - (now - lastCellsAt)
        if (wait <= 0 && pendingCells == null) {
            lastCellsAt = now
            push(MapIds.SRC_CELLS, fc)
        } else {
            pendingCells = fc
            handler.removeCallbacks(flushCells)
            handler.postDelayed(flushCells, wait.coerceAtLeast(1))
        }
    }

    fun setHeat(fc: FeatureCollection) = push(MapIds.SRC_HEAT, fc)
    fun setPlaces(fc: FeatureCollection) = push(MapIds.SRC_PLACES, fc)
    fun setRecommended(fc: FeatureCollection) = push(MapIds.SRC_REC, fc)
    fun setStreets(fc: FeatureCollection) = push(MapIds.SRC_STREETS, fc)
    fun setLiveRoute(fc: FeatureCollection) = push(MapIds.SRC_ROUTE, fc)

    fun setLink(from: LatLng?, to: LatLng?) {
        push(MapIds.SRC_LINK, if (from == null || to == null) MapGeo.empty() else MapGeo.link(from, to))
    }

    // ── Eu ──

    private var meIcon: String? = null
    private var meLat = 0.0
    private var meLng = 0.0
    private var hasMe = false

    /** Poziția mea; conul de direcție apare doar peste 1 m/s. */
    fun setMe(lat: Double, lng: Double, bearing: Float, speedMps: Float, icon: String) {
        meIcon = icon; meLat = lat; meLng = lng; hasMe = true
        icons.cone()
        push(MapIds.SRC_ME, MapGeo.me(lat, lng, icon, bearing, speedMps > 1f))
    }

    fun meLatLng(): LatLng? = if (hasMe) LatLng(meLat, meLng) else null

    private var pulseOn = false
    private var pulseAnim: ValueAnimator? = null

    /** Pulsul „Tu”: raza 8→22 dp, opacitate 0,35→0, 1800 ms, la nesfârșit. Oprit la mișcare redusă. */
    fun setPulse(on: Boolean) {
        val want = on && !reducedMotion
        if (pulseOn == want) return
        pulseOn = want
        if (want) startPulse() else stopPulse()
    }

    private fun startPulse() {
        pulseAnim?.cancel()
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val layer = s.getLayer(MapIds.L_ME_PULSE) ?: return
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                if (destroyed) return@addUpdateListener
                val t = a.animatedFraction
                try {
                    layer.setProperties(
                        PropertyFactory.circleRadius(8f + 14f * t),
                        PropertyFactory.circleOpacity(0.35f * (1f - t))
                    )
                } catch (_: Exception) { }
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnim?.cancel(); pulseAnim = null
        val s = style ?: return
        if (!s.isFullyLoaded) return
        try {
            s.getLayer(MapIds.L_ME_PULSE)?.setProperties(PropertyFactory.circleRadius(0f), PropertyFactory.circleOpacity(0f))
        } catch (_: Exception) { }
    }

    // ── Prieteni: glisare 400 ms spre țintă, apoi setGeoJson pe sursa sincronă ──

    private val positions = HashMap<String, DoubleArray>()
    private var friendPins: List<FriendPin> = emptyList()
    private var friendAnim: ValueAnimator? = null

    fun setFriends(pins: List<FriendPin>) {
        friendAnim?.cancel(); friendAnim = null
        friendPins = pins
        val keep = HashSet<String>(pins.size)
        for (p in pins) keep.add(p.uid)
        positions.keys.retainAll(keep)

        var moving = false
        val starts = HashMap<String, DoubleArray>(pins.size)
        for (p in pins) {
            val cur = positions[p.uid]
            if (cur == null) {
                positions[p.uid] = doubleArrayOf(p.lat, p.lng)
            } else {
                starts[p.uid] = doubleArrayOf(cur[0], cur[1])
                if (cur[0] != p.lat || cur[1] != p.lng) moving = true
            }
        }
        if (!moving || reducedMotion) {
            for (p in pins) positions[p.uid]?.let { it[0] = p.lat; it[1] = p.lng }
            pushFriends()
            return
        }
        friendAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                if (destroyed) return@addUpdateListener
                val t = a.animatedFraction.toDouble()
                for (p in friendPins) {
                    val s = starts[p.uid] ?: continue
                    val cur = positions[p.uid] ?: continue
                    cur[0] = s[0] + (p.lat - s[0]) * t
                    cur[1] = s[1] + (p.lng - s[1]) * t
                }
                pushFriends()
            }
            start()
        }
    }

    private fun pushFriends() = push(MapIds.SRC_FRIENDS, MapGeo.friends(friendPins, positions))

    /** Poziția desenată ACUM a unui prieten (pentru linia eu → prieten). */
    fun friendLatLng(uid: String): LatLng? = positions[uid]?.let { LatLng(it[0], it[1]) }

    // ── Cameră ──

    private fun tilt(): Double = if (threeD) 55.0 else 0.0

    /** Zbor (animateCamera) la o țintă, cu zoom dat; bearing 0, pitch după 2D/3D. Mișcare redusă → salt. */
    fun flyTo(lat: Double, lng: Double, zoom: Double, durationMs: Int = 900) {
        val m = map ?: return
        val cp = CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).tilt(tilt()).bearing(0.0).build()
        val u = CameraUpdateFactory.newCameraPosition(cp)
        if (reducedMotion) m.moveCamera(u) else m.animateCamera(u, durationMs)
    }

    /** Alunecare (easeCamera) la o țintă; zoom-ul rămâne dacă nu e dat. */
    fun easeTo(lat: Double, lng: Double, zoom: Double? = null, durationMs: Int = 600) {
        val m = map ?: return
        val ll = LatLng(lat, lng)
        val u = if (zoom == null) CameraUpdateFactory.newLatLng(ll) else CameraUpdateFactory.newLatLngZoom(ll, zoom)
        if (reducedMotion) m.moveCamera(u) else m.easeCamera(u, durationMs)
    }

    /** Recentrare: 600 ms, zoom 16, nordul sus. */
    fun recenter(lat: Double, lng: Double) {
        val m = map ?: return
        val cp = CameraPosition.Builder().target(LatLng(lat, lng)).zoom(16.0).tilt(tilt()).bearing(0.0).build()
        val u = CameraUpdateFactory.newCameraPosition(cp)
        if (reducedMotion) m.moveCamera(u) else m.animateCamera(u, 600)
    }

    /** Încadrează punctele cu o margine de `scale` (1,35 = 35 %), instant. Un singur punct → zoom 16. */
    fun fitBounds(points: List<Pair<Double, Double>>, paddingPx: Int, scale: Double = 1.35) {
        val m = map ?: return
        if (points.isEmpty()) return
        val distinct = points.distinct()
        if (distinct.size < 2) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(distinct[0].first, distinct[0].second), 16.0))
            return
        }
        try {
            val b = LatLngBounds.Builder()
            for ((lat, lng) in distinct) b.include(LatLng(lat, lng))
            val raw = b.build()
            val c = raw.center
            val halfLat = raw.latitudeSpan / 2 * scale
            val halfLng = raw.longitudeSpan / 2 * scale
            val grown = LatLngBounds.from(
                (c.latitude + halfLat).coerceAtMost(85.0), c.longitude + halfLng,
                (c.latitude - halfLat).coerceAtLeast(-85.0), c.longitude - halfLng
            )
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(grown, paddingPx))
        } catch (_: Exception) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(distinct[0].first, distinct[0].second), 15.0))
        }
    }

    // ── Atingeri ──

    /** Dreptunghi de 48 dp în jurul degetului, deplasat în sus (ancora iconițelor e jos): prietenii au prioritate, apoi locurile. */
    fun hitTest(ll: LatLng): MapHit? {
        val m = map ?: return null
        val p = m.projection.toScreenLocation(ll)
        val r = 24f * density
        val rect = RectF(p.x - r, p.y - r * 1.7f, p.x + r, p.y + r * 0.3f)
        try {
            for (group in hitGroups) {
                val f = m.queryRenderedFeatures(rect, *group).firstOrNull() ?: continue
                val kind = f.getStringProperty("kind") ?: continue
                return MapHit(kind, f.getStringProperty("id") ?: "")
            }
        } catch (_: Exception) { }
        return null
    }

    private val hitGroups = arrayOf(
        arrayOf(MapIds.L_FRIENDS),
        arrayOf(MapIds.L_PLACES, MapIds.L_REC),
        arrayOf(MapIds.L_ME)
    )
}

/**
 * Gazda Compose a hărții: un singur MapView, ciclul de viață legat de LifecycleOwner-ul compoziției
 * (ON_START/RESUME/PAUSE/STOP/DESTROY), distrus la ieșirea din compoziție. Controllerul se creează o dată per ecran.
 */
@Composable
fun ForjaMap(controller: MapController, modifier: Modifier = Modifier, bottomInsetDp: Int = 0) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember(controller) { controller.createView(context, bottomInsetDp) }
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event -> controller.onLifecycle(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.destroy()
        }
    }
    AndroidView(modifier = modifier, factory = { view })
}
