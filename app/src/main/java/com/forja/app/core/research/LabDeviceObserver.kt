package com.forja.app.core.research

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

internal class LabDeviceObserver(private val context: Context) : LabObserver {
    private val capture = LabCapture(context)
    private var lastBattery = -1
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> {
                    capture.event("DEVICE", "screen_changed", JSONObject().put("screen",
                        if (context.getSystemService(PowerManager::class.java).isInteractive) "ON" else "OFF"))
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    if (percent != lastBattery) {
                        lastBattery = percent
                        capture.event("DEVICE", "battery_changed", JSONObject().put("batteryPercent", percent)
                            .put("battery", percent)
                            .put("charging", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0))
                    }
                }
                Intent.ACTION_TIME_CHANGED -> capture.event("DEVICE", "clock_changed", JSONObject().put("reason", "Android TIME_SET"))
            }
        }
    }
    override fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        capture.event("DEVICE", "screen_changed", JSONObject().put("screen",
            if (context.getSystemService(PowerManager::class.java).isInteractive) "ON" else "OFF"))
    }
    override fun close() {
        capture.close()
        if (registered) { runCatching { context.unregisterReceiver(receiver) } }
        registered = false
    }
}
