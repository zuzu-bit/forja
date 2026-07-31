package com.forja.app.core.sleep

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.data.db.SleepSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sesiune de somn: totul LOCAL. Accelerometrul numără mișcările;
 * scorul și fazele se estimează din durată + mișcare. Fără microfon, fără cloud.
 */
class SleepTrackService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sensorManager: SensorManager? = null
    private var lastMagnitude = 9.8f
    private var movements = 0
    private var lastMovementAt = 0L
    private var sessionId: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishSession()
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        startForeground(NOTIF_ID, buildNotification())
        val app = ForjaApp.from(this)
        app.presence.manualState = "sleep"
        app.auth.currentUid?.let { app.presence.publishState(it, "sleep") }
        scope.launch {
            val existing = app.db.sleepDao().activeSessionOnce()
            sessionId = existing?.id ?: app.db.sleepDao().insert(
                SleepSessionEntity(startAt = System.currentTimeMillis())
            )
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun finishSession() {
        sensorManager?.unregisterListener(this)
        val app = ForjaApp.from(this)
        app.presence.manualState = null
        app.auth.currentUid?.let { app.presence.publishState(it, "idle") }
        val moves = movements
        scope.launch {
            val dao = app.db.sleepDao()
            dao.activeSessionOnce()?.let { s ->
                val end = System.currentTimeMillis()
                val totalMin = ((end - s.startAt) / 60000).toInt().coerceAtLeast(1)
                // Estimare onestă: din durată + mișcare (nu diagnostic medical).
                val movePenalty = (moves * 1.5).coerceAtMost(30.0)
                val durationScore = when {
                    totalMin >= 450 -> 92
                    totalMin >= 390 -> 84
                    totalMin >= 330 -> 72
                    totalMin >= 270 -> 58
                    else -> 40
                }
                val score = (durationScore - movePenalty).roundToInt().coerceIn(15, 98)
                val deep = (totalMin * (0.20 + 0.06 * (1 - moves / 40.0).coerceIn(0.0, 1.0))).toInt()
                val rem = (totalMin * 0.21).toInt()
                val light = (totalMin - deep - rem).coerceAtLeast(0)
                dao.update(
                    s.copy(endAt = end, movements = moves, score = score,
                        deepMin = deep, lightMin = light, remMin = rem)
                )
            }
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        val now = System.currentTimeMillis()
        if (delta > 1.2f && now - lastMovementAt > 20_000) {
            lastMovementAt = now
            movements++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "sleep")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("FORJA veghează somnul")
            .setContentText("Totul rămâne pe telefon. Atinge pentru raport.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 31
        const val ACTION_STOP = "com.forja.app.sleep.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SleepTrackService::class.java))
        }
        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, SleepTrackService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
