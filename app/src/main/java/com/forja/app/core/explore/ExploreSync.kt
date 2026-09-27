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
import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.data.db.PlaceEntity
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * de 150 m și locurile (nume, stele, notă) — și numai cât timp prefs.exploreSyncSite e pornit.
 * Editările făcute din laptop se preiau la deschiderea hărții ([pull]); câștigă cea mai nouă.
 */
object ExploreSync {
    enum class Outcome { DONE, SKIPPED, RETRY, REJECTED }

    const val WORK_PERIODIC = "explore-sync"
    const val WORK_NOW = "explore-sync-now"
    const val WORK_TOMBSTONE = "explore-sync-tombstone"
    const val KEY_TOMBSTONES = "tombstones"
    const val KEY_TOMBSTONE_AT = "tombstone_at"
    private const val PATH_SYNC = "/v2/social/explore/sync"
    private const val PATH_STATE = "/v2/social/explore/state"
    private const val GRID_M = 150
    private const val CELLS_PER_PAGE = 400
    private const val PLACES_PER_PAGE = 100
    /** Serverul primește ≤ 64 KiB pe cerere; paginăm și după octeți, nu doar după număr. */
    private const val PAGE_BYTES = 56_000
    private const val PERIOD_MIN = 30L
    private const val FRIENDS_TIMEOUT_MS = 10_000L

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
            wm.cancelUniqueWork(WORK_TOMBSTONE)
        } catch (_: Exception) { }
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
     * Un loc șters local: trimite imediat ștergerea (`deleted:true`); dacă nu merge netul, o lasă
     * unui lucrător cu reîncercare. Fără comutator pornit nu pleacă nimic.
     */
    fun onPlaceDeleted(context: Context, p: PlaceEntity) {
        val app = ForjaApp.from(context)
        val ids = tombstoneIds(p)
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
    fun remoteId(p: PlaceEntity): String = p.remoteId?.takeIf { it.isNotBlank() } ?: "p${p.id}"

    /** Toate id-urile sub care locul a putut ajunge pe site (înainte și după recomandare). */
    private fun tombstoneIds(p: PlaceEntity): List<String> =
        listOfNotNull(p.remoteId?.takeIf { it.isNotBlank() }, "p${p.id}").distinct()

    /**
     * Trimite ce s-a schimbat de la ultima sincronizare: celule (pagini ≤ 400) și locuri (pagini ≤ 100),
     * apoi reține `server_at`. Nimic nu pleacă fără comutator pornit și cont conectat.
     */
    suspend fun push(app: ForjaApp): Outcome {
        if (!app.prefs.exploreSyncSite.first()) return Outcome.SKIPPED
        val uid = app.auth.currentUid ?: return Outcome.SKIPPED
        val dao = app.db.exploreDao()
        val startedAt = System.currentTimeMillis()
        val since = app.prefs.exploreSyncedAt.first()
        val cells = dao.cellsChangedSince(since)
        val places = dao.placesChangedSince(since)
        if (cells.isEmpty() && places.isEmpty()) return Outcome.SKIPPED

        val device = InsightsApi.deviceId(app.prefs)()
        val family = app.prefs.familyUids.first()
        val friends = if (places.any { it.recommended }) friendUids(app, uid) else emptySet()

        var serverAt = 0L
        try {
            for (page in pages(cells.map { cellJson(it) }, CELLS_PER_PAGE)) {
                serverAt = post(device, page, emptyList())
            }
            val entries = places.flatMap { placeEntries(it, family, friends) }
            for (page in pages(entries, PLACES_PER_PAGE)) {
                serverAt = post(device, emptyList(), page)
            }
        } catch (e: InsightsFailure) {
            return classify(e)
        } catch (_: Exception) {
            return Outcome.RETRY
        }
        // Ceasul telefonului poate fi în urma serverului: nu sărim peste modificările dintre ele.
        if (serverAt > 0) app.prefs.setExploreSyncedAt(minOf(serverAt, startedAt))
        schedule(app)
        return Outcome.DONE
    }

    /** Ștergeri explicite (`deleted:true`) pentru id-urile date. */
    suspend fun pushTombstones(app: ForjaApp, ids: List<String>, at: Long): Outcome {
        if (ids.isEmpty()) return Outcome.SKIPPED
        if (!app.prefs.exploreSyncSite.first() || app.auth.currentUid == null) return Outcome.SKIPPED
        return try {
            val device = InsightsApi.deviceId(app.prefs)()
            for (page in pages(ids.map { tombstoneJson(it, at) }, PLACES_PER_PAGE)) post(device, emptyList(), page)
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

    private suspend fun friendUids(app: ForjaApp, uid: String): Set<String> = try {
        withTimeoutOrNull(FRIENDS_TIMEOUT_MS) { app.friends.friendsFlow(uid).first() }
            ?.map { it.uid }?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }

    /** Pagini de cel mult [maxCount] intrări și ~[PAGE_BYTES] octeți (o intrare mare merge singură). */
    private fun pages(items: List<JsonObject>, maxCount: Int): List<List<JsonObject>> {
        val out = ArrayList<List<JsonObject>>()
        var page = ArrayList<JsonObject>()
        var bytes = 0
        for (item in items) {
            val size = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (page.isNotEmpty() && (page.size >= maxCount || bytes + size > PAGE_BYTES)) {
                out.add(page); page = ArrayList(); bytes = 0
            }
            page.add(item); bytes += size
        }
        if (page.isNotEmpty()) out.add(page)
        return out
    }

    /** Coordonate la 6 zecimale (~11 cm): destul pentru hartă, de trei ori mai puțini octeți. */
    private fun coord(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    /** Text pe o singură linie, fără caractere de control (serverul le respinge). */
    private fun line(v: String, max: Int): String =
        v.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").trim().take(max)

    /** Nota poate avea rânduri noi; restul caracterelor de control pleacă. */
    private fun note(v: String, max: Int): String =
        v.replace("\r\n", "\n").replace(Regex("[\\u0000-\\u0008\\u000B-\\u001F\\u007F]"), " ").trim().take(max)

    /** Un POST explore/sync; întoarce `server_at`. */
    private suspend fun post(device: String, cells: List<JsonObject>, places: List<JsonObject>): Long {
        val body = buildJsonObject {
            put("device", device)
            put("grid_m", GRID_M)
            put("reset", false)
            putJsonArray("cells") { cells.forEach { add(it) } }
            putJsonArray("places") { places.forEach { add(it) } }
        }
        val resp = InsightsApi.json(PATH_SYNC, body)
        return resp["server_at"]?.jsonPrimitive?.longOrNull ?: 0L
    }

    private fun cellJson(c: ExploreCellEntity): JsonObject = buildJsonObject {
        put("id", c.id)
        put("min_lat", coord(c.minLat))
        put("min_lng", coord(c.minLng))
        put("max_lat", coord(maxOf(c.maxLat, c.minLat + 0.000001)))
        put("max_lng", coord(maxOf(c.maxLng, c.minLng + 0.000001)))
        put("first_at", c.firstAt)
        put("last_at", maxOf(c.lastAt, c.firstAt))
        put("visits", c.visits.coerceAtLeast(1))
    }

    /**
     * Locul ca intrări de sincronizare. Când locul a primit între timp un id de recomandare,
     * vechiul „p<id>” de pe site se șterge, ca să nu apară de două ori.
     */
    private fun placeEntries(p: PlaceEntity, family: Set<String>, friends: Set<String>): List<JsonObject> {
        val id = remoteId(p)
        val visible = LinkedHashSet<String>()
        visible.addAll(family)
        if (p.recommended) visible.addAll(friends)
        val place = buildJsonObject {
            put("id", id)
            put("lat", coord(p.lat))
            put("lng", coord(p.lng))
            put("first_at", p.firstAt)
            put("last_at", maxOf(p.lastAt, p.firstAt))
            put("stay_ms", p.stayMs.coerceAtLeast(0L))
            put("name", line(p.name, 80))
            put("stars", p.stars.coerceIn(0, 5))
            put("note", note(p.note, 300))
            put("recommended", p.recommended)
            putJsonArray("visible_to") { visible.take(100).forEach { add(it) } }
            put("updated_at", p.updatedAt.coerceAtLeast(0L))
            put("deleted", false)
        }
        val legacy = "p${p.id}"
        return if (id != legacy) listOf(tombstoneJson(legacy, p.updatedAt.coerceAtLeast(0L)), place) else listOf(place)
    }

    private fun tombstoneJson(id: String, at: Long): JsonObject = buildJsonObject {
        put("id", id)
        put("deleted", true)
        put("updated_at", at.coerceAtLeast(0L))
    }
}
