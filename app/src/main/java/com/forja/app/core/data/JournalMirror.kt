package com.forja.app.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.sqlite.db.SimpleSQLiteQuery
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.ActivityEntity
import com.forja.app.core.data.db.MealEntity
import com.forja.app.core.music.MusicStats
import com.forja.app.core.music.PlayEvent
import com.forja.app.core.network.InsightsApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Jurnalele pe site, complete și stabile (mirror, pachetul C):
 *  - mesele și turele primesc un id stabil (`cloudId`, din momentul lor) în locul lui m{id}/a{id} din Room, care după o
 *    reinstalare o luau de la 1 și suprascriau istoria; documentul vechi se șterge doar dacă e chiar al rândului (același `at`);
 *  - o trecere de reconciliere (la conectare, apoi la 6 ore) retrimite tot ce e în Room și n-a urcat (mesele salvate fără cont,
 *    scrierile pierdute înainte de 4.x), cu ce a găsit analiza (componente, scor, sfat);
 *  - antrenamentele (din [SiteMirror]): payload v2 cu exerciții, serii, muzica de atunci, dus la capăt sau nu;
 *  - poza mesei: cu contractul v4, o miniatură de 512 px urcă pe serverul site-ului (`PUT /insights/api/ratie/photo/{id}`) și
 *    rămâne cât ai contul, ca jurnalul. Mesele ultimelor 90 de zile cu poză o primesc la prima trecere după semnare.
 * Jurnalele nu țin de contract (sunt ale contului, ca în aplicație); doar poza cere v4.
 */
object JournalMirror {
    private const val PASS_EVERY_MS = 6 * 3_600_000L
    private const val BATCH = 300
    private const val PHOTO_EDGE = 512
    private const val PHOTO_MAX = 190_000
    private const val PHOTO_DAYS = 90L
    private const val PHOTOS_PER_PASS = 40

