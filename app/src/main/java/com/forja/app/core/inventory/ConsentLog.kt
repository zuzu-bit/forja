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
 * - În jurnalul muzicii ([MusicLog], `want = "consent"`), cu locurile lui numărate separat (cel mult 60 în memorie, 40
 *   pe telefon, 60 de trimis): nu împinge afară încercările muzicii. În ecranul ascuns Probă nu umple lista (acolo
 *   stau încercările muzicii), dar pleacă cu Copiază / Trimite; urcă la POST /v1/diag/music doar cu cont și cu
 *   contractul semnat, exact ca rândurile muzicii; serverul le arată în comanda de admin `consent [n]`.
 * - Rândurile obișnuite: ASK, SHOWN, RESULT pe dialog, APPLY_START / APPLY_END pe aplicare. Poarta (GATE) doar când e
 *   lentă sau ruta a rămas în urmă; lansarea (LAUNCH) doar când aruncă. Restul (STUCK, LOST, ADOPT, RETRY, BACK,
 *   T_SKIP) apar doar când se întâmplă.
 *
 * Rândul: `rung` = `<K>_<PAS>` (K: W scriere, T coș, L laptop), `err` = `r<id>a<încercare> <notă>`.
 */
object ConsentLog {
    const val TAG = "ForjaConsent"
    const val WANT = MusicLog.CONSENT

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
