package com.forja.app.core.data

import com.forja.app.ForjaApp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site (mirror P0 — doar cusătura; o umple pachetul D): users/{uid}/focus/{YYYY-MM-DD} (sesiunile de concentrare, arborii, aplicațiile blocate și încercările), detox/{YYYY-MM-DD} (interceptările pe pachete, niciodată textul; cuvintele și scrisoarea doar cu Prefs.detoxWordsOnSite) și breath/{YYYY-MM-DD}.
 * Pleacă doar cu contractul v4 (Prefs.contractAtLeast(4)); revocarea le șterge prin [SiteMirror.forget], pentru că
 * numele sunt deja în [SiteMirror.MIRRORED]. Pornită din [SiteMirror.start], o dată pe proces.
 */
object FocusMirror {
    /** Numele din [SiteMirror.MIRRORED] pe care le scrie oglinda aceasta. */
    val PATHS: List<String> = listOf("focus", "detox", "breath")

    private val started = AtomicBoolean(false)

    /** Pornește ascultătorii o singură dată pe proces. Deocamdată nu face nimic. */
    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        // Pachetul care o umple pornește aici ascultătorii (app.appScope, contractAtLeast(4), uid-ul conectat).
    }
}
