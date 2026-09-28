package com.forja.app.core.music

import android.content.Context
import com.forja.app.ForjaApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * „Ce asculți” pe site (DESIGN-4.4 §3.3): topul săptămânii în users/{uid}/settings/music — titlu, artist, de câte ori,
 * minute estimate, aplicația. Cel mult o dată la 6 ore, doar cu cont și cu contractul semnat (Prefs.contractSigned).
 * Ascultările rămân pe telefon; pleacă doar rezumatul.
 */
internal object MusicCloud {
    private const val EVERY_MS = 6L * 3600 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val lock = Mutex()

    fun maybeUpload(context: Context) {
        val app = context.applicationContext as? ForjaApp ?: return
        scope.launch {
            if (lock.isLocked) return@launch
            lock.withLock { upload(app) }
        }
    }

    private suspend fun upload(app: ForjaApp) {
        try {
            val now = System.currentTimeMillis()
            if (now - MusicStats.summaryAt(app) < EVERY_MS) return
            val uid = app.auth.currentUid ?: return
            if (!app.prefs.contractSigned.first()) return
            val summary = WeeklySummary.build(MusicStats.rows(app), now) { pkg -> pkg?.let { appName(app, it) } }
            val ok = withTimeoutOrNull(20_000L) {
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .collection("settings").document("music")
                    .set(summary.toMap(), SetOptions.merge()).await()
                true
            } ?: false
            if (ok) MusicStats.setSummaryAt(app, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    private fun appName(app: Context, pkg: String): String? = MusicKind.MUSIC_APPS[pkg] ?: try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().take(40)
    } catch (_: Exception) {
        null
    }
}
