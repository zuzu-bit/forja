package com.forja.app.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.FamilyLoc
import com.forja.app.core.data.Friend
import com.forja.app.core.data.RecommendedPlace
import com.forja.app.core.data.listening
import com.forja.app.core.data.db.PlaceEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.explore.ExploreSync
import com.forja.app.core.location.BgLocation
import com.forja.app.core.location.GoTrackService
import com.forja.app.core.map.ExploreStats
import com.forja.app.core.map.ForjaMap
import com.forja.app.core.map.FriendPin
import com.forja.app.core.map.MapController
import com.forja.app.core.map.MapGeo
import com.forja.app.core.map.MapLayers
import com.forja.app.core.map.MapPrefs
import com.forja.app.core.util.Fmt
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/** Înălțimea barei de jos: butoanele hărții stau deasupra ei. */
private const val BOTTOM_BAR_DP = 118

/** Ce loc e selectat pe hartă: al meu (editabil) sau recomandat de un prieten. */
private sealed class PlaceSel {
    data class Mine(val p: PlaceEntity) : PlaceSel()
    data class Rec(val r: RecommendedPlace) : PlaceSel()
}

/** Ultimul meu fix: poziție, direcție, viteză. */
private data class MyFix(val lat: Double, val lng: Double, val bearing: Float, val speed: Float)

