package com.forja.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExerciseEntity::class, PlanEntity::class, PlanExerciseEntity::class,
        WorkoutSessionEntity::class, SetLogEntity::class,
        MealEntity::class, SleepSessionEntity::class, SleepEventEntity::class,
        ActivityEntity::class, FocusRuleEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ForjaDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun mealDao(): MealDao
    abstract fun sleepDao(): SleepDao
    abstract fun activityDao(): ActivityDao
    abstract fun focusDao(): FocusDao

    companion object {
        @Volatile private var instance: ForjaDatabase? = null

        fun get(context: Context): ForjaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForjaDatabase::class.java,
                    "forja.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}

/** Datele de start — exercițiile și planurile exacte din prototip (asset-uri Adobe Stock preview). */
object Seed {
    private const val V = "https://v.ftcdn.net"
    private const val T3 = "https://t3.ftcdn.net/jpg"
    private const val T4 = "https://t4.ftcdn.net/jpg"

    val exercises = listOf(
        ExerciseEntity(0, "Genuflexiuni cu haltera", 5, 8, "62,5", "KG",
            "$V/08/40/97/72/700_F_840977260_48ywqt97BN3HcGoVsV1anWp7Asm8Lfnj_ST.mp4",
            "$V/05/22/22/89/700_F_522228989_OjVMRHbNianFnKbbuPwcX0EVj9788vhz_ST.mp4",
            "$T3/08/40/97/72/240_F_840977260_48ywqt97BN3HcGoVsV1anWp7Asm8Lfnj.jpg"),
        ExerciseEntity(1, "Ramat cu haltera", 4, 10, "40", "KG",
            "$V/03/33/72/86/700_F_333728678_D5Ad2fxZ2vF43nRfgALGOaTBSJTCBP8E_ST.mp4",
            "$V/05/22/22/89/700_F_522228989_OjVMRHbNianFnKbbuPwcX0EVj9788vhz_ST.mp4",
            "$T3/03/33/72/86/240_F_333728678_D5Ad2fxZ2vF43nRfgALGOaTBSJTCBP8E.jpg"),
        ExerciseEntity(2, "Flotări cu gantere", 4, 12, "corp", "SARCINĂ",
            "$V/02/04/23/24/700_F_204232438_61XkO3nVRtQq0PZm9fmmPL75ylFgF7wB_ST.mp4",
            "$V/05/22/22/89/700_F_522228989_OjVMRHbNianFnKbbuPwcX0EVj9788vhz_ST.mp4",
            "$T3/02/04/23/24/240_F_204232438_61XkO3nVRtQq0PZm9fmmPL75ylFgF7wB.jpg"),
        ExerciseEntity(3, "Presă umeri cu gantere", 4, 10, "2×14", "KG",
            "$V/04/27/02/22/700_F_427022252_45scQ3vrBVIoiTC4mRQqd7SJmRYwdKuY_ST.mp4",
            "$V/05/22/22/89/700_F_522228989_OjVMRHbNianFnKbbuPwcX0EVj9788vhz_ST.mp4",
            "$T3/04/27/02/22/240_F_427022252_45scQ3vrBVIoiTC4mRQqd7SJmRYwdKuY.jpg"),
        ExerciseEntity(4, "Îndreptări românești", 4, 8, "70", "KG",
            "$V/03/92/70/97/700_F_392709785_JdoeyjI2V3vixvag3rlDGWtRH97lqQ0z_ST.mp4",
            "$V/05/22/22/89/700_F_522228989_OjVMRHbNianFnKbbuPwcX0EVj9788vhz_ST.mp4",
            "$T4/03/92/70/97/240_F_392709785_JdoeyjI2V3vixvag3rlDGWtRH97lqQ0z.jpg")
    )

    val plans = listOf(
        PlanEntity(0, "Piept & Spate", "FORȚĂ · 45 MIN · SALĂ", "$T4/06/22/38/57/500_F_622385753_VgquhCDAoHqLCGy3w8Q9zUEpxDLGfX54.jpg", 0),
        PlanEntity(1, "Picioare & Core", "FORȚĂ · 40 MIN · SALĂ", "$T3/08/40/97/72/500_F_840977260_48ywqt97BN3HcGoVsV1anWp7Asm8Lfnj.jpg", 1),
        PlanEntity(2, "Full Body Acasă", "FĂRĂ ECHIPAMENT · 30 MIN", "$T3/02/04/23/24/500_F_204232438_61XkO3nVRtQq0PZm9fmmPL75ylFgF7wB.jpg", 2)
    )

    val planExercises = listOf(
        PlanExerciseEntity(0, 0, 1), PlanExerciseEntity(0, 1, 2), PlanExerciseEntity(0, 2, 3),
        PlanExerciseEntity(1, 0, 0), PlanExerciseEntity(1, 1, 4), PlanExerciseEntity(1, 2, 3),
        PlanExerciseEntity(2, 0, 2), PlanExerciseEntity(2, 1, 0), PlanExerciseEntity(2, 2, 1), PlanExerciseEntity(2, 3, 4)
    )

    suspend fun ensure(db: ForjaDatabase) {
        val dao = db.workoutDao()
        if (dao.planCount() == 0) {
            dao.insertExercises(exercises)
            dao.insertPlans(plans)
            dao.insertPlanExercises(planExercises)
        }
    }
}
