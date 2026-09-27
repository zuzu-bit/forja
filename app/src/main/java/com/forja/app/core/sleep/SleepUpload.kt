package com.forja.app.core.sleep

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.data.CloudSync
import com.forja.app.core.network.SleepApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Urcarea automată a nopții (WorkManager) — la sfârșitul sesiunii:
 *  1. fiecare bucată → `PUT /v1/sleep-chunk` (bucățile deja urcate se sar; progresul e în `upload.json`)
 *  2. `POST /v1/sleep-analyze`
 *  3. sondaj `GET /v1/sleep-analysis` la 20 s, cel mult 20 min în total (peste mai multe rulări, dacă e nevoie)
 *  4. `timeline.json` salvat + rezumatul de dimineață refăcut cu cronologia + Firestore + notificare
 *     „Raportul nopții e gata” pe canalul „sleep” existent.
 *
 * Constrângeri: Wi-Fi (implicit) sau orice rețea dacă „și pe date mobile” e pornit; baterie ≥ 15 %
 * (constrângerea sistemului + verificare explicită). Clipurile de 5 s rămân locale și clasificate live, ca înainte.
 */
object SleepUpload {
    const val TAG = "sleep_upload"
    private const val PREFS = "forja_sleep"
    private const val KEY_CELLULAR = "sleep_upload_cellular"
    private const val KEY_DATA_SESSION = "session"
    const val NOTIF_ID = 35
    const val PROGRESS_FILE = "upload.json"
    const val POLL_EVERY_MS = 20_000L
    const val POLL_TOTAL_MS = 20L * 60_000L
    /** Cât sondăm într-o singură rulare (WorkManager oprește lucrările lungi la ~10 min). */
    const val POLL_PER_RUN_MS = 7L * 60_000L
    const val MAX_ATTEMPTS = 24
    const val MIN_BATTERY = 15
    const val SERVER_TTL_MS = 7L * 24 * 3600_000L

    /** Starea urcării unei sesiuni — în `sleep_full/<id>/upload.json`. */
    @Serializable
    data class Progress(
        val uploaded: List<Int> = emptyList(),
        val rejected: List<Int> = emptyList(),
        val analyzeRequestedAt: Long = 0L,
        val pollStartedAt: Long = 0L,
        val pollSpentMs: Long = 0L,
        val attempts: Int = 0,
        val done: Boolean = false,
        val lastError: String = "",
        val updatedAt: Long = 0L
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // ───────────── preferința „și pe date mobile” (SharedPreferences proprii; Prefs.kt rămâne neatins) ─────────────

    fun cellularAllowed(context: Context): Boolean =
        try { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CELLULAR, false) } catch (_: Exception) { false }

    fun setCellularAllowed(context: Context, on: Boolean) {
        try { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CELLULAR, on).apply() } catch (_: Exception) { }
    }

    // ───────────── programare ─────────────

    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (cellularAllowed(context)) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Pornește (sau lasă în coadă) urcarea sesiunii. Idempotent: KEEP pe numele unic al sesiunii. */
    fun schedule(context: Context, sessionId: Long, replace: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SleepUploadWorker>()
            .setInputData(workDataOf(KEY_DATA_SESSION to sessionId))
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$TAG:$sessionId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            req
        )
    }

    /** După schimbarea opțiunii de rețea: reprogramăm cu noile constrângeri (dacă mai e ceva de făcut). */
    fun reschedule(context: Context, sessionId: Long) {
        val p = loadProgress(AacRecorder.sessionDir(context.filesDir, sessionId))
        if (p?.done == true) return
        schedule(context, sessionId, replace = true)
    }

    fun loadProgress(dir: File): Progress? = try {
        val f = File(dir, PROGRESS_FILE)
        if (f.exists()) json.decodeFromString(Progress.serializer(), f.readText()) else null
    } catch (_: Exception) { null }

    fun saveProgress(dir: File, p: Progress) {
        try {
            dir.mkdirs()
            File(dir, PROGRESS_FILE).writeText(json.encodeToString(Progress.serializer(), p.copy(updatedAt = System.currentTimeMillis())))
        } catch (_: Exception) { }
    }

    /** Starea urcării, în cuvinte scurte, pentru ecranul de somn. */
    fun describe(context: Context, sessionId: Long): String {
        val dir = AacRecorder.sessionDir(context.filesDir, sessionId)
        val m = AacRecorder.manifestFor(context.filesDir, sessionId, 0L)
        val p = loadProgress(dir)
        return when {
            m == null -> "Nu există înregistrare pentru noaptea asta."
            p == null -> if (cellularAllowed(context)) "Urcarea pornește când ai net." else "Urcarea pornește pe Wi-Fi."
            p.done -> ""
            p.attempts >= MAX_ATTEMPTS -> "Urcarea a renunțat după $MAX_ATTEMPTS încercări."
            p.uploaded.size + p.rejected.size < m.chunks.size ->
                "Urcat ${p.uploaded.size} din ${m.chunks.size} bucăți" + (if (cellularAllowed(context)) "." else " (pe Wi-Fi).")
            p.analyzeRequestedAt > 0L -> "Serverul ascultă noaptea. Poate dura până la 20 min."
            else -> "Bucățile au urcat. Urmează analiza."
        }
    }

    fun batteryPercent(context: Context): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    } catch (_: Exception) { 100 }

    /** „Raportul nopții e gata” — canalul „sleep” existent; deschide aplicația. */
    fun notifyReady(context: Context, coverage: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, "sleep")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("Raportul nopții e gata")
            .setContentText(coverage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(coverage))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) } catch (_: Exception) { }
    }
}

class SleepUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong("session", 0L)
        if (sessionId <= 0L) return Result.failure()
        val app = ForjaApp.from(applicationContext)
        val api = SleepApi.get(app.forjaApi)
        if (!api.available) return Result.failure()

        val dir = AacRecorder.sessionDir(applicationContext.filesDir, sessionId)
        val manifest = AacRecorder.manifestFor(applicationContext.filesDir, sessionId, 0L) ?: return Result.failure()
        var p = SleepUpload.loadProgress(dir) ?: SleepUpload.Progress()
        if (p.done) return Result.success()
        if (p.attempts >= SleepUpload.MAX_ATTEMPTS) return Result.failure()
        p = p.copy(attempts = p.attempts + 1)
        SleepUpload.saveProgress(dir, p)

        if (SleepUpload.batteryPercent(applicationContext) < SleepUpload.MIN_BATTERY) {
            SleepUpload.saveProgress(dir, p.copy(lastError = "baterie sub ${SleepUpload.MIN_BATTERY} %"))
            return Result.retry()
        }

        // 1. bucățile, în ordine; ce s-a urcat deja se sare
        val chunksToSend = manifest.chunks.filter { it.dur > 0 || manifest.chunks.size == 1 }
        for (c in chunksToSend) {
            if (c.index in p.uploaded || c.index in p.rejected) continue
            val f = AacRecorder.chunkFile(applicationContext.filesDir, sessionId, c)
            when (val r = api.uploadChunk(sessionId, c, f)) {
                is SleepApi.Upload.Ok -> { p = p.copy(uploaded = p.uploaded + c.index); SleepUpload.saveProgress(dir, p) }
                is SleepApi.Upload.Rejected -> { p = p.copy(rejected = p.rejected + c.index, lastError = "bucata ${c.index}: refuzată (${r.code})"); SleepUpload.saveProgress(dir, p) }
                is SleepApi.Upload.Retry -> { SleepUpload.saveProgress(dir, p.copy(lastError = "bucata ${c.index}: ${r.why}")); return Result.retry() }
            }
        }
        val sent = chunksToSend.filter { it.index in p.uploaded }
        if (sent.isEmpty()) {
            finish(app, dir, sessionId, manifest, SleepTimeline("failed", "nicio bucată nu a putut urca", startedAt = manifest.startedAt), p)
            return Result.failure()
        }
        // Bucățile sunt pe server 7 zile: înregistrarea rămâne ascultabilă și după ce dispare de pe telefon.
        try { extendRecordedUntil(app, sessionId) } catch (_: Exception) { }

        // 2. analiza
        var timeline: SleepTimeline? = null
        if (p.analyzeRequestedAt == 0L) {
            timeline = api.analyze(sessionId, sent, manifest.startedAt) ?: run {
                SleepUpload.saveProgress(dir, p.copy(lastError = "analiza nu a putut fi cerută"))
                return Result.retry()
            }
            p = p.copy(analyzeRequestedAt = System.currentTimeMillis(), pollStartedAt = System.currentTimeMillis())
            SleepUpload.saveProgress(dir, p)
        }

        // 3. sondaj — la 20 s, cel mult 20 min în total (împărțite pe rulări)
        val runStart = System.currentTimeMillis()
        while (timeline == null || timeline.status == "processing") {
            val spentTotal = p.pollSpentMs + (System.currentTimeMillis() - runStart)
            if (spentTotal >= SleepUpload.POLL_TOTAL_MS) {
                timeline = SleepTimeline("timeout", "serverul nu a terminat în 20 de minute", startedAt = manifest.startedAt)
                break
            }
            if (System.currentTimeMillis() - runStart >= SleepUpload.POLL_PER_RUN_MS) {
                SleepUpload.saveProgress(dir, p.copy(pollSpentMs = spentTotal, lastError = ""))
                return Result.retry()
            }
            delay(SleepUpload.POLL_EVERY_MS)
            timeline = api.analysis(sessionId, manifest.startedAt, sent) ?: continue
        }

        val t = timeline ?: SleepTimeline("failed", "fără răspuns", startedAt = manifest.startedAt)
        finish(app, dir, sessionId, manifest, t, p.copy(pollSpentMs = p.pollSpentMs + (System.currentTimeMillis() - runStart)))
        return Result.success()
    }

    /** Salvează cronologia, reface rezumatul, sincronizează cifrele și anunță — o singură dată. */
    private suspend fun finish(app: ForjaApp, dir: File, sessionId: Long, manifest: AacRecorder.Manifest, t0: SleepTimeline, p: SleepUpload.Progress) {
        val sentMin = ((manifest.chunks.sumOf { it.dur }) / 60_000L).toInt()
        val t = if (t0.stats.totalMin == 0 && sentMin > 0) t0.copy(stats = t0.stats.copy(totalMin = sentMin)) else t0
        SleepTimeline.save(dir, t)
        SleepUpload.saveProgress(dir, p.copy(done = true, lastError = if (t.listened) "" else t.reason))
        if (!t.listened) return

        try {
            val dao = app.db.sleepDao()
            val sessions = dao.eventsForSessionOnce(sessionId)
            val snoreLocal = sessions.count { it.type == "snore" }
            val talkLocal = sessions.count { it.type == "talk" }
            val s = findSession(app, sessionId)
            if (s != null) {
                val totalMin = (((s.endAt ?: s.startAt) - s.startAt) / 60_000L).toInt().coerceAtLeast(1)
                val summary = try {
                    SleepApi.get(app.forjaApi).summaryWithTimeline(
                        totalMin, s.score, s.deepMin, s.remMin, s.movements,
                        maxOf(snoreLocal, t.stats.snoreEpisodes), maxOf(talkLocal, t.stats.talkCount), t
                    )
                } catch (_: Exception) { null }
                val updated = if (!summary.isNullOrBlank()) s.copy(summary = summary) else s
                if (updated != s) dao.update(updated)
                try {
                    CloudSync.sleep(
                        app.auth.currentUid, updated,
                        snoreCount = maxOf(snoreLocal, t.stats.snoreEpisodes),
                        talkCount = maxOf(talkLocal, t.stats.talkCount),
                        soundCount = sessions.count { it.type == "sound" },
                        snoreMin = t.stats.snoreMin,
                        coverageMin = t.stats.coverageMin
                    )
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        val cov = if (t.stats.coverageMin > 0 && t.stats.totalMin > 0)
            "Am ascultat ${hm(t.stats.coverageMin)} din ${hm(t.stats.totalMin)}."
        else "Cronologia nopții te așteaptă în Somn."
        SleepUpload.notifyReady(applicationContext, cov)
    }

    private suspend fun findSession(app: ForjaApp, sessionId: Long): com.forja.app.core.data.db.SleepSessionEntity? = try {
        app.db.sleepDao().recent(60).first().firstOrNull { it.id == sessionId }
    } catch (_: Exception) { null }

    /** Înregistrarea e pe server 7 zile — cardul „Înregistrarea nopții” rămâne vizibil atât. */
    private suspend fun extendRecordedUntil(app: ForjaApp, sessionId: Long) {
        val s = findSession(app, sessionId) ?: return
        val until = System.currentTimeMillis() + SleepUpload.SERVER_TTL_MS
        if (s.recordedUntil < until) app.db.sleepDao().update(s.copy(recordedUntil = until))
    }

    private fun hm(min: Int): String = if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)} min" else "$min min"
}
