package com.forja.app.core.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.feature.nutrition.NutritionPrefs
import com.forja.app.feature.nutrition.Targets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Ziua ta, citită o singură dată, doar de pe telefon (Room, DataStore, Firestore-ul prietenilor) — fără servere noi.
 * Tot ce lipsește rămâne gol: varianta care ar avea nevoie de el nu se alege.
 */
internal object NudgeSnapshot {
    /** Un prieten contează doar cu poziția proaspătă. */
    private const val FRIEND_FRESH_MS = 15 * 60_000L
    private const val MY_FIX_FRESH_MS = 15 * 60_000L

    fun zone(): ZoneId = ZoneId.systemDefault()
    fun dayOf(t: Long): Long = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate().toEpochDay()
    private fun startOf(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Doar ceasul și calendarul (pentru evenimente care își aduc singure datele). */
    fun clock(now: Long = System.currentTimeMillis(), voice: Voice = Voice.Camarad, name: String? = null): NudgeData {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone())
        return NudgeData(now = now, hour = z.hour, minute = z.minute, dayOfWeek = z.dayOfWeek.value, month = z.monthValue,
            name = name, voice = voice)
    }

    suspend fun basics(app: ForjaApp, now: Long = System.currentTimeMillis()): NudgeData {
        val name = try { app.prefs.cachedName.first().takeIf { it.isNotBlank() } } catch (_: Exception) { null }
        val voice = try { Voice.of(NutritionPrefs.of(app).voice.first().ordinal) } catch (_: Exception) { Voice.Camarad }
        return clock(now, voice, name)
    }

    /** Instantaneul complet al zilei. `friends` = prietenii citiți de lucrător (null = nu îi citim acum). */
    suspend fun read(app: ForjaApp, now: Long = System.currentTimeMillis(), friends: List<FriendView>? = null): NudgeData {
        val base = basics(app, now)
        val today = dayOf(now)
        val todayStart = startOf(today)
        val db = app.db

        // mișcare
        val acts = try { db.activityDao().since(startOf(today - 400)).first() } catch (_: Exception) { emptyList() }
        fun kmBetween(from: Long, to: Long) = acts.filter { it.startAt in from until to }.sumOf { it.distanceM } / 1000.0
        val kmToday = kmBetween(todayStart, Long.MAX_VALUE)
        val kmYesterday = kmBetween(startOf(today - 1), todayStart)
        val weekStart = LocalDate.ofEpochDay(today).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
            .atStartOfDay(zone()).toInstant().toEpochMilli()
        val kmWeek = kmBetween(weekStart, Long.MAX_VALUE)
        val kmAvg7 = kmBetween(startOf(today - 7), todayStart) / 7.0
        val weekTarget = try { app.prefs.weekKmTarget.first() } catch (_: Exception) { 0 }
        val walkDays = acts.map { dayOf(it.startAt) }.toSet()

        // rație
        val nutrition = NutritionPrefs.of(app)
        val meals = try { db.mealDao().mealsForDay(today).first() } catch (_: Exception) { emptyList() }
        val kcal = meals.sumOf { it.kcal }
        val target = try { Targets.of(nutrition.profile.first())?.kcal ?: nutrition.kcalTarget.first() } catch (_: Exception) { 0 }
        val kcalLeft = if (meals.isNotEmpty() && target > 0 && target - kcal >= 0) target - kcal else null
        val mealStreakNow = try { nutrition.streak().first() } catch (_: Exception) { 0 }
        val mealBroke = if (mealStreakNow == 0 && meals.isEmpty()) {
            Streaks.walk(StreakKind.Meals, today, max = 120) { d -> d < today - 1 && hasMeals(app, d) }.brokeAt
        } else 0
        val mealStreak = Streak(StreakKind.Meals, mealStreakNow, meals.isNotEmpty(), mealBroke)

        // instrucție: câte sesiuni încheiate de la începutul fiecărei zile (fără interogări noi în Room)
        val workouts = db.workoutDao()
        val last = try { workouts.lastSession().first() } catch (_: Exception) { null }
        val sinceCache = HashMap<Long, Int>()
        suspend fun countSince(d: Long): Int = sinceCache.getOrPut(d) {
            try { workouts.sessionCountSince(startOf(d)).first() } catch (_: Exception) { 0 }
        }
        val workoutStreak = Streaks.walk(StreakKind.Workout, today, max = 120) { d ->
            countSince(d) > (if (d == today) 0 else countSince(d + 1))
        }
        val workoutToday = last?.takeIf { it.endedAt != null && it.startedAt >= todayStart }

        // focus
        val forest = try { app.prefs.focusForest.first() } catch (_: Exception) { Triple(0, 0, 0) }

        // somn
        val lastSleep = try { db.sleepDao().lastFinished().first() } catch (_: Exception) { null }
        val lastNight = lastSleep?.let { s ->
            val end = s.endAt ?: return@let null
            if (now - end > 14 * 3600_000L) return@let null
            SleepView(minutes = ((end - s.startAt) / 60_000L).toInt(), deepMin = s.deepMin)
        }
        val alarmOn = try { app.prefs.alarmEnabled.first() } catch (_: Exception) { false }
        val wake = if (alarmOn) try { Ro.clock(app.prefs.alarmHour.first(), app.prefs.alarmMinute.first()) } catch (_: Exception) { null } else null

        // teren
        val explore = db.exploreDao()
        val places = try { explore.placesOnce() } catch (_: Exception) { emptyList() }
        val newToday = places.filter { it.firstAt >= todayStart }.maxByOrNull { it.firstAt }?.let {
            PlaceView(it.name, (it.stayMs / 60_000L).toInt(), it.visits, places.size)
        }

        return base.copy(
            kmToday = kmToday, kmYesterday = kmYesterday, kmWeek = kmWeek, weekTargetKm = weekTarget, kmAvg7 = kmAvg7,
            mealsToday = meals.size,
            lunchLogged = meals.any { it.mealType == 1 },
            dinnerLogged = meals.any { it.mealType == 2 },
            kcalLeft = kcalLeft,
            workoutToday = workoutToday?.planName,
            setsToday = workoutToday?.totalSets ?: 0,
            lastPlan = last?.planName,
            focusMinToday = forest.first * 15 + forest.third / 60,
            treesToday = forest.first,
            streaks = listOf(mealStreak, workoutStreak, Streaks.fromDays(StreakKind.Walk, today, walkDays)),
            lastNight = lastNight,
            wake = wake,
            sleepTracking = SleepTrackService.running,
            placesTotal = places.size,
            newPlaceToday = newToday,
            friendMoving = friends?.firstOrNull { it.state in setOf("walk", "run", "ride") }
        )
    }

