package com.forja.app.core.explore

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.data.db.ExploreCellEntity
import com.forja.app.core.data.db.PlaceEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * Grila de explorare: celule de ~150 m în proiecția Web-Mercator.
 * id = "x_y" (întregi), stabil între sesiuni și dispozitive.
 */
object ExploreGrid {
    const val CELL_M = 150.0
    private const val R = 6378137.0

    fun cellOf(lat: Double, lng: Double): String {
        val x = floor(R * Math.toRadians(lng) / CELL_M).toLong()
        val y = floor(R * ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2)) / CELL_M).toLong()
        return "${x}_$y"
    }

    /** [minLat, minLng, maxLat, maxLng] pentru celula dată. */
    fun bounds(id: String): DoubleArray {
        val parts = id.split('_')
        val x = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val y = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val minLng = Math.toDegrees(x * CELL_M / R)
        val maxLng = Math.toDegrees((x + 1) * CELL_M / R)
        val minLat = Math.toDegrees(2 * atan(exp(y * CELL_M / R)) - PI / 2)
        val maxLat = Math.toDegrees(2 * atan(exp((y + 1) * CELL_M / R)) - PI / 2)
        return doubleArrayOf(minLat, minLng, maxLat, maxLng)
    }
}

/**
 * Candidatul de „ședere”: unde stai acum, de când, ultimul fix și cât timp ONEST ai stat.
 * Persistat în prefs.exploreCandidate ca JSON, ca receiverul din fundal să continue de unde a rămas.
 */
@Serializable
private data class DwellCandidate(
    val lat: Double,
    val lng: Double,
    val since: Long,
    val last: Long,
    val stayMs: Long = 0L,
    /** Locul deja creat/actualizat pentru această ședere (0 = niciunul încă). */
    val placeId: Long = 0L,
    /** Cât din stayMs a fost deja adunat în loc — ca să nu numărăm de două ori. */
    val syncedMs: Long = 0L,
    /** Ultima actualizare a locului în DB (throttle 5 min). */
    val placeSyncAt: Long = 0L
)

/**
 * Explorarea: ține minte zonele prin care ai trecut și locurile unde ai STAT.
 * Primește fixuri din toate sursele de locație (prezență, fundal, GO, hartă).
 *
 * Reguli: fix acceptat doar cu precizie ≤ 60 m; celula („teritoriul”) se cucerește la prima trecere pe jos, alergând
 * sau pe bicicletă — peste 12 m/s (43 km/h) nu se cucerește nimic, decât dacă sursa e GO (sport ales explicit);
 * vizită nouă după 30 min. Un LOC apare când ai stat ≥ prag (implicit 5 h) pe o rază de 100 m, cu viteză ≤ 2,5 m/s —
 * pauzele GPS și plimbatul nu se numără; o ședere nouă la > 6 h de ultima = încă o vizită. Oglindă în Firestore
 * (users/{uid}/explore, users/{uid}/places) + cifrele de clasament pe users/{uid} (exploreCells, placesCount).
 */