/**
 * Harta VIU pe MapLibre + OpenFreeMap: 3D real (clădiri extrudate), teritorii cucerite, străzile tale, prieteni cu avatar,
 * mod fantomă (familia te vede și atunci), GO recording, locuri și recomandări, straturi, zi/noapte.
 * Toată logica de hartă e în [MapController]; aici doar datele și cromul.
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
    val mapPrefs = remember { MapPrefs(context) }

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
    // Nu cerem locația automat la intrare — o cere doar butonul GO / recentrare.

    // ── Prieteni + familie ──
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        try { app.friends.friendsFlow(uid).collect { friends = it } } catch (_: Exception) { }
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

    // ── Explorare: teritorii, locuri, recomandări, străzile tale ──
    val cellsFlow = remember { app.db.exploreDao().allCells() }
    val placesFlow = remember { app.db.exploreDao().places() }
    val activitiesFlow = remember { app.db.activityDao().all() }
    val cells by cellsFlow.collectAsState(initial = emptyList())
    val places by placesFlow.collectAsState(initial = emptyList())
    val activities by activitiesFlow.collectAsState(initial = emptyList())
    var recommended by remember { mutableStateOf<List<RecommendedPlace>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        try { app.friends.recommendedPlacesFlow(uid).collect { recommended = it } } catch (_: Exception) { }
    }
    val map3d by app.prefs.map3d.collectAsState(initial = false)
    val thresholdMin by app.prefs.placeThresholdMin.collectAsState(initial = 300)
    val layers by mapPrefs.layers.collectAsState(initial = MapLayers())
    // „Și pe site”: la deschiderea hărții preluăm editările făcute din laptop (doar cu comutatorul pornit).
    val syncSite by app.prefs.exploreSyncSite.collectAsState(initial = false)
    LaunchedEffect(syncSite) {
        if (syncSite) try { ExploreSync.pull(app) } catch (_: Exception) { }
    }

    // Cifrele teritoriului
    val percentLabel = remember(cells) { ExploreStats.percentLabel(cells) }
    // Locul între prieteni doar când există ce compara: am teritorii și cel puțin un prieten a publicat un număr — altfel ar ieși
    // „Loc #1” pentru oricine are un prieten, iar tonul nu promite mai mult decât face aplicația.
    val rank = remember(cells.size, friends) {
        if (cells.isEmpty() || friends.none { it.exploreCells > 0 }) null else ExploreStats.rankAmongFriends(cells.size, friends)
    }
    val modeCounts = remember(cells) { ExploreStats.modeCounts(cells) }

    var placesOpen by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var selectedPlace by remember { mutableStateOf<PlaceSel?>(null) }
    var ghostUntil by remember { mutableStateOf(0L) }
    val ghostActive = ghostUntil == -1L || ghostUntil > System.currentTimeMillis()
    var ghostOpen by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }
    var sportOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Friend?>(null) }
    // Cardul prietenului arată selecția LIVE (poziție, stare), nu instantaneul de la atingere.
    val selectedLive = remember(selected, shownFriends) { selected?.let { s -> shownFriends.firstOrNull { it.uid == s.uid } ?: s } }
    val energySentToday = remember { mutableStateMapOf<String, Boolean>() }
    // Ultima energie primită de la prietenul selectat (energy/{me}_{zi}_{el}) — o citire când îl alegi.
    var energyFromSelectedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(selected?.uid) {
        energyFromSelectedAt = 0L
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        val other = selected?.uid ?: return@LaunchedEffect
        try {
            val snap = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("energy")
                .whereEqualTo("to", uid).whereEqualTo("from", other).get().await()
            energyFromSelectedAt = snap.documents.maxOfOrNull { it.getLong("at") ?: 0L } ?: 0L
        } catch (_: Exception) { }
    }

    val go by GoTrackService.state.collectAsState()

    // ── Motorul hărții ──
    var styleReady by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }
    val controller = remember {
        MapController(context).also {
            it.reducedMotion = reducedMotion
            // Noaptea se știe înainte de a crea MapView-ul (culoarea de încărcare, nuanța butonului „i”): implicit după ceas
            // (Auto), corectată de preferințe imediat ce sosesc — fără fulger de hârtie crem la 23:00.
            it.setNight(MapLayers().isNight())
            // Legate la creare, nu într-un SideEffect: un stil servit din cache poate fi gata înainte de prima recompoziție.
            it.onStyleReady = { styleReady = true; mapFailed = false }
            it.onLoadFailed = { mapFailed = true }
        }
    }
    var online by remember { mutableStateOf(true) }
    var myFix by remember { mutableStateOf<MyFix?>(null) }
    var hadFirstFix by remember { mutableStateOf(false) }
    var photoVersion by remember { mutableStateOf(0) }

    // Noaptea se recalculează la fiecare minut (modul Auto: 21:00–06:00).
    var minuteTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); minuteTick++ } }
    val night = remember(layers, minuteTick) { layers.isNight() }

    // Conectivitate: fără net → chip onest; când revine, reîncercăm stilul dacă nu s-a încărcat.
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = false }
        }
        try {
            online = cm?.activeNetwork != null
            cm?.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) { }
        onDispose { try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) { } }
    }
    LaunchedEffect(online) { if (online && mapFailed) controller.reload() }

    SideEffect {
        controller.onTap = { hit ->
            when (hit?.kind) {
                "friend" -> shownFriends.firstOrNull { it.uid == hit.id }?.let { f ->
                    selectedPlace = null
                    selected = f
                    if (f.lat != null && f.lng != null) controller.easeTo(f.lat, f.lng, null, 600)
                }
                "place" -> places.firstOrNull { it.id.toString() == hit.id }?.let { p ->
                    selected = null
                    selectedPlace = PlaceSel.Mine(p)
                }
                "rec" -> recommended.firstOrNull { it.id == hit.id }?.let { r ->
                    selected = null
                    selectedPlace = PlaceSel.Rec(r)
                }
                "me" -> Unit
                else -> { selected = null; selectedPlace = null }
            }
        }
    }

    LaunchedEffect(night) { controller.setNight(night) }
    LaunchedEffect(layers) { controller.setLayers(layers) }
    LaunchedEffect(map3d, styleReady) { controller.set3d(map3d, animate = styleReady) }
    // Pulsul pornește abia când există un fix (nu doar permisiunea) — altfel ar anima un strat gol.
    LaunchedEffect(myFix != null, ghostActive, styleReady) { controller.setPulse(myFix != null && !ghostActive) }

    // Fotografiile prietenilor (Coil → bitmap rotund); când apare una nouă, avatarele se redesenează.
    LaunchedEffect(friends) {
        for (f in friends) {
            if (controller.icons.loadPhoto(f.uid, f.photoUrl)) photoVersion++
        }
    }

    // Teritorii + strălucire: geometria se construiește în fundal, setGeoJson pe firul principal (≤ 1/s).
    LaunchedEffect(cells, styleReady) {
        val (cf, ef, hf) = withContext(Dispatchers.Default) { Triple(MapGeo.cells(cells), MapGeo.cellEdges(cells), MapGeo.heat(cells)) }
        controller.setCells(cf, ef)
        controller.setHeat(hf)
    }
    // Străzile tale: toate turele salvate.
    LaunchedEffect(activities, styleReady) {
        val fc = withContext(Dispatchers.Default) { MapGeo.streets(activities) }
        controller.setStreets(fc)
    }
    // Locurile mele și recomandările (iconițele se înregistrează în stil la nevoie).
    val selectedPlaceId = (selectedPlace as? PlaceSel.Mine)?.p?.id
    val selectedRecId = (selectedPlace as? PlaceSel.Rec)?.r?.id
    LaunchedEffect(places, selectedPlaceId, styleReady) {
        controller.setPlaces(MapGeo.places(places, selectedPlaceId) { p -> controller.icons.place(p.stars, true, p.id == selectedPlaceId) })
    }
    LaunchedEffect(recommended, selectedRecId, styleReady) {
        controller.setRecommended(MapGeo.recommended(recommended, selectedRecId) { r -> controller.icons.place(r.stars, false, r.id == selectedRecId) })
    }
    // Prietenii: avatar (foto sau inițiale), etichetă „Ana · 1,2 km”, selectatul deasupra, fantoma din familie la 0,55,
    // insigna „♪” cât ascultă ceva (proaspăt < 10 min; minuteTick o stinge la timp).
    LaunchedEffect(shownFriends, selected?.uid, myFix, photoVersion, styleReady, minuteTick) {
        val me = myFix
        val now = System.currentTimeMillis()
        val pins = shownFriends
            .filter { it.lat != null && it.lng != null && (!it.ghost || it.viaFamily) }
            .map { f ->
                val first = f.name.trim().split(' ').first().ifBlank { "Prieten" }
                val dist = if (me != null) ExploreStats.distanceM(me.lat, me.lng, f.lat!!, f.lng!!) else null
                val moving = f.state == "run" || f.state == "walk" || f.state == "ride"
                val isSel = f.uid == selected?.uid
                FriendPin(
                    uid = f.uid, lat = f.lat!!, lng = f.lng!!,
                    icon = controller.icons.friend(
                        f.uid, f.name, f.state, ghost = f.viaFamily, family = f.viaFamily, selected = isSel,
                        music = f.listening(now) != null
                    ),
                    label = if (dist != null) "$first · ${ExploreStats.distanceLabel(dist)}" else first,
                    sort = if (isSel) 2f else if (moving) 1f else 0f,
                    alpha = if (f.viaFamily) 0.55f else 1f
                )
            }
        controller.setFriends(pins)
    }
    // Linia punctată eu → prietenul selectat: controllerul o trage spre poziția GLISATĂ a prietenului, cadru cu cadru.
    LaunchedEffect(selectedLive?.uid, styleReady) { controller.setLinkTo(selectedLive?.uid) }

    // Poziția mea LIVE cât timp harta e deschisă: update la 3 s, hrănește Explorarea.
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
                myFix = MyFix(
                    loc.latitude, loc.longitude,
                    if (loc.hasBearing()) loc.bearing else 0f,
                    if (loc.hasSpeed()) loc.speed else 0f
                )
            }
        }
        try {
            // Ultimul fix cunoscut — instant, ca să nu aștepți GPS-ul.
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) cb.onLocationResult(LocationResult.create(listOf(loc)))
            }
            client.requestLocationUpdates(request, cb, android.os.Looper.getMainLooper())
        } catch (_: SecurityException) { }
        onDispose { client.removeLocationUpdates(cb) }
    }

    // Markerul „Tu”: la fix, în GO stă pe ultimul punct al traseului; reflectă fantoma.
    LaunchedEffect(myFix, ghostActive, go.points.size, go.recording, styleReady) {
        val fix = myFix ?: return@LaunchedEffect
        val icon = controller.icons.me(ghostActive)
        if (go.recording && go.points.isNotEmpty()) {
            val (lat, lng) = go.points.last()
            controller.setMe(lat, lng, fix.bearing, go.lastSpeedMps.toFloat(), icon, ghostActive)
        } else {
            controller.setMe(fix.lat, fix.lng, fix.bearing, fix.speed, icon, ghostActive)
        }
    }
    // La deschidere: zbor (900 ms) la ultima poziție cunoscută/actuală, zoom 15,5 — o singură dată per ecran.
    LaunchedEffect(myFix != null, styleReady) {
        val fix = myFix ?: return@LaunchedEffect
        if (!styleReady || hadFirstFix) return@LaunchedEffect
        hadFirstFix = true
        controller.flyTo(fix.lat, fix.lng, 15.5, 900)
    }

    // Traseul GO — glow + linie verde; camera urmărește DOAR cât înregistrezi.
    LaunchedEffect(go.points.size, go.recording, styleReady) {
        if (go.recording && go.points.isNotEmpty()) {
            controller.setLiveRoute(MapGeo.route(go.points))
            val (lat, lng) = go.points.last()
            controller.easeTo(lat, lng, null, 900)
        } else {
            controller.setLiveRoute(MapGeo.empty())
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

    fun navigateTo(lat: Double, lng: Double, label: String) {
        try {
            val q = Uri.encode(label.ifBlank { "Loc FORJA" })
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($q)")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast.show("Nu am găsit o aplicație de navigație pe telefon.")
        }
    }

    fun pickFriend(f: Friend) {
        if (f.lat != null && f.lng != null && (!f.ghost || f.viaFamily)) {
            selectedPlace = null
            selected = f
            controller.flyTo(f.lat, f.lng, maxOf(controller.zoom, 15.0), 900)
        } else {
            toast.show(
                if (f.ghost) "${f.name} e în modul fantomă acum."
                else "${f.name} nu și-a pornit încă locația."
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        // Harta — un singur MapView; 3D = pitch real al camerei, cromul rămâne drept.
        ForjaMap(controller = controller, modifier = Modifier.fillMaxSize(), bottomInsetDp = BOTTOM_BAR_DP)

        // ── UI peste hartă ──
        // Voalul de sus doar noaptea: pe hârtia de zi un fum negru ar fi ieftin, iar antetul stă oricum pe pastila lui.
        if (night) TopScrim(Modifier.height(120.dp))

        val headerShape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Antetul pe aceeași pastilă întunecată ca MapFab/MapChip: lizibil și pe cremul de zi, și pe noapte.
            Column(
                Modifier
                    .clip(headerShape)
                    .background(Color(0xE6101114))
                    .border(1.dp, StrokeOnVideo, headerShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Harta VIU", style = TitleModule.copy(fontSize = 22.sp, lineHeight = 26.sp))
                    Spacer(Modifier.width(10.dp))
                    StampLabel("TEREN", color = Accent2, fontSize = 8, rotationDeg = -4f, appear = false)
                }
                Text(
                    if (friends.isEmpty()) "invită-ți primul prieten"
                    else "${friends.size} prieteni · ${friends.count { !it.ghost && System.currentTimeMillis() - it.locUpdatedAt < 15 * 60000 }} activi",
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

        // Sub antet: cipurile teritoriului, starea hărții, fantoma, bannerul — pe o singură coloană.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 68.dp)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MapChip(
                    text = "▩ ${cells.size} teritorii · $percentLabel",
                    color = if (layers.territories) Accent2 else TextDim,
                    active = layers.territories
                ) { placesOpen = true }
                MapChip(text = "● ${places.size} locuri", color = PlaceAmber) { placesOpen = true }
                if (rank != null) {
                    MapChip(text = "Loc #$rank între prieteni", color = SleepRem) { placesOpen = true }
                }
                if (mapFailed || !online) {
                    MapChip(text = "Fără net · harta din memorie", color = TextSecondary)
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
                                try { app.auth.currentUid?.let { app.friends.setGhost(it, 0L) } } catch (_: Exception) { }
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

        // ── Jos: cardul selecției, consola GO sau butoanele, banda cu prieteni, atribuirea ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = BOTTOM_BAR_DP.dp)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            val sel = selectedPlace
            val f = selectedLive
            if (sel != null) {
                PlaceCardOnMap(
                    sel = sel,
                    onClose = { selectedPlace = null },
                    onEdit = { placesOpen = true },
                    onNavigate = { lat, lng, label -> navigateTo(lat, lng, label) },
                    onCenter = { lat, lng -> controller.easeTo(lat, lng, null, 600) }
                )
                Spacer(Modifier.height(10.dp))
            } else if (f != null) {
                FriendCardOnMap(
                    f = f,
                    myFix = myFix,
                    energySent = energySentToday[f.uid] == true,
                    energyReceivedAt = energyFromSelectedAt,
                    onClose = { selected = null },
                    onEnergy = {
                        scope.launch {
                            val uid = app.auth.currentUid ?: return@launch
                            val myName = try { app.auth.loadProfile()?.name ?: "Un prieten" } catch (_: Exception) { "Un prieten" }
                            val sent = try { app.friends.sendEnergy(uid, myName, f.uid) } catch (_: Exception) { false }
                            energySentToday[f.uid] = true
                            toast.show(
                                if (sent) "${f.name.split(' ').first()} a primit energia ta."
                                else "I-ai trimis deja energie azi. Un fulger pe zi."
                            )
                            selected = null
                        }
                    },
                    onNavigate = { if (f.lat != null && f.lng != null) navigateTo(f.lat, f.lng, f.name) }
                )
                Spacer(Modifier.height(10.dp))
            }

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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    // Stânga: Straturi + banda cu prieteni
                    Column(Modifier.weight(1f)) {
                        MapFab(
                            icon = { Icon(Icons.Outlined.Layers, "Straturi", tint = TextSecondary, modifier = Modifier.size(20.dp)) }
                        ) { layersOpen = true }
                        if (shownFriends.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            FriendsStrip(
                                friends = shownFriends.sortedByDescending { it.locUpdatedAt },
                                selectedUid = selected?.uid,
                                onPick = { pickFriend(it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    // Dreapta: 3D/2D, recentrare, GO
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                        MapFab(icon = {
                            Icon(
                                Icons.Filled.MyLocation, "Centrează pe mine",
                                tint = if (myFix != null) Accent2 else TextDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }) {
                            val fix = myFix
                            if (fix != null) {
                                controller.recenter(fix.lat, fix.lng)
                            } else if (!hasLocation) {
                                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else {
                                toast.show("Aștept semnalul GPS — ieși sub cer liber dacă ești în casă.")
                            }
                        }
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

            // Atribuirea (obligatorie), pe un singur rând de 21 dp: butonul „i” al MapLibre (21 dp, la 16 dp de stânga și
            // BOTTOM_BAR_DP de jos — vezi createView) + creditul, care începe 6 dp după el, în aceeași nuanță. Rândul e mereu
            // ultimul copil, deci nici Straturi / banda cu prieteni, nici consola GO nu acoperă butonul.
            Spacer(Modifier.height(4.dp))
            Row(Modifier.height(21.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(27.dp))
                Text(
                    "© OpenFreeMap · OpenMapTiles · OpenStreetMap",
                    style = monoLabel(7, 0.06f).copy(color = if (night) TextDim else Color(0xFF6B675F))
                )
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
                                    try { app.auth.currentUid?.let { app.friends.setGhost(it, until) } } catch (_: Exception) { }
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
                                try { app.auth.currentUid?.let { app.friends.setGhost(it, 0L) } } catch (_: Exception) { }
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
                pickFriend(f)
            }
        )
    }

    // Sheet straturi
    if (layersOpen) {
        LayersSheet(
            layers = layers,
            onChange = { l -> scope.launch { mapPrefs.save(l) } },
            onClose = { layersOpen = false }
        )
    }

    // Sheet locuri (+ teritorii)
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
                controller.flyTo(lat, lng, 16.0, 900)
            },
            onClose = { placesOpen = false },
            syncSite = syncSite,
            onSyncSite = { on ->
                scope.launch {
                    app.prefs.setExploreSyncSite(on)
                    if (on) {
                        // Retrimitem tot: site-ul trebuie să arate exact ce are telefonul (chiar dacă a fost șters de acolo).
                        app.prefs.setExploreSyncedAt(0L)
                        ExploreSync.schedule(context)
                        ExploreSync.kick(context)
                        toast.show("Explorarea merge și pe site. Intră în panoul online cu același cont.")
                    } else {
                        ExploreSync.cancel(context)
                        toast.show("Oprit. Explorarea rămâne doar în telefon.")
                    }
                }
            },
            territory = TerritorySummary(
                cells = cells.size, percentLabel = percentLabel, rank = rank,
                walk = modeCounts.walk, run = modeCounts.run, ride = modeCounts.ride
            )
        )
    }
}

/**
 * Cardul locului selectat (al meu sau recomandat), jos, lat, peste hartă.
 * La recomandări, „Pe hartă” (`onCenter`) aduce camera pe loc și închide cardul (contractul, §2.5).
 */
