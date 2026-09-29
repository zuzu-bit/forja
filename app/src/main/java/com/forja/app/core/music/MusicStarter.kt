package com.forja.app.core.music

import android.app.Activity
import android.content.Context
import android.media.session.MediaSession
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Un singur motor de pornire pentru Inventar („Pornește muzica”, Play, TOP 1) și Antrenament (DESIGN-4.4 §1.10).
 *
 * Leagă mașina pură ([StartMachine], [Planner]) de telefon: instantaneul sesiunilor și al playerelor, treptele
 * ([MusicRungs]), termenele (Handler), jurnalul ([MusicLog]), tabelul învățat, împrumuturile ([LeaseBook]) și coada
 * FORJA a Antrenamentului ([MusicQueue]). Totul pe firul principal.
 *
 * 4.4.1 (music-design.md §C1): detecția cu două întrebări plus playerele văzute cântând, Spotify niciodată ascuns de
 * detecție ([Snapshot.absent]), rândul ENV înaintea fiecărei porniri, fereastra saltului măsurată de la atingerea
 * reală, saltul „pentru rezultat” pe care FORJA îl închide singură ([HopWatch], RET), verificarea aterizării cozii.
 */
object MusicStarter {

    /** Coada FORJA activă: piesa curentă din listă. */
    data class QueueInfo(val index: Int, val size: Int, val mix: Mix)

    private val _state = MutableStateFlow<StartState>(StartState.Idle)
    val state: StateFlow<StartState> = _state.asStateFlow()

    private val _origin = MutableStateFlow<MusicSource?>(null)
    /** Cine a cerut ultima pornire (ecranul Muzică arată doar starea pornirilor lui, discul doar pe ale Antrenamentului). */
    val origin: StateFlow<MusicSource?> = _origin.asStateFlow()

