package com.forja.app.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.forja.app.ForjaApp
import com.forja.app.core.music.MusicStats
import com.forja.app.feature.nutrition.NutritionPrefs
import com.forja.app.feature.nutrition.Targets
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site a datelor care până în 4.4 rămâneau doar în telefon (DESIGN-4.4 §3.3):
 *  - antrenamentele terminate → users/{uid}/workouts/w{id} (la fiecare sesiune încheiată + completarea ultimelor 60 de zile);
 *  - rația zilnică → users/{uid}/settings/targets (de fiecare dată când se schimbă);
 *  - emailul iese din users/{uid} (document citit de prieteni) și trece în users/{uid}/settings/account (doar tu).
 *
 * Nu atinge ecranele: ascultă Room (`workout_sessions`) și DataStore-ul Rației. Antrenamentele și rația pleacă doar cu
 * contractul semnat; emailul se mută oricum (e o micșorare a ce se vede, nu o trimitere nouă). Scrierile folosesc
 * cache-ul offline Firestore — fără net, pleacă la revenire. Idempotent: același document, `SetOptions.merge()`.
 */
object SiteMirror {
    /** Completarea la prima trecere: antrenamentele din ultimele 60 de zile. */
    const val BACKFILL_MS = 60L * 24 * 3_600_000L
    /** Așteptăm să se așeze scrierile (sesiune închisă, glisor de kcal) înainte de o trecere. */
    private const val SETTLE_MS = 2_000L
    private const val MAX_WORKOUTS_PER_PASS = 500

    private val started = AtomicBoolean(false)
    private val workoutsLock = Mutex()

    /**
     * Tot ce oglindește site-ul în Firestore sub users/{uid}/… și șterge revocarea ([forget]): colecții (adâncime 2, ca
     * regula users/{uid}/{sub}/{docId} să le acopere — nimic mai adânc) și documentele din settings/. Jurnalele (meals,
     * sleep, activities) nu sunt aici: rămân în cont. Pachetele A–D scriu doar în numele de aici:
     *  workouts, inventory (v3) · focus/{zi}, detox/{zi}, breath/{zi} (D) · nudges/{zi}, listens/{zi} (D) · games/{zid|asalt} (C)
     *  · sleepEvents/s{id} (B) · live/go (A) · settings/music, targets (v3) · settings/presence, contacts (A), storage (C).
     */
    val MIRRORED: List<String> = listOf(
        "workouts", "inventory", "focus", "detox", "breath", "nudges", "listens", "games", "sleepEvents", "live",
        "settings/music", "settings/targets", "settings/presence", "settings/contacts", "settings/storage",
    )

    /** Pornește ascultătorii o singură dată pe proces. Sigur din orice fir. */
    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        val uids = authUid()

        // Antrenamente: fiecare schimbare în workout_sessions (o sesiune încheiată) → o trecere după ceas.
        app.appScope.launch {
            try {
                combine(uids, app.prefs.contractSigned, app.db.workoutDao().lastSession()) { uid, signed, _ ->
                    if (signed) uid else null
                }.collectLatest { uid ->
                    if (uid == null) return@collectLatest
                    delay(SETTLE_MS)
                    withContext(NonCancellable) { workoutsPass(app, uid) }
                }
            } catch (_: Exception) { }
        }

        // Rația: profilul corpului sau obiectivul ales, exact ca inelele din Rație.
        app.appScope.launch {
            try {
                val nutrition = NutritionPrefs.of(app)
                combine(uids, app.prefs.contractSigned, nutrition.profile, nutrition.kcalTarget) { uid, signed, profile, manual ->
                    if (signed && uid != null) uid to SitePayloads.targets(Targets.of(profile), manual) else null
                }.distinctUntilChanged().collectLatest { pair ->
                    if (pair == null) return@collectLatest
                    delay(SETTLE_MS)
                    withContext(NonCancellable) { targetsPass(app, pair.first, pair.second) }
                }
            } catch (_: Exception) { }
        }

        // Emailul: o singură dată pe cont, la prima pornire cu contul conectat. Întâi jurnalele din Room trec la acest cont
        // (sau se golesc, dacă sunt ale altuia): nimic din telefonul altcuiva nu urcă în contul lui.
        app.appScope.launch {
            try {
                uids.collect { uid ->
                    if (uid == null) return@collect
                    try { Journals.claim(app, uid) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    accountPass(app, uid)
                }
            } catch (_: Exception) { }
        }

        // Oglinzile noi (goale până le umplu pachetele): fiecare își pornește singură ascultătorii, o dată pe proces.
        FocusMirror.start(app)
        ListenMirror.start(app)
        GamesMirror.start(app)
        StorageMirror.start(app)
    }

