package com.forja.app.core.recovery

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.network.InsightsFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executorul unei comenzi de pe site — pornește DOAR când vine o comandă și trăiește cel mult până la termenul ei.
 * Nu mai există serviciul permanent din 4.0–4.3: ascultarea stă în bătaia serviciului contractului ([Finder]).
 *
 * - **Urmărește** (`locate`): GPS la 3 s, poziții la cel puțin 10 s, notificarea „Te caută contul tău” cu „Oprește”.
 * - **Sună** (`ring`): soneria pe canalul de alarmă ([FinderRinger]), ecranul „Aici sunt.” ([FoundActivity]) peste
 *   ecranul blocat, o poziție trimisă; „Am găsit telefonul” oprește soneria și închide comanda pe site.
 *
 * Notificarea nu se poate ascunde cât rulează comanda: canal cu importanță mare, vizibilă pe ecranul blocat, pusă la loc
 * dacă e ștearsă cu degetul. Pornit ca serviciu obișnuit (procesul e ținut de serviciul contractului), încearcă să treacă
 * în prim-plan (LOCATION, sau SHORT_SERVICE pentru sonerie fără locație); dacă Android refuză din fundal, notificarea
 * apare oricum, ca notificare obișnuită.
 */
class LostPhoneService : Service(), LocationListener {

    /** Ce execută telefonul acum (citit de bătaie, de Profil și de FoundActivity). */
    data class Active(val id: String, val kind: FinderCommand.Kind, val until: Long, val ringEndsAt: Long)

