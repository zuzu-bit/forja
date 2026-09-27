package com.forja.app.core.sync

/** Regulile de siguranță: fără cont potrivit sau fără revizia curentă nu se colectează nimic. */
object CollectionPolicy {
    fun allowed(owner: String?, current: String?, selected: Set<String>, granted: Set<String>, revision: Long, currentRevision: Long): Set<String> =
        if (owner == null || owner != current || revision != currentRevision) emptySet() else selected.intersect(granted)

    /** Fereastra de utilizare a aplicațiilor: de la activare, dar nu înainte de sesiune și nu mai mult de 24 h. */
    fun usageFrom(activated: Long, sessionStart: Long, now: Long): Long =
        maxOf(activated, sessionStart, now - 86400000L).coerceAtMost(now)
}
