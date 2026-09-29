package com.forja.app.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.robolectric.RuntimeEnvironment


/**
 * Schema Room v8 (4.4.2), exact cum o crea Room pe telefoane: testele pornesc de la o bază v8 reală și rulează peste ea
 * migrarea 8 → 9, fără codul generat de Room (KSP).
 */
object V8Schema {
    val CREATE: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `sets` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `load` TEXT NOT NULL, `loadLabel` TEXT NOT NULL, `videoFront` TEXT NOT NULL, `videoSide` TEXT NOT NULL, `thumb` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `plans` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `meta` TEXT NOT NULL, `cover` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `plan_exercises` (`planId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, PRIMARY KEY(`planId`, `position`))",
        "CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `planName` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `totalSets` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `set_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `exerciseName` TEXT NOT NULL, `setNo` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `load` TEXT NOT NULL, `at` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `meals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, `mealType` INTEGER NOT NULL, `name` TEXT NOT NULL, `kcal` INTEGER NOT NULL, `protein` INTEGER NOT NULL, `carbs` INTEGER NOT NULL, `fat` INTEGER NOT NULL, `grams` INTEGER NOT NULL, `source` TEXT NOT NULL, `confidence` TEXT NOT NULL, `at` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `barcode` TEXT, `photoPath` TEXT)",
        "CREATE TABLE IF NOT EXISTS `sleep_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER, `movements` INTEGER NOT NULL, `score` INTEGER NOT NULL, `deepMin` INTEGER NOT NULL, `lightMin` INTEGER NOT NULL, `remMin` INTEGER NOT NULL, `phases` TEXT NOT NULL, `summary` TEXT NOT NULL, `recordedUntil` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sleep_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `type` TEXT NOT NULL, `at` INTEGER NOT NULL, `durationS` INTEGER NOT NULL, `intensity` INTEGER NOT NULL, `clipPath` TEXT, `transcript` TEXT)",
        "CREATE TABLE IF NOT EXISTS `activities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `durationS` INTEGER NOT NULL, `kcal` INTEGER NOT NULL, `polyline` TEXT NOT NULL, `type` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `focus_rules` (`packageName` TEXT NOT NULL, `label` TEXT NOT NULL, `untilHour` INTEGER NOT NULL, `untilMinute` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
        "CREATE TABLE IF NOT EXISTS `explore_cells` (`id` TEXT NOT NULL, `minLat` REAL NOT NULL, `minLng` REAL NOT NULL, `maxLat` REAL NOT NULL, `maxLng` REAL NOT NULL, `firstAt` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL, `visits` INTEGER NOT NULL, `mode` TEXT NOT NULL DEFAULT 'walk', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `firstAt` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL, `stayMs` INTEGER NOT NULL, `name` TEXT NOT NULL, `stars` INTEGER NOT NULL, `note` TEXT NOT NULL, `recommended` INTEGER NOT NULL, `remoteId` TEXT, `cellId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, `visits` INTEGER NOT NULL DEFAULT 1)",
    )

    /** O bază pe disc la `version`: creată cu schema v8, urcată prin `upgrade` (aici: migrarea 8 → 9). */
    fun open(name: String, version: Int, upgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }): SupportSQLiteOpenHelper {
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) { CREATE.forEach(db::execSQL) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = upgrade(db, oldVersion, newVersion)
            }).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).also { opened += it }
    }

    private val opened = mutableListOf<SupportSQLiteOpenHelper>()
    /** Închide tot ce a deschis testul (în @After). */
    fun closeAll() { opened.forEach { try { it.close() } catch (_: Exception) { } }; opened.clear() }

    /** Numele coloanelor și dacă sunt NOT NULL, din PRAGMA table_info. */
    fun columns(db: SupportSQLiteDatabase, table: String): Map<String, Pair<String, Boolean>> {
        val out = LinkedHashMap<String, Pair<String, Boolean>>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) out[c.getString(c.getColumnIndexOrThrow("name"))] =
                c.getString(c.getColumnIndexOrThrow("type")) to (c.getInt(c.getColumnIndexOrThrow("notnull")) == 1)
        }
        return out
    }

    fun count(db: SupportSQLiteDatabase, table: String): Int = db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
}
