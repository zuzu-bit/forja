package com.forja.app.core.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O melodie (sau o carte, un video) așa cum o anunță sesiunea media a playerului (Spotify, YouTube Music, orice
 * player): titlu, artist, album, copertă, aplicația, starea și poziția, plus felul ei. Nimic din notificări.
 */
data class Track(
    val title: String,
    val artist: String,
    val album: String?,
    val art: Bitmap?,
    val app: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val kind: MediaKind = MediaKind.MUSIC,
    /** Sesiunea (pentru „Reia” pe exact ea). */
    val id: String? = null,
    val pkg: String? = null
)

/**
 * Muzica ta, din FORJA: ce cântă acum ([nowPlaying]), sesiunea vorbită/video de alături ([other]), comenzile
 * (prin [MusicStarter]), pauza de la finalul inventarului și ascultările tale (local).
 *
 * Sursa: sesiunile media active (MediaSessionManager.getActiveSessions), pe care Android le arată doar unui serviciu de
 * notificări aprobat de om — [MusicListenerService]. FORJA NU citește notificările: serviciul există doar ca permis.
 * Fără acces, FORJA vede doar playerele audio anonime ale sistemului (tipul lor: muzică, vorbire, film).
 *
 * Eroul ecranului (4.4): o sesiune de muzică care cântă, altfel una de muzică pe pauză. O carte audio, un podcast sau
 * un video nu devin niciodată eroul — apar alături ([other]), cu „Reia”.
 */
object Music {
    /** O ascultare = cel puțin 30 s de melodie cântată. */
    private const val PLAY_COUNT_MS = 30_000L
    /** Schimbată după 3–30 s (piesă > 60 s) = sărită. */
    private const val SKIP_MIN_MS = 3_000L

    private val _now = MutableStateFlow<Track?>(null)
    /** Muzica arătată: cea care cântă, altfel cea pe pauză; null fără acces sau fără muzică. */
    val nowPlaying: StateFlow<Track?> = _now.asStateFlow()

    private val _other = MutableStateFlow<Track?>(null)
    /** Ultima sesiune care nu e muzică (carte audio, podcast, video), cât nu e erou nimic sau cât cântă ea. */
    val other: StateFlow<Track?> = _other.asStateFlow()

    private val _audible = MutableStateFlow(false)
    /** Un player media (nu al FORJA, nu mut) se aude acum — singurul semn fără acces. */
    val audible: StateFlow<Boolean> = _audible.asStateFlow()

    /** Aceeași melodie, cu pachetul playerului și dacă e muzică (pentru hartă și ascultări). */
    internal class Now(val track: Track, val pkg: String, val music: Boolean)

    private val _session = MutableStateFlow<Now?>(null)
    internal val session: StateFlow<Now?> = _session.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionView>>(emptyList())
    /** Toate sesiunile, pentru motorul de pornire (ordinea sistemului: cea mai recentă întâi). */
    internal val sessions: StateFlow<List<SessionView>> = _sessions.asStateFlow()

    private val _configs = MutableStateFlow<List<ConfigView>>(emptyList())
    /** Playerele media active (anonime), cu tipul de conținut. */
    internal val configs: StateFlow<List<ConfigView>> = _configs.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    // Nimic din muzică nu are voie să oprească aplicația: erorile de fundal se înghit aici.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> })

