package com.forja.app.core.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Reminder-e blânde și încurajatoare, la o oră ALEATOARE din zi (10:00–21:00).
 * Fiecare declanșare se reprogramează singură pentru a doua zi, la altă oră.
 * Se pot opri din setări (prefs.nudgesOn).
 */
object ForjaNudge {
    private const val WORK = "forja_nudge"
    private const val NOTIF_ID = 40

    private val MESSAGES = listOf(
        "Cinci minute pentru tine? Deschide FORJA și respiră puțin.",
        "Corpul tău îți mulțumește pentru fiecare pas. Hai la o tură scurtă.",
        "Mâncarea de azi contează — notează-ți o masă în FORJA.",
        "Somnul bun începe de cu seară. Pregătește-ți noaptea în FORJA.",
        "Ești mai puternic decât scuza de azi. Un pas mic, acum.",
        "Prietenii tăi se mișcă. Vezi ce fac și inspiră-te.",
        "O apă, o respirație, un gând bun — le ai pe toate în FORJA.",
        "Progresul e suma zilelor mici. Azi e una dintre ele.",
        "Mândria de diseară se clădește din alegerea de acum.",
        "Cum te simți azi? Un minut de somn, mâncare sau mișcare contează."
    )

    private fun nextDelayMs(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Random.nextInt(10, 21))   // între 10:00 și 20:59
            set(Calendar.MINUTE, Random.nextInt(0, 60))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
    }

    fun schedule(context: Context) {
        val req = OneTimeWorkRequestBuilder<NudgeWorker>()
            .setInitialDelay(nextDelayMs(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun postNudge(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val msg = MESSAGES[Random.nextInt(MESSAGES.size)]
        val pi = PendingIntent.getActivity(
            context, 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, "social")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("FORJA")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) } catch (_: Exception) { }
    }
}

class NudgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = ForjaApp.from(applicationContext)
        if (app.prefs.nudgesOn.first()) {
            ForjaNudge.postNudge(applicationContext)
            ForjaNudge.schedule(applicationContext)   // reprogramează pentru mâine, la altă oră
        }
        return Result.success()
    }
}
