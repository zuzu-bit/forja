package com.forja.app.core.research

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** User-authorized BLE discovery, bounded to 15 seconds every two minutes, always stopped with the session. */
internal class LabBluetoothObserver(private val context: Context, private val scope: CoroutineScope) : LabObserver {
    private val capture = LabCapture(context)
    private var job: Job? = null
    private var scanning = false
    private val lastSeen = mutableMapOf<String, Long>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!capture.allows("BLUETOOTH") || !hasPermission()) return
            val canReadDevice = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
            val identifier = if (canReadDevice) runCatching { result.device.address }.getOrNull() else null
            val name = result.scanRecord?.deviceName ?: if (canReadDevice) runCatching { result.device.name }.getOrNull() else null
            val key = identifier ?: result.scanRecord?.bytes?.contentHashCode()?.toString() ?: "unknown"
            val now = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            val previous = lastSeen[key]
            if (previous != null && elapsed - previous < 5_000) return
            if (lastSeen.size > 1_000) lastSeen.clear()
            lastSeen[key] = elapsed
            val timestamp = now - ((android.os.SystemClock.elapsedRealtimeNanos() - result.timestampNanos).coerceAtLeast(0) / 1_000_000)
            capture.event("BLUETOOTH", "device_observed", JSONObject().put("name", name ?: JSONObject.NULL)
                .put("identifier", identifier ?: JSONObject.NULL).put("rssi", result.rssi).put("timestamp", timestamp)
                .put("identifierMayRotate", true).put("transport", "BLE"), timestamp)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            capture.event("BLUETOOTH", "scan_failed", JSONObject().put("errorCode", errorCode))
        }
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun hasPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN))
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!hasPermission()) {
            capture.event("BLUETOOTH", "source_unavailable", JSONObject().put("reason", "Bluetooth scan and location permission required")); return
        }
        job = scope.launch {
            while (isActive && capture.allows("BLUETOOTH")) {
                if (!hasPermission()) break
                val scanner = runCatching { manager?.adapter?.bluetoothLeScanner }.getOrNull()
                if (scanner == null) {
                    capture.event("BLUETOOTH", "source_unavailable", JSONObject().put("reason", "Bluetooth unavailable or disabled"))
                    delay(120_000); continue
                }
                try {
                    scanning = true
                    scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
                    capture.event("BLUETOOTH", "scan_started", JSONObject().put("durationMs", 15_000))
                    delay(15_000)
                } catch (e: Exception) {
                    capture.event("BLUETOOTH", "scan_failed", JSONObject().put("reason", e.javaClass.simpleName))
                } finally { stopScan() }
                delay(105_000)
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) {
            runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
            scanning = false
            capture.event("BLUETOOTH", "scan_stopped", JSONObject())
        }
    }
    override fun close() {
        capture.close()
        job?.cancel()
        job = null
        stopScan()
    }
}
