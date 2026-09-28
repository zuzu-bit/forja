package com.forja.app.core.recovery

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.forja.app.core.network.InsightsFailure
import com.forja.app.core.sync.AutomaticCollectionService
import com.forja.app.core.sync.CollectionSettings
import com.forja.app.core.sync.SyncFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** O poziție de trimis site-ului (ultima știută). */
data class FinderFix(val lat: Double, val lon: Double, val accuracy: Float, val at: Long)

/** Ce vede rândul „Telefonul meu” și foaia Găsire. */
data class FinderUi(
    val state: FinderState,
    val lastOkAt: Long,
    val battery: Int?,
    /** O propoziție despre ce nu merge (fără internet, scos de pe site…) sau null. */
    val problem: String?,
    /** „incomplet” doar din cauza canalului mut: atingerea duce la setările canalului, nu în Echipare. */
    val onlyChannel: Boolean = false,
)

/**
 * Bătaia găsirii: o dată pe ciclu (~60 s) în serviciul contractului, telefonul spune site-ului că e viu, cu ultima
 * poziție știută și bateria; răspunsul aduce comanda de pe site (Sună / Urmărește), dacă există.
 *
 * Doze: fiecare bătaie ține un wake lock parțial (≤ 30 s) și armează `setAndAllowWhileIdle` peste `next_s`.
 * În Doze adânc Android îl întinde la ~9 min; alarma trezește bucla ([poke]) sau, dacă procesul a murit, repornește
 * serviciul ([FinderWakeReceiver] → [CollectionSettings.selfHeal]). Fără alarmă exactă și fără FCM.
 */
object Finder {
    private const val WAKE_REQUEST = 640
    private const val BEAT_WAKE_MS = 30_000L
    private const val HEAL_RETRY_MS = 15 * 60_000L

    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val beating = Mutex()
    /** Ultima poziție din serviciul contractului (pentru „Probă”, care rulează în afara lui). */
    @Volatile private var serviceFix: FinderFix? = null

    /**
     * Bucla bătăilor, lansată de [AutomaticCollectionService] lângă bucla de sincronizare (nu în ea): o sesiune
     * refuzată de site (423, 429) nu oprește găsirea. Se oprește odată cu serviciul.
     */
    suspend fun run(c: Context, latestFix: () -> SyncFix?, authorized: () -> Boolean) {
        LostPhoneRecovery.ensureChannel(c)
        while (currentCoroutineContext().isActive && authorized()) {
            val fix = latestFix()?.let { FinderFix(it.latitude, it.longitude, it.accuracy, it.at) }
            if (fix != null) serviceFix = fix
            val next = try { beat(c, fix)?.nextMs } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                ?: FinderLogic.nextBeatMillis(null)
            arm(c, next)
            // O trezire rămasă de dinainte de bătaia de acum nu mai contează.
            wakes.tryReceive()
            withTimeoutOrNull(next) { wakes.receive() }
        }
    }

    /** Alarma a sunat cât serviciul trăiește: următoarea bătaie pleacă acum. */
    fun poke() { wakes.trySend(Unit) }

    class Beat(val ok: Boolean, val nextMs: Long)

