package com.forja.app.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.forja.app.ForjaApp

/**
 * Jurnalele din Room sunt ale unui singur om. Pe același telefon, alt cont nu le vede și nu le urcă în contul lui
 * (până la mirror P0, SiteMirror completa antrenamentele ultimelor 60 de zile ale celui dinainte).
 *  - la ieșirea din cont (Profil → Ieși): [wipe] golește toate jurnalele;
 *  - la fiecare cont conectat (pornire, schimbare de cont fără Profil): [claim] — dacă baza e a altui cont, se golește;
 *    altfel rândurile fără stăpân (ownerUid null: cele de dinainte de v9 și cele scrise de atunci) trec la contul acesta.
 * Planurile și exercițiile (date de start) și regulile de concentrare (setări) rămân.
 */
object Journals {
    /** Tabelele cu viața cuiva: ce a mâncat, a dormit, a mers, a jucat, pe unde a fost. */
    val TABLES: List<String> = listOf(
        "meals", "activities", "workout_sessions", "set_logs", "sleep_sessions", "sleep_events",
        "focus_sessions", "breath_sessions", "game_plays", "explore_cells", "places",
    )
    /** Tabelele cu coloana ownerUid (v9). */
    val OWNED: List<String> = listOf(
        "meals", "activities", "workout_sessions", "sleep_sessions", "focus_sessions", "breath_sessions", "game_plays",
    )

    private const val FILE = "forja_journals"
    private const val KEY_OWNER = "owner"

    /** Golește toate jurnalele, într-o singură tranzacție. */
    fun wipe(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            for (t in TABLES) db.execSQL("DELETE FROM `$t`")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Baza trece la `uid`. `previousOwner` = contul care o avea (null = necunoscut, instalări dinainte de v9).
     * Golește dacă baza e a altuia (stăpânul știut sau un rând etichetat cu alt uid), apoi etichetează rândurile fără stăpân.
     * Întoarce true dacă a golit ceva.
     */
    fun claim(db: SupportSQLiteDatabase, uid: String, previousOwner: String?): Boolean {
        val foreign = (previousOwner != null && previousOwner != uid) || OWNED.any { t ->
            db.query("SELECT 1 FROM `$t` WHERE ownerUid IS NOT NULL AND ownerUid != ? LIMIT 1", arrayOf<Any>(uid)).use { it.moveToFirst() }
        }
        if (foreign) wipe(db)
        db.beginTransaction()
        try {
            for (t in OWNED) db.execSQL("UPDATE `$t` SET ownerUid = ? WHERE ownerUid IS NULL", arrayOf<Any>(uid))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return foreign
    }

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Ieșirea din cont: golește jurnalele și uită stăpânul; ecranele deschise se reîmprospătează. */
    @Synchronized
    fun wipe(app: ForjaApp) {
        wipe(app.db.openHelper.writableDatabase)
        prefs(app).edit().remove(KEY_OWNER).apply()
        try { app.db.invalidationTracker.refreshVersionsAsync() } catch (_: Exception) { }
    }

    /** Contul conectat preia baza (sau o golește, dacă e a altuia). Ieftin când stăpânul e deja el. */
    @Synchronized
    fun claim(app: ForjaApp, uid: String) {
        val previous = prefs(app).getString(KEY_OWNER, null)
        val wiped = claim(app.db.openHelper.writableDatabase, uid, previous)
        if (previous != uid) prefs(app).edit().putString(KEY_OWNER, uid).apply()
        if (wiped) try { app.db.invalidationTracker.refreshVersionsAsync() } catch (_: Exception) { }
    }
}
