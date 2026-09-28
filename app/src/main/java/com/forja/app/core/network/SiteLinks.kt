package com.forja.app.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.forja.app.BuildConfig

/**
 * Legăturile telefonului spre site-ul FORJA (forja-insights), câte una pentru fiecare secțiune.
 * Site-ul 4.4 are o secțiune pentru fiecare abilitate a aplicației, adresabilă prin hash: `/insights#gasire`.
 * Dacă browserul nu e conectat, site-ul cere logarea și apoi deschide exact secțiunea cerută.
 */
object SiteLinks {
    /** Secțiunile site-ului 4.4 (hash-urile sunt contract cu `server/insights.html`, vezi DESIGN-4.4.md §5). */
    enum class Section(val hash: String) {
        Azi("azi"),
        Teren("teren"),
        Camarazi("camarazi"),
        Inventar("inventar"),
        Somn("somn"),
        Ratie("ratie"),
        Mars("mars"),
        Muzica("muzica"),
        Paza("paza"),
        Gasire("gasire"),
        Cont("cont"),
    }

    val base: String get() = BuildConfig.INSIGHTS_URL.trimEnd('/')

    /** `https://…/insights#<secțiune>`; `detail` (opțional) se adaugă după „/”, ex. `inventar/<idRulare>`. */
    fun url(section: Section? = null, detail: String? = null): String {
        val root = "$base/insights"
        if (section == null) return root
        val tail = detail?.takeIf { it.isNotBlank() }?.let { "/" + Uri.encode(it) }.orEmpty()
        return "$root#${section.hash}$tail"
    }

    /** Deschide secțiunea în browser. Întoarce false dacă nu există niciun browser (apelantul arată un toast). */
    fun open(context: Context, section: Section? = null, detail: String? = null): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url(section, detail))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
