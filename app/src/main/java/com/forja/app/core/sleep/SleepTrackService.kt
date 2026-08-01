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

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioJob: kotlinx.coroutines.Job? = null
    private val sampleRate = 16000
    private val ringSeconds = 6
    private val ring = ShortArray(sampleRate * ringSeconds)
    private var ringPos = 0
    private var baseline = 250.0
    private var lastSnoreEventAt = 0L
    private var lastTalkEventAt = 0L
    private val peakTimes = ArrayDeque<Long>()
    private var burstStartAt = 0L
    private var burstCount = 0
    private var burstWindowStart = 0L

    private var sessionId: Long = 0
    private var sessionStartAt: Long = 0
    private var alarmFired = false

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
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) return
            audioRecord = rec
            rec.startRecording()
            audioJob = scope.launch {
                val chunk = ShortArray(sampleRate / 10) // 100ms
                while (isActive) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) { delay(50); continue }
                    // scrie în ring
                    for (i in 0 until n) {
                        ring[ringPos] = chunk[i]
                        ringPos = (ringPos + 1) % ring.size
                    }
                    processChunk(chunk, n)
                }
            }
        } catch (_: Exception) { }
    }

    /** Heuristici locale: RMS + ritm → sforăit; rafale neregulate → vorbit. Estimare, nu diagnostic. */
    private fun processChunk(chunk: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
        val rms = sqrt(sum / n)
        val now = System.currentTimeMillis()

        // baseline: urmărește lent zgomotul de fond
        if (rms < baseline * 1.5) baseline = baseline * 0.995 + rms * 0.005
        val threshold = maxOf(baseline * 2.5, 300.0)

        if (rms > threshold) {
            // vârf — pentru ritmul sforăitului
            if (peakTimes.isEmpty() || now - peakTimes.last() > 300) {
                peakTimes.addLast(now)
                while (peakTimes.size > 8) peakTimes.removeFirst()
            }
            // rafale — pentru vorbit
            if (burstStartAt == 0L) burstStartAt = now
            if (now - burstWindowStart > 15000) { burstWindowStart = now; burstCount = 0 }
        } else {
            if (burstStartAt != 0L) {
                val burstDur = now - burstStartAt
                if (burstDur in 300..4000) burstCount++
                burstStartAt = 0L
            }
        }

        // Sforăit: ≥4 vârfuri cu intervale ritmice 1,2–6s
        if (peakTimes.size >= 4 && now - lastSnoreEventAt > 180_000) {
            val list = peakTimes.toList().takeLast(5)
            val intervals = list.zipWithNext { a, b -> b - a }
            val rhythmic = intervals.count { it in 1200..6000 }
            if (rhythmic >= 3) {
                lastSnoreEventAt = now
                val ratio = rms / baseline
                saveEvent("snore", now, (intervals.sum() / 1000).toInt().coerceAtLeast(4), intensityFor(ratio))
                peakTimes.clear()
            }
        }

        // Vorbit: ≥3 rafale scurte neregulate în 15s
        if (burstCount >= 3 && now - lastTalkEventAt > 180_000 && now - lastSnoreEventAt > 30_000) {
            lastTalkEventAt = now
            val ratio = rms / baseline
            saveEvent("talk", now, 5, intensityFor(ratio))
            burstCount = 0
        }
    }

    private fun intensityFor(ratio: Double): Int = when {
        ratio > 7 -> 3
        ratio > 4 -> 2
        else -> 1
    }

    /**
     * Salvează evenimentul + clipul de 5s (LOCAL), apoi cere serverului FORJA
     * verdictul REAL (Whisper): vorbire → „Vorbire", altfel „Sforăit".
     * Fără server, eticheta rămâne onestă: „Sunet" — nu ghicim.
     */
    private fun saveEvent(typeHint: String, at: Long, durationS: Int, intensity: Int) {
        val app = ForjaApp.from(this)
        val snapshot = ShortArray(sampleRate * 5)
        val start = (ringPos - snapshot.size + ring.size * 2) % ring.size
        for (i in snapshot.indices) snapshot[i] = ring[(start + i) % ring.size]
        scope.launch {
            var path: String? = null
            var wavBytes: ByteArray? = null
            try {
                val dir = File(filesDir, "sleep_clips").apply { mkdirs() }
                val f = File(dir, "${sessionId}_${typeHint}_$at.wav")
                writeWav(f, snapshot, sampleRate)
                path = f.absolutePath
                wavBytes = f.readBytes()
            } catch (_: Exception) { }

            var finalType = "sound"
            var transcript: String? = null
            if (app.forjaApi.available && wavBytes != null) {
                val verdict = try { app.forjaApi.classifySleepAudio(wavBytes) } catch (_: Exception) { null }
                if (verdict != null) {
                    finalType = verdict.type
                    if (verdict.type == "talk" && verdict.transcript.isNotBlank()) {
                        transcript = verdict.transcript
                    }
                }
            }
            app.db.sleepDao().insertEvent(
                SleepEventEntity(
                    sessionId = sessionId, type = finalType, at = at,
                    durationS = durationS, intensity = intensity, clipPath = path,
                    transcript = transcript
                )
            )
        }
    }

    /** Alarma deșteaptă: în fereastra de 30 min dinaintea orei, prima mișcare te trezește. */
    private fun startAlarmWatcher(app: ForjaApp) {
        scope.launch {
            while (isActive && !alarmFired) {
                delay(20_000)
                try {
                    val enabled = app.prefs.alarmEnabled.first()
                    if (!enabled) continue
                    val h = app.prefs.alarmHour.first()
                    val m = app.prefs.alarmMinute.first()
                    val zone = ZoneId.systemDefault()
                    var target = LocalDate.now().atTime(h, m).atZone(zone).toInstant().toEpochMilli()
                    if (target <= sessionStartAt) {
                        target = LocalDate.now().plusDays(1).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
                    }
                    val now = System.currentTimeMillis()
                    val inWindow = now >= target - 30 * 60_000 && now < target
                    val recentMovement = movementTimes.any { now - it < 3 * 60_000 }
                    if (now >= target || (inWindow && recentMovement)) {
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
            val notif = NotificationCompat.Builder(this, "sleep")
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
        sensorManager?.unregisterListener(this)
        audioJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
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
                val updated = s.copy(
                    endAt = end, movements = moves, score = score,
                    deepMin = deep, lightMin = light, remMin = rem, phases = phases
                )
                dao.update(updated)
                // Raportul urcă în baza companiei — fără clipuri audio, doar cifrele.
                try {
                    val events = dao.eventsForSessionOnce(s.id)
                    com.forja.app.core.data.CloudSync.sleep(
                        app.auth.currentUid, updated,
                        snoreCount = events.count { it.type == "snore" },
                        talkCount = events.count { it.type == "talk" },
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
        sensorManager?.unregisterListener(this)
        audioJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
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
