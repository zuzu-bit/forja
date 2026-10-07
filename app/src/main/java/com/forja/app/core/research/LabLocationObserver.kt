package com.forja.app.core.research

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

internal class LabLocationObserver(private val context: Context) : LabObserver {
    private val capture = LabCapture(context)
    @Volatile private var closed = false
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (closed) return
            result.locations.forEach { location -> capture.event("LOCATION", "location_updated", JSONObject()
                .put("latitude", location.latitude).put("longitude", location.longitude)
                .put("accuracy", location.accuracy.toDouble()).put("provider", location.provider ?: "fused")
                .put("source", "fused_lab").put("timestamp", location.time), location.time) }
        }
    }
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!hasPermission(context)) {
            capture.event("LOCATION", "source_unavailable", JSONObject().put("reason", "Location permission required")); return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000)
            .setMinUpdateIntervalMillis(10_000).setMinUpdateDistanceMeters(5f).setMaxUpdateDelayMillis(15_000).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnSuccessListener {
                if (!closed && capture.allows("LOCATION")) LabObservers.markLocation(true)
                else client.removeLocationUpdates(callback)
            }
            .addOnFailureListener {
                if (!closed) {
                    LabObservers.markLocation(false)
                    capture.event("LOCATION", "source_unavailable", JSONObject().put("reason", it.javaClass.simpleName))
                }
            }
    }
    override fun close() {
        capture.close()
        closed = true
        client.removeLocationUpdates(callback)
        LabObservers.markLocation(false)
    }
    companion object {
        fun hasPermission(context: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
