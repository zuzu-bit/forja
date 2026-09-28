package com.forja.app.core.explore

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forja.app.ForjaApp
import com.forja.app.core.data.SiteSyncStore
import com.forja.app.core.data.db.PlaceEntity
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.TimeUnit

/**
 * Lucrătorul de sincronizare a explorării cu site-ul: periodic (30 min, cu net) sau la cerere
 * (după o editare). Cu date de intrare `tombstones` trimite doar ștergerile date.
 */
class ExploreSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = ForjaApp.from(applicationContext)
        val tombstones = inputData.getString(ExploreSync.KEY_TOMBSTONES)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val outcome = if (tombstones.isNotEmpty()) {
            ExploreSync.pushTombstones(app, tombstones, inputData.getLong(ExploreSync.KEY_TOMBSTONE_AT, System.currentTimeMillis()))
        } else {
            ExploreSync.push(app)
        }
        return when (outcome) {
            ExploreSync.Outcome.DONE, ExploreSync.Outcome.SKIPPED, ExploreSync.Outcome.REJECTED -> Result.success()
            ExploreSync.Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object { const val MAX_ATTEMPTS = 6 }
}

/**
 * Explorarea „Și pe site”: telefonul rămâne sursa; trimite în contul FORJA (site) doar celulele
 * de 150 m (cu modul cuceririi: pe jos, alergând, pe bicicletă) și locurile (nume, stele, notă, vizite) — și numai
 * cât timp „Și pe site” e pornit și contractul e semnat.
 * Editările făcute din laptop se preiau la deschiderea hărții ([pull]); câștigă cea mai nouă.
 *
 * Când pleacă: periodic (30 min, cu net, programat la semnarea contractului), la o celulă nouă (cel mult o dată
 * la 5 min, [kickSoon]) și la orice loc creat sau editat ([kick]).
 */
object ExploreSync {
    enum class Outcome { DONE, SKIPPED, RETRY, REJECTED }

    const val WORK_PERIODIC = "explore-sync"
    const val WORK_NOW = "explore-sync-now"
    const val WORK_CELL = "explore-sync-cell"
    const val WORK_TOMBSTONE = "explore-sync-tombstone"
    const val KEY_TOMBSTONES = "tombstones"
    const val KEY_TOMBSTONE_AT = "tombstone_at"
    private const val PATH_SYNC = "/v2/social/explore/sync"
    private const val PATH_STATE = "/v2/social/explore/state"
    private const val PATH_HEALTH = "/health"
    private const val CELLS_PER_PAGE = 400
    private const val PLACES_PER_PAGE = 100
    private const val PERIOD_MIN = 30L
    private const val FRIENDS_TIMEOUT_MS = 10_000L
    /** Cel mult o sincronizare pornită de celule noi la 5 minute (pe jos cucerești o celulă la ~2 min). */
    const val CELL_KICK_GAP_MS = 5 * 60_000L
    /** Cât ținem minte ce schemă are serverul (`/health` → `explore_sync`). */
    private const val HEALTH_TTL_MS = 6 * 3_600_000L

    /** O singură trimitere odată: lucrătorii (periodic, acum, celulă nouă) pot porni în paralel. */
    private val pushLock = Mutex()
    private val kickGuard = Any()

    private fun connected(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Sincronizarea periodică (unică, 30 min, doar cu net). Idempotentă: nu resetează ceasul dacă există deja. */
    fun schedule(context: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<ExploreSyncWorker>(PERIOD_MIN, TimeUnit.MINUTES)
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Exception) { }
    }

