package com.forja.app.core.data

import com.forja.app.ForjaApp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Oglinda pentru site (mirror P0 — doar cusătura; o umple pachetul D): users/{uid}/listens/{YYYY-MM-DD} (cel mult 300 de piese pe zi) și nudges/{YYYY-MM-DD} (mesajele Căștii și ce ai făcut cu ele).
 * Pleacă doar cu contractul v4 (Prefs.contractAtLeast(4)); revocarea le șterge prin [SiteMirror.forget], pentru că
 * numele sunt deja în [SiteMirror.MIRRORED]. Pornită din [SiteMirror.start], o dată pe proces.
 */
object ListenMirror {
    /** Numele din [SiteMirror.MIRRORED] pe care le scrie oglinda aceasta. */
    val PATHS: List<String> = listOf("listens", "nudges")

    private val started = AtomicBoolean(false)

    /** Pornește ascultătorii o singură dată pe proces. Deocamdată nu face nimic. */
    fun start(app: ForjaApp) {
        if (!started.compareAndSet(false, true)) return
        // Pachetul care o umple pornește aici ascultătorii (app.appScope, contractAtLeast(4), uid-ul conectat).
    }
}
