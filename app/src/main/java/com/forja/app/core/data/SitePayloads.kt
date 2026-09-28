package com.forja.app.core.data

import com.forja.app.feature.nutrition.Targets
import kotlin.math.roundToLong

/** Un antrenament terminat, citit din Room (`workout_sessions` + `plans.meta`). */
data class WorkoutRecord(
    val id: Long,
    val planId: Int,
    val planName: String,
    /** „FORȚĂ · 45 MIN · SALĂ”; null pentru antrenamentele fără plan (intervalele din Inventar). */
    val planMeta: String?,
    val startedAt: Long,
    val endedAt: Long,
    val totalSets: Int
)

/** O serie din `set_logs`: repetări și greutatea așa cum a fost scrisă („62,5”, „2×14”, „corp”). */
data class SetRecord(val reps: Int, val load: String)

/** Rația zilnică pe site: exact ce arată inelele din Rație (kcal + P/C/G în grame). */
data class SiteTargets(val kcal: Int, val protein: Int, val carbs: Int, val fat: Int) {
    /** Amprenta pentru „s-a schimbat ceva de la ultima trimitere”. */
    val signature: String get() = "$kcal|$protein|$carbs|$fat"
}

/**
 * Documentele pe care telefonul le scrie pentru site (DESIGN-4.4 §3.3) — logică pură, testată în SitePayloadsTest.
 *
 * users/{uid}/workouts/w{id}: `{ startAt, endAt, durationS, title, kind, sets, volumeKg|null, kcal|null, source }`
 *   kind ∈ forta · acasa · intervale; source ∈ instructie (Instrucție) · asteptare („Cât aștepți” din Inventar).
 *   kcal rămâne null: telefonul nu măsoară caloriile unui antrenament de forță și nu inventăm cifre.
 * users/{uid}/settings/targets: `{ kcal, protein, carbs, fat, updatedAt }` — niciodată profilul corpului.
 */
object SitePayloads {
    const val SOURCE_TRAINING = "instructie"
    const val SOURCE_WAITING = "asteptare"
    const val KIND_STRENGTH = "forta"
    const val KIND_HOME = "acasa"
    const val KIND_INTERVALS = "intervale"

    fun workout(w: WorkoutRecord, sets: List<SetRecord>): Map<String, Any?> = mapOf(
        "startAt" to w.startedAt,
        "endAt" to w.endedAt,
        "durationS" to ((w.endedAt - w.startedAt) / 1000L).coerceAtLeast(0L),
        "title" to w.planName.trim().ifBlank { "Antrenament" }.take(80),
        "kind" to kind(w),
        "sets" to (if (w.totalSets > 0) w.totalSets else sets.size),
        "volumeKg" to volumeKg(sets),
        "kcal" to null,
        "source" to source(w)
    )

    /**
     * planId negativ = intervalele Sport din „Cât aștepți” (4.3, -1 - starea aleasă). 4.4 a scos Sport din Inventar, deci
     * `asteptare` / `intervale` apar doar în completarea de 60 de zile a sesiunilor vechi.
     */
    fun source(w: WorkoutRecord): String = if (w.planId < 0) SOURCE_WAITING else SOURCE_TRAINING

    fun kind(w: WorkoutRecord): String {
        if (w.planId < 0) return KIND_INTERVALS
        val text = ((w.planMeta ?: "") + " " + w.planName).uppercase()
        return if ("FĂRĂ ECHIPAMENT" in text || "FARA ECHIPAMENT" in text || "ACASĂ" in text || "ACASA" in text) KIND_HOME
        else KIND_STRENGTH
    }

    /**
     * Kilogramele unei repetări din textul scris în plan: „62,5” → 62,5; „2×14” (două gantere) → 28; „20 kg” → 20.
     * „corp” (greutatea corpului) sau orice alt text → null: nu intră în volum.
     */
    fun loadKg(load: String): Double? {
        val s = load.trim().lowercase().replace(',', '.').replace("kg", "").trim()
        if (s.isEmpty()) return null
        val pair = Regex("^(\\d{1,2})\\s*[×x*]\\s*(\\d{1,3}(?:\\.\\d+)?)$").find(s)
        val kg = if (pair != null) {
            pair.groupValues[1].toDouble() * pair.groupValues[2].toDouble()
        } else {
            s.toDoubleOrNull()
        }
        return kg?.takeIf { it > 0.0 && it < 1000.0 }
    }

    /** Volumul = Σ repetări × kg, rotunjit la 0,1 kg; null când nicio serie nu are o greutate în kilograme. */
    fun volumeKg(sets: List<SetRecord>): Double? {
        var any = false
        var sum = 0.0
        for (s in sets) {
            if (s.reps <= 0) continue
            val kg = loadKg(s.load) ?: continue
            any = true
            sum += kg * s.reps
        }
        return if (any) (sum * 10.0).roundToLong() / 10.0 else null
    }

    /**
     * Rația ca în ecranul Rație: din profilul corpului când e complet, altfel obiectivul de kcal ales (implicit 2 000)
     * cu macronutrienții împărțiți 25 % proteine · 45 % carbohidrați · 30 % grăsimi (NutritionScreen, inelele zilei).
     */
    fun targets(fromProfile: Targets?, manualKcal: Int): SiteTargets {
        val kcal = fromProfile?.kcal ?: manualKcal
        return SiteTargets(
            kcal = kcal,
            protein = fromProfile?.protein ?: (kcal * 0.25 / 4).toInt().coerceAtLeast(1),
            carbs = fromProfile?.carbs ?: (kcal * 0.45 / 4).toInt().coerceAtLeast(1),
            fat = fromProfile?.fat ?: (kcal * 0.30 / 9).toInt().coerceAtLeast(1)
        )
    }

    fun targetsDoc(t: SiteTargets, updatedAt: Long): Map<String, Any> = mapOf(
        "kcal" to t.kcal,
        "protein" to t.protein,
        "carbs" to t.carbs,
        "fat" to t.fat,
        "updatedAt" to updatedAt
    )

    /**
     * Ce antrenamente pleacă la o trecere: terminate după ultimul trimis (`endedAt > since`) și începute în ultimele
     * [horizonMs] (prima trecere = completarea ultimelor 60 de zile). Întoarce și noul ceas.
     */
    fun workoutsToSend(all: List<WorkoutRecord>, since: Long, now: Long, horizonMs: Long): Pair<List<WorkoutRecord>, Long> {
        val cutoff = now - horizonMs
        val out = all.filter { it.endedAt > since && it.startedAt >= cutoff && it.endedAt >= it.startedAt }
            .sortedBy { it.endedAt }
        return out to (out.maxOfOrNull { it.endedAt } ?: since).coerceAtLeast(since)
    }
}
