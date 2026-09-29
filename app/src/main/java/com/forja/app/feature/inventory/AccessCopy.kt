package com.forja.app.feature.inventory

import androidx.compose.runtime.Immutable
import com.forja.app.core.inventory.MoveReason

/**
 * Rândul „Acces complet” din confirmare (4.4.2), când „Acces la toate fișierele” nu e dat și planul are mutări.
 * [owned] = mutările din dosarul altei aplicații (Android/media/<pachet>/), [app] = numele ei, când e una singură
 * ([apps] = câte aplicații), [forDest] = dosarul ales e în afara Pictures/DCIM: fără acces, „Aplică” îl cere întâi.
 */
@Immutable
data class AccessUi(val owned: Int = 0, val app: String? = null, val apps: Int = 0, val forDest: Boolean = false) {
    /** „Aplică” nu poate ajunge în dosarul ales fără acces: îl cere înainte (și rândul nu are „Fără”). */
    val required: Boolean get() = forDest
}

/**
 * Textele accesului și ale paginii de rezultat (TONE: fără „!”, ș/ț cu virgulă, butoane ≤ 18 caractere, puțin text).
 * „Poze” acoperă și videoclipurile, ca în tot Inventarul („MUT POZELE ÎN DOSARE”).
 */
object AccessCopy {
    const val TITLE = "Acces complet"
    const val LINE = "Mută oriunde, fără ferestre de acord."
    const val DIR = "Dosarul ales cere acces complet."
    const val ALLOW = "Permite"
    const val SKIP = "Fără"
    const val ALLOW_RESULT = "Permite accesul"
    const val RETRY = ApplyCopy.RETRY
    const val BACK = ApplyCopy.BACK
    /** Titlurile paginii de rezultat: ceva aplicat / nimic aplicat. */
    const val PARTIAL = "Aproape"
    const val NOTHING = "Nimic mutat"
    const val OTHER_APP = "altei aplicații"
    const val OTHER_APPS = "altor aplicații"

    /** A cui e poza: „WhatsApp”, „altei aplicații” (una, fără nume), „altor aplicații” (mai multe). */
    fun owner(app: String?, apps: Int): String = when {
        apps > 1 -> OTHER_APPS
        !app.isNullOrBlank() -> app.trim()
        else -> OTHER_APP
    }

    /** „6 poze sunt ale WhatsApp. Android le mută doar cu acces complet.” (și forma de singular). */
    fun ownedLine(n: Int, app: String?, apps: Int = 1): String {
        val who = owner(app, apps)
        return if (n == 1) "1 poză e a $who. Android o mută doar cu acces complet."
        else "${fmtCount(n)} poze sunt ale $who. Android le mută doar cu acces complet."
    }

    /** Rândul din confirmare: dosarul ales, pozele altei aplicații sau varianta simplă. */
    fun line(ui: AccessUi): String = when {
        ui.forDest -> DIR
        ui.owned > 0 -> ownedLine(ui.owned, ui.app, ui.apps)
        else -> LINE
    }

    /** Rândul motivului, pe pagina de rezultat (unul singur). */
    fun reason(r: MoveReason, app: String? = null, apps: Int = 1): String = when (r) {
        MoveReason.Owned -> "Sunt ale ${owner(app, apps)}: Android le mută doar cu acces complet."
        MoveReason.Dir -> DIR
        MoveReason.Denied -> "Android a refuzat mutarea."
        MoveReason.Gone -> "Au dispărut între timp."
        MoveReason.Volume -> "Sunt pe alt card de memorie."
        MoveReason.Mismatch, MoveReason.Error -> "Android nu le-a mutat."
    }

    /** Butonul acțiunii de pe pagina de rezultat. */
    fun fix(f: DoneFix): String = when (f) {
        DoneFix.Access -> ALLOW_RESULT
        DoneFix.Retry -> RETRY
    }
}
