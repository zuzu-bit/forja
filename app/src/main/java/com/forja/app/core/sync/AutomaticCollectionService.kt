package com.forja.app.core.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.forja.app.core.network.InsightsFailure
import com.forja.app.core.recovery.Finder
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.core.sync.CollectionSettings as Config
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * „Sincronizare în cont”: colectarea pornită de contractul semnat, cu notificare permanentă și memorie
 * mărginită. Datele alese pleacă în contul FORJA (site), inclusiv în fundal, până le oprește utilizatorul.
 *
 * 4.4: lângă bucla de sincronizare rulează bătaia găsirii ([Finder.run]) — telefonul rămâne găsibil de pe site.
 * Serviciul pornește și singur (boot, actualizare, alarma găsirii: [CollectionSettings.selfHeal]) și nu mai poartă
 * DATA_SYNC decât cu fișiere alese anume, deci nu mai cade după 6 ore (Android 15).
 * Când sincronizarea nu poate rula (notificările FORJA oprite, permisiuni lipsă, Android refuză din fundal locația),
 * serviciul rămâne în prim-plan doar pentru găsire ([Config.FINDER], SPECIAL_USE pe 34+): bătaia merge fără poziție
 * nouă, iar site-ul află de ce (`notification_missing`, `permission_missing`). Orice ieșire după un
 * startForegroundService trece întâi prin startForeground ([finish]) — altfel Android închide procesul.
 *
 * Sesiunea de server se refolosește cât timp setul de consimțământ nu se schimbă (nu o sesiune nouă la
 * fiecare salvare); la rotire, sesiunea anterioară se șterge. O schimbare de revizie repornește logica de
 * start în loc să lase serviciul oprit în fundal.
 */
class AutomaticCollectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var work: Job? = null
    private var beats: Job? = null
    /** Android 15 a consumat bugetul dataSync (6 h / 24 h): până la următorul start, fără DATA_SYNC. */
    private var noDataSync = false
    private var transport: SyncTransport? = null
    private var listener: LocationListener? = null
    @Volatile private var recorder: AudioRecord? = null
    private var runningRevision = -1L
    private var configured = emptySet<String>()
    private var foreground = false
    private var destroyed = false
    /** După stopSelf(): nicio repornire până la un nou onStartCommand (altfel startForeground după oprire lasă notificarea). */
    private var stopping = false
    /**
     * Pornit cu startForegroundService și încă fără startForeground: Android cere startForeground înainte de oprire,
     * altfel închide procesul („did not then call Service.startForeground()”).
     */
    private var fgPending = false
    /** Momentul ultimei porniri a logicii (anti-buclă la reluare). */
    private var begunAt = 0L
    private val fixes = mutableListOf<SyncFix>()
    private val auth = FirebaseAuth.getInstance()

    private val authListener = FirebaseAuth.AuthStateListener {
        // Întâi ieșirea curată din prim-plan (finish), abia apoi stopService-ul din Config.stop.
        if (Config.owner(this) != it.currentUser?.uid) { finish(); Config.stop(this); Finder.disarm(this) }
    }

    /** Revizie nouă (salvare din Echipare, oprire imediată, curățare): se reia logica de start, nu se stă în fundal. */
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "revision") return@OnSharedPreferenceChangeListener
        // apply() de pe firul principal notifică sincron: amânăm ca un save() din begin()/finally să nu reintre în begin().
        main.post {
            if (destroyed || stopping) return@post
            if (Config.revision(this) == runningRevision && (work?.isActive == true || beats?.isActive == true)) return@post
            begin()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Config.prefs(this).registerOnSharedPreferenceChangeListener(changes)
        auth.addAuthStateListener(authListener)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        if (intent?.action == STOP) {
            // Oprit de tine: nici găsirea nu mai repornește singură până deschizi FORJA ([Config.userStopped]).
            Config.setUserStopped(this, true)
            finish(); Config.stop(this); Finder.disarm(this); return START_NOT_STICKY
        }
        // Pornirile noastre trec toate prin startForegroundService (Profil, boot, alarma găsirii); repornirea „sticky”
        // a sistemului vine fără intent și fără această obligație.
        if (intent != null && !foreground) fgPending = true
        noDataSync = false
        val rev = Config.revision(this)
        // Rulează deja cu tot setul (nu cu unul redus la o pornire din fundal): nimic de reluat.
        if (rev == runningRevision && work?.isActive == true && configured == Config.enabled(this).intersect(grants())) return START_STICKY
        begin()
        // Ucis de sistem: Android îl repornește (serviciu „sticky” în prim-plan) — găsirea rămâne vie.
        return START_STICKY
    }

    /** Logica de start: calculează ce e permis, trece în prim-plan cu tipurile potrivite, pornește bucla. */
    private fun begin() {
        if (destroyed || stopping) return
        halt()
        begunAt = SystemClock.elapsedRealtime()
        if (!Config.contractOn(this)) {
            status("Contractul are rânduri noi. Până îl semnezi, sincronizarea stă; jurnalele merg mai departe.")
            finish(); return
        }
        val owner = Config.owner(this)
        val selected = Config.enabled(this)
        val granted = grants()
        val rev = Config.revision(this)
        // Un set gol nu mai oprește serviciul: cu contractul semnat, rulează doar pentru găsire (mai jos, `allowedNow` gol).
        // Oprit rămâne doar după „Oprește” din notificare sau fără contul care a semnat.
        val stoppedByUser = selected.isEmpty() && Config.userStopped(this)
        if (stoppedByUser || owner == null || owner != auth.currentUser?.uid) {
            status(
                if (stoppedByUser) "Sincronizarea este oprită."
                else "Sincronizarea este oprită: conectează-te în contul tău FORJA."
            )
            finish(); return
        }
        val allowedNow = CollectionPolicy.allowed(owner, auth.currentUser?.uid, selected, granted, rev, Config.revision(this))
        // O permisiune retrasă scoate categoria ei; un set rămas gol nu se salvează — alegerea rămâne, găsirea bate mai departe.
        if (allowedNow.isNotEmpty() && allowedNow != selected) Config.save(this, allowedNow)
        // Sincronizarea cere o notificare vizibilă; găsirea nu (site-ul primește atunci `notification_missing`).
        val notices = NotificationManagerCompat.from(this).areNotificationsEnabled()
        // Bugetul dataSync consumat (Android 15): fișierele alese așteaptă și în notificare, nu doar în starea din Profil.
        val syncable = (if (notices) allowedNow else emptySet()).let { if (noDataSync) it - setOf("photos", "files") else it }
        runningRevision = Config.revision(this)
        val snapshotRevision = runningRevision
        val allowed = promote(syncable) ?: run {
            status("Android a întrerupt pornirea. Verifică permisiunile și redeschide FORJA.")
            finish(); return
        }
        when {
            allowed.isEmpty() && !notices ->
                status("Sincronizarea așteaptă notificările FORJA din Android. Găsirea telefonului merge mai departe.")
            allowed.isEmpty() && allowedNow.isEmpty() ->
                status("Sincronizarea așteaptă permisiunile Android. Găsirea telefonului merge mai departe.")
            allowed != syncable ->
                status("Android a refuzat ${(syncable - allowed).joinToString(", ") { Config.label(it) }} în fundal. Redeschide FORJA pentru reluare.")
        }
        val beganNanos = SystemClock.elapsedRealtimeNanos()
        if ("location" in allowed) startLocation(beganNanos)
        // Găsirea are condiția ei: același cont și contractul semnat — nu notificările, nu permisiunile sincronizării.
        fun finderAlive(): Boolean = !stopping && !destroyed && Config.contractOn(this) && owner == auth.currentUser?.uid
        // Găsirea: bătaia are bucla ei, ca o sesiune refuzată de site (423, 429) să nu lase telefonul negăsibil.
        beats = scope.launch {
            Finder.run(this@AutomaticCollectionService, { synchronized(fixes) { fixes.lastOrNull() } }, ::finderAlive)
            // Doar găsire: fără bătaie (contract revocat, alt cont) serviciul nu mai are ce face.
            if (allowed.isEmpty() && runningRevision == snapshotRevision) finish()
        }
        if (allowed.isEmpty()) return
        fun authorized(): Boolean =
            CollectionPolicy.allowed(owner, auth.currentUser?.uid, allowed, grants(), snapshotRevision, Config.revision(this)) == allowed &&
                NotificationManagerCompat.from(this).areNotificationsEnabled() && Config.contractOn(this)
        val t = SyncTransport(owner, ::authorized); transport = t
        work = scope.launch {
            try {
                while (isActive && authorized()) {
                    val p = Config.prefs(this@AutomaticCollectionService)
                    var at = p.getLong("session_at", 0)
                    var id = p.getString("session_id", null)
                    val consentKey = allowed.sorted().joinToString(",")
                    if (id == null || p.getString("session_consent", null) != consentKey || System.currentTimeMillis() - at >= SESSION_MS) {
                        val previous = id
                        id = UUID.randomUUID().toString(); at = System.currentTimeMillis()
                        synchronized(fixes) { fixes.clear() }
                        p.edit().putString("session_id", id).putLong("session_at", at).putString("session_consent", consentKey)
                            .putLong("session_revision", snapshotRevision).apply()
                        if (previous != null) {
                            // Rotire: sesiunea veche nu mai primește nimic — o ștergem ca să nu atingem plafonul de 20.
                            try { withContext(Dispatchers.IO) { t.delete(previous) } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        }
                    }
                    val sessionId = id
                    val sessionAt = at
                    try {
                        withContext(Dispatchers.IO) { t.open(allowed, sessionId, automatic = true) }
                        status("Sincronizarea este activă. Datele pleacă în contul tău FORJA.")
                        coroutineScope {
                            if ("audio" in allowed) launch(Dispatchers.IO) { audio(t, sessionId, ::authorized) }
                            val sent = mutableMapOf<String, String>()
                            while (isActive && authorized() && System.currentTimeMillis() - sessionAt < SESSION_MS) {
                                if ("location" in allowed || "app_usage" in allowed) {
                                    val points = synchronized(fixes) { fixes.toList() }
                                    withContext(Dispatchers.IO) {
                                        check(authorized())
                                        val data = metrics(allowed, points, sessionAt)
                                        check(authorized())
                                        try {
                                            t.metrics(sessionId, data.toString().toByteArray())
                                        } catch (e: com.forja.app.core.network.InsightsFailure) {
                                            // Un server care încă nu știe usage_backfill (sau îl refuză) nu pierde locația și
                                            // timpul pe ecran: retrimitem o dată fără zilele încheiate; ziua lor se socotește trimisă.
                                            if (e.code != 400 || !data.has("usage_backfill")) throw e
                                            data.remove("usage_backfill")
                                            check(authorized())
                                            t.metrics(sessionId, data.toString().toByteArray())
                                            Config.prefs(this@AutomaticCollectionService).edit()
                                                .putString(KEY_BACKFILL_DAY, java.time.LocalDate.now().toString()).apply()
                                        }
                                        // Zilele încheiate au plecat: până mâine nu se mai trimit.
                                        if (data.has("usage_backfill")) Config.prefs(this@AutomaticCollectionService).edit()
                                            .putString(KEY_BACKFILL_DAY, java.time.LocalDate.now().toString()).apply()
                                    }
                                }
                                withContext(Dispatchers.IO) { syncSelected(t, sessionId, allowed, sent, ::authorized) }
                                status("Sincronizat la ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}. Sincronizarea este activă.")
                                com.forja.app.core.notify.SyncNotice.rotate(this@AutomaticCollectionService, NOTIFICATION, { foreground && !stopping }) { notification(fresh = false) }
                                repeat(12) {
                                    delay(5000)
                                    if (!authorized()) throw CancellationException("Sincronizarea a fost oprită sau o permisiune a fost retrasă")
                                }
                            }
                            coroutineContext.cancelChildren()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: InsightsFailure) {
                        if (e.code == 410 || e.code == 409 || e.code == 404) {
                            // Sesiunea a fost ștearsă din panou sau are alt consimțământ: la următoarea tură se deschide alta.
                            Config.prefs(this@AutomaticCollectionService).edit().remove("session_id").remove("session_consent").apply()
                        }
                        status(
                            when (e.code) {
                                423 -> "Primirea datelor este oprită din panoul online. Reîncercare automată în 30 de secunde."
                                429 -> "Site-ul are prea multe sesiuni deschise. Șterge una din panoul online; reîncercare în 30 de secunde."
                                401 -> "Contul nu mai este conectat. Conectează-te din nou în FORJA."
                                else -> "Sincronizarea a eșuat: ${e.message?.take(140)}. Reîncercare automată în 30 de secunde."
                            }
                        )
                        delay(30000)
                    }
                    catch (e: Exception) {
                        status("Sincronizarea a eșuat: ${e.message?.take(140)}. Reîncercare automată în 30 de secunde.")
                        delay(30000)
                    }
                }
            } finally {
                if (runningRevision == snapshotRevision) {
                    val self = this@AutomaticCollectionService
                    val remaining = allowed.intersect(grants())
                    if (remaining != allowed && remaining.isNotEmpty()) Config.save(self, remaining)
                    // Nu rămânem opriți în fundal: dacă mai e ceva pornit (revizie nouă) sau găsirea are încă voie
                    // (notificări oprite, permisiune retrasă), reluăm logica de start — ea alege sincronizare sau doar găsire.
                    val finder = Config.contractOn(self) && Config.owner(self) == auth.currentUser?.uid
                    val again = !destroyed && !stopping && Config.enabled(self).isNotEmpty() &&
                        (Config.revision(self) != snapshotRevision || finder)
                    halt()
                    // O reluare imediat după pornire nu se repetă în buclă: a doua așteaptă 30 s.
                    val wait = if (SystemClock.elapsedRealtime() - begunAt < 5_000L) 30_000L else 0L
                    if (again) main.postDelayed({ if (!destroyed && !stopping && work?.isActive != true && beats?.isActive != true) begin() }, wait)
                    else {
                        if (!stopping) status(
                            when {
                                Config.enabled(this@AutomaticCollectionService).isEmpty() -> "Sincronizarea este oprită."
                                !NotificationManagerCompat.from(this@AutomaticCollectionService).areNotificationsEnabled() ->
                                    "Sincronizarea așteaptă: pornește notificările FORJA din Android."
                                else -> "Sincronizarea este întreruptă. Redeschide FORJA pentru reluare."
                            }
                        )
                        finish()
                    }
                }
            }
        }
    }

    /**
     * Trecerea în prim-plan cu masca potrivită ([mask]). La refuz (microfon/locație pornite din fundal), se reia fără
     * categoriile refuzate; la urmă, doar găsirea. Întoarce setul cu care rulăm (gol = doar găsire) sau null.
     */
    private fun promote(wanted: Set<String>): Set<String>? {
        fun attempt(set: Set<String>): Boolean = try {
            // Setul gol = doar găsirea: notificarea o spune ([Config.FINDER]), tipul e SPECIAL_USE pe 34+.
            configured = set.ifEmpty { setOf(Config.FINDER) }
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), mask(set))
            foreground = true
            fgPending = false
            true
        } catch (_: Exception) { false }

        // Pașii: tot setul; fără ce e interzis la boot pe Android 15 (DATA_SYNC, microfon) — locația rămâne;
        // fără tipurile „în uz” (microfon, locație), care din fundal cer „Tot timpul”; fără amândouă;
        // la urmă doar găsirea (fără locație, fără fișiere), ca telefonul să bată și după boot fără „Tot timpul”.
        val boot = setOf("audio", "photos", "files")
        val inUse = setOf("audio", "location")
        return listOf(wanted, wanted - boot, wanted - inUse, wanted - boot - inUse, emptySet()).distinct()
            .firstOrNull { attempt(it) }
    }

    /**
     * Tipurile de prim-plan pentru un set: SPECIAL_USE pe 34+ pentru app_usage (și pentru setul gol, doar găsire);
     * DATA_SYNC doar pentru poze/fișiere alese anume (galeria are lucrătorul ei): fără limita de 6 h de pe Android 15;
     * LOCATION; MICROPHONE. Pe 34+ niciodată 0.
     */
    private fun mask(set: Set<String>): Int {
        val sync = !noDataSync && set.any { (it == "files" || it == "photos") && Config.hasSelected(this, it) }
        var types = (if (Build.VERSION.SDK_INT >= 34 && "app_usage" in set) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (sync) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0) or
            (if ("location" in set) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
            (if ("audio" in set) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (Build.VERSION.SDK_INT >= 34 && types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return types
    }

    private fun grants(): Set<String> = buildSet {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION)) add("location")
        if (UsageReader.allowed(this@AutomaticCollectionService)) add("app_usage")
        if (has(Manifest.permission.RECORD_AUDIO)) add("audio")
        // Poze și fișiere: doar cele alese anume (URI-uri cu acces dat); fără ele categoria nu are ce trimite.
        if (Config.hasSelected(this@AutomaticCollectionService, "photos")) add("photos")
        if (Config.hasSelected(this@AutomaticCollectionService, "files")) add("files")
    }

    private fun status(text: String) {
        Config.status(this, text)
        Config.prefs(this).edit().putLong("heartbeat", System.currentTimeMillis()).apply()
    }

    /**
     * Casca (core/notify/SyncNotice): replica caldă în titlu, dar forma restrânsă spune mereu ce urcă, antetul
     * „Sincronizare activă” și butonul „Oprește”. `fresh` = start nou (swipe-ul de dinainte se uită).
     */
    private fun notification(fresh: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Config.settings(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutomaticCollectionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return com.forja.app.core.notify.SyncNotice.build(this, configured, open, stop, fresh)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation(startNanos: Long) {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val callback = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (runningRevision != Config.revision(this@AutomaticCollectionService) || l.elapsedRealtimeNanos < startNanos || !l.hasAccuracy() || l.accuracy > 10000) return
                synchronized(fixes) {
                    if (fixes.lastOrNull()?.let { l.time - it.at < 10000 } == true) return
                    fixes += SyncFix(l.time, l.latitude, l.longitude, l.accuracy, 1)
                    while (fixes.size > 300) fixes.removeAt(0)
                }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { status("Locația telefonului este oprită. Celelalte categorii rămân active.") }
            @Deprecated("Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        // Fiecare furnizor în propriul try: doar cu locație aproximativă, GPS aruncă SecurityException, dar NETWORK merge.
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (manager.isProviderEnabled(provider)) { manager.requestLocationUpdates(provider, 10000L, 0f, callback, Looper.getMainLooper()); any = true }
            } catch (_: Exception) { }
        }
        if (!any) status("Locația nu este disponibilă momentan. Celelalte categorii rămân active.")
    }

    private fun metrics(flags: Set<String>, points: List<SyncFix>, start: Long): JSONObject = JSONObject().apply {
        if ("location" in flags) {
            put("locations", JSONArray().apply {
                points.forEach {
                    put(JSONObject().put("at", it.at).put("latitude", it.latitude).put("longitude", it.longitude).put("accuracy_m", it.accuracy.toDouble()).put("segment", it.segment))
                }
            })
            put("visits", JSONArray().apply {
                SyncMath.visits(points).forEach {
                    put(JSONObject().put("first_seen", it.first).put("last_seen", it.last).put("latitude", it.latitude).put("longitude", it.longitude).put("observed_ms", it.observedMs).put("samples", it.samples))
                }
            })
        }
        if ("app_usage" in flags) {
            val now = System.currentTimeMillis()
            val from = CollectionPolicy.usageFrom(Config.prefs(this@AutomaticCollectionService).getLong("since_app_usage", now), start, now)
            val apps = UsageReader.read(this@AutomaticCollectionService, from, now)
            put("usage_window", JSONObject().put("from", from).put("to", now).put("method", "activity_events"))
            put("app_usage", JSONArray().apply {
                apps.forEach { put(JSONObject().put("package", it.pkg).put("label", it.label).put("foreground_ms", it.duration).put("opens", it.opens).put("last_used", it.lastUsed)) }
            })
            // Mirror D: o dată pe zi, zilele încheiate (ieri … acum 7 zile) numărate întregi pe telefon, cu orele lor —
            // umplu golurile sesiunii live (serviciu oprit, rotația sesiunii, zilele dinainte de activare).
            val today = java.time.LocalDate.now()
            if (Config.prefs(this@AutomaticCollectionService).getString(KEY_BACKFILL_DAY, null) != today.toString()) {
                try {
                    val days = JSONArray()
                    for (d in UsageDay.daysToSend(today)) {
                        val u = UsageReader.readDay(this@AutomaticCollectionService, d)
                        if (u.apps.isEmpty()) continue
                        days.put(JSONObject().put("date", u.date).put("first_at", u.firstAt).put("last_at", u.lastAt)
                            .put("hours", JSONArray().apply { u.hours.forEach { put(it.coerceIn(0L, 7_200_000L)) } })
                            .put("apps", JSONArray().apply {
                                u.apps.forEach { put(JSONObject().put("package", it.pkg).put("label", it.label).put("foreground_ms", it.duration.coerceAtMost(90_000_000L)).put("opens", it.opens.coerceAtMost(100_000)).put("last_used", it.lastUsed)) }
                            }))
                    }
                    if (days.length() > 0) put("usage_backfill", days)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    private suspend fun syncSelected(t: SyncTransport, id: String, flags: Set<String>, sent: MutableMap<String, String>, authorized: () -> Boolean) {
        var sequence = 0
        for (category in listOf("photos", "files").filter { it in flags }) {
            val uris = JSONArray(Config.prefs(this).getString(category, "[]"))
            for (index in 0 until uris.length()) {
                if (sequence >= 5) return
                check(authorized())
                val uri = Uri.parse(uris.getString(index)); val slot = sequence++
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (out.size() <= MAX_ITEM) {
                        check(authorized())
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_ITEM + 1 - out.size()))
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: error("Fișierul selectat nu mai este disponibil")
                require(bytes.size in 1..MAX_ITEM) { "Un fișier selectat depășește 5 MB sau este gol" }
                val hash = SyncTransport.sha256(bytes)
                if (sent[uri.toString()] == hash) continue
                var name = "Fișier selectat"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
                check(authorized())
                t.item(
                    id, if (category == "photos") "photo" else "file", slot,
                    name.take(200).replace(Regex("[\\p{Cntrl}]"), "_"),
                    contentResolver.getType(uri)?.substringBefore(';') ?: "application/octet-stream", bytes
                )
                sent[uri.toString()] = hash
            }
        }
    }

    /** Clipuri contigue de 5 s (16 kHz mono PCM16 → WAV). Microfonul face pauză cât timp rulează sesiunea de somn. */
    @SuppressLint("MissingPermission")
    private suspend fun audio(t: SyncTransport, id: String, authorized: () -> Boolean) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Microfon incompatibil cu 16 kHz" }
        var r: AudioRecord? = null
        var sequence = 0
        var pausedForSleep = false
        fun release() {
            val rec = r ?: return
            try { rec.stop() } catch (_: Exception) { }
            try { rec.release() } catch (_: Exception) { }
            if (recorder === rec) recorder = null
            r = null
        }
        try {
            while (currentCoroutineContext().isActive && authorized()) {
                if (SleepTrackService.running) {
                    // Somnul are prioritate la microfon: eliberăm și așteptăm să se termine noaptea.
                    if (!pausedForSleep) { release(); pausedForSleep = true; status("Microfonul live face pauză cât timp se măsoară somnul.") }
                    delay(3000); continue
                }
                if (pausedForSleep) { pausedForSleep = false; status("Microfonul live a reluat după somn. Sincronizarea este activă.") }
                val rec = r ?: AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, CLIP_BYTES)).also {
                    check(authorized())
                    require(it.state == AudioRecord.STATE_INITIALIZED) { "Microfon indisponibil" }
                    r = it; recorder = it; it.startRecording()
                }
                val pcm = ByteArray(CLIP_BYTES); var offset = 0
                var interrupted = false
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive(); check(authorized())
                    if (SleepTrackService.running) { interrupted = true; break }
                    val read = rec.read(pcm, offset, pcm.size - offset, AudioRecord.READ_NON_BLOCKING)
                    require(read >= 0) { "Microfon indisponibil" }; offset += read
                    if (read == 0) delay(20)
                }
                if (interrupted) continue
                check(authorized())
                val bytes = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
                }.array()
                t.item(id, "audio", sequence++ % 24, "Microfon ${System.currentTimeMillis()}.wav", "audio/wav", bytes)
            }
        } finally { release() }
    }

    private fun halt() {
        runningRevision = -1
        transport?.cancel(); transport = null
        work?.cancel(); work = null
        beats?.cancel(); beats = null
        listener?.let { try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } catch (_: Exception) { } }; listener = null
        try { recorder?.stop() } catch (_: Exception) { }
        synchronized(fixes) { fixes.clear() }
    }

    /** Ieșirea din prim-plan + oprirea serviciului, o singură cale. */
    private fun finish() {
        stopping = true
        halt()
        // Oprit de tine sau fără contract: alarma găsirii nu îl mai repornește. Altfel rămâne, ca auto-vindecare.
        if (!Config.contractOn(this) || Config.userStopped(this)) Finder.disarm(this)
        // Pornit cu startForegroundService și oprit înainte de prim-plan: întâi startForeground (tipul minim), apoi oprirea.
        if (fgPending && !foreground) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION, notification(), mask(emptySet()))
                foreground = true
            } catch (_: Exception) { }
        }
        fgPending = false
        if (foreground) { try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }; foreground = false }
        stopSelf()
    }

    /** Android 14 (API 34) cheamă varianta cu un parametru doar pentru shortService, pe care nu îl folosim. */
    override fun onTimeout(startId: Int) {
        status("Android a întrerupt sincronizarea în fundal. Redeschide FORJA pentru reluare.")
        finish()
    }

    /**
     * Android 15: bugetul dataSync (6 h / 24 h, doar cu fișiere alese) s-a terminat. Nu cade tot serviciul: repornim
     * fără DATA_SYNC (locația și găsirea rămân); dacă Android refuză, oprim curat.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0 && !noDataSync) {
            noDataSync = true
            status("Fișierele alese așteaptă până deschizi FORJA. Restul rămâne activ.")
            begin()
            if (foreground && !stopping) return
        }
        status("Android a întrerupt sincronizarea în fundal. Redeschide FORJA pentru reluare.")
        finish()
        com.forja.app.core.notify.SyncNotice.paused(this) { foreground && !stopping }
    }

    override fun onDestroy() {
        running = false
        destroyed = true
        halt(); scope.cancel()
        auth.removeAuthStateListener(authListener)
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(changes)
        super.onDestroy()
    }

    companion object {
        /** Serviciul trăiește în proces (citit de alarma găsirii și de auto-vindecare). */
        @Volatile var running = false
            private set
        const val CHANNEL = "sync"
        private const val NOTIFICATION = 36
        private const val STOP = "com.forja.app.sync.STOP"
        private const val SESSION_MS = 23 * 3600000L
        /** Ziua (YYYY-MM-DD) în care au plecat zilele încheiate (usage_backfill). */
        private const val KEY_BACKFILL_DAY = "usage_backfill_day"
        private const val MAX_ITEM = 5 * 1024 * 1024
        private const val CLIP_BYTES = 160000
    }
}
