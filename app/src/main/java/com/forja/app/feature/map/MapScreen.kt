package com.forja.app.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forja.app.ForjaApp
import com.forja.app.core.data.FamilyLoc
import com.forja.app.core.data.Friend
import com.forja.app.core.data.RecommendedPlace
import com.forja.app.core.data.db.PlaceEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.location.BgLocation
import com.forja.app.core.location.GoTrackService
import com.forja.app.core.map.ForjaTiles
import com.forja.app.core.map.TileState
import com.forja.app.core.util.Fmt
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

private val Bucharest = GeoPoint(44.4268, 26.1025)

/** Ce loc e selectat pe hartă: al meu (editabil) sau recomandat de un prieten. */
private sealed class PlaceSel {
    data class Mine(val p: PlaceEntity) : PlaceSel()
    data class Rec(val r: RecommendedPlace) : PlaceSel()
}

/**
 * Harta VIU — prieteni reali, live, cu interpolare; mod fantomă (familia te vede și atunci);
 * GO recording; explorare (zone deblocate + locuri unde ai stat); recomandări de la prieteni; 2D/3D.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(onOpenActivities: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val reducedMotion = LocalReducedMotion.current

    // Acceptă și locația aproximativă — mai bine ceva decât nimic; cerem precisă când lipsește.
    var hasLocation by remember {
        mutableStateOf(BgLocation.hasFine(context) || BgLocation.hasCoarse(context))
    }
    var hasBackground by remember { mutableStateOf(BgLocation.hasBackground(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasLocation = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val bgPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBackground = granted
        if (granted) {
            scope.launch {
                app.prefs.setBgShareOn(true)
                BgLocation.registerIfReady(context)
                toast.show("Gata — prietenii te văd și când FORJA e închisă. Fantoma rămâne excepția.")
            }
        }
    }
    val bgBannerDismissed by app.prefs.bgBannerDismissed.collectAsState(initial = true)
    // Nu mai cerem locația automat la intrare — o cere doar butonul GO / recentrare.

    // ── Prieteni + familie ──
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        app.friends.friendsFlow(uid).collect { friends = it }
    }
    var familyLocs by remember { mutableStateOf<Map<String, FamilyLoc>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        try { app.friends.familyLocFlow(uid).collect { familyLocs = it } } catch (_: Exception) { }
    }
    val familyUids by app.prefs.familyUids.collectAsState(initial = emptySet())
    // Prietenii fantomă care m-au pus în familie: poziția lor vine din familyLoc.
    val shownFriends = remember(friends, familyLocs) {
        friends.map { f ->
            if (!f.ghost) f else {
                val fl = familyLocs[f.uid]
                if (fl == null) f else f.copy(
                    lat = fl.lat, lng = fl.lng, speedMps = fl.speedMps,
                    state = fl.state, locUpdatedAt = fl.locUpdatedAt, viaFamily = true
                )
            }
        }
    }

    // ── Explorare: zone, locuri, recomandări ──
    val cellsFlow = remember { app.db.exploreDao().allCells() }
    val placesFlow = remember { app.db.exploreDao().places() }
    val cells by cellsFlow.collectAsState(initial = emptyList())
    val places by placesFlow.collectAsState(initial = emptyList())
    var recommended by remember { mutableStateOf<List<RecommendedPlace>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        try { app.friends.recommendedPlacesFlow(uid).collect { recommended = it } } catch (_: Exception) { }
    }
    val showExplore by app.prefs.showExplore.collectAsState(initial = true)
    val map3d by app.prefs.map3d.collectAsState(initial = false)
    val thresholdMin by app.prefs.placeThresholdMin.collectAsState(initial = 300)
    var tileState by remember { mutableStateOf(TileState.CARTO) }
    var placesOpen by remember { mutableStateOf(false) }
    var selectedPlace by remember { mutableStateOf<PlaceSel?>(null) }
    val cellsState = rememberUpdatedState(cells)
    val placesState = rememberUpdatedState(places)

    var ghostUntil by remember { mutableStateOf(0L) }
    val ghostActive = ghostUntil == -1L || ghostUntil > System.currentTimeMillis()
    var ghostOpen by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }
    var sportOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Friend?>(null) }

    val go by GoTrackService.state.collectAsState()

    // Referințe osmdroid ținute între recompoziții
    val mapRef = remember { mutableStateOf<MapView?>(null) }
    val mapDetached = remember { mutableStateOf(false) }
    val exploreOverlay = remember { mutableStateOf<ExploreOverlay?>(null) }
    val friendMarkers = remember { mutableMapOf<String, Marker>() }
    val friendAnimTargets = remember { mutableMapOf<String, GeoPoint>() }
    val placeMarkers = remember { mutableMapOf<Long, Marker>() }
    val recMarkers = remember { mutableMapOf<String, Marker>() }
    val myMarker = remember { mutableStateOf<Marker?>(null) }
    val goLine = remember { mutableStateOf<Polyline?>(null) }
    val goGlow = remember { mutableStateOf<Polyline?>(null) }
    var hadFirstFix by remember { mutableStateOf(false) }

    fun detachMap(m: MapView) {
        if (!mapDetached.value) {
            mapDetached.value = true
            try { m.onDetach() } catch (_: Exception) { }
        }
    }

    fun navigateTo(lat: Double, lng: Double, label: String) {
        try {
            val q = Uri.encode(label.ifBlank { "Loc FORJA" })
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($q)")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast.show("Nu am găsit o aplicație de navigație pe telefon.")
        }
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        // Harta — în 3D înclinăm doar containerul ei (graphicsLayer), restul UI-ului rămâne drept.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RectangleShape)
                    .graphicsLayer {
                        rotationX = if (map3d) 38f else 0f
                        cameraDistance = 16f * density
                    },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    // În 3D harta e mai mare decât ecranul, ca planul înclinat să umple tot cadrul.
                    modifier = if (map3d) Modifier.requiredSize(w * 1.2f, h * 1.45f) else Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            ForjaTiles.setup(this)
                            controller.setZoom(14.5)
                            controller.setCenter(Bucharest)
                            val ov = ExploreOverlay({ cellsState.value }, { placesState.value }).also {
                                it.density = ctx.resources.displayMetrics.density
                            }
                            overlays.add(0, ov)
                            exploreOverlay.value = ov
                            mapRef.value = this
                        }
                    },
                    update = { map ->
                        exploreOverlay.value?.isEnabled = showExplore

                        // Prieteni: markeri cu interpolare (fără teleport); fantomele din familie apar cu ♥.
                        val valid = shownFriends.filter { it.lat != null && it.lng != null && (!it.ghost || it.viaFamily) }
                        val validIds = valid.map { it.uid }.toSet()
                        friendMarkers.keys.filter { it !in validIds }.forEach { uid ->
                            friendMarkers.remove(uid)?.let { map.overlays.remove(it) }
                            friendAnimTargets.remove(uid)
                        }
                        valid.forEach { f ->
                            val target = GeoPoint(f.lat!!, f.lng!!)
                            val icon = MapMarkers.friendMarker(
                                context, f.name, ghost = f.viaFamily, state = f.state, family = f.viaFamily
                            )
                            val existing = friendMarkers[f.uid]
                            if (existing == null) {
                                val m = Marker(map).apply {
                                    position = target
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    this.icon = icon
                                    title = f.name
                                    setOnMarkerClickListener { _, _ ->
                                        selectedPlace = null
                                        selected = f
                                        true
                                    }
                                }
                                friendMarkers[f.uid] = m
                                map.overlays.add(m)
                            } else {
                                if (existing.icon !== icon) existing.icon = icon
                                existing.setOnMarkerClickListener { _, _ -> selectedPlace = null; selected = f; true }
                                friendAnimTargets[f.uid] = target
                            }
                        }

                        // Locurile mele — pin amber, sub prieteni.
                        val placeIds = places.map { it.id }.toSet()
                        placeMarkers.keys.filter { it !in placeIds }.forEach { id ->
                            placeMarkers.remove(id)?.let { map.overlays.remove(it) }
                        }
                        places.forEach { p ->
                            val icon = MapMarkers.placeMarker(context, p.stars, mine = true)
                            val m = placeMarkers[p.id] ?: Marker(map).apply {
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                placeMarkers[p.id] = this
                                map.overlays.add(minOf(1, map.overlays.size), this)
                            }
                            m.position = GeoPoint(p.lat, p.lng)
                            if (m.icon !== icon) m.icon = icon
                            m.title = p.name
                            m.setOnMarkerClickListener { _, _ ->
                                selected = null
                                selectedPlace = PlaceSel.Mine(p)
                                true
                            }
                        }

                        // Recomandările prietenilor — pin albastru.
                        val recIds = recommended.map { it.id }.toSet()
                        recMarkers.keys.filter { it !in recIds }.forEach { id ->
                            recMarkers.remove(id)?.let { map.overlays.remove(it) }
                        }
                        recommended.forEach { r ->
                            val icon = MapMarkers.placeMarker(context, r.stars, mine = false)
                            val m = recMarkers[r.id] ?: Marker(map).apply {
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                recMarkers[r.id] = this
                                map.overlays.add(minOf(1, map.overlays.size), this)
                            }
                            m.position = GeoPoint(r.lat, r.lng)
                            if (m.icon !== icon) m.icon = icon
                            m.title = r.name
                            m.setOnMarkerClickListener { _, _ ->
                                selected = null
                                selectedPlace = PlaceSel.Rec(r)
                                true
                            }
                        }

                        map.invalidate()
                    },
                    onRelease = { detachMap(it) }
                )
            }
        }

        // Ciclul de viață osmdroid: onResume la intrare, onPause + onDetach la ieșire (fără fire de tile-uri scăpate).
        val liveMap = mapRef.value
        DisposableEffect(liveMap) {
            try { liveMap?.onResume() } catch (_: Exception) { }
            onDispose {
                if (liveMap != null) {
                    try { liveMap.onPause() } catch (_: Exception) { }
                    detachMap(liveMap)
                }
            }
        }

        // Sursa de tile-uri: CARTO → OSM întunecat → offline (doar zonele văzute).
        LaunchedEffect(liveMap) {
            val m = liveMap ?: return@LaunchedEffect
            tileState = try { ForjaTiles.chooseOnline(m) } catch (_: Exception) { TileState.OFFLINE }
        }

        // Zonele noi se văd imediat, nu la următoarea mișcare de hartă.
        LaunchedEffect(cells.size, showExplore) {
            mapRef.value?.invalidate()
        }

        // Interpolare lină spre țintele noi — gentle, fără teleport (mișcare redusă = direct).
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(60)
                val map = mapRef.value ?: continue
                var changed = false
                val factor = if (reducedMotion) 1.0 else 0.12
                friendAnimTargets.forEach { (uid, target) ->
                    val m = friendMarkers[uid] ?: return@forEach
                    val cur = m.position
                    val dLat = target.latitude - cur.latitude
                    val dLng = target.longitude - cur.longitude
                    if (kotlin.math.abs(dLat) > 1e-7 || kotlin.math.abs(dLng) > 1e-7) {
                        m.position = GeoPoint(cur.latitude + dLat * factor, cur.longitude + dLng * factor)
                        changed = true
                    }
                }
                if (changed) map.invalidate()
            }
        }

        // Poziția mea LIVE cât timp harta e deschisă: update la 3s, centrare la primul fix, hrănește Explorarea.
        DisposableEffect(hasLocation) {
            if (!hasLocation) return@DisposableEffect onDispose { }
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateDistanceMeters(0f)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    try { app.explore.onLocation(loc, "map") } catch (_: Exception) { }
                    val map = mapRef.value ?: return
                    if (mapDetached.value) return
                    val p = GeoPoint(loc.latitude, loc.longitude)
                    val existing = myMarker.value
                    if (existing == null) {
                        val m = Marker(map).apply {
                            position = p
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = MapMarkers.friendMarker(context, "Tu", me = true, ghost = ghostActive)
                            title = "Tu"
                        }
                        myMarker.value = m
                        map.overlays.add(m)
                    } else {
                        existing.position = p
                    }
                    if (!hadFirstFix) {
                        hadFirstFix = true
                        map.controller.setZoom(16.0)
                        map.controller.animateTo(p)
                    }
                    map.invalidate()
                }
            }
            try {
                // Ultimul fix cunoscut — instant, ca să nu aștepți GPS-ul.
                client.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        cb.onLocationResult(LocationResult.create(listOf(loc)))
                    }
                }
                client.requestLocationUpdates(request, cb, android.os.Looper.getMainLooper())
            } catch (_: SecurityException) { }
            onDispose {
                client.removeLocationUpdates(cb)
            }
        }

        // Markerul meu reflectă fantoma și când o pornești/oprești ulterior.
        LaunchedEffect(ghostActive) {
            val m = myMarker.value ?: return@LaunchedEffect
            m.icon = MapMarkers.friendMarker(context, "Tu", me = true, ghost = ghostActive)
            mapRef.value?.invalidate()
        }

        // Traseul GO — glow 13dp @16% + linie 4,5dp, camera follow. Glow-ul stă peste zonele explorate.
        LaunchedEffect(go.points.size, go.recording) {
            val map = mapRef.value ?: return@LaunchedEffect
            if (go.recording && go.points.isNotEmpty()) {
                val pts = go.points.map { GeoPoint(it.first, it.second) }
                if (goLine.value == null) {
                    val glow = Polyline().apply {
                        outlinePaint.color = android.graphics.Color.parseColor("#296F855A")
                        outlinePaint.strokeWidth = 13 * context.resources.displayMetrics.density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    val line = Polyline().apply {
                        outlinePaint.color = android.graphics.Color.parseColor("#6F855A")
                        outlinePaint.strokeWidth = 4.5f * context.resources.displayMetrics.density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    goGlow.value = glow
                    goLine.value = line
                    map.overlays.add(minOf(1, map.overlays.size), glow)
                    map.overlays.add(line)
                }
                goGlow.value?.setPoints(pts)
                goLine.value?.setPoints(pts)
                myMarker.value?.position = pts.last()
                map.controller.animateTo(pts.last())
                map.invalidate()
            }
            if (!go.recording && goLine.value != null) {
                map.overlays.remove(goLine.value)
                map.overlays.remove(goGlow.value)
                goLine.value = null
                goGlow.value = null
                map.invalidate()
            }
        }

        // Ghost inițial + familia din profilul meu (oglindă locală pentru publicatori)
        LaunchedEffect(Unit) {
            val uid = app.auth.currentUid ?: return@LaunchedEffect
            try {
                val snap = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
                ghostUntil = snap.getLong("ghostUntil") ?: 0L
                val fam = (snap.get("familyUids") as? List<*>)?.mapNotNull { it as? String }?.toSet()
                if (fam != null && fam != familyUids) app.prefs.setFamilyUids(fam)
            } catch (_: Exception) { }
        }

        // ── UI peste hartă ──
        TopScrim(Modifier.height(120.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Harta VIU", style = TitleModule.copy(fontSize = 22.sp))
                Text(
                    if (friends.isEmpty()) "invită-ți primul prieten" else "${friends.size} prieteni · ${friends.count { !it.ghost && System.currentTimeMillis() - it.locUpdatedAt < 15 * 60000 }} activi",
                    style = monoLabel(8, 0.12f).copy(color = TextSecondary)
                )
            }
            Row {
                MapFab(icon = { Icon(Icons.Outlined.History, "Activitățile tale", tint = TextSecondary, modifier = Modifier.size(20.dp)) }) {
                    onOpenActivities()
                }
                Spacer(Modifier.width(8.dp))
                MapFab(icon = { Icon(Icons.Outlined.Search, "Prieteni", tint = TextSecondary, modifier = Modifier.size(20.dp)) }) {
                    friendsOpen = true
                }
                Spacer(Modifier.width(8.dp))
                MapFab(icon = { Icon(Icons.Outlined.Place, "Locurile tale", tint = TextSecondary, modifier = Modifier.size(20.dp)) }) {
                    placesOpen = true
                }
                Spacer(Modifier.width(8.dp))
                MapFab(
                    icon = {
                        Icon(
                            Icons.Outlined.VisibilityOff, "Mod fantomă",
                            tint = if (ghostActive) SleepRem else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    active = ghostActive
                ) { ghostOpen = true }
            }
        }

        // Sub antet: chip-urile explorării, starea hărții, fantoma, bannerul — pe o singură coloană, fără suprapuneri.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 62.dp)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MapChip(
                    text = "▩ ${cells.size} zone",
                    color = if (showExplore) Accent2 else TextDim,
                    active = showExplore
                ) { scope.launch { app.prefs.setShowExplore(!showExplore) } }
                MapChip(text = "● ${places.size} locuri", color = PlaceAmber) { placesOpen = true }
                when (tileState) {
                    TileState.OFFLINE -> MapChip(text = "Hartă offline · doar zonele văzute", color = TextSecondary)
                    TileState.OSM -> MapChip(text = "Hartă OSM", color = TextSecondary)
                    TileState.CARTO -> {}
                }
            }

            // Chip stare fantomă
            if (ghostActive) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xE0101822))
                        .border(1.dp, Color(0x809DBFE8), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Fantomă · " +
                            (if (ghostUntil == -1L) "până o reactivezi" else "până la ${Fmt.clock(ghostUntil)}") +
                            (if (familyUids.isNotEmpty()) " · familia te vede" else ""),
                        style = BodySmall.copy(color = SleepRem)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "oprește",
                        style = BodySmall.copy(color = TextPrimary),
                        modifier = Modifier.pressable({
                            scope.launch {
                                app.auth.currentUid?.let { app.friends.setGhost(it, 0L) }
                                app.prefs.setGhostUntilLocal(0L)
                                ghostUntil = 0L
                                toast.show("Ești din nou vizibil pe hartă.")
                            }
                        })
                    )
                }
            }

            // Banner: activează locația în fundal — inima hărții VIU.
            if (hasLocation && !hasBackground && !bgBannerDismissed && !go.recording) {
                Spacer(Modifier.height(8.dp))
                ForjaCard(
                    Modifier.fillMaxWidth(),
                    fill = Color(0xF0121214),
                    stroke = Color(0x666F855A)
                ) {
                    Text("Prietenii să te vadă mereu?", style = BodyStrong.copy(fontSize = 14.sp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Acum te văd doar cât e FORJA deschisă. Alege „Se permite tot timpul” și harta trăiește și în fundal — fantoma rămâne singura excepție.",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        PrimaryButton("Activează", small = true, onClick = {
                            bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        SecondaryButton("Nu acum", onClick = {
                            scope.launch { app.prefs.setBgBannerDismissed() }
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Consola GO / butoanele hărții
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 118.dp)
                .padding(horizontal = 16.dp)
        ) {
            if (go.recording) {
                var tick by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) {
                    while (true) { kotlinx.coroutines.delay(1000); tick++ }
                }
                val elapsedS = remember(tick, go.startedAt) { (System.currentTimeMillis() - go.startedAt) / 1000 }
                ForjaCard(
                    Modifier.fillMaxWidth(),
                    fill = Color(0xF0121214),
                    stroke = StrokeOnVideo
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        GoStat("TIMP", Fmt.durationMs(elapsedS))
                        GoStat("KM", Fmt.km(go.distanceM, 2))
                        if (go.sport == "ride") {
                            GoStat(
                                "VITEZĂ",
                                if (go.distanceM >= 50 && elapsedS > 0)
                                    Fmt.km(go.distanceM / elapsedS * 3600, 1)
                                else "—"
                            )
                        } else {
                            GoStat(
                                "RITM",
                                if (go.distanceM >= 50)
                                    Fmt.pace((elapsedS / (go.distanceM / 1000.0)).toLong())
                                else "—"
                            )
                        }
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Error)
                                .pressable({
                                    GoTrackService.stop(context)
                                    toast.show(
                                        if (go.distanceM > 30)
                                            "Tură salvată: ${Fmt.km(go.distanceM)} km. Bravo."
                                        else "Prea scurt pentru salvare — data viitoare."
                                    )
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Stop, "Oprește", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.End),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 2D / 3D — înclinare persistată
                    MapFab(
                        icon = {
                            Text(
                                if (map3d) "2D" else "3D",
                                style = monoLabel(10, 0.10f).copy(color = if (map3d) Accent2 else TextSecondary)
                            )
                        },
                        active = map3d
                    ) {
                        scope.launch {
                            val next = !map3d
                            app.prefs.setMap3d(next)
                            if (next) toast.show("Vedere înclinată. Apasă din nou pentru 2D.")
                        }
                    }
                    // Recentrare pe mine
                    MapFab(icon = {
                        Icon(
                            Icons.Filled.MyLocation, "Centrează pe mine",
                            tint = if (hadFirstFix) Accent2 else TextDim,
                            modifier = Modifier.size(20.dp)
                        )
                    }) {
                        val p = myMarker.value?.position
                        if (p != null) {
                            mapRef.value?.controller?.setZoom(16.0)
                            mapRef.value?.controller?.animateTo(p)
                        } else if (!hasLocation) {
                            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else {
                            toast.show("Aștept semnalul GPS — ieși sub cer liber dacă ești în casă.")
                        }
                    }
                    // GO — pornește înregistrarea (alergare / mers / ciclism)
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(AccentGradient)
                            .border(1.dp, Color(0x66EDF3E4), CircleShape)
                            .pressable({
                                if (!hasLocation) {
                                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                } else {
                                    sportOpen = true
                                }
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "GO", tint = OnAccent, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        // Card loc selectat (al meu sau recomandat) — același loc ca cardul de prieten.
        val sel = selectedPlace
        if (sel != null) {
            ForjaCard(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                fill = Color(0xF0121214),
                stroke = StrokeOnVideo
            ) {
                when (sel) {
                    is PlaceSel.Mine -> {
                        val p = sel.p
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(PlaceAmber))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { "Loc fără nume" }, style = BodyStrong.copy(fontSize = 15.sp))
                                Text(
                                    "Ai stat ${stayLabel(p.stayMs)} · ultima dată ${Fmt.freshness(p.lastAt)}",
                                    style = BodySmall.copy(color = TextSecondary)
                                )
                            }
                            Text(
                                "închide",
                                style = BodyTiny.copy(color = TextDim),
                                modifier = Modifier.pressable({ selectedPlace = null })
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        StarRow(stars = p.stars, size = 16.dp, tint = PlaceAmber, onPick = null)
                        if (p.note.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(p.note, style = BodySmall.copy(color = TextSecondary))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            PrimaryButton(
                                text = "Editează",
                                small = true,
                                onClick = { placesOpen = true },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            SecondaryButton(
                                "Navighează",
                                onClick = { navigateTo(p.lat, p.lng, p.name) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is PlaceSel.Rec -> {
                        val r = sel.r
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(SleepRem))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { "Loc recomandat" }, style = BodyStrong.copy(fontSize = 15.sp))
                                Text(
                                    "Recomandat de ${r.ownerName} · ${Fmt.freshness(r.at)}",
                                    style = BodySmall.copy(color = TextSecondary)
                                )
                            }
                            Text(
                                "închide",
                                style = BodyTiny.copy(color = TextDim),
                                modifier = Modifier.pressable({ selectedPlace = null })
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        StarRow(stars = r.stars, size = 16.dp, tint = SleepRem, onPick = null)
                        if (r.note.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(r.note, style = BodySmall.copy(color = TextSecondary))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            PrimaryButton(
                                text = "Navighează",
                                small = true,
                                onClick = { navigateTo(r.lat, r.lng, r.name) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            SecondaryButton(
                                "Pe hartă",
                                onClick = {
                                    mapRef.value?.controller?.animateTo(GeoPoint(r.lat, r.lng))
                                    selectedPlace = null
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Card prieten selectat
        if (sel == null) selected?.let { f ->
            ForjaCard(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 190.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                fill = Color(0xF0121214),
                stroke = StrokeOnVideo
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = f.name, size = 44.dp, ring = true, ringColor = if (f.viaFamily) SleepRem else Accent2, live = !f.viaFamily)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                f.viaFamily -> "${f.name} · fantomă, dar familia te vede"
                                f.state == "run" -> "${f.name} aleargă acum"
                                f.state == "ride" -> "${f.name} e pe roți"
                                f.state == "walk" -> "${f.name} se plimbă"
                                f.state == "sleep" -> "${f.name} doarme"
                                else -> f.name
                            },
                            style = BodyStrong.copy(fontSize = 15.sp)
                        )
                        Text(
                            String.format(Locale.ROOT, "%.1f", f.speedMps * 3.6).replace('.', ',') +
                                " km/h · Actualizat ${Fmt.freshness(f.locUpdatedAt)}",
                            style = BodySmall.copy(color = TextSecondary)
                        )
                    }
                    Text(
                        "închide",
                        style = BodyTiny.copy(color = TextDim),
                        modifier = Modifier.pressable({ selected = null })
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    PrimaryButton(
                        text = "Trimite-i energie",
                        small = true,
                        onClick = {
                            scope.launch {
                                val uid = app.auth.currentUid ?: return@launch
                                val myName = try { app.auth.loadProfile()?.name ?: "Un prieten" } catch (_: Exception) { "Un prieten" }
                                val sent = try { app.friends.sendEnergy(uid, myName, f.uid) } catch (_: Exception) { false }
                                toast.show(
                                    if (sent) "${f.name.split(' ').first()} a primit energia ta."
                                    else "I-ai trimis deja energie azi. Un fulger pe zi."
                                )
                                selected = null
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(
                        "Pe hartă",
                        onClick = {
                            if (f.lat != null && f.lng != null) {
                                mapRef.value?.controller?.animateTo(GeoPoint(f.lat, f.lng))
                            }
                            selected = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Selector de sport pentru GO — Alergare / Mers / Ciclism
    if (sportOpen) {
        ModalBottomSheet(
            onDismissRequest = { sportOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Ce faci acum?", style = TitleModule.copy(fontSize = 20.sp))
                Spacer(Modifier.height(4.dp))
                Text("Consola și caloriile se adaptează sportului.", style = BodySmall)
                Spacer(Modifier.height(14.dp))
                @Composable
                fun sportOption(label: String, sub: String, sport: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
                    ForjaCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .pressable({
                                sportOpen = false
                                GoTrackService.start(context, sport)
                                toast.show("GO. Fiecare metru se vede.")
                            }),
                        fill = Surface2, padding = 14.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = Accent2, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(label, style = BodyStrong.copy(fontSize = 15.sp))
                                Text(sub, style = BodyTiny.copy(color = TextSecondary))
                            }
                        }
                    }
                }
                sportOption("Alergare", "ritm pe km · ca la alergătorii serioși", "run", Icons.AutoMirrored.Filled.DirectionsRun)
                sportOption("Mers", "plimbare, hike, pași — tot contează", "walk", Icons.AutoMirrored.Filled.DirectionsWalk)
                sportOption("Ciclism", "viteză în km/h în loc de ritm", "ride", Icons.AutoMirrored.Filled.DirectionsBike)
            }
        }
    }

    // Sheet mod fantomă
    if (ghostOpen) {
        ModalBottomSheet(
            onDismissRequest = { ghostOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Mod fantomă", style = TitleModule.copy(fontSize = 20.sp))
                Spacer(Modifier.height(4.dp))
                Text("Dispari de pe hartă pentru prieteni — familia te vede în continuare.", style = BodySmall)
                if (familyUids.isEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Încă n-ai pe nimeni în familie. O alegi din lista de prieteni, om cu om.",
                        style = BodyTiny.copy(color = TextDim)
                    )
                }
                Spacer(Modifier.height(16.dp))
                @Composable
                fun ghostOption(title: String, sub: String, until: Long) {
                    ForjaCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .pressable({
                                scope.launch {
                                    app.auth.currentUid?.let { app.friends.setGhost(it, until) }
                                    app.prefs.setGhostUntilLocal(until)
                                    ghostUntil = until
                                    ghostOpen = false
                                    toast.show(
                                        when (until) {
                                            -1L -> "Fantomă activă. O oprești tu."
                                            0L -> "Ești din nou vizibil pe hartă."
                                            else -> "Ești fantomă până la ${Fmt.clock(until)}."
                                        }
                                    )
                                }
                            }),
                        fill = Surface2, padding = 12.dp
                    ) {
                        Text(title, style = BodyStrong)
                        Text(sub, style = BodyTiny.copy(color = TextDim))
                    }
                }
                ghostOption("Pauză 1 oră", "până la ${Fmt.clock(System.currentTimeMillis() + 3600_000)}", System.currentTimeMillis() + 3600_000)
                val tomorrow7 = java.time.LocalDate.now().plusDays(1).atTime(7, 0)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                ghostOption("Până mâine", "07:00", tomorrow7)
                ghostOption("Până o reactivez", "manual", -1L)
                if (ghostActive) {
                    Spacer(Modifier.height(6.dp))
                    SecondaryButton(
                        "Oprește fantoma",
                        onClick = {
                            scope.launch {
                                app.auth.currentUid?.let { app.friends.setGhost(it, 0L) }
                                app.prefs.setGhostUntilLocal(0L)
                                ghostUntil = 0L
                                ghostOpen = false
                                toast.show("Ești din nou vizibil pe hartă.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // Sheet prieteni
    if (friendsOpen) {
        FriendsSheet(
            friends = shownFriends,
            onClose = { friendsOpen = false },
            onPick = { f ->
                friendsOpen = false
                if (f.lat != null && f.lng != null && (!f.ghost || f.viaFamily)) {
                    mapRef.value?.controller?.animateTo(GeoPoint(f.lat, f.lng))
                    selectedPlace = null
                    selected = f
                } else {
                    toast.show(
                        if (f.ghost) "${f.name} e în modul fantomă acum."
                        else "${f.name} nu și-a pornit încă locația."
                    )
                }
            }
        )
    }

    // Sheet locuri
    if (placesOpen) {
        PlacesSheet(
            places = places,
            recommended = recommended,
            thresholdMin = thresholdMin,
            cellCount = cells.size,
            onThreshold = { min -> scope.launch { app.prefs.setPlaceThresholdMin(min) } },
            onSave = { p ->
                scope.launch {
                    try {
                        app.explore.savePlace(p)
                        if ((selectedPlace as? PlaceSel.Mine)?.p?.id == p.id) selectedPlace = PlaceSel.Mine(p)
                    } catch (_: Exception) { toast.show("Nu s-a putut salva. Mai încearcă.") }
                }
            },
            onDelete = { p ->
                scope.launch {
                    try {
                        app.explore.deletePlace(p)
                        if ((selectedPlace as? PlaceSel.Mine)?.p?.id == p.id) selectedPlace = null
                        toast.show("Loc șters.")
                    } catch (_: Exception) { toast.show("Nu s-a putut șterge. Mai încearcă.") }
                }
            },
            onRecommend = { p ->
                val friendUids = friends.map { it.uid }
                if (friendUids.isEmpty()) {
                    toast.show("Adaugă întâi un prieten — lui îi recomanzi.")
                } else {
                    scope.launch {
                        val uid = app.auth.currentUid ?: return@launch
                        try {
                            val myName = try { app.auth.loadProfile()?.name ?: "Un prieten" } catch (_: Exception) { "Un prieten" }
                            val id = app.friends.recommendPlace(uid, myName, p, friendUids)
                            app.explore.savePlace(p.copy(recommended = true, remoteId = id))
                            toast.show("Recomandat prietenilor tăi. Îl văd pe hartă cu punct albastru.")
                        } catch (_: Exception) {
                            toast.show("Nu s-a putut recomanda. Verifică internetul.")
                        }
                    }
                }
            },
            onPick = { lat, lng ->
                placesOpen = false
                mapRef.value?.controller?.setZoom(16.0)
                mapRef.value?.controller?.animateTo(GeoPoint(lat, lng))
            },
            onClose = { placesOpen = false }
        )
    }
}

@Composable
private fun MapFab(icon: @Composable () -> Unit, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (active) Color(0x299DBFE8) else Color(0xE6101114))
            .border(1.dp, if (active) Color(0x809DBFE8) else StrokeOnVideo, CircleShape)
            .pressable(onClick),
        contentAlignment = Alignment.Center
    ) { icon() }
}

/** Chip mic, mono, peste hartă: zone deblocate, locuri, starea hărții. */
@Composable
private fun MapChip(text: String, color: Color = TextSecondary, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    val base = Modifier
        .clip(shape)
        .background(if (active) TabPillActive else Color(0xE6101114))
        .border(1.dp, if (active) Color(0x666F855A) else StrokeOnVideo, shape)
    val m = if (onClick != null) base.pressable(onClick) else base
    Box(m.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = monoLabel(9, 0.10f).copy(color = color))
    }
}

@Composable
private fun GoStat(label: String, value: String) {
    Column {
        Text(label, style = monoLabel(8, 0.12f))
        Text(value, style = heroNumeral(22))
    }
}
