package com.forja.app.core.notify

import java.time.LocalDateTime
import java.time.ZoneId

/** Date false pentru testele Cascăi (JVM pur): un moment ales și o zi cu de toate. */
object NudgeFixtures {
    val zone: ZoneId = ZoneId.systemDefault()

    fun at(hour: Int, minute: Int = 0, day: Int = 14, month: Int = 10, year: Int = 2026): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Doar ceasul (fără date personale). 14 octombrie 2026 = miercuri. */
    fun clock(hour: Int, minute: Int = 0, dayOfWeek: Int = 3, month: Int = 10): NudgeData =
        NudgeData(now = at(hour, minute), hour = hour, minute = minute, dayOfWeek = dayOfWeek, month = month)

    /** O zi plină: nume, km, mese, serii, somn, focus, un loc nou. */
    fun rich(hour: Int, minute: Int = 0): NudgeData = clock(hour, minute).copy(
        name = "Lana Popescu",
        kmToday = 3.4, kmYesterday = 6.2, kmWeek = 14.0, weekTargetKm = 29, kmAvg7 = 2.1,
        mealsToday = 2, lunchLogged = true, dinnerLogged = false, kcalLeft = 640,
        workoutToday = "Forță · Ziua A", setsToday = 18, lastPlan = "Forță · Ziua A",
        focusMinToday = 45, treesToday = 3,
        streaks = listOf(
            Streak(StreakKind.Meals, 12, doneToday = true),
            Streak(StreakKind.Workout, 4, doneToday = true),
            Streak(StreakKind.Walk, 2, doneToday = true)
        ),
        lastNight = SleepView(minutes = 452, deepMin = 70),
        wake = "07:30",
        placesTotal = 25,
        newPlaceToday = PlaceView("Parcul Tineretului", 95, 1, 25)
    )

    /** Valori lungi, realiste (portul check_bank.py): dacă încap acestea, încap toate. */
    val longFill: Map<String, Value> = mapOf(
        "nume" to Value("Alexandrinaa"),
        "km" to Value("12,4"), "km_ieri" to Value("12,4"), "km_sapt" to Value("120"), "km_medie" to Value("12,4"),
        "ramas" to Value("120", 120), "kcal_ramase" to Value("1 250", 1250), "mese_azi" to Value("4", 4),
        "bilant" to Value("12,4 km, 4 mese, un antrenament, 120 min de focus"),
        "antrenament" to Value("Forță completă · A45"), "plan" to Value("Forță completă · A45"),
        "seturi" to Value("24", 24), "focus_min" to Value("120", 120), "copaci" to Value("12", 12),
        "focus_sesiune" to Value("120", 120), "copaci_noi" to Value("12", 12),
        "serie_zile" to Value("100", 100), "tip_serie" to Value("de instrucție"), "serie_risc" to Value("100", 100),
        "serie_mese" to Value("100", 100), "serie_mers" to Value("100", 100), "serie_antrenament" to Value("100", 100),
        "serie_veche" to Value("100", 100), "serie_prag" to Value("100", 100), "record_vechi" to Value("100", 100),
        "somn_h" to Value("10 h 40 min"), "profund" to Value("2 h 40 min"), "acoperire" to Value("6 h 10 min din 10 h 40 min"),
        "evenimente" to Value("12", 12), "ora_trezire" to Value("07:30"), "min_stingere" to Value("30", 30), "ora" to Value("14:05"),
        "locuri" to Value("1 250", 1250), "loc" to Value("Parcul Herăstrău N"), "ore_stat" to Value("10 h 20 min"),
        "vizite" to Value("12", 12), "prieten" to Value("Alexandrinaa"), "distanta" to Value("900 m"),
        "stare_prieten" to Value("Pe bicicletă"), "mese_gasite" to Value("12", 12), "zile_absent" to Value("12", 12),
        "categorii" to Value("locație, aplicații, fotografii, fișiere alese, microfon")
    )
}
