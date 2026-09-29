package com.forja.app.core.sleep

/**
 * Oglinda nopții pe site (pachetul B), fără Firebase și fără Android — doar hărțile care se scriu, ca să poată fi
 * testate pe JVM. [SleepCloud] le trimite:
 *  · users/{uid}/sleep/s{id} (jurnalul, fără contract): starea (recording | done | interrupted), alarma, sunetele de
 *    adormit, stingerea, urcarea sunetului;
 *  · users/{uid}/sleepEvents/s{id} (doar cu contractul v4, „Cronologia nopții”): fazele, trezirile, liniile scorului,
 *    cel mult [EVENTS_MAX] momente (sforăit, vorbit, tuse, sunet) cu ce s-a înțeles din vorbe, limitările analizei.
 *    Revocarea o șterge (SiteMirror.MIRRORED), jurnalul rămâne.
 */
object SleepNightDoc {
    const val EVENTS_MAX = 200
    const val TEXT_MAX = 200
    const val LIMITS_MAX = 6

    /** Un moment prins de detectorul de pe telefon (clipul de 5 s rămâne pe telefon). */
    data class PhoneEvent(val id: Long, val type: String, val at: Long, val durationS: Int, val intensity: Int, val transcript: String?)

    /** Alarma deșteaptă a nopții. `target` = ora-limită (epoch ms); `firedAt` 0 = n-a sunat în veghe. */
    data class Alarm(val on: Boolean, val target: Long, val windowMin: Int, val firedAt: Long = 0L, val reason: String = "", val snoozes: Int = 0)

    /** Sunetul de adormit și cât a cântat în timpul veghei. */
    data class SoundUse(val sound: String, val minutes: Int)

    /** Starea urcării sunetului, cum o vede site-ul. */
    data class Audio(val chunks: Int, val uploaded: Int, val state: String, val lastError: String, val audioStartAt: Long, val recordedUntil: Long)

    fun alarmMap(a: Alarm?): Map<String, Any>? = a?.takeIf { it.on }?.let {
        buildMap {
            put("target", it.target)
            put("windowMin", it.windowMin)
            if (it.firedAt > 0L) put("firedAt", it.firedAt)
            if (it.reason.isNotBlank()) put("reason", it.reason)
            put("snoozes", it.snoozes)
        }
    }

    fun soundsList(list: List<SoundUse>): List<Map<String, Any>> =
        list.filter { it.minutes > 0 && it.sound.isNotBlank() }.sortedByDescending { it.minutes }.take(6)
            .map { mapOf("sound" to it.sound, "minutes" to it.minutes) }

    fun audioMap(a: Audio): Map<String, Any> = buildMap {
        put("chunks", a.chunks)
        put("uploaded", a.uploaded)
        put("state", a.state)
        if (a.lastError.isNotBlank()) put("lastError", a.lastError.take(120))
        if (a.audioStartAt > 0L) put("audioStartAt", a.audioStartAt)
        if (a.recordedUntil > 0L) put("recordedUntil", a.recordedUntil)
    }

    /**
     * Starea urcării din `upload.json` (fără el: încă nu a pornit nicio rulare, adică se așteaptă rețeaua).
     * none · waiting_wifi · waiting_net · waiting_battery · uploading · analyzing · done · failed
     */
    fun audioState(
        chunks: Int, uploaded: Int, rejected: Int, analyzeRequested: Boolean, done: Boolean,
        attempts: Int, maxAttempts: Int, lastError: String, cellular: Boolean, started: Boolean
    ): String = when {
        chunks <= 0 -> "none"
        done -> if (uploaded > 0 && lastError.isBlank()) "done" else "failed"
        attempts >= maxAttempts -> "failed"
        analyzeRequested -> "analyzing"
        lastError.contains("baterie") -> "waiting_battery"
        uploaded + rejected >= chunks -> "analyzing"
        !started || uploaded == 0 -> if (cellular) "waiting_net" else "waiting_wifi"
        else -> "uploading"
    }