    private val _queue = MutableStateFlow<QueueInfo?>(null)
    val queue: StateFlow<QueueInfo?> = _queue.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /**
     * Un rând de spus pe ecranul live (toast), o singură dată pe antrenament: „Azi, ordinea o alege Spotify.” Rămâne
     * aici până îl arată ecranul ([noticeShown]), ca să nu se piardă cât FORJA nu e în față.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeSaid = false

    /**
     * Activitatea FORJA vie (MainActivity o înregistrează în onCreate și o șterge în onDestroy). Saltul intermediar în
     * player pleacă din ea, pentru rezultat și fără task nou, ca FORJA să-l poată închide (RET_SUB).
     */
    @Volatile var host: WeakReference<Activity>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> })

    @Volatile private var appCtx: Context? = null
    private var attached = false
    private var startJob: Job? = null

    private val leases = LeaseBook()
    private var leasePkg: String? = null
    private var workoutLease: Lease? = null

    private var learned: LearnedTable = LearnedTable.EMPTY
    private var top: TrackRef? = null
    private var preferred: String? = null
    /** Playerul ei văzut cântând (cel mai ascultat în 30 de zile, altfel ultimul): nu o presupunere din ce e instalat. */
    private var ownPlayer: String? = null
    private var lastPkg: String? = null
    private var historyPkgs: Set<String> = emptySet()
    private var installed: Set<String> = emptySet()
    private var searchable: Set<String> = emptySet()
    private val versions = HashMap<String, String>()
    private var keyToken: MediaSession.Token? = null
    /** Spotify, după cele două întrebări către PackageManager (pentru rândul ENV și pentru [absent]). */
    private var spotifyProbe: MusicRungs.Probe? = null
    /** Clasa primei erori din ultima citire a istoricului / a playerelor (rândul ENV, câmpul `ex`). */
    private var refreshError: String? = null
    /** Playere dovedite lipsă până la repornirea aplicației ([Snapshot.absent]). */
    private val absent = HashSet<String>()
    /** Ultima citire completă și fără erori a istoricului și a playerelor ([refresh]); ceasul monoton, 0 = niciuna. */
    private var refreshedAt = 0L

    /** Saltul în player deschis de FORJA și întoarcerea (RET_SUB). */
    private val hops = HopWatch()

    /** Ultima intenție pornită (pentru „Încearcă din nou”: aceeași, nu altă muzică). */
    private var lastWant: Want? = null
    /** Ce cânta deja când pornirea a rămas la „Nu a pornit.” / „Deschide Spotify” (doar ce pornește după le șterge). */
    private var stuckSessions: Set<String> = emptySet()
    private var stuckConfigs: Set<Int> = emptySet()

    private var musicQueue: MusicQueue? = null
    private var queueMix: Mix = Mix.MIX
    private var pendingList: FPlaylist? = null
    private var qKey: String? = null
    private var qPos = 0L
    private var qDur = 0L
    private var qSeenAt = 0L
    private var qPlaying = false

    private val tick: Runnable = Runnable { machine.onChange() }
    /** Termenul aterizării: piesa cerută trebuie să cânte în 6 s ([MusicQueue.check]). */
    private val landCheck: Runnable = Runnable { checkLanding() }
    /** La 800 ms după ce FORJA a închis ecranul playerului: e din nou în față? (rândul RET). */
    private val retCheck: Runnable = Runnable {
        hops.check(hostVisible(), SystemClock.elapsedRealtime())?.let { logRet(it) }
    }

    private class Players(
        val installed: Set<String>,
        val preferred: String?,
        val searchable: Set<String>,
        val versions: Map<String, String>,
        val spotify: MusicRungs.Probe
    )

    private val port: StartPort = object : StartPort {
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun wallNow(): Long = System.currentTimeMillis()
        override fun snapshot(): Snapshot = buildSnapshot()
        override fun send(step: Step, want: Want): SendResult {
            val ctx = appCtx ?: return SendResult.Skipped("no-context")
            val r = MusicRungs.send(ctx, step, keyToken, host?.get())
            noteAbsent(step, r)
            // Un salt intermediar a plecat (V_*): de acum se urmărește întoarcerea în FORJA (RET).
            if (r is SendResult.Sent && step.rung.visible && !step.rung.terminal) {
                hops.sent(want.wire, step.pkg, machine.source ?: _origin.value ?: MusicSource.INVENTORY, r.note == MusicRungs.SUB, now())
            }
            return r
        }
        override fun undo(target: UndoTarget) {
            appCtx?.let { MusicRungs.undo(it, target) }
        }
        override fun schedule(atMs: Long) {
            val h = Music.handler
            h.removeCallbacks(tick)
            h.postDelayed(tick, (atMs - now()).coerceAtLeast(16L))
        }
        override fun emit(state: StartState) {
            if (state is StartState.Failed || state is StartState.NeedsTap) {
                stuckSessions = Music.sessions.value.filter { it.state == PState.PLAYING }.map { it.id }.toSet()
                stuckConfigs = Music.configs.value.map { it.id }.toSet()
            }
            _state.value = state
        }
        override fun log(event: AttemptEvent) {
            appCtx?.let { MusicLog.add(it, event) }
        }
        override fun learn(pkg: String?, rung: Rung, outcome: LearnedTable.Outcome) = learnOutcome(pkg, rung, outcome)
        override fun success(step: Step, want: Want, source: MusicSource, wrongTrack: Boolean) = onSuccess(step, want, source, wrongTrack)
        override fun ended() {
            Music.handler.removeCallbacks(tick)
            appCtx?.let { MusicLog.flush(it) }
        }
    }

    private val machine: StartMachine = StartMachine(port)

    // ───────────────────────────── Legarea ─────────────────────────────

    /** Chemată de [Music.ensureStarted]: încarcă ce s-a învățat și ascultă revenirea FORJA în față. */
    internal fun attach(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (attached) return
        attached = true
        scope.launch {
            try { learned = withContext(Dispatchers.IO) { MusicStats.learned(app) } } catch (_: Exception) { }
            refresh(app)
        }
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // S-a întors ea din player înainte ca FORJA să-i închidă ecranul: RET back.
                    hops.back(SystemClock.elapsedRealtime())?.let { logRet(it) }
                    machine.resumed()
                }
            })
        } catch (_: Exception) { }
    }

    /**
     * Istoricul, topul, playerul ei, ce e pe telefon (la deschiderea hubului și înainte de fiecare pornire; DataStore e
     * deja în memorie). Fiecare citire e separată: una care cade nu le mai golește pe celelalte (29.09: un `catch` gol
     * lăsa „nimic instalat”), iar clasa primei erori ajunge în rândul ENV. Un player văzut cântând muzică (istoric,
     * ultimul player, sesiunile de acum) e pe telefon, orice ar spune PackageManager.
     */
    private suspend fun refresh(app: Context) {
        val topR = read { MusicStats.topRef(app, 7) }
        val prefR = read { MusicStats.preferredPkg(app) }
        val histR = read { MusicStats.historyPkgs(app) }
        val lastR = read { MusicStats.lastMusicPkg(app) }
        var error = listOf(topR, prefR, histR, lastR).firstNotNullOfOrNull { it.exceptionOrNull() }?.let { cls(it) }
        // O citire căzută păstrează ce se știa (de la hub sau de la pornirea de dinainte).
        top = topR.getOrElse { top }
        ownPlayer = prefR.getOrElse { ownPlayer }
        historyPkgs = histR.getOrElse { historyPkgs }
        lastPkg = lastR.getOrElse { lastPkg }
        val seen = LinkedHashSet<String>().apply {
            addAll(historyPkgs)
            lastPkg?.let { add(it) }
            Music.sessions.value.filter { it.kind == MediaKind.MUSIC && !it.remote }.forEach { add(it.pkg) }
        }.filter { it !in MusicKind.SPOKEN_APPS && it !in MusicKind.VIDEO_APPS }
        val own = ownPlayer
        val topPkg = top?.pkg
        val known = HashMap(versions)
        val gone = absent.toSet()
        try {
            // Întrebările către PackageManager, în afara firului principal.
            val found = withContext(Dispatchers.IO) {
                val sp = MusicRungs.probe(app, MusicKind.SPOTIFY)
                var inst: Set<String> = (MusicKind.MUSIC_APPS.keys.filter { p ->
                    if (p == MusicKind.SPOTIFY) sp.any else MusicRungs.installed(app, p)
                } + seen).toSet()
                val pref = own?.takeIf { it in inst }
                    ?: MusicKind.SPOTIFY.takeIf { it in inst }
                    ?: MusicKind.YT_MUSIC.takeIf { it in inst }
                    ?: MusicKind.SAMSUNG_MUSIC.takeIf { it in inst }
                    ?: MusicRungs.defaultMusicApp(app)?.also { inst = inst + it }
                val candidates = setOfNotNull(pref, MusicKind.SPOTIFY.takeIf { it !in gone }, topPkg)
                val search = candidates.filter { MusicRungs.searchable(app, it) }.toSet()
                val vers = HashMap<String, String>()
                sp.version?.let { vers[MusicKind.SPOTIFY] = it }
                for (p in inst) if (p !in known && p !in vers) MusicRungs.version(app, p)?.let { vers[p] = it }
                Players(inst, pref, search, vers, sp)
            }
            installed = found.installed
            preferred = found.preferred
            searchable = found.searchable
            versions.putAll(found.versions)
            spotifyProbe = found.spotify
            // PackageManager îl vede din nou (l-a instalat între timp): nu mai e „lipsă”.
            if (found.spotify.any) absent -= MusicKind.SPOTIFY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (error == null) error = cls(e)
        }
        refreshError = error
        if (error == null) refreshedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Hubul ([prewarm]) sau pornirea de dinainte au citit totul de curând (și nimic nu e dovedit lipsă): atingerea nu
     * recitește istoricul și playerele, ca pregătirea să nu mănânce din cele 1,5 s ale saltului.
     */
    private fun refreshedRecently(): Boolean =
        refreshedAt != 0L && SystemClock.elapsedRealtime() - refreshedAt < FRESH_MS && absent.isEmpty()

    /** Partea ieftină a [refresh], pe firul principal: un player văzut acum cântând muzică e pe telefon. */
    private fun seePlaying() {
        val playing = Music.sessions.value.filter { it.kind == MediaKind.MUSIC && !it.remote }.map { it.pkg }
            .filter { it !in installed && it !in MusicKind.SPOKEN_APPS && it !in MusicKind.VIDEO_APPS }
        if (playing.isNotEmpty()) installed = installed + playing
    }

    /** O citire din DataStore, pe firul de I/O: reușita sau excepția ei (anularea trece mai departe). */
    private suspend fun <T> read(block: suspend () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun cls(e: Throwable): String = e.javaClass.simpleName.ifEmpty { "Exception" }

    /**
     * Un link VIEW Spotify la care nu răspunde nicio activitate, iar ambele întrebări către PackageManager spun „nu e”:
     * Spotify chiar lipsește — până la repornirea aplicației, planurile merg la celălalt player (sau la nimic). O căutare
     * fără răspuns nu dovedește nimic ([Planner.provesSpotifyMissing]).
     */
    private fun noteAbsent(step: Step, r: SendResult) {
        if (r !is SendResult.Skipped || r.reason != "no-activity") return
        if (Planner.provesSpotifyMissing(step, probedAny = spotifyProbe?.any == true)) absent += MusicKind.SPOTIFY
    }

    private fun foreground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) {
        true
    }

    /** Ecranul FORJA (MainActivity) e din nou în față? Direct din ciclul lui de viață (fără întârzierea de 700 ms a procesului). */
    private fun hostVisible(): Boolean {
        val owner = host?.get() as? LifecycleOwner ?: return foreground()
        return try { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } catch (_: Exception) { foreground() }
    }

    private var accessAt = 0L
    private var accessCached = false

    /** Accesul se citește din Setări; în timpul unei încercări instantaneele sunt dese, deci cel mult o dată la 2 s. */
    private fun access(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - accessAt > 2_000L || accessAt == 0L) {
            accessCached = Music.hasAccess(ctx)
            accessAt = now
        }
        return accessCached
    }

    private fun buildSnapshot(): Snapshot {
        val ctx = appCtx ?: return Snapshot(access = false)
        val access = access(ctx)
        var keyTarget: String? = null
        var outside = false
        keyToken = null
        if (access && Build.VERSION.SDK_INT >= 33) {
            val m = Music.sessionManager(ctx)
            try {
                keyTarget = m?.mediaKeyEventSessionPackageName?.takeIf { it.isNotBlank() }
                val token = m?.mediaKeyEventSession
                if (token != null && Music.idOf(token) == null) {
                    keyToken = token
                    outside = true
                }
            } catch (_: Exception) { }
        }
        val sessions = if (access) Music.sessions.value else emptyList()
        for (s in sessions) if (s.pkg !in versions) MusicRungs.version(ctx, s.pkg)?.let { versions[s.pkg] = it }
        return Snapshot(
            access = access,
            sdk = Build.VERSION.SDK_INT,
            fg = foreground(),
            sessions = sessions,
            configs = Music.configs.value,
            keyTarget = keyTarget,
            keyTokenOutside = outside,
            preferredPkg = preferred,
            top = top,
            installed = installed,
            searchable = searchable,
            historyPkgs = historyPkgs,
            absent = absent.toSet(),
            learned = learned,
            versions = HashMap(versions),
            now = System.currentTimeMillis()
        )
    }

    /**
     * Rândul ENV, chiar înaintea pornirii (4.4.1): ce vedea FORJA — doar coduri de pachete și forma ID-ului, niciun
     * titlu; `ms` = cât a durat pregătirea de la atingere. Proba ascunsă nu-l scrie (fiecare treaptă a ei e o pornire).
     */
    private fun logEnv(ctx: Context, want: Want, snap: Snapshot, prepMs: Long) {
        if (want is Want.Probe) return
        val sp = spotifyProbe
        val spotifyId = snap.sessions.firstOrNull { it.pkg == MusicKind.SPOTIFY && !it.mediaId.isNullOrBlank() }?.mediaId
            ?: snap.top?.takeIf { it.pkg == MusicKind.SPOTIFY }?.mediaId
        val line = EnvLine.line(
            EnvLine.Facts(
                launch = sp?.launch == true,
                info = sp?.info == true,
                installed = snap.installed,
                sessions = snap.sessions.map { it.pkg },
                keyTarget = snap.keyTarget,
                history = snap.historyPkgs,
                last = lastPkg,
                preferred = snap.preferredPkg,
                track = (want as? Want.Workout)?.first ?: snap.top,
                spotifyMediaId = spotifyId,
                fg = snap.fg,
                error = refreshError
            )
        )
        MusicLog.add(
            ctx,
            AttemptEvent(System.currentTimeMillis(), want.wire, EnvLine.RUNG, null, null, null, DiagResult.SKIPPED, prepMs.coerceAtLeast(0L), line)
        )
    }

    // ───────────────────────────── Comenzi ─────────────────────────────

    /** Pornește o intenție. `tap` = o atingere acum (un salt vizibil în player e permis în 1,5 s de la ea). */
    fun start(context: Context, want: Want, source: MusicSource, tap: Boolean) {
        launchStart(context.applicationContext, source, tap, want) { want }
    }

    /**
     * O pornire nouă, o singură încercare odată: anulează ce era, arată „Pornește…” din prima clipă, pregătește
     * intenția ([prepare]: istoricul, lista), citește din nou playerele (doar dacă n-au fost citite de curând,
     * [refreshedRecently]), scrie rândul ENV și abia apoi pornește mașina. Momentul atingerii se ia ÎNAINTEA pregătirii:
     * o pregătire lentă duce la „Deschide Spotify”, nu la un salt târziu. O pauză sau ieșirea din ecran o pot opri și în
     * timpul pregătirii.
     */
    private fun launchStart(app: Context, source: MusicSource, tap: Boolean, shown: Want, prepare: suspend () -> Want) {
        val calledAt = SystemClock.elapsedRealtime()
        val tapAt = if (tap) calledAt else null
        Music.ensureStarted(app)
        Music.onMain {
            // O pornire nouă vine dintr-o atingere în FORJA: un salt de dinainte rămas deschis s-a încheiat (s-a întors ea,
            // prea repede ca procesul să fi trecut prin fundal) — altfel o reușită de acum l-ar raporta drept „auto”.
            if (hops.open && hostVisible()) hops.settle(SystemClock.elapsedRealtime())?.let { logRet(it) }
            startJob?.cancel()
            startJob = null
            machine.cancel()
            pendingList = null
            _origin.value = source
            _state.value = StartState.Starting(Rung.ALREADY, shown)
            startJob = scope.launch {
                val want = prepare()
                lastWant = want
                if (refreshedRecently()) seePlaying() else refresh(app)
                logEnv(app, want, buildSnapshot(), SystemClock.elapsedRealtime() - calledAt)
                machine.start(want, source, tapAt)
            }
        }
    }

    /**
     * Hubul Antrenament s-a deschis: urmărirea sesiunilor pornește și playerele se citesc acum, ca atingerea pe
     * „Începe sesiunea” să găsească planul gata (pregătirea intră în cele 1,5 s ale saltului).
     */
    suspend fun prewarm(context: Context) {
        val app = context.applicationContext
        Music.ensureStarted(app)
        withContext(Dispatchers.Main.immediate) { refresh(app) }
    }

    /**
     * Pornirea [want] ar sări acum în player? Da, când primul pas care se poate face e vizibil (nicio sesiune a
     * playerului, tasta media la alt player — 29.09). Hubul arată atunci ↗ lângă eticheta listei. Pe firul principal.
     */
    fun willHop(want: Want): Boolean {
        if (appCtx == null) return false
        val snap = buildSnapshot()
        if (Planner.alreadyPlaying(want, snap)) return false
        return Planner.plan(want, snap).firstOrNull { it.skip == null }?.rung?.visible == true
    }

    /**
     * „Încearcă din nou”: exact intenția care n-a pornit (Reia pe carte reia cartea, nu pornește muzica), altfel
     * „Pornește muzica”.
     */
    fun retry(context: Context, source: MusicSource) {
        val w = lastWant?.takeIf { it !is Want.Workout && it !is Want.Probe } ?: Want.MyMusic
        start(context, w, source, tap = true)
    }

    /** Atingerea pe „Deschide Spotify” / „Deschide playerul” (NeedsTap, Failed). */
    fun tap(context: Context) {
        Music.ensureStarted(context)
        Music.onMain {
            if (!machine.tap()) openPlayer(context)
        }
    }

    /**
     * Discul Play/Pauză — după starea reală ([Transport.toggle]); niciodată pauză pe un Play.
     * [idle] = ce pornește când nu cântă nimic (implicit „Pornește muzica”).
     */
    fun toggle(context: Context, source: MusicSource, idle: (() -> Unit)? = null) {
        val app = context.applicationContext
        Music.ensureStarted(app)
        Music.onMain {
            val hero = Music.nowPlaying.value
            val access = Music.hasAccess(app)
            when (Transport.toggle(access, hero?.playing, Music.audible.value, _state.value)) {
                ToggleAction.PAUSE -> pause(app)
                ToggleAction.RESUME -> hero?.id?.let { start(app, Want.Resume(it), source, tap = true) } ?: start(app, Want.MyMusic, source, true)
                ToggleAction.START -> if (idle != null) idle() else start(app, Want.MyMusic, source, tap = true)
                ToggleAction.NONE -> Unit
            }
        }
    }

    /**
     * Pauză pe ce cântă (sesiunea care cântă; fără acces, tasta PAUSE doar dacă se aude ceva). Anulează o pornire.
     * [sessionId] = exact sesiunea aceea (PAUZĂ pe rândul cărții oprește cartea, nu muzica de alături).
     */
    fun pause(context: Context, sessionId: String? = null) {
        val app = context.applicationContext
        Music.ensureStarted(app)
        Music.onMain {
            abortPending()
            if (machine.active) machine.cancel()
            val playing = Music.sessions.value.let { list ->
                if (sessionId != null) list.firstOrNull { it.id == sessionId }
                else list.firstOrNull { it.id == Music.nowPlaying.value?.id && it.state.activeish } ?: list.firstOrNull { it.state.activeish }
            }
            val c = Music.controller(playing?.id)
            if (c != null) {
                try { c.transportControls.pause() } catch (_: Exception) { Music.mediaKey(app, KeyEvent.KEYCODE_MEDIA_PAUSE) }
            } else if (!Music.hasAccess(app) && Music.audible.value) {
                Music.mediaKey(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }
    }

    /** ⏭: piesa următoare din lista FORJA (dacă e activă), altfel a playerului. */
    fun next(context: Context) = skip(context, forward = true)

    /** ⏮: în lista FORJA, de la capăt după 5 s sau piesa dinainte; altfel a playerului. */
    fun previous(context: Context) = skip(context, forward = false)

    private fun skip(context: Context, forward: Boolean) {
        val app = context.applicationContext
        Music.ensureStarted(app)
        Music.onMain {
            val q = musicQueue
            val target = q?.let { qq -> Music.sessions.value.firstOrNull { it.pkg == qq.targetPkg && !it.remote } }
            if (q != null && !q.released && target != null) {
                applyQueue(app, if (forward) q.next() else q.previous(target.positionMs), target)
                if (q.released) endQueue(q, q.releaseReason ?: "released")
                return@onMain
            }
            val s = Music.sessions.value.let { list ->
                list.firstOrNull { it.id == Music.nowPlaying.value?.id } ?: list.firstOrNull { it.state.activeish }
            }
            val c = Music.controller(s?.id)
            try {
                when {
                    c != null && forward -> c.transportControls.skipToNext()
                    c != null -> c.transportControls.skipToPrevious()
                    else -> Music.mediaKey(app, if (forward) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            } catch (_: Exception) {
                Music.mediaKey(app, if (forward) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        }
    }

    /**
     * Ieșirea de urgență (apăsare lungă pe disc, „Deschide playerul” fără încercare în așteptare): sesiunea de muzică
     * arătată, altfel playerul ei, altfel Spotify la Melodii apreciate ([Planner.openStep]); niciodată un video sau o
     * carte (Music.other). False dacă nu s-a deschis nimic.
     */
    fun openPlayer(context: Context): Boolean {
        val app = context.applicationContext
        val heroId = Music.nowPlaying.value?.id
        val shown = Music.sessions.value.firstOrNull { it.id == heroId }
        val step = Planner.openStep(shown, ownPlayer, absent) ?: return false
        val r = MusicRungs.send(app, step, null)
        noteAbsent(step, r)
        return r is SendResult.Sent
    }

    /** Ecranul care a pornit încercarea s-a închis: încercarea lui se oprește (nu și muzica), la fel saltul lui deschis. */
    fun cancel(source: MusicSource) {
        Music.onMain {
            closeHop(source)
            val pending = startJob?.isActive == true && _origin.value == source
            if (machine.source == source || pending) {
                abortPending()
                machine.cancel()
            } else if (_origin.value == source && stuck()) {
                // „Nu a pornit.” fără încercare în așteptare: la întoarcere, ecranul pornește curat.
                clearStuck()
            }
        }
    }

    private fun stuck(): Boolean = _state.value.let { it is StartState.Failed || it is StartState.NeedsTap }

    private fun clearStuck() {
        machine.cancel()
        _state.value = StartState.Idle
    }

    /**
     * Pornirea a rămas la „Nu a pornit.” / „Deschide Spotify”, dar muzica a pornit altfel (din Spotify, notificare,
     * căști): starea veche nu mai stă sub comenzi. Contează doar ce a pornit după (nu ce se oprea atunci).
     */
    private fun startedElsewhere(st: StartState): Boolean {
        val resumeId = (st as? StartState.Failed)?.open?.sessionId
        val sessions = Music.sessions.value
        if (sessions.any { it.state == PState.PLAYING && (it.kind == MediaKind.MUSIC || it.id == resumeId) && it.id !in stuckSessions }) return true
        return sessions.isEmpty() && Music.configs.value.any {
            (it.content == ContentHint.MUSIC || it.content == ContentHint.NONE) && it.id !in stuckConfigs
        }
    }

    /** O pornire încă în pregătire (istoricul se citește) se oprește, iar „Pornește…” dispare. */
    private fun abortPending() {
        val job = startJob ?: return
        startJob = null
        if (job.isActive) {
            job.cancel()
            if (!machine.active) _state.value = StartState.Idle
        }
    }

    /** Cine a pornit muzica care cântă acum (null = nimeni / ea). */
    fun owner(): MusicSource? = leases.owner()

    /** Finalul unui inventar poate pune pauză? Nu cât ține un antrenament (muzica de sală nu tace pentru un inventar). */
    fun inventoryMayPause(): Boolean = leases.inventoryMayPause()

    /** „Începe sesiunea” (cu sau fără „Muzică”): de acum până la [endWorkout], un inventar nu oprește muzica. */
    fun workoutBegan() {
        Music.onMain { leases.workoutLive = true }
    }

    // ───────────────────────────── Saltul și întoarcerea (RET_SUB) ─────────────────────────────

    /** MainActivity: rezultatul saltului în player a sosit (anulat). Înaintea închiderii = ecranul lui e în alt task. */
    fun onHopResult() {
        Music.onMain { hops.result(SystemClock.elapsedRealtime()) }
    }

    /**
     * Ecranul live, la Înapoi (= „Încheie”), pe firul principal: true = apăsarea ei pentru ecranul Spotify, ajunsă după
     * ce FORJA l-a închis singură sau imediat după ce s-a întors ea ([HopWatch.swallowBack]). Nu încheie antrenamentul
     * și nu oprește muzica abia pornită; un Înapoi de după încheie ca de obicei.
     */
    fun swallowBack(): Boolean = hops.swallowBack(SystemClock.elapsedRealtime())

    /** Muzica e confirmată: ecranul playerului deschis de FORJA în taskul ei se închide, iar peste 800 ms se vede efectul. */
    private fun closeHopOnSuccess() {
        if (!hops.confirm(SystemClock.elapsedRealtime())) return
        try { host?.get()?.finishActivity(MusicRungs.REQ_HOP) } catch (_: Exception) { }
        val h = Music.handler
        h.removeCallbacks(retCheck)
        h.postDelayed(retCheck, HopWatch.CHECK_MS)
    }

    /** Sesiunea s-a încheiat / ecranul a plecat: un salt rămas deschis (ecranul playerului în taskul FORJA) se închide. */
    private fun closeHop(source: MusicSource?) {
        if (hops.drop(source)) try { host?.get()?.finishActivity(MusicRungs.REQ_HOP) } catch (_: Exception) { }
    }

    private fun logRet(r: HopWatch.Row) {
        val ctx = appCtx ?: return
        MusicLog.add(ctx, AttemptEvent(System.currentTimeMillis(), r.want, HopWatch.RUNG, r.pkg, r.pkg?.let { versions[it] }, null, r.result, r.ms, r.note))
        MusicLog.flush(ctx)
    }

    // ───────────────────────────── Antrenament ─────────────────────────────

    /**
     * „Începe sesiunea” cu „Muzică” pornit: lista FORJA ([Playlist]) pentru cât ține sesiunea, prima piesă pe sesiunea
     * playerului, apoi coada. Din 4.4.1 atingerea pe „Începe sesiunea” e atingerea care permite un salt (cel mult unul,
     * în 1,5 s de la ea). Nu așteaptă nimic: antrenamentul merge oricum. [ready] = lista clădită de hub la deschidere
     * (folosită dacă e pentru aceeași alegere și durată, [Playlist.readyOr]), ca atingerea să găsească planul gata.
     */
    fun startWorkout(context: Context, mix: Mix, targetMin: Int, tap: Boolean = false, ready: FPlaylist? = null) {
        val app = context.applicationContext
        workoutBegan()
        launchStart(app, MusicSource.WORKOUT, tap, Want.Workout(null)) {
            val list = Playlist.readyOr(ready, mix, targetMin) {
                try {
                    withContext(Dispatchers.IO) {
                        Playlist.build(MusicStats.rows(app), MusicStats.library(app), mix, targetMin, System.currentTimeMillis())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            pendingList = list
            queueMix = mix
            Want.Workout(list?.items?.firstOrNull()?.ref())
        }
    }

    /**
     * Finalul antrenamentului: oprește doar muzica pornită de FORJA ([finished] = sesiunea s-a terminat: după „Oprește la
     * final”; „Încheie” = mereu), apoi sunetul „misiune îndeplinită” (doar la final; estompează muzica rămasă).
     * Un salt rămas deschis (ecranul Spotify în taskul FORJA) se închide.
     */
    fun endWorkout(context: Context, finished: Boolean) {
        val app = context.applicationContext
        Music.onMain {
            if (machine.source == MusicSource.WORKOUT || _origin.value == MusicSource.WORKOUT && startJob?.isActive == true) {
                abortPending()
                machine.cancel()
            }
            closeHop(MusicSource.WORKOUT)
            musicQueue?.let { endQueue(it, "workout-end") }
            stopQueue()
            // Rândul „Azi, ordinea o alege Spotify.” e al acestui antrenament: următorul îl poate spune din nou.
            noticeSaid = false
            _notice.value = null
            leases.workoutLive = false
            // Discul sesiunii încheiate nu mai rămâne la „Nu a pornit.” / „Deschide Spotify”.
            if (_origin.value == MusicSource.WORKOUT && stuck()) clearStuck()
            val lease = workoutLease
            workoutLease = null
            scope.launch {
                val stop = if (finished) {
                    try { withContext(Dispatchers.IO) { MusicStats.workoutStop(app) } } catch (_: Exception) { true }
                } else true
                // Antrenamentul s-a terminat: împrumutul se eliberează oricum; muzica se oprește doar dacă era a FORJA.
                val ours = leases.release(lease)
                var paused = false
                if (ours && stop && Music.isPlayingNow(app)) {
                    pause(app)
                    paused = true
                    delay(400)
                }
                if (finished) MusicCue.play(app, MusicCue.Cue.CHIME, duck = !paused)
            }
        }
    }

    /** Ecranul live a arătat rândul ([notice]). */
    fun noticeShown() {
        _notice.value = null
    }

    /** Piesa asta a pornit-o lista FORJA? (ascultările ei nu hrănesc lista). */
    internal fun isForjaTrack(pkg: String, title: String, artist: String, mediaId: String?): Boolean {
        val q = musicQueue ?: return false
        if (q.released || q.targetPkg != pkg) return false
        val key = TrackKey.of(title, artist)
        return q.items().any { it.key == key || mediaId != null && it.mediaId == mediaId }
    }

    // ───────────────────────────── Reușita, coada, împrumuturile ─────────────────────────────

    private fun learnOutcome(pkg: String?, rung: Rung, outcome: LearnedTable.Outcome) {
        if (pkg == null) return
        learned = learned.record(pkg, versions[pkg], rung, outcome, System.currentTimeMillis())
        val table = learned
        val ctx = appCtx ?: return
        scope.launch { try { withContext(Dispatchers.IO) { MusicStats.setLearned(ctx, table) } } catch (_: Exception) { } }
    }

    private fun onSuccess(step: Step, want: Want, source: MusicSource, wrongTrack: Boolean) {
        // Muzica e confirmată: ecranul playerului deschis de FORJA în taskul ei se închide (RET_SUB).
        closeHopOnSuccess()
        if (step.rung == Rung.ALREADY || source == MusicSource.PROBE) {
            // Muzica ei cânta deja: FORJA nu ia împrumut („nu pornim peste muzica ta”).
            pendingList = null
            return
        }
        val lease = leases.acquire(source)
        leasePkg = step.pkg
        if (source == MusicSource.WORKOUT) workoutLease = lease
        val list = pendingList
        pendingList = null
        val pkg = step.pkg
        // Piesa 1 a listei a cântat, cerută pe sesiune (S_TOP, și după un salt care doar a trezit Spotify) sau prin
        // saltul spotify:track (V_TRACK): piesele 2…N vin prin coadă, cât playerul le primește.
        if (source == MusicSource.WORKOUT && want is Want.Workout && want.first != null &&
            (step.rung == Rung.S_TOP || step.rung == Rung.V_TRACK) && !wrongTrack &&
            list != null && list.items.isNotEmpty() && pkg != null && learned.tracksLand(pkg, versions[pkg])
        ) {
            musicQueue?.let { endQueue(it, "replaced") }
            val q = MusicQueue(list.items, list.reserve, pkg)
            musicQueue = q
            qKey = TrackKey.of(list.items[0].title, list.items[0].artist)
            _queue.value = QueueInfo(0, q.size, queueMix)
        }
    }

    private fun stopQueue() {
        Music.handler.removeCallbacks(landCheck)
        musicQueue = null
        qKey = null
        _queue.value = null
    }

    /**
     * Coada s-a terminat, din orice motiv: rândul QUEUE (refused = piesa cerută n-a apărut, altfel ok), cu câte piese
     * au cântat cum le-a cerut FORJA (`n:`) — pe Spotify gratuit, de aici se vede cât ține alocarea zilei.
     */
    private fun endQueue(q: MusicQueue, reason: String) {
        if (musicQueue !== q) return
        stopQueue()
        val ctx = appCtx ?: return
        val result = if (reason == MusicQueue.REFUSED) DiagResult.REFUSED else DiagResult.OK
        MusicLog.add(ctx, AttemptEvent(System.currentTimeMillis(), WORKOUT_WIRE, QUEUE_RUNG, q.targetPkg, versions[q.targetPkg], MediaKind.MUSIC, result, 0L, "n:${q.landed} $reason"))
        MusicLog.flush(ctx)
    }

    /** Un rând pe ecranul live, o singură dată pe antrenament. */
    private fun say(text: String) {
        if (noticeSaid) return
        noticeSaid = true
        _notice.value = text
    }

    private fun applyQueue(ctx: Context, a: MusicQueue.Action, target: SessionView) {
        val q = musicQueue ?: return
        when (a) {
            is MusicQueue.Action.Issue -> {
                val c = Music.controller(target.id)
                val r = if (c != null) MusicRungs.playTrack(c, a.item.ref()) else SendResult.Skipped("no-session")
                if (r is SendResult.Sent) {
                    // Cererea a plecat; piesa trebuie să apară în 6 s (altfel Spotify nu primește azi piesele cerute).
                    q.issued(a.index, SystemClock.elapsedRealtime())
                    scheduleLanding()
                    _queue.value = QueueInfo(a.index, q.size, queueMix)
                } else {
                    q.release("send-failed")
                    endQueue(q, "send-failed")
                }
            }
            MusicQueue.Action.SeekZero -> try { Music.controller(target.id)?.transportControls?.seekTo(0) } catch (_: Exception) { }
            is MusicQueue.Action.Landed -> {
                // Spotify a pus piesa cerută: se învață (tracksLand), lista merge mai departe.
                learnOutcome(q.targetPkg, Rung.S_TOP, LearnedTable.Outcome.OK)
                _queue.value = QueueInfo(a.index, q.size, queueMix)
            }
            is MusicQueue.Action.Release -> {
                // A preluat ea: muzica e a ei, nu se mai oprește la final.
                if (a.reason == "takeover") leases.drop()
                if (a.reason == MusicQueue.REFUSED) {
                    // Piesa cerută n-a apărut: ratare pe S_TOP (după 2, coada nu mai pornește până la o reușită), lista se
                    // lasă, iar autoplay-ul playerului continuă muzica. Ea află o singură dată.
                    learnOutcome(q.targetPkg, Rung.S_TOP, LearnedTable.Outcome.MISS)
                    endQueue(q, a.reason)
                    say(MusicQueue.refusedNotice(q.targetPkg))
                } else {
                    endQueue(q, a.reason)
                }
            }
            MusicQueue.Action.Hold -> scheduleLanding()
            MusicQueue.Action.None -> Unit
        }
    }

    /** Termenul aterizării piesei cerute (dacă e una în așteptare). */
    private fun scheduleLanding() {
        val h = Music.handler
        h.removeCallbacks(landCheck)
        val at = musicQueue?.landingDeadline ?: return
        h.postDelayed(landCheck, (at - SystemClock.elapsedRealtime()).coerceAtLeast(16L))
    }

    private fun checkLanding() {
        val q = musicQueue ?: return
        val ctx = appCtx ?: return
        val t = Music.sessions.value.firstOrNull { it.pkg == q.targetPkg && !it.remote }
        if (t == null) {
            // Sesiunea playerului a dispărut (oprit, omorât): nu e un refuz, coada doar se retrage.
            q.release("session-gone")
            endQueue(q, "session-gone")
            return
        }
        applyQueue(ctx, q.check(t.title, t.artist, t.mediaId, t.durationMs, SystemClock.elapsedRealtime()), t)
        if (q.released) endQueue(q, q.releaseReason ?: "released") else scheduleLanding()
    }

    /** Sesiunile sau playerele s-au schimbat (din [Music], pe firul principal). */
    internal fun onMediaChanged() {
        if (machine.active) machine.onChange()
        if (stuck() && startedElsewhere(_state.value)) clearStuck()
        val ctx = appCtx ?: return
        val sessions = Music.sessions.value
        // Împrumutul cade dacă altă aplicație de muzică a preluat.
        val lp = leasePkg
        if (leases.owner() != null && lp != null && sessions.any { it.state == PState.PLAYING && it.kind == MediaKind.MUSIC && it.pkg != lp }) {
            leases.drop()
            musicQueue?.let { endQueue(it, "other-player") }
            stopQueue()
        }
        val q = musicQueue ?: return
        val t = sessions.firstOrNull { it.pkg == q.targetPkg && !it.remote } ?: return
        val now = SystemClock.elapsedRealtime()
        val key = TrackKey.of(t.title ?: "", t.artist ?: "")
        if (t.hasMetadata && key != qKey) {
            val prevPos = qPos + if (qPlaying) now - qSeenAt else 0L
            val a = q.onTrack(t.title, t.artist, t.mediaId, t.durationMs, prevPos, qDur, t.state == PState.PLAYING, foreground())
            qKey = key
            applyQueue(ctx, a, t)
            // Aterizarea se verifică la fiecare schimbare de piesă: piesa cerută a apărut, sau o reclamă o amână.
            if (musicQueue === q && !q.released && q.landingDeadline != null) {
                applyQueue(ctx, q.check(t.title, t.artist, t.mediaId, t.durationMs, now), t)
            }
            if (q.released) endQueue(q, q.releaseReason ?: "released")
            else if (a is MusicQueue.Action.None) _queue.value = QueueInfo(q.index, q.size, queueMix)
        }
        qPos = t.positionMs
        qDur = t.durationMs
        qSeenAt = now
        qPlaying = t.state == PState.PLAYING
    }

    // ───────────────────────────── Proba ─────────────────────────────

    /** Tabelul învățat, pentru ecranul Probă. */
    internal fun learnedTable(): LearnedTable = learned

    internal suspend fun resetLearned(context: Context) {
        learned = LearnedTable.EMPTY
        withContext(Dispatchers.IO) { MusicStats.setLearned(context.applicationContext, LearnedTable.EMPTY) }
    }

    /** Ce vede motorul acum (pentru ecranul Probă). */
    internal fun snapshotNow(): Snapshot = buildSnapshot()

    internal val busy: Boolean get() = machine.active

    private const val WORKOUT_WIRE = "workout"
    private const val QUEUE_RUNG = "QUEUE"
    /** Cât rămâne bună citirea istoricului și a playerelor pentru o atingere (hubul o reface la fiecare revenire). */
    private const val FRESH_MS = 60_000L
}
