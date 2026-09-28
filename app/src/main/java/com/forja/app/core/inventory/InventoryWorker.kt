package com.forja.app.core.inventory

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// ═══════════════ Inventar 4.3 — lucrătorul din fundal ═══════════════
// WorkManager, lucrare unică „inventory” (REPLACE la o pornire nouă, KEEP la reluare), în prim-plan (dataSync) cu
// notificarea „Inventar · 34 %” pe canalul „inventory”. Dacă prim-planul nu e permis (aplicația în fundal pe
// Android 12+), lucrează ca job obișnuit; WorkManager îl repornește după oprire, iar punctele de reluare de pe disc
// (după fiecare stagiu, după fiecare lot AI) nu lasă munca să se piardă.

class InventoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        InventoryNotify.foregroundInfo(applicationContext, Inventory.progress.value, null)

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN) ?: return Result.failure()
        val app = ForjaApp.from(applicationContext)
        val stored = withContext(Dispatchers.IO) { InventoryStore.readRun(app, runId) }?.meta
        val meta = stored ?: metaFrom(inputData, runId) ?: return Result.failure()
        if (meta.stage !in RUNNING) return Result.success()
        if (!Inventory.claim(app, runId)) return Result.success()   // înlocuită între timp de o rulare nouă
        val ctl = RunCtl(app, meta.copy(attempts = meta.attempts + 1))
        try {
            withContext(Dispatchers.IO) { ctl.saveMeta() }
            if (ctl.meta.attempts > MAX_ATTEMPTS) {
                Inventory.fail(ctl, "Inventarul s-a oprit de prea multe ori. Pornește-l din nou.")
                return Result.failure()
            }
            try {
                setForeground(InventoryNotify.foregroundInfo(app, Inventory.progress.value, null))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fără prim-plan (ForegroundServiceStartNotAllowedException, tip lipsă): continuăm ca job obișnuit.
            }
            when (ctl.meta.kind) {
                InvKind.Photos -> PhotoPipeline.run(ctl)
                InvKind.Documents -> DocPipeline.run(ctl)
            }
            return Result.success()
        } catch (e: CancellationException) {
            // Oprită (de utilizator, de o rulare nouă sau de sistem): nicio actualizare nu mai vine după asta, deci
            // notificarea de progres nu rămâne agățată; la o repornire, lucrătorul o pune din nou.
            ctl.finish()
            InventoryNotify.cancelProgress(app)
            throw e
        } catch (e: InvFailure) {
            Inventory.fail(ctl, e.message ?: FAIL_TEXT)
            return Result.failure()
        } catch (_: Exception) {
            Inventory.fail(ctl, FAIL_TEXT)
            return Result.failure()
        } finally {
            Inventory.release(runId)
        }
    }

    companion object {
        internal const val WORK_NAME = "inventory"
        private const val TAG = "inventory"
        private const val KEY_RUN = "run"
        private const val KEY_KIND = "kind"
        private const val KEY_CREATED = "created"
        private const val KEY_LAST_N = "last_n"
        private const val KEY_BUCKET = "bucket"
        private const val KEY_TREE = "tree"
        private const val NO_BUCKET = Long.MIN_VALUE
        /** Porniri ale aceleiași rulări (procese ucise, opriri de sistem) după care renunțăm cu o eroare clară. */
        private const val MAX_ATTEMPTS = 8
        private const val FAIL_TEXT = "Nu a mers. Încearcă din nou."
        private val RUNNING = setOf(InvStage.Scanning, InvStage.Grouping, InvStage.Naming)

        /** REPLACE = o pornire nouă (oprește rularea veche); KEEP = reluare (nu dublează o lucrare deja la coadă). */
        internal fun enqueue(ctx: Context, meta: RunMeta, replace: Boolean) {
            val data = workDataOf(
                KEY_RUN to meta.runId,
                KEY_KIND to meta.kind.name,
                KEY_CREATED to meta.createdAt,
                KEY_LAST_N to (meta.lastN ?: -1),
                KEY_BUCKET to (meta.bucketId ?: NO_BUCKET),
                KEY_TREE to meta.tree
            )
            val req = OneTimeWorkRequestBuilder<InventoryWorker>()
                .setInputData(data)
                .addTag(TAG)
                .build()
            try {
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req
                )
            } catch (_: Exception) { }
        }

        internal fun cancelWork(ctx: Context) {
            try { WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) } catch (_: Exception) { }
        }

        /** Rularea abia pornită nu are încă fișier pe disc: o refacem din datele lucrării. */
        private fun metaFrom(d: Data, runId: String): RunMeta? {
            val kind = try { InvKind.valueOf(d.getString(KEY_KIND) ?: return null) } catch (_: Exception) { return null }
            return RunMeta(
                runId = runId,
                kind = kind,
                createdAt = d.getLong(KEY_CREATED, System.currentTimeMillis()),
                lastN = d.getInt(KEY_LAST_N, -1).takeIf { it > 0 },
                bucketId = d.getLong(KEY_BUCKET, NO_BUCKET).takeIf { it != NO_BUCKET },
                tree = d.getString(KEY_TREE)
            )
        }
    }
}

