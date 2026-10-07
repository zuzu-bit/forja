package com.forja.app.core.research

import android.content.Context
import androidx.work.*
import com.forja.app.ForjaApp
import java.util.concurrent.TimeUnit

class LabSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = ForjaApp.from(applicationContext).labResearch
        if (!controller.isActive() && !controller.hasPendingRevocations()) return Result.success()
        return if (controller.flush()) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK = "forja_lab_sync"
        private const val PERIODIC = "forja_lab_sync_recovery"
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueue(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LabSyncWorker>().setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LabSyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build())
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
