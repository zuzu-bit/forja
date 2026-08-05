package com.forja.app.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "forja_prefs")

class Prefs(private val context: Context) {
    private object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val kcalTarget = intPreferencesKey("kcal_target")
        val weekKmTarget = intPreferencesKey("week_km_target")
        val notifOn = booleanPreferencesKey("notif_on")
        val sleepReminder = booleanPreferencesKey("sleep_reminder")
        val focusUnlockUntil = longPreferencesKey("focus_unlock_until")
        val focusActive = booleanPreferencesKey("focus_active")
        val cachedName = stringPreferencesKey("cached_name")
        val geminiKey = stringPreferencesKey("gemini_key")
        val alarmEnabled = booleanPreferencesKey("alarm_enabled")
        val alarmHour = intPreferencesKey("alarm_hour")
        val alarmMinute = intPreferencesKey("alarm_minute")
        val bgShareOn = booleanPreferencesKey("bg_share_on")
        val ghostUntilLocal = longPreferencesKey("ghost_until_local")
        val bgBannerDismissed = booleanPreferencesKey("bg_banner_dismissed")
        val alarmWindowMin = intPreferencesKey("alarm_window_min")
        val detoxUntil = longPreferencesKey("detox_until")
        val galleryScanOn = booleanPreferencesKey("gallery_scan_on")
        val detoxOn = booleanPreferencesKey("detox_addiction_on")
        val detoxStreakStart = longPreferencesKey("detox_streak_start")
        val detoxLetter = stringPreferencesKey("detox_letter")
        val detoxWords = stringPreferencesKey("detox_words")
        val detoxSlips = intPreferencesKey("detox_slips")
        val permsIntroSeen = booleanPreferencesKey("perms_intro_seen")
        val nudgesOn = booleanPreferencesKey("nudges_on")
        val focusForestDay = longPreferencesKey("focus_forest_day")
        val focusGrown = intPreferencesKey("focus_grown")
        val focusWithered = intPreferencesKey("focus_withered")
        val focusPartialSecs = intPreferencesKey("focus_partial_secs")
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[K.onboardingDone] ?: false }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboardingDone] = true }

    val kcalTarget: Flow<Int> = context.dataStore.data.map { it[K.kcalTarget] ?: 2250 }
    suspend fun setKcalTarget(v: Int) = context.dataStore.edit { it[K.kcalTarget] = v }

    val weekKmTarget: Flow<Int> = context.dataStore.data.map { it[K.weekKmTarget] ?: 29 }
    suspend fun setWeekKmTarget(v: Int) = context.dataStore.edit { it[K.weekKmTarget] = v }

    val notifOn: Flow<Boolean> = context.dataStore.data.map { it[K.notifOn] ?: true }
    suspend fun setNotifOn(v: Boolean) = context.dataStore.edit { it[K.notifOn] = v }

    val sleepReminder: Flow<Boolean> = context.dataStore.data.map { it[K.sleepReminder] ?: false }
    suspend fun setSleepReminder(v: Boolean) = context.dataStore.edit { it[K.sleepReminder] = v }

    val focusUnlockUntil: Flow<Long> = context.dataStore.data.map { it[K.focusUnlockUntil] ?: 0L }
    suspend fun setFocusUnlockUntil(v: Long) = context.dataStore.edit { it[K.focusUnlockUntil] = v }

    val focusActive: Flow<Boolean> = context.dataStore.data.map { it[K.focusActive] ?: false }
    suspend fun setFocusActive(v: Boolean) = context.dataStore.edit { it[K.focusActive] = v }

    val cachedName: Flow<String> = context.dataStore.data.map { it[K.cachedName] ?: "" }
    suspend fun setCachedName(v: String) = context.dataStore.edit { it[K.cachedName] = v }

    val geminiKey: Flow<String> = context.dataStore.data.map { it[K.geminiKey] ?: "" }
    suspend fun setGeminiKey(v: String) = context.dataStore.edit { it[K.geminiKey] = v.trim() }

    val alarmEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.alarmEnabled] ?: false }
    suspend fun setAlarmEnabled(v: Boolean) = context.dataStore.edit { it[K.alarmEnabled] = v }

    val alarmHour: Flow<Int> = context.dataStore.data.map { it[K.alarmHour] ?: 7 }
    val alarmMinute: Flow<Int> = context.dataStore.data.map { it[K.alarmMinute] ?: 0 }
    suspend fun setAlarmTime(h: Int, m: Int) = context.dataStore.edit {
        it[K.alarmHour] = h; it[K.alarmMinute] = m
    }

    val bgShareOn: Flow<Boolean> = context.dataStore.data.map { it[K.bgShareOn] ?: false }
    suspend fun setBgShareOn(v: Boolean) = context.dataStore.edit { it[K.bgShareOn] = v }

    val ghostUntilLocal: Flow<Long> = context.dataStore.data.map { it[K.ghostUntilLocal] ?: 0L }
    suspend fun setGhostUntilLocal(v: Long) = context.dataStore.edit { it[K.ghostUntilLocal] = v }

    val bgBannerDismissed: Flow<Boolean> = context.dataStore.data.map { it[K.bgBannerDismissed] ?: false }
    suspend fun setBgBannerDismissed() = context.dataStore.edit { it[K.bgBannerDismissed] = true }

    /** Fereastra alarmei circadiene: cu câte minute înainte de ora-limită are voie să sune. */
    val alarmWindowMin: Flow<Int> = context.dataStore.data.map { it[K.alarmWindowMin] ?: 40 }
    suspend fun setAlarmWindowMin(v: Int) = context.dataStore.edit { it[K.alarmWindowMin] = v }

    /** Detox: totul blocat (în afară de esențiale) până la această oră. 0 = oprit. */
    val detoxUntil: Flow<Long> = context.dataStore.data.map { it[K.detoxUntil] ?: 0L }
    suspend fun setDetoxUntil(v: Long) = context.dataStore.edit { it[K.detoxUntil] = v }

    /** Scanarea galeriei (à la Bixby Vision) — strict opt-in. */
    val galleryScanOn: Flow<Boolean> = context.dataStore.data.map { it[K.galleryScanOn] ?: false }
    suspend fun setGalleryScanOn(v: Boolean) = context.dataStore.edit { it[K.galleryScanOn] = v }

    /** Reminder-e blânde, la ore aleatoare — pornit implicit, se poate opri. */
    val nudgesOn: Flow<Boolean> = context.dataStore.data.map { it[K.nudgesOn] ?: true }
    suspend fun setNudgesOn(v: Boolean) = context.dataStore.edit { it[K.nudgesOn] = v }

    // ── Pădurea de Focus: copaci care cresc pe durata focus-ului, se ofilesc la renunțare ──
    /** (crescuți, ofiliți, secunde spre următorul) pentru ziua de azi. */
    val focusForest: Flow<Triple<Int, Int, Int>> = context.dataStore.data.map { p ->
        val today = java.time.LocalDate.now().toEpochDay()
        if ((p[K.focusForestDay] ?: -1L) == today)
            Triple(p[K.focusGrown] ?: 0, p[K.focusWithered] ?: 0, p[K.focusPartialSecs] ?: 0)
        else Triple(0, 0, 0)
    }
    suspend fun addFocusProgress(secs: Int) {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val same = (p[K.focusForestDay] ?: -1L) == today
            var grown = if (same) (p[K.focusGrown] ?: 0) else 0
            val withered = if (same) (p[K.focusWithered] ?: 0) else 0
            var partial = (if (same) (p[K.focusPartialSecs] ?: 0) else 0) + secs
            while (partial >= 900) { partial -= 900; grown++ }   // un copac la 15 min de focus
            p[K.focusForestDay] = today
            p[K.focusGrown] = grown
            p[K.focusWithered] = withered
            p[K.focusPartialSecs] = partial
        }
    }
    /** Oprire manuală: copacul început (≥1 min) se ofilește, cronometrul pleacă de la zero. */
    suspend fun witherFocusTree() {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val same = (p[K.focusForestDay] ?: -1L) == today
            val grown = if (same) (p[K.focusGrown] ?: 0) else 0
            val partial = if (same) (p[K.focusPartialSecs] ?: 0) else 0
            val withered = (if (same) (p[K.focusWithered] ?: 0) else 0) + (if (partial >= 60) 1 else 0)
            p[K.focusForestDay] = today
            p[K.focusGrown] = grown
            p[K.focusWithered] = withered
            p[K.focusPartialSecs] = 0
        }
    }

    suspend fun addFocusBreak() {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val same = (p[K.focusForestDay] ?: -1L) == today
            val grown = if (same) (p[K.focusGrown] ?: 0) else 0
            val withered = (if (same) (p[K.focusWithered] ?: 0) else 0) + 1
            val partial = if (same) (p[K.focusPartialSecs] ?: 0) else 0
            p[K.focusForestDay] = today
            p[K.focusGrown] = grown
            p[K.focusWithered] = withered
            p[K.focusPartialSecs] = partial
        }
    }

    // ── Detox de adicție — totul pe telefon, nimic pe server ──
    val detoxOn: Flow<Boolean> = context.dataStore.data.map { it[K.detoxOn] ?: false }
    suspend fun setDetoxOn(v: Boolean) = context.dataStore.edit {
        it[K.detoxOn] = v
        if (v && (it[K.detoxStreakStart] ?: 0L) == 0L) it[K.detoxStreakStart] = System.currentTimeMillis()
    }

    val detoxStreakStart: Flow<Long> = context.dataStore.data.map { it[K.detoxStreakStart] ?: 0L }
    val detoxSlips: Flow<Int> = context.dataStore.data.map { it[K.detoxSlips] ?: 0 }
    /** „Am alunecat” — resetează seria fără rușine, dar păstrează numărul de reveniri. */
    suspend fun detoxSlip() = context.dataStore.edit {
        it[K.detoxStreakStart] = System.currentTimeMillis()
        it[K.detoxSlips] = (it[K.detoxSlips] ?: 0) + 1
    }

    /** Scrisoarea către mine — de ce vreau să scap. Rămâne pe telefon. */
    val detoxLetter: Flow<String> = context.dataStore.data.map { it[K.detoxLetter] ?: "" }
    suspend fun setDetoxLetter(v: String) = context.dataStore.edit { it[K.detoxLetter] = v }

    /** Cuvintele-declanșator, setate de el. NU pleacă niciodată de pe telefon. */
    val detoxWords: Flow<String> = context.dataStore.data.map { it[K.detoxWords] ?: "" }
    suspend fun setDetoxWords(v: String) = context.dataStore.edit { it[K.detoxWords] = v }

    /** Ecranul de pornire cu permisiuni a fost arătat o dată. */
    val permsIntroSeen: Flow<Boolean> = context.dataStore.data.map { it[K.permsIntroSeen] ?: false }
    suspend fun setPermsIntroSeen() = context.dataStore.edit { it[K.permsIntroSeen] = true }

    /** „De la capăt": la ieșirea din cont, prezentarea și permisiunile inițiale se văd din nou. */
    suspend fun resetFirstRun() = context.dataStore.edit {
        it.remove(K.onboardingDone)
        it.remove(K.permsIntroSeen)
    }
}
