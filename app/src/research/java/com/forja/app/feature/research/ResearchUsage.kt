package com.forja.app.feature.research

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class ResearchAppUse(val pkg: String, val label: String, val duration: Long, val opens: Int, val lastUsed: Long)
object ResearchUsage {
    fun allowed(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun read(context: Context, from: Long, to: Long): List<ResearchAppUse> {
        check(allowed(context)) { "Enable Usage Access for FORJA Research, then read activity." }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(from, to) ?: error("Android did not return usage history. Unlock the phone and try again.")
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
            val label = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString().take(200) } catch (_: Exception) { pkg }
            ResearchAppUse(pkg, label, ResearchMath.duration(values, from, to), opens[pkg] ?: 0, (last[pkg] ?: from).coerceIn(from, to))
        }.filter { it.duration > 0 }.sortedByDescending { it.duration }.take(100)
    }
}
