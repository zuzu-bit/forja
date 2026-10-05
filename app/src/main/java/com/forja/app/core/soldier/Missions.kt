package com.forja.app.core.soldier

import com.forja.app.ForjaApp
import com.forja.app.core.util.Fmt
import com.forja.app.navigation.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** O misiune zilnică: ce bifezi în FORJA ca să câștigi puncte. [route] = unde se face (null = se face singură). */
data class Mission(val id: String, val title: String, val short: String, val points: Int, val route: String?)

/** Starea unei misiuni azi. */
data class MissionStatus(val mission: Mission, val done: Boolean)

/**
 * Misiunile de azi, calculate din ce e deja în jurnalele FORJA (fără nicio bifă manuală): o masă în jurnal, o tură,
 * un antrenament încheiat, somnul înregistrat, focusul, respirația, detoxul ținut, un loc nou, un joc câștigat,
 * o comandă „Hei FORJA”, prezența, energia trimisă unui prieten. Punctele se dau o singură dată pe zi, pe misiune.
 */
object Missions {
    /** Câte misiuni fac o „zi bună” (seria). */
    const val GOOD_DAY = 3
    /** Zilele păstrate în stare. */
    private const val KEEP_DAYS = 10L
    private const val DAY_MS = 24 * 3_600_000L
    /** Bonusul seriei: la fiecare 7 zile bune la rând. */
    const val STREAK_BONUS = 50
    const val STREAK_ID = "streak7"

    val all: List<Mission> = listOf(
        Mission("present", "Prezent la apel", "Prezent", 5, null),
        Mission("meal", "O masă în jurnal", "Masă", 10, Route.NUTRITION),
        Mission("meals3", "Rație completă: trei mese", "3 mese", 10, Route.NUTRITION),
        Mission("move", "O tură de cel puțin un kilometru", "1 km", 15, Route.MAP),
        Mission("move5", "Cinci kilometri într-o zi", "5 km", 10, Route.MAP),
        Mission("workout", "Un antrenament încheiat", "Antrenament", 20, Route.WORKOUT),
        Mission("sleep", "Noaptea înregistrată", "Somn", 15, Route.SLEEP),
        Mission("focus", "Cincisprezece minute de focus", "Focus", 10, Route.FOCUS),
        Mission("breath", "Un minut de respirație", "Respiră", 5, Route.BREATH),
        Mission("detox", "Paznicul Detox ținut", "Detox", 5, Route.FOCUS),
        Mission("place", "Un loc nou pe hartă", "Loc nou", 10, Route.MAP),
        Mission("game", "Un joc câștigat", "Joc", 5, Route.WAIT_ZID),
        Mission("voice", "O comandă „Hei FORJA”", "Voce", 5, Route.VOICE),
        Mission("energy", "Energie trimisă unui prieten", "Energie", 5, Route.MAP)
    )
    val byId: Map<String, Mission> = all.associateBy { it.id }
    val maxPerDay: Int = all.sumOf { it.points }

