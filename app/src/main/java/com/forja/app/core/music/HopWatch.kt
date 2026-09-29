package com.forja.app.core.music

/**
 * Saltul în player și întoarcerea în FORJA (RET_SUB, 4.4.1). Kotlin pur; stratul Android ([MusicStarter]) face
 * deschiderea și închiderea, aici se ține minte saltul deschis și se decide rândul RET din jurnal.
 *
 * FORJA deschide ecranul de link al Spotify „pentru rezultat”, din MainActivity, fără task nou (sub): dacă Spotify îl
 * lasă în taskul FORJA, FORJA îl închide singură (finishActivity) după ce muzica e confirmată. Închiderea unui ecran
 * deschis pentru rezultat nu e o pornire de activitate, deci regulile Android 16 pentru fundal nu o opresc.
 *
 * Rândul RET (ms = de la salt până la întoarcere):
 * - ok · auto: FORJA a închis ecranul și e din nou în față după [CHECK_MS];
 * - timeout · none: FORJA l-a închis, dar nu e în față (ecranul Spotify stă în taskul lui, `singleTask`): e nevoie de Înapoi;
 * - ok · back: s-a întors ea înainte ca FORJA să închidă ecranul (`back task` = saltul a plecat cu task nou).
 * `cancel:<ms>` = Android a anulat rezultatul imediat după salt (ecranul Spotify s-a deschis în taskul lui).
 */
class HopWatch {

    data class Row(val want: String, val pkg: String?, val result: DiagResult, val ms: Long, val note: String)

    private class Hop(val want: String, val pkg: String?, val source: MusicSource, val sub: Boolean, val at: Long) {
        /** FORJA a cerut închiderea ecranului (0 = încă nu). */
        var closedAt = 0L
        /** Rezultatul (anulat) a venit înainte de închidere și de întoarcere (0 = nu). */
        var cancelAt = 0L
    }

    private var hop: Hop? = null

    /** Un salt e deschis (ecranul playerului a fost adus în față de FORJA și nu s-a lămurit întoarcerea). */
    val open: Boolean get() = hop != null

    /** O treaptă vizibilă intermediară (V_*) a plecat. [sub] = deschisă pentru rezultat, fără task nou. */
    fun sent(want: String, pkg: String?, source: MusicSource, sub: Boolean, now: Long) {
        hop = Hop(want, pkg, source, sub, now)
    }

    /** Rezultatul saltului a ajuns în FORJA (anulat). Contează doar dacă vine înaintea închiderii: taskul lui Spotify. */
    fun result(now: Long) {
        val h = hop ?: return
        if (h.closedAt == 0L && h.cancelAt == 0L) h.cancelAt = now
    }

    /**
     * Muzica e confirmată. true = FORJA închide acum ecranul (finishActivity), iar peste [CHECK_MS] se vede, cu [check],
     * dacă a revenit. Un salt cu task nou nu se poate închide: rămâne deschis până se întoarce ea ([back]).
     */
    fun confirm(now: Long): Boolean {
        val h = hop ?: return false
        if (!h.sub || h.closedAt != 0L) return false
        h.closedAt = now
        return true
    }

    /** La [CHECK_MS] după închidere: FORJA e în față (auto) sau nu (none, e nevoie de Înapoi). */
    fun check(visible: Boolean, now: Long): Row? {
        val h = hop ?: return null
        if (h.closedAt == 0L) return null
        hop = null
        return row(h, if (visible) DiagResult.OK else DiagResult.TIMEOUT, if (visible) "auto" else "none", now)
    }

    /** FORJA a revenit în față. Dacă FORJA nu închisese saltul, s-a întors ea (back). */
    fun back(now: Long): Row? {
        val h = hop ?: return null
        if (h.closedAt != 0L) return null
        hop = null
        return row(h, DiagResult.OK, if (h.sub) "back" else "back task", now)
    }

    /**
     * Sesiunea s-a încheiat sau ecranul care a pornit încercarea a plecat ([source] = al cui; null = oricare): un salt
     * rămas deschis se uită, fără rând. true = ecranul playerului e încă în taskul FORJA și trebuie închis acum.
     */
    fun drop(source: MusicSource?): Boolean {
        val h = hop ?: return false
        if (source != null && h.source != source) return false
        // Închis deja de FORJA: rândul RET îl scrie verificarea care urmează.
        if (h.closedAt != 0L) return false
        hop = null
        return h.sub
    }

    private fun row(h: Hop, result: DiagResult, note: String, now: Long): Row {
        val cancel = if (h.cancelAt != 0L) " cancel:${(h.cancelAt - h.at).coerceAtLeast(0L)}" else ""
        return Row(h.want, h.pkg, result, (now - h.at).coerceAtLeast(0L), note + cancel)
    }

    companion object {
        /** Cât se așteaptă după închidere până se vede dacă FORJA e din nou în față. */
        const val CHECK_MS = 800L
        const val RUNG = "RET"
    }
}
