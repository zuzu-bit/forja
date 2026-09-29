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
data class SetRecord(val reps: Int, val load: String, val exercise: String = "", val setNo: Int = 0, val at: Long = 0L)

/** O piesă ascultată în timpul antrenamentului (MusicHistory, PLAY). */
data class TrackRecord(val title: String, val artist: String, val at: Long)

/** O componentă a farfuriei, cum o păstrează `meals.details` (mirror, pachetul C). */
@kotlinx.serialization.Serializable
data class MealPart(val name: String, val grams: Int = 0, val kcal: Int = 0, val protein: Int = 0, val carbs: Int = 0, val fat: Int = 0)

/** `meals.details` (JSON): ce a găsit analiza — componente, scor 1–10 cu motivul, sfatul, modelul. */
@kotlinx.serialization.Serializable
data class MealDetails(val items: List<MealPart> = emptyList(), val score: Int? = null, val reason: String? = null, val tip: String? = null, val model: String? = null)

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
    /** Versiunea documentelor de antrenament; o versiune nouă face completarea de 60 de zile să retrimită tot. */
    const val WORKOUT_PAYLOAD = 2
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /** Id-urile stabile din Firestore (mirror): din momentul jurnalului, nu din id-ul Room (care o ia de la 1 după o reinstalare). */
    fun mealCloudId(at: Long): String = "m-$at"
    fun activityCloudId(startAt: Long): String = "a-$startAt"
    fun workoutCloudId(startedAt: Long): String = "w-$startedAt"

    fun encodeDetails(d: MealDetails): String = json.encodeToString(MealDetails.serializer(), d)
    fun decodeDetails(raw: String?): MealDetails? = if (raw.isNullOrBlank()) null else try { json.decodeFromString(MealDetails.serializer(), raw) } catch (_: Exception) { null }

    /**
     * users/{uid}/meals/{cloudId}: jurnalul mesei plus, când există, `items` (≤ 12), `score` {value 1–10, reason}, `tip`
     * și `photo` (poza e pe serverul site-ului, contract v4). Nimic din profilul corpului.
     */
    fun mealDoc(name: String, kcal: Int, protein: Int, carbs: Int, fat: Int, grams: Int, mealType: Int, epochDay: Long, source: String, confidence: String,
                at: Long, details: String?, photo: Boolean?): Map<String, Any?> {
        val d = decodeDetails(details)
        val out = linkedMapOf<String, Any?>(
            "name" to name, "kcal" to kcal, "protein" to protein, "carbs" to carbs, "fat" to fat, "grams" to grams, "mealType" to mealType,
            "epochDay" to epochDay, "source" to source, "confidence" to confidence, "at" to at
        )
        if (d != null) {
            if (d.items.isNotEmpty()) out["items"] = d.items.take(12).map { mapOf("name" to it.name.take(80), "grams" to it.grams, "kcal" to it.kcal, "protein" to it.protein, "carbs" to it.carbs, "fat" to it.fat) }
            if (d.score != null && d.score in 1..10) out["score"] = mapOf("value" to d.score, "reason" to d.reason?.take(200))
            d.tip?.takeIf { it.isNotBlank() }?.let { out["tip"] = it.take(300) }
        }
        if (photo != null) out["photo"] = photo
        return out
    }

    /** Exercițiile, în ordinea în care au fost lucrate, fiecare cu seriile lui (repetări, greutatea scrisă, kg când e număr). */
    fun exercises(sets: List<SetRecord>): List<Map<String, Any?>> =
        sets.filter { it.exercise.isNotBlank() }.sortedWith(compareBy<SetRecord> { it.at }.thenBy { it.setNo })
            .groupBy { it.exercise }.entries.take(30)
            .map { (name, list) -> mapOf("name" to name.take(80), "sets" to list.take(20).map { mapOf("reps" to it.reps, "load" to it.load.take(20), "kg" to loadKg(it.load), "at" to it.at) }) }

    /**
     * Payload v2: tot ce era în v1, plus `completed` (seriile făcute ≥ cele din plan), `plannedSets`, `exercises` și
     * `music` (≤ 30 piese pornite între început și sfârșit). Fără plan (intervalele), `completed` lipsește.
     */
    fun workoutV2(w: WorkoutRecord, sets: List<SetRecord>, plannedSets: Int?, music: List<TrackRecord>): Map<String, Any?> {
        val out = LinkedHashMap(workout(w, sets))
        val done = out["sets"] as Int
        if (plannedSets != null && plannedSets > 0) { out["plannedSets"] = plannedSets; out["completed"] = done >= plannedSets }
        val ex = exercises(sets)
        if (ex.isNotEmpty()) out["exercises"] = ex
        val m = music.filter { it.at in w.startedAt..w.endedAt && it.title.isNotBlank() }.sortedBy { it.at }.take(30)
        if (m.isNotEmpty()) out["music"] = m.map { mapOf("title" to it.title.take(120), "artist" to it.artist.take(120), "at" to it.at) }
        out["payload"] = WORKOUT_PAYLOAD
        return out
    }

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