    /** Ziua (epochDay) a clipei [now], după fusul telefonului. */
    fun dayOf(now: Long): Long = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    private fun dayStartMillis(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Ce e bifat în ziua lui [now], din jurnale. [foreground] = FORJA e pe ecran acum (prezența). */
    suspend fun evaluate(app: ForjaApp, now: Long = System.currentTimeMillis(), foreground: Boolean = false): List<MissionStatus> {
        val day = dayOf(now)
        val dayStart = dayStartMillis(day)
        val dayEnd = dayStartMillis(day + 1)
        fun inDay(t: Long?) = t != null && t >= dayStart && t < dayEnd
        val db = app.db
        val prefs = app.prefs
        suspend fun <T> safe(default: T, block: suspend () -> T): T = try { withTimeoutOrNull(4000) { block() } ?: default } catch (_: Exception) { default }

        val meals = safe(0) { db.mealDao().mealsForDay(day).first().size }
        // turele: și cea începută aseară și încheiată după miezul nopții (rândul apare abia la final) — în ziua încheierii
        val km = safe(0.0) { db.activityDao().since(dayStart - DAY_MS).first().filter { inDay(it.startAt) || inDay(it.endAt) }.sumOf { it.distanceM } / 1000.0 }
        // un antrenament încheiat azi (pornit azi sau terminat azi — și cel început aseară contează în ziua în care s-a încheiat)
        val workout = safe(false) { db.workoutDao().lastSession().first()?.let { it.endedAt != null && (inDay(it.startedAt) || inDay(it.endedAt)) } ?: false }
        val sleep = safe(false) { db.sleepDao().lastFinished().first()?.let { inDay(it.endAt) } ?: false }
        val focusMin = safe(0) { prefs.focusForest.first().let { it.first * 15 + it.third / 60 } }
        val breath = safe(false) { db.breathSessionDao().since(dayStart).first().any { it.durationS >= 60 && it.startAt < dayEnd } }
        val detox = safe(false) { prefs.detoxOn.first() }
        // un loc nou: apare după pragul de ședere (firstAt = începutul șederii) — cel început aseară contează în ziua în care a atins pragul
        val placeThresholdMs = safe(300L) { prefs.placeThresholdMin.first().coerceAtLeast(1).toLong() } * 60_000L
        val place = safe(false) { db.exploreDao().places().first().any { inDay(it.firstAt) || (it.firstAt < dayStart && inDay(it.firstAt + placeThresholdMs)) } }
        val game = safe(false) { db.gamePlayDao().since(dayStart).first().any { it.outcome == "won" && it.at < dayEnd } }
        val voice = safe(false) { prefs.voiceUsedDay.first() == day }
        val present = foreground || safe(false) { prefs.presentDay.first() == day }
        val energy = safe(false) { prefs.energySentDay.first() == day }

        val done = mapOf(
            "present" to present, "meal" to (meals >= 1), "meals3" to (meals >= 3), "move" to (km >= 1.0), "move5" to (km >= 5.0),
            "workout" to workout, "sleep" to sleep, "focus" to (focusMin >= 15), "breath" to breath, "detox" to detox,
            "place" to place, "game" to game, "voice" to voice, "energy" to energy
        )
        return all.map { MissionStatus(it, done[it.id] == true) }
    }

    /**
     * Rezultatul unei sincronizări: punctele noi de azi, misiunile proaspăt bifate, dacă a apărut un grad nou și
     * bonusul de serie, dacă s-a dat acum.
     */
    data class Sync(val state: SoldierState, val today: List<MissionStatus>, val newPoints: Int, val newlyDone: List<Mission>, val promoted: Rank?, val streakBonus: Boolean)

    /** Bifează ce e nou, dă punctele, ține seria, împarte darurile gradelor noi. Sigur de apelat oricând (idempotent pe zi). */
    suspend fun sync(app: ForjaApp, now: Long = System.currentTimeMillis(), foreground: Boolean = false): Sync {
        SoldierStore.load(app)
        val today = evaluate(app, now, foreground)
        val day = dayOf(now)
        var adv: Advance? = null
        val next = SoldierStore.update(app) { s -> advance(s, day, today).also { adv = it }.state }
        val a = adv ?: return Sync(next, today, 0, emptyList(), null, false)
        return Sync(next, today, a.newPoints, a.newly, a.promoted, a.bonus)
    }

    /** Ce a schimbat o zi evaluată: starea nouă, punctele și misiunile noi, gradul nou (dacă e), bonusul seriei (dacă s-a dat). */
    class Advance(val state: SoldierState, val newPoints: Int, val newly: List<Mission>, val promoted: Rank?, val bonus: Boolean)

    /**
     * Partea pură a sincronizării: starea [s0] după ziua [day] cu misiunile [today] (bifate sau nu). Punctele se dau o
     * singură dată pe zi, pe misiune; seria e un contor ([SoldierState.goodRun] până la [SoldierState.goodRunDay]).
     */
    fun advance(s0: SoldierState, day: Long, today: List<MissionStatus>): Advance {
        // Zile din viitor (ceasul a stat dat înainte o vreme, apoi s-a îndreptat): nu-s de încredere — le lăsăm,
        // altfel ar bloca tot ce urmează. O zi în urmă (fus orar, miezul nopții) e în regulă: `already` ne apără de dubluri.
        val bogus = s0.days.keys.filter { it > day + 1 }
        val s = if (bogus.isEmpty()) s0 else s0.copy(
            days = s0.days - bogus.toSet(),
            goodRun = if (s0.goodRunDay > day + 1) 0 else s0.goodRun,
            goodRunDay = if (s0.goodRunDay > day + 1) 0L else s0.goodRunDay
        )
        val before = s.rank
        val already = s.doneOn(day)
        val newly = today.filter { it.done && it.mission.id !in already }.map { it.mission }
        val newPoints = newly.sumOf { it.points }
        var todaySet = already + newly.map { it.id }
        var earned = s.earned + newPoints
        var balance = s.balance + newPoints
        // Seria: zilele bune la rând, ținută ca un contor ([goodRun], până la [goodRunDay]) — nu depinde de câte
        // zile păstrăm în [days]. Azi intră în serie când ajunge la prag; o zi fără prag o rupe.
        var run = s.goodRun
        var runDay = s.goodRunDay
        if (runDay == 0L && s.streak > 0) {
            // seria din 5.0 (numărată din zilele păstrate): o preluăm dacă e încă vie
            val lastGood = listOf(day, day - 1).firstOrNull { d -> countMissions(s.days[d] ?: emptySet()) >= GOOD_DAY }
            if (lastGood != null) { run = s.streak; runDay = lastGood }
        }
        var bonus = false
        val todayGood = countMissions(todaySet) >= GOOD_DAY
        if (todayGood && runDay < day) {
            run = (if (runDay == day - 1) run else 0) + 1
            runDay = day
            if (run % 7 == 0 && STREAK_ID !in todaySet) {
                bonus = true
                todaySet = todaySet + STREAK_ID
                earned += STREAK_BONUS; balance += STREAK_BONUS
            }
        }
        // (runDay == day + 1: o zi „din viitor” încă vie, după un fus orar — seria rămâne cum e)
        val streak = if (runDay in (day - 1)..(day + 1)) run else 0
        val days = (s.days + (day to todaySet)).filterKeys { it >= day - KEEP_DAYS }
        val withDays = s.copy(earned = earned, balance = balance, days = days, streak = streak, goodRun = run, goodRunDay = runDay)
        val gifted = SoldierStore.grantGifts(withDays)
        return Advance(gifted, newPoints, newly, if (gifted.rank.index > before.index) gifted.rank else null, bonus)
    }

    /** Câte misiuni adevărate (fără bonusuri) are o zi. */
    fun countMissions(ids: Set<String>): Int = ids.count { it in byId }

    /** Punctele bifate într-o zi (cu bonusul seriei, dacă s-a dat atunci). */
    fun pointsOn(s: SoldierState, day: Long = Fmt.epochDay()): Int = s.doneOn(day).sumOf { byId[it]?.points ?: if (it == STREAK_ID) STREAK_BONUS else 0 }
}
