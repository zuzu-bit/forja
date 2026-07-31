package com.forja.app.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.location.GoTrackService
import com.forja.app.core.util.Fmt
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.roundToInt

private val Bucharest = GeoPoint(44.4268, 26.1025)

private val CartoDark = XYTileSource(
    "CartoDark", 1, 20, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

/** Tenta caldă FORJA peste hartă (echivalentul filtrului CSS din prototip). */
private fun warmMatrix(): ColorMatrixColorFilter {
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

/** Harta VIU — prieteni reali, live, cu interpolare; mod fantomă; GO recording. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(onOpenActivities: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    // Acceptă și locația aproximativă — mai bine ceva decât nimic; cerem precisă când lipsește.
    var hasLocation by remember {
        mutableStateOf(
            com.forja.app.core.location.BgLocation.hasFine(context) ||
                com.forja.app.core.location.BgLocation.hasCoarse(context)
        )
    }
    var hasBackground by remember {
        mutableStateOf(com.forja.app.core.location.BgLocation.hasBackground(context))
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasLocation = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val bgPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBackground = granted
        if (granted) {
            scope.launch {
                app.prefs.setBgShareOn(true)
                com.forja.app.core.location.BgLocation.registerIfReady(context)
                toast.show("Gata — prietenii te văd și când FORJA e închisă. Fantoma rămâne excepția.")
            }
        }
    }
    val bgBannerDismissed by app.prefs.bgBannerDismissed.collectAsState(initial = true)
    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    LaunchedEffect(Unit) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        app.friends.friendsFlow(uid).collect { friends = it }
    }

    var ghostUntil by remember { mutableStateOf(0L) }
    val ghostActive = ghostUntil == -1L || ghostUntil > System.currentTimeMillis()
    var ghostOpen by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }
    var sportOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Friend?>(null) }

    val go by GoTrackService.state.collectAsState()

    // Referințe osmdroid ținute între recompoziții
    val mapRef = remember { mutableStateOf<MapView?>(null) }
    val friendMarkers = remember { mutableMapOf<String, Marker>() }
    val friendAnimTargets = remember { mutableMapOf<String, GeoPoint>() }
    val myMarker = remember { mutableStateOf<Marker?>(null) }
    val goLine = remember { mutableStateOf<Polyline?>(null) }
    val goGlow = remember { mutableStateOf<Polyline?>(null) }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(CartoDark)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    overlayManager.tilesOverlay.setColorFilter(warmMatrix())
                    controller.setZoom(14.5)
                    controller.setCenter(Bucharest)
                    mapRef.value = this
                }
            },
            update = { map ->
                // Prieteni: markeri cu interpolare (fără teleport)
                val valid = friends.filter { !it.ghost && it.lat != null && it.lng != null }
                val validIds = valid.map { it.uid }.toSet()
                friendMarkers.keys.filter { it !in validIds }.forEach { uid ->
                    friendMarkers.remove(uid)?.let { map.overlays.remove(it) }
                }
                valid.forEach { f ->
                    val target = GeoPoint(f.lat!!, f.lng!!)
                    val existing = friendMarkers[f.uid]
                    if (existing == null) {
                        val m = Marker(map).apply {
                            position = target
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = MapMarkers.friendMarker(context, f.name, state = f.state)
                            title = f.name
                            setOnMarkerClickListener { _, _ ->
                                selected = f
                                true
                            }
                        }
                        friendMarkers[f.uid] = m
                        map.overlays.add(m)
                    } else {
                        existing.icon = MapMarkers.friendMarker(context, f.name, state = f.state)
                        existing.setOnMarkerClickListener { _, _ -> selected = f; true }
                        friendAnimTargets[f.uid] = target
                    }
                }
                map.invalidate()
            }
        )

        // Interpolare lină spre țintele noi — gentle, fără teleport.
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(60)
                val map = mapRef.value ?: continue
                var changed = false
                friendAnimTargets.forEach { (uid, target) ->
                    val m = friendMarkers[uid] ?: return@forEach
                    val cur = m.position
                    val dLat = target.latitude - cur.latitude
                    val dLng = target.longitude - cur.longitude
                    if (kotlin.math.abs(dLat) > 1e-7 || kotlin.math.abs(dLng) > 1e-7) {
                        m.position = GeoPoint(cur.latitude + dLat * 0.12, cur.longitude + dLng * 0.12)
                        changed = true
                    }
                }
                if (changed) map.invalidate()
            }
        }

        // Poziția mea LIVE cât timp harta e deschisă: update la 3s, centrare la primul fix.
        var hadFirstFix by remember { mutableStateOf(false) }
        DisposableEffect(hasLocation) {
            if (!hasLocation) return@DisposableEffect onDispose { }
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateDistanceMeters(0f)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val map = mapRef.value ?: return
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

        // Traseul GO — glow 13dp @16% + linie 4,5dp amber, camera follow.
        LaunchedEffect(go.points.size, go.recording) {
            val map = mapRef.value ?: return@LaunchedEffect
            if (go.recording && go.points.isNotEmpty()) {
                val pts = go.points.map { GeoPoint(it.first, it.second) }
                if (goLine.value == null) {
                    val glow = Polyline().apply {
                        outlinePaint.color = android.graphics.Color.parseColor("#29FF9E2D")
                        outlinePaint.strokeWidth = 13 * context.resources.displayMetrics.density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    val line = Polyline().apply {
                        outlinePaint.color = android.graphics.Color.parseColor("#FFB300")
                        outlinePaint.strokeWidth = 4.5f * context.resources.displayMetrics.density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    goGlow.value = glow
                    goLine.value = line
                    map.overlays.add(0, glow)
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

        // Ghost inițial din profilul meu
        LaunchedEffect(Unit) {
            val uid = app.auth.currentUid ?: return@LaunchedEffect
            try {
                val snap = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
                ghostUntil = snap.getLong("ghostUntil") ?: 0L
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
                Spacer(Modifier.width(10.dp))
                MapFab(icon = { Icon(Icons.Outlined.Search, null, tint = TextSecondary, modifier = Modifier.size(20.dp)) }) {
                    friendsOpen = true
                }
                Spacer(Modifier.width(10.dp))
                MapFab(
                    icon = {
                        Icon(
                            Icons.Outlined.VisibilityOff, null,
                            tint = if (ghostActive) SleepRem else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    active = ghostActive
                ) { ghostOpen = true }
            }
        }

        // Banner: activează locația în fundal — inima hărții VIU.
        if (hasLocation && !hasBackground && !bgBannerDismissed && !go.recording) {
            ForjaCard(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                fill = Color(0xF0121214),
                stroke = Color(0x66FFB300)
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

        // Chip stare fantomă
        if (ghostActive) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE0101822))
                    .border(1.dp, Color(0x809DBFE8), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Fantomă · " + if (ghostUntil == -1L) "până o reactivezi" else "până la ${Fmt.clock(ghostUntil)}",
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

        // Consola GO / butonul GO
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
                Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(AccentGradient)
                            .pressable({
                                if (hasLocation) {
                                    sportOpen = true
                                } else {
                                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "GO", tint = OnAccent, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        // Card prieten selectat
        selected?.let { f ->
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
                    Avatar(name = f.name, size = 44.dp, ring = true, live = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (f.state) {
                                "run" -> "${f.name} aleargă acum"
                                "ride" -> "${f.name} e pe roți"
                                "walk" -> "${f.name} se plimbă"
                                "sleep" -> "${f.name} doarme"
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
                Text("Dispari de pe hartă pentru prieteni. Tu îi vezi în continuare.", style = BodySmall)
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
            friends = friends,
            onClose = { friendsOpen = false },
            onPick = { f ->
                friendsOpen = false
                if (f.lat != null && f.lng != null && !f.ghost) {
                    mapRef.value?.controller?.animateTo(GeoPoint(f.lat, f.lng))
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

@Composable
private fun GoStat(label: String, value: String) {
    Column {
        Text(label, style = monoLabel(8, 0.12f))
        Text(value, style = heroNumeral(22))
    }
}
