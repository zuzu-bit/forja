package com.forja.app.core.map

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/** DataStore-ul PROPRIU al hărții („forja_map”) — straturile și modul de noapte nu stau în Prefs.kt. */
private val Context.mapStore by preferencesDataStore(name = "forja_map")

/** Modul de noapte al hărții: automat între 21:00 și 06:00, pornit sau oprit. */
enum class NightMode { AUTO, ON, OFF }

/** Straturile hărții, așa cum le alege omul din foaia „Straturi”. Toate pornite implicit. */
data class MapLayers(
    val territories: Boolean = true,
    val heat: Boolean = true,
    val streets: Boolean = true,
    val places: Boolean = true,
    val friends: Boolean = true,
    val buildings3d: Boolean = true,
    val night: NightMode = NightMode.AUTO,
    val labelsRo: Boolean = true
) {
    /** Noapte acum? În AUTO: 21:00–06:00, ora locală. */
    fun isNight(hour: Int = LocalTime.now().hour): Boolean = when (night) {
        NightMode.ON -> true
        NightMode.OFF -> false
        NightMode.AUTO -> hour >= 21 || hour < 6
    }
}

class MapPrefs(private val context: Context) {
    private object K {
        val territories = booleanPreferencesKey("layer_territories")
        val heat = booleanPreferencesKey("layer_heat")
        val streets = booleanPreferencesKey("layer_streets")
        val places = booleanPreferencesKey("layer_places")
        val friends = booleanPreferencesKey("layer_friends")
        val buildings3d = booleanPreferencesKey("layer_buildings_3d")
        val night = stringPreferencesKey("night_mode")   // auto · on · off
        val labelsRo = booleanPreferencesKey("labels_ro")
    }

    val layers: Flow<MapLayers> = context.mapStore.data.map { p ->
        MapLayers(
            territories = p[K.territories] ?: true,
            heat = p[K.heat] ?: true,
            streets = p[K.streets] ?: true,
            places = p[K.places] ?: true,
            friends = p[K.friends] ?: true,
            buildings3d = p[K.buildings3d] ?: true,
            night = when (p[K.night]) { "on" -> NightMode.ON; "off" -> NightMode.OFF; else -> NightMode.AUTO },
            labelsRo = p[K.labelsRo] ?: true
        )
    }

    suspend fun save(l: MapLayers) = context.mapStore.edit { p ->
        p[K.territories] = l.territories
        p[K.heat] = l.heat
        p[K.streets] = l.streets
        p[K.places] = l.places
        p[K.friends] = l.friends
        p[K.buildings3d] = l.buildings3d
        p[K.night] = when (l.night) { NightMode.ON -> "on"; NightMode.OFF -> "off"; NightMode.AUTO -> "auto" }
        p[K.labelsRo] = l.labelsRo
    }
}
