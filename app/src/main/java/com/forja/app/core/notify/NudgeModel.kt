package com.forja.app.core.notify

/*
 * Modelul mesajelor „Casca” (notifications-design.md §H) — obiecte pure, fără Android, testate pe JVM.
 * Contextul spune CÂND vorbește Casca, șablonul spune CE spune, NudgeData e ziua ta citită o singură dată.
 */

/** Momentul sau evenimentul la care vorbește Casca. */
enum class NudgeContext {
    Morning, Midday, Evening, StreakRisk, Milestone, Comeback, MealLog, FocusDone,
    SyncOngoing, SyncStopped, NewPlace, FriendNear, NewFriend, SleepReport, Bedtime, Permission;

    /** Mesajele „antrenorului” (plafon 3/zi, ≥ 3 h între ele, auto-reglare). Restul sunt evenimente sau informație. */
    val coach: Boolean get() = this in COACH

    companion object {
        val COACH = setOf(Morning, Midday, Evening, StreakRisk, Comeback, Permission)
    }
}

/** Poza mascotei (aceleași nume ca MascotState; „Angry” doar la seria de instrucție, vocea Sergent). */
enum class NudgePose { Happy, Talking, Thinking, Sorry, Wink, Angry }

/** Vocea aleasă în Rație (NutritionPrefs.voice) — schimbă replica doar unde se schimbă sensul. */
enum class Voice { Sergent, Camarad, Antrenor, Ghid;
    companion object { fun of(ordinal: Int): Voice = entries.getOrElse(ordinal) { Camarad } }
}

/** Ce se întâmplă cu un context în orele de liniște (22:00–08:00). */
enum class QuietPolicy {
    /** Trece (doar reminderul de culcare; alarma nu e un nudge). */
    Pass,
    /** Rezultatul a ceva pornit de tine (raportul nopții, Focus): ajunge, dar fără sunet și fără vibrație. */
    Silent,
    /** Se păstrează și pleacă după 08:00 (camarad nou). */
    Hold,
    /** Nu pleacă deloc. */
    Drop
}

/** Felul seriei — eticheta intră în text: „Șapte zile de instrucție”. */
enum class StreakKind(val label: String) { Workout("de instrucție"), Meals("de rație"), Walk("de mers") }

/** O serie: câte zile la rând, dacă azi e deja bifată și, dacă s-a rupt ieri, la cât s-a oprit. */
data class Streak(val kind: StreakKind, val current: Int, val doneToday: Boolean, val brokeAt: Int = 0)

/** Un prieten deja filtrat: nu e fantomă, nu vine prin familie, nu doarme, poziție de < 15 min. */
data class FriendView(val uid: String, val name: String, val distanceM: Int?, val state: String?)

/** Un loc (PlaceEntity) văzut de mesaje. */
data class PlaceView(val name: String, val stayMin: Int, val visits: Int, val total: Int, val revisit: Boolean = false)

/** Noaptea (sesiunea de somn + cronologia de pe server). Totul e estimat. */
data class SleepView(
    val minutes: Int,
    val deepMin: Int = 0,
    val coverageMin: Int = 0,
    val totalMin: Int = 0,
    val events: Int = 0,
    val bestOfWeek: Boolean = false
)

/**
 * Ziua ta, citită o singură dată (NudgeSnapshot) sau venită cu un eveniment. Câmpurile goale/0 înseamnă „nu știm”:
 * o variantă care ar avea nevoie de ele nu se alege (regula de aur, §C).
 */
