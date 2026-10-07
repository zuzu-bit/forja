package com.forja.app.core.research

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Android exposes no foreground transition callback to ordinary apps. Query only the new interval. */
internal class LabAppObserver(private val context: Context, private val scope: CoroutineScope) : LabObserver {
    private val capture = LabCapture(context)
    private var job: Job? = null
    private var foreground: String? = null
    private var cursor = System.currentTimeMillis()
    private var consentStart = cursor
    private val seen = LinkedHashSet<String>()
    override fun start() {
        if (!hasUsageAccess(context)) {
            capture.event("APP", "source_unavailable", JSONObject().put("reason", "Grant Usage Access in Android settings")); return
        }
        job = scope.launch {
            val manager = context.getSystemService(UsageStatsManager::class.java)
            while (isActive) {
                try {
                if (capture.allows("APP")) {
                    if (!hasUsageAccess(context)) {
                        capture.event("APP", "source_unavailable", JSONObject().put("reason", "Usage Access revoked")); return@launch
                    }
                    val now = System.currentTimeMillis()
                    if (now < cursor) {
                        capture.event("DEVICE", "clock_changed", JSONObject().put("previousTimestamp", cursor).put("currentTimestamp", now))
                        cursor = now
                        consentStart = now
                    }
                    val events = manager.queryEvents((cursor - 1_000).coerceAtLeast(0), now)
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        // The first query begins at consent time; it does not import historical usage.
                        if (event.timeStamp < consentStart) continue
                        val key = "${event.timeStamp}:${event.eventType}:${event.packageName}:${event.className}"
                        if (!seen.add(key)) continue
                        if (seen.size > 2_000) seen.remove(seen.first())
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && foreground != event.packageName) {
                            foreground = event.packageName
                            capture.event("APP", "foreground_changed", JSONObject().put("package", event.packageName)
                                .put("foreground", event.packageName).put("app", appLabel(context, event.packageName))
                                .put("since", event.timeStamp).put("api", "UsageStatsManager"), event.timeStamp)
                        } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED && foreground == event.packageName) {
                            foreground = null
                            capture.event("APP", "foreground_left", JSONObject().put("package", event.packageName)
                                .put("foreground", JSONObject.NULL).put("since", event.timeStamp), event.timeStamp)
                        }
                    }
                    cursor = now
                }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    capture.event("APP", "source_unavailable", JSONObject().put("reason", e.javaClass.simpleName))
                    return@launch
                }
                delay(5_000)
            }
        }
    }
    override fun close() {
        capture.close()
        job?.cancel()
        job = null
    }
    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
                else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

internal fun appLabel(context: Context, packageName: String): String = runCatching {
    @Suppress("DEPRECATION") context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString().take(256)
}.getOrDefault(packageName.take(256))
