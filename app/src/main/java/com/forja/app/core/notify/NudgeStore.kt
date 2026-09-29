package com.forja.app.core.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.channels.awaitClose

/**
 * Memoria Cascăi, doar pe telefon: fișierul propriu `forja_nudge` (SharedPreferences, sincron — o citesc și serviciul
 * de sincronizare pe firul principal, și lucrătorul, și receptorul). Scrierile trec printr-un singur zăvor.
 */
internal object NudgeStore {
    private const val FILE = "forja_nudge"
    private const val KEY_STATE = "state_v1"
    private const val KEY_SYNC_LINE = "sync_line_v1"
    private const val KEY_SYNC_POSTED = "sync_posted_v1"
    private const val KEY_SYNC_DISMISSED = "sync_dismissed"
    private const val KEY_SYNC_EVENT = "sync_event_at"
    private const val KEY_BED_ARMED = "bedtime_armed"
    private const val KEY_BED_FIRED = "bedtime_fired"
    private const val KEY_ECHOES = "echoes_v1"
    private const val ECHOES_MAX = 6
    private const val KEY_LOG = "log_v1"
    private const val LOG_MAX = 200
    private const val LOG_KEEP_MS = 15L * 86_400_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(c: Context): NudgeState = synchronized(lock) { readUnlocked(c) }

    private fun readUnlocked(c: Context): NudgeState = try {
        prefs(c).getString(KEY_STATE, null)?.let { json.decodeFromString(NudgeState.serializer(), it) } ?: NudgeState()
    } catch (_: Exception) { NudgeState() }

    /** Citește, transformă, scrie — atomic față de ceilalți scriitori din proces. */
    fun update(c: Context, f: (NudgeState) -> NudgeState): NudgeState = synchronized(lock) {
        val next = f(readUnlocked(c))
        try { prefs(c).edit().putString(KEY_STATE, json.encodeToString(NudgeState.serializer(), next)).apply() } catch (_: Exception) { }
        next
    }

    // ───────────── Notificarea permanentă (citită sincron de serviciu) ─────────────

    /** Replica pregătită pentru fereastra curentă a notificării permanente. */
    @Serializable
    data class SyncLine(
        val slotKey: String, val id: String, val title: String, val body: String, val pose: String,
        val at: Long, val personal: Boolean
    )

    /** Ce e afișat acum pe notificarea permanentă. */
    @Serializable
    data class SyncPosted(val slotKey: String, val id: String, val at: Long, val personal: Boolean)

    fun syncLine(c: Context): SyncLine? = try {
        prefs(c).getString(KEY_SYNC_LINE, null)?.let { json.decodeFromString(SyncLine.serializer(), it) }
    } catch (_: Exception) { null }

    fun setSyncLine(c: Context, l: SyncLine) {
        try { prefs(c).edit().putString(KEY_SYNC_LINE, json.encodeToString(SyncLine.serializer(), l)).apply() } catch (_: Exception) { }
    }

    fun syncPosted(c: Context): SyncPosted? = try {
        prefs(c).getString(KEY_SYNC_POSTED, null)?.let { json.decodeFromString(SyncPosted.serializer(), it) }
    } catch (_: Exception) { null }

    fun setSyncPosted(c: Context, p: SyncPosted) {
        try { prefs(c).edit().putString(KEY_SYNC_POSTED, json.encodeToString(SyncPosted.serializer(), p)).apply() } catch (_: Exception) { }
    }

    /** Swipe pe notificarea permanentă (Android 14+): nu o mai re-postăm până la următorul start al serviciului. */
    fun syncDismissed(c: Context): Boolean = prefs(c).getBoolean(KEY_SYNC_DISMISSED, false)
    fun setSyncDismissed(c: Context, v: Boolean) { prefs(c).edit().putBoolean(KEY_SYNC_DISMISSED, v).apply() }

    /** Un eveniment (loc nou, raport gata) merită o replică nouă pe notificarea permanentă (cel mult o dată la 3 h). */
    fun syncEventAt(c: Context): Long = prefs(c).getLong(KEY_SYNC_EVENT, 0L)
    fun markSyncEvent(c: Context) { prefs(c).edit().putLong(KEY_SYNC_EVENT, System.currentTimeMillis()).apply() }

    // ───────────── Culcarea: amintirea armată și ultima care a sunat (ca re-armarea să nu o anuleze pe cea de diseară) ─────────────

