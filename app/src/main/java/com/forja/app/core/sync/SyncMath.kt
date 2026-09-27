package com.forja.app.core.sync

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class SyncFix(val at: Long, val latitude: Double, val longitude: Double, val accuracy: Float, val segment: Int = 0)
data class SyncVisit(val first: Long, val last: Long, val latitude: Double, val longitude: Double, val observedMs: Long, val samples: Int)
data class UsageInterval(val start: Long, val end: Long)

/** Matematica sincronizării: distanțe, opriri observate, durate de utilizare fără dublă numărare. */
object SyncMath {
    fun distance(a: SyncFix, b: SyncFix): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val x = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6371000.0 * 2 * asin(sqrt(x.coerceIn(0.0, 1.0)))
    }

    /** O observație nu e o afirmație despre timpul dintre fixuri îndepărtate sau lipsă. */
    fun visits(fixes: List<SyncFix>): List<SyncVisit> {
        val out = mutableListOf<SyncVisit>()
        var anchor: SyncFix? = null
        var previous: SyncFix? = null
        for (fix in fixes.sortedBy { it.at }) {
            if (fix.accuracy > 100) { anchor = null; previous = null; continue }
            val a = anchor; val p = previous
            if (a == null || p == null || fix.segment != p.segment || fix.at - p.at > 120000 || distance(a, fix) > 75) {
                anchor = fix
                out.add(SyncVisit(fix.at, fix.at, fix.latitude, fix.longitude, 0, 1))
            } else {
                val old = out.last()
                out[out.lastIndex] = old.copy(last = fix.at, observedMs = old.observedMs + (fix.at - p.at).coerceAtLeast(0), samples = old.samples + 1)
            }
            previous = fix
        }
        return out
    }

    /** Reuniunea intervalelor unei aplicații: activitățile suprapuse nu dublează durata. */
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
