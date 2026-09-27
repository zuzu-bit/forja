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
 * cu o singură excepție, aleasă de tine: modul fantomă (familia te vede și atunci).
 * Merge și cu aplicația închisă (updates livrate unui receiver), repornit la boot.
 * Același fix hrănește și Explorarea (zone + locuri).
 * Familia e „mereu pornită”: cu familyUids nevid cadența urcă la 120 s și urmărirea pornește chiar și cu
 * „Locație în fundal” oprită (prietenii obișnuiți tot nu te văd — doar familyLoc se scrie).
 */
object BgLocation {

    private const val REQUEST_CODE = 21
    /** Cadența obișnuită (prieteni) și cea de familie („te vede și când FORJA e închisă”). */
    const val DEFAULT_INTERVAL_MS = 180_000L
    const val FAMILY_INTERVAL_MS = 120_000L

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
                val family = app.prefs.familyUids.first().isNotEmpty()
                if (!app.prefs.bgShareOn.first() && !family) return@launch
                if (!(hasFine(context) || hasCoarse(context)) || !hasBackground(context)) return@launch
                val interval = if (family) FAMILY_INTERVAL_MS else DEFAULT_INTERVAL_MS
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                    .setMinUpdateDistanceMeters(20f)
                    .setMaxUpdateDelayMillis(interval * 2)
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

/** Primește pozițiile și în fundal → Explorare + prieteni (dacă nu ești fantomă) + familie (mereu). */
class BgLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        val loc = result.lastLocation ?: return
        val app = context.applicationContext as? ForjaApp ?: return
        val uid = app.auth.currentUid ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                val state = when {
                    speed >= 5.0 -> "ride"
                    speed >= 2.2 -> "run"
                    speed >= 0.4 -> "walk"
                    else -> "idle"
                }
                val now = System.currentTimeMillis()
                val ghostUntil = app.prefs.ghostUntilLocal.first()
                val ghost = ghostUntil == -1L || ghostUntil > now
                if (!ghost && app.prefs.bgShareOn.first()) {
                    FirebaseFirestore.getInstance().collection("users").document(uid).set(
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
                // Familia te vede și în fantomă.
                val fam = app.prefs.familyUids.first()
                if (fam.isNotEmpty()) {
                    app.friends.writeFamilyLoc(uid, loc.latitude, loc.longitude, speed, state, fam, now)
                }
                // Explorarea: așteptăm prelucrarea, receiverul are fereastra goAsync.
                try {
                    app.explore.ingest(
                        lat = loc.latitude, lng = loc.longitude,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
                        speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                        atMs = loc.time.takeIf { it > 0 } ?: now,
                        source = "bg"
                    )
                } catch (_: Exception) { }
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
            // Găsirea telefonului: după restart, serviciul revine doar cu locația „Tot timpul”.
            try { com.forja.app.core.recovery.LostPhoneRecovery.resume(context, boot = true) } catch (_: Exception) { }
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
