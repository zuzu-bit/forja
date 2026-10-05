package com.forja.app.core.soldier

import com.forja.app.ForjaApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starea Cascăi, citită o dată la pornire și ținută în memorie ([state]); fiecare schimbare se scrie în DataStore.
 * [outfit] e ținuta purtată acum — mascota o citește de oriunde (panou, bule, notificări), fără să știe de rest.
 */
object SoldierStore {
    private val _state = MutableStateFlow(SoldierState())
    val state: StateFlow<SoldierState> = _state.asStateFlow()
    private val _outfit = MutableStateFlow(Outfit.RECRUIT)
    val outfit: StateFlow<Outfit> = _outfit.asStateFlow()
    private val _ready = MutableStateFlow(false)
    /** Starea e cea de pe disc (nu cea goală dinainte de [load])? */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val lock = Mutex()
    @Volatile private var loaded = false

    /** Citirea de pe disc (o singură dată; apelurile următoare sunt gratuite). */
    suspend fun load(app: ForjaApp): SoldierState {
        if (loaded) return _state.value
        lock.withLock {
            if (!loaded) {
                val s = SoldierState.fromJson(app.prefs.soldierState.first()) ?: SoldierState(createdAt = System.currentTimeMillis())
                publish(s)
                loaded = true
                _ready.value = true
            }
        }
        return _state.value
    }

    /** Schimbare atomică: citește, transformă, scrie, publică. */
    suspend fun update(app: ForjaApp, fn: (SoldierState) -> SoldierState): SoldierState {
        load(app)
        return lock.withLock {
            val next = fn(_state.value)
            if (next != _state.value) {
                publish(next)
                try { app.prefs.setSoldierState(next.toJson()) } catch (_: Exception) { }
            }
            next
        }
    }

    private fun publish(s: SoldierState) {
        _state.value = s
        _outfit.value = s.outfit
    }

    /** Pune o piesă deținută (sau o scoate, cu null). */
    suspend fun equip(app: ForjaApp, slot: Slot, id: String?): SoldierState = update(app) { s ->
        if (id == null) s.copy(equipped = s.equipped - slot)
        else if (id in s.owned && Gear.get(id)?.slot == slot) s.copy(equipped = s.equipped + (slot to id)) else s
    }

    /** Cumpără (din sold) și pune piesa; false dacă nu se poate (grad mic, sold mic, deja deținută). */
    suspend fun buy(app: ForjaApp, id: String): Boolean {
        val item = Gear.get(id) ?: return false
        var ok = false
        update(app) { s ->
            if (id in s.owned || s.rank.index < item.rank || s.balance < item.cost) s
            else { ok = true; s.copy(balance = s.balance - item.cost, owned = s.owned + id, equipped = s.equipped + (item.slot to id)) }
        }
        return ok
    }

    /** Avansarea a fost sărbătorită. */
    suspend fun promotionSeen(app: ForjaApp) = update(app) { s -> s.copy(promoSeen = s.rank.index) }

    /** Darurile gradelor noi (cost 0), puse direct pe mascotă. */
    internal fun grantGifts(s: SoldierState): SoldierState {
        val rank = s.rank.index
        if (rank <= s.gifted) return s
        var owned = s.owned
        var equipped = s.equipped
        for (r in (s.gifted + 1)..rank) {
            for (g in Gear.giftsOf(r)) {
                owned = owned + g.id
                equipped = equipped + (g.slot to g.id)
            }
        }
        return s.copy(owned = owned, equipped = equipped, gifted = rank)
    }
}
