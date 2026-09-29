package com.forja.app.core.data

import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.data.db.MealEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Sincronizarea în baza de date a companiei (Firestore-ul FORJA):
 * jurnalele urcă la contul fiecărui utilizator — users/{uid}/meals|sleep|activities —, iar din 4.4 și
 * antrenamentele (users/{uid}/workouts), rația (users/{uid}/settings/targets) și emailul contului
 * (users/{uid}/settings/account). Tot sub users/{uid}/…: regulile le dau doar proprietarului.
 * Pozele și clipurile audio NU se stochează nicăieri: se analizează și dispar.
 * Nopțile (users/{uid}/sleep) urcă prin [com.forja.app.core.sleep.SleepCloud].
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

    /** Un antrenament terminat (payload din [SitePayloads.workout]) → users/{uid}/workouts/w{id}. */
    fun workout(uid: String?, localId: Long, doc: Map<String, Any?>) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("workouts").document("w$localId").set(doc, SetOptions.merge())
        } catch (_: Exception) { }
    }

    /** Rația zilnică (payload din [SitePayloads.targetsDoc]) → users/{uid}/settings/targets. */
    fun targets(uid: String?, doc: Map<String, Any>) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("settings").document("targets").set(doc, SetOptions.merge())
        } catch (_: Exception) { }
    }
}
