package com.forja.app.core.music

import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Context
import android.content.Intent
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

    fun send(ctx: Context, step: Step, keyToken: MediaSession.Token?): SendResult {
        return try {
            sendUnsafe(ctx, step, keyToken)
        } catch (e: SecurityException) {
            SendResult.Error("SecurityException")
        } catch (e: Exception) {
            SendResult.Error(e.javaClass.simpleName)
        }
    }

    private fun sendUnsafe(ctx: Context, step: Step, keyToken: MediaSession.Token?): SendResult {
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
                visible(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:track:$id")).setPackage(SPOTIFY))
            }
            Rung.V_PFS_TOP -> {
                val t = step.track ?: return SendResult.Skipped("no-track")
                val pkg = step.pkg ?: return SendResult.Skipped("no-player")
                visible(ctx, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtras(searchExtras(t)))
            }
            Rung.V_LIKED_PLAY -> visible(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("$LIKED:play")).setPackage(SPOTIFY))
            Rung.V_PFS_DATA -> visible(
                ctx,
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, Uri.parse(LIKED)).setPackage(SPOTIFY).putExtra(SearchManager.QUERY, "")
            )
            Rung.V_PFS_ANY -> {
                val pkg = step.pkg ?: return SendResult.Skipped("no-player")
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ANY)
                    .putExtra(SearchManager.QUERY, "")
                if (!resolves(ctx, i)) SendResult.Skipped("no-search-activity") else visible(ctx, i)
            }
            Rung.O_SESSION -> {
                val c = Music.controller(step.sessionId)
                if (c != null && openSession(ctx, c)) SendResult.Sent()
                else if (step.pkg != null && launch(ctx, step.pkg)) SendResult.Sent("launcher")
                else SendResult.Skipped("no-activity")
            }
            Rung.O_LIKED_PAGE -> visible(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(LIKED)).setPackage(SPOTIFY))
                .let { if (it is SendResult.Sent) it else if (launch(ctx, SPOTIFY)) SendResult.Sent("launcher") else it }
            Rung.O_LAUNCH -> {
                val pkg = step.pkg
                if (pkg != null && launch(ctx, pkg)) SendResult.Sent()
                else visible(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
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

    fun installed(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

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

    fun version(ctx: Context, pkg: String): String? = try {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        info.versionName
    } catch (_: Exception) {
        null
    }

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
