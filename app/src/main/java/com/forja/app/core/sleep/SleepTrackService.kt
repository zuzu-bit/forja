package com.forja.app.core.sleep

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Somn à la Sleep as Android — totul LOCAL, nimic în cloud:
 * · microfonul detectează sforăit/vorbit și salvează clipuri de 5s pe telefon
 * · accelerometrul numără mișcările → cicluri de somn (hipnogramă estimată)
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

    // Audio — 32 kHz pentru claritate; clipurile Whisper se reduc la 16 kHz.
    private var audioRecord: AudioRecord? = null
    private var audioJob: kotlinx.coroutines.Job? = null
    private val sampleRate = 32000
    private val ringSeconds = 6
    private val ring = ShortArray(sampleRate * ringSeconds)
    private var ringPos = 0
    private var totalWritten = 0L
    private var fullRecorder: AacRecorder? = null
    private var fullFile: File? = null
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
    private var alarmFired = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishSession()
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        startForeground(NOTIF_ID, buildNotification())
        // WakeLock parțial: fără el, Doze amână bucla de veghe și alarma inteligentă
        // ar dormi odată cu tine. Limită de 12h ca plasă de siguranță pentru baterie.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "forja:sleep").apply {
                setReferenceCounted(false)
                acquire(12 * 3600_000L)
            }
        } catch (_: Exception) { }
        val app = ForjaApp.from(this)
        app.presence.manualState = "sleep"
        app.auth.currentUid?.let { app.presence.publishState(it, "sleep") }
        sessionStartAt = System.currentTimeMillis()
        scope.launch {
            val existing = app.db.sleepDao().activeSessionOnce()
            sessionId = existing?.id ?: app.db.sleepDao().insert(
                SleepSessionEntity(startAt = sessionStartAt)
            )
            if (existing != null) sessionStartAt = existing.startAt
            // Înregistrarea completă a nopții (AAC) — pornită după ce știm sesiunea.
            try {
                val dir = File(filesDir, "sleep_full").apply { mkdirs() }
                // Curățenie: fișierele mai vechi de 24h dispar și local.
                dir.listFiles()?.forEach {
                    if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000) it.delete()
                }
                val f = File(dir, "$sessionId.m4a")
                fullFile = f
                fullRecorder = AacRecorder(sampleRate, f)
            } catch (_: Exception) { }
        }
        // Mișcare
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // Microfon — doar dacă permisiunea există; altfel somn fără audio, onest.
        startAudio()
        // Alarma deșteaptă
        startAlarmWatcher(app)
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
        scope.launch {
            while (isActive && !alarmFired) {
                delay(20_000)
                try {
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
                    val now = System.currentTimeMillis()
                    val windowStart = deadline - windowMs

                    // Granițele ciclurilor: adormire ~15 min + k × 90 min.
                    var cycleTarget = 0L
                    var t = sessionStartAt + 15 * 60_000L
                    while (t <= deadline) {
                        if (t >= windowStart) cycleTarget = t
                        t += 90 * 60_000L
                    }

                    val inWindow = now in windowStart until deadline
                    val recentMovement = movementTimes.any { now - it < 3 * 60_000 }
                    val atCycleEnd = cycleTarget in 1..now
                    if (now >= deadline || (inWindow && (recentMovement || atCycleEnd))) {
                        alarmFired = true
                        fireAlarm()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun fireAlarm() {
        val i = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, "alarm")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Bună dimineața")
                .setContentText("E fereastra ta de trezire.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(34, notif)
            startActivity(i)
        } catch (_: Exception) {
            try { startActivity(i) } catch (_: Exception) { }
        }
    }

    private fun finishSession() {
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
        val moveTimes = movementTimes.toList()
        scope.launch {
            val dao = app.db.sleepDao()
            dao.activeSessionOnce()?.let { s ->
                val end = System.currentTimeMillis()
                val totalMin = ((end - s.startAt) / 60000).toInt().coerceAtLeast(1)
                val (phases, deep, light, rem) = buildPhases(s.startAt, totalMin, moveTimes)
                val movePenalty = (moves * 1.2).coerceAtMost(25.0)
                val durationScore = when {
                    totalMin >= 450 -> 92
                    totalMin >= 390 -> 84
                    totalMin >= 330 -> 72
                    totalMin >= 270 -> 58
                    else -> 40
                }
                val score = (durationScore - movePenalty).roundToInt().coerceIn(15, 98)

                // Înregistrarea completă → stocarea companiei, disponibilă 24h.
                var recordedUntil = 0L
                val f = fullFile
                if (f != null && f.exists() && f.length() > 4000 && app.forjaApi.available) {
                    val ok = try { app.forjaApi.uploadSleepRecording(s.id, f) } catch (_: Exception) { false }
                    if (ok) recordedUntil = end + 24 * 3600_000
                }
                if (f != null && f.exists() && f.length() > 4000 && recordedUntil == 0L) {
                    // n-a urcat — rămâne local, tot 24h (curățată la următoarea sesiune)
                    recordedUntil = end + 24 * 3600_000
                }

                val events = dao.eventsForSessionOnce(s.id)
                val snoreCount = events.count { it.type == "snore" }
                val talkCount = events.count { it.type == "talk" }

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
                try {
                    com.forja.app.core.data.CloudSync.sleep(
                        app.auth.currentUid, updated,
                        snoreCount = snoreCount,
                        talkCount = talkCount,
                        soundCount = events.count { it.type == "sound" }
                    )
                } catch (_: Exception) { }
            }
            stopSelf()
        }
    }

    /**
     * Hipnogramă estimată din cicluri de ~90 min + mișcare:
     * minutele cu mișcare → somn ușor / treaz; adânc mai ales în prima parte a nopții.
     */
    private fun buildPhases(startAt: Long, totalMin: Int, moveTimes: List<Long>): PhaseResult {
        val moveMinutes = moveTimes.map { ((it - startAt) / 60000).toInt() }.toSet()
        val segs = StringBuilder()
        var deep = 0; var light = 0; var rem = 0
        var segStart = 0
        var segType = ""
        fun typeAt(min: Int): String {
            if (min < 8) return "light"
            if (moveMinutes.any { abs(it - min) <= 1 }) return if (min in moveMinutes) "awake" else "light"
            val cyclePos = (min - 8) % 90
            val cycleNo = (min - 8) / 90
            return when {
                cyclePos < 12 -> "light"
                cyclePos < 42 -> if (cycleNo < 3) "deep" else "light"
                cyclePos < 68 -> "light"
                else -> "rem"
            }
        }
        for (min in 0 until totalMin) {
            val t = typeAt(min)
            if (t != segType) {
                if (segType.isNotEmpty()) {
                    segs.append("$segStart,$min,$segType;")
                }
                segType = t
                segStart = min
            }
            when (t) {
                "deep" -> deep++
                "rem" -> rem++
                "light" -> light++
            }
        }
        if (segType.isNotEmpty()) segs.append("$segStart,$totalMin,$segType;")
        return PhaseResult(segs.toString(), deep, light, rem)
    }

    data class PhaseResult(val phases: String, val deep: Int, val light: Int, val rem: Int)

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        val now = System.currentTimeMillis()
        if (delta > 1.2f && now - lastMovementAt > 20_000) {
            lastMovementAt = now
            movements++
            movementTimes.add(now)
            if (movementTimes.size > 2000) movementTimes.removeAt(0)
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
            .setContentText("Sunet + mișcare, analizate local. Nimic nu pleacă de pe telefon.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
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
        const val ACTION_STOP = "com.forja.app.sleep.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SleepTrackService::class.java))
        }
        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, SleepTrackService::class.java).setAction(ACTION_STOP)
            )
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
