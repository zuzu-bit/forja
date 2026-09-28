package com.forja.app.feature.nutrition

import kotlin.math.roundToInt

/*
 * Profilul corpului și rația calculată din el (SPEC-4.2 „nutrition-3” §1).
 * Mifflin-St Jeor → TDEE × activitate (1,2 – 1,725) ± obiectiv (−15 % / 0 / +10 %);
 * proteine 1,8 g/kg (slăbit 2,0), grăsimi 0,8 g/kg, restul carbo. Estimare, nu prescripție.
 */

/** Ce a răspuns la chestionar. Valorile 0 / gol înseamnă „nespus”. */
data class BodyProfile(
    val sex: Int = -1,              // 0 femeie · 1 bărbat · 2 nebinar
    val age: Int = 0,
    val heightCm: Int = 0,
    val weightKg: Float = 0f,
    val activity: Int = -1,         // 0 sedentar · 1 ușor · 2 moderat · 3 intens
    val goal: Int = -1,             // 0 slăbit · 1 menținut · 2 masă
    val diet: Int = -1,             // index în DIETS
    val restrictions: Set<String> = emptySet(),
    val eatWhere: Int = -1,         // index în EAT_WHERE
    val changes: Set<String> = emptySet(),
    val activityMode: Int = 0,      // 0 Smart · 1 Toate caloriile
    val cravings: Int = -1,         // 0 tot timpul · 1 din când în când · 2 rar
    val done: Boolean = false
) {
    /** Datele minime pentru formulă: sex, vârstă, înălțime, greutate, activitate, obiectiv. */
    val complete: Boolean
        get() = sex >= 0 && age in 10..110 && heightCm in 120..230 && weightKg in 30f..300f && activity >= 0 && goal >= 0

    val bmi: Float get() = if (heightCm <= 0) 0f else weightKg / ((heightCm / 100f) * (heightCm / 100f))

    companion object {
        val SEXES = listOf("Femeie", "Bărbat", "Nebinar")
        val ACTIVITIES = listOf("Sedentar", "Ușor activ", "Activ", "Foarte activ")
        val ACTIVITY_HINTS = listOf("birou, puțină mișcare", "1–3 antrenamente pe săptămână", "3–5 antrenamente pe săptămână", "muncă fizică sau sport zilnic")
        val ACTIVITY_FACTORS = listOf(1.2, 1.375, 1.55, 1.725)
        val GOALS = listOf("Slăbit", "Menținut", "Masă musculară")
        val DIETS = listOf("Echilibrată", "Vegetariană", "Vegană", "Paleo", "Keto", "Proteică", "Low-carb")
        val RESTRICTIONS = listOf("Lactate", "Gluten", "Ouă", "Nuci", "Pește", "Fructe de mare", "Soia", "Carne roșie", "Citrice", "Semințe")
        val EAT_WHERE = listOf("Gătesc acasă", "Comand", "Mănânc în oraș")
        val CHANGES = listOf("Mai puțin zahăr", "Mai puțin fast-food", "Fără mâncat pe fugă", "Mai multe legume", "Fără mâncat pe stres")
        val CRAVINGS = listOf("Tot timpul", "Din când în când", "Rar")
    }
}

/** Rația zilnică calculată: kcal + P/C/G în grame, cu explicația într-o linie. */
data class Targets(val kcal: Int, val protein: Int, val carbs: Int, val fat: Int, val bmr: Int, val tdee: Int) {
    val summary: String get() = "${Targets.fmt(kcal)} kcal · P $protein g · C $carbs g · G $fat g"

    companion object {
        /** Mifflin-St Jeor. Nebinar → media formulelor (onest: formula are doar două ramuri). */
        fun of(p: BodyProfile): Targets? {
            if (!p.complete) return null
            val base = 10.0 * p.weightKg + 6.25 * p.heightCm - 5.0 * p.age
            val bmr = when (p.sex) {
                0 -> base - 161
                1 -> base + 5
                else -> base - 78
            }
            val tdee = bmr * BodyProfile.ACTIVITY_FACTORS[p.activity.coerceIn(0, 3)]
            val kcal = when (p.goal) {
                0 -> tdee * 0.85
                2 -> tdee * 1.10
                else -> tdee
            }.roundToInt().coerceIn(NutritionPrefs.MIN_KCAL, NutritionPrefs.MAX_KCAL)
            val protein = (p.weightKg * if (p.goal == 0) 2.0 else 1.8).roundToInt()
            val fat = (p.weightKg * 0.8).roundToInt()
            val carbs = ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(0)
            return Targets(kcal, protein, carbs, fat, bmr.roundToInt(), tdee.roundToInt())
        }

        /** „1 850” — mii separate cu spațiu subțire, ca în raportul de zi. */
        fun fmt(n: Int): String {
            val s = n.toString()
            if (s.length <= 3) return s
            val out = StringBuilder()
            s.forEachIndexed { i, c ->
                if (i > 0 && (s.length - i) % 3 == 0) out.append(' ')
                out.append(c)
            }
            return out.toString()
        }

        fun fmtBmi(v: Float): String = String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',')

        /** Explicația într-o linie: „din 62 kg, 1,68 m, 29 ani, activ”. */
        fun explain(p: BodyProfile): String {
            val w = if (p.weightKg == p.weightKg.toInt().toFloat()) "${p.weightKg.toInt()}" else fmtBmi(p.weightKg)
            val h = String.format(java.util.Locale.ROOT, "%.2f", p.heightCm / 100f).replace('.', ',')
            return "din $w kg, $h m, ${p.age} ani, ${BodyProfile.ACTIVITIES[p.activity.coerceIn(0, 3)].lowercase()}"
        }
    }
}

/** Indicele de masă corporală, cu categoria și două propoziții calde, oneste. */
object Bmi {
    data class Band(val label: String, val title: String, val text: String)

    fun band(bmi: Float): Band = when {
        bmi < 18.5f -> Band(
            "Subponderal", "Sub linia de jos",
            "Corpul are nevoie de mai multă rație, nu de mai puțină. Mâncatul regulat, cu proteine la fiecare masă, e primul pas."
        )
        bmi < 25f -> Band(
            "Normal", "Indice în zona bună",
            "Ești exact unde trebuie. E o bază solidă pentru tonus și pentru obiceiuri care țin."
        )
        bmi < 30f -> Band(
            "Supraponderal", "Puțin peste linie",
            "Nu e o sentință, e un punct de plecare. Câteva sute de kcal pe zi și mișcare constantă mută acul."
        )
        else -> Band(
            "Obezitate", "Peste linie",
            "Aici contează pașii mici și repetați, nu diete dure. Rația de mai jos e calculată ca să ții de ea."
        )
    }

    /** Cum descriem metabolismul din TDEE / greutate — orientativ, sec. */
    fun metabolism(t: Targets, p: BodyProfile): String {
        val perKg = if (p.weightKg > 0) t.bmr / p.weightKg else 0f
        return when {
            perKg >= 24f -> "Rapid: arde mult și în repaus"
            perKg >= 20f -> "Echilibrat: are nevoie de obiceiuri constante"
            else -> "Lent: porțiile mici contează mai mult"
        }
    }
}
