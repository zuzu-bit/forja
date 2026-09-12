package com.forja.app.feature.research

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.forja.app.MainActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF101A18)
private val Panel = Color(0xFF1A2824)
private val Mint = Color(0xFFB8E6BE)
private val Muted = Color(0xFFAABAB2)
private data class Picked(val uri: Uri, val name: String, val type: String, val kind: String, val bytes: ByteArray)
private data class SentSession(val id: String, val connection: ResearchConnection, val label: String)

class ResearchExportActivity : ComponentActivity() {
    private var page by mutableStateOf("home")
    private var notice by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var origin by mutableStateOf("http://10.0.2.2:8787")
    private var token by mutableStateOf("")
    private var connected by mutableStateOf(false)
    private var locationOn by mutableStateOf(false)
    private var audioOn by mutableStateOf(false)
    private var audioConfirm by mutableStateOf(false)
    private var audioSeconds by mutableStateOf(0)
    private var audioClips by mutableStateOf(0)
    private var agree by mutableStateOf(false)
    private var technical by mutableStateOf(false)
    private var usageLoaded by mutableStateOf(false)
    private var usageFrom = 0L
    private var usageTo = 0L
    private val fixes = mutableStateListOf<ResearchFix>()
    private val apps = mutableStateListOf<ResearchAppUse>()
    private val picked = mutableStateListOf<Picked>()
    private val receipts = mutableStateListOf<SentSession>()
    private var locationListener: LocationListener? = null
    private var locationTimeout: Job? = null
    private var locationSegment = 0
    private var audioJob: Job? = null
    private var audioTimeout: Job? = null
    private var audioTransport: ResearchTransport? = null
    @Volatile private var recorder: AudioRecord? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notice = if (it.values.any { granted -> granted }) "Location access is ready. Tap Start location session." else "Location access was not granted."
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notice = if (it) "Microphone access is ready. Tap Start live audio." else "Microphone access was not granted."
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { loadPicked(it, "file") }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { loadPicked(it, "photo") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        origin = getSharedPreferences("research_connection", MODE_PRIVATE).getString("origin", origin) ?: origin
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Color(0xFFF1F5F0), onSurfaceVariant = Muted)) {
                ResearchScreen()
            }
        }
    }
    override fun onPause() { stopLocation(); stopAudio(); super.onPause() }
    private fun protect() { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    private fun connection(): ResearchConnection = ResearchConnection.parse(origin, token)
    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "This action could not be completed." }
            finally { busy = false }
        }
    }
    private fun review() { stopLocation(); stopAudio(); agree = false; technical = false; page = "review" }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationOn || busy) return
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) { notice = "Turn on Location in Android settings, then start a session."; return }
        protect(); val startNanos = SystemClock.elapsedRealtimeNanos(); locationSegment++
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (!locationOn || l.elapsedRealtimeNanos < startNanos || !l.hasAccuracy()) return
                if (fixes.lastOrNull()?.let { l.time <= it.at } == true) return
                fixes.add(ResearchFix(l.time, l.latitude, l.longitude, l.accuracy, locationSegment))
                if (fixes.size >= 300) { stopLocation(); notice = "Session stopped at 300 location samples. Review your data." }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { notice = "Location provider disabled. There may be gaps in your session." }
            @Deprecated("Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            locationListener = listener; locationOn = true
            providers.forEach { manager.requestLocationUpdates(it, 10000L, 0f, listener, Looper.getMainLooper()) }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            notice = "Location session started. Waiting for a fresh position."
            locationTimeout = lifecycleScope.launch { delay(15 * 60 * 1000L); stopLocation(); notice = "The 15-minute location session has ended." }
        } catch (e: Exception) { stopLocation(); notice = e.message ?: "Location could not start." }
    }
    private fun stopLocation() {
        locationListener?.let { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) }
        locationListener = null; locationTimeout?.cancel(); locationTimeout = null; locationOn = false
        if (!audioOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun readUsage() {
        if (!ResearchUsage.allowed(this)) {
            notice = "Enable Usage Access for FORJA Research, return here, then tap Read app activity."
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))); return
        }
        protect()
        task {
            val to = System.currentTimeMillis(); val from = to - 86400000L
            val values = withContext(Dispatchers.IO) { ResearchUsage.read(this@ResearchExportActivity, from, to) }
            apps.clear(); apps.addAll(values); usageFrom = from; usageTo = to; usageLoaded = true
            notice = "Read ${values.size} apps from available activity events. Nothing sent."
        }
    }
    private fun loadPicked(uris: List<Uri>, kind: String) {
        if (uris.isEmpty()) return
        protect()
        task {
            val remaining = 5 - picked.size
            require(remaining > 0) { "You can select up to five items. Remove an item first." }
            val values = withContext(Dispatchers.IO) {
                uris.take(remaining).map { uri ->
                    var name = "Selected item"
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (output.size() <= 5 * 1024 * 1024) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 5 * 1024 * 1024 + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Could not open $name")
                    require(bytes.size in 1..(5 * 1024 * 1024)) { "$name must be between 1 byte and 5 MiB." }
                    Picked(uri, name.take(200).replace(Regex("[\\p{Cntrl}]"), "_"), contentResolver.getType(uri)?.substringBefore(';') ?: "application/octet-stream", kind, bytes)
                }
            }
            picked.addAll(values); notice = "${values.size} selected items added. Nothing sent."
        }
    }
    private fun metrics(): JSONObject = JSONObject().apply {
        if (fixes.isNotEmpty()) {
            put("locations", JSONArray().apply { fixes.forEach { put(JSONObject().put("at", it.at).put("latitude", it.latitude).put("longitude", it.longitude).put("accuracy_m", it.accuracy.toDouble()).put("segment", it.segment)) } })
            put("visits", JSONArray().apply { ResearchMath.visits(fixes).forEach { put(JSONObject().put("first_seen", it.first).put("last_seen", it.last).put("latitude", it.latitude).put("longitude", it.longitude).put("observed_ms", it.observedMs).put("samples", it.samples)) } })
        }
        if (usageLoaded) {
            put("usage_window", JSONObject().put("from", usageFrom).put("to", usageTo).put("method", "activity_events"))
            put("app_usage", JSONArray().apply { apps.forEach { put(JSONObject().put("package", it.pkg).put("label", it.label).put("foreground_ms", it.duration).put("opens", it.opens).put("last_used", it.lastUsed)) } })
        }
    }
    private fun sendData() {
        if (!agree || busy) return
        val connection = try { connection() } catch (e: Exception) { notice = e.message ?: "Add a server first."; return }
        val flags = buildSet { if (fixes.isNotEmpty()) add("location"); if (usageLoaded) add("app_usage"); if (picked.any { it.kind == "file" }) add("files"); if (picked.any { it.kind == "photo" }) add("photos") }
        if (flags.isEmpty()) return
        val data = metrics().toString(2).toByteArray(); val items = picked.toList()
        val id = UUID.randomUUID().toString(); receipts.add(SentSession(id, connection, "Phone data"))
        agree = false; protect()
        task {
            notice = "Sending your reviewed data…"
            withContext(Dispatchers.IO) {
                val transport = ResearchTransport(connection); transport.open(flags, id)
                if ("location" in flags || "app_usage" in flags) transport.metrics(id, data)
                items.forEachIndexed { i, item -> transport.item(id, item.kind, i, item.name, item.type, item.bytes) }
            }
            notice = "Sent and verified. You can delete this session from Uploads."
            fixes.clear(); apps.clear(); usageLoaded = false; picked.clear(); page = "uploads"
        }
    }
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        audioConfirm = false
        if (!has(Manifest.permission.RECORD_AUDIO)) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        val c = try { connection() } catch (e: Exception) { notice = e.message ?: "Add a server first."; return }
        if (audioOn || audioJob?.isCompleted == false || busy) return
        protect(); audioOn = true; audioSeconds = 0; audioClips = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = UUID.randomUUID().toString(); receipts.add(SentSession(id, c, "Live microphone"))
        val transport = ResearchTransport(c); audioTransport = transport
        audioTimeout = lifecycleScope.launch { delay(120000); stopAudio(); notice = "The two-minute audio session has ended." }
        audioJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    transport.open(setOf("audio"), id)
                    val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    require(min > 0) { "This microphone cannot record at 16 kHz." }
                    val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 160000))
                    try {
                        require(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
                        withContext(Dispatchers.Main) {
                            check(audioOn && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "Keep the audio screen open to start recording." }
                            r.startRecording(); recorder = r
                        }
                        val deadline = SystemClock.elapsedRealtime() + 120000
                        for (sequence in 0 until 24) {
                            ensureActive(); val pcm = ByteArray(160000); var offset = 0
                            while (offset < pcm.size && SystemClock.elapsedRealtime() < deadline) {
                                ensureActive()
                                val read = r.read(pcm, offset, pcm.size - offset, AudioRecord.READ_NON_BLOCKING)
                                require(read >= 0) { "Microphone stopped or became unavailable." }
                                offset += read
                                if (read == 0) delay(20)
                            }
                            if (offset == 0) break
                            val bytes = wav(pcm.copyOf(offset - offset % 2))
                            transport.item(id, "audio", sequence, "Microphone clip ${sequence + 1}.wav", "audio/wav", bytes)
                            withContext(Dispatchers.Main) { audioSeconds += (offset / 32000); audioClips++; notice = "Live audio: $audioClips clips received and verified." }
                            if (SystemClock.elapsedRealtime() >= deadline) break
                        }
                    } finally { try { r.stop() } catch (_: Exception) { }; r.release(); recorder = null }
                }
                notice = "Audio session finished. Open Uploads to delete its clips."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Audio stopped: ${e.message}. Already received clips remain in Uploads." }
            finally { audioTimeout?.cancel(); audioTimeout = null; audioOn = false; audioTransport = null; if (!locationOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    private fun stopAudio() {
        if (!audioOn && audioJob?.isActive != true) return
        audioTimeout?.cancel(); audioTimeout = null
        audioOn = false; try { recorder?.stop() } catch (_: Exception) { }
        audioTransport?.cancel(); audioJob?.cancel()
        if (!locationOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()

    @Composable private fun ResearchScreen() {
        BackHandler(page != "home") { if (!busy) { audioConfirm = false; page = "home" } }
        val hasData = fixes.isNotEmpty() || usageLoaded || picked.isNotEmpty()
        Scaffold(containerColor = Ink, bottomBar = {
            if (page == "home" && hasData) Surface(color = Ink) {
                Button(onClick = ::review, enabled = !busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Review & send", modifier = Modifier.padding(8.dp), fontSize = 16.sp); Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.ArrowForward, null)
                }
            }
        }) { inset ->
            Column(Modifier.fillMaxSize().padding(inset).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!busy) { if (page == "home") finish() else page = "home" } }) { Icon(Icons.Outlined.ArrowBack, "Înapoi") }
                    Column(Modifier.weight(1f)) { Text("FORJA", letterSpacing = 4.sp, fontWeight = FontWeight.Bold, color = Mint); Text("DATELE MELE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp) }
                    IconButton(onClick = { if (!busy) page = "uploads" }) { Icon(Icons.Outlined.CloudDone, "Uploads") }
                    IconButton(onClick = { if (!busy) { protect(); page = "settings" } }) { Icon(Icons.Outlined.Settings, "Server settings") }
                }
                if (audioOn || locationOn) Surface(color = Color(0xFF49302C), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (audioOn) Icons.Outlined.Mic else Icons.Outlined.MyLocation, null, tint = Color(0xFFFFB6A5))
                        Text(if (audioOn) "Microphone LIVE · $audioClips clips sent" else "Location session running", Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 13.sp)
                        TextButton(onClick = { stopAudio(); stopLocation(); notice = "Collection stopped." }) { Text("STOP", color = Color.White) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                when (page) {
                    "home" -> Home()
                    "location" -> LocationPage()
                    "apps" -> AppsPage()
                    "files" -> FilesPage()
                    "audio" -> AudioPage()
                    "settings" -> SettingsPage()
                    "review" -> ReviewPage()
                    "uploads" -> UploadsPage()
                }
                if (notice.isNotBlank()) Surface(color = Panel, shape = RoundedCornerShape(14.dp)) { Text(notice, Modifier.fillMaxWidth().padding(14.dp), fontSize = 13.sp, color = Mint) }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (audioConfirm) AlertDialog(onDismissRequest = { audioConfirm = false }, icon = { Icon(Icons.Outlined.Mic, null) },
            title = { Text("Send live microphone audio?") }, text = { Text("Audio will go to $origin in short clips for up to two minutes. Keep this screen open. Stop ends collection; you can delete received clips in Uploads.") },
            confirmButton = { TextButton(onClick = ::startAudio) { Text("Start live audio") } }, dismissButton = { TextButton(onClick = { audioConfirm = false }) { Text("Cancel") } })
    }
    @Composable private fun Home() {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF314D3D), Color(0xFF1A2D26))), RoundedCornerShape(26.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).background(Mint, CircleShape)); Text(if (locationOn || audioOn) "SESSION ACTIVE" else "YOU CONTROL COLLECTION", Modifier.padding(start = 8.dp), fontSize = 10.sp, letterSpacing = 1.sp, color = Mint) }
            Text("Explore your\nphone data.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
            Text("Start a measurement. See what it contains. Choose what reaches your test server.", color = Color(0xFFCFDCD2), fontSize = 14.sp, lineHeight = 21.sp)
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { protect(); page = "settings" }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Dns, null, tint = Mint, modifier = Modifier.size(20.dp)); Text(if (connected) "Test server connected" else "Connect your test server", Modifier.weight(1f).padding(start = 10.dp), color = Muted, fontSize = 13.sp); Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
        }
        Text("What do you want to explore?", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature("Location\n& places", "Route and time at each stop", Icons.Outlined.LocationOn, "location", Modifier.weight(1f), if (fixes.isEmpty()) "Start a session" else "${fixes.size} samples")
            Feature("App\nactivity", "Apps used and foreground time", Icons.Outlined.BarChart, "apps", Modifier.weight(1f), if (usageLoaded) "${apps.size} apps" else "Last 24 hours")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature("Files\n& photos", "Only the items you select", Icons.Outlined.PhotoLibrary, "files", Modifier.weight(1f), "${picked.size} selected")
            Feature("Live\naudio", "Microphone clips to your server", Icons.Outlined.GraphicEq, "audio", Modifier.weight(1f), if (audioOn) "Live now" else "Start / stop")
        }
        Text("Location and microphone stop when you leave this screen or lock the phone.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = { startActivity(Intent(this@ResearchExportActivity, HealthExportActivity::class.java)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Health summaries · advanced export", color = Muted, fontSize = 12.sp) }
    }
    @Composable private fun Feature(title: String, subtitle: String, icon: ImageVector, destination: String, modifier: Modifier, footer: String) {
        Column(modifier.height(222.dp).background(Panel, RoundedCornerShape(22.dp)).border(1.dp, Color(0xFF2B3B34), RoundedCornerShape(22.dp)).clickable(enabled = !busy) { notice = ""; page = destination }.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).background(Color(0xFF2D4034), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Mint, modifier = Modifier.size(22.dp)) }
            Text(title, fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            Spacer(Modifier.weight(1f)); Text(footer, color = Mint, fontSize = 11.sp)
        }
    }
    @Composable private fun Heading(title: String, detail: String) { Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold); Text(detail, color = Muted, fontSize = 14.sp, lineHeight = 21.sp) }
    @Composable private fun Action(title: String, onClick: () -> Unit, enabled: Boolean = true) { Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(title, Modifier.padding(7.dp)) } }
    @Composable private fun Line(title: String, detail: String) { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) } }
    @Composable private fun LocationPage() {
        Heading("Location & places", "Record a route while this screen stays open. Then see the observed time around each stop. Sessions last up to 15 minutes.")
        Action(if (locationOn) "Stop location session" else "Start location session", { if (locationOn) stopLocation() else startLocation() }, fixes.size < 300 || locationOn)
        Line("${fixes.size} location samples", "Includes coordinates, timestamp and estimated accuracy. Nothing is uploaded during collection.")
        val visits = ResearchMath.visits(fixes)
        if (fixes.isEmpty()) Line("Your places will appear here", "Start a session on your test phone and move between locations. This app cannot retrieve past location history.")
        visits.takeLast(12).forEachIndexed { i, v -> Line("Stop ${visits.size.coerceAtMost(12).let { visits.size - it } + i + 1} · ${duration(v.observedMs)} observed", "${coordinates(v.latitude, v.longitude)}\n${time(v.first)} → ${time(v.last)} · ${v.samples} samples") }
        Text("A stop groups samples within 75 m. Gaps over two minutes and accuracy worse than 100 m are excluded from dwell estimates. These are observations, not exact arrival or departure times.", color = Muted, fontSize = 12.sp)
        if (fixes.isNotEmpty()) { Action("Review location data", ::review); TextButton(onClick = { stopLocation(); fixes.clear() }, enabled = !busy) { Text("Clear location data") } }
    }
    @Composable private fun AppsPage() {
        Heading("App activity", "See available foreground activity from the last 24 hours. Android asks you to enable Usage Access for this research copy.")
        Action("Read app activity", ::readUsage)
        Text("Durations are estimates from Android activity events. Missing events can undercount time; split-screen apps can overlap. Foreground time does not reveal what you typed or viewed.", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        if (!usageLoaded) Line("No app activity read yet", "Your list will show app names and foreground duration.")
        if (usageLoaded && apps.isEmpty()) Line("No activity events available", "Android returned no completed foreground intervals for this window.")
        apps.forEach { app -> Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row { Text(app.label, Modifier.weight(1f), fontSize = 14.sp); Text(duration(app.duration), color = Mint, fontSize = 13.sp) }
            LinearProgressIndicator(progress = { (app.duration.toFloat() / (apps.firstOrNull()?.duration ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Mint)
            Text(app.pkg, color = Muted, fontSize = 10.sp)
        } }
        if (usageLoaded) { Action("Review app activity", ::review); TextButton(onClick = { apps.clear(); usageLoaded = false }, enabled = !busy) { Text("Clear app activity") } }
    }
    @Composable private fun FilesPage() {
        Heading("Files & photos", "Choose up to five items, each up to 5 MiB. You decide exactly which files can be sent.")
        Action("Choose photos", { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Choose files") }
        Text("Original file contents are uploaded, including embedded metadata such as photo location tags. Nothing is scanned or selected automatically.", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        PickedList()
        if (picked.isNotEmpty()) Action("Review selected items", ::review)
    }
    @Composable private fun PickedList() {
        picked.toList().forEach { item -> Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.kind == "photo") AsyncImage(model = item.uri, contentDescription = "Selected photo", modifier = Modifier.size(54.dp)) else Icon(Icons.Outlined.InsertDriveFile, null, tint = Mint, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(item.name, fontSize = 13.sp, maxLines = 2); Text("${item.bytes.size / 1024} KiB · ${item.kind}", fontSize = 11.sp, color = Muted) }
            IconButton(onClick = { picked.remove(item); agree = false }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove ${item.name}") }
        } }
    }
    @Composable private fun AudioPage() {
        Heading("Live audio", "Send your test phone’s microphone to your server in short clips. The session stops when you leave the app, lock the screen, or tap Stop.")
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.GraphicEq, null, modifier = Modifier.size(76.dp), tint = if (audioOn) Color(0xFFFFB6A5) else Mint)
            Text(if (audioOn) "MICROPHONE LIVE" else "Microphone is off", fontWeight = FontWeight.SemiBold)
            Text("$audioClips clips received · ${audioSeconds}s captured", color = Muted, fontSize = 13.sp)
        }
        Action(if (audioOn) "Stop live audio" else "Start live audio", { if (audioOn) stopAudio() else audioConfirm = true })
        Line("Where audio goes", origin)
        Text("Up to two minutes per session. Clips contain about five seconds of microphone audio, so listening has a short delay. This does not capture phone calls or other apps’ internal audio. Slow connections can cause gaps.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = { page = "uploads" }, enabled = !busy) { Text("View or delete uploaded sessions") }
    }
    @Composable private fun SettingsPage() {
        Heading("Your test server", "Connect the research copy to the receiver you control. Data is kept there for up to 24 hours and can be deleted from Uploads.")
        OutlinedTextField(value = origin, onValueChange = { origin = it; connected = false; agree = false }, label = { Text("Server address") }, placeholder = { Text("https://your-test-server.example") }, singleLine = true, enabled = !busy && !audioOn, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { token = it; connected = false; agree = false }, label = { Text("Pairing token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy && !audioOn, modifier = Modifier.fillMaxWidth())
        Action("Test connection", { task { val c = connection(); withContext(Dispatchers.IO) { ResearchTransport(c).test() }; connected = true; getSharedPreferences("research_connection", MODE_PRIVATE).edit().putString("origin", c.origin).apply(); notice = "Connected. No new data was uploaded." } })
        Text("The token is kept only while this app screen remains alive. The receiver and connection guide are included with the research source code.", color = Muted, fontSize = 12.sp)
    }
    @Composable private fun ReviewPage() {
        Heading("Review before sending", "Collection is stopped. This is the data in your current selection.")
        if (fixes.isNotEmpty()) Line("Location & places", "${fixes.size} coordinates with timestamps and accuracy; ${ResearchMath.visits(fixes).size} observed stops.")
        if (usageLoaded) Line("App activity", "${apps.size} apps · ${time(usageFrom)} to ${time(usageTo)}. Includes package names, labels, foreground duration and activity-start counts.")
        PickedList()
        Line("Destination", origin)
        TextButton(onClick = { technical = !technical }) { Text(if (technical) "Hide exact metric fields" else "Show exact metric fields", fontSize = 12.sp) }
        if (technical) Text(metrics().toString(2), fontSize = 11.sp, color = Muted)
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { agree = !agree }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agree, onCheckedChange = { agree = it }, enabled = !busy)
            Text("Send the reviewed data and selected file contents to this test server.", fontSize = 13.sp)
        }
        Action("Send reviewed data", ::sendData, agree && (fixes.isNotEmpty() || usageLoaded || picked.isNotEmpty()))
        Text("Received data expires after 24 hours. If sending is interrupted, some items may already be on the server; use Uploads to delete the session.", color = Muted, fontSize = 12.sp)
    }
    @Composable private fun UploadsPage() {
        Heading("Your uploads", "Each session can be deleted from the test server. An interrupted upload may contain only some of its selected items.")
        if (receipts.isEmpty()) Line("No sessions in this app visit", "A session appears here when sending starts. You can also inspect older sessions in the server viewer.")
        receipts.toList().reversed().forEach { r -> Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(r.label, fontWeight = FontWeight.SemiBold); Text(r.connection.origin, color = Muted, fontSize = 12.sp); Text(r.id, color = Muted, fontSize = 10.sp)
            OutlinedButton(onClick = { stopAudio(); task { withContext(Dispatchers.IO) { ResearchTransport(r.connection).delete(r.id) }; receipts.remove(r); notice = "Session deleted." } }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(17.dp)); Text("Delete session", Modifier.padding(start = 7.dp)) }
        } }
    }
    private fun duration(ms: Long): String = if (ms < 60000) "${ms / 1000}s" else if (ms < 3600000) "${ms / 60000} min" else "${ms / 3600000}h ${(ms / 60000) % 60}m"
    private fun coordinates(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    private fun time(at: Long) = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
}
