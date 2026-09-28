package com.forja.app.core.music

import android.content.Context
import com.forja.app.ForjaApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * „Ce asculți”, pe hartă (ca în Bump): users/{uid}.nowPlaying = {title, artist, app, at} cât cântă o aplicație de muzică.
 *
 * Reguli: cel mult o scriere la 30 s; se scrie doar la o melodie nouă sau o dată la 5 min (ca prietenii s-o vadă
 * proaspătă, < 10 min); după 5 min de pauză câmpul se șterge. Niciodată în modul fantomă (dacă fantoma pornește, câmpul
 * se șterge pe loc), doar cu cont, cu contractul de securitate semnat (Prefs.contractSigned) și cu „Pe hartă” pornit.
 */
internal object MusicPresence {
    private const val MIN_GAP_MS = 30_000L
    private const val REFRESH_MS = 5 * 60_000L
    private const val CLEAR_AFTER_MS = 5 * 60_000L
    private const val TICK_MS = 15_000L
    private const val TICK_IDLE_MS = 60_000L

    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    fun ensureStarted(context: Context) {
        if (started) return
        val app = context.applicationContext as? ForjaApp ?: return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { run(app) }
    }

    private suspend fun run(app: ForjaApp) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        scope.launch { Music.session.collect { wake.trySend(Unit) } }
        scope.launch { MusicStats.shareOnMapFlow(app).collect { wake.trySend(Unit) } }

        var lastKey: String? = null
        var lastWrite = 0L
        var publishedUid: String? = null
        var pausedSince = 0L

        while (true) {
            val now = System.currentTimeMillis()
            val s = Music.session.value
            val uid = try { app.auth.currentUid } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val ghostUntil = try { app.prefs.ghostUntilLocal.first() } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L }
            val ghost = ghostUntil == -1L || ghostUntil > now
            val signed = try { app.prefs.contractSigned.first() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val share = try { MusicStats.shareOnMap(app) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val allowed = uid != null && signed && !ghost && share
            val playing = s != null && s.music && s.track.playing
            var pending = false

            if (publishedUid != null && (!allowed || publishedUid != uid)) {
                // Fantomă, contract revocat, alt cont: nu mai rămâne nimic la vedere.
                clear(publishedUid)
                publishedUid = null
                lastKey = null
                pausedSince = 0L
            }
            if (allowed && playing) {
                pausedSince = 0L
                val t = s!!.track
                val key = t.title + "\u0001" + t.artist
                if (key != lastKey || now - lastWrite >= REFRESH_MS) {
                    if (now - lastWrite >= MIN_GAP_MS) {
                        write(uid!!, t, now)
                        lastKey = key
                        lastWrite = now
                        publishedUid = uid
                    } else {
                        pending = true
                    }
                }
            } else if (publishedUid != null) {
                if (pausedSince == 0L) pausedSince = now
                if (now - pausedSince >= CLEAR_AFTER_MS) {
                    clear(publishedUid)
                    publishedUid = null
                    lastKey = null
                    pausedSince = 0L
                }
            }

            val waitMs = when {
                pending -> (lastWrite + MIN_GAP_MS - now).coerceIn(1_000L, TICK_MS)
                publishedUid != null -> TICK_MS
                playing -> if (allowed) TICK_MS else TICK_IDLE_MS   // fantoma cu termen expiră singură
                else -> 0L
            }
            if (waitMs > 0L) withTimeoutOrNull(waitMs) { wake.receive() } else wake.receive()
        }
    }

    private fun write(uid: String, t: Track, at: Long) {
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid).set(
                mapOf(
                    "nowPlaying" to mapOf(
                        "title" to t.title.take(120),
                        "artist" to t.artist.take(120),
                        "app" to t.app.take(40),
                        "at" to at
                    )
                ),
                SetOptions.merge()
            )
        } catch (_: Exception) { }
    }

    private fun clear(uid: String) {
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .set(mapOf("nowPlaying" to FieldValue.delete()), SetOptions.merge())
        } catch (_: Exception) { }
    }
}
