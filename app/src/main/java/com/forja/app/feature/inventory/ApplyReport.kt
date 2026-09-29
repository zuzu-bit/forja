package com.forja.app.feature.inventory

import com.forja.app.core.inventory.MoveFail
import com.forja.app.core.inventory.MoveReason
import com.forja.app.core.inventory.MoveReasons

/**
 * Acțiunea (una singură) a paginii de rezultat, când n-a mers tot: accesul complet, încă o încercare sau niciuna
 * ([None]: o nouă încercare ar da același eșec; rămâne doar „Înapoi la dosare”).
 */
enum class DoneFix { Access, Retry, None }

/**
 * Ce spune pagina de la finalul unei aplicări (4.4.2, Kotlin pur): [failed] = nemutatele (pierdute + rămase în plan),
 * [reason] = un singur rând (null = nimic de spus), [fix] = acțiunea (null = totul e aplicat: pagina „Gata” obișnuită),
 * [effective] = eșecurile care contează (pentru jurnal).
 */
internal data class ApplyReport(val failed: Int, val reason: MoveReason?, val fix: DoneFix?, val effective: Map<String, MoveFail>)

internal object ApplyReports {
    /**
     * [remaining] = id-urile rămase în plan după aplicare, [lost] = cele ieșite din plan fără să ajungă la loc,
     * [failures] = motivele strânse din runde. „Permite accesul” doar când motivul se rezolvă cu el și nu e dat încă
     * ([accessAvailable] = Android 11+); fără acțiune când o nouă încercare ar da același eșec (alt volum; dosarul altei
     * aplicații sau dosar nepermis, cu accesul dat sau imposibil); altfel „Încearcă din nou” aplică restul planului.
     */
    fun of(
        photos: Boolean,
        remaining: Set<String>,
        lost: Int,
        failures: Map<String, MoveFail>,
        accessAvailable: Boolean,
        granted: Boolean
    ): ApplyReport {
        // Contează eșecurile elementelor încă în plan și cele dispărute; unul reușit într-o rundă următoare, nu.
        val effective = failures.filter { (id, f) -> id in remaining || f.reason == MoveReason.Gone }
        val raw = when {
            remaining.isNotEmpty() ->
                MoveReasons.main(effective.filterKeys { it in remaining }.values.groupingBy { it.reason }.eachCount()) ?: MoveReason.Error
            photos && lost > 0 && effective.values.any { it.reason == MoveReason.Gone } -> MoveReason.Gone
            else -> null
        }
        // „Owned” / „dir” spun „doar cu acces complet”: adevărat numai când accesul se poate da și nu e dat. Altfel (dat deja
        // sau Android 10, fără el) rândul e cel general, iar o nouă încercare ar da același eșec: nicio acțiune.
        val accessUseless = granted || !accessAvailable
        val stuck = raw == MoveReason.Volume || ((raw == MoveReason.Owned || raw == MoveReason.Dir) && accessUseless)
        val reason = if ((raw == MoveReason.Owned || raw == MoveReason.Dir) && accessUseless) MoveReason.Error else raw
        val fix = when {
            remaining.isEmpty() -> null
            photos && !accessUseless && raw?.needsAccess == true -> DoneFix.Access
            stuck -> DoneFix.None
            else -> DoneFix.Retry
        }
        return ApplyReport(lost + remaining.size, reason, fix, effective)
    }
}
