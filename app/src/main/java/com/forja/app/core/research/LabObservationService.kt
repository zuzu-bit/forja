package com.forja.app.core.research

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Explicitly started from Device / Lab Access; never started at boot or from a push command. */
object LabObservers {
    private var applicationContext: WeakReference<Context>? = null
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning
    @Volatile private var locationRunning = false
    fun isRunning() = mutableRunning.value
    fun isLocationRunning() = locationRunning
    internal fun markRunning(on: Boolean) { mutableRunning.value = on }
    internal fun markLocation(on: Boolean) { locationRunning = on }
    fun hasVisibleNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel("lab_observation")?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun start(context: Context, refreshSource: String? = null) {
        applicationContext = WeakReference(context.applicationContext)
        if (!ForjaApp.from(context).labResearch.isActive()) return
        if (!hasVisibleNotificationPermission(context)) {
            stop()
            ForjaApp.from(context).labResearch.observe("DEVICE", "observer_unavailable", JSONObject()
                .put("reason", "Enable FORJA notifications and the Lab observation channel to show the persistent Stop control"))
            return
        }
        try {
            context.startForegroundService(Intent(context, LabObservationService::class.java).putExtra("refreshSource", refreshSource))
        } catch (e: Exception) {
            ForjaApp.from(context).labResearch.observe("DEVICE", "observer_unavailable", JSONObject()
                .put("reason", "Open Profile to resume lab observation")
                .put("error", e.javaClass.simpleName))
        }
    }
    fun stop() {
        mutableRunning.value = false
        locationRunning = false
        applicationContext?.get()?.let { context ->
            ForjaApp.from(context).labResearch.cancelTransfers()
            context.stopService(Intent(context, LabObservationService::class.java))
        }
    }
}

internal interface LabObserver : AutoCloseable { fun start() }

/** A queued provider callback belongs to the observer's association, never a later enrollment. */
internal class LabCapture(private val context: Context) {
    val deviceId = ForjaApp.from(context).labResearch.session.value?.deviceId
    @Volatile private var closed = false
    fun close() { closed = true }
    fun allows(source: String): Boolean = !closed && context.labAllows(source) &&
        ForjaApp.from(context).labResearch.session.value?.deviceId == deviceId
    fun event(source: String, type: String, payload: JSONObject, timestamp: Long = System.currentTimeMillis()) {
        if (allows(source)) ForjaApp.from(context).labResearch.observe(source, type, payload, timestamp, expectedDeviceId = deviceId)
    }
}

