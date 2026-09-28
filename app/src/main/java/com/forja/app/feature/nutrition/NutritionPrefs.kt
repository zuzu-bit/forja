package com.forja.app.feature.nutrition

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * Chei: `kcal_target` (obiectiv zilnic, implicit 2000), `streak_count`, `streak_last_day` (epochDay al ultimei zile cu masă),
 * profilul corpului `profile_*` (sex, vârstă, înălțime, greutate, activitate, obiectiv, dietă, restricții, unde mănânci,
 * ce schimbi, cum contează activitatea, pofte, `profile_done`) și `mascot_voice` (personalitatea Bucătarului).
 * Seria e locală și onestă: contează zilele consecutive cu cel puțin o masă notată — o zi rămasă fără mese
 * (după ștergere) nu mai contează, iar „azi” se mută singur după miezul nopții.
 * Scrierile nu aruncă niciodată: un disc plin sau un fișier corupt nu pică aplicația după ce masa e deja salvată.
 */
class NutritionPrefs(private val context: Context) {
    private object K {
        val kcalTarget = intPreferencesKey("kcal_target")
        val streakCount = intPreferencesKey("streak_count")
        val streakLastDay = longPreferencesKey("streak_last_day")
        val sex = intPreferencesKey("profile_sex")
        val age = intPreferencesKey("profile_age")
        val height = intPreferencesKey("profile_height_cm")
        val weight = floatPreferencesKey("profile_weight_kg")
        val activity = intPreferencesKey("profile_activity")
        val goal = intPreferencesKey("profile_goal")
        val diet = intPreferencesKey("profile_diet")
        val restrictions = stringSetPreferencesKey("profile_restrictions")
        val eatWhere = intPreferencesKey("profile_eat_where")
        val changes = stringSetPreferencesKey("profile_changes")
        val activityMode = intPreferencesKey("profile_activity_mode")
        val cravings = intPreferencesKey("profile_cravings")
        val profileDone = booleanPreferencesKey("profile_done")
        val voice = intPreferencesKey("mascot_voice")
    }

    /** Profilul corpului, așa cum a fost răspuns la chestionar (gol până la prima completare). */
    val profile: Flow<BodyProfile> = context.nutritionStore.data.map { p ->
        BodyProfile(
            sex = p[K.sex] ?: -1,
            age = p[K.age] ?: 0,
            heightCm = p[K.height] ?: 0,
            weightKg = p[K.weight] ?: 0f,
            activity = p[K.activity] ?: -1,
            goal = p[K.goal] ?: -1,
            diet = p[K.diet] ?: -1,
            restrictions = p[K.restrictions] ?: emptySet(),
            eatWhere = p[K.eatWhere] ?: -1,
            changes = p[K.changes] ?: emptySet(),
            activityMode = p[K.activityMode] ?: 0,
            cravings = p[K.cravings] ?: -1,
            done = p[K.profileDone] ?: false
        )
    }

    suspend fun saveProfile(v: BodyProfile) = safeEdit {
        it[K.sex] = v.sex; it[K.age] = v.age; it[K.height] = v.heightCm; it[K.weight] = v.weightKg
        it[K.activity] = v.activity; it[K.goal] = v.goal; it[K.diet] = v.diet
        it[K.restrictions] = v.restrictions; it[K.eatWhere] = v.eatWhere; it[K.changes] = v.changes
        it[K.activityMode] = v.activityMode; it[K.cravings] = v.cravings
        it[K.profileDone] = v.done
    }

    /** Personalitatea Bucătarului (implicit Camarad). */
    val voice: Flow<MascotVoice> = context.nutritionStore.data.map { MascotVoice.of(it[K.voice] ?: MascotVoice.Camarad.ordinal) }
    suspend fun setVoice(v: MascotVoice) = safeEdit { it[K.voice] = v.ordinal }

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