    companion object {
        @Volatile var active: Active? = null
            private set
        val ringing: Boolean get() = active?.kind == FinderCommand.Kind.Ring && FinderRinger.ringing
        val locating: Boolean get() = active?.kind == FinderCommand.Kind.Locate

        private const val ID = 627
        private const val ACTION_RUN = "com.forja.app.finder.RUN"
        private const val ACTION_END = "com.forja.app.finder.END"
        private const val ACTION_STOP = "com.forja.app.finder.STOP"
        private const val ACTION_FOUND = "com.forja.app.finder.FOUND"
        private const val ACTION_REPOST = "com.forja.app.finder.REPOST"
        private const val ACTION_SILENCE = "com.forja.app.finder.SILENCE"
        private const val HANDLED_MAX = 12
        private const val EXEC_BEAT_MS = 15_000L

        /** Pornește (sau prelungește) comanda. */
        fun run(c: Context, cmd: FinderCommand) = send(
            c, Intent(c, LostPhoneService::class.java).setAction(ACTION_RUN)
                .putExtra("id", cmd.id).putExtra("kind", cmd.kind.wire)
                .putExtra("created_at", cmd.createdAt).putExtra("start_before", cmd.startBefore)
                .putExtra("until", cmd.until).putExtra("phase", cmd.phase)
                .putExtra("minutes", cmd.minutes ?: -1).putExtra("seconds", cmd.seconds ?: -1)
        )

        /** Oprește local ce rulează (site-ul a închis comanda, contract revocat, ieșire din cont). */
        fun end(c: Context) {
            if (active != null) send(c, Intent(c, LostPhoneService::class.java).setAction(ACTION_END))
        }

        /** „Am găsit telefonul”: soneria tace, comanda se închide pe site. */
        fun found(c: Context) = send(c, Intent(c, LostPhoneService::class.java).setAction(ACTION_FOUND))

        /** O tastă de volum pe „Aici sunt.”: soneria tace și comanda se oprește și pe site (nu mai spunem „sună”). */
        fun silence(c: Context) = send(c, Intent(c, LostPhoneService::class.java).setAction(ACTION_SILENCE))

        /** Comenzile deja închise pe acest telefon (nu le repornim dacă site-ul încă nu a aflat). */
        fun handled(c: Context): Set<String> =
            LostPhoneRecovery.prefs(c).getString("handled", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

        private fun markHandled(c: Context, id: String) {
            val list = LostPhoneRecovery.prefs(c).getString("handled", "").orEmpty().split(',').filter { it.isNotBlank() && it != id }
            LostPhoneRecovery.prefs(c).edit().putString("handled", (list + id).takeLast(HANDLED_MAX).joinToString(",")).apply()
        }

        private fun send(c: Context, i: Intent) {
            try { c.startService(i) } catch (_: Exception) { }
        }

        private fun Intent.command(): FinderCommand? {
            val id = getStringExtra("id")?.takeIf { it.isNotBlank() } ?: return null
            return FinderCommand(
                id = id,
                kind = if (getStringExtra("kind") == FinderCommand.Kind.Ring.wire) FinderCommand.Kind.Ring else FinderCommand.Kind.Locate,
                createdAt = getLongExtra("created_at", 0L),
                startBefore = getLongExtra("start_before", 0L),
                until = getLongExtra("until", 0L),
                phase = getStringExtra("phase") ?: "queued",
                minutes = getIntExtra("minutes", -1).takeIf { it > 0 },
                seconds = getIntExtra("seconds", -1).takeIf { it > 0 },
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: LocationManager
    private var device: RecoveryDevice? = null
    private var cmd: FinderCommand? = null
    private var deadline: Job? = null
    private var ticker: Job? = null
    private var sending: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var foreground = false
    private var lastSend = 0L
    private var lastFixElapsed = 0L
    private var startedElapsed = 0L
    private var oneShotDone = false
    private var lastFix: FinderFix? = null
    private val registered = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LostPhoneRecovery.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN -> intent.command()?.let(::runCommand)
            ACTION_END -> endLocal(markHandled = false)
            ACTION_STOP -> userStop()
            ACTION_FOUND -> found()
            ACTION_SILENCE -> if (active?.kind == FinderCommand.Kind.Ring) { FinderRinger.stop(this); userStop() }
            ACTION_REPOST -> if (cmd != null) show()
        }
        if (cmd == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun runCommand(next: FinderCommand) {
        val owner = LostPhoneRecovery.device(this) ?: return
        val now = System.currentTimeMillis()
        val current = cmd
        if (current != null && current.id == next.id) {
            // Aceeași comandă, termen nou (Urmărește +10 min de pe site).
            cmd = next
            active = active?.copy(until = next.until)
            scheduleDeadline()
            show()
            return
        }
        if (current != null) endLocal(markHandled = true, stopService = false)
        if (next.id in handled(this) || !next.startable(now)) return

        device = owner
        cmd = next
        val ring = next.kind == FinderCommand.Kind.Ring
        val ringEnds = if (ring) now + FinderLogic.ringMillis(next, now) else 0L
        active = Active(next.id, next.kind, next.until, ringEnds)
        startedElapsed = SystemClock.elapsedRealtimeNanos()
        lastSend = 0L; lastFixElapsed = 0L; oneShotDone = false; lastFix = null
        val endAt = if (ring) ringEnds else next.until
        wake = Finder.hold(this, (endAt - now + 15_000L).coerceIn(30_000L, 65 * 60_000L))
        promote(ring)
        if (ring) {
            FinderRinger.start(this)
            // Pe ecranul blocat îl deschide full-screen intent-ul notificării; cu telefonul în mână, încercăm direct.
            try { startActivity(FoundActivity.intent(this)) } catch (_: Exception) { }
        }
        registerProviders()
        report(if (ring) "ringing" else "locating")
        scheduleDeadline()
        ticker = scope.launch {
            while (isActive) {
                delay(EXEC_BEAT_MS)
                // Urmărirea nu merge nevăzută: notificările oprite între timp închid căutarea, iar site-ul află de ce.
                if (!ring && !LostPhoneRecovery.notices(this@LostPhoneService)) {
                    finalStatus("notification_missing")
                    endLocal(markHandled = true)
                    break
                }
                show()
                // Între bătăile de minut ale serviciului contractului: „Oprește” sau „+10 min” de pe site ajung în ≤ 15 s.
                try { Finder.beat(this@LostPhoneService, lastFix ?: Finder.bestFix(this@LostPhoneService)) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** Prim-plan dacă Android îl permite; altfel notificarea obișnuită, aceeași. */
    private fun promote(ring: Boolean) {
        // Locație dacă e permisă (din fundal cere „Tot timpul”); pentru sonerie, altfel serviciul scurt (Android 14+).
        val types = buildList {
            if (Build.VERSION.SDK_INT < 29) add(0)
            else {
                if (LostPhoneRecovery.locationPermission(this@LostPhoneService)) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                if (ring && Build.VERSION.SDK_INT >= 34) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
                if (Build.VERSION.SDK_INT < 34 && isEmpty()) add(0)
            }
        }
        foreground = types.any { type ->
            try {
                ServiceCompat.startForeground(this, ID, notification(), type)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!foreground) show()
    }

    private fun scheduleDeadline() {
        deadline?.cancel()
        val a = active ?: return
        val endAt = if (a.kind == FinderCommand.Kind.Ring) minOf(a.ringEndsAt, a.until.takeIf { it > 0 } ?: a.ringEndsAt) else a.until
        deadline = scope.launch {
            delay((endAt - System.currentTimeMillis()).coerceAtLeast(1L))
            endLocal(markHandled = true)
        }
    }

    /**
     * Spune site-ului ce face telefonul pentru comanda asta (confirmarea mută comanda din coadă în „activă”).
     * Răspunsul aduce comanda cu termenul real (acum + durata, nu termenul din coadă): îl luăm pe loc.
     */
    private fun report(status: String) {
        val d = device ?: return
        val id = cmd?.id ?: return
        scope.launch {
            try {
                val reply = withTimeout(20_000) {
                    LostPhoneRecovery.call(this@LostPhoneService, d, "status", buildJsonObject {
                        put("secret", d.secret); put("command", id); put("status", status)
                    })
                }
                val next = FinderCommand.parse(reply["command"] as? JsonObject)
                val a = active
                if (next != null && a != null && next.id == id && cmd?.id == id && next.until > 0L && next.until != a.until) {
                    cmd = next
                    active = a.copy(until = next.until)
                    scheduleDeadline()
                    show()
                }
            } catch (e: InsightsFailure) {
                // 409: comanda nu mai e pe site (oprită din site, expirată).
                if (e.code == 409 && cmd?.id == id) endLocal(markHandled = true)
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerProviders() {
        if (cmd == null || oneShotDone || !LostPhoneRecovery.locationPermission(this)) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider in registered) continue
            try {
                if (!manager.isProviderEnabled(provider)) continue
                manager.requestLocationUpdates(provider, 3000L, 0f, this, Looper.getMainLooper())
                registered.add(provider)
            } catch (_: Exception) { }
        }
    }

    override fun onLocationChanged(loc: Location) {
        val d = device ?: return
        val a = cmd ?: return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val age = elapsed - loc.elapsedRealtimeNanos
        if (!loc.hasAccuracy() || age !in 0..90_000_000_000L || loc.elapsedRealtimeNanos <= lastFixElapsed) return
        lastFix = FinderFix(loc.latitude, loc.longitude, loc.accuracy, now - age / 1_000_000)
        if (loc.elapsedRealtimeNanos < startedElapsed || now >= a.until || sending?.isActive == true || now - lastSend < 10_000) return
        lastSend = now
        lastFixElapsed = loc.elapsedRealtimeNanos
        val at = now - age / 1_000_000
        sending = scope.launch {
            try {
                withTimeout(20_000) {
                    LostPhoneRecovery.call(this@LostPhoneService, d, "position", buildJsonObject {
                        put("secret", d.secret)
                        put("command", a.id)
                        put("lat", loc.latitude)
                        put("lon", loc.longitude)
                        put("accuracy", loc.accuracy.coerceIn(0f, 10_000f).toDouble())
                        put("at", at)
                        put("battery", Finder.battery(this@LostPhoneService)?.first ?: 0)
                    })
                }
                // Soneria trimite o singură poziție: după ea, GPS-ul se oprește.
                if (a.kind == FinderCommand.Kind.Ring) {
                    oneShotDone = true
                    try { manager.removeUpdates(this@LostPhoneService) } catch (_: Exception) { }
                    registered.clear()
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) {
                // 409 imediat după pornire = site-ul încă n-a primit confirmarea; următorul fix reîncearcă în 10 s.
            }
        }
    }

    /** „Oprește” din notificare: comanda se închide pe site (cu reîncercare) și aici, pe loc. */
    private fun userStop() {
        val a = cmd; val d = device
        if (a != null && d != null) LostPhoneRecovery.queue(this, d, "stop", a.id)
        endLocal(markHandled = true)
    }

    /** „Am găsit telefonul”: site-ul află „găsit”, apoi comanda se închide. */
    private fun found() {
        FinderRinger.stop(this)
        finalStatus("found", thenStop = true)
        endLocal(markHandled = true)
    }

    /**
     * Ultima stare a comenzii, trimisă din afara serviciului (care se oprește imediat după): „found” sau
     * „notification_missing”. `thenStop` = comanda se închide și pe site (cu reîncercare).
     */
    private fun finalStatus(status: String, thenStop: Boolean = false) {
        val a = cmd ?: return
        val d = device ?: return
        val app = applicationContext
        ForjaApp.from(app).appScope.launch {
            try {
                withTimeout(8_000) {
                    LostPhoneRecovery.call(app, d, "status", buildJsonObject {
                        put("secret", d.secret); put("command", a.id); put("status", status)
                    })
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) { }
            if (thenStop) LostPhoneRecovery.queue(app, d, "stop", a.id)
        }
    }

    /** Oprește tot ce face comanda; `stopService` = și serviciul (altfel urmează o comandă nouă). */
    private fun endLocal(markHandled: Boolean, stopService: Boolean = true) {
        val a = active
        deadline?.cancel(); deadline = null
        ticker?.cancel(); ticker = null
        sending?.cancel(); sending = null
        try { manager.removeUpdates(this) } catch (_: Exception) { }
        registered.clear()
        FinderRinger.stop(this)
        if (a != null && markHandled) markHandled(this, a.id)
        cmd = null
        active = null
        FoundActivity.close()
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wake = null
        if (stopService) {
            if (foreground) {
                try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
                foreground = false
            }
            try { getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (_: Exception) { }
            stopSelf()
        }
    }

    private fun show() {
        if (cmd == null) return
        try { getSystemService(NotificationManager::class.java)?.notify(ID, notification()) } catch (_: Exception) { }
    }

    private fun notification(): Notification {
        val a = cmd
        val now = System.currentTimeMillis()
        val ring = a?.kind == FinderCommand.Kind.Ring
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val foundScreen = PendingIntent.getActivity(this, 631, FoundActivity.intent(this), flags)
        val open = if (ring) foundScreen else PendingIntent.getActivity(
            this, 630, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val act = PendingIntent.getService(
            this, if (ring) 632 else 628,
            Intent(this, LostPhoneService::class.java).setAction(if (ring) ACTION_FOUND else ACTION_STOP), flags
        )
        val repost = PendingIntent.getService(this, 633, Intent(this, LostPhoneService::class.java).setAction(ACTION_REPOST), flags)
        val ringEnds = active?.ringEndsAt ?: now
        val text = when {
            a == null -> "Te caută contul tău."
            ring -> "Sună cel mult ${FinderLogic.seconds((((ringEnds - now).coerceAtLeast(0L)) / 1000L).toInt().coerceAtLeast(1))}."
            else -> FinderLogic.locateText(a.until, now)
        }
        val b = NotificationCompat.Builder(this, LostPhoneRecovery.CHANNEL)
            .setSmallIcon(if (ring) android.R.drawable.ic_lock_idle_alarm else android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Te caută contul tău")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (ring) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            // Cine ține telefonul trebuie să vadă că e căutat — și pe ecranul blocat. Nu arată nicio poziție.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .addAction(0, if (ring) "Am găsit telefonul" else "Oprește", act)
        if (ring) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(ringEnds).setShowWhen(true)
            if (Finder.fullScreenAllowed(this)) b.setFullScreenIntent(foundScreen, true)
        }
        return b.build()
    }

    @Deprecated("Necesar pe versiunile vechi de Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    override fun onProviderEnabled(provider: String) {
        registerProviders()
    }

    override fun onProviderDisabled(provider: String) {
        registered.remove(provider)
    }

    /** SHORT_SERVICE (soneria fără locație) are ~3 minute: soneria e oricum mai scurtă. */
    override fun onTimeout(startId: Int) {
        endLocal(markHandled = true)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        endLocal(markHandled = true)
    }

    override fun onDestroy() {
        if (active != null) endLocal(markHandled = false, stopService = false)
        if (foreground) {
            try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            foreground = false
        }
        scope.cancel()
        super.onDestroy()
    }
}
