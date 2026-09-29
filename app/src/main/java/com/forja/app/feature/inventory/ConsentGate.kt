package com.forja.app.feature.inventory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Acordul Android (dialogul de scriere, de coș sau cel pentru mutările din laptop), câte unul pe rând.
 *
 * - **Scoate, apoi completează.** Pe Dispatchers.Main.immediate, `complete()` reia bucla care așteaptă chiar în
 *   interiorul apelului, iar bucla cere pe loc dialogul următor (scriere → coș). În 4.4 ordinea era inversă
 *   (`answer?.complete(ok); answer = null`) și ștergea tocmai cererea coșului: aplicarea rămânea pentru totdeauna în
 *   „AȘTEPT ACORDUL TĂU”. Aici cererea și așteptătorul se scot ÎNAINTE de `complete()`, peste tot.
 * - **Cererea rămâne publicată până la răspuns** (nu dispare la lansare): ecranul o poate relansa, iar „Înapoi la
 *   dosare” o poate închide oricând. `launched` spune că dialogul ei e deja cerut (o recreare nu îl mai cere o dată).
 * - **Rezultatele se potrivesc cu lansările** în ordinea lor (ActivityResult nu poartă id): un rezultat care nu e al
 *   cererii curente se ignoră. Lansările aceleiași cereri (încercările ei) cer aceeași bucată, deci oricare răspuns
 *   al lor e valabil pentru ea.
 * - **Dialog care nu apare** ([missing]): prima dată cererea se reface în tăcere (PendingIntent nou, încercarea 1);
 *   după aceea rămâne „blocată” ([Request.stuck]) și pagina arată „Încearcă din nou” și „Înapoi la dosare”.
 * - Anularea coroutinei care așteaptă scoate cererea: nimic nu rămâne agățat.
 *
 * Kotlin pur (testat pe JVM, pe un „fir principal” cu semantica lui Main.immediate).
 */
class ConsentGate<T : Any> {
    enum class Kind(val code: String) { WRITE("W"), TRASH("T"), LAPTOP("L") }

    /** Răspunsul la o cerere. [DROPPED] = FORJA a renunțat la ea (înlocuită, imposibil de refăcut), nu omul. */
    enum class Answer { YES, NO, DROPPED }

    /** Ce a făcut un rezultat (pentru jurnalul de diagnoză). */
    enum class Outcome { APPLIED, STALE, NO_LAUNCH }

    /** Ce urmează când dialogul unei lansări nu a apărut sau s-a pierdut. */
    enum class Missing { RENEW, STUCK, IGNORED }

    data class Request<T : Any>(
        val id: Long,
        val payload: T,
        val kind: Kind,
        /** 0 = primul PendingIntent; +1 la fiecare cerere refăcută. Cheia lansării pe ecran, împreună cu [id]. */
        val attempt: Int = 0,
        /** Dialogul acestei încercări e cerut (launcher.launch() a trecut); rezultatul vine ca mesaj ulterior. */
        val launched: Boolean = false,
        /** Nici încercarea refăcută n-a arătat dialogul: omul alege „Încearcă din nou” sau „Înapoi la dosare”. */
        val stuck: Boolean = false
    ) {
        /** Eticheta din jurnal: `r<id>a<încercare>`. */
        val tag: String get() = "r${id}a$attempt"
    }

    private val _current = MutableStateFlow<Request<T>?>(null)
    val current: StateFlow<Request<T>?> = _current.asStateFlow()
    private var waiter: CompletableDeferred<Answer>? = null
    private var nextId = 0L
    /** Id-urile lansate și încă fără rezultat, în ordinea lansării (un rezultat pe lansare). */
    private val inFlight = ArrayDeque<Long>()

