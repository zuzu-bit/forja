package com.forja.app.core.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Starea locală a oglinzilor spre site (explorarea, antrenamentele, rația, emailul): ceasuri de sincronizare și amprente.
 * Fișier propriu, sincron (îl citesc și lucrătorii din fundal), separat de Prefs ca să nu atingă cheile altor module.
 * Cheile legate de cont poartă uid-ul: alt cont pe același telefon pornește de la zero.
 */
object SiteSyncStore {
    private const val FILE = "forja_site_sync_v1"

    fun of(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** `/health` → `explore_sync` (1 = celule fără mod, 2 = mod pe celulă + vizite pe loc) și când l-am citit. */
    const val HEALTH_EXPLORE_SYNC = "health_explore_sync"
    const val HEALTH_AT = "health_at"

    /** Schema cu care au plecat TOATE celulele (retrimiterea completă o dată, când serverul trece la v2). */
    const val EXPLORE_SCHEMA_SENT = "explore_schema_sent"

    /** Contul pentru care s-a făcut ultima sincronizare a explorării (alt cont → retrimitem tot). */
    const val EXPLORE_UID = "explore_uid"

    /** Momentul programat al ultimei sincronizări pornite de o celulă nouă (cel mult una la 5 min). */
    const val EXPLORE_CELL_KICK_AT = "explore_cell_kick_at"

    /** Cel mai nou `endedAt` de antrenament deja trimis în users/{uid}/workouts. */
    fun workoutsSince(uid: String) = "workouts_since_$uid"

    /** Amprenta rației trimise ultima dată în users/{uid}/settings/targets. */
    fun targetsSignature(uid: String) = "targets_sig_$uid"

    /** Emailul a ieșit din users/{uid} (mutat în users/{uid}/settings/account). */
    fun emailMoved(uid: String) = "email_moved_$uid"
}
