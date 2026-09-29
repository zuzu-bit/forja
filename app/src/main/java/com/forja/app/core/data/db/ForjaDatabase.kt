package com.forja.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ExerciseEntity::class, PlanEntity::class, PlanExerciseEntity::class,
        WorkoutSessionEntity::class, SetLogEntity::class,
        MealEntity::class, SleepSessionEntity::class, SleepEventEntity::class,
        ActivityEntity::class, FocusRuleEntity::class,
        ExploreCellEntity::class, PlaceEntity::class,
        FocusSessionEntity::class, BreathSessionEntity::class, GamePlayEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class ForjaDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun mealDao(): MealDao
    abstract fun sleepDao(): SleepDao
    abstract fun activityDao(): ActivityDao
    abstract fun focusDao(): FocusDao
    abstract fun exploreDao(): ExploreDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun breathSessionDao(): BreathSessionDao
    abstract fun gamePlayDao(): GamePlayDao

    companion object {
        @Volatile private var instance: ForjaDatabase? = null

        // De la v4 încolo migrăm FĂRĂ pierdere de date.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN photoPath TEXT")
            }
        }

        // v6: explorarea — zonele deblocate și locurile unde ai stat.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `explore_cells` (`id` TEXT NOT NULL, `minLat` REAL NOT NULL, `minLng` REAL NOT NULL, " +
                        "`maxLat` REAL NOT NULL, `maxLng` REAL NOT NULL, `firstAt` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL, " +
                        "`visits` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, " +
                        "`lng` REAL NOT NULL, `firstAt` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL, `stayMs` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `stars` INTEGER NOT NULL, `note` TEXT NOT NULL, `recommended` INTEGER NOT NULL, " +
                        "`remoteId` TEXT, `cellId` TEXT NOT NULL)"
                )
            }
        }

        // v7: locurile țin minte când au fost editate — reconciliere cu panoul online.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v8: teritoriile țin minte cum le-ai cucerit (pe jos / alergând / bicicletă); locurile numără vizitele.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE explore_cells ADD COLUMN mode TEXT NOT NULL DEFAULT 'walk'")
                db.execSQL("ALTER TABLE places ADD COLUMN visits INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v9 (mirror): jurnalele noi pentru site (concentrare, respirație, jocuri), detaliile analizei mesei, id-uri stabile
         * pentru site (cloudId) și contul al cui e fiecare rând (ownerUid, vezi Journals.claim). Doar adăugări: nimic nu se pierde.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in MIGRATION_8_9_SQL) db.execSQL(sql)
            }
        }

        /** Instrucțiunile v8 → v9, în ordine (testul le rulează pe o bază v8 reală). */
        internal val MIGRATION_8_9_SQL: List<String> = listOf(
            "ALTER TABLE meals ADD COLUMN details TEXT",
            "ALTER TABLE meals ADD COLUMN cloudId TEXT",
            "ALTER TABLE meals ADD COLUMN ownerUid TEXT",
            "ALTER TABLE activities ADD COLUMN cloudId TEXT",
            "ALTER TABLE activities ADD COLUMN ownerUid TEXT",
            "ALTER TABLE workout_sessions ADD COLUMN cloudId TEXT",
            "ALTER TABLE workout_sessions ADD COLUMN ownerUid TEXT",
            "ALTER TABLE sleep_sessions ADD COLUMN ownerUid TEXT",
            "CREATE TABLE IF NOT EXISTS `focus_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startAt` INTEGER NOT NULL, " +
                "`endAt` INTEGER, `kind` TEXT NOT NULL, `plannedMin` INTEGER NOT NULL, `rules` TEXT NOT NULL, `grown` INTEGER NOT NULL, " +
                "`withered` INTEGER NOT NULL, `blockHits` TEXT NOT NULL, `endedBy` TEXT, `cloudId` TEXT, `ownerUid` TEXT)",
            "CREATE TABLE IF NOT EXISTS `breath_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startAt` INTEGER NOT NULL, " +
                "`endAt` INTEGER NOT NULL, `pattern` TEXT NOT NULL, `cycles` INTEGER NOT NULL, `durationS` INTEGER NOT NULL, " +
                "`completed` INTEGER NOT NULL, `ownerUid` TEXT)",
            "CREATE TABLE IF NOT EXISTS `game_plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, " +
                "`game` TEXT NOT NULL, `level` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `stars` INTEGER NOT NULL, `score` INTEGER NOT NULL, " +
                "`durationS` INTEGER NOT NULL, `ownerUid` TEXT)"
        )

        fun get(context: Context): ForjaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForjaDatabase::class.java,
                    "forja.db"
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
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
