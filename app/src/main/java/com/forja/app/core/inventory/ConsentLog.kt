package com.forja.app.core.inventory

import android.content.Context
import android.util.Log
import com.forja.app.core.music.AttemptEvent
import com.forja.app.core.music.DiagResult
import com.forja.app.core.music.MusicLog

/**
 * Jurnalul ascuns al acordului Android la aplicarea Inventarului (4.4.1): cererea, poarta, lansarea, acoperirea
 * ecranului, rezultatul, reîncercările, renunțările, fiecare cu milisecundele. Fără URI-uri, fără nume de dosare sau
 * de fișiere; doar pachetul care a creat cererea (MediaProvider) și numărul elementelor.
 *
 * - Mereu în logcat, eticheta [TAG]: `adb logcat -s ForjaConsent`.
 * - În jurnalul muzicii ([MusicLog], `want = "consent"`): se vede în ecranul ascuns Probă (Copiază / Trimite) și urcă
 *   la POST /v1/diag/music doar cu cont și cu contractul semnat, exact ca rândurile muzicii; serverul le arată în
 *   comanda de admin `music [n]`.
 *
 * Rândul: `rung` = `<K>_<PAS>` (K: W scriere, T coș, L laptop), `err` = `r<id>a<încercare> <notă>`.
 */
object ConsentLog {
    const val TAG = "ForjaConsent"
    const val WANT = "consent"

    fun add(ctx: Context, rung: String, result: DiagResult, ms: Long, note: String? = null, pkg: String? = null) {
        try { Log.i(TAG, listOfNotNull(rung, result.wire, "${ms}ms", pkg, note).joinToString(" ")) } catch (_: Throwable) { }
        try {
            MusicLog.add(
                ctx,
                AttemptEvent(
                    at = System.currentTimeMillis(),
                    want = WANT,
                    rung = rung.take(40),
                    pkg = pkg?.take(100),
                    ver = null,
                    kind = null,
                    result = result,
                    ms = ms.coerceAtLeast(0),
                    err = note?.take(120)
                )
            )
        } catch (_: Exception) { }
    }

    /** Trimite ce așteaptă (dacă are voie: cont + contract); la sfârșitul unei aplicări sau al acordului din laptop. */
    fun flush(ctx: Context) {
        try { MusicLog.flush(ctx) } catch (_: Exception) { }
    }
}
