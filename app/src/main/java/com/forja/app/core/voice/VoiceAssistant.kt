package com.forja.app.core.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * „Hei FORJA" — asistentul vocal.
 *
 * Un singur proprietar al microfonului și al vocii: ascultă (SpeechRecognizer), înțelege
 * (CommandParser), execută (CommandExecutor) și răspunde cu voce (TextToSpeech), ca cineva
 * care nu vede ecranul să poată face totul doar vorbind. Tot ce ține de recunoaștere
 * trece prin firul principal, cum cere Android.
 */
class VoiceAssistant(private val app: ForjaApp) {

    enum class Phase { IDLE, WAITING_WAKE, LISTENING, THINKING, SPEAKING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val transcript: String = "",
        val response: String = "",
        val question: String? = null,
        val level: Float = 0f,
        val wakeLoop: Boolean = false,
        val available: Boolean = true
    )

    sealed class Event {
        data class Navigate(val route: String) : Event()
        data class NeedPermissions(val permissions: List<String>) : Event()
    }

    private enum class Session { NONE, WAKE, COMMAND, SLOT }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = CommandExecutor(app)

    private var recognizer: SpeechRecognizer? = null
    private var recognizerOnDevice = false
    private var onDeviceFailed = false
    private var wakePreferOffline = true
    private var session = Session.NONE
    private var wakeLoopWanted = false
    private var wakeErrors = 0
    private var pending: Pending? = null
    private var retryAfterPermission: VoiceCommand? = null
    private var retryPermissions: List<String>? = null
    private var lastSpoken = ""
    private var busyRetries = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Motorul de voce lipsește sau nu a pornit: dialogul merge mai departe ca și cum vocea ar fi oprită. */
    private var ttsFailed = false
    private var ttsQueued: Pair<String, (() -> Unit)?>? = null
    private var afterSpeech: (() -> Unit)? = null
    private var utteranceSeq = 0
    private var focusRequest: AudioFocusRequest? = null

    // Preferințe, ținute la zi fără blocarea firului principal.
    @Volatile private var speakOn = true
    @Volatile private var confirmSend = true
    @Volatile private var lang = "ro-RO"

    init {
        scope.launch { app.prefs.voiceSpeakOn.collect { speakOn = it } }
        scope.launch { app.prefs.voiceConfirmSend.collect { confirmSend = it } }
        scope.launch { app.prefs.voiceLang.collect { l -> lang = l; main.post { applyTtsLanguage() } } }
        _state.update { it.copy(available = SpeechRecognizer.isRecognitionAvailable(app)) }
    }

    // ── API public ──────────────────────────────────────────────────────────────────

