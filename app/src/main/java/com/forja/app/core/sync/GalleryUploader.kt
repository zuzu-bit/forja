package com.forja.app.core.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Galeria întreagă, treptat — partea din contract care înlocuiește „Fotografii selectate (maximum 5)”.
 * Fiecare rulare ia următoarele [BATCH] poze din MediaStore (după _ID crescător; cursorul e persistat), face
 * miniaturi JPEG ≤ 512 px și le urcă în seiful de fișiere al contului (`PUT /v2/files/{id}`, kind photo,
 * dosarul = albumul), aceeași cale ca copiile de curățenie. Doar pe Wi-Fi implicit, cu bateria bună.
 * Cât mai rămâne de urcat, o rulare nouă se pune la rând la câteva minute; altfel lucrătorul zilnic ia
 * pozele noi (id-uri mai mari decât cursorul). Fără contract semnat sau fără cont nu urcă nimic.
 *
 * Serverul păstrează azi o copie 24 h și cel mult 500 de elemente pe cont / 1 500 de urcări pe zi; la 429 lotul
 * se oprește și reluăm la rularea următoare, fără să pierdem cursorul.
 */
object GalleryUploader {
    const val KEY_ON = "gallery_all"
    const val KEY_CURSOR = "gallery_cursor"
    const val KEY_COUNT = "gallery_uploaded"
    const val KEY_STATUS = "gallery_status"
    const val KEY_CELLULAR = "gallery_cellular"
    const val WORK_DAILY = "gallery-upload"
    const val WORK_NEXT = "gallery-upload-next"
    const val BATCH = 50
    private const val THUMB_SIDE = 512
    private const val THUMB_MAX = 160_000

    fun on(c: Context): Boolean = CollectionSettings.prefs(c).getBoolean(KEY_ON, false) && CollectionSettings.contractOn(c)
    fun uploaded(c: Context): Int = CollectionSettings.prefs(c).getInt(KEY_COUNT, 0)
    fun status(c: Context): String = CollectionSettings.prefs(c).getString(KEY_STATUS, "") ?: ""
    fun cellularAllowed(c: Context): Boolean = CollectionSettings.prefs(c).getBoolean(KEY_CELLULAR, false)

