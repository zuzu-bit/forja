package com.forja.app.core.data

import com.forja.app.ForjaApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site (pachetul C): users/{uid}/settings/storage — câte poze, video-uri și documente ai pe telefon și cât
 * ocupă (fără nume de fișiere), plus cât a urcat oglinda și ce așteaptă (Wi-Fi, loc, permisiune). O scrie
 * [com.forja.app.core.sync.MediaMirror] la sfârșitul fiecărei treceri, cel mult o dată la 10 minute (sau la o stare nouă).
 * Pleacă doar cu contractul v4 (Prefs.contractAtLeast(4)); revocarea o șterge prin [SiteMirror.forget] (e în MIRRORED).
 */
object StorageMirror {
    /** Numele din [SiteMirror.MIRRORED] pe care le scrie oglinda aceasta. */
    val PATHS: List<String> = listOf("settings/storage")
    private const val MIN_GAP_MS = 10 * 60_000L

    private val started = AtomicBoolean(false)
    @Volatile private var last: Pair<Long, String> = 0L to ""

    /** Nimic de ascultat: documentul îl scrie trecerea oglinzii. Păstrat pentru cusătura din [SiteMirror.start]. */
    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
    }

    suspend fun publish(app: ForjaApp, uid: String, doc: Map<String, Any?>) {
        try {
            if (!app.prefs.contractAtLeast(4).first()) return
            val state = ((doc["gallery"] as? Map<*, *>)?.get("state") as? String).orEmpty()
            val now = System.currentTimeMillis()
            if (state == last.second && now - last.first < MIN_GAP_MS) return
            last = now to state
            FirebaseFirestore.getInstance().collection("users").document(uid).collection("settings").document("storage").set(doc)
        } catch (_: Exception) { }
    }
}
