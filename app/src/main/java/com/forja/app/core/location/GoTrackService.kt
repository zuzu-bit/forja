package com.forja.app.core.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.util.Fmt
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class GoState(
    val recording: Boolean = false,
    val sport: String = "run",           // run · walk · ride
    val startedAt: Long = 0L,
    val distanceM: Double = 0.0,
    val points: List<Pair<Double, Double>> = emptyList(),
    val lastSpeedMps: Double = 0.0
)

/**
 * Înregistrare traseu (GO) à la Strava: alergare / mers / ciclism.
 * Serviciu foreground (tip LOCATION) cu FusedLocation la 1s — polilinie live + consolă.
 * Respectă fantoma pentru users/{uid}; familia primește poziția mereu; fixurile hrănesc Explorarea.
 */
class GoTrackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callback: LocationCallback? = null
    private var lastFirestorePush = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Repornit de sistem după moartea procesului: nu pornim o înregistrare-fantomă.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> finish()
            else -> begin(intent.getStringExtra(EXTRA_SPORT) ?: "run")
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin(sport: String) {
        if (state.value.recording) return
        // Fără permisiune de locație nu avem ce înregistra — și Android 14+ ar refuza startForeground.
        if (!(BgLocation.hasFine(this) || BgLocation.hasCoarse(this))) {
            stopSelf()
            return
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(sport), type)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        state.value = GoState(recording = true, sport = sport, startedAt = System.currentTimeMillis())
        val app = ForjaApp.from(this)
        val client = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val s = state.value
                if (!s.recording) return
                try { app.explore.onLocation(loc, "go") } catch (_: Exception) { }
                val pts = s.points
                var dist = s.distanceM
                if (pts.isNotEmpty()) {
                    val (plat, plng) = pts.last()
                    val res = FloatArray(1)
                    android.location.Location.distanceBetween(plat, plng, loc.latitude, loc.longitude, res)
                    if (res[0] < 300) dist += res[0]   // ignoră salturi GPS
                }
                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                state.value = s.copy(
                    distanceM = dist,
                    points = pts + (loc.latitude to loc.longitude),
                    lastSpeedMps = speed
                )
                // Publică live către prieteni — max la 5s; fantoma oprește users/{uid}, nu și familia.
                val now = System.currentTimeMillis()
                if (now - lastFirestorePush > 5000) {
                    lastFirestorePush = now
                    val uid = app.auth.currentUid ?: return
                    val sportNow = state.value.sport
                    if (!app.presence.isGhostNow()) {
                        FirebaseFirestore.getInstance().collection("users").document(uid).set(
                            mapOf(
                                "lat" to loc.latitude, "lng" to loc.longitude,
                                "speedMps" to speed,
                                "state" to sportNow,
                                "locUpdatedAt" to now
                            ), SetOptions.merge()
                        )
                    }
                    scope.launch {
                        try {
                            val fam = app.prefs.familyUids.first()
                            if (fam.isNotEmpty()) {
                                app.friends.writeFamilyLoc(uid, loc.latitude, loc.longitude, speed, sportNow, fam, now)
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {
            state.value = GoState(sport = sport)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finish() {
        callback?.let {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it)
        }
        callback = null
        val s = state.value
        val app = ForjaApp.from(this)
        if (s.recording && s.distanceM > 30) {
            val end = System.currentTimeMillis()
            val durS = (end - s.startedAt) / 1000
            scope.launch {
                val activity = ActivityEntity(
                    startAt = s.startedAt, endAt = end,
                    distanceM = s.distanceM, durationS = durS,
                    kcal = kcalFor(s.sport, s.distanceM),
                    polyline = s.points.joinToString(";") { "${it.first},${it.second}" },
                    type = s.sport
                )
                val newId = app.db.activityDao().insert(activity)
                com.forja.app.core.data.CloudSync.activity(app.auth.currentUid, activity.copy(id = newId))
                // Publică rezumatul către prieteni: km-ii reali ai săptămânii + ultima activitate.
                app.auth.currentUid?.let { uid ->
                    try {
                        val weekM = app.db.activityDao().distanceSinceOnce(Fmt.startOfWeekMillis())
                        FirebaseFirestore.getInstance().collection("users").document(uid).set(
                            mapOf(
                                "weekKm" to weekM / 1000.0,
                                "state" to "idle",
                                "lastActivityType" to s.sport,
                                "lastActivityKm" to s.distanceM / 1000.0,
                                "lastActivityDurS" to durS,
                                "lastActivityAt" to end
                            ), SetOptions.merge()
                        )
                    } catch (_: Exception) { }
                }
                state.value = GoState(sport = s.sport)
                ServiceCompat.stopForeground(this@GoTrackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            state.value = GoState(sport = s.sport)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(sport: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val label = when (sport) {
            "walk" -> "Mersul tău se înregistrează"
            "ride" -> "Tura ta pe roți se înregistrează"
            else -> "Alergarea ta se înregistrează"
        }
        return NotificationCompat.Builder(this, "go")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(label)
            .setContentText("Atinge pentru consolă. Oprești din hartă.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        callback?.let {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it)
        }
        callback = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 32
        const val ACTION_STOP = "com.forja.app.go.STOP"
        const val EXTRA_SPORT = "sport"
        val state = MutableStateFlow(GoState())

        fun kcalFor(sport: String, distanceM: Double): Int {
            val perKm = when (sport) {
                "walk" -> 50
                "ride" -> 25
                else -> 62
            }
            return (distanceM / 1000.0 * perKm).roundToInt()
        }

        fun start(context: Context, sport: String = "run") {
            try {
                context.startForegroundService(
                    Intent(context, GoTrackService::class.java).putExtra(EXTRA_SPORT, sport)
                )
            } catch (_: Exception) { }
        }
        fun stop(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, GoTrackService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) { }
        }
    }
}