data class NudgeData(
    val now: Long,
    val hour: Int,
    val minute: Int,
    /** ISO: 1 = luni … 7 = duminică. */
    val dayOfWeek: Int,
    val month: Int,
    val name: String? = null,
    val voice: Voice = Voice.Camarad,
    // mișcare
    val kmToday: Double = 0.0,
    val kmYesterday: Double = 0.0,
    val kmWeek: Double = 0.0,
    val weekTargetKm: Int = 0,
    val kmAvg7: Double = 0.0,
    // rație
    val mealsToday: Int = 0,
    val lunchLogged: Boolean = false,
    val dinnerLogged: Boolean = false,
    /** Kcal rămase azi (doar ≥ 0 și cu ≥ 1 masă notată; niciodată „ai depășit”). */
    val kcalLeft: Int? = null,
    // instrucție
    val workoutToday: String? = null,
    val setsToday: Int = 0,
    val lastPlan: String? = null,
    // focus
    val focusMinToday: Int = 0,
    val treesToday: Int = 0,
    // serii
    val streaks: List<Streak> = emptyList(),
    // somn
    val lastNight: SleepView? = null,
    /** „07:30” — doar dacă alarma e setată. */
    val wake: String? = null,
    val minutesToBedtime: Int? = null,
    val sleepTracking: Boolean = false,
    // teren
    val placesTotal: Int = 0,
    val newPlaceToday: PlaceView? = null,
    val friendMoving: FriendView? = null,
    // revenire
    val absentDays: Int = 0,
    val comebackStep: Int = 0,
    val oldStreak: Int = 0,
    // sincronizare
    val categories: String? = null,
    // evenimente
    val place: PlaceView? = null,
    val friend: FriendView? = null,
    val sleep: SleepView? = null,
    val focusSessionMin: Int = 0,
    val focusSessionTrees: Int = 0,
    val mealsFound: Int = 0,
    val milestone: Streak? = null,
    val recordOld: Int = 0,
    val permission: String? = null
) {
    /** Cea mai lungă serie activă (≥ 1). */
    val longest: Streak? get() = streaks.filter { it.current > 0 }.maxByOrNull { it.current }

    /** Seria în pericol: cea mai lungă serie ≥ 3 care azi nu e bifată. */
    val risk: Streak? get() = streaks.filter { it.current >= 3 && !it.doneToday }.maxByOrNull { it.current }

    /** O serie ≥ 3 care s-a rupt ieri (cea mai lungă). */
    val broke: Streak? get() = streaks.filter { it.brokeAt >= 3 }.maxByOrNull { it.brokeAt }

    fun streak(kind: StreakKind): Streak? = streaks.firstOrNull { it.kind == kind }

    /** Azi nu s-a notat nimic: nici km, nici masă, nici instrucție, nici focus. */
    val emptyDay: Boolean get() = kmToday < 0.1 && mealsToday == 0 && workoutToday == null && focusMinToday == 0

    val isWeekend: Boolean get() = dayOfWeek >= 6
    val isWinter: Boolean get() = month == 12 || month <= 2
    val isSummer: Boolean get() = month in 6..8
}

/** Un placeholder completat: textul și, pentru numere, valoarea (pluralul, ordinalele). */
data class Value(val text: String, val n: Long? = null)

/**
 * Un șablon din bancă. Placeholderele (`{km}`, `{serie_zile|zi|zile}`, `{locuri@m}`) se citesc din text:
 * dacă lipsește unul, varianta nu se alege. `reserve` = variantă fără date, aleasă doar când nu merge nimic personal.
 */
data class Template(
    val id: String,
    val context: NudgeContext,
    val title: String,
    val body: String,
    val pose: NudgePose,
    val reserve: Boolean = false,
    val voices: Map<Voice, String> = emptyMap(),
    val cond: (NudgeData) -> Boolean = { true }
) {
    val placeholders: Set<String> get() = Nudge.keysOf(title) + Nudge.keysOf(body) + voices.values.flatMap { Nudge.keysOf(it) }
}

/** Mesajul gata de afișat. */
data class Rendered(
    val id: String,
    val context: NudgeContext,
    val title: String,
    val body: String,
    val pose: NudgePose,
    /** Are date personale: pe ecranul de blocare apare versiunea publică. */
    val private: Boolean,
    /** Somn, prieteni, kcal: nu se oglindește pe ceas (Galaxy Watch). */
    val localOnly: Boolean
)