    /** Trimite antrenamentele terminate după ultimul trimis (prima dată: ultimele 60 de zile). */
    private suspend fun workoutsPass(app: ForjaApp, uid: String) = workoutsLock.withLock {
        try {
            // Room poate fi încă al contului dinainte (ieșire fără Profil): se golește înainte de orice completare.
            Journals.claim(app, uid)
            val store = SiteSyncStore.of(app)
            val key = SiteSyncStore.workoutsSince(uid)
            val since = store.getLong(key, 0L)
            val now = System.currentTimeMillis()
            val (todo, next) = SitePayloads.workoutsToSend(finishedWorkouts(app, since, now - BACKFILL_MS), since, now, BACKFILL_MS)
            if (todo.isEmpty()) return@withLock
            for (w in todo) CloudSync.workout(uid, w.id, SitePayloads.workout(w, setsOf(app, w.id)))
            store.edit().putLong(key, next).apply()
        } catch (_: Exception) { }
    }

    /**
     * Revocarea contractului: tot ce e în [MIRRORED] se șterge din Firestore — topul muzicii, rația, antrenamentele,
     * rezumatele Inventarului și, după v4, concentrarea, detoxul, respirația, Casca, ascultarea, jocurile, cronologia
     * nopților, GO-ul live și setările site-ului — iar ceasurile locale se uită, ca o semnătură nouă să retrimită tot.
     * Jurnalele (mese, somn, activități) rămân: nu țin de contract. Ștergerile trec prin cache-ul offline (fără net,
     * pleacă la revenire); regulile permit proprietarului orice ștergere în users/{uid}/…
     */
    suspend fun forget(app: ForjaApp, uid: String) {
        try {
            SiteSyncStore.of(app).edit().remove(SiteSyncStore.workoutsSince(uid)).remove(SiteSyncStore.targetsSignature(uid)).apply()
        } catch (_: Exception) { }
        try { MusicStats.setSummaryAt(app, 0L) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        val user = FirebaseFirestore.getInstance().collection("users").document(uid)
        for (name in MIRRORED) {
            if (name.startsWith("settings/")) {
                try { user.collection("settings").document(name.removePrefix("settings/")).delete() } catch (_: Exception) { }
                continue
            }
            val col = user.collection(name)
            var after: DocumentSnapshot? = null
            // Pagini după id (cel mult 20 × 200): o ștergere încă neconfirmată nu face ca aceeași pagină să revină la nesfârșit.
            for (page in 0 until 20) {
                val snap = try {
                    withTimeoutOrNull(20_000L) {
                        val q = col.orderBy(FieldPath.documentId()).limit(200)
                        (if (after != null) q.startAfter(after!!) else q).get().await()
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: break
                for (d in snap.documents) try { d.reference.delete() } catch (_: Exception) { }
                if (snap.size() < 200) break
                after = snap.documents.last()
            }
        }
    }

    private fun targetsPass(app: ForjaApp, uid: String, t: SiteTargets) {
        try {
            val store = SiteSyncStore.of(app)
            val key = SiteSyncStore.targetsSignature(uid)
            if (store.getString(key, null) == t.signature) return
            CloudSync.targets(uid, SitePayloads.targetsDoc(t, System.currentTimeMillis()))
            store.edit().putString(key, t.signature).apply()
        } catch (_: Exception) { }
    }

    private suspend fun accountPass(app: ForjaApp, uid: String) {
        try {
            val store = SiteSyncStore.of(app)
            val key = SiteSyncStore.emailMoved(uid)
            if (store.getBoolean(key, false)) return
            if (app.auth.moveEmailToAccount(uid)) store.edit().putBoolean(key, true).apply()
        } catch (_: Exception) { }
    }

    /** uid-ul contului conectat, live (null după ieșire). Primește starea curentă imediat la abonare. */
    private fun authUid(): Flow<String?> = callbackFlow {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    // ── Room, citit direct (fără a atinge DAO-urile ecranului de antrenament) ──

    private fun finishedWorkouts(app: ForjaApp, since: Long, startedAfter: Long): List<WorkoutRecord> {
        val sql = """
            SELECT s.id, s.planId, s.planName, s.startedAt, s.endedAt, s.totalSets, p.meta
            FROM workout_sessions s LEFT JOIN plans p ON p.id = s.planId
            WHERE s.endedAt IS NOT NULL AND s.endedAt > ? AND s.startedAt >= ?
            ORDER BY s.endedAt LIMIT $MAX_WORKOUTS_PER_PASS
        """.trimIndent()
        val out = ArrayList<WorkoutRecord>()
        app.db.query(SimpleSQLiteQuery(sql, arrayOf<Any>(since, startedAfter))).use { c ->
            while (c.moveToNext()) {
                out += WorkoutRecord(
                    id = c.getLong(0),
                    planId = c.getInt(1),
                    planName = c.getString(2) ?: "",
                    startedAt = c.getLong(3),
                    endedAt = c.getLong(4),
                    totalSets = c.getInt(5),
                    planMeta = if (c.isNull(6)) null else c.getString(6)
                )
            }
        }
        return out
    }

    private fun setsOf(app: ForjaApp, sessionId: Long): List<SetRecord> {
        val out = ArrayList<SetRecord>()
        app.db.query(SimpleSQLiteQuery("SELECT reps, load FROM set_logs WHERE sessionId = ?", arrayOf<Any>(sessionId))).use { c ->
            while (c.moveToNext()) out += SetRecord(reps = c.getInt(0), load = c.getString(1) ?: "")
        }
        return out
    }
}
