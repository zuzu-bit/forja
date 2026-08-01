package com.forja.app.core.focus

import android.app.AppOpsManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.feature.focus.FocusBlockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Focus: verifică (fără AccessibilityService) ce aplicație e în prim-plan.
 * FORJA vede doar ce aplicație e deschisă — nu citește mesajele, parolele sau conținutul ecranului.
 */
class FocusMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastBlockShown = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        monitor()
        return START_STICKY
    }

    /** Esențialele care NU se blochează niciodată: launcher, telefon, mesaje, FORJA. */
    private fun essentialPackages(): Set<String> {
        val set = mutableSetOf(packageName)
        try {
            val home = packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
            if (home != null) set.add(home)
        } catch (_: Exception) { }
        try {
            val dialer = packageManager.resolveActivity(
                Intent(Intent.ACTION_DIAL), 0
            )?.activityInfo?.packageName
            if (dialer != null) set.add(dialer)
        } catch (_: Exception) { }
        try {
            android.provider.Telephony.Sms.getDefaultSmsPackage(this)?.let { set.add(it) }
        } catch (_: Exception) { }
        set.add("com.android.settings")
        return set
    }

    private fun monitor() {
        val app = ForjaApp.from(this)
        val essentials = essentialPackages()
        scope.launch {
            while (true) {
                delay(1200)
                try {
                    val unlockUntil = app.prefs.focusUnlockUntil.first()
                    if (System.currentTimeMillis() < unlockUntil) continue

                    val detoxUntil = app.prefs.detoxUntil.first()
                    val detoxOn = System.currentTimeMillis() < detoxUntil
                    val rules = app.db.focusDao().enabledRules()
                    if (!detoxOn && rules.isEmpty()) continue

                    val fg = foregroundPackage() ?: continue

                    // Detox: totul în pauză, în afară de esențiale.
                    if (detoxOn && fg !in essentials && System.currentTimeMillis() - lastBlockShown > 4000) {
                        lastBlockShown = System.currentTimeMillis()
                        startActivity(
                            Intent(this@FocusMonitorService, FocusBlockActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("label", "Detox: telefonul")
                                putExtra("until", com.forja.app.core.util.Fmt.clock(detoxUntil))
                            }
                        )
                        continue
                    }

                    val cal = Calendar.getInstance()
                    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    val rule = rules.firstOrNull {
                        it.packageName == fg && nowMin < it.untilHour * 60 + it.untilMinute
                    }
                    if (rule != null && System.currentTimeMillis() - lastBlockShown > 4000) {
                        lastBlockShown = System.currentTimeMillis()
                        val i = Intent(this@FocusMonitorService, FocusBlockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("label", rule.label)
                            putExtra("until", String.format("%02d:%02d", rule.untilHour, rule.untilMinute))
                        }
                        startActivity(i)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun foregroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 8000, now)
        var last: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Focus activ")
            .setContentText("Aplicațiile alese sunt în pauză. Respiră.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 33
        const val ACTION_STOP = "com.forja.app.focus.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FocusMonitorService::class.java))
        }
        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, FocusMonitorService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Avem permisiunea Usage Access? (setare specială Android) */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