    fun bedtimeArmed(c: Context): Long = prefs(c).getLong(KEY_BED_ARMED, 0L)
    fun setBedtimeArmed(c: Context, t: Long) { prefs(c).edit().putLong(KEY_BED_ARMED, t).apply() }
    fun bedtimeFired(c: Context): Long = prefs(c).getLong(KEY_BED_FIRED, 0L)
    fun setBedtimeFired(c: Context, t: Long) { prefs(c).edit().putLong(KEY_BED_FIRED, t).apply() }

    // ───────────── Ecoul de pe Panou: replica stă aici, nu în Intent ─────────────

    /**
     * Un mesaj trimis cu ecou. Intentul notificării poartă doar `key` (aleator): titlul, textul și poza se iau de aici,
     * ca o altă aplicație care pornește MainActivity (exportată, e lansatorul) să nu poată pune vorbe în gura Cascăi.
     */
    @Serializable
    data class EchoRec(val key: String, val id: String, val ctx: String, val title: String, val body: String, val pose: String, val at: Long)

    private fun echoesUnlocked(c: Context): List<EchoRec> = try {
        prefs(c).getString(KEY_ECHOES, null)?.let { json.decodeFromString(ListSerializer(EchoRec.serializer()), it) } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun writeEchoes(c: Context, list: List<EchoRec>) {
        try { prefs(c).edit().putString(KEY_ECHOES, json.encodeToString(ListSerializer(EchoRec.serializer()), list)).apply() } catch (_: Exception) { }
    }

    fun addEcho(c: Context, e: EchoRec) = synchronized(lock) {
        writeEchoes(c, (echoesUnlocked(c).filter { it.key != e.key } + e).takeLast(ECHOES_MAX))
    }

    /** Ecoul cu cheia dată, o singură dată (se șterge la citire); null pentru o cheie necunoscută. */
    fun takeEcho(c: Context, key: String): EchoRec? = synchronized(lock) {
        val all = echoesUnlocked(c)
        val hit = all.firstOrNull { it.key == key } ?: return@synchronized null
        writeEchoes(c, all.filter { it.key != key })
        hit
    }

    // ───────────── Jurnalul Căștii (mirror D): ce a trimis și ce ai făcut cu mesajul ─────────────

    /**
     * Un mesaj trimis. Mesajele private (somnul, prietenii, caloriile: Rendered.private / localOnly) se țin fără titlu și
     * text — pe site ajung doar ca fel. `outcome`: posted · opened · tapped · dismissed.
     */
    @Serializable
    data class LogRec(
        val at: Long, val id: String, val ctx: String, val channel: String,
        val title: String? = null, val body: String? = null, val private: Boolean = false, val outcome: String = "posted"
    )

    private fun logUnlocked(c: Context): List<LogRec> = try {
        prefs(c).getString(KEY_LOG, null)?.let { json.decodeFromString(ListSerializer(LogRec.serializer()), it) } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun writeLog(c: Context, list: List<LogRec>) {
        val now = System.currentTimeMillis()
        val keep = list.filter { now - it.at < LOG_KEEP_MS }.takeLast(LOG_MAX)
        try { prefs(c).edit().putString(KEY_LOG, json.encodeToString(ListSerializer(LogRec.serializer()), keep)).apply() } catch (_: Exception) { }
    }

    fun log(c: Context): List<LogRec> = synchronized(lock) { logUnlocked(c) }

    /** Alt cont a intrat pe telefon (MindOwner): jurnalul Căștii celui dinainte nu trece la el. */
    fun clearLog(c: Context) = synchronized(lock) { try { prefs(c).edit().remove(KEY_LOG).apply() } catch (_: Exception) { } }

    fun logPosted(c: Context, r: Rendered, channel: String, now: Long) = synchronized(lock) {
        val hidden = r.private || r.localOnly
        writeLog(c, logUnlocked(c) + LogRec(now, r.id, r.context.name, channel, if (hidden) null else r.title, if (hidden) null else r.body, hidden))
    }

    /** Ultimul mesaj încă „posted” care se potrivește (după id sau context) primește rezultatul. */
    fun logOutcome(c: Context, outcome: String, id: String? = null, ctx: String? = null) = synchronized(lock) {
        val all = logUnlocked(c)
        val i = all.indexOfLast { it.outcome == "posted" && (id == null || it.id == id) && (ctx == null || it.ctx == ctx) }
        if (i < 0) return@synchronized
        writeLog(c, all.toMutableList().also { it[i] = it[i].copy(outcome = outcome) })
    }

    /** Se schimbă jurnalul (pentru ListenMirror): emite la abonare și la fiecare scriere. */
    fun logChanges(c: Context): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.callbackFlow {
        val p = prefs(c)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY_LOG) trySend(System.currentTimeMillis()) }
        p.registerOnSharedPreferenceChangeListener(l)
        trySend(System.currentTimeMillis())
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }
}
