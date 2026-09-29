package com.forja.app.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * Migrarea Room 8 → 9 (mirror P0): o bază v8 cu date reale trece la v9 fără să piardă nimic, iar fiecare tabelă atinsă
 * are exact coloanele entității (nume și tip SQLite, citite prin reflecție din clasele Kotlin), ca Room să o accepte la
 * deschidere. Rulează fără codul generat de Room: doar SQLite + instrucțiunile din ForjaDatabase.MIGRATION_8_9.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class Migration8To9Test {
    @After fun close() = V8Schema.closeAll()

    private fun seedV8(name: String) {
        val v8 = V8Schema.open(name, 8)
        v8.writableDatabase.apply {
            execSQL("INSERT INTO meals (epochDay, mealType, name, kcal, protein, carbs, fat, grams, source, confidence, at, confirmed, barcode, photoPath) VALUES (20000, 1, 'Ciorbă', 380, 20, 30, 12, 400, 'ESTIMAT', 'medie', 1000, 1, NULL, '/m/1.jpg')")
            execSQL("INSERT INTO activities (startAt, endAt, distanceM, durationS, kcal, polyline, type) VALUES (1000, 2000, 5230.5, 1800, 300, '44.4,26.1;44.41,26.11', 'run')")
            execSQL("INSERT INTO workout_sessions (planId, planName, startedAt, endedAt, totalSets) VALUES (0, 'Piept & Spate', 1000, 2000, 14)")
            execSQL("INSERT INTO sleep_sessions (startAt, endAt, movements, score, deepMin, lightMin, remMin, phases, summary, recordedUntil) VALUES (1000, 2000, 3, 82, 90, 250, 100, '0,30,light', 'Somn liniștit.', 0)")
        }
        v8.close()
    }

    private fun migrate(name: String) = V8Schema.open(name, 9) { db, from, to ->
        assertEquals(8, from); assertEquals(9, to)
        ForjaDatabase.MIGRATION_8_9.migrate(db)
    }

    @Test fun rowsSurviveAndNewColumnsAreEmpty() {
        seedV8("m89-rows.db")
        val db = migrate("m89-rows.db").writableDatabase
        db.query("SELECT name, kcal, photoPath, details, cloudId, ownerUid FROM meals").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Ciorbă", c.getString(0)); assertEquals(380, c.getInt(1)); assertEquals("/m/1.jpg", c.getString(2))
            assertTrue(c.isNull(3)); assertTrue(c.isNull(4)); assertTrue(c.isNull(5))
        }
        db.query("SELECT distanceM, polyline, cloudId, ownerUid FROM activities").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(5230.5, c.getDouble(0), 0.0); assertEquals("44.4,26.1;44.41,26.11", c.getString(1))
            assertTrue(c.isNull(2)); assertTrue(c.isNull(3))
        }
        db.query("SELECT planName, totalSets, cloudId, ownerUid FROM workout_sessions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Piept & Spate", c.getString(0)); assertEquals(14, c.getInt(1)); assertTrue(c.isNull(2)); assertTrue(c.isNull(3))
        }
        db.query("SELECT summary, ownerUid FROM sleep_sessions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Somn liniștit.", c.getString(0)); assertTrue(c.isNull(1))
        }
        for (t in listOf("focus_sessions", "breath_sessions", "game_plays")) assertEquals(t, 0, V8Schema.count(db, t))
        // Tabelele noi primesc rânduri cu valorile implicite ale entităților.
        db.execSQL("INSERT INTO focus_sessions (startAt, kind, plannedMin, rules, grown, withered, blockHits) VALUES (1, 'detox', 60, '[]', 0, 0, '{}')")
        db.execSQL("INSERT INTO breath_sessions (startAt, endAt, pattern, cycles, durationS, completed) VALUES (1, 2, '4-7-8', 4, 76, 1)")
        db.execSQL("INSERT INTO game_plays (at, game, level, outcome, stars, score, durationS) VALUES (1, 'zid', 3, 'won', 2, 1200, 95)")
        db.query("SELECT endAt, endedBy, cloudId, ownerUid FROM focus_sessions").use { c -> assertTrue(c.moveToFirst()); for (i in 0..3) assertTrue(c.isNull(i)) }
    }

    /** Coloanele fiecărei tabele atinse = câmpurile entității, cu afinitatea SQLite a tipului Kotlin. */
    @Test fun everyTouchedTableMatchesItsEntity() {
        seedV8("m89-schema.db")
        val db = migrate("m89-schema.db").writableDatabase
        val entities = mapOf(
            "meals" to MealEntity::class.java, "activities" to ActivityEntity::class.java,
            "workout_sessions" to WorkoutSessionEntity::class.java, "sleep_sessions" to SleepSessionEntity::class.java,
            "focus_sessions" to FocusSessionEntity::class.java, "breath_sessions" to BreathSessionEntity::class.java,
            "game_plays" to GamePlayEntity::class.java,
        )
        for ((table, cls) in entities) {
            val want = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.associate { f ->
                f.name to when (f.type) {
                    java.lang.Long.TYPE, java.lang.Integer.TYPE, java.lang.Boolean.TYPE,
                    java.lang.Long::class.java, java.lang.Integer::class.java, java.lang.Boolean::class.java -> "INTEGER"
                    java.lang.Double.TYPE, java.lang.Float.TYPE, java.lang.Double::class.java -> "REAL"
                    String::class.java -> "TEXT"
                    else -> "?" + f.type.name
                }
            }
            val got = V8Schema.columns(db, table)
            assertEquals(table, want.keys.sorted(), got.keys.sorted())
            for ((col, type) in want) assertEquals("$table.$col", type, got.getValue(col).first)
            // Un primitiv Kotlin (Long, Int, Boolean, Double) e NOT NULL în Room; un tip boxed (Long?) e nullable.
            for (f in cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type.isPrimitive })
                assertTrue("$table.${f.name} NOT NULL", got.getValue(f.name).second)
        }
        // Coloanele noi v9: toate nullable (rândurile vechi nu au valori).
        for ((table, cols) in mapOf("meals" to listOf("details", "cloudId", "ownerUid"), "activities" to listOf("cloudId", "ownerUid"),
            "workout_sessions" to listOf("cloudId", "ownerUid"), "sleep_sessions" to listOf("ownerUid")))
            for (c in cols) assertEquals("$table.$c nullable", false, V8Schema.columns(db, table).getValue(c).second)
    }

    @Test fun theDatabaseIsAtVersionNineAndRegistersTheMigration() {
        seedV8("m89-version.db")
        val db = migrate("m89-version.db").writableDatabase
        assertEquals(9, db.version)
        assertEquals(8, ForjaDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, ForjaDatabase.MIGRATION_8_9.endVersion)
    }
}
