package com.forja.app.core.sleep

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import com.forja.app.core.data.db.SleepEventEntity
import com.forja.app.core.data.db.SleepSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Somn à la Sleep as Android:
 * · microfonul detectează sforăit/vorbit și salvează clipuri de 5s pe telefon (clasificate live pe server)
 * · toată noaptea se înregistrează în bucăți de ~30 min (AAC) și urcă dimineața pe server, pe Wi-Fi,
 *   pentru cronologia „Noaptea, ascultată” ([SleepUpload])
 * · accelerometrul numără mișcările → stadii estimate ([SleepStaging]) → hipnogramă
 * · alarma deșteaptă: sună în fereastra de somn ușor, nu în mijlocul somnului profund
 */
class SleepTrackService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Mișcare
    private var sensorManager: SensorManager? = null
    private var lastMagnitude = 9.8f
    private var movements = 0
    private var lastMovementAt = 0L
    private val movementTimes = mutableListOf<Long>()
    /** Micro-mișcări (delta > 0,35 m/s²) pe minut de la începutul sesiunii — intrare pentru [SleepStaging]. */
    private val microPerMinute = HashMap<Int, Int>()

    // Audio — 32 kHz pentru claritate; clipurile Whisper se reduc la 16 kHz.
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private val sampleRate = 32000
    private val ringSeconds = 6
    private val ring = ShortArray(sampleRate * ringSeconds)
    private var ringPos = 0
    private var totalWritten = 0L
    private var fullRecorder: AacRecorder? = null
    private var fullDir: File? = null
    private var baseline = 250.0
    private var lastSnoreEventAt = 0L
    private var lastTalkEventAt = 0L
    private val peakTimes = ArrayDeque<Long>()
    private var burstStartAt = 0L
    private var burstCount = 0
    private var burstWindowStart = 0L
    // Caracteristici pentru a separa sforăitul de vorbit
    private var lpF = 0.0
    private var recentZcr = 0.04
    private var recentHigh = 0.30

    private var sessionId: Long = 0
    private var sessionStartAt: Long = 0
    @Volatile private var alarmFired = false
    private var alarmJob: Job? = null
    /** Amânare: după „Încă 10 minute” alarma revine la această oră (0 = fără amânare). */
    @Volatile private var snoozeUntil = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // Promovarea în prim-plan: tipul se decide după permisiuni, niciodată 0 pe Android 14+.
    private var promoted = false
    /** true doar dacă am fost promovați cu tipul MICROPHONE — altfel nu pornim AudioRecord. */
    private var micType = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = ForjaApp.from(this)
        when (intent?.action) {
            ACTION_STOP -> {
                if (!running) {
                    // Cursă rară: comanda a venit prin startForegroundService fără sesiune vie.
                    // Onorăm contractul (promovăm), închidem sesiunea rămasă și ne oprim.
                    closeStaleSession(this)
                    cancelAlarmNotification()
                    leaveForegroundAndStop()
                    return START_NOT_STICKY
                }
                finishSession()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                if (!running) {
                    AlarmRinger.stop()
                    cancelAlarmNotification()
                    leaveForegroundAndStop()
                    return START_NOT_STICKY
                }
                snoozeAlarm(app)
                return START_STICKY
            }
            else -> {
                if (intent == null) {
                    // Repornire START_STICKY după moartea procesului: datele nopții s-au pierdut,
                    // închidem onest sesiunea rămasă deschisă și nu ne prefacem că veghem.
                    closeStaleSession(this)
                    leaveForegroundAndStop()
                    return START_NOT_STICKY
                }
                if (!promote()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(app)
                return START_STICKY
            }
        }
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Promovare în prim-plan cu tipul derivat din permisiuni:
     * · 34+: MICROPHONE dacă avem RECORD_AUDIO, altfel SPECIAL_USE (niciodată 0 → MissingForegroundServiceTypeException)
     * · 30–33: MICROPHONE sau MANIFEST
     * · <30: tipul e ignorat.
     * Întoarce false dacă nu am putut deveni prim-plan — apelantul trebuie să oprească serviciul.
     */
    private fun promote(): Boolean {
        if (promoted) return true
        val notif = buildNotification()
        val mic = micGranted()
        return try {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    if (mic) {
                        try {
                            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                            micType = true
                        } catch (_: Exception) {
                            // SecurityException / ForegroundServiceStartNotAllowedException (pornire din fundal)
                            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                            micType = false
                        }
                    } else {
                        ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        micType = false
                    }
                }
                Build.VERSION.SDK_INT >= 30 -> {
                    ServiceCompat.startForeground(
                        this, NOTIF_ID, notif,
                        if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                    )
                    micType = mic
                }
                else -> {
                    ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
                    micType = mic
                }
            }
            promoted = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Ieșire curată: notificarea de veghe dispare odată cu serviciul. */
    private fun leaveForegroundAndStop() {
        if (!promoted) promote()
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        promoted = false
        stopSelf()
    }

    private fun cancelAlarmNotification() {
        try { NotificationManagerCompat.from(this).cancel(ALARM_NOTIF_ID) } catch (_: Exception) { }
    }

    private fun startSession(app: ForjaApp) {
        if (running) return
        running = true
        // WakeLock parțial: fără el, Doze amână bucla de veghe și alarma inteligentă
        // ar dormi odată cu tine. Limită de 12h ca plasă de siguranță pentru baterie.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "forja:sleep").apply {
                setReferenceCounted(false)
                acquire(12 * 3600_000L)
            }
        } catch (_: Exception) { }
        app.presence.manualState = "sleep"
        app.auth.currentUid?.let { app.presence.publishState(it, "sleep") }
        sessionStartAt = System.currentTimeMillis()
        scope.launch {
            val existing = app.db.sleepDao().activeSessionOnce()
            sessionId = existing?.id ?: app.db.sleepDao().insert(
                SleepSessionEntity(startAt = sessionStartAt)
            )
            if (existing != null) sessionStartAt = existing.startAt
            // Înregistrarea completă a nopții (AAC, bucăți de ~30 min) — pornită după ce știm sesiunea.
            try {
                val root = File(filesDir, "sleep_full").apply { mkdirs() }
                cleanupRecordings(root)
                // Clipurile de 5 s se păstrează 7 zile, apoi pleacă singure.
                File(filesDir, "sleep_clips").listFiles()?.forEach {
                    if (System.currentTimeMillis() - it.lastModified() > 7 * 24 * 3600_000L) it.delete()
                }
                if (micType) {
                    val dir = AacRecorder.sessionDir(filesDir, sessionId)
                    fullDir = dir
                    fullRecorder = AacRecorder(sampleRate, dir, sessionId)
                }
            } catch (_: Exception) { }
        }
        // Mișcare
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // Microfon — doar dacă am fost promovați cu tipul MICROPHONE; altfel somn fără audio, onest
        // (pe Android 14+ înregistrarea sub SPECIAL_USE din fundal e refuzată de sistem).
        if (micType) startAudio()
        // Alarma deșteaptă
        startAlarmWatcher(app)
    }

    /**
     * Curățenie locală în `sleep_full/`:
     * · fișierele vechi cu o singură înregistrare (`<id>.m4a`) dispar după 24 h, ca înainte;
     * · bucățile unei sesiuni dispar după 24 h dacă au urcat (serverul le ține 7 zile), altfel după 3 zile;
     * · `timeline.json` / `staging.json` / manifestul rămân 30 de zile (raportul se citește din ele).
     */
    private fun cleanupRecordings(root: File) {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { f ->
            try {
                if (f.isFile) {
                    if (now - f.lastModified() > 24 * 3600_000L) f.delete()
                    return@forEach
                }
                if (!f.isDirectory) return@forEach
                val age = now - f.lastModified()
                if (age > 30L * 24 * 3600_000L) { f.deleteRecursively(); return@forEach }
                val progress = SleepUpload.loadProgress(f)
                f.listFiles()?.forEach { c ->
                    if (!c.name.endsWith(".m4a")) return@forEach
                    val idx = c.name.removePrefix("chunk_").removeSuffix(".m4a").toIntOrNull()
                    val uploaded = progress != null && (progress.done || (idx != null && idx in progress.uploaded))
                    val keepMs = if (uploaded) 24 * 3600_000L else 3L * 24 * 3600_000L
                    if (now - c.lastModified() > keepMs) c.delete()
                }
            } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        try {
            val bufBytes = maxOf(minBuf, sampleRate)
            // VOICE_RECOGNITION: reglat pentru voce, curat, fără procesări agresive de apel.
            var rec: AudioRecord? = null
            for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                try {
                    val r = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
                } catch (_: Exception) { }
            }
            if (rec == null) return
            val recorder = rec
            audioRecord = recorder
            recorder.startRecording()
            audioJob = scope.launch {
                val chunk = ShortArray(sampleRate / 10) // 100ms
                while (isActive) {
                    val n = recorder.read(chunk, 0, chunk.size)
                    if (n <= 0) { delay(50); continue }
                    // scrie în ring
                    for (i in 0 until n) {
                        ring[ringPos] = chunk[i]
                        ringPos = (ringPos + 1) % ring.size
                    }
                    totalWritten += n
                    // înregistrarea completă (AAC)
                    fullRecorder?.feed(chunk, n)
                    processChunk(chunk, n)
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Caracteristici locale: energie (RMS), ritm (vârfuri), rata de treceri prin zero (ZCR)
     * și cât din energie e peste ~500 Hz. Sforăitul = jos, ritmic, ZCR mic; vorbirea = mai
     * sus în frecvență, ZCR mai mare, neregulată. Estimare, nu diagnostic.
     */
    private fun processChunk(chunk: ShortArray, n: Int) {
        var sum = 0.0
        var lowSum = 0.0
        var crossings = 0
        for (i in 0 until n) {
            val x = chunk[i].toDouble()
            sum += x * x
            lpF += 0.09 * (x - lpF)              // trece-jos ~500 Hz
            lowSum += lpF * lpF
            if (i > 0 && (chunk[i] >= 0) != (chunk[i - 1] >= 0)) crossings++
        }
        val rms = sqrt(sum / n)
        val zcr = crossings.toDouble() / n
        val fullE = sum / n
        val highRatio = if (fullE > 1.0) (1.0 - (lowSum / n) / fullE).coerceIn(0.0, 1.0) else 0.0
        val now = System.currentTimeMillis()

        // baseline: urmărește lent zgomotul de fond
        if (rms < baseline * 1.5) baseline = baseline * 0.995 + rms * 0.005
        val threshold = maxOf(baseline * 2.5, 300.0)

        if (rms > threshold) {
            recentZcr = recentZcr * 0.85 + zcr * 0.15
            recentHigh = recentHigh * 0.85 + highRatio * 0.15
            if (peakTimes.isEmpty() || now - peakTimes.last() > 300) {
                peakTimes.addLast(now)
                while (peakTimes.size > 8) peakTimes.removeFirst()
            }
            if (burstStartAt == 0L) burstStartAt = now
            if (now - burstWindowStart > 15000) { burstWindowStart = now; burstCount = 0 }
        } else {
            if (burstStartAt != 0L) {
                val burstDur = now - burstStartAt
                if (burstDur in 300..4000) burstCount++
                burstStartAt = 0L
            }
        }

        val soundsLikeSpeech = recentHigh > 0.34 && recentZcr > 0.05

        // Vorbit: rafale neregulate ȘI semnătură de vorbire → cerem serverului transcrierea REALĂ
        if (burstCount >= 3 && soundsLikeSpeech && now - lastTalkEventAt > 120_000 && now - lastSnoreEventAt > 20_000) {
            lastTalkEventAt = now
            burstCount = 0
            peakTimes.clear()
            saveTalk(now, intensityFor(rms / baseline))
            return
        }

        // Sforăit: ≥4 vârfuri ritmice ȘI NU sună a vorbire → salvăm direct, fără transcriere inventată
        if (peakTimes.size >= 4 && !soundsLikeSpeech && now - lastSnoreEventAt > 180_000) {
            val list = peakTimes.toList().takeLast(5)
            val intervals = list.zipWithNext { a, b -> b - a }
            val rhythmic = intervals.count { it in 1200..6000 }
            if (rhythmic >= 3) {
                lastSnoreEventAt = now
                saveSnore(now, (intervals.sum() / 1000).toInt().coerceAtLeast(4), intensityFor(rms / baseline))
                peakTimes.clear()
            }
        }
    }

    private fun intensityFor(ratio: Double): Int = when {
        ratio > 7 -> 3
        ratio > 4 -> 2
        else -> 1
    }

    /** Ultimele 5 s reale, la 16 kHz, normalizate ca volum — salvate LOCAL. Întoarce (bytes, cale). */
    private fun snapshotClip(): Pair<ByteArray?, String?> {
        val wantedFull = sampleRate * 5
        val copyLen = minOf(totalWritten, wantedFull.toLong()).toInt()
        if (copyLen < sampleRate) return null to null
        val full = ShortArray(copyLen)
        val start = ((ringPos - copyLen) % ring.size + ring.size) % ring.size
        for (i in 0 until copyLen) full[i] = ring[(start + i) % ring.size]
        val snap = ShortArray(copyLen / 2)                       // /2 → 16 kHz
        for (i in snap.indices) snap[i] = full[i * 2]
        var peak = 1
        for (s in snap) { val a = abs(s.toInt()); if (a > peak) peak = a }
        if (peak in 1 until 29000) {                             // ridică vorbirea slabă spre ~-1 dBFS
            val g = 29000.0 / peak
            for (i in snap.indices) snap[i] = (snap[i] * g).toInt().coerceIn(-32767, 32767).toShort()
        }
        return try {
            val dir = File(filesDir, "sleep_clips").apply { mkdirs() }
            val f = File(dir, "clip_${sessionId}_${System.currentTimeMillis()}.wav")
            writeWav(f, snap, 16000)
            f.readBytes() to f.absolutePath
        } catch (_: Exception) { null to null }
    }

    /** Sforăit — sigur pe telefon, fără server, fără transcriere. */
    private fun saveSnore(at: Long, durationS: Int, intensity: Int) {
        val app = ForjaApp.from(this)
        val (_, path) = snapshotClip()
        scope.launch {
            app.db.sleepDao().insertEvent(
                SleepEventEntity(
                    sessionId = sessionId, type = "snore", at = at,
                    durationS = durationS, intensity = intensity, clipPath = path, transcript = null
                )
            )
        }
    }

    /** Vorbit — serverul (Whisper) confirmă vorbirea REALĂ; altfel rămâne „Sunet", nu inventăm. */
    private fun saveTalk(at: Long, intensity: Int) {
        val app = ForjaApp.from(this)
        val (wavBytes, path) = snapshotClip()
        scope.launch {
            var type = "sound"
            var transcript: String? = null
            if (app.forjaApi.available && wavBytes != null) {
                val verdict = try { app.forjaApi.classifySleepAudio(wavBytes) } catch (_: Exception) { null }
                if (verdict != null && verdict.speech) {
                    type = "talk"
                    if (verdict.transcript.isNotBlank()) transcript = verdict.transcript
                }
            }
            app.db.sleepDao().insertEvent(
                SleepEventEntity(
                    sessionId = sessionId, type = type, at = at,
                    durationS = 5, intensity = intensity, clipPath = path, transcript = transcript
                )
            )
        }
    }

    /**
     * Alarma circadiană: „treaz cel târziu la H:M".
     * În fereastra aleasă (20/30/40 min înainte), te trezește la primul dintre:
     * · finalul unui ciclu de somn (~90 min, cu ~15 min latență la adormire)
     * · un moment de somn ușor (mișcare recentă)
     * · ora-limită — niciodată mai târziu.
     */
    private fun startAlarmWatcher(app: ForjaApp) {
        alarmJob?.cancel()
        alarmJob = scope.launch {
            while (isActive && !alarmFired) {
                delay(20_000)
                try {
                    val now = System.currentTimeMillis()
                    // Amânare activă: singura regulă e ora la care revine alarma.
                    if (snoozeUntil > 0) {
                        if (now >= snoozeUntil) {
                            snoozeUntil = 0L
                            alarmFired = true
                            fireAlarm()
                        }
                        continue
                    }
                    val enabled = app.prefs.alarmEnabled.first()
                    if (!enabled) continue
                    val h = app.prefs.alarmHour.first()
                    val m = app.prefs.alarmMinute.first()
                    val windowMs = app.prefs.alarmWindowMin.first().coerceIn(10, 90) * 60_000L
                    val zone = ZoneId.systemDefault()
                    var deadline = LocalDate.now().atTime(h, m).atZone(zone).toInstant().toEpochMilli()
                    if (deadline <= sessionStartAt) {
                        deadline = LocalDate.now().plusDays(1).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
                    }
                    val windowStart = deadline - windowMs

                    // Granițele ciclurilor: adormire ~15 min + k × 90 min.
                    var cycleTarget = 0L
                    var t = sessionStartAt + 15 * 60_000L
                    while (t <= deadline) {
                        if (t >= windowStart) cycleTarget = t
                        t += 90 * 60_000L
                    }

                    val inWindow = now in windowStart until deadline
                    val recentMovement = synchronized(movementTimes) { movementTimes.any { now - it < 3 * 60_000 } }
                    val atCycleEnd = cycleTarget in 1..now
                    if (now >= deadline || (inWindow && (recentMovement || atCycleEnd))) {
                        alarmFired = true
                        fireAlarm()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    /** „Încă 10 minute”: alarma tace și revine peste 10 minute, fără să oprească veghea. */
    private fun snoozeAlarm(app: ForjaApp) {
        AlarmRinger.stop()
        cancelAlarmNotification()
        alarmFired = false
        snoozeUntil = System.currentTimeMillis() + SNOOZE_MS
        startAlarmWatcher(app)
    }

    /**
     * Alarma nu tace niciodată: notificare cu full-screen intent + pornire directă a activității;
     * dacă notificările sunt oprite sau Android 14+ nu ne lasă alarma pe tot ecranul,
     * sună chiar serviciul. Dacă AlarmActivity nu apare în câteva secunde, tot serviciul sună.
     */
    @SuppressLint("MissingPermission")
    private fun fireAlarm() {
        val i = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val nmc = NotificationManagerCompat.from(this)
        val notificationsOn = try { nmc.areNotificationsEnabled() } catch (_: Exception) { false }
        val fullScreenOk = if (Build.VERSION.SDK_INT >= 34) {
            try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent() } catch (_: Exception) { false }
        } else true
        try {
            val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, "alarm")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Bună dimineața")
                .setContentText("E fereastra ta de trezire.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setOngoing(true)
                .setAutoCancel(true)
                .build()
            if (notificationsOn) nmc.notify(ALARM_NOTIF_ID, notif)
        } catch (_: Exception) { }
        try { startActivity(i) } catch (_: Exception) { }
        if (!notificationsOn || !fullScreenOk) AlarmRinger.start(this)
        scope.launch {
            delay(4_000)
            // Doar cât veghea e vie și alarma încă e „în așteptare”: după „M-am trezit” nu mai sunăm.
            if (running && alarmFired && !AlarmActivity.visible) AlarmRinger.start(this@SleepTrackService)
        }
    }

    private fun finishSession() {
        running = false
        finishing = true
        alarmFired = false
        snoozeUntil = 0L
        AlarmRinger.stop()
        cancelAlarmNotification()
        alarmJob?.cancel()
        try { wakeLock?.release() } catch (_: Exception) { }
        sensorManager?.unregisterListener(this)
        audioJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
        try { fullRecorder?.stop() } catch (_: Exception) { }
        fullRecorder = null
        val app = ForjaApp.from(this)
        app.presence.manualState = null
        app.auth.currentUid?.let { app.presence.publishState(it, "idle") }
        val moves = movements
        val moveTimes = synchronized(movementTimes) { movementTimes.toList() }
        val micro = synchronized(microPerMinute) { HashMap(microPerMinute) }
        val recDir = fullDir
        scope.launch {
            val dao = app.db.sleepDao()
            dao.activeSessionOnce()?.let { s ->
                val end = System.currentTimeMillis()
                val totalMin = ((end - s.startAt) / 60000).toInt().coerceAtLeast(1)
                // Stadii estimate din mișcare (Cole-Kripke simplificat) — vezi SleepStaging.
                val staging = SleepStaging.stage(SleepStaging.epochs(s.startAt, totalMin, moveTimes, micro))
                val phases = staging.phases
                val deep = staging.deepMin
                val light = staging.lightMin
                val rem = staging.remMin
                val score = staging.score
                try { recDir?.let { File(it, STAGING_FILE).writeText(SleepStaging.toJson(staging)) } } catch (_: Exception) { }

                // Înregistrarea completă: bucățile rămân local 24 h; urcarea pe server o face SleepUpload
                // (Wi-Fi, baterie ≥ 15 %), care prelungește la 7 zile când bucățile au ajuns pe server.
                var recordedUntil = 0L
                val manifest = AacRecorder.manifestFor(filesDir, s.id, s.startAt)
                val hasAudio = manifest != null && manifest.chunks.any { c ->
                    AacRecorder.chunkFile(filesDir, s.id, c).length() > 4000
                }
                if (hasAudio) {
                    recordedUntil = end + 24 * 3600_000L
                    if (app.forjaApi.available) {
                        try { SleepUpload.schedule(this@SleepTrackService, s.id) } catch (_: Exception) { }
                    }
                }

                val events = dao.eventsForSessionOnce(s.id)
                val snoreCount = events.count { it.type == "snore" }
                val talkCount = events.count { it.type == "talk" }
                val snoreMinLocal = (events.filter { it.type == "snore" }.sumOf { it.durationS } / 60).coerceAtLeast(if (snoreCount > 0) 1 else 0)

                // Rezumatul de dimineață — două propoziții din cifre reale.
                val summary = if (app.forjaApi.available) {
                    try {
                        app.forjaApi.sleepSummary(totalMin, score, deep, rem, moves, snoreCount, talkCount) ?: ""
                    } catch (_: Exception) { "" }
                } else ""

                val updated = s.copy(
                    endAt = end, movements = moves, score = score,
                    deepMin = deep, lightMin = light, remMin = rem, phases = phases,
                    summary = summary, recordedUntil = recordedUntil
                )
                dao.update(updated)
                // Raportul urcă în baza companiei — cifrele + rezumatul, nu audio-ul brut.
                // Minutele de sforăit/acoperirea se completează după analiza serverului (SleepUpload).
                try {
                    com.forja.app.core.data.CloudSync.sleep(
                        app.auth.currentUid, updated,
                        snoreCount = snoreCount,
                        talkCount = talkCount,
                        soundCount = events.count { it.type == "sound" },
                        snoreMin = snoreMinLocal,
                        coverageMin = 0
                    )
                } catch (_: Exception) { }
            }
            try { ServiceCompat.stopForeground(this@SleepTrackService, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
            promoted = false
            finishing = false
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        val now = System.currentTimeMillis()
        // Micro-mișcări: sub pragul „relevant”, dar peste zgomotul senzorului — numărate pe minut.
        if (delta > 0.35f && delta <= 1.2f && sessionStartAt > 0) {
            val minute = ((now - sessionStartAt) / 60_000L).toInt()
            synchronized(microPerMinute) { microPerMinute[minute] = (microPerMinute[minute] ?: 0) + 1 }
        }
        if (delta > 1.2f && now - lastMovementAt > 20_000) {
            lastMovementAt = now
            movements++
            synchronized(movementTimes) {
                movementTimes.add(now)
                if (movementTimes.size > 2000) movementTimes.removeAt(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "sleep")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("FORJA veghează somnul")
            .setContentText("Sunet + mișcare. Înregistrarea urcă dimineața pe server, pe Wi-Fi.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        running = false
        finishing = false
        try { wakeLock?.release() } catch (_: Exception) { }
        sensorManager?.unregisterListener(this)
        audioJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        try { fullRecorder?.stop() } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 31
        const val ALARM_NOTIF_ID = 34
        const val ACTION_STOP = "com.forja.app.sleep.STOP"
        const val ACTION_SNOOZE = "com.forja.app.sleep.SNOOZE"
        const val SNOOZE_MS = 10 * 60_000L
        /** Stadiile estimate ale nopții, scrise lângă înregistrare: `sleep_full/<id>/staging.json`. */
        const val STAGING_FILE = "staging.json"

        /** true cât timp o sesiune de somn e vie în ACEST proces. */
        @Volatile
        var running: Boolean = false
            private set

        /** true cât raportul nopții se scrie după STOP — al doilea „M-am trezit” nu mai închide sesiunea peste el. */
        @Volatile
        private var finishing: Boolean = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SleepTrackService::class.java))
        }
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun snooze(context: Context) = send(context, ACTION_SNOOZE)

        /**
         * STOP/SNOOZE sigure: dacă serviciul nu rulează (procesul a murit peste noapte),
         * nu-l mai pornim doar ca să-l oprim — închidem sesiunea direct în baza de date.
         */
        private fun send(context: Context, action: String) {
            val i = Intent(context, SleepTrackService::class.java).setAction(action)
            if (!running) {
                if (action == ACTION_STOP && !finishing) closeStaleSession(context)
                AlarmRinger.stop()
                try { NotificationManagerCompat.from(context).cancel(ALARM_NOTIF_ID) } catch (_: Exception) { }
                return
            }
            try {
                context.startService(i)
            } catch (_: Exception) {
                // IllegalStateException din fundal: serviciul e deja prim-plan, deci e permis.
                try { ContextCompat.startForegroundService(context, i) } catch (_: Exception) { }
            }
        }

        /** Închide sesiunea rămasă deschisă fără serviciu: scor 0, onest — noaptea s-a pierdut. */
        fun closeStaleSession(context: Context) {
            val app = ForjaApp.from(context)
            app.appScope.launch {
                try {
                    val dao = app.db.sleepDao()
                    dao.activeSessionOnce()?.let { s ->
                        dao.update(
                            s.copy(
                                endAt = System.currentTimeMillis(),
                                score = 0,
                                summary = "Veghea s-a întrerupt peste noapte — telefonul a oprit FORJA. Scoate-o de la optimizarea bateriei."
                            )
                        )
                    }
                } catch (_: Exception) { }
                try {
                    app.presence.manualState = null
                    app.auth.currentUid?.let { app.presence.publishState(it, "idle") }
                } catch (_: Exception) { }
            }
        }

        /** WAV PCM16 mono — header standard de 44 de octeți. */
        fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
            val dataSize = samples.size * 2
            FileOutputStream(file).use { out ->
                fun le32(v: Int) = byteArrayOf(
                    (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
                    (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte()
                )
                fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
                out.write("RIFF".toByteArray())
                out.write(le32(36 + dataSize))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(le32(16))
                out.write(le16(1))
                out.write(le16(1))
                out.write(le32(sampleRate))
                out.write(le32(sampleRate * 2))
                out.write(le16(2))
                out.write(le16(16))
                out.write("data".toByteArray())
                out.write(le32(dataSize))
                val bytes = ByteArray(dataSize)
                for (i in samples.indices) {
                    bytes[i * 2] = (samples[i].toInt() and 0xff).toByte()
                    bytes[i * 2 + 1] = (samples[i].toInt() shr 8 and 0xff).toByte()
                }
                out.write(bytes)
            }
        }
    }
}