    @Volatile private var appCtx: Context? = null
    private var msm: MediaSessionManager? = null
    private var listening = false
    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private var order: List<MediaSession.Token> = emptyList()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list.orEmpty()) }
    private val labels = HashMap<String, String>()
    private val categories = HashMap<String, AppCategory>()
    private var seq = 0

    /** Opțiunea „Oprește la final”: null = nesetată în acest proces (se citește din DataStore; implicit pornită). */
    @Volatile private var stopFlag: Boolean? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback, val id: String)

    // ───────────────────────────── Acces ─────────────────────────────

    /** Omul a dat „Acces la notificări” serviciului FORJA — singura cale prin care Android arată sesiunile media. */
    fun hasAccess(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Exception) {
        false
    }

    /** Pagina de sistem pentru acces: direct la FORJA pe Android 11+, lista generală mai devreme. */
    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent(context).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    internal fun listenerComponent(context: Context) = ComponentName(context, MusicListenerService::class.java)

    // ───────────────────────────── Comenzi ─────────────────────────────

    /** Play: reia exact muzica arătată; fără ea, „Pornește muzica” (scara verificată din [MusicStarter]). */
    fun play(context: Context) {
        val id = _now.value?.id
        MusicStarter.start(context, if (id != null) Want.Resume(id) else Want.MyMusic, MusicSource.INVENTORY, tap = true)
    }

    /** Pauză pe ce cântă (niciodată pe altceva); anulează o pornire în curs. */
    fun pause(context: Context) = MusicStarter.pause(context)

    fun next(context: Context) = MusicStarter.next(context)

    fun previous(context: Context) = MusicStarter.previous(context)

    /** Deschide playerul (Melodii apreciate în Spotify, altfel aplicația de muzică). False dacă nu s-a deschis nimic. */
    fun openFavorites(context: Context): Boolean = MusicStarter.openPlayer(context)

    /** Pauza de la finalul inventarului (implicit pornită). Rămâne și după o repornire a procesului. */
    fun stopWhenDone(enabled: Boolean) {
        stopFlag = enabled
        val app = appCtx ?: return
        scope.launch {
            try { withContext(Dispatchers.IO) { MusicStats.setStopWhenDone(app, enabled) } } catch (_: Exception) { }
        }
    }

    /** Starea comutatorului „Oprește la final”, pentru ecranul Muzică. */
    fun stopWhenDoneFlow(context: Context): Flow<Boolean> = MusicStats.stopWhenDoneFlow(context.applicationContext)

    /** „Pe hartă”: publică sau nu melodia pentru prieteni. Oprit → câmpul de pe hartă se șterge în câteva secunde. */
    fun shareOnMapFlow(context: Context): Flow<Boolean> = MusicStats.shareOnMapFlow(context.applicationContext)

    fun shareOnMap(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        scope.launch {
            try { withContext(Dispatchers.IO) { MusicStats.setShareOnMap(app, enabled) } } catch (_: Exception) { }
        }
    }

    /**
     * Inventarul s-a terminat: pauză (dacă „Oprește la final”), apoi sunetul FORJA „misiune îndeplinită” (arpegiu scurt,
     * sintetizat) și vibrația. Cu telefonul pe silențios sau vibrații: doar vibrația.
     * Excepție (4.4): cât ține un antrenament, muzica nu se oprește (fie pornită de FORJA, fie de ea) — sala nu tace
     * pentru un inventar; sunetul doar o estompează.
     */
    fun onInventoryDone(context: Context) {
        val app = context.applicationContext
        ensureStarted(app)
        scope.launch {
            val stop = stopFlag ?: try { withContext(Dispatchers.IO) { MusicStats.stopWhenDone(app) } } catch (_: Exception) { true }
            var paused = false
            if (stop && MusicStarter.inventoryMayPause() && isPlayingNow(app)) {
                MusicStarter.pause(app)
                paused = true
                delay(400)   // playerul coboară volumul; sunetul vine în liniște
            }
            MusicCue.play(app, MusicCue.Cue.CHIME, duck = !paused)
        }
    }

    /** Melodia ta cea mai ascultată în ultimele [days] zile (doar muzică): („Titlu · Artist”, ascultări), sau null. */
    suspend fun topTrack(context: Context, days: Int = 7): Pair<String, Int>? =
        try {
            withContext(Dispatchers.IO) { MusicStats.top(context.applicationContext, days) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Aceeași piesă, cu pachetul și ID-ul media (pentru cipul „TOP 1” și pornire). */
    suspend fun topRef(context: Context, days: Int = 7): TrackRef? =
        try {
            withContext(Dispatchers.IO) { MusicStats.topRef(context.applicationContext, days) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ───────────────────────────── Sesiunile media ─────────────────────────────

    /** Pornește urmărirea sesiunilor (idempotent), a playerelor audio și publicarea pe hartă. */
    internal fun ensureStarted(context: Context) {
        val app = context.applicationContext
        val first = appCtx == null
        appCtx = app
        MusicPresence.ensureStarted(app)
        onMain {
            startConfigs(app)
            startSessions(app)
            MusicStarter.attach(app)
        }
        if (first) MusicCloud.maybeUpload(app)
    }

    internal fun onListenerConnected(context: Context) = ensureStarted(context)

    internal fun onListenerDisconnected(context: Context) {
        val app = context.applicationContext
        onMain { if (!hasAccess(app)) stopSessions() }
    }

    internal fun sessionManager(context: Context): MediaSessionManager? =
        msm ?: context.applicationContext.getSystemService(MediaSessionManager::class.java)?.also { msm = it }

    private fun startSessions(app: Context) {
        if (!hasAccess(app)) {
            if (listening || tracked.isNotEmpty()) stopSessions()
            return
        }
        val m = sessionManager(app) ?: return
        val cn = listenerComponent(app)
        try {
            if (!listening) {
                m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
                listening = true
            }
            onSessions(m.getActiveSessions(cn).orEmpty())
        } catch (_: SecurityException) {
            stopSessions()
        } catch (_: Exception) {
        }
    }

    private fun stopSessions() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        listening = false
        for (t in tracked.values) {
            try { t.controller.unregisterCallback(t.callback) } catch (_: Exception) { }
        }
        tracked.clear()
        order = emptyList()
        recompute()
    }

    private fun onSessions(list: List<MediaController>) {
        val own = appCtx?.packageName
        val keep = list.filter { it.packageName != own }
        val tokens = keep.map { it.sessionToken }.toSet()
        val it = tracked.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in tokens) {
                try { e.value.controller.unregisterCallback(e.value.callback) } catch (_: Exception) { }
                it.remove()
            }
        }
        for (c in keep) {
            val token = c.sessionToken
            if (tracked.containsKey(token)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
                override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
                override fun onSessionDestroyed() {
                    tracked.remove(token)?.let { t -> try { t.controller.unregisterCallback(t.callback) } catch (_: Exception) { } }
                    order = order - token
                    recompute()
                }
            }
            try {
                c.registerCallback(cb, main)
                tracked[token] = Tracked(c, cb, "${c.packageName}#${++seq}")
            } catch (_: Exception) { }
        }
        // Ordinea sistemului = prioritatea (sesiunea cea mai recentă întâi).
        order = keep.map { it.sessionToken }.filter { tracked.containsKey(it) }
        recompute()
    }

    private fun trackedList(): List<Tracked> = order.mapNotNull { tracked[it] }

    /** Controlerul sesiunii cu id-ul dat (pentru motor). */
    internal fun controller(id: String?): MediaController? = if (id == null) null else tracked.values.firstOrNull { it.id == id }?.controller

    /** Id-ul sesiunii cu tokenul dat, dacă e în listă. */
    internal fun idOf(token: MediaSession.Token?): String? = if (token == null) null else tracked[token]?.id

    private val playingSince = HashMap<String, Long>()

    private fun recompute() {
        val app = appCtx
        val list = trackedList()
        val now = SystemClock.elapsedRealtime()
        for (t in list) {
            val st = try { t.controller.playbackState?.state } catch (_: Exception) { null }
            if (PState.of(st) == PState.PLAYING) playingSince.getOrPut(t.id) { now } else playingSince.remove(t.id)
        }
        playingSince.keys.retainAll(list.map { it.id }.toSet())
        correlate()
        val finalViews = if (app == null) emptyList() else list.map { view(app, it) }
        _sessions.value = finalViews

        val hero = finalViews.firstOrNull { it.state.activeish && it.hasMetadata && (it.kind == MediaKind.MUSIC || it.kind == MediaKind.UNKNOWN) }
            ?: finalViews.firstOrNull { it.hasMetadata && it.kind == MediaKind.MUSIC && !it.state.activeish && !it.remote }
        val otherView = finalViews.firstOrNull { v ->
            v.hasMetadata && v.id != hero?.id &&
                (v.kind == MediaKind.SPOKEN || v.kind == MediaKind.VIDEO || v.kind == MediaKind.UNKNOWN && !v.state.activeish)
        }?.takeIf { hero == null || it.state.activeish }

        val heroNow = if (app != null && hero != null) list.firstOrNull { it.id == hero.id }?.let { toNow(app, it, hero) } else null
        _session.value = heroNow
        _now.value = heroNow?.track
        _other.value = if (app != null && otherView != null) list.firstOrNull { it.id == otherView.id }?.let { toNow(app, it, otherView)?.track } else null
        countPlay(heroNow, hero)
        if (app != null && hero != null && hero.kind == MediaKind.MUSIC && hero.state == PState.PLAYING) rememberPlayer(app, hero.pkg)
        MusicStarter.onMediaChanged()
    }

    private fun titleOf(md: MediaMetadata?): String? {
        if (md == null) return null
        return text(md, MediaMetadata.METADATA_KEY_TITLE) ?: text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    }

    private fun artistOf(md: MediaMetadata): String = listOf(
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        MediaMetadata.METADATA_KEY_AUTHOR
    ).firstNotNullOfOrNull { text(md, it) } ?: ""

    private fun text(md: MediaMetadata, key: String): String? =
        try { md.getString(key)?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    private fun durationOf(md: MediaMetadata?): Long =
        try { md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L } catch (_: Exception) { 0L }

    private fun positionOf(st: PlaybackState?, duration: Long): Long {
        var pos = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return if (duration > 0) pos.coerceIn(0L, duration) else pos.coerceAtLeast(0L)
    }

    private fun view(app: Context, t: Tracked): SessionView {
        val c = t.controller
        val md = try { c.metadata } catch (_: Exception) { null }
        val st = try { c.playbackState } catch (_: Exception) { null }
        val pkg = c.packageName
        val duration = durationOf(md)
        val mediaId = md?.let { text(it, MediaMetadata.METADATA_KEY_MEDIA_ID) }?.take(HistoryCodec.ID_MAX)
        val title = titleOf(md)
        val remote = try { c.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE } catch (_: Exception) { false }
        val kind = MusicKind.classify(
            pkg = pkg,
            mediaId = mediaId,
            durationMs = duration,
            genre = md?.let { text(it, MediaMetadata.METADATA_KEY_GENRE) },
            content = hintFor(t.id, title),
            category = categoryOf(app, pkg)
        )
        return SessionView(
            id = t.id, pkg = pkg, kind = kind, state = PState.of(st?.state), actions = st?.actions ?: 0L,
            title = title, artist = md?.let { artistOf(it) }, mediaId = mediaId, durationMs = duration,
            positionMs = positionOf(st, duration), speed = st?.playbackSpeed ?: 0f, remote = remote
        )
    }

    private fun toNow(app: Context, t: Tracked, v: SessionView): Now? {
        val c = t.controller
        val md = try { c.metadata } catch (_: Exception) { null } ?: return null
        val title = titleOf(md) ?: return null
        val art = try {
            md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) { null }
        return Now(
            Track(
                title = title, artist = artistOf(md), album = text(md, MediaMetadata.METADATA_KEY_ALBUM), art = art,
                app = appLabel(app, v.pkg), playing = v.state.activeish, positionMs = v.positionMs, durationMs = v.durationMs,
                kind = v.kind, id = v.id, pkg = v.pkg
            ),
            pkg = v.pkg,
            music = v.kind == MediaKind.MUSIC
        )
    }

    internal fun appLabel(app: Context, pkg: String): String = labels.getOrPut(pkg) {
        MusicKind.MUSIC_APPS[pkg] ?: try {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun categoryOf(app: Context, pkg: String): AppCategory = categories.getOrPut(pkg) {
        try {
            when (app.packageManager.getApplicationInfo(pkg, 0).category) {
                ApplicationInfo.CATEGORY_AUDIO -> AppCategory.AUDIO
                ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
                else -> AppCategory.OTHER
            }
        } catch (_: Exception) {
            AppCategory.OTHER
        }
    }

    private var lastPlayerSaved: String? = null

    private fun rememberPlayer(app: Context, pkg: String) {
        if (pkg == lastPlayerSaved) return
        lastPlayerSaved = pkg
        scope.launch { try { withContext(Dispatchers.IO) { MusicStats.setLastMusicPkg(app, pkg) } } catch (_: Exception) { } }
    }

    // ───────────────────────────── Playerele audio (fără acces; felul conținutului cât cântă) ─────────────────────────────

    private var configsOn = false
    /**
     * Câte videouri FORJA sunt pe ecran (demonstrațiile din sesiunea live). Playerul lor e mut și fără tip de conținut;
     * pe Android 14 și mai vechi, sistemul poate să-l arate totuși printre playerele active — atunci un player fără tip
     * nu se ia drept muzică (fără acces, singurul semn e tipul de conținut).
     */
    private var ownVideos = 0
    private val configCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = onConfigs(configs.orEmpty())
    }
    private val configSeenAt = HashMap<Int, Long>()
    /** Felul conținutului atribuit unei sesiuni (id → (titlu, tip)), din playerul apărut odată cu ea. */
    private val hints = HashMap<String, Pair<String?, ContentHint>>()

    private fun startConfigs(app: Context) {
        if (configsOn) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        try {
            am.registerAudioPlaybackCallback(configCallback, main)
            configsOn = true
            onConfigs(am.activePlaybackConfigurations.orEmpty())
        } catch (_: Exception) { }
    }

    private fun onConfigs(list: List<AudioPlaybackConfiguration>) {
        val views = list.mapNotNull { c ->
            val a = try { c.audioAttributes } catch (_: Exception) { null } ?: return@mapNotNull null
            if (a.usage != AudioAttributes.USAGE_MEDIA) return@mapNotNull null
            val hint = when (a.contentType) {
                AudioAttributes.CONTENT_TYPE_MUSIC -> ContentHint.MUSIC
                AudioAttributes.CONTENT_TYPE_SPEECH -> ContentHint.SPEECH
                AudioAttributes.CONTENT_TYPE_MOVIE -> ContentHint.MOVIE
                else -> ContentHint.NONE
            }
            if (hint == ContentHint.NONE && ownVideos > 0 && Build.VERSION.SDK_INT < 35) return@mapNotNull null
            ConfigView(c.hashCode(), hint)
        }
        val now = SystemClock.elapsedRealtime()
        val ids = views.map { it.id }.toSet()
        configSeenAt.keys.retainAll(ids)
        for (v in views) configSeenAt.getOrPut(v.id) { now }
        _configs.value = views
        _audible.value = views.isNotEmpty()
        if (tracked.isNotEmpty()) recompute() else MusicStarter.onMediaChanged()
    }

    /** Un video FORJA (demonstrația din sesiunea live) a apărut / a dispărut de pe ecran. */
    internal fun ownVideo(context: Context, on: Boolean) {
        val app = context.applicationContext
        onMain {
            ownVideos = (ownVideos + if (on) 1 else -1).coerceAtLeast(0)
            if (!configsOn) return@onMain
            try { app.getSystemService(AudioManager::class.java)?.let { onConfigs(it.activePlaybackConfigurations.orEmpty()) } } catch (_: Exception) { }
        }
    }

    /**
     * Leagă tipul de conținut de o sesiune: un singur player audio nou și o singură sesiune pornită în aceeași
     * secundă și jumătate → sunt același lucru. Altfel nu se ghicește.
     */
    private fun correlate() {
        val now = SystemClock.elapsedRealtime()
        val freshConfigs = _configs.value.filter { now - (configSeenAt[it.id] ?: 0L) <= 1_500L && it.content != ContentHint.NONE }
        val freshSessions = playingSince.filterValues { now - it <= 1_500L }.keys
        if (freshConfigs.size == 1 && freshSessions.size == 1) {
            val id = freshSessions.first()
            val t = tracked.values.firstOrNull { it.id == id } ?: return
            val title = try { titleOf(t.controller.metadata) } catch (_: Exception) { null }
            hints[id] = title to freshConfigs[0].content
        }
        hints.keys.retainAll(tracked.values.map { it.id }.toSet())
    }

    private fun hintFor(id: String, title: String?): ContentHint {
        val h = hints[id] ?: return ContentHint.NONE
        return if (h.first == title) h.second else ContentHint.NONE
    }

    // ───────────────────────────── Ascultări (≥ 30 s = o ascultare; 3–30 s = sărită) ─────────────────────────────

    private var countKey: String? = null
    private var countAccumMs = 0L
    private var countSince = 0L
    private var countDone = false
    private var countLastPos = 0L
    private var countJob: Job? = null
    private var countRow: PlayRow? = null

    private fun countPlay(now: Now?, v: SessionView?) {
        val t = now?.track
        val key = if (now != null && now.music && t != null && v != null) TrackKey.of(t.title, t.artist) + "\u0001" + v.pkg else null
        val clock = SystemClock.elapsedRealtime()
        // Aceeași melodie luată de la capăt (repetare) = o ascultare nouă.
        val restarted = key != null && key == countKey && t != null && t.positionMs < 3_000 && countLastPos > PLAY_COUNT_MS
        if (key != countKey || restarted) {
            countJob?.cancel()
            // Săritura: piesa de dinainte a cântat 3–30 s și avea peste un minut.
            val prev = countRow
            val heard = countAccumMs + if (countSince != 0L) clock - countSince else 0L
            val app = appCtx
            // Doar când a urmat altă piesă (nu când playerul s-a oprit sau a dispărut).
            if (prev != null && key != null && !countDone && heard in SKIP_MIN_MS until PLAY_COUNT_MS && prev.durS > 60 && app != null) {
                val skip = prev.copy(at = System.currentTimeMillis(), event = PlayEvent.SKIP, src = sourceOf(prev))
                scope.launch { try { withContext(Dispatchers.IO) { MusicStats.record(app, skip) } } catch (_: Exception) { } }
            }
            countKey = key
            countAccumMs = 0L
            countSince = 0L
            countDone = false
            countRow = if (key != null && t != null && v != null) PlayRow(
                at = 0L, title = t.title, artist = t.artist, pkg = v.pkg, kind = v.kind, mediaId = v.mediaId,
                uri = uriOf(v.id), durS = (v.durationMs / 1000).toInt()
            ) else null
        }
        if (t != null) countLastPos = t.positionMs
        val playing = key != null && v?.state == PState.PLAYING
        if (playing && countSince == 0L) {
            countSince = clock
            if (!countDone) {
                val wait = (PLAY_COUNT_MS - countAccumMs).coerceAtLeast(0L)
                val app = appCtx
                countJob?.cancel()
                countJob = scope.launch {
                    delay(wait)
                    val row = countRow
                    if (countKey == key && countSince != 0L && !countDone && app != null && row != null) {
                        countDone = true
                        try {
                            // Sursa se decide acum, nu la prima apariție a piesei: prima piesă din lista FORJA apare
                            // înainte ca pornirea să fie confirmată (și coada să existe) — tot a FORJA e.
                            val done = row.copy(at = System.currentTimeMillis(), src = sourceOf(row))
                            withContext(Dispatchers.IO) { MusicStats.record(app, done) }
                        } catch (_: Exception) { }
                        MusicCloud.maybeUpload(app)
                    }
                }
            }
        } else if (!playing && countSince != 0L) {
            countAccumMs += clock - countSince
            countSince = 0L
            countJob?.cancel()
        }
    }

    /** Ascultarea e a listei FORJA (nu hrănește lista) sau a ei. */
    private fun sourceOf(row: PlayRow): PlaySource =
        if (row.pkg != null && MusicStarter.isForjaTrack(row.pkg, row.title, row.artist, row.mediaId)) PlaySource.FORJA else PlaySource.USER

    private fun uriOf(id: String): String? {
        val t = tracked.values.firstOrNull { it.id == id } ?: return null
        val md = try { t.controller.metadata } catch (_: Exception) { null } ?: return null
        return text(md, MediaMetadata.METADATA_KEY_MEDIA_URI)?.take(HistoryCodec.ID_MAX)
    }

    // ───────────────────────────── Ajutoare ─────────────────────────────

    /** Cântă ceva acum? Sesiunea (cu acces) sau un player media activ al sistemului (fără acces). */
    internal fun isPlayingNow(context: Context): Boolean {
        if (_now.value?.playing == true) return true
        if (hasAccess(context)) return _sessions.value.any { it.state == PState.PLAYING && it.kind == MediaKind.MUSIC }
        return _audible.value
    }

    /** Tasta media, trimisă ca de la căști: Android o dă sesiunii tastei media sau ultimului player folosit. */
    internal fun mediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Exception) { }
    }

    internal fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    internal val handler: Handler get() = main
}
