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
}
