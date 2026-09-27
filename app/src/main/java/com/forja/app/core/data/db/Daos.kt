package com.forja.app.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM plans ORDER BY position")
    fun plans(): Flow<List<PlanEntity>>

    @Query("SELECT e.* FROM exercises e INNER JOIN plan_exercises pe ON pe.exerciseId = e.id WHERE pe.planId = :planId ORDER BY pe.position")
    suspend fun exercisesForPlan(planId: Int): List<ExerciseEntity>

    @Query("SELECT COUNT(*) FROM plans")
    suspend fun planCount(): Int

    @Query("UPDATE exercises SET sets = :sets, reps = :reps, load = :load WHERE id = :id")
    suspend fun updateExerciseParams(id: Int, sets: Int, reps: Int, load: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercises(items: List<ExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlans(items: List<PlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlanExercises(items: List<PlanExerciseEntity>)

    @Insert
    suspend fun insertSession(s: WorkoutSessionEntity): Long

    @Update
    suspend fun updateSession(s: WorkoutSessionEntity)

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun session(id: Long): WorkoutSessionEntity?

    @Insert
    suspend fun insertSetLog(log: SetLogEntity)

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE endedAt IS NOT NULL AND startedAt >= :since")
    fun sessionCountSince(since: Long): Flow<Int>

    @Query("SELECT * FROM workout_sessions WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT 1")
    fun lastSession(): Flow<WorkoutSessionEntity?>
}

@Dao
interface MealDao {
    @Query("SELECT * FROM meals WHERE epochDay = :day ORDER BY at")
    fun mealsForDay(day: Long): Flow<List<MealEntity>>

    @Insert
    suspend fun insert(meal: MealEntity): Long

    @Update
    suspend fun update(meal: MealEntity)

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(SUM(kcal),0) FROM meals WHERE epochDay = :day")
    fun kcalForDay(day: Long): Flow<Int>
}

@Dao
interface SleepDao {
    @Insert
    suspend fun insert(s: SleepSessionEntity): Long

    @Update
    suspend fun update(s: SleepSessionEntity)

    @Query("SELECT * FROM sleep_sessions WHERE endAt IS NULL ORDER BY startAt DESC LIMIT 1")
    fun activeSession(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions WHERE endAt IS NULL ORDER BY startAt DESC LIMIT 1")
    suspend fun activeSessionOnce(): SleepSessionEntity?

    @Query("SELECT * FROM sleep_sessions WHERE endAt IS NOT NULL ORDER BY startAt DESC LIMIT 1")
    fun lastFinished(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions WHERE endAt IS NOT NULL AND startAt >= :since ORDER BY startAt")
    fun finishedSince(since: Long): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE endAt IS NOT NULL ORDER BY startAt DESC LIMIT :n")
    fun recent(n: Int): Flow<List<SleepSessionEntity>>

    @Insert
    suspend fun insertEvent(e: SleepEventEntity): Long

    @Query("SELECT * FROM sleep_events WHERE sessionId = :sessionId ORDER BY at")
    fun eventsForSession(sessionId: Long): Flow<List<SleepEventEntity>>

    @Query("SELECT * FROM sleep_events WHERE sessionId = :sessionId")
    suspend fun eventsForSessionOnce(sessionId: Long): List<SleepEventEntity>

    @Query("DELETE FROM sleep_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(a: ActivityEntity): Long

    @Query("SELECT * FROM activities WHERE startAt >= :since ORDER BY startAt")
    fun since(since: Long): Flow<List<ActivityEntity>>

    @Query("SELECT COALESCE(SUM(distanceM),0) FROM activities WHERE startAt >= :since")
    fun distanceSince(since: Long): Flow<Double>

    @Query("SELECT * FROM activities ORDER BY startAt DESC LIMIT 1")
    fun last(): Flow<ActivityEntity?>

    @Query("SELECT * FROM activities ORDER BY startAt DESC")
    fun all(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun byId(id: Long): ActivityEntity?

    @Query("SELECT COALESCE(SUM(distanceM),0) FROM activities WHERE startAt >= :since")
    suspend fun distanceSinceOnce(since: Long): Double
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_rules ORDER BY label")
    fun rules(): Flow<List<FocusRuleEntity>>

    @Query("SELECT * FROM focus_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<FocusRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: FocusRuleEntity)

    @Query("DELETE FROM focus_rules WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface ExploreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCell(c: ExploreCellEntity)

    @Query("SELECT * FROM explore_cells WHERE id = :id")
    suspend fun cell(id: String): ExploreCellEntity?

    @Query("SELECT * FROM explore_cells WHERE maxLat >= :minLat AND minLat <= :maxLat AND maxLng >= :minLng AND minLng <= :maxLng")
    fun cellsIn(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): Flow<List<ExploreCellEntity>>

    @Query("SELECT * FROM explore_cells")
    fun allCells(): Flow<List<ExploreCellEntity>>

    @Query("SELECT COUNT(*) FROM explore_cells")
    fun countCells(): Flow<Int>

    @Insert
    suspend fun insertPlace(p: PlaceEntity): Long

    @Update
    suspend fun updatePlace(p: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deletePlace(id: Long)

    @Query("SELECT * FROM places ORDER BY lastAt DESC")
    fun places(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY lastAt DESC")
    suspend fun placesOnce(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun place(id: Long): PlaceEntity?

    @Query("SELECT * FROM places WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng LIMIT 1")
    suspend fun placeNear(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): PlaceEntity?

    @Query("SELECT * FROM explore_cells WHERE lastAt > :since")
    suspend fun cellsChangedSince(since: Long): List<ExploreCellEntity>

    @Query("SELECT * FROM places WHERE updatedAt > :since OR lastAt > :since")
    suspend fun placesChangedSince(since: Long): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE remoteId = :remoteId LIMIT 1")
    suspend fun placeByRemoteId(remoteId: String): PlaceEntity?

    @Query("DELETE FROM explore_cells")
    suspend fun clearCells()

    @Query("DELETE FROM places")
    suspend fun clearPlaces()
}
