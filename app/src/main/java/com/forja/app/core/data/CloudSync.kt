package com.forja.app.core.data

import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.data.db.SleepSessionEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Sincronizarea în baza de date a companiei (Firestore-ul FORJA):
 * jurnalele urcă la contul fiecărui utilizator — users/{uid}/meals|sleep|activities.
 * Pozele și clipurile audio NU se stochează nicăieri: se analizează și dispar.
 * Scrierile folosesc cache-ul offline Firestore — fără net, se trimit la revenire.
 */
object CloudSync {
    private val db get() = FirebaseFirestore.getInstance()

    fun meal(uid: String?, m: MealEntity) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("meals").document("m${m.id}").set(
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
            db.collection("users").document(uid).collection("meals").document("m$localId").delete()
        } catch (_: Exception) { }
    }

    fun sleep(uid: String?, s: SleepSessionEntity, snoreCount: Int, talkCount: Int, soundCount: Int) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("sleep").document("s${s.id}").set(
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
                    "soundEvents" to soundCount
                ),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    fun activity(uid: String?, a: ActivityEntity) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("activities").document("a${a.id}").set(
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
