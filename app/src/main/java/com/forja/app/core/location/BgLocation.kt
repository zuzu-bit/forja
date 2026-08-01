package com.forja.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Locația în FUNDAL, à la Zenly/Bump: prietenii te văd oriunde, oricând —
 * cu o singură excepție, aleasă de tine: modul fantomă.
 * Merge și cu aplicația închisă (updates livrate unui receiver), repornit la boot.
 */
object BgLocation {

    private const val REQUEST_CODE = 21

    fun hasFine(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasCoarse(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, BgLocationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    /** Pornește urmărirea în fundal dacă totul e la locul lui: cont + setare + permisiuni. */
    @SuppressLint("MissingPermission")
    fun registerIfReady(context: Context) {
        val app = context.applicationContext as? ForjaApp ?: return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (app.auth.currentUid == null) return@launch
                if (!app.prefs.bgShareOn.first()) return@launch
                if (!(hasFine(context) || hasCoarse(context)) || !hasBackground(context)) return@launch
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 180_000L)
                    .setMinUpdateDistanceMeters(20f)
                    .setMaxUpdateDelayMillis(360_000L)
                    .build()
                LocationServices.getFusedLocationProviderClient(context)
                    .requestLocationUpdates(request, pendingIntent(context))
            } catch (_: Exception) { }
        }
    }

    fun unregister(context: Context) {
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(pendingIntent(context))
        } catch (_: Exception) { }
    }
}

/** Primește pozițiile și în fundal → le publică prietenilor (dacă nu ești fantomă). */
class BgLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        val loc = result.lastLocation ?: return
        val app = context.applicationContext as? ForjaApp ?: return
        val uid = app.auth.currentUid ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ghostUntil = app.prefs.ghostUntilLocal.first()
                val ghost = ghostUntil == -1L || ghostUntil > System.currentTimeMillis()
                if (!ghost && app.prefs.bgShareOn.first()) {
                    val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                    val state = when {
                        speed >= 5.0 -> "ride"
                        speed >= 2.2 -> "run"
                        speed >= 0.4 -> "walk"
                        else -> "idle"
                    }
                    FirebaseFirestore.getInstance().collection("users").document(uid).set(
                        mapOf(
                            "lat" to loc.latitude,
                            "lng" to loc.longitude,
                            "speedMps" to speed,
                            "state" to state,
                            "locUpdatedAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** După restart de telefon: locația în fundal + paznicul Focus/Detox repornesc singuri. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BgLocation.registerIfReady(context)
            val app = context.applicationContext as? ForjaApp ?: return
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val focusOn = app.prefs.focusActive.first()
                    val detoxOn = app.prefs.detoxUntil.first() > System.currentTimeMillis()
                    if (focusOn || detoxOn) {
                        com.forja.app.core.focus.FocusMonitorService.start(context)
                    }
                } catch (_: Exception) { }
            }
        }
    }
}
