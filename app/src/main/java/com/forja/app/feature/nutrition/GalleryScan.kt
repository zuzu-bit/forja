package com.forja.app.feature.nutrition

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.network.MealAnalysis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** O masă găsită în galerie — așteaptă confirmarea utilizatorului (nimic automat). */
@Serializable
data class PendingMeal(
    val analysis: MealAnalysis,
    val at: Long,
    val photoPath: String
)

/**
 * Scanarea galeriei à la Bixby Vision — DOAR cu acordul explicit al utilizatorului:
 * pozele zilei sunt privite local, cele cu mâncare merg la analiza serverului,
 * iar rezultatele așteaptă confirmarea în aplicație. Nicio salvare fără OK-ul tău.
 */
object GalleryScan {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasReadPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun readPermissionName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Ultimele poze din galerie (cele mai noi primele). */
    fun recentImages(context: Context, limit: Int, sinceMillis: Long = 0L): List<Pair<Uri, Long>> {
        val out = mutableListOf<Pair<Uri, Long>>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && out.size < limit) {
                    val takenAt = cursor.getLong(dateCol) * 1000L
                    if (sinceMillis > 0 && takenAt < sinceMillis) break
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol).toString()
                    )
                    out.add(uri to takenAt)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Analizează pozele (3 în paralel); întoarce mesele găsite (fără să salveze nimic). */
    suspend fun scan(
        app: ForjaApp,
        images: List<Pair<Uri, Long>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<PendingMeal> = coroutineScope {
        val found = java.util.Collections.synchronizedList(mutableListOf<PendingMeal>())
        var done = 0
        onProgress(0, images.size)
        images.chunked(3).forEach { batch ->
            batch.map { pair ->
                async {
                    val bytes = MealAnalyze.readUri(app, pair.first, maxSide = 1120) ?: return@async
                    when (val res = MealAnalyze.analyzeJpeg(app, bytes)) {
                        is AnalyzeOutcome.Ok -> {
                            val path = MealAnalyze.savePhoto(app, bytes)
                            if (path != null) found.add(PendingMeal(res.analysis, pair.second, path))
                        }
                        is AnalyzeOutcome.Fail -> { /* nu e mâncare sau analiza a picat */ }
                    }
                }
            }.awaitAll()
            done += batch.size
            onProgress(done, images.size)
        }
        found.toList()
    }

    // ── Coada de confirmare (nimic nu intră în jurnal fără OK-ul tău) ──────────
    private fun pendingFile(context: Context) = File(context.filesDir, "pending_gallery.json")

    fun loadPending(context: Context): List<PendingMeal> = try {
        val f = pendingFile(context)
        if (!f.exists()) emptyList()
        else json.decodeFromString<List<PendingMeal>>(f.readText())
    } catch (_: Exception) { emptyList() }

    fun savePending(context: Context, items: List<PendingMeal>) {
        try {
            if (items.isEmpty()) pendingFile(context).delete()
            else pendingFile(context).writeText(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(PendingMeal.serializer()),
                    items
                )
            )
        } catch (_: Exception) { }
    }

    // ── Scanarea automată de seară ─────────────────────────────────────────────
    fun scheduleDaily(context: Context) {
        val req = PeriodicWorkRequestBuilder<GalleryScanWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(4, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "gallery_scan", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    fun cancelDaily(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("gallery_scan")
    }
}

class GalleryScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ForjaApp ?: return Result.success()
        try {
            if (!app.prefs.galleryScanOn.first()) return Result.success()
            if (!GalleryScan.hasReadPermission(applicationContext)) return Result.success()
            if (app.auth.currentUid == null) return Result.success()

            val since = com.forja.app.core.util.Fmt.startOfDayMillis()
            val images = GalleryScan.recentImages(applicationContext, limit = 12, sinceMillis = since)
            if (images.isEmpty()) return Result.success()

            val existing = GalleryScan.loadPending(applicationContext)
            val knownTimes = existing.map { it.at }.toSet()
            val fresh = images.filter { it.second !in knownTimes }
            if (fresh.isEmpty()) return Result.success()

            val found = GalleryScan.scan(app, fresh)
            if (found.isEmpty()) return Result.success()
            GalleryScan.savePending(applicationContext, existing + found)

            // Notificare blândă: tu confirmi, noi doar am găsit.
            val pi = PendingIntent.getActivity(
                applicationContext, 11,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(applicationContext, "social")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Mese găsite în pozele de azi")
                .setContentText("${found.size} ${if (found.size == 1) "masă așteaptă" else "mese așteaptă"} confirmarea ta în FORJA.")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(35, notif)
        } catch (_: Exception) { }
        return Result.success()
    }
}
