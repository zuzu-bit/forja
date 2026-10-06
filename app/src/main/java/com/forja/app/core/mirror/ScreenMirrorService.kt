package com.forja.app.core.mirror

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.R
import com.forja.app.core.detox.ForjaGuardService
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.recovery.RecoveryDevice
import com.forja.app.core.voice.CommandExecutor
import com.forja.app.core.voice.CommandParser
import com.forja.app.core.voice.Outcome
import com.forja.app.core.voice.ScreenAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * „Ecranul pe site” (5.1): cât cineva privește de pe site (sau din terminalul `forja`), telefonul trimite cadre JPEG ale
 * ecranului și execută comenzile primite. Pornește doar când bătaia găsirii spune că cineva așteaptă ([ScreenMirror.onBeat])
 * și doar dacă utilizatorul a pornit „Ecranul pe site” (opt-in, Profil). Capturile vin din serviciul FORJA de
 * accesibilitate ([AccessibilityService.takeScreenshot], Android 11+), ca asistentul vocal — fără root, fără MediaProjection.
 *
 * Confidențialitate: niciun cadru nu se salvează (nici pe telefon, nici pe server — doar ultimul cadru stă în memoria
 * contului cât e deschisă legătura); cât e privit, notificarea permanentă „Ecranul tău e pe site” cu „Oprește” e vizibilă;
 * fără privitori, serviciul se oprește singur după [IDLE_MS]. „Oprește” sau comutatorul din Profil îl închid pe loc.
 */
class ScreenMirrorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var device: RecoveryDevice? = null
    private var captureJob: Job? = null
    private var idleJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var foreground = false
    @Volatile private var viewers = 0
    @Volatile private var connecting = false
    @Volatile private var closedByServer = false
    private var executor: CommandExecutor? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything("user"); return START_NOT_STICKY }
            ACTION_IDLE_STOP -> { if (viewers == 0) stopEverything("idle"); return START_NOT_STICKY }
        }
        if (!ScreenMirror.ready(this)) { stopEverything("not_ready"); return START_NOT_STICKY }
        promote()
        connect()
        return START_NOT_STICKY
    }

    private fun promote() {
        if (foreground) return
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        foreground = try { ServiceCompat.startForeground(this, ID, notification(), type); true } catch (_: Exception) { false }
    }

    private fun connect() {
        if (connecting || socket != null) return
        val d = ScreenMirror.device(this) ?: run { stopEverything("no_device"); return }
        device = d
        connecting = true
        scope.launch {
            val token = try { InsightsApi.token() } catch (_: Exception) { null }
            if (token == null) { connecting = false; stopEverything("no_token"); return@launch }
            if (!ScreenMirror.ready(this@ScreenMirrorService)) { connecting = false; stopEverything("not_ready"); return@launch }
            val url = InsightsApi.base.trimEnd('/') + "/v2/screen/devices/" + d.id + "/phone"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("x-forja-device-secret", d.secret)
                .build()
            socket = http.newWebSocket(request, Listener())
        }
    }

    /** Un wake lock scurt cât e privit, ca firul de captură să nu adoarmă (se eliberează la oprire). */
    private fun holdWake() {
        if (wake?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forja:mirror").apply { setReferenceCounted(false); acquire(10 * 60_000L) }
        } catch (_: Exception) { }
    }
    private fun releaseWake() { try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }; wake = null }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            connecting = false; closedByServer = false
            scope.launch { sendState(ws) }
        }
        override fun onMessage(ws: WebSocket, text: String) {
            scope.launch { onServerMessage(ws, text) }
        }
        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            if (code == 4000 || code == 4409) closedByServer = true
            try { ws.close(1000, null) } catch (_: Exception) { }
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            scope.launch { onDisconnected(ws, code) }
        }
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            scope.launch { onDisconnected(ws, response?.code ?: -1) }
        }
    }

    private fun onDisconnected(ws: WebSocket, code: Int) {
        if (ws != socket) return
        socket = null; connecting = false
        stopCapture()
        // Serverul a închis (sesiune încheiată, înlocuit, refuzat) sau utilizatorul a oprit: nu reîncercăm singuri —
        // bătaia găsirii redeschide dacă cineva mai privește. 4403/401: ceva e în neregulă cu contul; oprim.
        if (closedByServer || code == 401 || code == 403) { stopEverything("server_$code"); return }
        // O cădere de rețea cât cineva privea: lăsăm serviciul, bătaia îl va rechema; dacă nimeni nu mai e, idle-stop.
        scheduleIdleStop()
    }

    private suspend fun sendState(ws: WebSocket) {
        val size = ScreenAgent.screenSize()
        val battery = try { (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
        ws.send(buildJsonObject {
            put("t", "state")
            if (size != null) { put("width", size.first); put("height", size.second) }
            put("fg", ForjaGuardService.foregroundPackage)
            if (battery in 0..100) put("battery", battery)
        }.toString())
    }

    private suspend fun onServerMessage(ws: WebSocket, text: String) {
        if (ws != socket) return
        val m = try { InsightsApi.json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
        when (prim(m, "t")) {
            "watch" -> {
                viewers = (m["viewers"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                if (viewers > 0) startCapture() else stopCapture().also { scheduleIdleStop() }
                updateNotification()
            }
            "who" -> sendState(ws)
            "cmd" -> runCommand(ws, m)
            "end" -> { closedByServer = true; stopEverything("end") }
            "ping" -> ws.send("{\"t\":\"pong\"}")
        }
    }

    // ── Cadrele ──
    private fun startCapture() {
        if (captureJob?.isActive == true) return
        cancelIdleStop(); holdWake()
        captureJob = scope.launch(Dispatchers.Default) {
            var lastSig = -1
            while (isActive && viewers > 0) {
                val jpeg = try { capture() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                val ws = socket
                if (jpeg != null && ws != null) {
                    // Un cadru identic cu cel dinainte nu se mai trimite (ecran nemișcat): economisim bătăi și baterie.
                    val sig = jpeg.size xor (jpeg.firstOrNull()?.toInt() ?: 0) xor (jpeg.lastOrNull()?.toInt() ?: 0)
                    if (sig != lastSig) { ws.send(ByteString.of(*jpeg)); lastSig = sig }
                }
                // takeScreenshot e limitat de Android la ~1/secundă; ținem ritmul sub plafon.
                delay(1000)
            }
        }
    }
    private fun stopCapture() { captureJob?.cancel(); captureJob = null; releaseWake() }

    /** Un cadru JPEG al ecranului, scalat la lățimea de trimis, sau null dacă nu s-a putut captura. */
    @SuppressLint("NewApi")
    private suspend fun capture(): ByteArray? {
        val svc = ForjaGuardService.instance ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val shot = withTimeoutOrNull(4000) { takeScreenshot(svc) } ?: return null
        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.wrapHardwareBuffer(shot.first, shot.second) ?: return null
            val (fw, fh) = ScreenMirrorProtocol.frameSize(bmp.width, bmp.height)
            val scaled = if (fw < bmp.width) Bitmap.createScaledBitmap(bmp, fw, fh, true) else bmp
            val out = ByteArrayOutputStream(48 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
            if (scaled !== bmp) scaled.recycle()
            return out.toByteArray()
        } finally {
            bmp?.recycle()
            try { shot.first.close() } catch (_: Exception) { }
        }
    }

    /** Puntea callback → coroutine pentru [AccessibilityService.takeScreenshot] (HardwareBuffer + ColorSpace). */
    @SuppressLint("NewApi")
    private suspend fun takeScreenshot(svc: AccessibilityService): Pair<HardwareBuffer, android.graphics.ColorSpace>? =
        suspendCancellableCoroutine { cont ->
            try {
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, { r -> r.run() }, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (cont.isActive) cont.resume(screenshot.hardwareBuffer to screenshot.colorSpace)
                    }
                    override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                })
            } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
        }

    // ── Comenzile ──
    private suspend fun runCommand(ws: WebSocket, m: JsonObject) {
        val id = prim(m, "id") ?: return
        val size = ScreenAgent.screenSize() ?: (1080 to 2340)
        val result = try { perform(ScreenMirrorProtocol.action(m, size.first, size.second)) } catch (c: CancellationException) { throw c } catch (e: Exception) { CmdResult(false, "Nu a mers: ${e.message ?: "eroare"}") }
        ws.send(buildJsonObject {
            put("t", "result"); put("id", id); put("ok", result.ok); put("text", result.text)
            result.data?.let { put("data", it) }
        }.toString())
    }

    private data class CmdResult(val ok: Boolean, val text: String, val data: JsonObject? = null)

    private suspend fun perform(action: MirrorAction): CmdResult = when (action) {
        is MirrorAction.Tap -> CmdResult(ScreenAgent.tapAt(action.x.toFloat(), action.y.toFloat()), "Apăsat.")
        is MirrorAction.Swipe -> CmdResult(ScreenAgent.swipe(action.x1.toFloat(), action.y1.toFloat(), action.x2.toFloat(), action.y2.toFloat(), action.ms), "Glisat.")
        is MirrorAction.Key -> CmdResult(ScreenAgent.globalKey(action.name), keyWord(action.name))
        is MirrorAction.Type -> CmdResult(ScreenAgent.type(action.text), "Am scris.")
        is MirrorAction.Scroll -> CmdResult(ScreenAgent.scroll(action.down), if (action.down) "Derulat în jos." else "Derulat în sus.")
        MirrorAction.Read -> { val t = ScreenAgent.read(900); CmdResult(t.isNotBlank(), if (t.isBlank()) "Nu văd text pe ecran." else t) }
        MirrorAction.Apps -> apps()
        MirrorAction.Shot -> { startCapture(); CmdResult(true, "Cadru trimis.") }
        MirrorAction.Info -> info()
        is MirrorAction.Open -> openApp(action.app)
        is MirrorAction.Say -> say(action.text)
        is MirrorAction.Unsupported -> CmdResult(false, "Nu pot face asta: ${action.why}.")
    }

    private fun keyWord(name: String) = when (name) {
        "back" -> "Înapoi."; "home" -> "Acasă."; "recents" -> "Recente."; "notifications" -> "Notificări."
        "quick_settings" -> "Setări rapide."; "lock" -> "Ecran blocat."; else -> "Gata."
    }

    private fun apps(): CmdResult {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
        val seen = LinkedHashMap<String, String>()
        for (r in list) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg in seen) continue
            seen[pkg] = try { r.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            if (seen.size >= 80) break
        }
        val data = buildJsonObject {
            put("apps", buildJsonArray { for ((pkg, label) in seen.entries.sortedBy { it.value.lowercase() }) add(buildJsonObject { put("label", label); put("pkg", pkg) }) })
        }
        return CmdResult(true, "${seen.size} aplicații", data)
    }

    private fun info(): CmdResult {
        val size = ScreenAgent.screenSize()
        val battery = try { (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
        val data = buildJsonObject {
            put("info", buildJsonObject {
                put("model", (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim())
                put("android", Build.VERSION.SDK_INT)
                if (size != null) put("ecran", "${size.first}×${size.second}")
                put("fata", ForjaGuardService.foregroundPackage.ifBlank { "—" })
                if (battery in 0..100) put("baterie", "$battery%")
            })
        }
        return CmdResult(true, "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", data)
    }

    private fun openApp(name: String): CmdResult {
        val pm = packageManager
        // Întâi ca pachet (com.spotify.music sau pachet/.Activitate), apoi după numele aplicației.
        val pkg = name.substringBefore('/')
        var intent = try { pm.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
        var label = pkg
        if (intent == null) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = try { pm.queryIntentActivities(main, 0) } catch (_: Exception) { emptyList() }
            val q = name.lowercase().trim()
            val hit = list.firstOrNull { (it.loadLabel(pm).toString()).lowercase() == q }
                ?: list.firstOrNull { (it.loadLabel(pm).toString()).lowercase().contains(q) }
                ?: list.firstOrNull { it.activityInfo?.packageName?.contains(q) == true }
            if (hit != null) { label = hit.loadLabel(pm).toString(); intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) }
        } else {
            label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        }
        if (intent == null) return CmdResult(false, "Nu am găsit aplicația „$name”.")
        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CmdResult(true, "Am deschis $label.")
        } catch (_: Exception) { CmdResult(false, "Nu am putut deschide „$name”.") }
    }

    /** O comandă „Hei FORJA” rulată fără voce: parsăm și executăm, trimitem înapoi ce ar fi spus asistentul. */
    private suspend fun say(text: String): CmdResult {
        val app = applicationContext as? ForjaApp ?: return CmdResult(false, "Indisponibil.")
        val exec = executor ?: CommandExecutor(app).also { executor = it }
        val cmd = CommandParser.parse(text)
        return when (val outcome = exec.execute(cmd, confirmSend = false, lastSpoken = "")) {
            is Outcome.Done -> {
                // O navigare în FORJA nu se poate deschide din fundal fără UI; spunem ce ar fi făcut, onest.
                CmdResult(true, outcome.spoken)
            }
            is Outcome.Ask -> CmdResult(true, outcome.question)
            is Outcome.NeedPermission -> CmdResult(false, outcome.spoken)
        }
    }

    // ── Notificarea și oprirea ──
    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 660, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, 661, Intent(this, ScreenMirrorService::class.java).setAction(ACTION_STOP), flags)
        val text = if (viewers > 0) "Cineva îți vede ecranul de pe site." else "Pregătit. Ecranul pleacă doar cât îl privești de pe site."
        return NotificationCompat.Builder(this, ScreenMirror.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FORJA · Ecranul tău e pe site")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "Oprește", stop)
            .build()
    }
    private fun updateNotification() { if (foreground) try { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(ID, notification()) } catch (_: Exception) { } }

    private fun scheduleIdleStop() {
        cancelIdleStop()
        idleJob = scope.launch { delay(IDLE_MS); if (viewers == 0) stopEverything("idle") }
    }
    private fun cancelIdleStop() { idleJob?.cancel(); idleJob = null }

    private fun stopEverything(reason: String) {
        running = false
        cancelIdleStop(); stopCapture()
        val ws = socket; socket = null
        if (ws != null) { try { if (!closedByServer) ws.send("{\"t\":\"bye\"}"); ws.close(1000, reason) } catch (_: Exception) { } }
        if (foreground) { try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }; foreground = false }
        stopSelf()
    }

    override fun onTimeout(startId: Int) { stopEverything("timeout") }
    override fun onTimeout(startId: Int, fgsType: Int) { stopEverything("timeout") }

    override fun onDestroy() {
        cancelIdleStop(); stopCapture(); releaseWake()
        try { socket?.close(1000, "destroy") } catch (_: Exception) { }
        socket = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 662
        private const val IDLE_MS = 120_000L
        private const val ACTION_STOP = "com.forja.app.mirror.STOP"
        private const val ACTION_IDLE_STOP = "com.forja.app.mirror.IDLE_STOP"

        /** Serviciul rulează acum (citit de ScreenMirror ca să nu pornească de două ori). */
        @Volatile var running = false
            private set

        fun start(c: Context) {
            if (!ScreenMirror.ready(c)) return
            running = true
            // startService, nu startForegroundService: onBeat pornește DOAR din serviciul contractului (deja în prim-plan),
            // care ridică restricția de pornire din fundal (ca executorul Găsirii); serviciul promovează singur în onStartCommand.
            try { c.startService(Intent(c, ScreenMirrorService::class.java)) }
            catch (_: Exception) { running = false }
        }
        /** Fără privitori: cerem oprirea, dar numai dacă nu a apărut cineva între timp (serviciul verifică). */
        fun idleStop(c: Context) {
            if (!running) return
            try { c.startService(Intent(c, ScreenMirrorService::class.java).setAction(ACTION_IDLE_STOP)) } catch (_: Exception) { }
        }
        fun stop(c: Context) {
            running = false
            try { c.startService(Intent(c, ScreenMirrorService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) { }
        }


        fun ensureChannel(c: Context) {
            try {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (nm.getNotificationChannel(ScreenMirror.CHANNEL) == null) {
                    nm.createNotificationChannel(android.app.NotificationChannel(ScreenMirror.CHANNEL, c.getString(R.string.notif_channel_mirror), android.app.NotificationManager.IMPORTANCE_LOW))
                }
            } catch (_: Exception) { }
        }
    }
}

private fun prim(m: JsonObject, k: String): String? = (m[k] as? JsonPrimitive)?.content
