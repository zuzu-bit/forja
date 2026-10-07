package com.forja.app.core.research

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.room.withTransaction
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LabSyncStatus(val lastSyncAt: Long? = null, val error: String? = null)

/** Consent boundary shared by collectors and existing fitness features. Normal features never wait for it. */
@OptIn(ExperimentalCoroutinesApi::class)
class LabController(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val store = LabSession(this.context)
    private val database = LabDatabase.get(this.context)
    private val journal = LabJournal(database)
    private val api = LabApi()
    private val restoredAssociation = store.load()
    private val _session = MutableStateFlow(restoredAssociation?.takeIf { association ->
        association.ownerUid == auth.currentUser?.uid && store.revocations().none { it.deviceId == association.deviceId }
    })
    val session: StateFlow<LabAssociation?> = _session.asStateFlow()
    private val _syncStatus = MutableStateFlow(LabSyncStatus())
    val syncStatus: StateFlow<LabSyncStatus> = _syncStatus.asStateFlow()
    val pendingCount: StateFlow<Int> = session.flatMapLatest { association ->
        if (association == null) flowOf(0) else database.events().pendingFlow(association.ownerUid, association.labSessionId, association.deviceId)
    }.catch {
        _syncStatus.value = _syncStatus.value.copy(error = "Jurnalul local Lab nu este disponibil. Funcțiile fitness rămân independente.")
        emit(0)
    }.stateIn(scope, SharingStarted.Eagerly, 0)
    val available: Boolean get() = api.available
    private val syncMutex = Mutex()
    private val commandMutex = Mutex()
    private var immediateSync: Job? = null
    private var commandJob: Job? = null
    private data class ActiveTransfer(val job: Job, val source: String)
    @Volatile private var activeTransfer: ActiveTransfer? = null
    private var syncSignalled = false
    private var lastWorkerScheduled = 0L
    private var lastUploadAttempt = 0L
    @Volatile private var lastCommandCheck = 0L
    @Volatile private var lastHeartbeat = 0L
    private data class Observation(val session: LabAssociation, val source: String, val type: String, val payload: String, val timestamp: Long, val receivedTimestamp: Long)
    private val observations = Channel<Observation>(Channel.UNLIMITED)

    init {
        if (_session.value == null) {
            runCatching {
                if (restoredAssociation != null) {
                    store.addRevocation(restoredAssociation)
                    LabArtifacts.clearTree(this.context, restoredAssociation)
                }
                store.clear()
            }.onFailure { _syncStatus.value = _syncStatus.value.copy(error = "Revocarea locală nu a putut fi salvată. Observarea rămâne oprită.") }
        }
        auth.addAuthStateListener {
            if (_session.value?.ownerUid?.let { uid -> uid != it.currentUser?.uid } == true) deactivate("Sesiunea Lab s-a oprit: contul autentificat s-a schimbat.")
            if (hasPendingRevocations()) runCatching { LabSyncWorker.enqueue(this.context) }
        }
        scope.launch(Dispatchers.IO) {
            for (item in observations) {
                try {
                    // Accepted observations keep their capture-time ownership and consent, even if
                    // collection is paused before the asynchronous Room transaction completes.
                    journal.append(item.session, item.source, item.type, item.payload, item.timestamp, item.receivedTimestamp)
                    syncNow()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { _syncStatus.value = _syncStatus.value.copy(error = "Jurnalul local Lab nu a putut salva evenimentul.") }
            }
        }
        if (isActive() || hasPendingRevocations()) runCatching { LabSyncWorker.enqueue(this.context) }
    }

    private fun current(): LabAssociation? = session.value?.takeIf { it.ownerUid == auth.currentUser?.uid }
    fun isActive(): Boolean = current() != null
    internal fun hasPendingRevocations(): Boolean = store.revocations().any { it.ownerUid == auth.currentUser?.uid }
    fun isSourceEnabled(source: String): Boolean = current()?.enabledSources?.contains(source.uppercase()) == true
    fun capabilities(): JSONObject {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val result = JSONObject().put("enabledSources", JSONArray(current()?.enabledSources?.sorted().orEmpty()))
        for (source in LabJournalPolicy.sources) {
            val permission = when (source) {
                "APP" -> if (LabAppObserver.hasUsageAccess(context)) "granted" else "missing"
                "NOTIFICATION" -> if ((if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, LabNotificationListener::class.java)) else NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))) "granted" else "missing"
                "CONTACT" -> if (granted(Manifest.permission.READ_CONTACTS)) "granted" else "missing"
                "LOCATION" -> if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "granted" else if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "partial" else "missing"
                "MEDIA" -> if (Build.VERSION.SDK_INT < 33) { if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) "granted" else "missing" }
                    else if (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) "granted"
                    else if (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) || (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) "partial" else "missing"
                "BLUETOOTH" -> if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN))) "granted" else "missing"
                "FILE" -> if (runCatching { LabArtifacts.tree(context)?.let { tree -> context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission } } == true }.getOrDefault(false)) "granted" else "missing"
                "ACTIVITY", "SLEEP", "NUTRITION" -> "existing_feature"
                else -> "granted"
            }
            val enabled = isSourceEnabled(source)
            result.put(source, JSONObject().put("enabled", enabled).put("permission", permission)
                .put("available", enabled && permission != "missing" && (source == "DEVICE" || (LabObservers.isRunning() && LabObservers.hasVisibleNotificationPermission(context)))))
        }
        return result
    }

    /** No exceptions or suspending work enter existing fitness/lifestyle call paths. */
    fun observe(source: String, type: String, payload: JSONObject, sourceTimestamp: Long = System.currentTimeMillis(), expectedDeviceId: String? = null) {
        try {
            val receivedTimestamp = System.currentTimeMillis()
            val canonical = source.uppercase()
            val association = current() ?: return
            if (expectedDeviceId != null && association.deviceId != expectedDeviceId) return
            if (canonical !in association.enabledSources || (canonical != "DEVICE" && (!LabObservers.isRunning() || !LabObservers.hasVisibleNotificationPermission(context))) || canonical !in LabJournalPolicy.sources || !LabJournalPolicy.validType(type) || sourceTimestamp < 0) return
            val json = payload.toString()
            if (json.toByteArray(Charsets.UTF_8).size > 32_768) {
                _syncStatus.value = _syncStatus.value.copy(error = "Un eveniment depășește limita de 32 KiB; sursa trebuie să reducă metadatele.")
                return
            }
            observations.trySend(Observation(association, canonical, type, json, sourceTimestamp, receivedTimestamp))
        } catch (_: Exception) { /* Optional research must never interrupt fitness. */ }
    }

    suspend fun enroll(enrollmentCode: String, label: String) = syncMutex.withLock {
        check(current() == null) { "Deconectează întâi sesiunea Lab existentă." }
        val uid = auth.currentUser?.uid ?: error("Autentifică-te înainte de asociere.")
        require(enrollmentCode.trim().length in 8..128) { "Introdu codul Lab emis de organizator." }
        require(label.trim().length in 1..80) { "Introdu numele telefonului de laborator." }
        // Each newly authorised association starts its own immutable device sequence at 1.
        val id = UUID.randomUUID().toString()
        // Persist cleanup intent before the HTTP request: even a cancelled response or failed
        // preference write cannot strand an accepted server association without a retry.
        store.addRevocation(LabAssociation(id, uid, "", label.trim(), "pending"))
        try {
        val response = api.request(uid, "POST", "/devices/enroll", JSONObject()
            .put("deviceId", id).put("enrollmentCode", enrollmentCode.trim()).put("consent", true)
            .put("label", label.trim()).put("androidVersion", Build.VERSION.RELEASE)
            .put("capabilities", JSONObject().put("enabledSources", JSONArray(listOf("DEVICE")))))
        check(auth.currentUser?.uid == uid && current() == null) { "Contul sau sesiunea s-a schimbat. Asocierea nu a fost activată local." }
        check(response.getString("deviceId") == id && response.getString("ownerUid") == uid) { "Asocierea serverului nu corespunde telefonului și contului." }
        val association = LabAssociation(id, uid, response.getString("labSessionId"), response.optString("label", label.trim()), response.getString("consentVersion"))
        store.save(association)
        _session.value = association
        store.removeRevocation(id)
        lastCommandCheck = 0L
        lastHeartbeat = 0L
        _syncStatus.value = LabSyncStatus()
        LabObservers.start(context)
        observe("DEVICE", "lab_associated", JSONObject().put("consentVersion", association.consentVersion))
        syncNow()
        } finally {
            if (hasPendingRevocations()) LabSyncWorker.enqueue(context)
        }
    }

    /** Called from the visible Profile screen; this never requests or bypasses Android permissions. */
    fun setSourceEnabled(source: String, enabled: Boolean) {
        val canonical = source.uppercase()
        require(canonical in LabJournalPolicy.sources && canonical != "DEVICE")
        val active = current() ?: return
        val sources = if (enabled) active.enabledSources + canonical else active.enabledSources - canonical
        val updated = active.copy(enabledSources = sources)
        store.save(updated)
        _session.value = updated
        if (!enabled) cancelTransfers(canonical)
        lastHeartbeat = 0L
        observe("DEVICE", "source_consent_changed", JSONObject().put("source", canonical).put("enabled", enabled).put("capabilities", capabilities()))
        LabObservers.start(context, canonical)
    }

    fun resume() { if (isActive()) LabObservers.start(context, "*") }
    fun pause() { cancelTransfers(); LabObservers.stop() }

    /** Pause/revoke cancels reads and HTTP bodies independently of the telemetry journal. */
    fun cancelTransfers(source: String? = null) {
        synchronized(this) {
            if (source == null) commandJob?.cancel()
            else activeTransfer?.takeIf { it.source == source }?.job?.cancel()
        }
    }

    suspend fun disconnect() {
        // Local consent is revoked before the network request, including when the phone is offline.
        deactivate(null)
        if (hasPendingRevocations()) {
            try { syncMutex.withLock { flushRevocations() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _syncStatus.value = LabSyncStatus(error = "Accesul local este oprit. Revocarea serverului va fi reîncercată online când contul asociat este autentificat.") }
        }
    }

    private fun deactivate(message: String?) {
        val association = _session.value
        _session.value = null
        cancelTransfers()
        immediateSync?.cancel()
        LabObservers.stop()
        // Stopping is unconditional, including storage-full or revoked SAF-provider failures.
        var cleanupFailed = false
        if (association != null) runCatching { store.addRevocation(association) }.onFailure { cleanupFailed = true }
        runCatching { store.clear() }.onFailure { cleanupFailed = true }
        runCatching { LabArtifacts.clearTree(context, association) }.onFailure { cleanupFailed = true }
        runCatching { LabSyncWorker.cancel(context) }
        _syncStatus.value = _syncStatus.value.copy(error = if (cleanupFailed) "Observarea este oprită. Stocarea locală nu a putut salva complet revocarea; cere organizatorului revocarea serverului." else message)
        if (hasPendingRevocations()) runCatching { LabSyncWorker.enqueue(context) }
    }

    fun syncNow() {
        if (!isActive() && !hasPendingRevocations()) return
        synchronized(this) {
            if (lastWorkerScheduled == 0L || SystemClock.elapsedRealtime() - lastWorkerScheduled >= 5_000) {
                lastWorkerScheduled = SystemClock.elapsedRealtime()
                LabSyncWorker.enqueue(context)
            }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        if (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) return
        synchronized(this) {
            syncSignalled = true
            if (immediateSync?.isActive == true) return
            immediateSync = scope.launch(Dispatchers.IO) {
                while (currentCoroutineContext().isActive && (this@LabController.isActive() || hasPendingRevocations())) {
                    synchronized(this@LabController) { syncSignalled = false }
                    delay(400) // Combine callback bursts without polling individual data sources.
                    val completed = flush()
                    val association = current()
                    val pending = association?.let { database.events().pendingCount(it.ownerUid, it.labSessionId, it.deviceId) } ?: 0
                    val repeat = synchronized(this@LabController) {
                        if (completed && (syncSignalled || pending > 0)) true
                        else { immediateSync = null; false }
                    }
                    if (!repeat) return@launch
                }
                synchronized(this@LabController) { immediateSync = null }
            }
        }
    }

    /** Shared lock serialises foreground requests and WorkManager retries; unknown ack IDs are ignored. */
    internal suspend fun flush(): Boolean = syncMutex.withLock {
        try {
            flushRevocations()
            val association = current() ?: return@withLock true
            while (current()?.deviceId == association.deviceId) {
                val pending = database.events().pending(association.ownerUid, association.labSessionId, association.deviceId)
                if (pending.isEmpty()) break
                var bytes = 0
                val batch = pending.takeWhile { event ->
                    bytes += event.payload.toByteArray(Charsets.UTF_8).size + 1_024
                    bytes <= 900_000
                }
                val pacing = 750 - (SystemClock.elapsedRealtime() - lastUploadAttempt)
                if (pacing > 0) delay(pacing)
                if (current()?.deviceId != association.deviceId) return@withLock true
                lastUploadAttempt = SystemClock.elapsedRealtime()
                val ack = api.upload(association, batch) {
                    check(current()?.deviceId == association.deviceId) { "Sincronizarea acestei asocieri a fost oprită." }
                }
                if (current()?.deviceId != association.deviceId) return@withLock true
                val accepted = strings(ack.optJSONArray("acceptedEventIds"))
                val ids = LabJournalPolicy.acknowledged(batch.map { it.eventId }, accepted, strings(ack.optJSONArray("duplicateEventIds")))
                val serverTime = ack.getLong("serverReceivedTimestamp")
                val timestamps = ack.optJSONObject("eventReceivedTimestamps")
                // Duplicate acknowledgements refer to the original server receipt, not this retry.
                database.withTransaction {
                    for ((time, group) in ids.groupBy { id ->
                        if (timestamps?.has(id) == true) timestamps.getLong(id) else if (id in accepted) serverTime else null
                    }) database.events().acknowledge(group, association.ownerUid, association.labSessionId, association.deviceId, time)
                }
                _syncStatus.value = LabSyncStatus(lastSyncAt = System.currentTimeMillis())
                if (ids.size != batch.size) {
                    _syncStatus.value = _syncStatus.value.copy(error = "Serverul nu a confirmat toate evenimentele. Jurnalul local a fost păstrat.")
                    return@withLock false
                }
            }
            if (current()?.deviceId == association.deviceId) {
                if (lastHeartbeat == 0L || SystemClock.elapsedRealtime() - lastHeartbeat >= 25_000) {
                    api.request(association.ownerUid, "POST", "/devices/${association.deviceId}/heartbeat", JSONObject()
                        .put("state", JSONObject().put("observationActive", LabObservers.isRunning()))
                        .put("capabilities", capabilities())) {
                        check(current()?.deviceId == association.deviceId) { "Asocierea a fost oprită." }
                    }
                    lastHeartbeat = SystemClock.elapsedRealtime()
                }
                if (LabObservers.isRunning()) scheduleCommands(association)
            }
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (error is LabApiException && error.code in setOf(401, 403)) deactivate("Accesul Lab a fost revocat sau autentificarea a expirat.")
            else _syncStatus.value = _syncStatus.value.copy(error = error.message ?: "Sincronizarea va fi reîncercată automat.")
            !isActive() && !hasPendingRevocations()
        }
    }

    private suspend fun flushRevocations() {
        val uid = auth.currentUser?.uid ?: return
        var confirmed = false
        for (pending in store.revocations().filter { it.ownerUid == uid }) {
            try { api.request(uid, "DELETE", "/devices/${pending.deviceId}/association") }
            catch (error: LabApiException) { if (error.code != 404) throw error }
            store.removeRevocation(pending.deviceId)
            confirmed = true
        }
        if (confirmed) _syncStatus.value = _syncStatus.value.copy(lastSyncAt = System.currentTimeMillis(), error = null)
    }

    private fun scheduleCommands(association: LabAssociation) {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (commandJob?.isActive == true || (lastCommandCheck != 0L && now - lastCommandCheck < 25_000)) return
            lastCommandCheck = now
            commandJob = scope.launch(Dispatchers.IO) {
                val ownJob = currentCoroutineContext().job
                try {
                    commandMutex.withLock {
                        if (current()?.deviceId == association.deviceId && LabObservers.isRunning()) processCommands(association)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    if (current()?.deviceId == association.deviceId) {
                        if (error is LabApiException && error.code in setOf(401, 403)) deactivate("Accesul Lab a fost revocat sau autentificarea a expirat.")
                        else _syncStatus.value = _syncStatus.value.copy(error = error.message ?: "Cererea de artefact va fi reîncercată.")
                    }
                } finally {
                    synchronized(this@LabController) {
                        if (activeTransfer?.job === ownJob) activeTransfer = null
                        if (commandJob === ownJob) commandJob = null
                    }
                }
            }
        }
    }

    private suspend fun processCommands(association: LabAssociation) {
        val response = api.request(association.ownerUid, "GET", "/devices/${association.deviceId}/commands")
        val serverTime = response.optLong("serverTimestamp", Long.MIN_VALUE)
        if (serverTime <= 0) return
        val pollReceived = SystemClock.elapsedRealtime()
        val commands = response.optJSONArray("commands") ?: return
        for (index in 0 until commands.length()) {
            if (current()?.deviceId != association.deviceId || !LabObservers.isRunning()) return
            val command = commands.getJSONObject(index)
            val id = command.optString("commandId")
            val remaining = (command.optLong("expiresAt") - serverTime).coerceAtMost(5 * 60_000L)
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}")) || command.optString("type") != "artifact_get" || remaining <= 0) continue
            val deadline = pollReceived + remaining
            val source = command.optString("source")
            val canonical = when (source) { "media" -> "MEDIA"; "files" -> "FILE"; else -> "" }
            val ownJob = currentCoroutineContext().job
            synchronized(this) { activeTransfer = ActiveTransfer(ownJob, canonical) }
            val result = JSONObject()
            val artifactId = command.optString("artifactId")
            var transferred: ArtifactContent? = null
            try {
                check(canonical.isNotEmpty() && isSourceEnabled(canonical)) { "Sursa nu are consimțământ activ pe telefon." }
                require(artifactId.length in 1..240)
                val content = LabArtifacts.read(context, artifactId, source)
                transferred = content
                check(content.bytes.size <= 8 * 1024 * 1024) { "Originalul depășește limita de transfer de 8 MiB." }
                check(current()?.deviceId == association.deviceId && LabObservers.isRunning() && isSourceEnabled(canonical)) { "Consimțământul a fost oprit." }
                result.put("status", "completed").put("mime", content.mime).put("filename", content.filename)
                    .put("bytesBase64", Base64.encodeToString(content.bytes, Base64.NO_WRAP))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { result.put("status", "failed").put("error", error.message ?: "Artefactul nu este accesibil.") }
            if (current()?.deviceId != association.deviceId || !LabObservers.isRunning() || SystemClock.elapsedRealtime() >= deadline) return
            if (result.optString("status") == "completed" && !isSourceEnabled(canonical)) {
                result.remove("bytesBase64")
                result.put("status", "failed").put("error", "Consimțământul sursei a fost oprit.")
            }
            try { api.request(association.ownerUid, "POST", "/devices/${association.deviceId}/commands/$id/result", result) {
                check(current()?.deviceId == association.deviceId && LabObservers.isRunning() && SystemClock.elapsedRealtime() < deadline) { "Accesul sau cererea de artefact a expirat." }
                if (result.optString("status") == "completed") {
                    check(isSourceEnabled(canonical)) { "Consimțământul sursei a fost oprit." }
                    LabArtifacts.verifyTransfer(context, artifactId, source, checkNotNull(transferred))
                }
            } }
            catch (error: LabApiException) {
                // A revoked/expired investigator request does not revoke the phone's association.
                if (error.code !in setOf(403, 404, 410)) throw error
                _syncStatus.value = _syncStatus.value.copy(error = "Cererea de artefact nu mai este autorizată sau a expirat.")
            }
        }
    }

    private fun strings(values: JSONArray?): List<String> = values?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
}
