package com.forja.app.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val sets: Int,
    val reps: Int,
    val load: String,        // „62,5" · „corp" · „2×14"
    val loadLabel: String,   // „KG" · „SARCINĂ"
    val videoFront: String,
    val videoSide: String,
    val thumb: String
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val meta: String,        // „FORȚĂ · 45 MIN · SALĂ"
    val cover: String,
    val position: Int
)

@Entity(tableName = "plan_exercises", primaryKeys = ["planId", "position"])
data class PlanExerciseEntity(
    val planId: Int,
    val position: Int,
    val exerciseId: Int
)

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Int,
    val planName: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val totalSets: Int = 0
)

@Entity(tableName = "set_logs")
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Int,
    val exerciseName: String,
    val setNo: Int,
    val reps: Int,
    val load: String,
    val at: Long
)

@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,      // ziua locală
    val mealType: Int,       // 0 mic dejun · 1 prânz · 2 cină · 3 gustare
    val name: String,
    val kcal: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val grams: Int,
    val source: String,      // „EXACT · COD DE BARE" · „ESTIMAT" · „MANUAL"
    val confidence: String,  // „ridicată" · „medie" · „—"
    val at: Long,
    val confirmed: Boolean = true,
    val barcode: String? = null,
    val photoPath: String? = null    // miniatura mesei, salvată în aplicație
)

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long? = null,
    val movements: Int = 0,
    val score: Int = 0,
    val deepMin: Int = 0,
    val lightMin: Int = 0,
    val remMin: Int = 0,
    val phases: String = "", // hipnogramă: „startMin,endMin,tip;…" (deep|light|rem|awake)
    val summary: String = "",        // rezumatul de dimineață (AI, două propoziții)
    val recordedUntil: Long = 0L     // înregistrarea completă e disponibilă până la…
)

/** Eveniment de somn detectat local: sforăit / vorbit / sunet / mișcare, cu clip de 5s. */
@Entity(tableName = "sleep_events")
data class SleepEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String,        // snore · talk · sound · move
    val at: Long,
    val durationS: Int,
    val intensity: Int,      // 1 Redus · 2 Moderat · 3 Puternic
    val clipPath: String? = null,
    val transcript: String? = null   // ce a auzit Whisper, dacă a fost vorbire
)

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val distanceM: Double,
    val durationS: Long,
    val kcal: Int,
    val polyline: String,    // „lat,lng;lat,lng;…"
    val type: String = "run" // run · walk · ride
)

@Entity(tableName = "focus_rules")
data class FocusRuleEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val untilHour: Int,      // blocat până la ora HH:MM azi
    val untilMinute: Int,
    val enabled: Boolean = true
)