/**
 * Starea unei rulări în lucru: progresul global (monoton, ponderat pe stagii după estimare), „cutiile”, miniaturile
 * recente, salvarea stării pe disc (cel mult o dată la 5 s) și notificarea (cel mult o dată pe secundă).
 * Când rularea nu mai e cea curentă (anulată / înlocuită) sau s-a terminat, tace și [checkAlive] oprește lucrul.
 */
internal class RunCtl(val app: ForjaApp, meta: RunMeta) {
    @Volatile var meta: RunMeta = meta
    val ctx: Context get() = app
    val runId: String get() = meta.runId
    @Volatile var bins: List<BinTick> = emptyList()

    private val recent = ArrayDeque<Uri>()
    private var best = meta.done.coerceIn(0, InvRules.PROGRESS_SCALE - 1)
    private var lastEmit = 0L
    private var lastNotify = 0L
    private var lastSave = 0L
    @Volatile private var finished = false

    fun alive(): Boolean = !finished && Inventory.isCurrent(runId)

    fun checkAlive() {
        if (!alive()) throw CancellationException("Inventarul a fost oprit sau înlocuit.")
    }

    fun finish() { finished = true }

    @Synchronized
    fun addRecent(uri: Uri?) {
        if (uri == null) return
        recent.remove(uri)
        recent.addLast(uri)
        while (recent.size > 8) recent.removeFirst()
    }

    /** Ponderile stagiilor în procentul global, din estimare (secunde): scanare, grupare, AI. */
    fun setEstimates(scanSec: Int, groupSec: Int, aiSec: Int) {
        val total = (scanSec + groupSec + aiSec).coerceAtLeast(1).toFloat()
        meta = meta.copy(
            spanScan = scanSec / total, spanGroup = groupSec / total,
            estScanSec = scanSec, estGroupSec = groupSec, estAiSec = aiSec
        )
        saveMeta()
    }

    @Synchronized
    fun report(stage: InvStage, frac: Double, eta: Int?, detail: String?, force: Boolean = false) {
        if (!alive()) return
        val f = frac.coerceIn(0.0, 1.0)
        val s = meta.spanScan.toDouble().coerceIn(0.0, 1.0)
        val g = meta.spanGroup.toDouble().coerceIn(0.0, 1.0 - s)
        val overall = when (stage) {
            InvStage.Scanning -> f * s
            InvStage.Grouping -> s + f * g
            InvStage.Naming -> s + g + f * (1.0 - s - g)
            else -> 1.0
        }
        // Sub 100 % până la Ready: procentul plin îl dă doar planul salvat.
        best = max(best, (overall * InvRules.PROGRESS_SCALE).roundToInt().coerceIn(0, InvRules.PROGRESS_SCALE - 1))
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEmit < 250) return
        lastEmit = now
        val p = InvProgress(runId, meta.kind, stage, best, InvRules.PROGRESS_SCALE, eta?.coerceAtLeast(0), recent.toList(), bins)
        Inventory.publish(p)
        if (force || now - lastNotify >= 1_000) {
            lastNotify = now
            InventoryNotify.showProgress(ctx, p, detail)
        }
        if (force || now - lastSave >= 5_000) {
            lastSave = now
            meta = meta.copy(stage = stage, done = best, total = InvRules.PROGRESS_SCALE)
            saveMeta()
        }
    }

    /** Începutul unui stagiu: salvat pe disc imediat (reluarea știe unde a rămas). */
    fun enter(stage: InvStage, detail: String? = null) {
        meta = meta.copy(stage = stage)
        report(stage, 0.0, null, detail, force = true)
    }

    fun saveMeta() {
        if (!alive()) return
        try { InventoryStore.writeRun(ctx, RunFile(meta.copy(updatedAt = System.currentTimeMillis()))) } catch (_: Exception) { }
    }
}

