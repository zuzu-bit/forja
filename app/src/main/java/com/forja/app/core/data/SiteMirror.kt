package com.forja.app.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.forja.app.ForjaApp
import com.forja.app.feature.nutrition.NutritionPrefs
import com.forja.app.feature.nutrition.Targets
import com.google.firebase.auth.FirebaseAuth
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
import kotlinx.coroutines.withContext
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

        // Emailul: o singură dată pe cont, la prima pornire cu contul conectat.
        app.appScope.launch {
            try {
                uids.collect { uid -> if (uid != null) accountPass(app, uid) }
            } catch (_: Exception) { }
        }
    }

    /** Trimite antrenamentele terminate după ultimul trimis (prima dată: ultimele 60 de zile). */
    suspend fun workoutsPass(app: ForjaApp, uid: String) = workoutsLock.withLock {
        try {
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

    private suspend fun targetsPass(app: ForjaApp, uid: String, t: SiteTargets) {
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
