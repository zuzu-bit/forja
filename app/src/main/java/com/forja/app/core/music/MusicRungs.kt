package com.forja.app.core.music

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent

/**
 * O funcție per treaptă (music-start.md §6.2): trimite comanda, tasta sau intentul și spune doar dacă s-a trimis.
 * Efectul (cântă sau nu) îl verifică [StartMachine]. Toate pe firul principal.
 */
internal object MusicRungs {
    private const val SPOTIFY = MusicKind.SPOTIFY
    private const val LIKED = "spotify:collection:tracks"
    private const val FOCUS_ANY = "vnd.android.cursor.item/*"

    /**
     * Codul cererii pentru saltul „pentru rezultat” (RET_SUB): sub 0x10000, ca să nu se ciocnească de codurile
     * aleatorii ale ActivityResultRegistry (de la 0x10000 în sus). Cu el FORJA închide ecranul playerului.
     */
    const val REQ_HOP = 0x4F4A

    /** Nota unui salt deschis pentru rezultat din activitatea FORJA (se vede în jurnal). */
    const val SUB = "sub"

    /**
     * [host] = activitatea FORJA vie (MainActivity): un salt intermediar (V_*) pleacă din ea, pentru rezultat și fără
     * task nou, ca FORJA să-l poată închide după ce muzica e confirmată. Fără ea (sau pentru pașii finali): ca până acum,
     * din contextul aplicației, cu task nou.
     */
    fun send(ctx: Context, step: Step, keyToken: MediaSession.Token?, host: Activity? = null): SendResult {
        return try {
            sendUnsafe(ctx, step, keyToken, host)
        } catch (e: SecurityException) {
            SendResult.Error("SecurityException")
        } catch (e: Exception) {
            SendResult.Error(e.javaClass.simpleName)
        }
    }

