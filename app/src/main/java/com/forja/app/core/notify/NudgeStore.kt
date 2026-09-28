package com.forja.app.core.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
}
