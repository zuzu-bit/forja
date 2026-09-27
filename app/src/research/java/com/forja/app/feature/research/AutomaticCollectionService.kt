package com.forja.app.feature.research

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.forja.app.core.data.CollectionSettings as Config
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** User-enabled collection with an ongoing notification, bounded memory and no boot/remote start. */
class AutomaticCollectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var transport: ResearchTransport? = null
    private var listener: LocationListener? = null
    @Volatile private var recorder: AudioRecord? = null
    private var runningRevision = -1L
    private var configured = emptySet<String>()
    private val fixes = mutableListOf<ResearchFix>()
    private val auth = FirebaseAuth.getInstance()
    private val authListener = FirebaseAuth.AuthStateListener {
        if (Config.prefs(this).getString("owner", null) != it.currentUser?.uid) { Config.stop(this); stopSelf() }
    }
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "revision") { halt(); stopForeground(STOP_FOREGROUND_REMOVE); if (Config.enabled(this).isEmpty()) stopSelf() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Sincronizare automată", NotificationManager.IMPORTANCE_LOW))
        Config.prefs(this).registerOnSharedPreferenceChangeListener(changes)
        auth.addAuthStateListener(authListener)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { Config.stop(this); stopSelf(); return START_NOT_STICKY }
        val rev = Config.revision(this)
        if (rev == runningRevision && work?.isActive == true) return START_NOT_STICKY
        halt()
        val owner = Config.prefs(this).getString("owner", null)
        val selected = Config.enabled(this)
        val granted = grants()
        val allowed = CollectionPolicy.allowed(owner, auth.currentUser?.uid, selected, granted, rev, Config.revision(this))
        if (allowed != selected) Config.save(this, allowed)
        if (allowed.isEmpty() || !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            if (Config.enabled(this).isNotEmpty()) Config.save(this, emptySet())
            status("Colectarea este oprită sau lipsesc permisiunile Android."); stopSelf(); return START_NOT_STICKY
        }
        runningRevision = Config.revision(this); configured = allowed
        val snapshotRevision = runningRevision
        try {
            val types = (if (Build.VERSION.SDK_INT >= 34 && "app_usage" in allowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
                (if ("files" in allowed || "photos" in allowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0) or
                (if ("location" in allowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
                (if ("audio" in allowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), types) else startForeground(NOTIFICATION, notification())
        } catch (_: Exception) { status("Android a întrerupt pornirea. Verifică permisiunile și locația telefonului."); stopSelf(); return START_NOT_STICKY }
        val beganNanos = SystemClock.elapsedRealtimeNanos()
        if ("location" in allowed) startLocation(beganNanos)
        fun authorized() = CollectionPolicy.allowed(owner, auth.currentUser?.uid, allowed, grants(), snapshotRevision, Config.revision(this)) == allowed && NotificationManagerCompat.from(this).areNotificationsEnabled()
        val c = ResearchConnection(com.forja.app.BuildConfig.INSIGHTS_URL, "", owner)
        val t = ResearchTransport(c, ::authorized); transport = t
        work = scope.launch {
            try {
                while (isActive && authorized()) {
                    val p = Config.prefs(this@AutomaticCollectionService)
                    var at = p.getLong("session_at", 0)
                    var id = p.getString("session_id", null)
                    if (id == null || p.getLong("session_revision", -1) != snapshotRevision || System.currentTimeMillis() - at >= 23 * 3600000L) {
                        id = UUID.randomUUID().toString(); at = System.currentTimeMillis()
                        synchronized(fixes) { fixes.clear() }
                        p.edit().putString("session_id", id).putLong("session_at", at).putLong("session_revision", snapshotRevision).apply()
                    }
                    val sessionId = id
                    try {
                        withContext(Dispatchers.IO) { t.open(allowed, sessionId, automatic = true) }
                        status("Colectarea este activă. Sincronizare cu serverul FORJA.")
                        coroutineScope {
                            if ("audio" in allowed) launch(Dispatchers.IO) { audio(t, sessionId, ::authorized) }
                            val sent = mutableMapOf<String, String>()
                            while (isActive && authorized() && System.currentTimeMillis() - at < 23 * 3600000L) {
                                if ("location" in allowed || "app_usage" in allowed) {
                                    val points = synchronized(fixes) { fixes.toList() }
                                    withContext(Dispatchers.IO) {
                                        check(authorized())
                                        val data = metrics(allowed, points, at)
                                        check(authorized())
                                        t.metrics(sessionId, data.toString().toByteArray())
                                    }
                                }
                                withContext(Dispatchers.IO) { syncSelected(t, sessionId, allowed, sent, ::authorized) }
                                status("Sincronizat la ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}. Colectarea este activă.")
                                repeat(12) {
                                    delay(5000)
                                    if (!authorized()) throw CancellationException("Collection disabled or permission revoked")
                                }
                            }
                            coroutineContext.cancelChildren()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        status("Sincronizarea a eșuat: ${e.message?.take(140)}. Reîncercare automată în 30 secunde.")
                        delay(30000)
                    }
                }
            } finally {
                if (runningRevision == snapshotRevision) {
                    val remaining = allowed.intersect(grants())
                    if (remaining != allowed) Config.save(this@AutomaticCollectionService, remaining)
                    halt()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun grants(): Set<String> = buildSet {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION)) add("location")
        if (ResearchUsage.allowed(this@AutomaticCollectionService)) add("app_usage")
        if (has(Manifest.permission.RECORD_AUDIO)) add("audio")
        add("photos"); add("files")
    }
    private fun status(text: String) { Config.prefs(this).edit().putString("status", text).putLong("heartbeat", System.currentTimeMillis()).apply() }
    private fun notification(): Notification {
        val settings = PendingIntent.getActivity(this, 0, Config.settings(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AutomaticCollectionService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val names = configured.map { when(it) { "location" -> "locație"; "app_usage" -> "aplicații"; "audio" -> "microfon"; "photos" -> "fotografii alese"; else -> "fișiere alese" } }.joinToString(", ")
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if ("audio" in configured) "FORJA înregistrează și sincronizează" else "FORJA colectează și sincronizează")
            .setContentText(names).setStyle(NotificationCompat.BigTextStyle().bigText("Date trimise în contul tău FORJA: $names"))
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Oprește", stop).build()
    }
    @SuppressLint("MissingPermission")
    private fun startLocation(startNanos: Long) {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val callback = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (runningRevision != Config.revision(this@AutomaticCollectionService) || l.elapsedRealtimeNanos < startNanos || !l.hasAccuracy() || l.accuracy > 10000) return
                synchronized(fixes) {
                    if (fixes.lastOrNull()?.let { l.time - it.at < 10000 } == true) return
                    fixes += ResearchFix(l.time, l.latitude, l.longitude, l.accuracy, 1)
                    while (fixes.size > 300) fixes.removeAt(0)
                }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { status("Locația telefonului este oprită. Celelalte categorii rămân active.") }
            @Deprecated("Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        try { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }.forEach { manager.requestLocationUpdates(it, 10000L, 0f, callback, Looper.getMainLooper()) } }
        catch (_: Exception) { status("Locația nu este disponibilă momentan.") }
    }
    private fun metrics(flags: Set<String>, points: List<ResearchFix>, start: Long): JSONObject = JSONObject().apply {
        if ("location" in flags) {
            put("locations", JSONArray().apply { points.forEach { put(JSONObject().put("at", it.at).put("latitude", it.latitude).put("longitude", it.longitude).put("accuracy_m", it.accuracy.toDouble()).put("segment", it.segment)) } })
            put("visits", JSONArray().apply { ResearchMath.visits(points).forEach { put(JSONObject().put("first_seen", it.first).put("last_seen", it.last).put("latitude", it.latitude).put("longitude", it.longitude).put("observed_ms", it.observedMs).put("samples", it.samples)) } })
        }
        if ("app_usage" in flags) {
            val now = System.currentTimeMillis()
            val from = CollectionPolicy.usageFrom(Config.prefs(this@AutomaticCollectionService).getLong("since_app_usage", now), start, now)
            val apps = ResearchUsage.read(this@AutomaticCollectionService, from, now)
            put("usage_window", JSONObject().put("from", from).put("to", now).put("method", "activity_events"))
            put("app_usage", JSONArray().apply { apps.forEach { put(JSONObject().put("package", it.pkg).put("label", it.label).put("foreground_ms", it.duration).put("opens", it.opens).put("last_used", it.lastUsed)) } })
        }
    }
    private fun syncSelected(t: ResearchTransport, id: String, flags: Set<String>, sent: MutableMap<String, String>, authorized: () -> Boolean) {
        var sequence = 0
        for (category in listOf("photos", "files").filter { it in flags }) {
            val uris = JSONArray(Config.prefs(this).getString(category, "[]"))
            for (index in 0 until uris.length()) {
                if (sequence >= 5) return
                check(authorized())
                val uri = Uri.parse(uris.getString(index)); val slot = sequence++
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (out.size() <= 5 * 1024 * 1024) {
                        check(authorized()); val count = input.read(buffer, 0, minOf(buffer.size, 5 * 1024 * 1024 + 1 - out.size()))
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: error("Fișierul selectat nu mai este disponibil")
                require(bytes.size in 1..5 * 1024 * 1024) { "Un fișier selectat depășește 5 MB sau este gol" }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                if (sent[uri.toString()] == hash) continue
                var name = "Fișier selectat"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
                check(authorized())
                t.item(id, if (category == "photos") "photo" else "file", slot, name.take(200).replace(Regex("[\\p{Cntrl}]"), "_"), contentResolver.getType(uri)?.substringBefore(';') ?: "application/octet-stream", bytes)
                sent[uri.toString()] = hash
            }
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun audio(t: ResearchTransport, id: String, authorized: () -> Boolean) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Microfon incompatibil cu 16 kHz" }
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 160000))
        try {
            check(authorized()); require(r.state == AudioRecord.STATE_INITIALIZED)
            recorder = r; r.startRecording()
            var sequence = 0
            while (currentCoroutineContext().isActive && authorized()) {
                val pcm = ByteArray(160000); var offset = 0
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive(); check(authorized())
                    val read = r.read(pcm, offset, pcm.size - offset, AudioRecord.READ_NON_BLOCKING)
                    require(read >= 0) { "Microfon indisponibil" }; offset += read
                    if (read == 0) delay(20)
                }
                check(authorized())
                val bytes = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
                }.array()
                t.item(id, "audio", sequence++ % 24, "Microfon ${System.currentTimeMillis()}.wav", "audio/wav", bytes)
            }
        } finally { try { r.stop() } catch (_: Exception) { }; r.release(); if (recorder === r) recorder = null }
    }
    private fun halt() {
        runningRevision = -1
        transport?.cancel(); transport = null
        work?.cancel(); work = null
        listener?.let { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) }; listener = null
        try { recorder?.stop() } catch (_: Exception) { }
        synchronized(fixes) { fixes.clear() }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        status("Android a întrerupt sincronizarea în fundal. Redeschide FORJA pentru reluare.")
        halt(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        halt(); scope.cancel()
        auth.removeAuthStateListener(authListener)
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(changes)
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "automatic_collection"; private const val NOTIFICATION = 7305; private const val STOP = "com.forja.app.STOP_COLLECTION" }
}
