package com.forja.app.core.sync

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.forja.app.ForjaApp
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic (min 15 min pe Android) — verifică dacă SyncService rulează.
 * Dacă Android l-a ucis (doze, battery saver, memory pressure), il repornește.
 * E ultima linie de apărare pentru persistența 24/7.
 */
class SyncKeepAliveWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val app = applicationContext as? ForjaApp ?: return Result.success()
        if (app.auth.currentUid == null) return Result.success()
        if (!SyncService.running) {
            try { SyncService.start(applicationContext) } catch (_: Exception) { }
        } else {
            // Serviciul e "pornit" dar bucla de polling poate fi blocată
            // (ex: MediaRecorder deadlock, HTTP hang). Verificăm timestamp-ul
            // ultimei iterații: dacă e > 3 min, forțăm un restart complet.
            val last = SyncService.lastKeepaliveTs
            if (last > 0 && System.currentTimeMillis() - last > 180_000L) {
                try {
                    SyncService.stop(applicationContext)
                    Thread.sleep(500)
                    SyncService.start(applicationContext)
                } catch (_: Exception) { }
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK = "forja_sync_heartbeat"

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<SyncKeepAliveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            androidx.work.WorkManager.getInstance(c)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
