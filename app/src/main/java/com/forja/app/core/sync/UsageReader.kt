package com.forja.app.core.sync

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.forja.app.core.focus.FocusMonitorService

data class AppUse(val pkg: String, val label: String, val duration: Long, val opens: Int, val lastUsed: Long)

/** Citirea activității în aplicații (Usage Access) — aceeași verificare ca modulul Focus. */
object UsageReader {
    fun allowed(context: Context): Boolean = FocusMonitorService.hasUsageAccess(context)

    fun read(context: Context, from: Long, to: Long): List<AppUse> {
        check(allowed(context)) { "Accesul la utilizare este oprit în Android." }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(from, to) ?: error("Android nu a returnat istoricul de utilizare. Deblochează telefonul și reîncearcă.")
        val active = mutableMapOf<String, Pair<String, Long>>()
        val spans = mutableMapOf<String, MutableList<UsageInterval>>()
        val opens = mutableMapOf<String, Int>(); val last = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE || event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN) {
                active.values.forEach { (p, start) -> spans.getOrPut(p) { mutableListOf() }.add(UsageInterval(start, event.timeStamp)) }
                active.clear(); continue
            }
            val pkg = event.packageName ?: continue
            val key = "$pkg/${event.className}"
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (key !in active) { active[key] = pkg to event.timeStamp; opens[pkg] = (opens[pkg] ?: 0) + 1 }
                    last[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    active.remove(key)?.let { (p, start) -> spans.getOrPut(p) { mutableListOf() }.add(UsageInterval(start, event.timeStamp)) }
                }
            }
        }
        active.values.forEach { (p, start) -> spans.getOrPut(p) { mutableListOf() }.add(UsageInterval(start, to)) }
        return spans.map { (pkg, values) ->
            val label = try {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString().take(200)
            } catch (_: Exception) { pkg }
            AppUse(pkg, label, SyncMath.duration(values, from, to), opens[pkg] ?: 0, (last[pkg] ?: from).coerceIn(from, to))
        }.filter { it.duration > 0 }.sortedByDescending { it.duration }.take(100)
    }
}
