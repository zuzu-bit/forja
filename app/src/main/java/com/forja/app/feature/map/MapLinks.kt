package com.forja.app.feature.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Cereri către ecranul Hartă venite din afara lui („Hei FORJA, arată-l pe Ion pe hartă”): ecranul le citește când
 * prietenii s-au încărcat și zboară la cel cerut.
 */
object MapLinks {
    private val _friend = MutableStateFlow<String?>(null)
    /** Numele (rostit) al prietenului de arătat; null = nimic cerut. */
    val friend: StateFlow<String?> = _friend.asStateFlow()

    fun requestFriend(name: String) { _friend.value = name.trim() }
    fun consumeFriend(): String? = _friend.value.also { _friend.value = null }

    private fun fold(s: String): String = s.lowercase(Locale.ROOT)
        .replace('ă', 'a').replace('â', 'a').replace('î', 'i')
        .replace('ș', 's').replace('ş', 's').replace('ț', 't').replace('ţ', 't').trim()

    /**
     * Numele din [names] care se potrivește cu ce s-a rostit: întreg, apoi prenumele, apoi un cuvânt care începe la fel
     * („Andrei” ↔ „Andrei Pop”, „ion” ↔ „Ion Ionescu”, „ioana” ≠ „Ion”). Null dacă niciunul.
     */
    fun match(query: String, names: List<String>): String? {
        val q = fold(query)
        if (q.isBlank()) return null
        names.firstOrNull { fold(it) == q }?.let { return it }
        names.firstOrNull { fold(it).split(' ').firstOrNull() == q }?.let { return it }
        names.firstOrNull { fold(it).split(' ').any { w -> w == q } }?.let { return it }
        if (q.length >= 3) names.firstOrNull { fold(it).split(' ').any { w -> w.startsWith(q) } }?.let { return it }
        return null
    }
}
