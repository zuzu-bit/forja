package com.forja.app.core.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Melodia care cântă acum, așa cum o anunță sesiunea media a playerului (Spotify, YouTube Music, orice player):
 * titlu, artist, album, copertă, aplicația, starea și poziția. Nimic din notificări.
 */
data class Track(
    val title: String,
    val artist: String,
    val album: String?,
    val art: Bitmap?,
    val app: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * Muzica ta, din FORJA: ce cântă acum ([nowPlaying]), pornire/pauză/înainte/înapoi, „Melodii apreciate”, pauza de la
 * finalul inventarului („Când tace muzica, inventarul e gata.”) și ascultările tale (local).
 *
 * Sursa: sesiunile media active (MediaSessionManager.getActiveSessions), pe care Android le arată doar unui serviciu de
 * notificări aprobat de om — [MusicListenerService]. FORJA NU citește notificările: serviciul există doar ca permis.
 * Fără acces, comenzile merg tot, prin tastele media ale sistemului (AudioManager), doar melodia nu se vede.
 */
object Music {
    private const val SPOTIFY = "com.spotify.music"
    private const val PLAY_CHECK_MS = 2_000L
    /** O ascultare = cel puțin 30 s de melodie cântată. */
    private const val PLAY_COUNT_MS = 30_000L

    /** Playerele cunoscute: numele afișat, fără să întrebăm sistemul. */
    private val KNOWN = mapOf(
        SPOTIFY to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.apple.android.music" to "Apple Music",
        "deezer.android.app" to "Deezer",
        "com.aspiro.tidal" to "Tidal",
        "com.soundcloud.android" to "SoundCloud",
        "com.amazon.mp3" to "Amazon Music",
        "com.sec.android.app.music" to "Samsung Music",
        "com.maxmpz.audioplayer" to "Poweramp",
        "in.krosbits.musicolet" to "Musicolet",
        "com.pandora.android" to "Pandora",
        "com.audiomack" to "Audiomack",
        "com.anghami" to "Anghami",
        "com.qobuz.music" to "Qobuz",
        "com.miui.player" to "Mi Music",
        "com.google.android.music" to "Play Music"
    )

    private val _now = MutableStateFlow<Track?>(null)
    /** Ce cântă acum (sesiunea care cântă are prioritate); null fără acces sau când nu cântă nimic. */
    val nowPlaying: StateFlow<Track?> = _now.asStateFlow()

    /** Aceeași melodie, cu pachetul playerului și dacă e o aplicație de muzică (pentru hartă și ascultări). */
    internal class Now(val track: Track, val pkg: String, val music: Boolean)

    private val _session = MutableStateFlow<Now?>(null)
    internal val session: StateFlow<Now?> = _session.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    // Nimic din muzică nu are voie să oprească aplicația: erorile de fundal se înghit aici.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> })

    @Volatile private var appCtx: Context? = null
    private var msm: MediaSessionManager? = null
    private var listening = false
    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private var order: List<MediaSession.Token> = emptyList()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list.orEmpty()) }
    private var playCheck: Job? = null
    private val labels = HashMap<String, String>()
    private val musicApps = HashMap<String, Boolean>()

    /** Opțiunea „Oprește la final”: null = nesetată în acest proces (se citește din DataStore; implicit pornită). */
    @Volatile private var stopFlag: Boolean? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

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

    /**
     * Pornește muzica: sesiunea activă (ultimul player), altfel tasta media PLAY (Android o dă ultimului player folosit).
     * Dacă după 2 s nu cântă nimic, deschide „Melodii apreciate” ([openFavorites]).
     */
    fun play(context: Context) {
        val app = context.applicationContext
        ensureStarted(app)
        onMain {
            val c = activeController()
            val sent = c != null && try { c.transportControls.play(); true } catch (_: Exception) { false }
            if (!sent) mediaKey(app, KeyEvent.KEYCODE_MEDIA_PLAY)
            playCheck?.cancel()
            playCheck = scope.launch {
                delay(PLAY_CHECK_MS)
                if (!isPlayingNow(app)) openFavorites(app)
            }
        }
    }

    fun pause(context: Context) {
        onMain { playCheck?.cancel() }
        command(context, KeyEvent.KEYCODE_MEDIA_PAUSE) { it.pause() }
    }

    fun next(context: Context) = command(context, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }

    fun previous(context: Context) = command(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }

    /**
     * „Melodii apreciate”: Spotify instalat → colecția ta (spotify:collection:tracks), altfel Spotify simplu;
     * fără Spotify → aplicația de muzică implicită a telefonului. False dacă nu s-a deschis nimic.
     */
    fun openFavorites(context: Context): Boolean {
        val pm = context.packageManager
        val spotify = try { pm.getLaunchIntentForPackage(SPOTIFY) } catch (_: Exception) { null }
        if (spotify != null) {
            val liked = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:collection:tracks")).setPackage(SPOTIFY)
            if (start(context, liked)) return true
            if (start(context, spotify)) return true
        }
        return start(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
    }

    /** Pauza de la finalul inventarului (implicit pornită). Rămâne și după o repornire a procesului. */
    fun stopWhenDone(enabled: Boolean) {
        stopFlag = enabled
        val app = appCtx ?: return
        scope.launch {
            try { withContext(Dispatchers.IO) { MusicStats.setStopWhenDone(app, enabled) } } catch (_: Exception) { }
        }
    }

    /**
     * Inventarul s-a terminat: pauză (dacă „Oprește la final”), apoi sunetul FORJA „misiune îndeplinită” (arpegiu scurt,
     * sintetizat) și vibrația. Cu telefonul pe silențios sau vibrații: doar vibrația.
     */
    fun onInventoryDone(context: Context) {
        val app = context.applicationContext
        ensureStarted(app)
        scope.launch {
            val stop = stopFlag ?: try { withContext(Dispatchers.IO) { MusicStats.stopWhenDone(app) } } catch (_: Exception) { true }
            var paused = false
            if (stop && isPlayingNow(app)) {
                pause(app)
                paused = true
                delay(400)   // playerul coboară volumul; sunetul vine în liniște
            }
            MissionChime.play(app, duckMusic = !paused)
        }
    }

    /** Melodia ta cea mai ascultată în ultimele [days] zile: („Titlu · Artist”, ascultări), sau null. */
    suspend fun topTrack(context: Context, days: Int = 7): Pair<String, Int>? =
        try {
            MusicStats.top(context.applicationContext, days)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ───────────────────────────── Sesiunile media ─────────────────────────────

    /** Pornește urmărirea sesiunilor (idempotent) și publicarea pe hartă. Apelată de serviciu, card și comenzi. */
    internal fun ensureStarted(context: Context) {
        val app = context.applicationContext
        appCtx = app
        MusicPresence.ensureStarted(app)
        onMain { startSessions(app) }
    }

    internal fun onListenerConnected(context: Context) = ensureStarted(context)

    internal fun onListenerDisconnected(context: Context) {
        val app = context.applicationContext
        onMain { if (!hasAccess(app)) stopSessions() }
    }

    private fun startSessions(app: Context) {
        if (!hasAccess(app)) {
            if (listening || tracked.isNotEmpty()) stopSessions()
            return
        }
        val m = msm ?: app.getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
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
                tracked[token] = Tracked(c, cb)
            } catch (_: Exception) { }
        }
        // Ordinea sistemului = prioritatea (sesiunea cea mai recentă întâi).
        order = keep.map { it.sessionToken }.filter { tracked.containsKey(it) }
        recompute()
    }

    private fun controllers(): List<MediaController> = order.mapNotNull { tracked[it]?.controller }

    /** Ținta comenzilor: sesiunea care cântă, altfel ultima folosită. */
    private fun activeController(): MediaController? {
        val list = controllers()
        return list.firstOrNull { isPlaying(it.playbackState) } ?: list.firstOrNull()
    }

    private fun recompute() {
        val app = appCtx
        val list = controllers()
        val pick = list.firstOrNull { isPlaying(it.playbackState) && titleOf(it.metadata) != null }
            ?: list.firstOrNull { titleOf(it.metadata) != null }
        val now = if (app != null && pick != null) toNow(app, pick) else null
        _session.value = now
        _now.value = now?.track
        countPlay(now)
    }

    private fun titleOf(md: MediaMetadata?): String? {
        if (md == null) return null
        return text(md, MediaMetadata.METADATA_KEY_TITLE) ?: text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    }

    private fun text(md: MediaMetadata, key: String): String? =
        try { md.getString(key)?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    private fun toNow(app: Context, c: MediaController): Now? {
        val md = c.metadata ?: return null
        val title = titleOf(md) ?: return null
        val artist = listOf(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_AUTHOR
        ).firstNotNullOfOrNull { text(md, it) } ?: ""
        val album = text(md, MediaMetadata.METADATA_KEY_ALBUM)
        val art = try {
            md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) { null }
        val duration = try { md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L) } catch (_: Exception) { 0L }
        val st = c.playbackState
        val playing = isPlaying(st)
        var pos = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        pos = if (duration > 0) pos.coerceIn(0L, duration) else pos.coerceAtLeast(0L)
        val pkg = c.packageName
        return Now(
            Track(
                title = title, artist = artist, album = album, art = art, app = appLabel(app, pkg),
                playing = playing, positionMs = pos, durationMs = duration
            ),
            pkg = pkg,
            music = isMusicApp(app, pkg)
        )
    }

    private fun isPlaying(st: PlaybackState?): Boolean = when (st?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun appLabel(app: Context, pkg: String): String = labels.getOrPut(pkg) {
        KNOWN[pkg] ?: try {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Pe hartă și în ascultări intră doar aplicațiile de muzică (cele cunoscute sau cele care se declară „audio”):
     * un video din browser sau din YouTube nu e „ce asculți” și nu pleacă la prieteni.
     */
    private fun isMusicApp(app: Context, pkg: String): Boolean = musicApps.getOrPut(pkg) {
        pkg in KNOWN || try {
            app.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_AUDIO
        } catch (_: Exception) {
            false
        }
    }

    // ───────────────────────────── Ascultări (≥ 30 s = o ascultare) ─────────────────────────────

    private var countKey: String? = null
    private var countAccumMs = 0L
    private var countSince = 0L
    private var countDone = false
    private var countLastPos = 0L
    private var countJob: Job? = null

    private fun countPlay(now: Now?) {
        val t = now?.track
        val key = if (now != null && now.music && t != null) t.title + "\u0001" + t.artist else null
        val clock = SystemClock.elapsedRealtime()
        // Aceeași melodie luată de la capăt (repetare) = o ascultare nouă.
        val restarted = key != null && key == countKey && t != null && t.positionMs < 3_000 && countLastPos > PLAY_COUNT_MS
        if (key != countKey || restarted) {
            countJob?.cancel()
            countKey = key
            countAccumMs = 0L
            countSince = 0L
            countDone = false
        }
        if (t != null) countLastPos = t.positionMs
        val playing = key != null && t?.playing == true
        if (playing && countSince == 0L) {
            countSince = clock
            if (!countDone) {
                val wait = (PLAY_COUNT_MS - countAccumMs).coerceAtLeast(0L)
                val title = t!!.title
                val artist = t.artist
                val app = appCtx
                countJob?.cancel()
                countJob = scope.launch {
                    delay(wait)
                    if (countKey == key && countSince != 0L && !countDone && app != null) {
                        countDone = true
                        try {
                            withContext(Dispatchers.IO) { MusicStats.record(app, title, artist, System.currentTimeMillis()) }
                        } catch (_: Exception) { }
                    }
                }
            }
        } else if (!playing && countSince != 0L) {
            countAccumMs += clock - countSince
            countSince = 0L
            countJob?.cancel()
        }
    }

    // ───────────────────────────── Ajutoare ─────────────────────────────

    private fun command(context: Context, keyCode: Int, action: (MediaController.TransportControls) -> Unit) {
        val app = context.applicationContext
        ensureStarted(app)
        onMain {
            val c = activeController()
            val sent = c != null && try { action(c.transportControls); true } catch (_: Exception) { false }
            if (!sent) mediaKey(app, keyCode)
        }
    }

    /** Cântă ceva acum? Sesiunea (cu acces) sau fluxul audio de muzică al telefonului (fără acces). */
    internal fun isPlayingNow(context: Context): Boolean {
        if (_now.value?.playing == true) return true
        return try { context.getSystemService(AudioManager::class.java)?.isMusicActive == true } catch (_: Exception) { false }
    }

    /** Tasta media, trimisă ca de la căști: Android o dă sesiunii active sau ultimului player folosit. */
    private fun mediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Exception) { }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