class ExploreTracker(private val app: ForjaApp) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Volatile private var candidate: DwellCandidate? = null
    @Volatile private var candidateLoaded = false
    @Volatile private var lastAcceptedAt = 0L

    init {
        // Tracker-ul se creează o dată pe proces, în ForjaApp.onCreate: tot atunci pornește oglinda pentru site
        // (antrenamente, rație, emailul mutat din profil). Idempotent.
        com.forja.app.core.data.SiteMirror.start(app)
    }

    fun onLocation(loc: Location, source: String = "") {
        onFix(
            lat = loc.latitude, lng = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
            speedMps = if (loc.hasSpeed()) loc.speed else 0f,
            atMs = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            source = source
        )
    }

    /** Ieftin, sigur din orice fir: filtrează și programează prelucrarea pe appScope. */
    fun onFix(
        lat: Double, lng: Double, accuracyM: Float, speedMps: Float,
        atMs: Long = System.currentTimeMillis(), source: String = ""
    ) {
        if (accuracyM > MAX_ACCURACY_M) return
        if (lat.isNaN() || lng.isNaN() || lat !in -85.0..85.0 || lng !in -180.0..180.0) return
        // Sursele rapide (GO la 1 s, harta la 3 s) nu au nevoie de fiecare fix — din 5 în 5 s e onest.
        if (atMs - lastAcceptedAt in 0L until MIN_GAP_MS) return
        lastAcceptedAt = atMs
        app.appScope.launch {
            try { ingest(lat, lng, accuracyM, speedMps, atMs, source) } catch (_: Exception) { }
        }
    }

    /** Varianta care așteaptă prelucrarea — pentru receiverul din fundal (goAsync). */
    suspend fun ingest(
        lat: Double, lng: Double, accuracyM: Float, speedMps: Float,
        atMs: Long = System.currentTimeMillis(), source: String = ""
    ) {
        if (accuracyM > MAX_ACCURACY_M) return
        mutex.withLock {
            if (!app.prefs.exploreOn.first()) return
            // Din mașină nu cucerești teritorii: peste 12 m/s ignorăm celula (GO rămâne valid — sportul e ales explicit).
            if (source == "go" || speedMps <= MAX_CONQUER_SPEED) touchCell(lat, lng, speedMps, atMs, source)
            dwell(lat, lng, speedMps, atMs, source)
        }
    }

    // ── Celule ──

    /** Cum cucerești: la GO după sportul ales, altfel după viteză (≥ 6 m/s bicicletă, ≥ 2,2 m/s alergare, restul pe jos). */
    private fun modeFor(speedMps: Float, source: String): String {
        if (source == "go") {
            val sport = com.forja.app.core.location.GoTrackService.state.value.sport
            if (sport == "run" || sport == "ride" || sport == "walk") return sport
        }
        return when {
            speedMps >= RIDE_SPEED -> "ride"
            speedMps >= RUN_SPEED -> "run"
            else -> "walk"
        }
    }

    private suspend fun touchCell(lat: Double, lng: Double, speedMps: Float, atMs: Long, source: String) {
        val id = ExploreGrid.cellOf(lat, lng)
        val dao = app.db.exploreDao()
        val existing = dao.cell(id)
        val updated: ExploreCellEntity
        val mirror: Boolean
        if (existing == null) {
            val b = ExploreGrid.bounds(id)
            updated = ExploreCellEntity(id, b[0], b[1], b[2], b[3], atMs, atMs, 1, modeFor(speedMps, source))
            mirror = true
        } else {
            val revisit = atMs - existing.lastAt > REVISIT_MS
            // Fără vizită nouă, scriem cel mult o dată pe minut: altfel fiecare fix (la 5 s) ar re-emite
            // allCells() în hartă și ar redesena overlay-ul degeaba.
            if (!revisit && atMs - existing.lastAt < CELL_WRITE_MIN_MS) return
            updated = existing.copy(
                lastAt = maxOf(existing.lastAt, atMs),
                visits = existing.visits + if (revisit) 1 else 0
            )
            mirror = revisit
        }
        dao.upsertCell(updated)
        if (mirror) mirrorCell(updated)
        if (existing == null) publishCounts(atMs)
    }

    private fun mirrorCell(c: ExploreCellEntity) {
        val uid = app.auth.currentUid ?: return
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("explore").document(c.id)
                .set(mapOf("firstAt" to c.firstAt, "lastAt" to c.lastAt, "visits" to c.visits, "mode" to c.mode), SetOptions.merge())
        } catch (_: Exception) { }
    }

    /** Cifrele de clasament pe users/{uid} — prietenii le citesc pentru „Loc #k între prieteni”. La fiecare celulă/loc nou. */
    private suspend fun publishCounts(atMs: Long) {
        val uid = app.auth.currentUid ?: return
        try {
            val dao = app.db.exploreDao()
            val cells = dao.countCellsOnce()
            val places = dao.countPlacesOnce()
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .set(mapOf("exploreCells" to cells, "placesCount" to places, "exploreUpdatedAt" to atMs), SetOptions.merge())
        } catch (_: Exception) { }
    }

    // ── Ședere → loc ──

    private suspend fun loadCandidate(): DwellCandidate? {
        if (candidateLoaded) return candidate
        candidate = try {
            val raw = app.prefs.exploreCandidate.first()
            if (raw.isBlank()) null else json.decodeFromString(DwellCandidate.serializer(), raw)
        } catch (_: Exception) { null }
        candidateLoaded = true
        return candidate
    }

    private suspend fun saveCandidate(c: DwellCandidate?) {
        candidate = c
        try {
            app.prefs.setExploreCandidate(if (c == null) "" else json.encodeToString(DwellCandidate.serializer(), c))
        } catch (_: Exception) { }
    }

    private suspend fun dwell(lat: Double, lng: Double, speedMps: Float, atMs: Long, source: String) {
        val cur = loadCandidate()
        if (cur == null) {
            saveCandidate(DwellCandidate(lat, lng, since = atMs, last = atMs))
            return
        }
        val dist = distanceM(cur.lat, cur.lng, lat, lng)
        if (dist > RADIUS_M) {
            // Ai plecat: ședere nouă, de aici. Ce a fost s-a numărat deja (sau nu a ajuns la prag).
            saveCandidate(DwellCandidate(lat, lng, since = atMs, last = atMs))
            return
        }
        val gap = atMs - cur.last
        val slow = speedMps <= MAX_DWELL_SPEED
        val added = if (slow && gap > 0) min(gap, MAX_GAP_MS) else 0L
        var next = cur.copy(last = maxOf(cur.last, atMs), stayMs = cur.stayMs + added)

        val thresholdMs = app.prefs.placeThresholdMin.first().coerceAtLeast(1) * 60_000L
        if (next.stayMs >= thresholdMs) {
            next = materialize(next, atMs, source)
        }
        saveCandidate(next)
    }

    /** Pragul e atins: creează locul (o singură dată) sau ține-l la zi (la cel mult 5 min). */
    private suspend fun materialize(c: DwellCandidate, atMs: Long, source: String): DwellCandidate {
        val dao = app.db.exploreDao()
        if (c.placeId == 0L) {
            val near = nearbyPlace(c.lat, c.lng)
            if (near != null) {
                // Loc deja cunoscut: o ședere nouă adaugă timpul ei; după > 6 h de la ultima dată e încă o vizită.
                val again = atMs - near.lastAt > REVISIT_PLACE_MS
                val upd = near.copy(lastAt = atMs, stayMs = near.stayMs + c.stayMs, visits = near.visits + if (again) 1 else 0)
                dao.updatePlace(upd)
                mirrorPlace(upd)
                return c.copy(placeId = near.id, syncedMs = c.stayMs, placeSyncAt = atMs)
            }
            val place = PlaceEntity(
                lat = c.lat, lng = c.lng,
                firstAt = c.since, lastAt = atMs,
                stayMs = c.stayMs, name = "", stars = 0, note = "",
                cellId = ExploreGrid.cellOf(c.lat, c.lng),
                updatedAt = atMs
            )
            val id = dao.insertPlace(place)
            val saved = place.copy(id = id)
            mirrorPlace(saved)
            publishCounts(atMs)
            notifyNewPlace(saved)
            ExploreSync.kick(app)
            return c.copy(placeId = id, syncedMs = c.stayMs, placeSyncAt = atMs)
        }
        if (atMs - c.placeSyncAt < PLACE_SYNC_MS) return c
        val existing = dao.place(c.placeId)
            ?: return c.copy(placeId = 0L, syncedMs = 0L, placeSyncAt = 0L) // șters de utilizator — nu-l reînviem în această ședere
        val delta = (c.stayMs - c.syncedMs).coerceAtLeast(0L)
        val upd = existing.copy(lastAt = atMs, stayMs = existing.stayMs + delta)
        dao.updatePlace(upd)
        mirrorPlace(upd)
        return c.copy(syncedMs = c.stayMs, placeSyncAt = atMs)
    }

    private suspend fun nearbyPlace(lat: Double, lng: Double): PlaceEntity? {
        val dLat = RADIUS_M / 111_320.0
        val dLng = RADIUS_M / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return app.db.exploreDao().placeNear(lat - dLat, lat + dLat, lng - dLng, lng + dLng)
    }

    // ── API pentru UI: salvare / ștergere cu oglindă ──

    /** Salvează editările (nume, stele, notă, recomandare), le oglindește în cont și, dacă e pornit „Și pe site”, le trimite. */
    suspend fun savePlace(p: PlaceEntity) {
        val stamped = p.copy(updatedAt = System.currentTimeMillis())
        app.db.exploreDao().updatePlace(stamped)
        mirrorPlace(stamped)
        ExploreSync.kick(app)
    }

    /** Șterge locul local, din cont și — dacă era recomandat — din places/. */
    suspend fun deletePlace(p: PlaceEntity) {
        app.db.exploreDao().deletePlace(p.id)
        ExploreSync.onPlaceDeleted(app, p)
        val uid = app.auth.currentUid ?: return
        try {
            val db = FirebaseFirestore.getInstance()
            db.collection("users").document(uid).collection("places").document("p${p.id}").delete()
            p.remoteId?.let { db.collection("places").document(it).delete() }
        } catch (_: Exception) { }
        val c = candidate
        if (c != null && c.placeId == p.id) saveCandidate(c.copy(placeId = 0L, syncedMs = c.stayMs, placeSyncAt = 0L))
    }

    /** Oglinda users/{uid}/places/p{id} — fire-and-forget, cache-ul Firestore o duce la capăt. */
    fun mirrorPlace(p: PlaceEntity) {
        val uid = app.auth.currentUid ?: return
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("places").document("p${p.id}")
                .set(
                    mapOf(
                        "lat" to p.lat, "lng" to p.lng,
                        "firstAt" to p.firstAt, "lastAt" to p.lastAt, "stayMs" to p.stayMs,
                        "name" to p.name, "stars" to p.stars, "note" to p.note,
                        "recommended" to p.recommended, "remoteId" to p.remoteId, "cellId" to p.cellId,
                        "visits" to p.visits
                    ),
                    SetOptions.merge()
                )
        } catch (_: Exception) { }
    }

    // ── Notificare ──

    private fun notifyNewPlace(p: PlaceEntity) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val pi = PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val h = p.stayMs / 3_600_000L
            val stayText = if (h >= 1) "$h h" else "${(p.stayMs / 60_000L).coerceAtLeast(1)} min"
            val n = NotificationCompat.Builder(app, "explore")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Un loc nou pe harta ta")
                .setContentText("Ai stat aici $stayText. Dă-i un nume și o notă.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(app).notify(NOTIF_BASE + (p.id % 1000).toInt(), n)
        } catch (_: Exception) { }
    }

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        return out[0].toDouble()
    }

    companion object {
        const val MAX_ACCURACY_M = 60f
        const val RADIUS_M = 100.0
        const val MAX_DWELL_SPEED = 2.5f
        const val MAX_GAP_MS = 5 * 60_000L
        const val REVISIT_MS = 30 * 60_000L
        const val CELL_WRITE_MIN_MS = 60_000L
        const val MIN_GAP_MS = 5_000L
        const val PLACE_SYNC_MS = 5 * 60_000L
        /** Peste 12 m/s (43 km/h) ești în mașină — nu cucerești teritorii. */
        const val MAX_CONQUER_SPEED = 12f
        const val RUN_SPEED = 2.2f
        const val RIDE_SPEED = 6f
        /** O ședere la > 6 h de ultima = vizită nouă la același loc. */
        const val REVISIT_PLACE_MS = 6 * 3_600_000L
        private const val NOTIF_BASE = 4100
    }
}