@Composable
private fun PlaceCardOnMap(
    sel: PlaceSel,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onNavigate: (Double, Double, String) -> Unit,
    onCenter: (Double, Double) -> Unit
) {
    ForjaCard(
        Modifier.fillMaxWidth(),
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
                            // Vizitele doar de la 2 în sus: locurile de dinainte de numărare au visits = 1 din migrare, nu din fapte.
                            (if (p.visits >= 2) "${visitsLabel(p.visits)} · ai stat " else "Ai stat ") +
                                "${stayLabel(p.stayMs)} · ultima dată ${Fmt.freshness(p.lastAt)}",
                            style = BodySmall.copy(color = TextSecondary)
                        )
                    }
                    Text("închide", style = BodyTiny.copy(color = TextDim), modifier = Modifier.pressable(onClose))
                }
                Spacer(Modifier.height(6.dp))
                StarRow(stars = p.stars, size = 16.dp, tint = PlaceAmber, onPick = null)
                if (p.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(p.note, style = BodySmall.copy(color = TextSecondary))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    PrimaryButton(text = "Editează", small = true, onClick = onEdit, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Navighează", onClick = { onNavigate(p.lat, p.lng, p.name) }, modifier = Modifier.weight(1f))
                }
            }
            is PlaceSel.Rec -> {
                val r = sel.r
                val owner = r.ownerName.split(' ').first()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(SleepRem))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name.ifBlank { "Loc recomandat" }, style = BodyStrong.copy(fontSize = 15.sp))
                        Text(
                            "Recomandat de ${r.ownerName} · ${Fmt.freshness(r.at)}" +
                                (if (r.visits >= 2) " · ${visitsLabel(r.visits, owner)}" else ""),
                            style = BodySmall.copy(color = TextSecondary)
                        )
                    }
                    Text("închide", style = BodyTiny.copy(color = TextDim), modifier = Modifier.pressable(onClose))
                }
                Spacer(Modifier.height(6.dp))
                StarRow(stars = r.stars, size = 16.dp, tint = SleepRem, onPick = null)
                if (r.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(r.note, style = BodySmall.copy(color = TextSecondary))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    PrimaryButton(text = "Navighează", small = true, onClick = { onNavigate(r.lat, r.lng, r.name) }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Pe hartă", onClick = { onCenter(r.lat, r.lng); onClose() }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Cardul prietenului selectat: avatar, stare, distanță + timp pe jos, „acum N min” pentru familie, energie, butoane. */
@Composable
private fun FriendCardOnMap(
    f: Friend,
    myFix: MyFix?,
    energySent: Boolean,
    energyReceivedAt: Long,
    onClose: () -> Unit,
    onEnergy: () -> Unit,
    onNavigate: () -> Unit
) {
    ForjaCard(
        Modifier.fillMaxWidth(),
        fill = Color(0xF0121214),
        stroke = StrokeOnVideo
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FriendAvatar(friend = f, size = 44.dp, selected = true)
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
                val dist = if (myFix != null && f.lat != null && f.lng != null)
                    ExploreStats.distanceM(myFix.lat, myFix.lng, f.lat, f.lng) else null
                val moving = f.state == "run" || f.state == "walk" || f.state == "ride"
                val parts = ArrayList<String>(3)
                if (dist != null) parts.add("la ${ExploreStats.distanceLabel(dist)} · ${ExploreStats.walkEtaLabel(dist)}")
                if (moving) parts.add(String.format(Locale.ROOT, "%.1f", f.speedMps * 3.6).replace('.', ',') + " km/h")
                // Familia/fantoma: cât de veche e poziția, spus simplu — „acum 12 min”.
                parts.add(if (f.viaFamily || f.ghost) Fmt.freshness(f.locUpdatedAt) else "actualizat ${Fmt.freshness(f.locUpdatedAt)}")
                Text(parts.joinToString(" · "), style = BodySmall.copy(color = TextSecondary))
                // Ce ascultă acum: nota și „Titlu · Artist”, un singur rând.
                val listening = f.listening()
                if (listening != null) {
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MusicNote, contentDescription = "Ascultă", tint = EmberHot, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            listening,
                            style = BodySmall.copy(color = TextPrimary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val energyLine = buildString {
                    if (energyReceivedAt > 0) append("Ți-a trimis energie ${Fmt.freshness(energyReceivedAt)}.")
                    if (energySent) { if (isNotEmpty()) append(" "); append("I-ai trimis energie azi.") }
                }
                if (energyLine.isNotEmpty()) {
                    Text(energyLine, style = BodyTiny.copy(color = Accent2))
                }
            }
            Text("închide", style = BodyTiny.copy(color = TextDim), modifier = Modifier.pressable(onClose))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            PrimaryButton(text = "Energie", small = true, onClick = onEnergy, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            SecondaryButton("Navighează", onClick = onNavigate, modifier = Modifier.weight(1f))
        }
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

/** Chip mic, mono, peste hartă: teritorii, locuri, locul între prieteni, starea hărții. */
@Composable
private fun MapChip(text: String, color: Color = TextSecondary, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    val base = Modifier
        .clip(shape)
        .background(if (active) TabPillActive else Color(0xE6101114))
        .border(1.dp, if (active) Color(0x666F855A) else StrokeOnVideo, shape)
    val m = if (onClick != null) base.pressable(onClick) else base
    Box(m.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = monoLabel(9, 0.10f).copy(color = color), maxLines = 1)
    }
}

@Composable
private fun GoStat(label: String, value: String) {
    Column {
        Text(label, style = monoLabel(8, 0.12f))
        Text(value, style = heroNumeral(22))
    }
}
