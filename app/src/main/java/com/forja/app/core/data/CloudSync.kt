package com.forja.app.core.data

import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.data.db.SleepSessionEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Sincronizarea în baza de date a companiei (Firestore-ul FORJA):
 * jurnalele urcă la contul fiecărui utilizator — users/{uid}/meals|sleep|activities.
 * This class uploads journal fields; audio storage is handled separately by ForjaApi.
 * Scrierile folosesc cache-ul offline Firestore — fără net, se trimit la revenire.
 */
object CloudSync {
    private val db get() = FirebaseFirestore.getInstance()
    private var ids = CloudRecordIds(null)

    fun configureCopy(context: android.content.Context) {
        val prefs = context.getSharedPreferences("online_copy", android.content.Context.MODE_PRIVATE)
        val id = prefs.getString("installation", null) ?: java.util.UUID.randomUUID().toString()
            .replace("-", "").take(16).also { prefs.edit().putString("installation", it).apply() }
        ids = CloudRecordIds(id)
    }

    fun recordingId(localId: Long): String = ids.record("s", localId)

    fun meal(uid: String?, m: MealEntity) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("meals").document(ids.record("m", m.id)).set(
                mapOf(
                    "name" to m.name,
                    "kcal" to m.kcal,
                    "protein" to m.protein,
                    "carbs" to m.carbs,
                    "fat" to m.fat,
                    "grams" to m.grams,
                    "mealType" to m.mealType,
                    "epochDay" to m.epochDay,
                    "source" to m.source,
                    "confidence" to m.confidence,
                    "at" to m.at
                ),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    fun deleteMeal(uid: String?, localId: Long) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("meals").document(ids.record("m", localId)).delete()
        } catch (_: Exception) { }
    }

    fun sleep(uid: String?, s: SleepSessionEntity, snoreCount: Int, talkCount: Int, soundCount: Int) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("sleep").document(ids.record("s", s.id)).set(
                mapOf(
                    "startAt" to s.startAt,
                    "endAt" to (s.endAt ?: 0L),
                    "score" to s.score,
                    "deepMin" to s.deepMin,
                    "lightMin" to s.lightMin,
                    "remMin" to s.remMin,
                    "movements" to s.movements,
                    "snoreEvents" to snoreCount,
                    "talkEvents" to talkCount,
                    "soundEvents" to soundCount,
                    "summary" to s.summary
                ),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    fun activity(uid: String?, a: ActivityEntity) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("activities").document(ids.record("a", a.id)).set(
                mapOf(
                    "type" to a.type,
                    "distanceM" to a.distanceM,
                    "durationS" to a.durationS,
                    "kcal" to a.kcal,
                    "startAt" to a.startAt,
                    "endAt" to a.endAt,
                    "polyline" to a.polyline
                ),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }
}