/**
 * Notificările inventarului (canalul „inventory”, creat și în ForjaApp): progres, „Dosarele sunt gata”, eroare.
 * Textele vin din core/notify (InventoryCopy): titlul cu procent e informație, rândul de sub el se schimbă după etapă;
 * „Originalele rămân pe telefon” doar la galerie. Grupul propriu, ca Samsung să nu-l amestece cu alte notificări.
 */
internal object InventoryNotify {
    const val CHANNEL = "inventory"
    const val ID_PROGRESS = com.forja.app.core.notify.NotifIds.INVENTORY_PROGRESS
    const val ID_RESULT = com.forja.app.core.notify.NotifIds.INVENTORY_RESULT
    private val Copy = com.forja.app.core.notify.InventoryCopy

    fun ensureChannel(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Inventar", NotificationManager.IMPORTANCE_LOW))
            }
        } catch (_: Exception) { }
    }

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** „Deschide” → MainActivity cu extra forja_route = "cleanup" (ecranul Inventar). */
    private fun openIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(OrganizerJobs.ROUTE_EXTRA, Route.CLEANUP)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, ID_PROGRESS, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun stageLine(p: InvProgress?): String = Copy.stageLine(
        when (p?.stage) {
            InvStage.Grouping -> "grouping"
            InvStage.Naming -> "naming"
            InvStage.Applying -> "applying"
            else -> "scanning"
        },
        photos = p?.kind != InvKind.Documents
    )

    fun progress(ctx: Context, p: InvProgress?, detail: String?): Notification {
        val total = p?.total?.coerceAtLeast(1) ?: 1
        val done = p?.done?.coerceIn(0, total) ?: 0
        val open = openIntent(ctx)
        // Rândul cald al etapei sub titlul cu procent; contorul exact al etapei („Dau nume · 12 din 40”) în antet.
        val line = stageLine(p)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.forja.app.R.drawable.ic_notify)
            .setContentTitle(if (p == null) Copy.TITLE else Copy.progressTitle(done * 100 / total))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setProgress(total, done, p == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setGroup(com.forja.app.core.notify.Groups.INVENTORY)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, Copy.OPEN, open)
        detail?.takeIf { it.isNotBlank() }?.let { b.setSubText(it) }
        return b.build()
    }

    /** Prim-planul lucrătorului: tip dataSync (constructorul cu tip există de la API 29; obligatoriu de la 34). */
    fun foregroundInfo(ctx: Context, p: InvProgress?, detail: String?): ForegroundInfo {
        ensureChannel(ctx)
        val n = progress(ctx, p, detail)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ID_PROGRESS, n)
    }

    fun showProgress(ctx: Context, p: InvProgress, detail: String?) {
        if (!canPost(ctx)) return
        try { NotificationManagerCompat.from(ctx).notify(ID_PROGRESS, progress(ctx, p, detail)) } catch (_: Exception) { }
    }

    /** „Dosarele sunt gata · 14 dosare” — „3 214 poze · 212 la gunoi. Tu decizi ce pleacă.” */
    fun ready(ctx: Context, kind: InvKind, folders: Int, items: Int, trash: Int) {
        cancelProgress(ctx)
        if (!canPost(ctx)) return
        post(ctx, Copy.readyTitle(folders), Copy.readyText(kind == InvKind.Photos, items, trash), com.forja.app.core.notify.NudgePose.Happy)
    }

    fun failed(ctx: Context, message: String) {
        cancelProgress(ctx)
        if (!canPost(ctx)) return
        post(ctx, Copy.FAILED_TITLE, message, com.forja.app.core.notify.NudgePose.Sorry)
    }

    private fun post(ctx: Context, title: String, text: String, pose: com.forja.app.core.notify.NudgePose) {
        try {
            ensureChannel(ctx)
            val open = openIntent(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.forja.app.R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setGroup(com.forja.app.core.notify.Groups.INVENTORY)
                .setContentIntent(open)
                .addAction(0, Copy.OPEN, open)
            com.forja.app.core.notify.MascotIcons.bitmap(ctx, pose)?.let { n.setLargeIcon(it) }
            NotificationManagerCompat.from(ctx).notify(ID_RESULT, n.build())
        } catch (_: Exception) { }
    }

    fun cancelProgress(ctx: Context) {
        try { NotificationManagerCompat.from(ctx).cancel(ID_PROGRESS) } catch (_: Exception) { }
    }

    fun cancelResult(ctx: Context) {
        try { NotificationManagerCompat.from(ctx).cancel(ID_RESULT) } catch (_: Exception) { }
    }

    fun cancelAll(ctx: Context) {
        cancelProgress(ctx)
        cancelResult(ctx)
    }
}
