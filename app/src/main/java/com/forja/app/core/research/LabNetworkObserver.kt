package com.forja.app.core.research

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject

internal class LabNetworkObserver(private val context: Context) : LabObserver {
    private val capture = LabCapture(context)
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var last = ""
    private val flags = if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0
    private val callback = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(flags) {
        override fun onAvailable(network: Network) { publish(network, manager.getNetworkCapabilities(network)) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { publish(network, networkCapabilities) }
        override fun onLost(network: Network) { publish(manager.activeNetwork, manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }) }
    } else object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { publish(network, manager.getNetworkCapabilities(network)) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { publish(network, networkCapabilities) }
        override fun onLost(network: Network) { publish(manager.activeNetwork, manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }) }
    }
    override fun start() {
        manager.registerDefaultNetworkCallback(callback); registered = true
        publish(manager.activeNetwork, manager.activeNetwork?.let { manager.getNetworkCapabilities(it) })
    }
    private fun publish(network: Network?, capabilities: NetworkCapabilities?) {
        if (!capture.allows("NETWORK")) return
        val kind = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        val wifi = if (kind == "Wi-Fi") runCatching {
            if (Build.VERSION.SDK_INT >= 29) capabilities?.transportInfo as? WifiInfo
            else @Suppress("DEPRECATION") context.getSystemService(WifiManager::class.java).connectionInfo
        }.getOrNull() else null
        val ssid = runCatching { wifi?.ssid }.getOrNull()?.trim('"')?.takeUnless { it == "<unknown ssid>" || it.isBlank() }
        val bssid = runCatching { wifi?.bssid }.getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" || it.isBlank() }
        val payload = JSONObject().put("connectionType", kind).put("connected", network != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("ssid", ssid ?: JSONObject.NULL).put("bssid", bssid ?: JSONObject.NULL)
            .put("wifiIdentifiersAvailable", ssid != null || bssid != null)
        if (last != payload.toString()) { last = payload.toString(); capture.event("NETWORK", "connectivity_changed", payload) }
    }
    override fun close() {
        capture.close()
        if (registered) { runCatching { manager.unregisterNetworkCallback(callback) } }
        registered = false
    }
}
