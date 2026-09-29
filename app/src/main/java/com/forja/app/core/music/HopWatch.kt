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
 *
 * Înapoi-ul ei întârziat ([swallowBack]): pe ecranul live, Înapoi = „Încheie”. Dacă FORJA închide ecranul Spotify chiar
 * când ea apasă Înapoi ca să iasă din el („te întorci cu Înapoi”), apăsarea ajunge pe ecranul live și ar încheia
 * antrenamentul și muzica abia pornită. Un singur Înapoi, în [SWALLOW_MS] de la revenirea FORJA în față după un salt,
 * se înghite.
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

    /** Un salt a plecat și FORJA încă nu s-a văzut din nou în față (și după rândul „none”: ea revine mai târziu). */
    private var away = false

    /** Când a revenit FORJA în față după un salt (sau a cerut închiderea ecranului playerului); 0 = nimic de înghițit. */
    private var returnedAt = 0L

    /** Un salt e deschis (ecranul playerului a fost adus în față de FORJA și nu s-a lămurit întoarcerea). */
    val open: Boolean get() = hop != null

    /** O treaptă vizibilă intermediară (V_*) a plecat. [sub] = deschisă pentru rezultat, fără task nou. */
    fun sent(want: String, pkg: String?, source: MusicSource, sub: Boolean, now: Long) {
        hop = Hop(want, pkg, source, sub, now)
        away = true
        returnedAt = 0L
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
        // Ecranul live revine peste o clipă: un Înapoi al ei de acum era pentru Spotify.
        returnedAt = now
        return true
    }

    /** La [CHECK_MS] după închidere: FORJA e în față (auto) sau nu (none, e nevoie de Înapoi). */
    fun check(visible: Boolean, now: Long): Row? {
        val h = hop ?: return null
        if (h.closedAt == 0L) return null
        hop = null
        if (visible) arrived(now)
        return row(h, if (visible) DiagResult.OK else DiagResult.TIMEOUT, if (visible) "auto" else "none", now)
    }

    /** FORJA a revenit în față (ciclul procesului). Dacă FORJA nu închisese saltul, s-a întors ea (back). */
    fun back(now: Long): Row? {
        arrived(now)
        return settle(now)
    }

    /**
     * O atingere nouă în FORJA: un salt rămas deschis s-a încheiat (s-a întors ea, prea repede ca procesul să fi trecut
     * prin fundal). Rândul e „back”, dar nimic de înghițit: atenția ei e deja în FORJA.
     */
    fun settle(now: Long): Row? {
        away = false
        val h = hop ?: return null
        if (h.closedAt != 0L) return null
        hop = null
        return row(h, DiagResult.OK, if (h.sub) "back" else "back task", now)
    }

    /**
     * Ecranul live primește Înapoi. true = e apăsarea ei pentru Spotify, ajunsă după ce FORJA a revenit în față dintr-un
     * salt (cel mult [SWALLOW_MS] de atunci): nu încheie antrenamentul. Doar una; următoarea încheie ca de obicei.
     */
    fun swallowBack(now: Long): Boolean {
        val at = returnedAt
        if (at == 0L || now < at || now - at > SWALLOW_MS) return false
        returnedAt = 0L
        return true
    }

    private fun arrived(now: Long) {
        if (!away) return
        away = false
        returnedAt = now
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
        away = false
        return h.sub
    }

    private fun row(h: Hop, result: DiagResult, note: String, now: Long): Row {
        val cancel = if (h.cancelAt != 0L) " cancel:${(h.cancelAt - h.at).coerceAtLeast(0L)}" else ""
        return Row(h.want, h.pkg, result, (now - h.at).coerceAtLeast(0L), note + cancel)
    }

    companion object {
        /** Cât se așteaptă după închidere până se vede dacă FORJA e din nou în față. */
        const val CHECK_MS = 800L
        /** Cât după revenirea FORJA un Înapoi e încă al ei pentru Spotify (închiderea + animația + reacția ei). */
        const val SWALLOW_MS = 3_000L
        const val RUNG = "RET"
    }
}
