package com.forja.app.core.voice

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.forja.app.core.voice.ui.UiControlException
import com.forja.app.core.voice.ui.VoiceUiConnection
import com.forja.app.feature.voice.VoiceAgentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/** A user-started microphone session. Unlock broadcasts never create this service. */
class VoiceAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val telemetry = Channel<TelemetryEvent>(Channel.UNLIMITED)
    private lateinit var telemetryWriter: Job
    private var foreground = false
    private var receiverRegistered = false
    private var recognizer: SpeechRecognizer? = null
    private var work: Job? = null
    private var retry: Job? = null
    private var recognitionWatchdog: Job? = null
    private var sessionId: String? = null
    private var stage = "command_received"
    private var cancelFailures = 0
    private var generation = 0
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private var utterance: Pair<String, CompletableDeferred<Unit>>? = null
    private lateinit var audio: AudioManager
    private var audioFocus: AudioFocusRequest? = null
    private var focusGeneration = 0
    private var terminalMessage: String? = null

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleLifecycleEvent(VoiceAgentLifecycle.Event.ScreenOff)
                Intent.ACTION_USER_PRESENT -> handleLifecycleEvent(VoiceAgentLifecycle.Event.UserPresent(
                    autoListen = VoiceAgentSettings.isAutoListenEnabled(this@VoiceAgentService),
                    locked = locked()
                ))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Agent vocal FORJA", NotificationManager.IMPORTANCE_LOW)
        )
        telemetryWriter = scope.launch(Dispatchers.IO) {
            for (event in telemetry) {
                VoiceTelemetry.record(applicationContext, event.id, event.stage, event.detail,
                    event.reason, event.failedStage, event.timestamp, event.elapsed)
            }
        }
        tts = TextToSpeech(this) { status ->
            scope.launch {
                val engine = tts
                val available = status == TextToSpeech.SUCCESS && engine != null &&
                    engine.setLanguage(Locale("ro", "RO")) >= TextToSpeech.LANG_AVAILABLE
                if (!ttsReady.isCompleted) ttsReady.complete(available)
                engine?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishSpeech(utteranceId)
            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) = finishSpeech(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finishSpeech(utteranceId)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!hasMicrophonePermission() || locked()) {
                    terminalMessage = "Deblochează telefonul și acordă acces la microfon în FORJA."
                    update(message = terminalMessage!!)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!foreground) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                        } else startForeground(NOTIFICATION_ID, notification())
                        foreground = true
                        VoiceAgentRuntime.update { it.copy(running = true) }
                        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_OFF) }
                        ContextCompat.registerReceiver(this, lockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                        receiverRegistered = true
                    } catch (_: Exception) {
                        terminalMessage = "Android nu a permis pornirea microfonului. Pornește agentul din ecranul FORJA."
                        update(message = terminalMessage!!)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                prepareListening()
            }
            ACTION_LISTEN -> if (foreground) prepareListening() else {
                terminalMessage = "Pornește agentul din ecranul FORJA pentru a folosi microfonul."
                update(message = terminalMessage!!)
                stopSelf()
            }
            ACTION_CANCEL -> if (foreground) abort("user_cancelled", "Acțiune anulată.", announce = true) else stopSelf()
            ACTION_STOP -> handleLifecycleEvent(VoiceAgentLifecycle.Event.Stop)
            else -> stopSelf() // No sticky restart, boot listener, or pending voice command.
        }
        return START_NOT_STICKY
    }

    private fun prepareListening() {
        if (!foreground) return
        if (locked()) {
            abort("device_locked", "Deblochează telefonul pentru a folosi agentul.")
            return
        }
        if (VoiceAgentRuntime.state.value.busy || work?.isActive == true || recognizer != null) return
        if (!hasMicrophonePermission()) {
            update(message = "Permisiunea pentru microfon lipsește. Acord-o din ecranul FORJA.")
            return
        }
        val token = ++generation
        sessionId = VoiceTelemetry.newSessionId()
        stage = "command_received"
        record(stage, "Captură inițiată explicit de utilizator.")
        work = scope.launch {
            update(message = "FORJA se pregătește să asculte.", transcript = "")
            speak("FORJA ascultă. Spune comanda pentru YouTube.")
            if (token == generation && foreground && !locked()) startRecognition(cancelOnly = false)
        }
    }

    private fun startRecognition(cancelOnly: Boolean) {
        if (!foreground || locked() || !hasMicrophonePermission() || recognizer != null) return
        if (cancelOnly && !VoiceAgentRuntime.state.value.busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            recognitionFailed("recognizer_unavailable", cancelOnly)
            return
        }
        if (!cancelOnly && !acquireAudioFocus()) {
            recognitionFailed("audio_focus_unavailable", cancelOnly)
            return
        }
        try {
            // The system recognizer can select a Romanian-capable engine; prefer offline when supported.
            // A globally available on-device recognizer does not imply Romanian language support.
            val client = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = client
            client.setRecognitionListener(object : RecognitionListener {
                private fun current() = recognizer === client && foreground && !locked()
                override fun onReadyForSpeech(params: Bundle?) {
                    if (current() && !cancelOnly) update(listening = true, message = "Ascult. Spune comanda pentru YouTube.")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (!current()) return
                    stopRecognition()
                    if (cancelOnly && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        restartCancellationListener()
                    } else recognitionFailed(recognitionError(error), cancelOnly)
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    stopRecognition()
                    if (cancelOnly) {
                        // While another app speaks, only the explicit wake-name cancellation is admitted.
                        if (heard.startsWith("forja", ignoreCase = true) && VoiceIntentResolver.isCancel(heard)) {
                            abort("user_cancelled", "Acțiune anulată.", announce = true)
                        } else restartCancellationListener()
                    } else if (heard.isBlank()) recognitionFailed("empty_transcript", false)
                    else acceptCommand(heard)
                }
            })
            update(listening = true)
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            client.startListening(request)
            recognitionWatchdog = scope.launch {
                delay(if (cancelOnly) 12_000 else 20_000)
                if (recognizer === client) {
                    stopRecognition()
                    if (cancelOnly) restartCancellationListener()
                    else recognitionFailed("speech_timeout", false)
                }
            }
        } catch (_: Exception) {
            stopRecognition()
            recognitionFailed("recognizer_start_failed", cancelOnly)
        }
    }

    private fun acceptCommand(transcript: String) {
        if (locked()) {
            abort("device_locked", "Telefon blocat. Comanda a fost anulată.")
            return
        }
        stage = "speech_recognized"
        record(stage, "Comandă transcrisă; textul nu este păstrat în jurnal.")
        update(listening = false, transcript = transcript)
        if (VoiceIntentResolver.isCancel(transcript)) {
            abort("user_cancelled", "Acțiune anulată.", announce = true)
            return
        }
        val intent = VoiceIntentResolver.resolve(transcript)
        if (intent == null) {
            fail("unsupported_command", "intent_resolved", "Comanda nu este disponibilă. Spune: deschide YouTube și caută, apoi titlul.")
            return
        }
        stage = "intent_resolved"
        record(stage, "Căutare YouTube și deschidere rezultat.")
        val token = ++generation
        work = scope.launch {
            update(busy = true, message = "Deschid YouTube și caut conținutul solicitat.")
            try {
                speak("Deschid YouTube și caut. Pentru anulare spune FORJA, oprește.")
                if (token != generation || locked()) throw CancellationException()
                cancelFailures = 0
                startRecognition(cancelOnly = true)
                VoiceUiConnection.execute(intent) { eventStage, detail ->
                    if (token == generation) {
                        stage = eventStage
                        record(eventStage, detail)
                        update(message = stageMessage(eventStage))
                    }
                }
                if (token != generation || locked()) return@launch
                stopRecognition()
                retry?.cancel()
                sessionId = null
                update(busy = false, listening = false, message = "Conținutul solicitat a fost deschis.", transcript = "")
                speak("Conținutul solicitat a fost deschis.")
            } catch (_: CancellationException) {
                if (token == generation) abort("action_cancelled", "Acțiune anulată.")
            } catch (error: UiControlException) {
                if (token == generation) fail(error.reason, error.stage, "Nu am putut finaliza acțiunea: ${error.reason}.")
            } catch (_: Exception) {
                if (token == generation) fail("unexpected_error", stage, "Nu am putut finaliza acțiunea. Încearcă din nou.")
            }
        }
    }

    private fun restartCancellationListener() {
        retry?.cancel()
        retry = scope.launch {
            delay(400)
            if (foreground && VoiceAgentRuntime.state.value.busy && !locked()) startRecognition(cancelOnly = true)
        }
    }

    private fun recognitionFailed(reason: String, cancelOnly: Boolean) {
        if (cancelOnly) {
            if (++cancelFailures < 3) restartCancellationListener()
            else update(message = "Acțiunea continuă. Anularea vocală este indisponibilă; folosește notificarea FORJA.")
        } else {
            if (sessionId == null) sessionId = VoiceTelemetry.newSessionId()
            fail(reason, "speech_recognized", "Nu am putut auzi comanda. Apasă Ascultă pentru a încerca din nou.")
        }
    }

    private fun fail(reason: String, failedStage: String, message: String) {
        record("failed", "Acțiunea nu a fost finalizată.", reason, failedStage)
        sessionId = null
        stopRecognition()
        retry?.cancel()
        update(listening = false, busy = false, message = message, transcript = "")
        work = scope.launch { speak(message) }
    }

    private fun abort(reason: String, message: String, announce: Boolean = false) {
        ++generation
        record("failed", "Acțiune oprită.", reason, stage)
        sessionId = null
        work?.cancel()
        work = null
        retry?.cancel()
        retry = null
        stopRecognition()
        tts?.stop()
        utterance?.second?.complete(Unit)
        utterance = null
        VoiceUiConnection.cancel()
        update(listening = false, busy = false, message = message, transcript = "")
        if (announce && !locked()) work = scope.launch { speak(message) }
    }

    private suspend fun speak(message: String) {
        stopRecognition()
        val token = generation
        if (locked() || withTimeoutOrNull(1_500) { ttsReady.await() } != true) return
        if (locked() || token != generation || !foreground) return
        val engine = tts ?: return
        val completion = CompletableDeferred<Unit>()
        val id = UUID.randomUUID().toString()
        utterance = id to completion
        if (!acquireAudioFocus()) {
            utterance = null
            return
        }
        try {
            if (engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
                withTimeoutOrNull(12_000) { completion.await() }
            }
        } finally {
            if (utterance?.first == id) {
                engine.stop()
                utterance = null
                releaseAudioFocus()
            }
        }
    }

    private fun finishSpeech(id: String?) {
        scope.launch { if (utterance?.first == id) utterance?.second?.complete(Unit) }
    }

    private fun stopRecognition() {
        recognitionWatchdog?.cancel()
        recognitionWatchdog = null
        val old = recognizer
        recognizer = null // Invalidates every callback before cancel/destroy can issue ERROR_CLIENT.
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
        releaseAudioFocus()
        VoiceAgentRuntime.update { it.copy(listening = false) }
    }

    private fun acquireAudioFocus(): Boolean {
        if (audioFocus != null) return true
        val token = ++focusGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch {
                        handleLifecycleEvent(VoiceAgentLifecycle.Event.AudioFocusLost(
                            currentRequest = token == focusGeneration && audioFocus != null
                        ))
                    }
                }
            }.build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        audioFocus = request
        return true
    }

    private fun handleLifecycleEvent(event: VoiceAgentLifecycle.Event) {
        val transition = VoiceAgentLifecycle.reduce(
            VoiceAgentRuntime.state.value.copy(running = foreground), event
        )
        when (transition.effect) {
            VoiceAgentLifecycle.Effect.PREPARE_LISTENING -> {
                prepareListening()
                return // prepareListening owns any resulting state changes.
            }
            VoiceAgentLifecycle.Effect.ABORT_ACTION,
            VoiceAgentLifecycle.Effect.CLOSE_SESSION -> abort(transition.reason!!, transition.state.message)
            VoiceAgentLifecycle.Effect.PAUSE_VOICE -> {
                stopRecognition()
                retry?.cancel()
                retry = null
                tts?.stop()
                utterance?.second?.complete(Unit)
                utterance = null
            }
            VoiceAgentLifecycle.Effect.NONE -> Unit
        }
        VoiceAgentRuntime.update { transition.state }
        if (transition.effect == VoiceAgentLifecycle.Effect.CLOSE_SESSION) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (foreground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun releaseAudioFocus() {
        ++focusGeneration
        audioFocus?.let { audio.abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun locked() = isLocked(this)

    private fun update(
        listening: Boolean = VoiceAgentRuntime.state.value.listening,
        busy: Boolean = VoiceAgentRuntime.state.value.busy,
        message: String = VoiceAgentRuntime.state.value.message,
        transcript: String = VoiceAgentRuntime.state.value.transcript
    ) {
        VoiceAgentRuntime.update { it.copy(listening = listening, busy = busy, message = message, transcript = transcript) }
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, VoiceAgentActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(action: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, VoiceAgentService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Agent vocal FORJA")
            .setContentText(VoiceAgentRuntime.state.value.message)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Ascultă", action(ACTION_LISTEN, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Anulează", action(ACTION_CANCEL, 2))
            .addAction(android.R.drawable.ic_delete, "Oprește agentul", action(ACTION_STOP, 3))
            .build()
    }

    private fun record(eventStage: String, detail: String, reason: String? = null, failedStage: String? = null) {
        val id = sessionId ?: return
        telemetry.trySend(TelemetryEvent(id, eventStage, detail, reason, failedStage))
    }

    override fun onDestroy() {
        foreground = false
        abort("service_destroyed", "Agentul vocal este oprit.")
        if (receiverRegistered) runCatching { unregisterReceiver(lockReceiver) }
        tts?.shutdown()
        tts = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        telemetry.close()
        // The IO consumer drains buffered events even after the microphone session is released.
        scope.launch { telemetryWriter.join(); scope.cancel() }
        VoiceAgentRuntime.update { VoiceAgentState(message = terminalMessage ?: "Agentul vocal este oprit.") }
        super.onDestroy()
    }

    private data class TelemetryEvent(
        val id: String, val stage: String, val detail: String, val reason: String?, val failedStage: String?,
        val timestamp: Long = System.currentTimeMillis(), val elapsed: Long = SystemClock.elapsedRealtime()
    )

    companion object {
        private const val CHANNEL = "voice_agent"
        private const val NOTIFICATION_ID = 74
        private const val ACTION_START = "com.forja.app.voice.START"
        private const val ACTION_LISTEN = "com.forja.app.voice.LISTEN"
        private const val ACTION_CANCEL = "com.forja.app.voice.CANCEL"
        private const val ACTION_STOP = "com.forja.app.voice.STOP"

        /** Call only from the visible FORJA Activity, after runtime permission approval. */
        fun start(context: Context) {
            var activityContext: Context? = context
            while (activityContext is ContextWrapper && activityContext !is Activity) {
                val base = activityContext.baseContext
                if (base === activityContext) break
                activityContext = base
            }
            if (activityContext !is Activity || activityContext.isFinishing || isLocked(context)) {
                VoiceAgentRuntime.update { it.copy(message = "Pornește agentul din ecranul FORJA, după deblocarea telefonului.") }
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, VoiceAgentService::class.java).setAction(ACTION_START))
            } catch (_: Exception) {
                VoiceAgentRuntime.update { it.copy(message = "Android nu a permis pornirea agentului. Revino în FORJA și încearcă din nou.") }
            }
        }

        fun listen(context: Context) = control(context, ACTION_LISTEN)
        fun cancel(context: Context) = control(context, ACTION_CANCEL)
        fun stop(context: Context) = control(context, ACTION_STOP)

        private fun control(context: Context, action: String) {
            if (!VoiceAgentRuntime.state.value.running) {
                if (action == ACTION_LISTEN) VoiceAgentRuntime.update {
                    it.copy(message = "Agentul este oprit. Pornește-l din ecranul FORJA.")
                }
                return
            }
            runCatching { context.startService(Intent(context, VoiceAgentService::class.java).setAction(action)) }
                .onFailure { VoiceAgentRuntime.update { it.copy(message = "Nu am putut contacta agentul. Revino în ecranul FORJA.") } }
        }

        private fun isLocked(context: Context): Boolean {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return keyguard.isDeviceLocked || keyguard.isKeyguardLocked
        }

        private fun recognitionError(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone_permission_denied"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "speech_network_error"
            SpeechRecognizer.ERROR_NO_MATCH -> "speech_not_understood"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
            SpeechRecognizer.ERROR_AUDIO -> "microphone_error"
            else -> "speech_error_$error"
        }

        private fun stageMessage(stage: String): String = when (stage) {
            "app_opened" -> "YouTube a fost deschis. Caut butonul de căutare."
            "target_found" -> "Elementul necesar a fost găsit."
            "action_executed" -> "Navighez în YouTube. Poți anula din notificarea FORJA."
            "result_verified" -> "Conținutul solicitat a fost deschis."
            else -> "Execut comanda în YouTube."
        }
    }
}

