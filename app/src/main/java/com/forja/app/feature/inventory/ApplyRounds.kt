package com.forja.app.feature.inventory

import android.content.IntentSender
import com.forja.app.core.inventory.ApplyResult
import com.forja.app.core.inventory.Landing
import com.forja.app.core.inventory.MoveFail
import com.forja.app.core.inventory.MoveReason
import com.forja.app.core.inventory.TrashAsk

/**
 * Bucla aplicării pozelor (S6), scoasă din InventoryViewModel ca testele s-o poată rula cu cereri și runde false. O
 * rundă: cererile (scrierea, dacă mai e ceva de mutat, și coșul), apoi dialogul de scriere, dialogul coșului și
 * [runApply]. Coșul poate cere un dialog sau nimic: o bucată aflată deja toată la coș ([TrashAsk.AlreadyTrashed],
 * acordul dat într-o aplicare întreruptă) nu cere dialog, dar runda tot rulează, ca pozele să iasă din plan. Bucla se
 * oprește când o rundă n-a avut nimic acordat sau n-a aplicat nimic; un răspuns care nu e DA o oprește pe loc.
 *
 * 4.4.2: cu „Acces la toate fișierele” ([manager]) nu se cere nimic: o singură rundă aplică tot ce a rămas, apoi bucla
 * se oprește. Fără el, fluxul e cel din 4.4.1; doar elementele care au eșuat deja dintr-un motiv pe care un acord nou
 * nu-l schimbă ([permanent]) nu se mai cer încă o dată în runda următoare ([writeRequest] le primește ca `skip`).
 */
internal class ApplyRounds(
    private val writeRequest: (skip: Set<String>) -> IntentSender?,
    private val trashRequest: suspend () -> TrashAsk,
    private val ask: suspend (IntentSender, ConsentGate.Kind) -> ConsentGate.Answer,
    private val waiting: (Boolean) -> Unit,
    private val runApply: suspend (manager: Boolean) -> ApplyResult,
    private val manager: Boolean = false
) {
    /** Totalurile rundelor de până acum (și după o excepție: ce s-a aplicat a ieșit deja din plan). */
    var total = ApplyResult(0, 0, 0, 0L)
        private set
    var landing: Landing? = null
        private set
    /** Nemutatele: cele pierdute în fiecare rundă + cele încă în plan după ultima (ca AppliedRec.failed de pe site). */
    var lost = 0
        private set
    var pending = 0
        private set
    var rounds = 0
        private set

    private val _failures = LinkedHashMap<String, MoveFail>()
    /** Motivul fiecărui eșec al aplicării, pe element (ultimul, dacă a eșuat în mai multe runde). */
    val failures: Map<String, MoveFail> get() = _failures

    private val _skip = HashSet<String>()
    /** Elementele care nu se mai cer în runda următoare: au eșuat dintr-un motiv [permanent]. */
    val skip: Set<String> get() = _skip

    /** Null = bucla a mers până la capăt; altfel răspunsul (NU / RENUNȚAT) care a oprit-o. */
    suspend fun run(): ConsentGate.Answer? {
        if (manager) {
            // Acces complet: fără ferestre de acord; toate mutările rămase și tot „De aruncat”, într-o singură rundă.
            record(runApply(true))
            return null
        }
        while (rounds < MAX_ROUNDS) {
            val w = writeRequest(skip)
            // Coșul se pregătește înaintea dialogului de scriere: întrebarea „ce e deja la coș” merge pe IO acum, nu în
            // rezultatul dialogului, iar după „Permite” la scriere dialogul coșului urmează pe loc (fără un cadru în care
            // pagina să nu mai aștepte).
            val t = trashRequest()
            if (w != null) {
                val a = asked(w, ConsentGate.Kind.WRITE)
                if (a != ConsentGate.Answer.YES) return a
            }
            if (t is TrashAsk.Dialog) {
                val a = asked(t.sender, ConsentGate.Kind.TRASH)
                if (a != ConsentGate.Answer.YES) return a
            }
            // Runda are ce aplica: un acord dat (scriere / coș) sau o bucată deja toată la coș, fără dialog.
            val granted = w != null || t != TrashAsk.None
            if (rounds > 0 && !granted) break
            val r = runApply(false)
            record(r)
            if (!granted) break
            if (r.moved + r.trashed == 0) break
        }
        return null
    }

    private fun record(r: ApplyResult) {
        total = ApplyResult(total.moved + r.moved, total.trashed + r.trashed, total.failed + r.failed, total.freedBytes + r.freedBytes)
        lost += r.lost
        pending = r.pending
        landing = landing?.merge(r.landing) ?: r.landing
        for ((id, f) in r.failures) {
            _failures[id] = f
            if (permanent(f.reason)) _skip += id
        }
        rounds++
    }

    private suspend fun asked(sender: IntentSender, kind: ConsentGate.Kind): ConsentGate.Answer {
        waiting(true)
        try {
            return ask(sender, kind)
        } finally {
            waiting(false)
        }
    }

    companion object {
        /** Plasa buclei: 64 de runde × 500 = 32 000 de elemente. */
        const val MAX_ROUNDS = 64

        /**
         * Un acord de scriere nou nu schimbă nimic: dosarul altei aplicații, dosar nepermis, alt volum, element dispărut.
         * Celelalte (refuz, eroare, cale diferită) se mai încearcă o dată în runda următoare, ca în 4.4.1.
         */
        fun permanent(r: MoveReason): Boolean =
            r == MoveReason.Owned || r == MoveReason.Dir || r == MoveReason.Volume || r == MoveReason.Gone
    }
}

/**
 * Refacerea unei cereri a coșului (dialog neapărut, rezultat pierdut după „Permite”): un dialog nou, gata (bucata e
 * deja toată la coș, deci acordul a fost dat: DA, iar bucla o numără) sau imposibil (FORJA renunță).
 */
internal fun TrashAsk.renewal(): ConsentGate.Renewal<IntentSender> = when (this) {
    is TrashAsk.Dialog -> ConsentGate.Renewal.Again(sender)
    TrashAsk.AlreadyTrashed -> ConsentGate.Renewal.Done
    TrashAsk.None -> ConsentGate.Renewal.Drop
}