/** A visible foreground session. The stop action ends all lab observation, including NLS capture. */
class LabObservationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = mutableMapOf<String, LabObserver>()
    private var started = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "FORJA Device / Lab Access", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !ForjaApp.from(this).labResearch.isActive() || !LabObservers.hasVisibleNotificationPermission(this)) {
            LabObservers.markRunning(false)
            ForjaApp.from(this).labResearch.cancelTransfers()
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 210, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 211, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("FORJA · Lab observation active")
            .setContentText("Authorized device events are shared with your lab. Tap Stop to pause.")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop lab observation", stop).build()
        val useLocation = ForjaApp.from(this).labResearch.isSourceEnabled("LOCATION") && LabLocationObserver.hasPermission(this)
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (useLocation && Build.VERSION.SDK_INT >= 29) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try { ServiceCompat.startForeground(this, 210, notification, types) }
        catch (e: Exception) {
            LabObservers.markRunning(false)
            ForjaApp.from(this).labResearch.cancelTransfers()
            ForjaApp.from(this).labResearch.observe("DEVICE", "observer_unavailable", JSONObject()
                .put("reason", "Foreground service could not start; resume from Profile")
                .put("error", e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }
        LabObservers.markRunning(true)
        refreshObservers(intent?.getStringExtra("refreshSource"))
        if (!started) {
            started = true
            ForjaApp.from(this).labResearch.observe("DEVICE", "observation_started", JSONObject()
                .put("android", Build.VERSION.RELEASE).put("androidVersion", Build.VERSION.RELEASE)
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("observationActive", true))
            scope.launch {
                while (LabObservers.isRunning()) {
                    delay(30_000)
                    if (!LabObservers.isRunning()) {
                        break
                    }
                    if (!LabObservers.hasVisibleNotificationPermission(this@LabObservationService)) {
                        ForjaApp.from(this@LabObservationService).labResearch.cancelTransfers()
                        stopSelf(); break
                    }
                    if (!ForjaApp.from(this@LabObservationService).labResearch.isActive()) {
                        stopSelf(); break
                    }
                    ForjaApp.from(this@LabObservationService).labResearch.observe("DEVICE", "heartbeat", JSONObject()
                        .put("screen", if (getSystemService(android.os.PowerManager::class.java).isInteractive) "ON" else "OFF")
                        .put("observationActive", true).put("android", Build.VERSION.RELEASE).put("androidVersion", Build.VERSION.RELEASE)
                        .put("model", Build.MANUFACTURER + " " + Build.MODEL))
                    ForjaApp.from(this@LabObservationService).labResearch.syncNow()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun refreshObservers(refreshSource: String?) {
        val controller = ForjaApp.from(this).labResearch
        val enabled = controller.session.value?.enabledSources.orEmpty().intersect(
            setOf("DEVICE", "APP", "MEDIA", "NETWORK", "LOCATION", "BLUETOOTH", "CONTACT", "FILE"))
        // A source toggle preserves other observers and their current-state cursors.
        // Explicit Resume retries previously missing Android grants.
        if (refreshSource == "*") {
            observers.values.forEach { runCatching { it.close() } }; observers.clear()
        } else {
            observers.keys.filter { it !in enabled }.forEach { source -> runCatching { observers.remove(source)?.close() } }
            if (refreshSource != null) {
                runCatching { observers.remove(refreshSource)?.close() }
            }
        }
        fun add(source: String, create: () -> LabObserver) {
            if (!controller.isSourceEnabled(source) || source in observers) {
                return
            }
            try {
                create().also { observers[source] = it; it.start() }
            }
            catch (e: Exception) {
                runCatching { observers.remove(source)?.close() }
                controller.observe("DEVICE", "source_unavailable", JSONObject()
                    .put("source", source).put("reason", e.javaClass.simpleName))
            }
        }
        add("DEVICE") { LabDeviceObserver(this) }
        add("APP") { LabAppObserver(this, scope) }
        add("MEDIA") { LabMediaObserver(this, scope) }
        add("NETWORK") { LabNetworkObserver(this) }
        add("LOCATION") { LabLocationObserver(this) }
        add("BLUETOOTH") { LabBluetoothObserver(this, scope) }
        add("CONTACT") { LabContactsObserver(this, scope) }
        add("FILE") { LabFileObserver(this, scope) }
    }

    override fun onDestroy() {
        LabObservers.markRunning(false)
        LabObservers.markLocation(false)
        ForjaApp.from(this).labResearch.cancelTransfers()
        observers.values.forEach { runCatching { it.close() } }; observers.clear()
        ForjaApp.from(this).labResearch.observe("DEVICE", "observation_paused", JSONObject().put("observationActive", false))
        scope.cancel()
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "lab_observation"; private const val STOP = "com.forja.app.lab.STOP" }
}

internal fun Context.labAllows(source: String): Boolean = LabObservers.isRunning() &&
    LabObservers.hasVisibleNotificationPermission(this) &&
    ForjaApp.from(this).labResearch.isActive() && ForjaApp.from(this).labResearch.isSourceEnabled(source)

internal fun Context.labEvent(source: String, type: String, payload: JSONObject, timestamp: Long = System.currentTimeMillis()) {
    if (labAllows(source)) ForjaApp.from(this).labResearch.observe(source, type, payload, timestamp)
}
