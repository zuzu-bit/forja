package com.forja.app.core.data

import android.content.Context
import com.forja.app.ForjaApp
import com.forja.app.core.focus.MindDocs
import com.forja.app.core.music.MediaKind
import com.forja.app.core.music.MusicKind
import com.forja.app.core.music.MusicStats
import com.forja.app.core.music.PlayEvent
import com.forja.app.core.music.PlaySource
import com.forja.app.core.notify.NudgeStore
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site (mirror D): users/{uid}/listens/{YYYY-MM-DD} (piesele zilei în ordine, cel mult 300: titlu, artist,
 * aplicația, durata, cine a pornit-o, sărită sau nu) și nudges/{YYYY-MM-DD} (mesajele Căștii și ce ai făcut cu ele;
 * cele private doar ca fel, fără text). Pleacă doar cu contractul v4 (Prefs.contractAtLeast(4)); revocarea le șterge prin
 * [SiteMirror.forget], pentru că numele sunt deja în [SiteMirror.MIRRORED]. Se rescriu doar zilele schimbate.
 */
object ListenMirror {
    /** Numele din [SiteMirror.MIRRORED] pe care le scrie oglinda aceasta. */
    val PATHS: List<String> = listOf("listens", "nudges")

    /** Ascultările se adună: o trecere la cel mult un minut după ultima piesă. */
    private const val LISTEN_SETTLE_MS = 60_000L
    private const val NUDGE_SETTLE_MS = 10_000L
    private const val DAY_MS = 86_400_000L

    private val started = AtomicBoolean(false)
    private val lock = Mutex()

    private data class Key(val uid: String, val signedAt: Long)

    /** Pornește ascultătorii o singură dată pe proces. */
    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        val who: Flow<Key?> = combine(authUid(), app.prefs.contractAtLeast(4), app.prefs.contractSignedAt) { uid, v4, at ->
            if (uid != null && v4) Key(uid, at) else null
        }.distinctUntilChanged()

        app.appScope.launch {
            try {
                combine(who, MusicStats.rowsFlow(app)) { k, rows -> k?.let { it to rows } }.collectLatest { pair ->
                    if (pair == null) return@collectLatest
                    delay(LISTEN_SETTLE_MS)
                    withContext(NonCancellable) { listensPass(app, pair.first, pair.second) }
                }
            } catch (_: Exception) { }
        }
        app.appScope.launch {
            try {
                combine(who, NudgeStore.logChanges(app)) { k, _ -> k }.collectLatest { k ->
                    if (k == null) return@collectLatest
                    delay(NUDGE_SETTLE_MS)
                    withContext(NonCancellable) { nudgesPass(app, k) }
                }
            } catch (_: Exception) { }
        }
    }

    private suspend fun listensPass(app: ForjaApp, k: Key, rows: List<com.forja.app.core.music.PlayRow>) = lock.withLock {
        try {
            if (MindOwner.claim(app, k.uid)) return@withLock // urmele altui cont, golite: fluxul aduce o trecere nouă
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val days = MindDocs.recentDays(now, zone).take(7)
            val since = now - 8 * DAY_MS
            val names = HashMap<String, String?>()
            val byDay = rows.filter { it.at >= since }.groupBy { MindDocs.dayKey(it.at, zone) }
            for (date in days) {
                val list = byDay[date] ?: continue
                val items = list.map { r ->
                    MindDocs.Listen(
                        at = r.at, title = r.title, artist = r.artist,
                        app = r.pkg?.let { p -> names.getOrPut(p) { appName(app, p) } },
                        durS = r.durS, forja = r.src == PlaySource.FORJA, skip = r.event == PlayEvent.SKIP,
                        kind = when (r.kind) { MediaKind.MUSIC -> "music"; MediaKind.SPOKEN -> "talk"; MediaKind.VIDEO -> "video"; else -> null }
                    )
                }
                FocusMirror.writeIfChanged(app, "${k.uid}:${k.signedAt}:listens:$date", k.uid, "listens", date, MindDocs.listensDoc(date, items, now))
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun nudgesPass(app: ForjaApp, k: Key) = lock.withLock {
        try {
            if (MindOwner.claim(app, k.uid)) return@withLock // urmele altui cont, golite: fluxul aduce o trecere nouă
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val byDay = NudgeStore.log(app).groupBy { MindDocs.dayKey(it.at, zone) }
            for (date in MindDocs.recentDays(now, zone).take(7)) {
                val list = byDay[date] ?: continue
                val items = list.map { MindDocs.NudgeItem(it.at, it.id, it.ctx, it.channel, it.title, it.body, it.private, it.outcome) }
                FocusMirror.writeIfChanged(app, "${k.uid}:${k.signedAt}:nudges:$date", k.uid, "nudges", date, MindDocs.nudgesDoc(date, items, now))
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private fun appName(c: Context, pkg: String): String? = MusicKind.MUSIC_APPS[pkg] ?: try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString().take(40)
    } catch (_: Exception) { null }

    private fun authUid(): Flow<String?> = callbackFlow {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()
}
