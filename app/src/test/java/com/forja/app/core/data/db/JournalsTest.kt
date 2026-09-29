package com.forja.app.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.forja.app.core.data.Journals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Confidențialitatea între conturi (mirror P0): alt cont conectat pe același telefon nu preia (și nu urcă pe site) jurnalele
 * celui dinainte, iar același cont care revine își regăsește tot. Planurile, exercițiile și regulile rămân.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class JournalsTest {
    @After fun close() = V8Schema.closeAll()

    private fun v9(name: String): SupportSQLiteDatabase =
        V8Schema.open(name, 8).also { it.writableDatabase; it.close() }.let {
            V8Schema.open(name, 9) { db, _, _ -> ForjaDatabase.MIGRATION_8_9.migrate(db) }.writableDatabase
        }

    private fun seedLife(db: SupportSQLiteDatabase, owner: String?) {
        val o = if (owner == null) "NULL" else "'$owner'"
        db.execSQL("INSERT INTO meals (epochDay, mealType, name, kcal, protein, carbs, fat, grams, source, confidence, at, confirmed, ownerUid) VALUES (1, 0, 'Omletă', 420, 28, 3, 30, 200, 'MANUAL', '—', 1, 1, $o)")
        db.execSQL("INSERT INTO activities (startAt, endAt, distanceM, durationS, kcal, polyline, type, ownerUid) VALUES (1, 2, 5000, 1800, 300, '', 'run', $o)")
        db.execSQL("INSERT INTO workout_sessions (planId, planName, startedAt, endedAt, totalSets, ownerUid) VALUES (0, 'Forță', 1, 2, 12, $o)")
        db.execSQL("INSERT INTO set_logs (sessionId, exerciseId, exerciseName, setNo, reps, load, at) VALUES (1, 0, 'Genuflexiuni', 1, 8, '62,5', 1)")
        db.execSQL("INSERT INTO sleep_sessions (startAt, endAt, movements, score, deepMin, lightMin, remMin, phases, summary, recordedUntil, ownerUid) VALUES (1, 2, 0, 80, 1, 1, 1, '', '', 0, $o)")
        db.execSQL("INSERT INTO sleep_events (sessionId, type, at, durationS, intensity, transcript) VALUES (1, 'talk', 1, 5, 1, 'nu acum')")
        db.execSQL("INSERT INTO focus_sessions (startAt, kind, plannedMin, rules, grown, withered, blockHits, ownerUid) VALUES (1, 'focus', 25, '[]', 1, 0, '{}', $o)")
        db.execSQL("INSERT INTO breath_sessions (startAt, endAt, pattern, cycles, durationS, completed, ownerUid) VALUES (1, 2, 'box', 4, 64, 1, $o)")
        db.execSQL("INSERT INTO game_plays (at, game, level, outcome, stars, score, durationS, ownerUid) VALUES (1, 'asalt', 2, 'lost', 0, 300, 40, $o)")
        db.execSQL("INSERT INTO explore_cells (id, minLat, minLng, maxLat, maxLng, firstAt, lastAt, visits) VALUES ('${owner ?: "x"}_1', 44.4, 26.1, 44.41, 26.11, 1, 2, 1)")
        db.execSQL("INSERT INTO places (lat, lng, firstAt, lastAt, stayMs, name, stars, note, recommended, cellId) VALUES (44.4, 26.1, 1, 2, 18000000, 'Acasă', 0, '', 0, 'x_1')")
    }
    private fun seedSettings(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO plans (id, name, meta, cover, position) VALUES (0, 'Piept & Spate', 'FORȚĂ', '', 0)")
        db.execSQL("INSERT INTO exercises (id, name, sets, reps, load, loadLabel, videoFront, videoSide, thumb) VALUES (0, 'Genuflexiuni', 5, 8, '62,5', 'KG', '', '', '')")
        db.execSQL("INSERT INTO focus_rules (packageName, label, untilHour, untilMinute, enabled) VALUES ('com.instagram.android', 'Instagram', 22, 0, 1)")
    }
    private fun lifeRows(db: SupportSQLiteDatabase) = Journals.TABLES.sumOf { V8Schema.count(db, it) }
    private fun owners(db: SupportSQLiteDatabase): Set<String?> = Journals.OWNED.flatMap { t ->
        db.query("SELECT ownerUid FROM `$t`").use { c -> buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getString(0)) } }
    }.toSet()

    @Test fun wipeEmptiesEveryJournalButKeepsPlansAndRules() {
        val db = v9("j-wipe.db")
        seedLife(db, "lana"); seedSettings(db)
        assertEquals(Journals.TABLES.size, lifeRows(db))
        Journals.wipe(db)
        for (t in Journals.TABLES) assertEquals(t, 0, V8Schema.count(db, t))
        assertEquals(1, V8Schema.count(db, "plans")); assertEquals(1, V8Schema.count(db, "exercises")); assertEquals(1, V8Schema.count(db, "focus_rules"))
    }

    @Test fun theFirstAccountAdoptsOldRowsAndKeepsThem() {
        val db = v9("j-adopt.db")
        seedLife(db, null)   // o instalare dinainte de v9: rânduri fără stăpân
        assertFalse(Journals.claim(db, "lana", previousOwner = null))
        assertEquals(setOf<String?>("lana"), owners(db))
        assertEquals(Journals.TABLES.size, lifeRows(db))
        // Un rând scris după aceea (fără ownerUid) trece tot la ea, la următoarea preluare; nimic nu se pierde.
        db.execSQL("INSERT INTO meals (epochDay, mealType, name, kcal, protein, carbs, fat, grams, source, confidence, at, confirmed) VALUES (2, 1, 'Supă', 200, 5, 20, 5, 300, 'MANUAL', '—', 2, 1)")
        assertFalse(Journals.claim(db, "lana", previousOwner = "lana"))
        assertEquals(setOf<String?>("lana"), owners(db)); assertEquals(2, V8Schema.count(db, "meals"))
    }

    @Test fun theSameAccountComingBackKeepsEverything() {
        // Ieși din cont și intri iar cu același cont: teritoriul, locurile, seturile, nopțile rămân.
        val db = v9("j-return.db")
        seedLife(db, "lana")
        assertFalse(Journals.claim(db, "lana", previousOwner = "lana"))
        assertEquals(Journals.TABLES.size, lifeRows(db))
    }

    @Test fun anotherAccountNeverInheritsTheJournals() {
        // Ieșire fără Profil (token revocat): stăpânul știut e altul → se golește tot, apoi baza e a noului cont.
        val db = v9("j-switch.db")
        seedLife(db, "lana"); seedSettings(db)
        assertTrue(Journals.claim(db, "bogdan", previousOwner = "lana"))
        assertEquals(0, lifeRows(db))
        assertEquals(1, V8Schema.count(db, "plans"))
        // Stăpân necunoscut (preferințele pierdute), dar rânduri etichetate cu alt cont: tot se golește.
        seedLife(db, "lana")
        assertTrue(Journals.claim(db, "bogdan", previousOwner = null))
        assertEquals(0, lifeRows(db))
    }
}
