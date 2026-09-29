package com.forja.app.core.sleep

import com.forja.app.core.data.db.SleepSessionEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Noaptea în Firestore (users/{uid}/sleep/s{id}), mutată neschimbată din CloudSync (mirror P0), ca tot ce ține de somn
 * să stea în core/sleep. Scrierea folosește cache-ul offline Firestore — fără net, se trimite la revenire.
 */
object SleepCloud {
    private val db get() = FirebaseFirestore.getInstance()

    /**
     * `snoreMin` / `talkCount` / `coverageMin` vin din cronologia serverului („Noaptea, ascultată”);
     * până la analiză, minutele de sforăit sunt cele din clipurile locale și acoperirea e 0.
     */
    fun sleep(
        uid: String?, s: SleepSessionEntity, snoreCount: Int, talkCount: Int, soundCount: Int,
        snoreMin: Int = 0, coverageMin: Int = 0
    ) {
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
                    "soundEvents" to soundCount,
                    "summary" to s.summary,
                    "snoreMin" to snoreMin,
                    "talkCount" to talkCount,
                    "coverageMin" to coverageMin,
                    "measurement" to "estimated"
                ),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }
}
