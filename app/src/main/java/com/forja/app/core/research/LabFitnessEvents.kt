package com.forja.app.core.research

import android.location.Location
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.MealEntity
import org.json.JSONObject

/** Additive telemetry: consent/account checks live in LabController; fitness never waits on sync. */
fun ForjaApp.recordLabEvent(
    source: String,
    type: String,
    sourceTimestamp: Long = System.currentTimeMillis(),
    vararg fields: Pair<String, Any?>,
    expectedDeviceId: String? = null
) {
    try {
        val payload = JSONObject()
        fields.forEach { (key, value) -> payload.put(key, value ?: JSONObject.NULL) }
        labResearch.observe(source, type, payload, sourceTimestamp, expectedDeviceId)
    } catch (_: Exception) {
        // A telemetry failure must not interrupt a workout, meal, alarm or existing cloud sync.
    }
}

fun ForjaApp.captureLabDevice(source: String): String? = try {
    if (LabObservers.isRunning() && labResearch.isSourceEnabled(source)) labResearch.session.value?.deviceId else null
} catch (_: Exception) { null }

/** An async fitness operation cannot attribute an earlier observation to a newer lab session. */
fun ForjaApp.recordLabEventForDevice(
    deviceId: String?, source: String, type: String,
    sourceTimestamp: Long = System.currentTimeMillis(), vararg fields: Pair<String, Any?>
) {
    if (deviceId == null) return
    recordLabEvent(source, type, sourceTimestamp, *fields, expectedDeviceId = deviceId)
}

fun ForjaApp.recordLabMeal(meal: MealEntity, deviceId: String?) {
    if (deviceId == null) return
    recordLabEventForDevice(
        deviceId, "NUTRITION", "meal_created", meal.at,
        "mealId" to meal.id, "name" to meal.name, "mealType" to meal.mealType,
        "kcal" to meal.kcal, "protein" to meal.protein, "carbs" to meal.carbs,
        "fat" to meal.fat, "grams" to meal.grams, "source" to meal.source,
        "confidence" to meal.confidence, "barcode" to meal.barcode
    )
    if (meal.photoPath != null) recordLabEventForDevice(
        deviceId, "NUTRITION", "meal_photo", meal.at,
        "mealId" to meal.id, "filename" to java.io.File(meal.photoPath).name,
        "originalOnDevice" to true
    )
}

/** Reuse positions FORJA already receives; the dedicated lab provider avoids double capture. */
fun ForjaApp.recordLabLocation(location: Location, provider: String) {
    if (LabObservers.isLocationRunning()) return
    if (provider != "forja_go" && com.forja.app.core.location.GoTrackService.state.value.recording) return
    recordLabEvent(
        "LOCATION", "location_update", location.time,
        "latitude" to location.latitude, "longitude" to location.longitude,
        "accuracy" to location.accuracy.toDouble(), "timestamp" to location.time,
        "source" to provider, "provider" to (location.provider ?: "unknown"),
        "speedMps" to if (location.hasSpeed()) location.speed.toDouble() else null
    )
}
