package com.forja.app.core.sleep

import com.forja.app.ForjaApp
import com.forja.app.core.data.db.SleepSessionEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Noaptea în Firestore, pentru site (Somn). Toate scrierile folosesc cache-ul offline Firestore — fără net, pleacă la
 * revenire — și sunt `merge`, ca să nu se calce între ele (serviciul, urcarea, ecranul).
 *  · users/{uid}/sleep/s{id}: jurnalul nopții (cifrele, rezumatul) + starea, alarma, sunetele, stingerea, urcarea.
 *    Se scrie la pornirea veghei (`state: recording`, endAt 0), la final și la o veghe întreruptă.
 *    Tot aici, fără contract, stadiile din mișcare: fazele, trezirile, latența, liniile scorului.
 *  · users/{uid}/sleepEvents/s{id}: cronologia fără sunet (momentele, vorbele, limitările) — doar cu v4.
 * Formele hărților sunt în [SleepNightDoc].
 */
object SleepCloud {
    private val db get() = FirebaseFirestore.getInstance()
    /** Ultima stare de urcare scrisă pe sesiune — nu retrimitem același lucru la fiecare bucată. */
    private val lastAudio = ConcurrentHashMap<Long, String>()

    private fun night(uid: String, sessionId: Long) =
        db.collection("users").document(uid).collection("sleep").document("s$sessionId")

    /**
     * `snoreMin` / `talkCount` / `coverageMin` vin din cronologia serverului („Noaptea, ascultată”);
     * până la analiză, minutele de sforăit sunt cele din clipurile locale și acoperirea e 0.
     * `extras`: starea, alarma, sunetele, stingerea (hărțile din [SleepNightDoc]).
     */
    fun sleep(
        uid: String?, s: SleepSessionEntity, snoreCount: Int, talkCount: Int, soundCount: Int,
        snoreMin: Int = 0, coverageMin: Int = 0, extras: Map<String, Any?> = emptyMap()
    ) {
        uid ?: return
        try {
            night(uid, s.id).set(
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
                ) + extras.filterValues { it != null },
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    /** Veghea a pornit: site-ul arată „Stingerea e activă de la 23:10”. */
    fun started(uid: String?, sessionId: Long, startAt: Long, alarm: SleepNightDoc.Alarm?, bedtime: Map<String, Any>?) {
        uid ?: return
        try {
            night(uid, sessionId).set(
                buildMap<String, Any> {
                    put("startAt", startAt); put("endAt", 0L); put("state", "recording")
                    SleepNightDoc.alarmMap(alarm)?.let { put("alarm", it) }
                    bedtime?.let { put("bedtime", it) }
                },
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    /** Stingerea aleasă: amintirea pornită și minutul din zi (trezirea − 8 h sau 23:00). */
    suspend fun bedtime(app: ForjaApp): Map<String, Any>? = try {
        val on = app.prefs.alarmEnabled.first()
        val minute = com.forja.app.core.notify.Bedtime.bedtimeMinute(on, app.prefs.alarmHour.first(), app.prefs.alarmMinute.first())
        mapOf("reminder" to app.prefs.sleepReminder.first(), "minute" to minute)
    } catch (_: Exception) { null }

    /** Starea urcării, din fișierele sesiunii. */
    fun audioOf(app: ForjaApp, sessionId: Long, recordedUntil: Long = 0L): SleepNightDoc.Audio {
        val dir = AacRecorder.sessionDir(app.filesDir, sessionId)
        val m = AacRecorder.manifestFor(app.filesDir, sessionId, 0L)
        val chunks = m?.chunks?.count { it.dur > 0 || m.chunks.size == 1 } ?: 0
        val p = SleepUpload.loadProgress(dir)
        val state = SleepNightDoc.audioState(
            chunks = chunks, uploaded = p?.uploaded?.size ?: 0, rejected = p?.rejected?.size ?: 0,
            analyzeRequested = (p?.analyzeRequestedAt ?: 0L) > 0L, done = p?.done == true,
            attempts = p?.attempts ?: 0, maxAttempts = SleepUpload.MAX_ATTEMPTS, lastError = p?.lastError ?: "",
            cellular = true, started = p != null
        )
        return SleepNightDoc.Audio(chunks, p?.uploaded?.size ?: 0, state, p?.lastError ?: "", m?.startedAt ?: 0L, recordedUntil)
    }

    /** Scrie `audio` pe noapte, doar când starea s-a schimbat (o scriere pe bucată urcată, nu pe secundă). */
    fun audio(app: ForjaApp, sessionId: Long, recordedUntil: Long = 0L, force: Boolean = false) {
        val uid = app.auth.currentUid ?: return
        try {
            val a = audioOf(app, sessionId, recordedUntil)
            val key = "${a.state}:${a.uploaded}:${a.chunks}:${a.recordedUntil}"
            if (!force && lastAudio[sessionId] == key) return
            lastAudio[sessionId] = key
            night(uid, sessionId).set(mapOf("audio" to SleepNightDoc.audioMap(a)), SetOptions.merge())
        } catch (_: Exception) { }
    }

    /**
     * Cronologia nopții (v4): cronologia serverului din `timeline.json` și momentele
     * prinse pe telefon (Room). Se rescrie întreagă: la final, după analiza serverului și după ce ștergi un moment.
     */
    suspend fun timeline(app: ForjaApp, sessionId: Long) {
        val uid = app.auth.currentUid ?: return
        try {
            if (!app.prefs.contractAtLeast(4).first()) return
            val s = app.db.sleepDao().recent(60).first().firstOrNull { it.id == sessionId } ?: return
            val dir = AacRecorder.sessionDir(app.filesDir, sessionId)
            val phone = app.db.sleepDao().eventsForSessionOnce(sessionId).map {
                SleepNightDoc.PhoneEvent(it.id, it.type, it.at, it.durationS, it.intensity, it.transcript)
            }
            val server = SleepTimeline.load(dir)
            val audioStartAt = AacRecorder.manifestFor(app.filesDir, sessionId, 0L)?.startedAt ?: server?.startedAt ?: 0L
            val doc = SleepNightDoc.timelineDoc(s.startAt, phone, server, audioStartAt, System.currentTimeMillis())
            db.collection("users").document(uid).collection("sleepEvents").document("s$sessionId").set(doc, SetOptions.merge())
        } catch (_: Exception) { }
    }

    /** „Vorbe din somn”, rezumatul de dimineață (v4, alături de cronologie). */
    suspend fun talkSummary(app: ForjaApp, sessionId: Long, text: String) {
        val uid = app.auth.currentUid ?: return
        if (text.isBlank()) return
        try {
            if (!app.prefs.contractAtLeast(4).first()) return
            db.collection("users").document(uid).collection("sleepEvents").document("s$sessionId")
                .set(mapOf("talkSummary" to text.trim().take(600)), SetOptions.merge())
        } catch (_: Exception) { }
    }
}
