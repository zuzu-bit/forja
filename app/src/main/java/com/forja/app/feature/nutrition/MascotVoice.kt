package com.forja.app.feature.nutrition

/*
 * Personalitatea Bucătarului: patru voci în registrul FORJA. Schimbă replicile, nu regulile.
 * Sergent (direct) · Camarad (cald) · Antrenor (energic) · Ghid (calm). Fără semne de exclamare.
 */
enum class MascotVoice(val label: String, val hint: String) {
    Sergent("Sergent", "Direct. Scurt. Fără menajamente."),
    Camarad("Camarad", "Cald. Îți ține partea."),
    Antrenor("Antrenor", "Energic. Te împinge înainte."),
    Ghid("Ghid", "Calm. Explică și așteaptă.");

    /** Replicile din fluxul de analiză, rotite cât durează scanarea. */
    val analysis: List<String>
        get() = when (this) {
            Sergent -> listOf("Mă uit la farfurie.", "Cântăresc porțiile.", "Verific totalurile.")
            Camarad -> listOf("Mă uit la farfurie, stai liniștit.", "Cântăresc porțiile din ochi.", "Verific totalurile, aproape gata.")
            Antrenor -> listOf("Mă uit la farfurie. Arată bine.", "Cântăresc porțiile. Ține ritmul.", "Verific totalurile. Imediat.")
            Ghid -> listOf("Mă uit la farfurie, pe îndelete.", "Cântăresc porțiile. Tu confirmi la final.", "Verific totalurile. Nimic nu se salvează încă.")
        }

    /** Când analiza a picat. */
    val sorry: String
        get() = when (this) {
            Sergent -> "Nu a mers. Refacem."
            Camarad -> "Nu a mers de data asta. Mai încercăm."
            Antrenor -> "Nu a mers. Încă o poză și gata."
            Ghid -> "Nu a mers. Poți reface poza sau scrie tu masa."
        }

    /** Rezultatul a sosit. */
    val done: String
        get() = when (this) {
            Sergent -> "Gata. Corectează ce nu se potrivește."
            Camarad -> "Gata. Uită-te peste porții, tu știi mai bine."
            Antrenor -> "Gata. Verifică porțiile și confirmă."
            Ghid -> "Gata. Estimare, nu cântar. Corectezi ce vrei."
        }

    /** Replici de rezervă pentru cardul zilei (la atingerea mascotei). */
    val extra: List<String>
        get() = when (this) {
            Sergent -> listOf("Farfuria de sus, în lumină.", "Apa nu se uită.", "Porția o decizi tu. Eu estimez.", "Codul de bare e exact. Poza e estimare.")
            Camarad -> listOf("Farfuria de sus, în lumină. Restul fac eu.", "Un pahar cu apă nu strică.", "Porția o decizi tu. Eu doar estimez.", "Codul de bare e exact. Poza e estimare.")
            Antrenor -> listOf("Poza de sus, lumină bună, mergem.", "Apă. Acum.", "Tu decizi porția. Eu țin socoteala.", "Codul de bare e exact. Poza e estimare.")
            Ghid -> listOf("Fotografiază de sus, cu lumină. Restul se face singur.", "Apa se uită ușor. Ia o gură.", "Porția o decizi tu. Eu doar estimez.", "Codul de bare e exact. Poza e estimare.")
        }

    companion object {
        fun of(index: Int): MascotVoice = entries.getOrElse(index) { Camarad }
    }
}