    /** Oprește orice sincronizare programată (comutatorul a fost închis). */
    fun cancel(context: Context) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_PERIODIC)
            wm.cancelUniqueWork(WORK_NOW)
            wm.cancelUniqueWork(WORK_CELL)
            wm.cancelUniqueWork(WORK_TOMBSTONE)
        } catch (_: Exception) { }
        try { SiteSyncStore.of(context).edit().remove(SiteSyncStore.EXPLORE_CELL_KICK_AT).apply() } catch (_: Exception) { }
    }

    /** O sincronizare acum (după o editare). Ieftin și sigur din orice fir; nu face nimic cu comutatorul oprit. */
    fun kick(context: Context) {
        val app = ForjaApp.from(context)
        app.appScope.launch {
            try {
                if (!app.prefs.exploreSyncSite.first() || app.auth.currentUid == null) return@launch
                val req = OneTimeWorkRequestBuilder<ExploreSyncWorker>()
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            } catch (_: Exception) { }
        }
    }

    /**
     * O celulă nouă a fost cucerită: harta de pe site o primește în cel mult 5 minute, fără să trimitem la fiecare
     * celulă. Prima celulă după o pauză pleacă imediat; cele din următoarele 5 minute așteaptă o singură rulare,
     * programată la capătul ferestrei. Se așteaptă din tracker (și din receptorul de fundal, care ține procesul viu
     * până la capăt); nu face nimic cu „Și pe site” oprit.
     */
    suspend fun kickSoon(app: ForjaApp) {
        try {
            if (!app.prefs.exploreSyncSite.first() || app.auth.currentUid == null) return
            val store = SiteSyncStore.of(app)
            val now = System.currentTimeMillis()
            val delay = synchronized(kickGuard) {
                val d = KickThrottle.delayMs(now, store.getLong(SiteSyncStore.EXPLORE_CELL_KICK_AT, 0L), CELL_KICK_GAP_MS)
                    ?: return
                store.edit().putLong(SiteSyncStore.EXPLORE_CELL_KICK_AT, now + d).apply()
                d
            }
            val req = OneTimeWorkRequestBuilder<ExploreSyncWorker>()
                .setConstraints(connected())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK_CELL, ExistingWorkPolicy.KEEP, req)
        } catch (_: Exception) { }
    }

    /**
     * Un loc șters local: trimite imediat ștergerea (`deleted:true`); dacă nu merge netul, o lasă
     * unui lucrător cu reîncercare. Fără comutator pornit nu pleacă nimic.
     */
    fun onPlaceDeleted(context: Context, p: PlaceEntity) {
        val app = ForjaApp.from(context)
        val ids = ExplorePayload.tombstoneIds(p)
        val at = System.currentTimeMillis()
        app.appScope.launch {
            try {
                if (!app.prefs.exploreSyncSite.first() || app.auth.currentUid == null) return@launch
                if (pushTombstones(app, ids, at) != Outcome.RETRY) return@launch
                val req = OneTimeWorkRequestBuilder<ExploreSyncWorker>()
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .setInputData(workDataOf(KEY_TOMBSTONES to ids.joinToString(","), KEY_TOMBSTONE_AT to at))
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(WORK_TOMBSTONE + ":" + ids.first(), ExistingWorkPolicy.REPLACE, req)
            } catch (_: Exception) { }
        }
    }

    /** Id-ul locului pe site: id-ul recomandării (places/) când există, altfel „p<id local>”. */
    fun remoteId(p: PlaceEntity): String = ExplorePayload.remoteId(p)

    /**
     * Trimite ce s-a schimbat de la ultima sincronizare (minus 15 min de suprapunere): celule (pagini ≤ 400) și locuri
     * (pagini ≤ 100), apoi reține `server_at`. Cu serverul pe schema 2 pleacă și modul celulei și vizitele locului; prima
     * dată retrimitem tot, ca celulele vechi să-și primească modul. Nimic nu pleacă fără comutator, contract și cont.
     */
    suspend fun push(app: ForjaApp): Outcome = pushLock.withLock { pushLocked(app) }

    private suspend fun pushLocked(app: ForjaApp): Outcome {
        if (!app.prefs.exploreSyncSite.first()) return Outcome.SKIPPED
        if (!app.prefs.contractSigned.first()) return Outcome.SKIPPED
        val uid = app.auth.currentUid ?: return Outcome.SKIPPED
        val store = SiteSyncStore.of(app)
        val dao = app.db.exploreDao()
        val startedAt = System.currentTimeMillis()
        val schema = serverSchema(app)
        val v2 = schema >= ExplorePayload.SCHEMA_V2
        val full = ExplorePayload.needsFullResend(
            serverSchema = schema,
            schemaSent = store.getInt(SiteSyncStore.EXPLORE_SCHEMA_SENT, 0),
            lastUid = store.getString(SiteSyncStore.EXPLORE_UID, null),
            uid = uid
        )
        val since = ExplorePayload.since(app.prefs.exploreSyncedAt.first(), full)
        val cells = dao.cellsChangedSince(since)
        val places = dao.placesChangedSince(since)
        if (cells.isEmpty() && places.isEmpty()) {
            // Nimic de trimis nici la o retrimitere completă: nu avem celule fără mod pe site.
            if (full) remember(store, uid, schema)
            return Outcome.SKIPPED
        }

        val device = InsightsApi.deviceId(app.prefs)()
        val family = app.prefs.familyUids.first()
        val friends = if (places.any { it.recommended }) friendUids(app, uid) else emptySet()

        var serverAt = 0L
        try {
            for (page in ExplorePayload.pages(cells.map { ExplorePayload.cell(it, v2) }, CELLS_PER_PAGE)) {
                serverAt = post(device, page, emptyList())
            }
            val entries = places.flatMap { ExplorePayload.placeEntries(it, family, friends, v2) }
            for (page in ExplorePayload.pages(entries, PLACES_PER_PAGE)) {
                serverAt = post(device, emptyList(), page)
            }
        } catch (e: InsightsFailure) {
            val outcome = classify(e)
            // Schema v2 refuzată: serverul s-a întors la v1 (sau cache-ul e vechi) — recitim /health data viitoare.
            if (outcome == Outcome.REJECTED && v2) forgetSchema(store)
            return outcome
        } catch (_: Exception) {
            return Outcome.RETRY
        }
        // Ceasul telefonului poate fi în urma serverului: nu sărim peste modificările dintre ele.
        if (serverAt > 0) app.prefs.setExploreSyncedAt(minOf(serverAt, startedAt))
        remember(store, uid, if (full || !v2) schema else store.getInt(SiteSyncStore.EXPLORE_SCHEMA_SENT, schema))
        schedule(app)
        return Outcome.DONE
    }

    /** Ștergeri explicite (`deleted:true`) pentru id-urile date. */
    suspend fun pushTombstones(app: ForjaApp, ids: List<String>, at: Long): Outcome {
        if (ids.isEmpty()) return Outcome.SKIPPED
        if (!app.prefs.exploreSyncSite.first() || app.auth.currentUid == null) return Outcome.SKIPPED
        return try {
            val device = InsightsApi.deviceId(app.prefs)()
            for (page in ExplorePayload.pages(ids.map { ExplorePayload.tombstone(it, at) }, PLACES_PER_PAGE)) {
                post(device, emptyList(), page)
            }
            Outcome.DONE
        } catch (e: InsightsFailure) {
            classify(e)
        } catch (_: Exception) {
            Outcome.RETRY
        }
    }

    /**
     * La deschiderea hărții: preia editările făcute din laptop (nume, stele, notă) pentru locurile
     * al căror `updated_at` de pe site e mai nou decât cel local.
     */
    suspend fun pull(app: ForjaApp) {
        if (!app.prefs.exploreSyncSite.first()) return
        if (app.auth.currentUid == null) return
        val dao = app.db.exploreDao()
        val resp = try { InsightsApi.json(PATH_STATE) } catch (_: Exception) { return }
        val list = resp["places"]?.jsonArray ?: return
        for (element in list) {
            val o = element as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val updatedAt = o["updated_at"]?.jsonPrimitive?.longOrNull ?: continue
            val localId = if (id.length > 1 && id.startsWith("p") && id.drop(1).all { it.isDigit() }) id.drop(1).toLongOrNull() else null
            val local = (if (localId != null) dao.place(localId) else dao.placeByRemoteId(id)) ?: continue
            if (updatedAt <= local.updatedAt) continue
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: local.name
            val stars = o["stars"]?.jsonPrimitive?.intOrNull ?: local.stars
            val note = o["note"]?.jsonPrimitive?.contentOrNull ?: local.note
            val merged = local.copy(
                name = name.take(80),
                stars = stars.coerceIn(0, 5),
                note = note.take(300),
                updatedAt = updatedAt
            )
            try {
                dao.updatePlace(merged)
                app.explore.mirrorPlace(merged)
            } catch (_: Exception) { }
        }
    }

    // ── intern ──

    private fun classify(e: InsightsFailure): Outcome =
        if (e.code in 400..499 && e.code != 408 && e.code != 429) Outcome.REJECTED else Outcome.RETRY

    /**
     * Schema serverului (`/health` → `explore_sync`), ținută minte 6 h. Fără răspuns folosim ultima valoare știută,
     * iar fără niciuna rămânem pe v1 — câmpurile noi nu pleacă spre un server care le-ar respinge.
     */
    private suspend fun serverSchema(app: ForjaApp): Int {
        val store = SiteSyncStore.of(app)
        val cached = store.getInt(SiteSyncStore.HEALTH_EXPLORE_SYNC, 0)
        val at = store.getLong(SiteSyncStore.HEALTH_AT, 0L)
        val now = System.currentTimeMillis()
        if (cached > 0 && now - at in 0 until HEALTH_TTL_MS) return cached
        val fresh = try {
            InsightsApi.json(PATH_HEALTH)["explore_sync"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) { null }
        if (fresh != null && fresh > 0) {
            store.edit().putInt(SiteSyncStore.HEALTH_EXPLORE_SYNC, fresh).putLong(SiteSyncStore.HEALTH_AT, now).apply()
            return fresh
        }
        return cached.takeIf { it > 0 } ?: ExplorePayload.SCHEMA_V1
    }

    private fun forgetSchema(store: android.content.SharedPreferences) {
        store.edit().remove(SiteSyncStore.HEALTH_EXPLORE_SYNC).remove(SiteSyncStore.HEALTH_AT).apply()
    }

    private fun remember(store: android.content.SharedPreferences, uid: String, schemaSent: Int) {
        store.edit().putString(SiteSyncStore.EXPLORE_UID, uid).putInt(SiteSyncStore.EXPLORE_SCHEMA_SENT, schemaSent).apply()
    }

    private suspend fun friendUids(app: ForjaApp, uid: String): Set<String> = try {
        withTimeoutOrNull(FRIENDS_TIMEOUT_MS) { app.friends.friendsFlow(uid).first() }
            ?.map { it.uid }?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }

    /** Un POST explore/sync; întoarce `server_at`. */
    private suspend fun post(device: String, cells: List<JsonObject>, places: List<JsonObject>): Long {
        val body = buildJsonObject {
            put("device", device)
            put("grid_m", ExplorePayload.GRID_M)
            put("reset", false)
            putJsonArray("cells") { cells.forEach { add(it) } }
            putJsonArray("places") { places.forEach { add(it) } }
        }
        val resp = InsightsApi.json(PATH_SYNC, body)
        return resp["server_at"]?.jsonPrimitive?.longOrNull ?: 0L
    }
}