    private suspend fun hasMeals(app: ForjaApp, day: Long): Boolean =
        try { app.db.mealDao().mealsForDay(day).first().isNotEmpty() } catch (_: Exception) { false }

    // ───────────── Prietenii (pentru „e la 300 m”, „e deja pe drum”) ─────────────

    /**
     * Prietenii vizibili acum, filtrați ca pe hartă și mai strict (corectura 5): fără fantomă, fără poziție venită
     * prin familie, fără starea „sleep”, doar cu poziția de < 15 min. Distanța se calculează pe telefon.
     */
    suspend fun friends(app: ForjaApp, now: Long = System.currentTimeMillis()): List<FriendView> {
        val uid = app.auth.currentUid ?: return emptyList()
        var latest: List<Friend> = emptyList()
        withTimeoutOrNull(6_000L) { app.friends.friendsFlow(uid).collect { latest = it } }
        if (latest.isEmpty()) return emptyList()
        val me = myFix(app, now)
        return latest.filter { f ->
            !f.ghost && !f.viaFamily && f.state != "sleep" && f.state != "ghost" && f.state != "off" &&
                f.lat != null && f.lng != null && now - f.locUpdatedAt in 0..FRIEND_FRESH_MS
        }.map { f ->
            val dist = me?.let { m ->
                val out = FloatArray(1)
                Location.distanceBetween(m.latitude, m.longitude, f.lat!!, f.lng!!, out)
                out[0].toInt()
            }
            FriendView(f.uid, f.name, dist, f.state)
        }
    }

    /** Ultima poziție a telefonului, doar dacă e proaspătă și permisiunea există. */
    private fun myFix(c: Context, now: Long): Location? {
        val fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
        }
        return providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .filter { now - it.time in 0..MY_FIX_FRESH_MS }
            .maxByOrNull { it.time }
    }

    /** Zile întregi între două momente, după calendar (nu după 24 h). */
    fun daysBetween(from: Long, to: Long): Long =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(from).atZone(zone()).toLocalDate(), Instant.ofEpochMilli(to).atZone(zone()).toLocalDate())
}
