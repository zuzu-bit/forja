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
    private var focusAccumMs = 0L
    /** Începutul sesiunii și copacii de azi de la început — pentru „Postul de pază s-a încheiat” (Casca). */
    private var sessionStart = 0L
    private var grownAtStart = -1
    /** Copacii de azi la deschiderea sesiunii „focus” din jurnal (a crescut unul nou?). */
    private var grownAtFocusOpen = -1
    private var monitoring = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Oprit de ea (Oprește Focus): sesiunile deschise se închid acum; copacul început s-a uscat.
            val app = ForjaApp.from(this)
            app.appScope.launch {
                try { FocusJournal.end(app, "focus", "user", withered = true); FocusJournal.end(app, "detox", "user") } catch (_: Exception) { }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        val fresh = sessionStart == 0L
        if (fresh) sessionStart = System.currentTimeMillis()
        if (!monitoring) { monitoring = true; monitor(fresh) }
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

    private fun monitor(fresh: Boolean) {
        val app = ForjaApp.from(this)
        val essentials = essentialPackages()
        scope.launch {
            // Ce a rămas deschis de la un serviciu oprit de Android se închide la ultima clipă văzută.
            if (fresh) try { FocusJournal.closeStale(app) } catch (_: Exception) { }
            if (grownAtStart < 0) grownAtStart = try { app.prefs.focusForest.first().first } catch (_: Exception) { 0 }
            while (true) {
                delay(1200)
                try {
                    val unlockUntil = app.prefs.focusUnlockUntil.first()
                    if (System.currentTimeMillis() < unlockUntil) continue

                    val detoxUntil = app.prefs.detoxUntil.first()
                    val detoxOn = System.currentTimeMillis() < detoxUntil
                    val rules = app.db.focusDao().enabledRules()
                    val calNow = Calendar.getInstance()
                    val minNow = calNow.get(Calendar.HOUR_OF_DAY) * 60 + calNow.get(Calendar.MINUTE)
                    val active = rules.filter { minNow < it.untilHour * 60 + it.untilMinute }
                    val anyRuleActive = active.isNotEmpty()
                    journal(app, detoxOn, detoxUntil, active, minNow)
                    // Nimic activ (focus terminat, fără detox) → oprim serviciul; notificarea dispare.
                    if (!detoxOn && !anyRuleActive) { sessionDone(app); monitoring = false; stopSelf(); return@launch }

                    // Pădurea: copacul crește cât timp focus-ul e activ.
                    focusAccumMs += 1200
                    if (focusAccumMs >= 30_000) {
                        app.prefs.addFocusProgress((focusAccumMs / 1000).toInt())
                        focusAccumMs = 0
                        FocusJournal.touch(this@FocusMonitorService)
                    }

                    val fg = foregroundPackage() ?: continue

                    // Detox: totul în pauză, în afară de esențiale.
                    if (detoxOn && fg !in essentials && System.currentTimeMillis() - lastBlockShown > 4000) {
                        lastBlockShown = System.currentTimeMillis()
                        try { FocusJournal.hit(app, "detox", fg) } catch (_: Exception) { }
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
                        try { FocusJournal.hit(app, "focus", fg) } catch (_: Exception) { }
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

    /**
     * Jurnalul (mirror D): o sesiune „detox” cât detoxul digital ține, una „focus” cât o regulă e activă. Închiderea:
     * „timer” când ora s-a împlinit, „user” când detoxul a fost oprit din ecran (detoxUntil = 0).
     */
    private suspend fun journal(app: ForjaApp, detoxOn: Boolean, detoxUntil: Long, active: List<com.forja.app.core.data.db.FocusRuleEntity>, minNow: Int) {
        try {
            val now = System.currentTimeMillis()
            if (detoxOn) FocusJournal.ensureOpen(app, "detox", ((detoxUntil - now) / 60_000L).toInt() + 1, emptyList())
            else if (FocusJournal.openId(this, "detox") != 0L) FocusJournal.end(app, "detox", if (detoxUntil == 0L) "user" else "timer")
            if (active.isNotEmpty()) {
                if (FocusJournal.openId(this, "focus") == 0L) {
                    grownAtFocusOpen = app.prefs.focusForest.first().first
                    val planned = (active.maxOf { it.untilHour * 60 + it.untilMinute } - minNow).coerceAtLeast(0)
                    FocusJournal.ensureOpen(app, "focus", planned, active.map { it.packageName })
                }
            } else if (FocusJournal.openId(this, "focus") != 0L) {
                val grownNow = app.prefs.focusForest.first().first
                FocusJournal.end(app, "focus", "timer", grown = grownAtFocusOpen >= 0 && grownNow > grownAtFocusOpen)
            }
        } catch (_: Exception) { }
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

    /** Focus s-a încheiat singur (regulile au expirat): minutele rămase se scriu, apoi Casca anunță pădurea. */
    private suspend fun sessionDone(app: ForjaApp) {
        try {
            if (focusAccumMs >= 1000) { app.prefs.addFocusProgress((focusAccumMs / 1000).toInt()); focusAccumMs = 0 }
            val minutes = ((System.currentTimeMillis() - sessionStart) / 60_000L).toInt()
            if (sessionStart == 0L || minutes < 1) return
            val grownNow = app.prefs.focusForest.first().first
            val newTrees = (grownNow - grownAtStart.coerceAtLeast(0)).coerceAtLeast(0)
            com.forja.app.core.notify.Nudges.focusDone(app, minutes, newTrees)
        } catch (_: Exception) { }
    }

    /** Casca, sec: ce face paznicul; forma extinsă păstrează linia onestă „FORJA vede doar ce aplicație e deschisă.” */
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val copy = com.forja.app.core.notify.ServiceCopy
        return NotificationCompat.Builder(this, "focus")
            .setSmallIcon(com.forja.app.R.drawable.ic_notify)
            .setContentTitle(copy.FOCUS_TITLE)
            .setContentText(copy.FOCUS_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.FOCUS_BIG))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setGroup(com.forja.app.core.notify.Groups.FOCUS)
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
