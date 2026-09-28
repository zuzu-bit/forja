package com.forja.app.core.cleanup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.forja.app.core.network.OrganizeVerdictV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.cleanupOnlineStore by preferencesDataStore(name = "forja_cleanup_online")

/**
 * Setările curățeniei online — DataStore propriu („forja_cleanup_online"), separat de Prefs.
 * Chei: `ai_on` (implicit PORNIT): după scanare, miniaturile / PDF-urile / fragmentele pleacă automat
 * la analiza pe serverul FORJA; `ai_verdicts`: verdictele primite, pe id („m:<id>" / „d:<sha>"), ca la o
 * redeschidere să nu retrimitem ce a fost deja analizat și etichetele să fie la loc. Comutatorul „Și pe site"
 * rămâne în OrganizerSettings (SharedPreferences), pentru că lucrătorii WorkManager îl citesc sincron.
 */
class CleanupOnlineSettings(private val context: Context) {
    private object K {
        val aiOn = booleanPreferencesKey("ai_on")
        val verdicts = stringPreferencesKey("ai_verdicts")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = false }
    private val mapSerializer = MapSerializer(String.serializer(), OrganizeVerdictV2.serializer())

    /** „Sugestii AI" — implicit pornit; oprit doar dacă utilizatorul l-a oprit explicit. */
    val aiOn: Flow<Boolean> = context.cleanupOnlineStore.data.map { it[K.aiOn] ?: true }

    suspend fun aiOnNow(): Boolean = try { aiOn.first() } catch (_: Exception) { true }

    suspend fun setAiOn(on: Boolean) { context.cleanupOnlineStore.edit { it[K.aiOn] = on } }

    /** Verdictele memorate (id → verdict). Un element trimis fără verdict întors e memorat gol: nu se retrimite. */
    suspend fun verdictsNow(): Map<String, OrganizeVerdictV2> = try {
        decode(context.cleanupOnlineStore.data.first()[K.verdicts])
    } catch (_: Exception) { emptyMap() }

    /** Adaugă verdictele noi peste cele vechi; peste [MAX_VERDICTS] cad cele mai vechi (ordinea de inserare). */
    suspend fun rememberVerdicts(fresh: Map<String, OrganizeVerdictV2>) {
        if (fresh.isEmpty()) return
        try {
            context.cleanupOnlineStore.edit { p ->
                val merged = LinkedHashMap(decode(p[K.verdicts]))
                fresh.forEach { (id, v) -> merged.remove(id); merged[id] = v }
                while (merged.size > MAX_VERDICTS) merged.remove(merged.keys.first())
                p[K.verdicts] = json.encodeToString(mapSerializer, merged)
            }
        } catch (_: Exception) { }
    }

    /** Uită verdictele pentru id-urile date (ex. la „Reia analiza” după o eroare). */
    suspend fun forgetVerdicts(ids: Collection<String>) {
        if (ids.isEmpty()) return
        try {
            context.cleanupOnlineStore.edit { p ->
                val merged = LinkedHashMap(decode(p[K.verdicts]))
                ids.forEach { merged.remove(it) }
                p[K.verdicts] = json.encodeToString(mapSerializer, merged)
            }
        } catch (_: Exception) { }
    }

    private fun decode(raw: String?): Map<String, OrganizeVerdictV2> =
        if (raw.isNullOrBlank()) emptyMap() else try { json.decodeFromString(mapSerializer, raw) } catch (_: Exception) { emptyMap() }

    companion object {
        const val MAX_VERDICTS = 4000
    }
}