    /** Ascultă o comandă acum (buton, tile, notificare, cuvânt de trezire). */
    fun listen() = onMain {
        if (!hasMic()) { emit(Event.NeedPermissions(listOf(Manifest.permission.RECORD_AUDIO))); return@onMain }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            _state.update { it.copy(available = false) }
            say("Telefonul nu are serviciu de recunoaștere vocală. Instalează aplicația Google.", null)
            return@onMain
        }
        stopSpeaking()
        chime()
        startSession(if (pending != null) Session.SLOT else Session.COMMAND)
    }

    /** Oprește tot: ascultarea curentă, vorbirea, dialogul în curs. Bucla „Hei FORJA" rămâne cum era. */
    fun stop() = onMain {
        pending = null
        stopSpeaking()
        cancelSession()
        _state.update { it.copy(phase = Phase.IDLE, question = null, level = 0f) }
        resumeWakeIfWanted(400)
    }

    /** Dialogul se poate purta și din tastatură (pentru cine preferă să scrie). */
    fun submitText(text: String) = onMain {
        if (text.isBlank()) return@onMain
        stopSpeaking(); cancelSession()
        _state.update { it.copy(transcript = text) }
        if (pending != null) answerPending(text) else runCommand(CommandParser.parse(text))
    }

    /** Spune ceva cu vocea FORJA (ex. prezentarea de la prima deschidere); [then] rulează după ce a terminat. */
    fun speak(text: String, then: (() -> Unit)? = null) = onMain { stopSpeaking(); say(text, then) }

    /** Spune după ce termină ce are de spus (ex. „Mesajul a plecat.” după „Trimit mesajul…”). */
    fun speakAfter(text: String) = onMain { say(text, null, queue = TextToSpeech.QUEUE_ADD) }

    /** Aplicația e din nou în prim-plan: dacă o comandă aștepta permisiuni, le cerem acum. */
    fun onForeground() = onMain {
        val perms = retryPermissions ?: return@onMain
        if (retryAfterPermission == null) { retryPermissions = null; return@onMain }
        val missing = perms.filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { retryPermissions = null; onPermissionsResult(emptyMap()) }
        else emit(Event.NeedPermissions(missing))
    }

    fun startWakeLoop() = onMain {
        wakeLoopWanted = true
        wakeErrors = 0
        _state.update { it.copy(wakeLoop = true) }
        if (session == Session.NONE) startSession(Session.WAKE)
    }

    fun stopWakeLoop() = onMain {
        wakeLoopWanted = false
        _state.update { it.copy(wakeLoop = false) }
        if (session == Session.WAKE) { cancelSession(); _state.update { it.copy(phase = Phase.IDLE) } }
    }

    /** Ecranul a cerut permisiunile; reluăm comanda care le aștepta. */
    fun onPermissionsResult(result: Map<String, Boolean>) = onMain {
        val retry = retryAfterPermission ?: run {
            // Nicio comandă în așteptare: a fost doar microfonul cerut de listen() — ascultăm acum.
            if (result[Manifest.permission.RECORD_AUDIO] == true) listen()
            return@onMain
        }
        retryAfterPermission = null
        retryPermissions = null
        runCommand(retry, denied = result.filterValues { !it }.keys)
    }

    fun shutdown() = onMain {
        wakeLoopWanted = false
        cancelSession()
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null; ttsReady = false; ttsFailed = false
    }

    // ── Sesiuni de ascultare ────────────────────────────────────────────────────────

    private fun hasMic() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureRecognizer(preferOnDevice: Boolean): SpeechRecognizer? {
        val wantOnDevice = preferOnDevice && !onDeviceFailed && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        if (recognizer != null && recognizerOnDevice != wantOnDevice) { recognizer?.destroy(); recognizer = null }
        if (recognizer == null) {
            recognizer = try {
                if (wantOnDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                else SpeechRecognizer.createSpeechRecognizer(app)
            } catch (_: Exception) { null }
            recognizerOnDevice = wantOnDevice
            recognizer?.setRecognitionListener(listener)
        }
        return recognizer
    }

    private fun startSession(kind: Session) {
        if (!hasMic()) { if (kind == Session.WAKE) wakeLoopWanted = false; return }
        val r = ensureRecognizer(preferOnDevice = kind == Session.WAKE) ?: run {
            _state.update { it.copy(available = false) }; return
        }
        try { r.cancel() } catch (_: Exception) { }
        session = kind
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (kind == Session.WAKE && wakePreferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        _state.update {
            it.copy(
                phase = if (kind == Session.WAKE) Phase.WAITING_WAKE else Phase.LISTENING,
                transcript = if (kind == Session.WAKE) it.transcript else "",
                level = 0f
            )
        }
        try {
            r.startListening(intent)
        } catch (_: Exception) {
            session = Session.NONE
            recognizer?.destroy(); recognizer = null
            if (kind == Session.WAKE) scheduleWake(3000) else _state.update { it.copy(phase = Phase.IDLE) }
        }
    }

    private fun cancelSession() {
        session = Session.NONE
        try { recognizer?.cancel() } catch (_: Exception) { }
    }

    private fun scheduleWake(delayMs: Long) {
        main.postDelayed({ if (wakeLoopWanted && session == Session.NONE && tts?.isSpeaking != true) startSession(Session.WAKE) }, delayMs)
    }

    private fun resumeWakeIfWanted(delayMs: Long = 600) {
        if (wakeLoopWanted) scheduleWake(delayMs)
        else if (session == Session.NONE) _state.update { it.copy(phase = Phase.IDLE, level = 0f) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (session == Session.NONE) return
            if (session != Session.WAKE) _state.update { it.copy(phase = Phase.LISTENING) }
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (session == Session.NONE) return
            val lvl = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(level = lvl) }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (session == Session.NONE) return
            if (session != Session.WAKE) _state.update { it.copy(phase = Phase.THINKING, level = 0f) }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (session == Session.NONE) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (session == Session.WAKE) {
                // Reacție imediată la „Hei FORJA", fără să așteptăm sfârșitul frazei.
                val after = VoiceText.afterWakeWord(VoiceText.normalize(t))
                if (after != null && after.isBlank()) wakeHeard("")
            } else if (t.isNotBlank()) _state.update { it.copy(transcript = t) }
        }
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val kind = session
            session = Session.NONE
            busyRetries = 0
            when (kind) {
                Session.WAKE -> {
                    wakeErrors = 0
                    for (c in list) {
                        val after = VoiceText.afterWakeWord(VoiceText.normalize(c)) ?: continue
                        wakeHeard(after); return
                    }
                    scheduleWake(250)
                }
                Session.COMMAND -> {
                    val raw = list.firstOrNull().orEmpty()
                    _state.update { it.copy(transcript = raw, phase = Phase.THINKING) }
                    val cmd = list.map { CommandParser.parse(it) }.firstOrNull { it !is VoiceCommand.Unknown }
                        ?: CommandParser.parse(raw)
                    runCommand(cmd)
                }
                Session.SLOT -> {
                    val raw = list.firstOrNull().orEmpty()
                    _state.update { it.copy(transcript = raw, phase = Phase.THINKING) }
                    answerPending(raw)
                }
                Session.NONE -> {}
            }
        }
        override fun onError(error: Int) {
            val kind = session
            session = Session.NONE
            _state.update { it.copy(level = 0f) }
            // cancel() nu oprește mesajele deja puse în coadă de serviciu: ce vine după anulare se ignoră.
            if (kind == Session.NONE) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wakeLoopWanted = false
                    _state.update { it.copy(phase = Phase.IDLE, wakeLoop = false) }
                    emit(Event.NeedPermissions(listOf(Manifest.permission.RECORD_AUDIO)))
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy(); recognizer = null
                    if (kind != Session.WAKE && busyRetries < 2) {
                        busyRetries++
                        main.postDelayed({ startSession(kind) }, 500)
                        return
                    }
                }
                12, 13 -> { // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
                    when {
                        recognizerOnDevice -> { onDeviceFailed = true; recognizer?.destroy(); recognizer = null }
                        kind == Session.WAKE && wakePreferOffline -> wakePreferOffline = false // fără pachet offline → online
                        lang != "en-US" -> lang = "en-US" // ultimul resort: engleză
                    }
                }
            }
            if (kind == Session.WAKE) {
                wakeErrors++
                val delay = when {
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (wakeErrors > 40) 4000L else 300L
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER -> 6000L
                    wakeErrors > 8 -> 15000L
                    else -> 1500L
                }
                scheduleWake(delay)
                return
            }
            busyRetries = 0
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (pending != null) "Nu am auzit răspunsul. Apasă microfonul și mai spune o dată." else "Nu am auzit nimic. Apasă microfonul și spune comanda."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    "Recunoașterea vocală are nevoie de internet. Verifică conexiunea."
                SpeechRecognizer.ERROR_AUDIO -> "Microfonul e ocupat de altă aplicație."
                12, 13 -> "Limba aleasă nu e disponibilă pentru recunoaștere pe acest telefon."
                else -> "Nu am putut asculta acum. Mai încearcă."
            }
            say(msg, null)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** S-a auzit „Hei FORJA" în fundal: cu comandă în aceeași frază sau doar chemarea. */
    private fun wakeHeard(afterWake: String) {
        cancelSession()
        buzz()
        if (afterWake.isNotBlank()) {
            _state.update { it.copy(transcript = afterWake, phase = Phase.THINKING) }
            runCommand(CommandParser.parse(afterWake))
        } else {
            chime()
            _state.update { it.copy(transcript = "", response = "", question = null) }
            main.postDelayed({ startSession(if (pending != null) Session.SLOT else Session.COMMAND) }, 350)
        }
    }

    // ── Execuție ────────────────────────────────────────────────────────────────────

    private fun runCommand(cmd: VoiceCommand, denied: Set<String> = emptySet()) {
        cancelSession()
        _state.update { it.copy(phase = Phase.THINKING) }
        if (cmd is VoiceCommand.Stop) pending = null
        scope.launch {
            // Misiunea „Hei FORJA” a Cascăi: o comandă adevărată pe zi — scrisă înainte de execuție, ca „ce grad am” s-o vadă bifată.
            if (cmd !is VoiceCommand.Unknown && cmd !is VoiceCommand.Stop && cmd !is VoiceCommand.Repeat) withContext(Dispatchers.IO) { try { app.prefs.markVoiceUsed() } catch (_: Exception) { } }
            val outcome = withContext(Dispatchers.IO) { executor.execute(cmd, confirmSend, lastSpoken, denied) }
            handle(outcome)
            if (cmd is VoiceCommand.StopListening) {
                wakeLoopWanted = false
                _state.update { it.copy(wakeLoop = false) }
                launch(Dispatchers.IO) { app.prefs.setVoiceWakeOn(false) }
                VoiceWakeService.stop(app)
            }
        }
    }

    private fun readScreenSoon(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val txt = withContext(Dispatchers.IO) { try { ScreenAgent.read(500) } catch (_: Exception) { "" } }
            if (txt.isBlank() || session != Session.NONE) { resumeWakeIfWanted(); return@launch }
            say("Pe ecran: $txt", null)
        }
    }

    private fun answerPending(raw: String) {
        val p = pending ?: run { runCommand(CommandParser.parse(raw)); return }
        cancelSession()
        _state.update { it.copy(phase = Phase.THINKING) }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { executor.answer(p, raw, confirmSend) }
            handle(outcome)
        }
    }

    private fun handle(outcome: Outcome) {
        when (outcome) {
            is Outcome.Done -> {
                pending = null
                _state.update { it.copy(question = null) }
                outcome.navigate?.let { navigateTo(it) }
                if (outcome.readScreenAfterMs > 0 && ScreenAgent.isConnected()) {
                    // După ce a deschis / apăsat / căutat ceva, citește ce a apărut pe ecran — ca să nu rămâi în întuneric.
                    say(outcome.spoken, then = { readScreenSoon(outcome.readScreenAfterMs) })
                } else say(outcome.spoken, null)
            }
            is Outcome.Ask -> {
                pending = outcome.pending
                _state.update { it.copy(question = outcome.question) }
                // Bipul spune „acum poți răspunde” — fără el, cine nu vede inelul nu știe că microfonul e deschis.
                say(outcome.question, then = { chime(); startSession(Session.SLOT) })
            }
            is Outcome.NeedPermission -> {
                pending = null
                retryAfterPermission = outcome.retry
                retryPermissions = outcome.permissions
                _state.update { it.copy(question = null) }
                // Explicația se aude întreagă, abia apoi apare dialogul Android (altfel vorbesc două voci deodată).
                say(outcome.spoken, then = {
                    if (isForeground()) emit(Event.NeedPermissions(outcome.permissions))
                    else notifyNeedsApp("FORJA are nevoie de o permisiune", "Deschide FORJA ca să permiți accesul și comanda ta continuă.")
                })
            }
        }
    }

    // ── Voce ────────────────────────────────────────────────────────────────────────

    private fun ensureTts() {
        if (tts != null || ttsFailed) return
        tts = TextToSpeech(app) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    // Fără motor de voce: nu rămânem muți și blocați — dialogul continuă fără voce.
                    ttsFailed = true
                    try { tts?.shutdown() } catch (_: Exception) { }
                    tts = null
                    val q = ttsQueued
                    ttsQueued = null
                    if (q != null) say(q.first, q.second) else _state.update { it.copy(phase = Phase.IDLE) }
                    return@post
                }
                if (ttsReady) {
                    applyTtsLanguage()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { main.post { speechFinished() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { main.post { speechFinished() } }
                        override fun onError(utteranceId: String?, errorCode: Int) { main.post { speechFinished() } }
                    })
                }
                ttsQueued?.let { (text, then) -> ttsQueued = null; say(text, then) }
            }
        }
    }

    private fun applyTtsLanguage() {
        val t = tts ?: return
        if (!ttsReady) return
        val wanted = Locale.forLanguageTag(lang)
        val r = try { t.setLanguage(wanted) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            try { t.setLanguage(Locale.getDefault()) } catch (_: Exception) { }
        }
        try { t.setSpeechRate(1.0f) } catch (_: Exception) { }
    }

    /** Spune textul (dacă vocea e pornită) și apoi, opțional, continuă cu [then]. */
    private fun say(text: String, then: (() -> Unit)?, queue: Int = TextToSpeech.QUEUE_FLUSH) {
        lastSpoken = text
        // „Răspunsuri cu voce” oprit are sens doar cât FORJA e pe ecran (TalkBack citește ecranul); în fundal vocea e singura cale.
        val silent = ttsFailed || (!speakOn && isForeground())
        _state.update { it.copy(response = text, phase = if (silent) it.phase else Phase.SPEAKING) }
        if (silent) {
            _state.update { it.copy(phase = Phase.IDLE) }
            // Fără voce, TalkBack citește textul de pe ecran: îi lăsăm timp înainte să deschidem microfonul.
            if (then != null) main.postDelayed(then, (300L + text.length * 30L).coerceAtMost(4000L)) else resumeWakeIfWanted()
            return
        }
        ensureTts()
        if (!ttsReady) { ttsQueued = text to then; return }
        if (queue == TextToSpeech.QUEUE_FLUSH || then != null) afterSpeech = then
        requestFocus()
        val id = "forja-${++utteranceSeq}"
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        val ok = try { tts?.speak(text, queue, params, id) } catch (_: Exception) { TextToSpeech.ERROR }
        if (ok != TextToSpeech.SUCCESS) speechFinished()
    }

    /** Activitatea FORJA e vizibilă? (dialogurile de permisiuni și startActivity merg doar din prim-plan) */
    private fun isForeground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) { true }

    @Volatile private var pendingRoute: Pair<String, Long>? = null

    /**
     * Un ecran FORJA de deschis după o comandă („deschide antrenamentul”, „unde e Ion”). Cu activitatea în viață, direct;
     * cu FORJA închisă, ruta așteaptă aici și aducem activitatea în față (cât permite Android din fundal) — ea o ia la
     * pornire ([takePendingRoute]).
     */
    private fun navigateTo(route: String) {
        if (_events.subscriptionCount.value > 0) emit(Event.Navigate(route))
        else pendingRoute = route to SystemClock.elapsedRealtime()
        if (!isForeground()) {
            try {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (_: Exception) { }
        }
    }

    /** MainActivity, la pornire: ruta cerută cu vocea cât FORJA era închisă (doar dacă e proaspătă). */
    fun takePendingRoute(maxAgeMs: Long = 15_000L): String? {
        val p = pendingRoute ?: return null
        pendingRoute = null
        return if (SystemClock.elapsedRealtime() - p.second <= maxAgeMs) p.first else null
    }

    private fun speechFinished() {
        abandonFocus()
        val then = afterSpeech
        afterSpeech = null
        if (then != null) {
            _state.update { it.copy(phase = Phase.IDLE) }
            then()
        } else {
            _state.update { it.copy(phase = if (session == Session.WAKE) Phase.WAITING_WAKE else Phase.IDLE) }
            resumeWakeIfWanted()
        }
    }

    private fun stopSpeaking() {
        afterSpeech = null
        ttsQueued = null
        try { tts?.stop() } catch (_: Exception) { }
        abandonFocus()
    }

    private fun requestFocus() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } catch (_: Exception) { }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        try { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req) } catch (_: Exception) { }
    }

    /** Un „bip" scurt: semnalul că microfonul ascultă — esențial când nu vezi ecranul. */
    private fun chime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            main.postDelayed({ try { tg.release() } catch (_: Exception) { } }, 400)
        } catch (_: Exception) { }
        buzz()
    }

    private fun buzz() {
        try {
            @Suppress("DEPRECATION")
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun notifyNeedsApp(title: String, text: String) {
        try {
            val pi = PendingIntent.getActivity(
                app, 71, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_VOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(app, "voice")
                .setSmallIcon(R.drawable.ic_voice_mic)
                .setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(pi).build()
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(72, n)
        } catch (_: Exception) { }
    }

    private fun emit(e: Event) { _events.tryEmit(e) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
