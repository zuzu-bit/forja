package com.forja.app.feature.nutrition

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nutritionStore by preferencesDataStore(name = "forja_nutrition")

/**
 * Preferințele modulului Rație — DataStore propriu (`forja_nutrition`), separat de `forja_prefs`.
 * Chei: `kcal_target` (obiectiv zilnic, implicit 2000), `streak_count`, `streak_last_day` (epochDay al ultimei zile cu masă).
 * Seria e locală și onestă: contează zilele consecutive cu cel puțin o masă notată.
 */
class NutritionPrefs(private val context: Context) {
    private object K {
        val kcalTarget = intPreferencesKey("kcal_target")
        val streakCount = intPreferencesKey("streak_count")
        val streakLastDay = longPreferencesKey("streak_last_day")
    }

    val kcalTarget: Flow<Int> = context.nutritionStore.data.map { it[K.kcalTarget] ?: DEFAULT_KCAL }
    suspend fun setKcalTarget(v: Int) = context.nutritionStore.edit { it[K.kcalTarget] = v.coerceIn(MIN_KCAL, MAX_KCAL) }

    /** Seria vizibilă: 0 dacă ultima masă e mai veche de ieri. */
    fun streak(today: Long): Flow<Int> = context.nutritionStore.data.map {
        val last = it[K.streakLastDay] ?: -1L
        val count = it[K.streakCount] ?: 0
        if (last < today - 1) 0 else count
    }

    /** O masă notată în ziua `day`: ieri a fost și ea → +1; azi deja numărată → nimic; pauză → reia de la 1. */
    suspend fun noteMeal(day: Long) = context.nutritionStore.edit {
        val last = it[K.streakLastDay] ?: -1L
        val count = it[K.streakCount] ?: 0
        when {
            last == day -> Unit
            last == day - 1 -> { it[K.streakCount] = count + 1; it[K.streakLastDay] = day }
            else -> { it[K.streakCount] = 1; it[K.streakLastDay] = day }
        }
    }

    companion object {
        const val DEFAULT_KCAL = 2000
        const val MIN_KCAL = 1200
        const val MAX_KCAL = 4500
        const val STEP_KCAL = 50

        @Volatile private var instance: NutritionPrefs? = null
        fun of(context: Context): NutritionPrefs =
            instance ?: synchronized(this) {
                instance ?: NutritionPrefs(context.applicationContext).also { instance = it }
            }
    }
}
