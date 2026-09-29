package com.forja.app.core.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Zilele încheiate pentru `usage_backfill` (mirror D): orele unei zile din intervalele de folosire, fără dublă numărare,
 * și ce zile se trimit. Pur (fără Android), testabil pe JVM.
 */
object UsageDay {
    /** Câte zile încheiate se trimit (ieri … acum 7 zile): cât ține Android istoricul de evenimente. */
    const val BACK_DAYS = 7

    /** Reuniunea intervalelor, tăiată la [from, to). */
    fun union(intervals: List<UsageInterval>, from: Long, to: Long): List<UsageInterval> {
        val sorted = intervals.map { UsageInterval(maxOf(it.start, from), minOf(it.end, to)) }.filter { it.end > it.start }.sortedBy { it.start }
        val out = ArrayList<UsageInterval>()
        for (v in sorted) {
            val last = out.lastOrNull()
            if (last != null && v.start <= last.end) out[out.lastIndex] = UsageInterval(last.start, maxOf(last.end, v.end)) else out += v
        }
        return out
    }

    /** Împarte [a, b) pe orele locale: (ora 0–23, ms). */
    fun hourSplit(a: Long, b: Long, zone: ZoneId): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        var at = a
        var guard = 0
        while (at < b && guard++ < 48) {
            val z = Instant.ofEpochMilli(at).atZone(zone)
            val next = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
            val end = minOf(b, next)
            out += z.hour to (end - at)
            at = end
        }
        return out
    }

    /** Orele (ms), prima și ultima folosire ale zilei, din intervalele tuturor aplicațiilor în afară de `self`. */
    fun hours(spans: Map<String, List<UsageInterval>>, self: String, from: Long, to: Long, zone: ZoneId): Triple<LongArray, Long, Long> {
        val hours = LongArray(24)
        var first = 0L; var last = 0L
        for ((pkg, values) in spans) {
            if (pkg == self || pkg.startsWith("com.forja.app")) continue
            for (v in union(values, from, to)) {
                if (first == 0L || v.start < first) first = v.start
                if (v.end > last) last = v.end
                for ((h, ms) in hourSplit(v.start, v.end, zone)) hours[h] += ms
            }
        }
        return Triple(hours, first, last)
    }

    /** Zilele de trimis, cea mai veche prima: ieri … acum [BACK_DAYS] zile. */
    fun daysToSend(today: LocalDate): List<LocalDate> = (BACK_DAYS downTo 1).map { today.minusDays(it.toLong()) }
}
