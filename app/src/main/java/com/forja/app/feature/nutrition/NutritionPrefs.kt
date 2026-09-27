package com.forja.app.feature.nutrition

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime

private val Context.nutritionStore by preferencesDataStore(name = "forja_nutrition")

/**
 * Preferințele modulului Rație — DataStore propriu (`forja_nutrition`), separat de `forja_prefs`.
 * Chei: `kcal_target` (obiectiv zilnic, implicit 2000), `streak_count`, `streak_last_day` (epochDay al ultimei zile cu masă).
 * Seria e locală și onestă: contează zilele consecutive cu cel puțin o masă notată — o zi rămasă fără mese
 * (după ștergere) nu mai contează, iar „azi” se mută singur după miezul nopții.
 * Scrierile nu aruncă niciodată: un disc plin sau un fișier corupt nu pică aplicația după ce masa e deja salvată.
 */
class NutritionPrefs(private val context: Context) {
    private object K {
        val kcalTarget = intPreferencesKey("kcal_target")
        val streakCount = intPreferencesKey("streak_count")
        val streakLastDay = longPreferencesKey("streak_last_day")
    }

    val kcalTarget: Flow<Int> = context.nutritionStore.data.map { it[K.kcalTarget] ?: DEFAULT_KCAL }
    suspend fun setKcalTarget(v: Int) = safeEdit { it[K.kcalTarget] = v.coerceIn(MIN_KCAL, MAX_KCAL) }

    /** Seria vizibilă: 0 dacă ultima masă e mai veche de ieri. „Azi” se reevaluează după fiecare miez de noapte cât e cineva abonat. */
    fun streak(): Flow<Int> = combine(context.nutritionStore.data, today()) { prefs, today ->
        val last = prefs[K.streakLastDay] ?: -1L
        val count = prefs[K.streakCount] ?: 0
        if (last < today - 1) 0 else count
    }

    /** O masă notată în ziua `day`: ieri a fost și ea → +1; azi deja numărată → nimic; pauză → reia de la 1. */
    suspend fun noteMeal(day: Long) = safeEdit {
        val last = it[K.streakLastDay] ?: -1L
        val count = it[K.streakCount] ?: 0
        when {
            last == day -> Unit
            last == day - 1 -> { it[K.streakCount] = count + 1; it[K.streakLastDay] = day }
            else -> { it[K.streakCount] = 1; it[K.streakLastDay] = day }
        }
    }

    /**
     * Ultima masă din ziua `day` a fost ștearsă: dacă ziua era ultima numărată, iese din serie
     * (seria se întoarce la ziua dinainte). O masă notată din nou în aceeași zi o pune la loc.
     */
    suspend fun noteMealRemoved(day: Long) = safeEdit {
        val last = it[K.streakLastDay] ?: -1L
        val count = it[K.streakCount] ?: 0
        if (last == day) {
            it[K.streakCount] = (count - 1).coerceAtLeast(0)
            it[K.streakLastDay] = day - 1
        }
    }

    /** `edit` poate arunca IOException (disc plin, fișier corupt) — aici se înghite, nu se propagă în viewModelScope. */
    private suspend fun safeEdit(block: suspend (MutablePreferences) -> Unit) {
        try { context.nutritionStore.edit(block) } catch (_: IOException) { }
    }

    companion object {
        const val DEFAULT_KCAL = 2000
        const val MIN_KCAL = 1200
        const val MAX_KCAL = 4500
        const val STEP_KCAL = 50

        /** Ziua de azi (epochDay), emisă la abonare și apoi după fiecare miez de noapte. */
        private fun today(): Flow<Long> = flow {
            while (true) {
                val now = ZonedDateTime.now()
                emit(now.toLocalDate().toEpochDay())
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                delay(Duration.between(now, midnight).toMillis() + 1_000L)
            }
        }

        @Volatile private var instance: NutritionPrefs? = null
        fun of(context: Context): NutritionPrefs =
            instance ?: synchronized(this) {
                instance ?: NutritionPrefs(context.applicationContext).also { instance = it }
            }
    }
}
