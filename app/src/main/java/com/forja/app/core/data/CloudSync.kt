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
 * Mirror (pachetul C): documentele au id-uri stabile (`cloudId`, sau derivate din momentul jurnalului), nu m{id}/a{id}/w{id}
 * din Room (care o iau de la 1 după o reinstalare și ar suprascrie istoria); [JournalMirror] completează ce n-a urcat.
 * Poza mesei urcă pe serverul site-ului doar cu contractul v4 ([JournalMirror.mealPhoto]); clipurile audio nu.
 * Nopțile (users/{uid}/sleep) urcă prin [com.forja.app.core.sleep.SleepCloud].
 * Scrierile folosesc cache-ul offline Firestore — fără net, se trimit la revenire.
 */
object CloudSync {
    private val db get() = FirebaseFirestore.getInstance()

    /** Id-ul stabil al mesei în Firestore (mirror C): `cloudId`, sau cel derivat din momentul ei pentru rândurile vechi. */
    fun mealId(m: MealEntity): String = m.cloudId ?: SitePayloads.mealCloudId(m.at)
    fun activityId(a: ActivityEntity): String = a.cloudId ?: SitePayloads.activityCloudId(a.startAt)

    /** users/{uid}/meals/{cloudId}, cu ce a găsit analiza; `photo` = true după ce poza a urcat (null = nu atinge câmpul). */
    fun meal(uid: String?, m: MealEntity, photo: Boolean? = null) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("meals").document(mealId(m)).set(
                SitePayloads.mealDoc(m.name, m.kcal, m.protein, m.carbs, m.fat, m.grams, m.mealType, m.epochDay, m.source, m.confidence, m.at, m.details, photo),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    /** Masa ștearsă: documentul stabil și, dacă a existat, cel vechi „m{id}” (de dinainte de mirror). */
    fun deleteMeal(uid: String?, m: MealEntity) {
        uid ?: return
        try {
            val col = db.collection("users").document(uid).collection("meals")
            col.document(mealId(m)).delete()
            col.document("m${m.id}").get().addOnSuccessListener { d -> if (d.exists() && d.getLong("at") == m.at) d.reference.delete() }
        } catch (_: Exception) { }
    }

    fun activity(uid: String?, a: ActivityEntity) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("activities").document(activityId(a)).set(
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

    /** Un antrenament terminat (payload din [SitePayloads.workoutV2]) → users/{uid}/workouts/{cloudId}. */
    fun workout(uid: String?, docId: String, doc: Map<String, Any?>) {
        uid ?: return
        try {
            db.collection("users").document(uid).collection("workouts").document(docId).set(doc, SetOptions.merge())
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