    /** Detectorul de pe telefon: 1 · 2 · 3 → 0..1, ca intensitatea serverului. */
    fun phoneIntensity(i: Int): Double = when { i >= 3 -> 0.85; i == 2 -> 0.55; i == 1 -> 0.3; else -> 0.0 }

    private fun kindOf(type: String): String = when (type) {
        "talk", "snore", "cough", "breath" -> type
        else -> "noise"
    }

    /**
     * Momentele nopții, pe ceas (epoch ms): ale serverului (ms de la începutul audio-ului + `audioStartAt`) și ale
     * telefonului. Peste [EVENTS_MAX] rămân întâi vorbele cu text, apoi cele mai puternice; în ordinea orei.
     */
    fun events(phone: List<PhoneEvent>, server: SleepTimeline?, audioStartAt: Long): List<Map<String, Any>> {
        data class Row(val kind: String, val at: Long, val dur: Long, val intensity: Double, val text: String, val source: String, val id: Long)
        val rows = ArrayList<Row>()
        val base = if (audioStartAt > 0L) audioStartAt else server?.startedAt ?: 0L
        if (server != null && base > 0L) for (e in server.events) {
            if (e.type == "silence") continue
            // absolut (clienți vechi) sau relativ la audio
            val at = if (e.at > 1_000_000_000_000L) e.at else base + e.at
            rows += Row(kindOf(e.type), at, e.durMs, e.intensity.coerceIn(0.0, 1.0), e.transcript.trim().take(TEXT_MAX), "server", 0L)
        }
        for (e in phone) {
            if (e.type == "move") continue
            rows += Row(kindOf(e.type), e.at, e.durationS.coerceAtLeast(0) * 1000L, phoneIntensity(e.intensity), (e.transcript ?: "").trim().take(TEXT_MAX), "phone", e.id)
        }
        val kept = if (rows.size <= EVENTS_MAX) rows else rows
            .sortedWith(compareByDescending<Row> { it.text.isNotEmpty() }.thenByDescending { it.intensity }.thenBy { it.at })
            .take(EVENTS_MAX)
        return kept.sortedBy { it.at }.map { r ->
            buildMap {
                put("kind", r.kind); put("at", r.at); put("dur", r.dur)
                put("intensity", Math.round(r.intensity * 100) / 100.0)
                if (r.text.isNotEmpty()) put("text", r.text)
                put("source", r.source)
                if (r.id > 0L) put("pid", r.id)
            }
        }
    }

    /** users/{uid}/sleepEvents/s{id} — cronologia întreagă a nopții, fără sunet. */
    fun timelineDoc(
        startAt: Long, staging: SleepStaging.Result?, phone: List<PhoneEvent>, server: SleepTimeline?,
        audioStartAt: Long, now: Long
    ): Map<String, Any> = buildMap {
        put("startAt", startAt)
        if (audioStartAt > 0L) put("audioStartAt", audioStartAt)
        if (staging != null) {
            if (staging.phases.isNotBlank()) put("phases", staging.phases.take(8000))
            put("awakeMin", staging.awakeMin)
            put("latencyMin", staging.latencyMin)
            put("awakenings", staging.awakenings)
            put("scoreLines", staging.lines.take(8).map { mapOf("delta" to it.delta, "reason" to it.reason.take(80)) })
        }
        put("events", events(phone, server, audioStartAt))
        if (server != null) {
            put("analysis", server.status)
            put("limits", server.limits.filter { it.isNotBlank() }.take(LIMITS_MAX).map { it.take(200) })
            put("stats", mapOf(
                "snoreEpisodes" to server.stats.snoreEpisodes, "coughCount" to server.stats.coughCount,
                "coverageMin" to server.stats.coverageMin, "totalMin" to server.stats.totalMin
            ))
        }
        put("updatedAt", now)
    }
}
