package com.forja.app.core.music

/**
 * Ce a mers și ce nu, per player și versiune: pentru fiecare treaptă, reușite, eșecuri și eșecurile la rând.
 * O treaptă cu 2 eșecuri la rând se sare 14 zile; o versiune nouă a playerului (alt major.minor) pornește de la zero.
 * Tot aici se ordonează treptele vizibile (cea care a mers prima). Kotlin pur, imuabil; se păstrează ca text.
 */
class LearnedTable private constructor(private val map: Map<String, Map<Rung, RungStats>>) {

    data class RungStats(
        val ok: Int = 0,
        val fail: Int = 0,
        val consecutive: Int = 0,
        val lastFailAt: Long = 0L,
        /** Pornea muzică, dar altă piesă decât cea cerută. */
        val miss: Int = 0,
        val lastOkAt: Long = 0L
    )

    enum class Outcome { OK, FAIL, MISS }

    fun stats(pkg: String, version: String?, rung: Rung): RungStats = map[slot(pkg, version)]?.get(rung) ?: RungStats()

    /** Sare treapta: 2 eșecuri la rând, cel mai recent acum mai puțin de 14 zile. */
    fun skip(pkg: String?, version: String?, rung: Rung, now: Long): Boolean {
        if (pkg == null) return false
        val s = map[slot(pkg, version)]?.get(rung) ?: return false
        return s.consecutive >= 2 && now - s.lastFailAt < SKIP_MS
    }

    /** Lista FORJA (comenzi piesă cu piesă) doar dacă playerul chiar a pus piesa cerută. */
    fun tracksLand(pkg: String?, version: String?): Boolean {
        if (pkg == null) return true
        val s = map[slot(pkg, version)]?.get(Rung.S_TOP) ?: return true
        return !(s.miss >= 2 && s.lastOkAt <= s.lastFailAt)
    }

    fun record(pkg: String?, version: String?, rung: Rung, outcome: Outcome, now: Long): LearnedTable {
        if (pkg == null) return this
        val key = slot(pkg, version)
        val row = map[key].orEmpty()
        val s = row[rung] ?: RungStats()
        val next = when (outcome) {
            Outcome.OK -> s.copy(ok = s.ok + 1, consecutive = 0, lastOkAt = now)
            Outcome.FAIL -> s.copy(fail = s.fail + 1, consecutive = s.consecutive + 1, lastFailAt = now)
            Outcome.MISS -> s.copy(ok = s.ok + 1, miss = s.miss + 1, consecutive = 0, lastFailAt = now)
        }
        // Versiunile vechi ale aceluiași player nu mai contează: „o actualizare îl resetează”.
        val pruned = map.filterKeys { !(it.startsWith("$pkg@") && it != key) }
        return LearnedTable(pruned + (key to (row + (rung to next))))
    }

    /** Ordinea încercării: întâi ce a mers (reușite − eșecuri), la egalitate ordinea dată. */
    fun order(pkg: String?, version: String?, rungs: List<Rung>): List<Rung> {
        if (pkg == null) return rungs
        val row = map[slot(pkg, version)] ?: return rungs
        return rungs.withIndex().sortedWith(
            compareByDescending<IndexedValue<Rung>> { v -> row[v.value]?.let { it.ok - it.fail } ?: 0 }.thenBy { it.index }
        ).map { it.value }
    }

    fun isEmpty(): Boolean = map.isEmpty()

    /** pachet@versiune⇥treaptă⇥ok⇥fail⇥laRând⇥ultimulEșec⇥ratări⇥ultimaReușită, câte un rând. */
    fun encode(): String = buildString {
        for ((slot, row) in map) for ((rung, s) in row) {
            if (isNotEmpty()) append('\n')
            append(slot).append('\t').append(rung.id).append('\t').append(s.ok).append('\t').append(s.fail).append('\t')
                .append(s.consecutive).append('\t').append(s.lastFailAt).append('\t').append(s.miss).append('\t').append(s.lastOkAt)
        }
    }

    override fun equals(other: Any?): Boolean = other is LearnedTable && other.map == map
    override fun hashCode(): Int = map.hashCode()

    companion object {
        const val SKIP_MS = 14L * 24 * 3600 * 1000
        val EMPTY = LearnedTable(emptyMap())

        /** „9.0.62.1055” → „9.0”; necunoscut → „?”. */
        fun versionKey(version: String?): String {
            val parts = version?.trim()?.split('.', '-', ' ')?.filter { it.isNotEmpty() }.orEmpty()
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0]
                else -> parts[0] + "." + parts[1]
            }
        }

        private fun slot(pkg: String, version: String?) = "$pkg@${versionKey(version)}"

        fun decode(raw: String?): LearnedTable {
            if (raw.isNullOrBlank()) return EMPTY
            val out = LinkedHashMap<String, MutableMap<Rung, RungStats>>()
            for (line in raw.lineSequence()) {
                val p = line.split('\t')
                if (p.size < 8) continue
                val rung = Rung.of(p[1]) ?: continue
                val s = RungStats(
                    ok = p[2].toIntOrNull() ?: 0, fail = p[3].toIntOrNull() ?: 0, consecutive = p[4].toIntOrNull() ?: 0,
                    lastFailAt = p[5].toLongOrNull() ?: 0L, miss = p[6].toIntOrNull() ?: 0, lastOkAt = p[7].toLongOrNull() ?: 0L
                )
                out.getOrPut(p[0]) { LinkedHashMap() }[rung] = s
            }
            return LearnedTable(out)
        }
    }
}
