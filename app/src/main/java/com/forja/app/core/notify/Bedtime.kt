package com.forja.app.core.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.forja.app.ForjaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * „Amintește-mi de somn” (Profil): cu 30 de minute înainte de stingere. Stingerea = trezirea − 8 h dacă alarma e
 * setată, altfel 23:00. Programată cu AlarmManager.setWindow (fără permisiunea de alarme exacte; corectura 2):
 * WorkManager ar putea întârzia ore întregi pe Samsung. Textul se calculează la declanșare (gard de prospețime):
 * „Stingerea în 25 de minute” e adevărat, iar o alarmă sosită după stingere nu mai trimite nimic.
 */
object Bedtime {
    const val ACTION = "com.forja.app.notify.BEDTIME"
    const val LEAD_MIN = 30
    const val SLEEP_TARGET_MIN = 8 * 60
    const val DEFAULT_BED_MIN = 23 * 60
    /** Fereastra lăsată sistemului (grupează trezirile, fără permisiuni speciale). */
    private const val WINDOW_MS = 10 * 60_000L
    private const val LATE_LIMIT_MIN = -15

    /** Minutul din zi al stingerii. */
    fun bedtimeMinute(alarmOn: Boolean, hour: Int, minute: Int): Int =
        if (alarmOn) Math.floorMod(hour * 60 + minute - SLEEP_TARGET_MIN, 24 * 60) else DEFAULT_BED_MIN

    /** Următoarea amintire (ms): stingerea − 30 min, azi sau mâine. */
    fun nextTrigger(now: Long, bedMinute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val triggerMin = Math.floorMod(bedMinute - LEAD_MIN, 24 * 60)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var t = today.atStartOfDay(zone).plusMinutes(triggerMin.toLong()).toInstant().toEpochMilli()
        if (t <= now) t = today.plusDays(1).atStartOfDay(zone).plusMinutes(triggerMin.toLong()).toInstant().toEpochMilli()
        return t
    }

    /** Minute până la stingere (negativ = a trecut), stingerea fiind cea mai apropiată de acum. */
    fun minutesToBedtime(now: Long, bedMinute: Int, zone: ZoneId = ZoneId.systemDefault()): Int {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        val nowMin = z.hour * 60 + z.minute
        var diff = bedMinute - nowMin
        if (diff > 12 * 60) diff -= 24 * 60
        if (diff < -12 * 60) diff += 24 * 60
        return diff
    }

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, NotifIds.BEDTIME, Intent(c, NudgeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private suspend fun bedMinute(app: ForjaApp): Int = bedtimeMinute(
        app.prefs.alarmEnabled.first(), app.prefs.alarmHour.first(), app.prefs.alarmMinute.first()
    )

    /** Armează (sau anulează) amintirea după setările curente. Idempotent: apelat la deschidere, orar și la schimbări. */
    suspend fun sync(app: ForjaApp) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(app)
        if (!app.prefs.sleepReminder.first()) { am.cancel(pi); return }
        val t = nextTrigger(System.currentTimeMillis(), bedMinute(app))
        try { am.setWindow(AlarmManager.RTC_WAKEUP, t, WINDOW_MS, pi) } catch (_: Exception) { }
    }

    /** Alarma a sunat: mesajul (dacă nu e prea târziu), apoi armarea pentru mâine. */
    suspend fun fire(app: ForjaApp) {
        try {
            if (app.prefs.sleepReminder.first()) {
                val left = minutesToBedtime(System.currentTimeMillis(), bedMinute(app))
                if (left >= LATE_LIMIT_MIN) Nudges.bedtime(app, left)
            }
        } finally {
            sync(app)
        }
    }

    /** Ziua (pentru teste și diagnostic): `LocalDate` a unui moment. */
    fun dateOf(t: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
}

/**
 * Receptorul Cascăi (nu e exportat): alarma de culcare, swipe-ul pe un mesaj (ignorat) și swipe-ul pe notificarea
 * permanentă de sincronizare (nu o mai re-postăm până la următorul start al serviciului; blocanta B3).
 */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Bedtime.ACTION -> {
                val app = context.applicationContext as? ForjaApp ?: return
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try { withTimeoutOrNull(8_000L) { Bedtime.fire(app) } } catch (_: Exception) { } finally { pending.finish() }
                }
            }
            ACTION_DISMISS -> {
                val ctx = intent.getStringExtra(EXTRA_CTX) ?: return
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                Nudges.onDismissed(context, ctx, id)
            }
            ACTION_SYNC_DISMISS -> NudgeStore.setSyncDismissed(context, true)
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.forja.app.notify.DISMISS"
        const val ACTION_SYNC_DISMISS = "com.forja.app.notify.SYNC_DISMISS"
        private const val EXTRA_CTX = "ctx"
        private const val EXTRA_ID = "id"

        fun dismissIntent(c: Context, notifId: Int, ctx: String, id: String): PendingIntent = PendingIntent.getBroadcast(
            c, 10_000 + notifId,
            Intent(c, NudgeReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_CTX, ctx).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun syncDismissIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
            c, 10_000 + NotifIds.SYNC_FGS, Intent(c, NudgeReceiver::class.java).setAction(ACTION_SYNC_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