    private fun constraints(c: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (cellularAllowed(c)) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** La ON_START și la semnare: lucrătorul zilnic există cât timp contractul e semnat; altfel dispare. Idempotent. */
    fun scheduleIfOn(c: Context) {
        try {
            if (!on(c) || currentUid() == null) { cancel(c); return }
            val req = PeriodicWorkRequestBuilder<GalleryUploadWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(c))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP, req)
            next(c, 1)
        } catch (_: Exception) { }
    }

    /** Următorul lot, la câteva minute — cât mai e ceva de urcat. */
    private fun next(c: Context, delayMin: Long) {
        try {
            val req = OneTimeWorkRequestBuilder<GalleryUploadWorker>()
                .setConstraints(constraints(c))
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK_NEXT, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) { }
    }

    fun cancel(c: Context) {
        try {
            val wm = WorkManager.getInstance(c)
            wm.cancelUniqueWork(WORK_DAILY)
            wm.cancelUniqueWork(WORK_NEXT)
        } catch (_: Exception) { }
    }

    private fun currentUid(): String? = try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }

    private fun canRead(c: Context): Boolean {
        fun has(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES)
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun setStatus(c: Context, text: String) {
        CollectionSettings.prefs(c).edit().putString(KEY_STATUS, text).apply()
    }

    private class Row(val id: Long, val name: String, val album: String, val mime: String)

    /** Următoarele [BATCH] poze după cursor, în ordinea id-urilor (MediaStore le dă crescător în timp). */
    private fun nextRows(c: Context, after: Long): List<Row> {
        val out = ArrayList<Row>(BATCH)
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE
        )
        var selection = "${MediaStore.Images.Media._ID} > ?"
        if (Build.VERSION.SDK_INT >= 30) selection += " AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        try {
            c.contentResolver.query(collection, cols, selection, arrayOf(after.toString()), "${MediaStore.Images.Media._ID} ASC")?.use { cur ->
                val idC = cur.getColumnIndex(MediaStore.Images.Media._ID)
                val nameC = cur.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val albumC = cur.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeC = cur.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                while (cur.moveToNext() && out.size < BATCH) {
                    out += Row(
                        cur.getLong(idC),
                        (if (nameC >= 0) cur.getString(nameC) else null) ?: "poză",
                        (if (albumC >= 0) cur.getString(albumC) else null) ?: "",
                        (if (mimeC >= 0) cur.getString(mimeC) else null) ?: "image/jpeg"
                    )
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Rezultatul unei rulări: a mers (cu sau fără rest), sau de reîncercat. */
    enum class Outcome { DONE, MORE, SKIPPED, RETRY }

    suspend fun runBatch(app: ForjaApp): Outcome {
        val p = CollectionSettings.prefs(app)
        if (!on(app)) return Outcome.SKIPPED
        val uid = app.auth.currentUid ?: return Outcome.SKIPPED
        if (CollectionSettings.owner(app) != uid) return Outcome.SKIPPED
        if (!canRead(app)) { setStatus(app, "Galeria așteaptă permisiunea „Poze & galerie” din Echipare."); return Outcome.SKIPPED }
        val rows = nextRows(app, p.getLong(KEY_CURSOR, 0L))
        if (rows.isEmpty()) { setStatus(app, "Galeria e pe site: ${uploaded(app)} miniaturi. Pozele noi urcă zilnic."); return Outcome.DONE }
        val device = try { InsightsApi.deviceId(app.prefs)() } catch (_: Exception) { return Outcome.RETRY }
        try { OrganizerJobs.ensureGrant(app) } catch (e: CancellationException) { throw e } catch (e: InsightsFailure) {
            setStatus(app, e.message ?: "Site-ul nu a răspuns."); return if (e.code == 401) Outcome.SKIPPED else Outcome.RETRY
        } catch (_: Exception) { return Outcome.RETRY }
        val engine = CleanupEngine(app, app.prefs)
        var count = uploaded(app)
        var cursor = p.getLong(KEY_CURSOR, 0L)
        var full = false
        for (r in rows) {
            if (!on(app)) return Outcome.SKIPPED
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, r.id)
            val thumb = engine.thumbnailJpeg(uri, THUMB_SIDE, THUMB_MAX)
            if (thumb == null || thumb.isEmpty()) { cursor = r.id; continue }   // necitibilă sau ștearsă între timp — mergem mai departe
            val headers = mapOf(
                "X-Device-ID" to device,
                "X-File-Kind" to "photo",
                "X-File-Name" to InsightsApi.header(r.name.take(200).replace(Regex("[\\p{Cntrl}]"), "_")),
                "X-File-Folder" to InsightsApi.header(("Galerie/" + r.album.take(100)).trimEnd('/', ' ')),
                "X-Media-Type" to "image/jpeg",
                "X-File-Sha256" to SyncTransport.sha256(thumb)
            )
            try {
                InsightsApi.upload("/v2/files/${UUID.randomUUID()}", thumb, "application/octet-stream", "PUT", headers)
                count++
            } catch (e: CancellationException) { throw e } catch (e: InsightsFailure) {
                when (e.code) {
                    429 -> { full = true; break }                       // seiful e plin sau limita zilnică — reluăm mai târziu
                    401 -> { setStatus(app, "Contul nu mai este conectat."); return Outcome.SKIPPED }
                    400, 403, 404, 409, 410, 413, 422 -> { }            // refuzată pentru această poză — nu blocăm restul
                    else -> { persist(p, cursor, count); setStatus(app, "Site-ul nu a răspuns. Reluăm cu net."); return Outcome.RETRY }
                }
            } catch (_: Exception) {
                persist(p, cursor, count); setStatus(app, "Fără conexiune. Reluăm cu Wi-Fi."); return Outcome.RETRY
            }
            cursor = r.id
        }
        persist(p, cursor, count)
        if (full) { setStatus(app, "Site-ul a atins limita de copii; galeria continuă când se eliberează loc."); return Outcome.RETRY }
        setStatus(app, "Galeria urcă treptat: $count miniaturi pe site.")
        return if (rows.size < BATCH) Outcome.DONE else Outcome.MORE
    }

    private fun persist(p: android.content.SharedPreferences, cursor: Long, count: Int) {
        p.edit().putLong(KEY_CURSOR, cursor).putInt(KEY_COUNT, count).apply()
    }

    /** După un lot cu rest: încă unul peste câteva minute (tot pe Wi-Fi, tot cu bateria bună). */
    internal fun onBatchOutcome(c: Context, outcome: Outcome) {
        when (outcome) {
            Outcome.MORE -> next(c, 3)
            Outcome.RETRY -> next(c, 30)
            else -> Unit
        }
    }
}

/** Lucrătorul (zilnic sau „următorul lot”): un lot de 50 de miniaturi per rulare. */
class GalleryUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = ForjaApp.from(applicationContext)
        val outcome = try { GalleryUploader.runBatch(app) } catch (e: CancellationException) { throw e } catch (_: Exception) { GalleryUploader.Outcome.RETRY }
        GalleryUploader.onBatchOutcome(applicationContext, outcome)
        return when (outcome) {
            GalleryUploader.Outcome.RETRY -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
