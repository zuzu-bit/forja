package com.forja.app.core.recovery

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forja.app.MainActivity
import com.forja.app.core.network.InsightsFailure
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Serviciul vizibil al găsirii: armat de proprietar, întreabă panoul la 30 s dacă există o comandă.
 * GPS-ul pornește DOAR pentru o comandă în curs (5/15/30 min) și se oprește singur la termen.
 * Android 10+: tip de serviciu LOCATION, pornit prin ServiceCompat în try/catch.
 */
class LostPhoneService : Service(), LocationListener {

    companion object {
        @Volatile var running = false
            private set
        internal var current: LostPhoneService? = null
        private const val ID = 627
        const val ACTION_DISABLE = "com.forja.app.recovery.DISABLE"
        const val ACTION_STOP_SEARCH = "com.forja.app.recovery.STOP_SEARCH"
        private const val READY = "Pregătit pentru cererile tale din panou · GPS oprit"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var d: RecoveryDevice? = null
    private var command: JsonObject? = null
    private var loop: Job? = null
    private var deadline: Job? = null
    private var sending: Job? = null
    private var lastSend = 0L
    private var lastFixElapsed = 0L
    private var startedElapsed = 0L
    private val registered = mutableSetOf<String>()
    private var noticeText = ""
    private lateinit var manager: LocationManager

    // Alt cont sau ieșire din cont → înrolarea nu mai e a acestui proprietar: ne oprim.
    private val auth = FirebaseAuth.AuthStateListener {
        val owner = d
        if (owner != null && LostPhoneRecovery.owner(this) != owner.owner) {
            LostPhoneRecovery.clear(this)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        FirebaseAuth.getInstance().addAuthStateListener(auth)
        current = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                LostPhoneRecovery.disable(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SEARCH -> {
                LostPhoneRecovery.stopSearch(this)
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
        if (loop != null) return START_STICKY

        val owner = LostPhoneRecovery.device(this)
        d = owner
        if (owner == null || !LostPhoneRecovery.exactPermission(this) || !LostPhoneRecovery.notices(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(LostPhoneRecovery.CHANNEL, "Găsirea telefonului", NotificationManager.IMPORTANCE_LOW)
            )
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, ID, notification(READY), type)
            running = true
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        loop = scope.launch {
            while (isActive && LostPhoneRecovery.device(this@LostPhoneService) == owner) {
                try {
                    if (!LostPhoneRecovery.exactPermission(this@LostPhoneService) || !LostPhoneRecovery.notices(this@LostPhoneService)) {
                        LostPhoneRecovery.disable(this@LostPhoneService)
                        break
                    }
                    val status = when {
                        !locationEnabled() -> "location_off"
                        command != null -> "locating"
                        else -> "ready"
                    }
                    val result = withTimeout(20_000) {
                        LostPhoneRecovery.call(
                            this@LostPhoneService, owner, "poll",
                            buildJsonObject { put("secret", owner.secret); put("status", status) }
                        )
                    }
                    val next = result["command"] as? JsonObject
                    val nextId = next?.str("id")
                    val blocked = LostPhoneRecovery.prefs(this@LostPhoneService).getString("blocked", null)
                    when {
                        next == null || nextId == null || next.long("until") <= System.currentTimeMillis() || blocked == nextId ->
                            stopSearchLocally()
                        !locationEnabled() -> {
                            stopSearchLocally()
                            updateNotice("Cerere primită · locația Android este dezactivată")
                        }
                        else -> {
                            if (command?.str("id") != nextId) {
                                stopSearchLocally()
                                check(next.str("phase") == "active" || next.long("start_before") > System.currentTimeMillis()) {
                                    "Comanda a expirat."
                                }
                                command = next
                                startedElapsed = SystemClock.elapsedRealtimeNanos()
                                lastSend = 0
                                lastFixElapsed = 0
                                LostPhoneRecovery.prefs(this@LostPhoneService).edit().putString("command", nextId).commit()
                                updateNotice("Căutare pornită din contul tău · aștept poziția GPS")
                                try {
                                    withTimeout(20_000) {
                                        LostPhoneRecovery.call(
                                            this@LostPhoneService, owner, "status",
                                            buildJsonObject {
                                                put("secret", owner.secret); put("command", nextId); put("status", "locating")
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    stopSearchLocally()
                                    throw e
                                }
                                check(LostPhoneRecovery.device(this@LostPhoneService) == owner) { "Înrolarea s-a schimbat." }
                                val untilMs = next.long("until")
                                deadline = scope.launch {
                                    delay((untilMs - System.currentTimeMillis()).coerceAtLeast(1))
                                    stopSearchLocally()
                                }
                            }
                            registerProviders()
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    updateNotice(if (command == null) "Fără răspuns de la panou · reîncerc" else "Căutare activă · conexiune întreruptă")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InsightsFailure) {
                    if (e.code in listOf(401, 403, 404)) {
                        LostPhoneRecovery.clear(this@LostPhoneService)
                        stopSelf()
                        break
                    }
                    if (e.code == 409) stopSearchLocally()
                    updateNotice("Căutarea așteaptă reconectarea la panou")
                } catch (_: Exception) {
                    updateNotice("Găsire activată · conexiune întreruptă; reîncerc")
                }
                delay(30_000)
            }
            stopSelf()
        }
        return START_STICKY
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun locationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    @SuppressLint("MissingPermission")
    private fun registerProviders() {
        if (command == null || !LostPhoneRecovery.exactPermission(this)) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider in registered) continue
            try {
                if (!manager.isProviderEnabled(provider)) continue
                manager.requestLocationUpdates(provider, 3000L, 0f, this, Looper.getMainLooper())
                registered.add(provider)
            } catch (_: Exception) { }
        }
    }

    /** Oprește GPS-ul și uită comanda; notificarea revine la „pregătit”. */
    internal fun stopSearchLocally() {
        deadline?.cancel(); deadline = null
        sending?.cancel(); sending = null
        command = null
        registered.clear()
        try { manager.removeUpdates(this) } catch (_: Exception) { }
        LostPhoneRecovery.prefs(this).edit().remove("command").apply()
        if (running) updateNotice(READY)
    }

    override fun onLocationChanged(loc: Location) {
        val owner = d ?: return
        val active = command ?: return
        val id = active.str("id") ?: return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val age = elapsed - loc.elapsedRealtimeNanos
        if (LostPhoneRecovery.device(this) != owner ||
            LostPhoneRecovery.prefs(this).getString("blocked", null) == id ||
            now >= active.long("until") ||
            sending?.isActive == true ||
            now - lastSend < 10_000 ||
            !loc.hasAccuracy() ||
            loc.elapsedRealtimeNanos <= lastFixElapsed ||
            loc.elapsedRealtimeNanos < startedElapsed ||
            age !in 0..90_000_000_000L
        ) return
        if (!LostPhoneRecovery.exactPermission(this) || !LostPhoneRecovery.notices(this)) {
            LostPhoneRecovery.disable(this)
            return
        }
        lastSend = now
        lastFixElapsed = loc.elapsedRealtimeNanos
        sending = scope.launch {
            try {
                val level = batteryLevel()
                val at = now - age / 1_000_000
                withTimeout(20_000) {
                    LostPhoneRecovery.call(
                        this@LostPhoneService, owner, "position",
                        buildJsonObject {
                            put("secret", owner.secret)
                            put("command", id)
                            put("lat", loc.latitude)
                            put("lon", loc.longitude)
                            put("accuracy", loc.accuracy.coerceIn(0f, 10_000f).toDouble())
                            put("at", at)
                            put("battery", level)
                        }
                    )
                }
                if (command?.str("id") == id) updateNotice("Poziție trimisă în contul tău · precizie ±${loc.accuracy.toInt()} m")
            } catch (_: TimeoutCancellationException) {
                updateNotice("Poziție netrimisă · aștept conexiunea")
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsightsFailure) {
                when (e.code) {
                    409 -> stopSearchLocally()
                    401, 403, 404 -> {
                        LostPhoneRecovery.clear(this@LostPhoneService)
                        stopSelf()
                    }
                    else -> updateNotice("Poziție refuzată de panou · reîncerc la următorul fix")
                }
            } catch (_: Exception) {
                updateNotice("Poziție netrimisă · aștept conexiunea")
            }
        }
    }

    private fun batteryLevel(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    } catch (_: Exception) { 0 }

    private fun notification(text: String): Notification {
        fun action(code: Int, name: String): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, LostPhoneService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 630, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, LostPhoneRecovery.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("FORJA · Telefonul meu")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
        if (command != null) b.addAction(0, "Oprește căutarea", action(628, ACTION_STOP_SEARCH))
        b.addAction(0, "Dezactivează găsirea", action(629, ACTION_DISABLE))
        return b.build()
    }

    private fun updateNotice(text: String) {
        if (text == noticeText) return
        noticeText = text
        LostPhoneRecovery.prefs(this).edit().putString("status", text).apply()
        if (running) {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(text))
            } catch (_: Exception) { }
        }
    }

    @Deprecated("Necesar pe versiunile vechi de Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    override fun onProviderEnabled(provider: String) {
        if (command != null) registerProviders()
    }

    override fun onProviderDisabled(provider: String) {
        registered.remove(provider)
        updateNotice("Locația Android nu dă momentan o poziție")
    }

    override fun onDestroy() {
        running = false
        if (current === this) current = null
        stopSearchLocally()
        scope.cancel()
        FirebaseAuth.getInstance().removeAuthStateListener(auth)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