    /**
     * O bătaie. Înrolează telefonul dacă nu e (contract v3), trimite starea și poziția, execută comanda primită.
     * Întoarce null dacă telefonul nu e (încă) înrolat.
     */
    suspend fun beat(c: Context, fix: FinderFix?): Beat? = beating.withLock {
        val d = LostPhoneRecovery.device(c) ?: LostPhoneRecovery.ensureEnrolled(c) ?: return@withLock null
        val p = LostPhoneRecovery.prefs(c)
        val wake = hold(c, BEAT_WAKE_MS)
        try {
            val now = System.currentTimeMillis()
            val status = status(c)
            val battery = battery(c)
            // Serverul ține `last` doar dacă e mai nou; nu retrimitem același punct.
            val send = fix?.takeIf { it.at > p.getLong("sent_fix_at", 0L) && it.at <= now + 60_000L }
            val v2 = LostPhoneRecovery.serverVersion(c) >= 2
            val result = withTimeout(20_000) {
                if (v2) LostPhoneRecovery.call(c, d, "beat", buildJsonObject {
                    put("secret", d.secret)
                    put("status", status)
                    if (send != null) put("fix", buildJsonObject {
                        put("lat", send.lat)
                        put("lon", send.lon)
                        put("accuracy", send.accuracy.coerceIn(0f, 10_000f).toDouble())
                        put("at", send.at)
                    })
                    if (battery != null) put("battery", battery.first)
                    if (battery != null) put("charging", battery.second)
                }) else LostPhoneRecovery.call(c, d, "poll", buildJsonObject {
                    put("secret", d.secret)
                    // Serverul vechi nu știe „ringing” / „permission_missing”.
                    put("status", if (status in setOf("ready", "locating", "location_off", "notification_missing")) status else "ready")
                })
            }
            val at = System.currentTimeMillis()
            val edit = p.edit().putLong("beat_ok_at", at).putLong("beat_at", at).remove("error")
            if (battery != null) edit.putInt("battery", battery.first)
            if (send != null && v2) edit.putLong("sent_fix_at", send.at)
            edit.apply()
            dispatch(c, FinderCommand.parse(result["command"] as? JsonObject))
            Beat(true, FinderLogic.nextBeatMillis(result["next_s"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }))
        } catch (_: TimeoutCancellationException) {
            failed(p, "Site-ul nu a răspuns.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: InsightsFailure) {
            when {
                // Un server vechi nu știe ruta „beat” (404): trecem pe „poll”, înrolarea rămâne.
                e.code == 404 && LostPhoneRecovery.serverVersion(c, fresh = true) < 2 -> failed(p, "Site-ul se actualizează.")
                // Scos de pe site („Scoate telefonul”) sau altă activare: nu revenim singuri; „Probă” îl readuce.
                e.code == 403 || e.code == 404 -> {
                    LostPhoneRecovery.markRemoved(c)
                    failed(p, "Scos de pe site. „Probă” îl readuce.")
                }
                e.code == 401 -> failed(p, "Contul nu mai e conectat.")
                else -> failed(p, "Site-ul a refuzat bătaia.")
            }
        } catch (_: IllegalStateException) {
            failed(p, "Găsirea s-a oprit.")
        } catch (_: Exception) {
            failed(p, "Fără internet.")
        } finally {
            try { if (wake.isHeld) wake.release() } catch (_: Exception) { }
        }
    }

    private fun failed(p: android.content.SharedPreferences, why: String): Beat {
        p.edit().putLong("beat_at", System.currentTimeMillis()).putString("error", why).apply()
        return Beat(false, FinderLogic.nextBeatMillis(null))
    }

    /** Ce face telefonul cu comanda de pe site. Urmărirea pornește doar dacă telefonul o poate arăta și are locație. */
    private fun dispatch(c: Context, command: FinderCommand?) {
        val active = LostPhoneService.active
        val now = System.currentTimeMillis()
        when (val d = FinderLogic.decide(command, active?.id, active?.until ?: 0L, LostPhoneService.handled(c), now)) {
            FinderLogic.Decision.Keep -> Unit
            FinderLogic.Decision.StopActive -> LostPhoneService.end(c)
            is FinderLogic.Decision.Extend -> LostPhoneService.run(c, d.command)
            is FinderLogic.Decision.Start -> {
                val ok = d.command.kind == FinderCommand.Kind.Ring ||
                    (LostPhoneRecovery.notices(c) && LostPhoneRecovery.locationPermission(c) && locationEnabled(c))
                // Altfel comanda rămâne în coadă pe site; bătaia spune de ce (notification_missing, location_off…).
                if (ok) LostPhoneService.run(c, d.command)
            }
        }
    }

    /** Starea pentru site (§3.4), în ordinea: ce face acum, apoi ce-l împiedică. */
    fun status(c: Context): String = FinderLogic.beatStatus(
        ringing = LostPhoneService.ringing,
        locating = LostPhoneService.locating,
        locationEnabled = locationEnabled(c),
        locationPermission = LostPhoneRecovery.locationPermission(c) && LostPhoneRecovery.backgroundPermission(c),
        notices = LostPhoneRecovery.notices(c),
    )

    fun locationEnabled(c: Context): Boolean = try {
        val m = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
        else m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

    /** Bateria (0–100) și dacă se încarcă. */
    fun battery(c: Context): Pair<Int, Boolean>? = try {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) level to bm.isCharging else null
    } catch (_: Exception) { null }

    /** Cea mai nouă poziție știută: din serviciul contractului sau din Android (ultima cunoscută). */
    @SuppressLint("MissingPermission")
    fun bestFix(c: Context): FinderFix? {
        var best = serviceFix
        if (LostPhoneRecovery.locationPermission(c)) {
            try {
                val m = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                    val l: Location = try { m.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: continue
                    if (!l.hasAccuracy() || l.time <= 0) continue
                    if (best == null || l.time > best.at) best = FinderFix(l.latitude, l.longitude, l.accuracy, l.time)
                }
            } catch (_: Exception) { }
        }
        return best
    }

    /** Rezultatul „Probei” din foaia Găsire, în cuvinte. */
    class Probe(val ok: Boolean, val message: String)

    /** „Probă”: o bătaie acum, cu confirmarea site-ului. Readuce telefonul scos de pe site. */
    suspend fun probe(c: Context): Probe {
        if (!CollectionSettings.contractOn(c)) return Probe(false, "Semnează întâi contractul.")
        if (LostPhoneRecovery.ensureEnrolled(c, force = true) == null) {
            return Probe(false, LostPhoneRecovery.prefs(c).getString("error", null) ?: "Nu a mers. Încearcă din nou.")
        }
        val fix = bestFix(c)
        val r = beat(c, fix)
        return when {
            r == null || !r.ok -> Probe(false, LostPhoneRecovery.prefs(c).getString("error", null) ?: "Nu a mers. Încearcă din nou.")
            fix == null -> Probe(true, "Site-ul te vede. Fără poziție acum.")
            else -> Probe(true, "Site-ul te vede. Poziție trimisă acum.")
        }
    }

    /** Starea pentru Profil. `contractOn` vine din oglinda contractului (v3). */
    fun ui(c: Context, now: Long = System.currentTimeMillis()): FinderUi {
        val p = LostPhoneRecovery.prefs(c)
        val contract = CollectionSettings.contractOn(c)
        val appNotices = LostPhoneRecovery.appNotices(c)
        val channel = LostPhoneRecovery.channelOn(c)
        val others = LostPhoneRecovery.exactPermission(c) && LostPhoneRecovery.backgroundPermission(c) &&
            batteryUnrestricted(c) && fullScreenAllowed(c)
        val ready = others && appNotices && channel
        val lastOk = if (LostPhoneRecovery.enabled(c)) p.getLong("beat_ok_at", 0L) else 0L
        val state = FinderLogic.state(contract, ready, lastOk, now)
        val problem = when {
            state != FinderState.NoLink -> null
            LostPhoneRecovery.removed(c) -> "Scos de pe site. „Probă” îl readuce."
            !AutomaticCollectionService.running -> "Sincronizarea e oprită. Deschide FORJA sau apasă „Probă”."
            else -> p.getString("error", null)
        }
        return FinderUi(
            state = state,
            lastOkAt = lastOk,
            battery = p.getInt("battery", -1).takeIf { it in 0..100 },
            problem = problem,
            onlyChannel = state == FinderState.Incomplete && others && appNotices && !channel,
        )
    }

    fun batteryUnrestricted(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    } catch (_: Exception) { false }

    fun fullScreenAllowed(c: Context): Boolean = Build.VERSION.SDK_INT < 34 || try {
        c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
    } catch (_: Exception) { false }

    // ── Trezirea (Doze) ──

    private fun wakeIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, WAKE_REQUEST, Intent(c, FinderWakeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Următoarea trezire, și în Doze (cel mult o dată la ~9 min acolo). Nu cere permisiunea de alarme exacte. */
    fun arm(c: Context, afterMs: Long) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + afterMs, wakeIntent(c))
        } catch (_: Exception) { }
    }

    fun disarm(c: Context) {
        try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(wakeIntent(c)) } catch (_: Exception) { }
    }

    /** Wake lock parțial cu termen (se eliberează singur). */
    fun hold(c: Context, ms: Long): PowerManager.WakeLock {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forja:finder").apply {
            setReferenceCounted(false)
            try { acquire(ms) } catch (_: Exception) { }
        }
    }

    internal fun healRetry(c: Context) = arm(c, HEAL_RETRY_MS)
}

/**
 * Alarma găsirii: trezește bucla bătăilor; dacă serviciul contractului nu mai rulează (proces ucis, oprit de sistem),
 * îl repornește (scutirea de la optimizarea bateriei permite pornirea din fundal) — auto-vindecare.
 */
class FinderWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Puntea până preia bucla (care își ia propriul wake lock).
        Finder.hold(context, 15_000L)
        if (AutomaticCollectionService.running) {
            Finder.poke()
            return
        }
        when (CollectionSettings.selfHeal(context)) {
            CollectionSettings.Heal.Started, CollectionSettings.Heal.NotWanted -> Unit
            CollectionSettings.Heal.Refused -> Finder.healRetry(context)
        }
    }
}
