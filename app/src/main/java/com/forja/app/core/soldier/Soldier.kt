package com.forja.app.core.soldier

import org.json.JSONArray
import org.json.JSONObject

/*
 * Casca în uniformă (5.0): mascota avansează în grad cu punctele câștigate din ce faci zilnic în FORJA
 * (mese în jurnal, ture, antrenamente, somn, focus, respirație…) și se îmbracă, pe grade militare, cu piesele
 * din garderobă. Punctele câștigate vreodată dau gradul (nu scad); soldul se cheltuie pe piese. Fiecare grad
 * nou aduce o piesă în dar, pusă direct pe ea.
 */

/** Locul unei piese pe mascotă (ordinea de desenare e în Mascot.kt). */
enum class Slot(val label: String) {
    HEAD("Cap"), EYES("Ochi"), TORSO("Corp"), BELT("Centură"), FEET("Bocanci"), BACK("Spate"), CHEST("Piept")
}

/** O piesă din garderobă: [rank] = gradul de la care se poate purta; [cost] = 0 → dar la avansarea în acel grad. */
data class GearItem(val id: String, val slot: Slot, val name: String, val rank: Int, val cost: Int) {
    val gift: Boolean get() = cost == 0
}

/** Un grad: [index] 0 = Recrut; [minPoints] = punctele câștigate vreodată de la care începe. */
data class Rank(val index: Int, val name: String, val minPoints: Int)

object Ranks {
    val all: List<Rank> get() = GearCatalog.ranks
    val last: Rank get() = all.last()

    fun of(earned: Int): Rank = all.lastOrNull { earned >= it.minPoints } ?: all.first()
    fun next(rank: Rank): Rank? = all.getOrNull(rank.index + 1)
    fun byIndex(i: Int): Rank = all.getOrNull(i.coerceIn(0, all.size - 1)) ?: all.first()

    /** 0..1 — cât din drumul spre gradul următor e făcut (1 la ultimul grad). */
    fun progress(earned: Int): Float {
        val r = of(earned)
        val n = next(r) ?: return 1f
        val span = (n.minPoints - r.minPoints).coerceAtLeast(1)
        return ((earned - r.minPoints).toFloat() / span).coerceIn(0f, 1f)
    }
}

object Gear {
    val all: List<GearItem> get() = GearCatalog.items
    private val byId: Map<String, GearItem> by lazy { all.associateBy { it.id } }
    fun get(id: String): GearItem? = byId[id]
    fun inSlot(slot: Slot): List<GearItem> = all.filter { it.slot == slot }.sortedWith(compareBy({ it.rank }, { it.cost }))
    /** Darurile unui grad (piesele cu cost 0 ale gradului). */
    fun giftsOf(rank: Int): List<GearItem> = all.filter { it.gift && it.rank == rank }
}

/** Ce poartă mascota acum: id-ul piesei pe fiecare loc (null = nimic) și gradul (insigna de pe piept). */
data class Outfit(
    val head: String? = null,
    val eyes: String? = null,
    val torso: String? = null,
    val belt: String? = null,
    val feet: String? = null,
    val back: String? = null,
    val chest: String? = null,
    val rank: Int = 0
) {
    fun of(slot: Slot): String? = when (slot) {
        Slot.HEAD -> head; Slot.EYES -> eyes; Slot.TORSO -> torso; Slot.BELT -> belt
        Slot.FEET -> feet; Slot.BACK -> back; Slot.CHEST -> chest
    }
    val empty: Boolean get() = head == null && eyes == null && torso == null && belt == null && feet == null && back == null && chest == null

    /** Aceeași ținută cu [id] pe [slot] (null = locul gol) — pentru previzualizări. */
    fun with(slot: Slot, id: String?): Outfit = when (slot) {
        Slot.HEAD -> copy(head = id); Slot.EYES -> copy(eyes = id); Slot.TORSO -> copy(torso = id); Slot.BELT -> copy(belt = id)
        Slot.FEET -> copy(feet = id); Slot.BACK -> copy(back = id); Slot.CHEST -> copy(chest = id)
    }

    companion object {
        val NONE = Outfit()
        /** Ținuta de început: tricou kaki și adidași — Recrutul. */
        val RECRUIT = Outfit(torso = "tshirt_khaki", feet = "sneakers")
    }
}

