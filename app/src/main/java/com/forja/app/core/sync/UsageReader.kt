package com.forja.app.core.sync

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.forja.app.core.focus.FocusMonitorService
import java.time.LocalDate
import java.time.ZoneId

data class AppUse(val pkg: String, val label: String, val duration: Long, val opens: Int, val lastUsed: Long)

/** O zi încheiată, numărată pe telefon: aplicațiile, orele (ms, fără FORJA) și prima / ultima folosire (fără FORJA). */
data class DayUse(val date: String, val firstAt: Long, val lastAt: Long, val hours: LongArray, val apps: List<AppUse>)

/**
 * Citirea activității în aplicații (Usage Access) — aceeași verificare ca modulul Focus. Deschiderile se numără ca pe
 * ecranul Focus: o aplicație adusă în față după alta (nu fiecare activitate a ei).
 */
object UsageReader {
    fun allowed(context: Context): Boolean = FocusMonitorService.hasUsageAccess(context)

    private class Spans(val spans: Map<String, List<UsageInterval>>, val opens: Map<String, Int>, val last: Map<String, Long>)

    private fun spans(context: Context, from: Long, to: Long): Spans {
        check(allowed(context)) { "Accesul la utilizare este oprit în Android." }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(from, to) ?: error("Android nu a returnat istoricul de utilizare. Deblochează telefonul și reîncearcă.")
        val active = mutableMapOf<String, Pair<String, Long>>()
        val spans = mutableMapOf<String, MutableList<UsageInterval>>()
        val opens = mutableMapOf<String, Int>(); val last = mutableMapOf<String, Long>()
        var lastPkg = ""
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE || event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN) {
                active.values.forEach { (p, start) -> spans.getOrPut(p) { mutableListOf() }.add(UsageInterval(start, event.timeStamp)) }
                active.clear(); lastPkg = ""; continue
            }
            val pkg = event.packageName ?: continue
            val key = "$pkg/${event.className}"
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (key !in active) active[key] = pkg to event.timeStamp
                    if (pkg != lastPkg) { opens[pkg] = (opens[pkg] ?: 0) + 1; lastPkg = pkg }
                    last[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    active.remove(key)?.let { (p, start) -> spans.getOrPut(p) { mutableListOf() }.add(UsageInterval(start, event.timeStamp)) }
                }
            }
        }
        active.values.forEach { (p, start) -> spans.getOrPut(p) { mutableListOf() }.add(UsageInterval(start, to)) }
        return Spans(spans, opens, last)
    }

    private fun label(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString().take(200)
    } catch (_: Exception) { pkg }

    fun read(context: Context, from: Long, to: Long): List<AppUse> {
        val s = spans(context, from, to)
        return s.spans.map { (pkg, values) ->
            AppUse(pkg, label(context, pkg), SyncMath.duration(values, from, to), s.opens[pkg] ?: 0, (s.last[pkg] ?: from).coerceIn(from, to))
        }.filter { it.duration > 0 }.sortedByDescending { it.duration }.take(100)
    }

    /** Ziua locală `date`, întreagă: pentru `usage_backfill` (zilele pe care sesiunea live le-a prins doar în parte). */
    fun readDay(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayUse {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val s = spans(context, from, to)
        val (hours, first, lastAt) = UsageDay.hours(s.spans, context.packageName, from, to, zone)
        val apps = s.spans.map { (pkg, values) ->
            // Ultima folosire a zilei: ultimul eveniment sau, fără el, capătul ultimului interval („ultima” pe Pază).
            val last = s.last[pkg] ?: values.maxOfOrNull { it.end } ?: from
            AppUse(pkg, label(context, pkg), SyncMath.duration(values, from, to), s.opens[pkg] ?: 0, last.coerceIn(from, to))
        }.filter { it.duration > 0 || it.opens > 0 }.sortedByDescending { it.duration }.take(40)
        return DayUse(date.toString(), first, lastAt, hours, apps)
    }
}