    private val started = AtomicBoolean(false)
    private val lock = Mutex()

    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        app.appScope.launch {
            try {
                callbackFlow {
                    val auth = FirebaseAuth.getInstance()
                    val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
                    auth.addAuthStateListener(l)
                    awaitClose { auth.removeAuthStateListener(l) }
                }.distinctUntilChanged().collectLatest { uid ->
                    if (uid == null) return@collectLatest
                    delay(8_000L)
                    while (true) {
                        pass(app, uid)
                        delay(PASS_EVERY_MS)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /** O trecere: mesele, turele, apoi pozele meselor (v4). Idempotentă (set cu merge pe id-uri stabile). */
    suspend fun pass(app: ForjaApp, uid: String) = lock.withLock {
        try {
            Journals.claim(app, uid)
            withContext(Dispatchers.IO) { mealsPass(app, uid); activitiesPass(app, uid) }
            if (app.prefs.contractAtLeast(4).first()) photosPass(app, uid)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private fun mealFrom(c: android.database.Cursor) = MealEntity(
        id = c.getLong(0), epochDay = c.getLong(1), mealType = c.getInt(2), name = c.getString(3) ?: "", kcal = c.getInt(4), protein = c.getInt(5),
        carbs = c.getInt(6), fat = c.getInt(7), grams = c.getInt(8), source = c.getString(9) ?: "", confidence = c.getString(10) ?: "", at = c.getLong(11),
        details = c.getString(12), cloudId = c.getString(13), photoPath = c.getString(14)
    )
    private const val MEAL_COLS = "id, epochDay, mealType, name, kcal, protein, carbs, fat, grams, source, confidence, at, details, cloudId, photoPath"

    private fun mealsPass(app: ForjaApp, uid: String) {
        val store = SiteSyncStore.of(app)
        val key = SiteSyncStore.journalUpTo(uid, "meals")
        var upTo = store.getLong(key, 0L)
        while (true) {
            val rows = ArrayList<MealEntity>()
            app.db.query(SimpleSQLiteQuery("SELECT $MEAL_COLS FROM meals WHERE id > ? AND (ownerUid IS NULL OR ownerUid = ?) ORDER BY id LIMIT $BATCH", arrayOf<Any>(upTo, uid))).use { c ->
                while (c.moveToNext()) rows += mealFrom(c)
            }
            if (rows.isEmpty()) break
            for (m0 in rows) {
                val m = stamp(app, "meals", m0.id, m0.cloudId, SitePayloads.mealCloudId(m0.at)).let { m0.copy(cloudId = it) }
                CloudSync.meal(uid, m)
                dropLegacy(uid, "meals", "m${m.id}", "at", m.at)
            }
            upTo = rows.last().id
            store.edit().putLong(key, upTo).apply()
            if (rows.size < BATCH) break
        }
    }

    private fun activitiesPass(app: ForjaApp, uid: String) {
        val store = SiteSyncStore.of(app)
        val key = SiteSyncStore.journalUpTo(uid, "activities")
        var upTo = store.getLong(key, 0L)
        while (true) {
            val rows = ArrayList<ActivityEntity>()
            app.db.query(SimpleSQLiteQuery("SELECT id, startAt, endAt, distanceM, durationS, kcal, polyline, type, cloudId FROM activities WHERE id > ? AND (ownerUid IS NULL OR ownerUid = ?) ORDER BY id LIMIT 100", arrayOf<Any>(upTo, uid))).use { c ->
                while (c.moveToNext()) rows += ActivityEntity(id = c.getLong(0), startAt = c.getLong(1), endAt = c.getLong(2), distanceM = c.getDouble(3), durationS = c.getLong(4),
                    kcal = c.getInt(5), polyline = c.getString(6) ?: "", type = c.getString(7) ?: "run", cloudId = c.getString(8))
            }
            if (rows.isEmpty()) break
            for (a0 in rows) {
                val a = a0.copy(cloudId = stamp(app, "activities", a0.id, a0.cloudId, SitePayloads.activityCloudId(a0.startAt)))
                CloudSync.activity(uid, a)
                dropLegacy(uid, "activities", "a${a.id}", "startAt", a.startAt)
            }
            upTo = rows.last().id
            store.edit().putLong(key, upTo).apply()
            if (rows.size < 100) break
        }
    }

    /** Rândul primește id-ul stabil o singură dată (după asta nu se mai schimbă, chiar dacă momentul e editat). */
    private fun stamp(app: ForjaApp, table: String, id: Long, current: String?, fresh: String): String {
        if (current != null) return current
        try { app.db.openHelper.writableDatabase.execSQL("UPDATE `$table` SET cloudId = ? WHERE id = ? AND cloudId IS NULL", arrayOf<Any>(fresh, id)) } catch (_: Exception) { }
        return fresh
    }

    /** Documentul vechi (m{id}, a{id}, w{id}) se șterge doar dacă e chiar al acestui rând: după o reinstalare, m5 poate fi altă masă. */
    private fun dropLegacy(uid: String, col: String, docId: String, field: String, value: Long) {
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid).collection(col).document(docId).get()
                .addOnSuccessListener { d -> if (d.exists() && d.getLong(field) == value) d.reference.delete() }
        } catch (_: Exception) { }
    }

    // ─────────────────────────── antrenamente (payload v2) ───────────────────────────

    /** Chemată din trecerea antrenamentelor ([SiteMirror]): fiecare cu exercițiile, seriile, planul și muzica de atunci. */
    suspend fun sendWorkouts(app: ForjaApp, uid: String, todo: List<WorkoutRecord>) {
        if (todo.isEmpty()) return
        val plays = try { MusicStats.rows(app).filter { it.event == PlayEvent.PLAY && it.isMusic } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        withContext(Dispatchers.IO) {
            for (w in todo) {
                val sets = ArrayList<SetRecord>()
                app.db.query(SimpleSQLiteQuery("SELECT reps, load, exerciseName, setNo, at FROM set_logs WHERE sessionId = ? ORDER BY at, setNo", arrayOf<Any>(w.id))).use { c ->
                    while (c.moveToNext()) sets += SetRecord(reps = c.getInt(0), load = c.getString(1) ?: "", exercise = c.getString(2) ?: "", setNo = c.getInt(3), at = c.getLong(4))
                }
                val planned = if (w.planId < 0) null else try {
                    app.db.query(SimpleSQLiteQuery("SELECT SUM(e.sets) FROM exercises e INNER JOIN plan_exercises pe ON pe.exerciseId = e.id WHERE pe.planId = ?", arrayOf<Any>(w.planId))).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
                    }
                } catch (_: Exception) { null }
                val current = app.db.query(SimpleSQLiteQuery("SELECT cloudId FROM workout_sessions WHERE id = ?", arrayOf<Any>(w.id))).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val docId = stamp(app, "workout_sessions", w.id, current, SitePayloads.workoutCloudId(w.startedAt))
                val music = plays.filter { it.at in w.startedAt..w.endedAt }.map { TrackRecord(it.title, it.artist, it.at) }
                CloudSync.workout(uid, docId, SitePayloads.workoutV2(w, sets, planned, music))
                dropLegacy(uid, "workouts", "w${w.id}", "startAt", w.startedAt)
            }
        }
    }

    // ─────────────────────────── poza mesei (contract v4) ───────────────────────────

    /** După salvarea unei mese cu poză: urcă acum (fără să aștepte trecerea de 6 ore). */
    fun kick(app: ForjaApp, meal: MealEntity) {
        if (meal.photoPath.isNullOrBlank()) return
        app.appScope.launch {
            try {
                val uid = app.auth.currentUid ?: return@launch
                if (!app.prefs.contractAtLeast(4).first()) return@launch
                mealPhoto(app, uid, meal)
            } catch (_: Exception) { }
        }
    }

    /** Masa ștearsă din telefon: și poza ei de pe site (dacă a urcat). */
    fun forgetPhoto(app: ForjaApp, meal: MealEntity) {
        app.appScope.launch {
            try { InsightsApi.json("/insights/api/ratie/photo/" + CloudSync.mealId(meal), null, "DELETE") } catch (_: Exception) { }
        }
    }

    private suspend fun photosPass(app: ForjaApp, uid: String) {
        val since = System.currentTimeMillis() - PHOTO_DAYS * 86_400_000L
        val rows = withContext(Dispatchers.IO) {
            val out = ArrayList<MealEntity>()
            app.db.query(SimpleSQLiteQuery("SELECT $MEAL_COLS FROM meals WHERE photoPath IS NOT NULL AND at >= ? AND (ownerUid IS NULL OR ownerUid = ?) ORDER BY at DESC LIMIT 400", arrayOf<Any>(since, uid))).use { c ->
                while (c.moveToNext()) out += mealFrom(c)
            }
            out
        }
        val sent = sentPhotos(app, uid)
        var n = 0
        for (m in rows) {
            if (CloudSync.mealId(m) in sent) continue
            if (n++ >= PHOTOS_PER_PASS) break
            if (!mealPhoto(app, uid, m)) break
        }
    }

    private fun sentPhotos(app: ForjaApp, uid: String): Set<String> =
        SiteSyncStore.of(app).getString(SiteSyncStore.mealPhotosSent(uid), "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    /** false = serverul sau rețeaua n-au răspuns (trecerea se oprește; se reia data viitoare). */
    private suspend fun mealPhoto(app: ForjaApp, uid: String, m: MealEntity): Boolean {
        val id = CloudSync.mealId(m)
        val bytes = withContext(Dispatchers.IO) { photoBytes(m.photoPath) } ?: return true
        return try {
            InsightsApi.upload("/insights/api/ratie/photo/$id", bytes, "image/jpeg", "PUT")
            CloudSync.meal(uid, m.copy(cloudId = id), photo = true)
            val store = SiteSyncStore.of(app)
            val list = (sentPhotos(app, uid).toList() + id).takeLast(400)
            store.edit().putString(SiteSyncStore.mealPhotosSent(uid), list.joinToString(",")).apply()
            true
        } catch (e: CancellationException) { throw e } catch (e: com.forja.app.core.network.InsightsFailure) {
            e.code in listOf(400, 404, 413)  // o poză refuzată nu oprește restul; 403 (fără v4), 5xx, rețea: stop
        } catch (_: Exception) { false }
    }

    private fun photoBytes(path: String?): ByteArray? {
        val f = path?.let { File(it) }?.takeIf { it.isFile } ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_EDGE) sample *= 2
            val raw = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longest = max(raw.width, raw.height)
            val bmp = if (longest > PHOTO_EDGE) Bitmap.createScaledBitmap(raw, max(1, (raw.width * PHOTO_EDGE.toFloat() / longest).roundToInt()), max(1, (raw.height * PHOTO_EDGE.toFloat() / longest).roundToInt()), true).also { if (it !== raw) raw.recycle() } else raw
            var q = 82
            var out: ByteArray
            do {
                out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
                q -= 12
            } while (out.size > PHOTO_MAX && q >= 40)
            bmp.recycle()
            out.takeIf { it.size <= PHOTO_MAX }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}