    /** Publică cererea și așteaptă răspunsul. O cerere nouă o închide pe cea veche cu [Answer.DROPPED]. */
    suspend fun ask(payload: T, kind: Kind): Answer {
        val d = CompletableDeferred<Answer>()
        val id = ++nextId
        val old = waiter
        waiter = d
        // O cerere nouă se lansează abia cu activitatea în față, deci după ce orice dialog vechi s-a închis: lansările
        // vechi fără rezultat nu mai au ce atinge.
        inFlight.clear()
        _current.value = Request(id, payload, kind)
        old?.complete(Answer.DROPPED)   // după publicare: cel vechi se poate relua pe loc și găsește cererea nouă întreagă
        try {
            return d.await()
        } finally {
            if (waiter === d) waiter = null
            if (_current.value?.id == id) _current.value = null
        }
    }

    /** launcher.launch() a trecut (fără excepție) pentru încercarea [attempt] a cererii [id]. False = nu mai e a ei. */
    fun launched(id: Long, attempt: Int): Boolean {
        val r = _current.value ?: return false
        if (r.id != id || r.attempt != attempt || r.launched) return false
        inFlight.addLast(id)
        _current.value = r.copy(launched = true)
        return true
    }

    /** Ce ar face acum un rezultat, fără să-l aplice (jurnalul îl scrie înaintea efectelor lui). */
    fun peek(): Outcome {
        val id = inFlight.firstOrNull() ?: return Outcome.NO_LAUNCH
        return if (_current.value?.id == id && waiter != null) Outcome.APPLIED else Outcome.STALE
    }

    /** Rezultatul din ActivityResult: al celei mai vechi lansări încă fără rezultat; contează doar dacă e al cererii curente. */
    fun answer(ok: Boolean): Outcome {
        val id = inFlight.removeFirstOrNull() ?: return Outcome.NO_LAUNCH   // fără lansare cunoscută (de ex. proces nou)
        val r = _current.value
        if (r == null || r.id != id) return Outcome.STALE                     // rezultatul unei cereri vechi
        val d = waiter ?: return Outcome.STALE
        waiter = null
        _current.value = null
        d.complete(if (ok) Answer.YES else Answer.NO)   // ultimul pas: bucla se poate relua pe loc și cere următorul dialog
        return Outcome.APPLIED
    }

    /**
     * Rezultatul unei lansări care n-a putut trimite PendingIntent-ul (SendIntentException: anulat, folosit deja): nu e
     * „Nu”-ul omului. Întoarce cererea curentă dacă rezultatul era al ei (apelantul cheamă apoi [missing]), altfel null.
     */
    fun dropLaunch(): Request<T>? {
        val id = inFlight.removeFirstOrNull() ?: return null
        return _current.value?.takeIf { it.id == id }
    }

    /**
     * Încercarea [attempt] a cererii [id] nu a arătat dialogul (ecranul a rămas în față), nu l-a putut trimite sau a
     * revenit fără rezultat. Prima încercare → [Missing.RENEW] (apelantul reface cererea și cheamă [renew]); oricare
     * alta → [Missing.STUCK] (pagina arată cele două acțiuni).
     */
    fun missing(id: Long, attempt: Int): Missing {
        val r = _current.value ?: return Missing.IGNORED
        if (r.id != id || r.attempt != attempt || r.stuck) return Missing.IGNORED
        if (r.attempt == 0) return Missing.RENEW
        _current.value = r.copy(stuck = true)
        return Missing.STUCK
    }

    /** Aceeași cerere, cu un PendingIntent nou (cele din MediaStore sunt ONE_SHOT): ecranul o lansează din nou. */
    fun renew(id: Long, payload: T): Boolean {
        val r = _current.value ?: return false
        if (r.id != id) return false
        _current.value = r.copy(payload = payload, attempt = r.attempt + 1, launched = false, stuck = false)
        return true
    }

    /** „Înapoi la dosare” ([Answer.NO]) sau o cerere imposibil de refăcut / ViewModel-ul închis ([Answer.DROPPED]). */
    fun cancel(answer: Answer) {
        val d = waiter
        waiter = null
        _current.value = null
        inFlight.clear()
        d?.complete(answer)
    }
}
