package com.forja.app.core.music

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

/**
 * Un singur motor de pornire pentru Inventar („Pornește muzica”, Play, TOP 1) și Antrenament (DESIGN-4.4 §1.10).
 *
 * Leagă mașina pură ([StartMachine], [Planner]) de telefon: instantaneul sesiunilor și al playerelor, treptele
 * ([MusicRungs]), termenele (Handler), jurnalul ([MusicLog]), tabelul învățat, împrumuturile ([LeaseBook]) și coada
 * FORJA a Antrenamentului ([MusicQueue]). Totul pe firul principal.
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
    private var historyPkgs: Set<String> = emptySet()
    private var installed: Set<String> = emptySet()
    private var searchable: Set<String> = emptySet()
    private val versions = HashMap<String, String>()
    private var keyToken: MediaSession.Token? = null

    private var musicQueue: MusicQueue? = null
    private var queueMix: Mix = Mix.MIX
    private var pendingList: FPlaylist? = null
    private var qKey: String? = null
    private var qPos = 0L
    private var qDur = 0L
    private var qSeenAt = 0L
    private var qPlaying = false

    private val tick: Runnable = Runnable { machine.onChange() }

    private class Players(val installed: Set<String>, val preferred: String?, val searchable: Set<String>, val versions: Map<String, String>)

    private val port: StartPort = object : StartPort {
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun wallNow(): Long = System.currentTimeMillis()
        override fun snapshot(): Snapshot = buildSnapshot()
        override fun send(step: Step, want: Want): SendResult {
            val ctx = appCtx ?: return SendResult.Skipped("no-context")
            return MusicRungs.send(ctx, step, keyToken)
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
            _state.value = state
        }
        override fun log(event: AttemptEvent) {
            appCtx?.let { MusicLog.add(it, event) }
        }
        override fun learn(pkg: String?, rung: Rung, outcome: LearnedTable.Outcome) {
            if (pkg == null) return
            learned = learned.record(pkg, versions[pkg], rung, outcome, System.currentTimeMillis())
            val table = learned
            val ctx = appCtx ?: return
            scope.launch { try { withContext(Dispatchers.IO) { MusicStats.setLearned(ctx, table) } } catch (_: Exception) { } }
        }
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
                    machine.resumed()
                }
            })
        } catch (_: Exception) { }
    }

    /** Istoric, top, playerul preferat, ce e instalat (înainte de fiecare pornire; DataStore e deja în memorie). */
    private suspend fun refresh(app: Context) {
        try {
            val data = withContext(Dispatchers.IO) {
                Triple(MusicStats.topRef(app, 7), MusicStats.preferredPkg(app), MusicStats.historyPkgs(app))
            }
            top = data.first
            historyPkgs = data.third
            // Întrebările către PackageManager, în afara firului principal.
            val known = HashMap(versions)
            val found = withContext(Dispatchers.IO) {
                var inst = MusicKind.MUSIC_APPS.keys.filter { MusicRungs.installed(app, it) }.toSet() +
                    data.third.filter { MusicRungs.installed(app, it) }
                val pref = data.second?.takeIf { it in inst }
                    ?: MusicKind.SPOTIFY.takeIf { it in inst }
                    ?: MusicKind.YT_MUSIC.takeIf { it in inst }
                    ?: MusicKind.SAMSUNG_MUSIC.takeIf { it in inst }
                    ?: MusicRungs.defaultMusicApp(app)?.also { inst = inst + it }
                val candidates = setOfNotNull(pref, MusicKind.SPOTIFY.takeIf { it in inst }, data.first?.pkg)
                val search = candidates.filter { MusicRungs.searchable(app, it) }.toSet()
                val vers = inst.filter { it !in known }.mapNotNull { p -> MusicRungs.version(app, p)?.let { p to it } }.toMap()
                Players(inst, pref, search, vers)
            }
            installed = found.installed
            preferred = found.preferred
            searchable = found.searchable
            versions.putAll(found.versions)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    private fun foreground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) {
        true
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
            learned = learned,
            versions = HashMap(versions),
            now = System.currentTimeMillis()
        )
    }

    // ───────────────────────────── Comenzi ─────────────────────────────

    /** Pornește o intenție. `tap` = o atingere acum (un salt vizibil în player e permis în 1,5 s). */
    fun start(context: Context, want: Want, source: MusicSource, tap: Boolean) {
        launchStart(context.applicationContext, source, tap, want) { want }
    }

    /**
     * O pornire nouă, o singură încercare odată: anulează ce era, arată „Pornește…” din prima clipă, pregătește
     * intenția ([prepare]: istoricul, lista) și abia apoi pornește mașina. O pauză sau ieșirea din ecran o pot opri
     * și în timpul pregătirii.
     */
    private fun launchStart(app: Context, source: MusicSource, tap: Boolean, shown: Want, prepare: suspend () -> Want) {
        Music.ensureStarted(app)
        Music.onMain {
            startJob?.cancel()
            startJob = null
            machine.cancel()
            pendingList = null
            _origin.value = source
            _state.value = StartState.Starting(Rung.ALREADY, shown)
            startJob = scope.launch {
                val want = prepare()
                refresh(app)
                machine.start(want, source, tap)
            }
        }
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

    /** Pauză pe ce cântă (sesiunea care cântă; fără acces, tasta PAUSE doar dacă se aude ceva). Anulează o pornire. */
    fun pause(context: Context) {
        val app = context.applicationContext
        Music.ensureStarted(app)
        Music.onMain {
            abortPending()
            if (machine.active) machine.cancel()
            val playing = Music.sessions.value.let { list ->
                list.firstOrNull { it.id == Music.nowPlaying.value?.id && it.state.activeish } ?: list.firstOrNull { it.state.activeish }
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
     * Ieșirea de urgență (apăsare lungă pe disc, „Deschide playerul”): playerul sesiunii arătate, altfel Spotify la
     * Melodii apreciate, altfel aplicația de muzică. False dacă nu s-a deschis nimic.
     */
    fun openPlayer(context: Context): Boolean {
        val app = context.applicationContext
        val hero = Music.nowPlaying.value ?: Music.other.value
        val step = when {
            hero?.id != null -> Step(Rung.O_SESSION, hero.pkg, hero.id)
            preferred == MusicKind.SPOTIFY || preferred == null && MusicRungs.installed(app, MusicKind.SPOTIFY) -> Step(Rung.O_LIKED_PAGE, MusicKind.SPOTIFY)
            else -> Step(Rung.O_LAUNCH, preferred)
        }
        return MusicRungs.send(app, step, null) is SendResult.Sent
    }

    /** Ecranul care a pornit încercarea s-a închis: încercarea lui se oprește (nu și muzica). */
    fun cancel(source: MusicSource) {
        Music.onMain {
            val pending = startJob?.isActive == true && _origin.value == source
            if (machine.source == source || pending) {
                abortPending()
                machine.cancel()
            }
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

    // ───────────────────────────── Antrenament ─────────────────────────────

    /**
     * „Începe sesiunea” cu „Muzică” pornit: lista FORJA ([Playlist]) pentru cât ține sesiunea, prima piesă pe sesiunea
     * playerului, apoi coada. Nimic vizibil fără o atingere. Nu așteaptă nimic: antrenamentul merge oricum.
     */
    fun startWorkout(context: Context, mix: Mix, targetMin: Int, tap: Boolean = false) {
        val app = context.applicationContext
        launchStart(app, MusicSource.WORKOUT, tap, Want.Workout(null)) {
            val list = try {
                withContext(Dispatchers.IO) {
                    Playlist.build(MusicStats.rows(app), MusicStats.library(app), mix, targetMin, System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            pendingList = list
            queueMix = mix
            Want.Workout(list?.items?.firstOrNull()?.ref())
        }
    }

    /**
     * Finalul antrenamentului: oprește doar muzica pornită de FORJA ([finished] = sesiunea s-a terminat: după „Oprește la
     * final”; „Încheie” = mereu), apoi sunetul „misiune îndeplinită” (doar la final; estompează muzica rămasă).
     */
    fun endWorkout(context: Context, finished: Boolean) {
        val app = context.applicationContext
        Music.onMain {
            if (machine.source == MusicSource.WORKOUT || _origin.value == MusicSource.WORKOUT && startJob?.isActive == true) {
                abortPending()
                machine.cancel()
            }
            stopQueue()
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

    /** Piesa asta a pornit-o lista FORJA? (ascultările ei nu hrănesc lista). */
    internal fun isForjaTrack(pkg: String, title: String, artist: String, mediaId: String?): Boolean {
        val q = musicQueue ?: return false
        if (q.released || q.targetPkg != pkg) return false
        val key = TrackKey.of(title, artist)
        return q.items().any { it.key == key || mediaId != null && it.mediaId == mediaId }
    }

    // ───────────────────────────── Reușita, coada, împrumuturile ─────────────────────────────

    private fun onSuccess(step: Step, want: Want, source: MusicSource, wrongTrack: Boolean) {
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
        if (source == MusicSource.WORKOUT && want is Want.Workout && want.first != null && step.rung == Rung.S_TOP && !wrongTrack &&
            list != null && list.items.isNotEmpty() && pkg != null && learned.tracksLand(pkg, versions[pkg])
        ) {
            val q = MusicQueue(list.items, list.reserve, pkg)
            musicQueue = q
            qKey = TrackKey.of(list.items[0].title, list.items[0].artist)
            _queue.value = QueueInfo(0, q.size, queueMix)
        }
    }

    private fun stopQueue() {
        musicQueue = null
        qKey = null
        _queue.value = null
    }

    private fun applyQueue(ctx: Context, a: MusicQueue.Action, target: SessionView) {
        val q = musicQueue ?: return
        when (a) {
            is MusicQueue.Action.Issue -> {
                val c = Music.controller(target.id)
                val r = if (c != null) MusicRungs.playTrack(c, a.item.ref()) else SendResult.Skipped("no-session")
                if (r is SendResult.Sent) {
                    _queue.value = QueueInfo(a.index, q.size, queueMix)
                } else {
                    q.release("send-failed")
                    stopQueue()
                }
            }
            MusicQueue.Action.SeekZero -> try { Music.controller(target.id)?.transportControls?.seekTo(0) } catch (_: Exception) { }
            is MusicQueue.Action.Release -> {
                // A preluat ea: muzica e a ei, nu se mai oprește la final.
                if (a.reason == "takeover") leases.drop()
                stopQueue()
            }
            MusicQueue.Action.Hold, MusicQueue.Action.None -> Unit
        }
    }

    /** Sesiunile sau playerele s-au schimbat (din [Music], pe firul principal). */
    internal fun onMediaChanged() {
        if (machine.active) machine.onChange()
        val ctx = appCtx ?: return
        val sessions = Music.sessions.value
        // Împrumutul cade dacă altă aplicație de muzică a preluat.
        val lp = leasePkg
        if (leases.owner() != null && lp != null && sessions.any { it.state == PState.PLAYING && it.kind == MediaKind.MUSIC && it.pkg != lp }) {
            leases.drop()
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
            if (q.released) stopQueue() else if (a is MusicQueue.Action.None) _queue.value = QueueInfo(q.index, q.size, queueMix)
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
}
