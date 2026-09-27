package com.forja.app.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.forja.app.ForjaApp
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Publică poziția mea către prieteni cât timp FORJA e deschisă.
 * On-device first: nimic nu pleacă în users/{uid} dacă fantoma e activă sau permisiunea lipsește.
 * Familia (familyLoc/{uid}) primește poziția MEREU — asta e înțelegerea.
 * Viteza decide starea: 0 idle · <2,2 walk · ≥2,2 run · ≥5 ride (din handoff).
 */
class PresenceRepository(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    private var lastPublish = 0L

    private val app: ForjaApp? get() = context.applicationContext as? ForjaApp

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
                // Explorarea primește orice fix, indiferent de fantomă sau throttle.
                try { app?.let { it.explore.onLocation(loc, "presence") } } catch (_: Exception) { }
                val now = System.currentTimeMillis()
                if (now - lastPublish < 8000) return
                lastPublish = now
                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                val state = stateFor(speed)
                if (!isGhost()) {
                    db.collection("users").document(uid).set(
                        mapOf(
                            "lat" to loc.latitude,
                            "lng" to loc.longitude,
                            "speedMps" to speed,
                            "state" to state,
                            "locUpdatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                }
                publishFamily(uid, loc.latitude, loc.longitude, speed, state, now)
            }
        }
        try {
            client.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {
            callback = null
        }
    }

    /** familyLoc/{uid} — scris și în fantomă, doar dacă am pe cineva în familie. */
    private fun publishFamily(uid: String, lat: Double, lng: Double, speed: Double, state: String, at: Long) {
        val a = app ?: return
        a.appScope.launch {
            try {
                val fam = a.prefs.familyUids.first()
                if (fam.isEmpty()) return@launch
                db.collection("familyLoc").document(uid).set(
                    mapOf(
                        "lat" to lat, "lng" to lng,
                        "speedMps" to speed, "state" to state,
                        "locUpdatedAt" to at,
                        "allowed" to fam.toList()
                    )
                )
            } catch (_: Exception) { }
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
