package com.forja.app.core.cleanup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cleanupOnlineStore by preferencesDataStore(name = "forja_cleanup_online")

/**
 * Setările curățeniei online — DataStore propriu („forja_cleanup_online"), separat de Prefs.
 * Cheie: `ai_on` (implicit PORNIT): după scanare, miniaturile / PDF-urile / fragmentele pleacă automat
 * la analiza pe serverul FORJA. Comutatorul „Și pe site" rămâne în OrganizerSettings (SharedPreferences),
 * pentru că lucrătorii WorkManager îl citesc sincron; și el e implicit pornit acum.
 */
class CleanupOnlineSettings(private val context: Context) {
    private object K {
        val aiOn = booleanPreferencesKey("ai_on")
    }

    /** „Sugestii AI" — implicit pornit; oprit doar dacă utilizatorul l-a oprit explicit. */
    val aiOn: Flow<Boolean> = context.cleanupOnlineStore.data.map { it[K.aiOn] ?: true }

    suspend fun aiOnNow(): Boolean = try { aiOn.first() } catch (_: Exception) { true }

    suspend fun setAiOn(on: Boolean) { context.cleanupOnlineStore.edit { it[K.aiOn] = on } }
}