/**
 * Starea Cascăi, pe telefon (DataStore, JSON): punctele câștigate vreodată, soldul, piesele deținute și purtate,
 * misiunile bifate pe zile (ultimele zile), seria de zile bune, gradele deja sărbătorite și cele cu daruri primite.
 */
data class SoldierState(
    val earned: Int = 0,
    val balance: Int = 0,
    val owned: Set<String> = setOf("tshirt_khaki", "sneakers"),
    val equipped: Map<Slot, String> = mapOf(Slot.TORSO to "tshirt_khaki", Slot.FEET to "sneakers"),
    /** zi (epochDay) → misiunile bifate în ziua aceea. */
    val days: Map<Long, Set<String>> = emptyMap(),
    /** Zile la rând cu cel puțin [Missions.GOOD_DAY] misiuni bifate (ziua de azi intră când ajunge la prag). */
    val streak: Int = 0,
    /** Gradul până la care darurile au fost primite. */
    val gifted: Int = 0,
    /** Gradul până la care avansarea a fost sărbătorită (foaia „Avansat în grad”). */
    val promoSeen: Int = 0,
    val createdAt: Long = 0L
) {
    val rank: Rank get() = Ranks.of(earned)
    val outfit: Outfit
        get() = Outfit(
            head = equipped[Slot.HEAD], eyes = equipped[Slot.EYES], torso = equipped[Slot.TORSO], belt = equipped[Slot.BELT],
            feet = equipped[Slot.FEET], back = equipped[Slot.BACK], chest = equipped[Slot.CHEST], rank = rank.index
        )
    fun doneOn(day: Long): Set<String> = days[day] ?: emptySet()
    /** O avansare nesărbătorită încă? */
    val promotionPending: Boolean get() = rank.index > promoSeen

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("earned", earned); o.put("balance", balance)
        o.put("owned", JSONArray(owned.toList()))
        o.put("equipped", JSONObject().also { e -> equipped.forEach { (s, id) -> e.put(s.name, id) } })
        o.put("days", JSONObject().also { d -> days.forEach { (day, ids) -> d.put(day.toString(), JSONArray(ids.toList())) } })
        o.put("streak", streak); o.put("gifted", gifted); o.put("promoSeen", promoSeen); o.put("createdAt", createdAt)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String?): SoldierState? {
            if (s.isNullOrBlank()) return null
            return try {
                val o = JSONObject(s)
                val owned = LinkedHashSet<String>()
                o.optJSONArray("owned")?.let { a -> for (i in 0 until a.length()) owned += a.getString(i) }
                val equipped = LinkedHashMap<Slot, String>()
                o.optJSONObject("equipped")?.let { e ->
                    for (slot in Slot.entries) e.optString(slot.name, "").takeIf { it.isNotBlank() }?.let { equipped[slot] = it }
                }
                val days = LinkedHashMap<Long, Set<String>>()
                o.optJSONObject("days")?.let { d ->
                    for (k in d.keys()) {
                        val day = k.toLongOrNull() ?: continue
                        val a = d.optJSONArray(k) ?: continue
                        val ids = LinkedHashSet<String>()
                        for (i in 0 until a.length()) ids += a.getString(i)
                        days[day] = ids
                    }
                }
                SoldierState(
                    earned = o.optInt("earned", 0), balance = o.optInt("balance", 0),
                    owned = owned.ifEmpty { setOf("tshirt_khaki", "sneakers") },
                    equipped = equipped, days = days,
                    streak = o.optInt("streak", 0), gifted = o.optInt("gifted", 0), promoSeen = o.optInt("promoSeen", 0),
                    createdAt = o.optLong("createdAt", 0L)
                )
            } catch (_: Exception) { null }
        }
    }
}
