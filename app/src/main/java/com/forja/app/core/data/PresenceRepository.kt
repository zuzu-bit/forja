package com.forja.app.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Publică poziția mea către prieteni cât timp FORJA e deschisă.
 * On-device first: nimic nu pleacă dacă fantoma e activă sau permisiunea lipsește.
 * Viteza decide starea: 0 idle · <2,2 walk · ≥2,2 run · ≥5 ride (din handoff).
 */
class PresenceRepository(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    private var lastPublish = 0L

    var manualState: String? = null   // „sleep" în sesiune de somn

    /** Oglinda locală a fantomei — citită sincron de publicatori. */
    @Volatile var ghostUntilCache: Long = 0L
    fun isGhostNow(): Boolean = ghostUntilCache == -1L || ghostUntilCache > System.currentTimeMillis()

    fun stateFor(speedMps: Double): String = when {
        manualState != null -> manualState!!
        speedMps >= 5.0 -> "ride"
        speedMps >= 2.2 -> "run"
        speedMps >= 0.4 -> "walk"
        else -> "idle"
    }

    @SuppressLint("MissingPermission")
    fun start(uid: String, isGhost: () -> Boolean) {
        if (callback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 8000L)
            .setMinUpdateDistanceMeters(8f)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                if (now - lastPublish < 8000) return
                lastPublish = now
                if (isGhost()) return
                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                db.collection("users").document(uid).set(
                    mapOf(
                        "lat" to loc.latitude,
                        "lng" to loc.longitude,
                        "speedMps" to speed,
                        "state" to stateFor(speed),
                        "locUpdatedAt" to now
                    ),
                    SetOptions.merge()
                )
            }
        }
        try {
            client.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {
            callback = null
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    fun publishState(uid: String, state: String) {
        db.collection("users").document(uid).set(
            mapOf("state" to state, "locUpdatedAt" to System.currentTimeMillis()),
            SetOptions.merge()
        )
    }
}
