package com.forja.app.feature.research

object CollectionPolicy {
    fun allowed(owner: String?, current: String?, selected: Set<String>, granted: Set<String>, revision: Long, currentRevision: Long): Set<String> =
        if (owner == null || owner != current || revision != currentRevision) emptySet() else selected.intersect(granted)
    fun usageFrom(activated: Long, sessionStart: Long, now: Long): Long = maxOf(activated, sessionStart, now - 86400000L).coerceAtMost(now)
}
