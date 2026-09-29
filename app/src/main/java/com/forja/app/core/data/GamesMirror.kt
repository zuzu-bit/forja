package com.forja.app.core.data

import com.forja.app.ForjaApp
import com.forja.app.core.data.db.GamePlayEntity
import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.GameStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site (pachetul C): users/{uid}/games/{zid sau asalt} — nivelurile, stelele, recordurile, ultimele 50 de
 * jocuri și cât ai jucat (în total și azi), din progresul GameStore și jurnalul Room `game_plays`.
 * Pleacă doar cu contractul v4 (Prefs.contractAtLeast(4)); revocarea le șterge prin [SiteMirror.forget] („games” e în MIRRORED).
 * Pornită din [SiteMirror.start], o dată pe proces; o scriere la cel mult câteva secunde după un joc terminat.
 */
object GamesMirror {
    /** Numele din [SiteMirror.MIRRORED] pe care le scrie oglinda aceasta. */
    val PATHS: List<String> = listOf("games")
    private const val SETTLE_MS = 4_000L

    private val started = AtomicBoolean(false)

    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        app.appScope.launch {
            try {
                val uids = callbackFlow {
                    val auth = FirebaseAuth.getInstance()
                    val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
                    auth.addAuthStateListener(l)
                    awaitClose { auth.removeAuthStateListener(l) }
                }.distinctUntilChanged()
                combine(uids, app.prefs.contractAtLeast(4), app.db.gamePlayDao().since(0L)) { uid, v4, plays -> if (v4 && uid != null) uid to plays else null }
                    .collectLatest { pair ->
                        if (pair == null) return@collectLatest
                        delay(SETTLE_MS)
                        val (uid, plays) = pair
                        for (g in GameId.entries) {
                            val progress = try { GameStore.progressNow(app, g) } catch (_: Exception) { continue }
                            val mine = plays.filter { it.game == g.key && (it.ownerUid == null || it.ownerUid == uid) }
                            if (mine.isEmpty() && progress == GameProgress()) continue
                            try {
                                FirebaseFirestore.getInstance().collection("users").document(uid).collection("games").document(g.key)
                                    .set(doc(g, progress, mine, System.currentTimeMillis()))
                            } catch (_: Exception) { }
                        }
                    }
            } catch (_: Exception) { }
        }
    }

    /** Documentul unui joc (logică pură, testată în GamesMirrorTest). Cheile hărților sunt text, cum cere Firestore. */
    fun doc(g: GameId, p: GameProgress, plays: List<GamePlayEntity>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Map<String, Any?> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sorted = plays.sortedByDescending { it.at }
        return mapOf(
            "levels" to g.levels,
            "unlocked" to p.unlocked.coerceAtMost(g.levels),
            "cleared" to p.cleared,
            "starsTotal" to p.stars.values.sum(),
            "endlessBest" to if (g == GameId.Zid) p.endlessBest else null,
            "stars" to p.stars.filterValues { it > 0 }.mapKeys { it.key.toString() },
            "best" to p.best.mapKeys { it.key.toString() },
            "plays" to sorted.take(50).map { mapOf("at" to it.at, "level" to it.level, "outcome" to it.outcome, "stars" to it.stars, "score" to it.score, "durationS" to it.durationS) },
            "playedS" to plays.sumOf { it.durationS.toLong() },
            "playedToday" to plays.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }.sumOf { it.durationS.toLong() },
            "lastAt" to sorted.firstOrNull()?.at,
            "updatedAt" to now
        )
    }
}
