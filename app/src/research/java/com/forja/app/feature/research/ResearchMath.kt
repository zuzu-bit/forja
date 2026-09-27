package com.forja.app.feature.research

import kotlin.math.*

data class ResearchFix(val at: Long, val latitude: Double, val longitude: Double, val accuracy: Float, val segment: Int = 0)
data class ResearchVisit(val first: Long, val last: Long, val latitude: Double, val longitude: Double, val observedMs: Long, val samples: Int)
data class UsageInterval(val start: Long, val end: Long)

object ResearchMath {
    fun distance(a: ResearchFix, b: ResearchFix): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val x = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6371000.0 * 2 * asin(sqrt(x.coerceIn(0.0, 1.0)))
    }
    /** An observation is not a claim about time between distant or missing fixes. */
    fun visits(fixes: List<ResearchFix>): List<ResearchVisit> {
        val out = mutableListOf<ResearchVisit>()
        var anchor: ResearchFix? = null
        var previous: ResearchFix? = null
        for (fix in fixes.sortedBy { it.at }) {
            if (fix.accuracy > 100) { anchor = null; previous = null; continue }
            val a = anchor; val p = previous
            if (a == null || p == null || fix.segment != p.segment || fix.at - p.at > 120000 || distance(a, fix) > 75) {
                anchor = fix
                out.add(ResearchVisit(fix.at, fix.at, fix.latitude, fix.longitude, 0, 1))
            } else {
                val old = out.last()
                out[out.lastIndex] = old.copy(last = fix.at, observedMs = old.observedMs + (fix.at - p.at).coerceAtLeast(0), samples = old.samples + 1)
            }
            previous = fix
        }
        return out
    }
    /** Union per-app intervals: overlapping activities must not double-count app duration. */
    fun duration(intervals: List<UsageInterval>, from: Long, to: Long): Long {
        val sorted = intervals.map { UsageInterval(max(it.start, from), min(it.end, to)) }.filter { it.end > it.start }.sortedBy { it.start }
        var total = 0L; var start = 0L; var end = 0L
        sorted.forEachIndexed { i, v ->
            if (i == 0) { start = v.start; end = v.end }
            else if (v.start <= end) end = max(end, v.end)
            else { total += end - start; start = v.start; end = v.end }
        }
        return total + if (sorted.isEmpty()) 0 else end - start
    }
}