    private fun sendUnsafe(ctx: Context, step: Step, keyToken: MediaSession.Token?, host: Activity?): SendResult {
        return when (step.rung) {
            Rung.ALREADY -> SendResult.Skipped("not-a-rung")
            Rung.S_PLAY -> withSession(step) { it.transportControls.play(); SendResult.Sent() }
            Rung.S_BTN -> withSession(step) { c ->
                val t = SystemClock.uptimeMillis()
                val down = c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                if (down) SendResult.Sent() else SendResult.Skipped("button-not-handled")
            }
            Rung.S_TOP -> withSession(step) { c ->
                val track = step.track ?: return@withSession SendResult.Skipped("no-track")
                playTrack(c, track)
            }
            Rung.S_LIKED -> withSession(step) { it.transportControls.playFromUri(Uri.parse(LIKED), Bundle()); SendResult.Sent() }
            Rung.S_ANY -> withSession(step) { it.transportControls.playFromSearch("", Bundle()); SendResult.Sent() }
            Rung.K_TOKEN -> {
                if (keyToken == null) SendResult.Skipped("no-token")
                else {
                    MediaController(ctx, keyToken).transportControls.play()
                    SendResult.Sent()
                }
            }
            Rung.K_PLAY -> {
                Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
                SendResult.Sent()
            }
            Rung.V_TRACK -> {
                val id = MusicKind.spotifyTrackId(step.track?.mediaId) ?: return SendResult.Skipped("no-track-id")
                hop(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:track:$id")).setPackage(SPOTIFY), host)
            }
            Rung.V_PFS_TOP -> {
                val t = step.track ?: return SendResult.Skipped("no-track")
                val pkg = step.pkg ?: return SendResult.Skipped("no-player")
                hop(ctx, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtras(searchExtras(t)), host)
            }
            Rung.V_LIKED_PLAY -> hop(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("$LIKED:play")).setPackage(SPOTIFY), host)
            Rung.V_PFS_DATA -> hop(
                ctx,
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, Uri.parse(LIKED)).setPackage(SPOTIFY).putExtra(SearchManager.QUERY, ""),
                host
            )
            Rung.V_PFS_ANY -> {
                val pkg = step.pkg ?: return SendResult.Skipped("no-player")
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ANY)
                    .putExtra(SearchManager.QUERY, "")
                if (!resolves(ctx, i)) SendResult.Skipped("no-search-activity") else hop(ctx, i, host)
            }
            Rung.O_SESSION -> {
                val c = Music.controller(step.sessionId)
                if (c != null && openSession(ctx, c)) SendResult.Sent()
                else if (step.pkg != null && launch(ctx, step.pkg)) SendResult.Sent("launcher")
                else SendResult.Skipped("no-activity")
            }
            // Pagina finală rămâne cu task nou: ea a venit aici să apese play, FORJA nu i-o ia de sub deget.
            Rung.O_LIKED_PAGE -> visible(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(LIKED)).setPackage(SPOTIFY))
                .let { if (it is SendResult.Sent) it else if (launch(ctx, SPOTIFY)) SendResult.Sent("launcher") else it }
            // Doar un player anume: fără pachet nu se mai deschide selectorul de muzică al sistemului (pe S23 nu răspundea).
            Rung.O_LAUNCH -> {
                val pkg = step.pkg ?: return SendResult.Skipped("no-player")
                if (launch(ctx, pkg)) SendResult.Sent() else SendResult.Skipped("no-activity")
            }
        }
    }

    private inline fun withSession(step: Step, block: (MediaController) -> SendResult): SendResult {
        val c = Music.controller(step.sessionId) ?: return SendResult.Skipped("no-session")
        return block(c)
    }

    /**
     * Piesa cerută, pe sesiunea playerului: după ID media (dacă playerul îl acceptă), altfel după URI, altfel căutare
     * „titlu artist” cu titlul și artistul separat. Un ID luat din alt player nu se trimite niciodată (doar căutarea).
     * Nota spune calea (se vede în jurnal).
     */
    fun playTrack(c: MediaController, track: TrackRef): SendResult {
        val t = track.forPlayer(try { c.packageName } catch (_: Exception) { null })
        val actions = try { c.playbackState?.actions ?: 0L } catch (_: Exception) { 0L }
        fun can(bit: Long) = actions == 0L || (actions and bit) != 0L
        val tc = c.transportControls
        val uri = t.uri ?: t.mediaId?.takeIf { it.startsWith("spotify:") }
        return when {
            t.mediaId != null && can(SessionView.ACTION_PLAY_FROM_MEDIA_ID) -> {
                tc.playFromMediaId(t.mediaId, Bundle())
                SendResult.Sent("via:id")
            }
            uri != null && can(SessionView.ACTION_PLAY_FROM_URI) -> {
                tc.playFromUri(Uri.parse(uri), Bundle())
                SendResult.Sent("via:uri")
            }
            can(SessionView.ACTION_PLAY_FROM_SEARCH) -> {
                tc.playFromSearch(t.query, searchExtras(t))
                SendResult.Sent("via:search")
            }
            else -> SendResult.Skipped("no-from-actions:0x${actions.toString(16)}")
        }
    }

    private fun searchExtras(t: TrackRef) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, t.title)
        if (t.artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, t.artist)
        putString(SearchManager.QUERY, t.query)
    }

    /** Oprește ce a pornit greșit (o carte trezită de tastă); fără acces, tasta PAUSE. */
    fun undo(ctx: Context, target: UndoTarget) {
        try {
            when (target) {
                is UndoTarget.Session -> Music.controller(target.id)?.transportControls?.pause()
                UndoTarget.Key -> Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        } catch (_: Exception) { }
    }

    // ───────────────────────────── Deschideri ─────────────────────────────

    private fun visible(ctx: Context, intent: Intent): SendResult = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SendResult.Sent()
    } catch (e: android.content.ActivityNotFoundException) {
        SendResult.Skipped("no-activity")
    }

    /**
     * Saltul intermediar (RET_SUB): din activitatea FORJA vie, pentru rezultat ([REQ_HOP]) și FĂRĂ task nou — un ecran
     * obișnuit al playerului se deschide în taskul FORJA, deasupra sesiunii live, iar FORJA îl poate închide
     * (finishActivity). Un ecran `singleTask` se deschide în taskul lui și Android anulează rezultatul imediat: atunci
     * ea revine cu Înapoi, ca înainte. Fără activitate vie: ca până acum, cu task nou din contextul aplicației.
     */
    private fun hop(ctx: Context, intent: Intent, host: Activity?): SendResult {
        if (host == null || host.isFinishing || host.isDestroyed) return visible(ctx, intent)
        return try {
            host.startActivityForResult(intent, REQ_HOP)
            SendResult.Sent(SUB)
        } catch (e: android.content.ActivityNotFoundException) {
            SendResult.Skipped("no-activity")
        }
    }

    /** Activitatea playerului care ține sesiunea (PendingIntent-ul sesiunii), cu voie explicită de pornire (Android 14+). */
    private fun openSession(ctx: Context, c: MediaController): Boolean {
        val pi = try { c.sessionActivity } catch (_: Exception) { null } ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(opts.toBundle())
            } else {
                pi.send()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = try { ctx.packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null } ?: return false
        return visible(ctx, i) is SendResult.Sent
    }

    // ───────────────────────────── Ce e pe telefon ─────────────────────────────

    /**
     * Cele două întrebări separate către PackageManager despre un pachet (29.09: prima a răspuns „nu” pentru Spotify,
     * deși a doua îi citea versiunea): intrarea de lansare și informațiile pachetului (cu versiunea, dacă o are).
     */
    data class Probe(val launch: Boolean, val info: Boolean, val version: String? = null) {
        val any: Boolean get() = launch || info
    }

    fun probe(ctx: Context, pkg: String): Probe {
        val launch = launchable(ctx, pkg)
        val info = packageInfo(ctx, pkg)
        return Probe(launch, info != null, info?.versionName)
    }

    /** Instalat = oricare dintre cele două întrebări spune „da” (a doua se pune doar dacă prima zice „nu”). */
    fun installed(ctx: Context, pkg: String): Boolean = launchable(ctx, pkg) || packageInfo(ctx, pkg) != null

    private fun launchable(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

    private fun packageInfo(ctx: Context, pkg: String): PackageInfo? = try {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
    } catch (_: Exception) {
        null
    }

    fun searchable(ctx: Context, pkg: String): Boolean =
        resolves(ctx, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg))

    private fun resolves(ctx: Context, i: Intent): Boolean = try {
        val pm = ctx.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(i, 0)
        list.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    fun version(ctx: Context, pkg: String): String? = packageInfo(ctx, pkg)?.versionName

    /** Aplicația de muzică implicită a telefonului (CATEGORY_APP_MUSIC), dacă răspunde vreuna. */
    fun defaultMusicApp(ctx: Context): String? = try {
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        val pm = ctx.packageManager
        val r = if (Build.VERSION.SDK_INT >= 33) pm.resolveActivity(i, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.resolveActivity(i, 0)
        r?.activityInfo?.packageName?.takeIf { it != "android" }
    } catch (_: Exception) {
        null
    }
}
