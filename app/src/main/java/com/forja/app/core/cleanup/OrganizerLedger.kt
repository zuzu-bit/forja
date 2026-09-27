package com.forja.app.core.cleanup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

// ═══════════════ Registrul organizării pe site (protocolul 4) ═══════════════
// Progresul rămâne pe telefon în „organizer_v4.db" (fără Room — contractul v4.0): lucrări, elemente,
// identitatea stabilă a originalelor. Copiile din cont expiră după 24 h; registrul nu.

/** O lucrare de organizare: pornită de pe telefon (după o scanare) sau primită din panoul online. */
@Serializable
data class OrgJob(
    val id: String,
    val owner: String,
    val device: String,
    val source: String,                 // "photos" | "files"
    val origin: String,                 // "phone" | "site"
    val destination: String,            // dosarul rădăcină al mutărilor, ex. "FORJA Curățenie", "Organizate", "FORJA"
    val mode: String,                   // "local" | "online" | "manual"
    val upload: Boolean,                // copiile analizate urcă în cont (24 h)
    val autoApply: Boolean,             // site-ul a cerut aplicarea automată (cere grant.organize)
    val createdAt: Long,
    var grantId: String = "",
    var tree: String = "",              // arborele SAF (files)
    var treeLabel: String = "",
    var folder: String = "",            // scope.folder (relativ la sursă)
    var recursive: Boolean = true,
    var count: Int = 0,                 // 0 = tot
    var scopeJson: String = "",         // CleanupScope (photos, lucrări din telefon)
    var sourceId: String? = null,
    var state: String = "queued",
    var message: String = "",
    var registered: Boolean = false,
    var commandId: String = "",         // request_id al comenzii „continue" curente
    var remoteRevision: Int = 0,
    var syncRevision: Long = -1L,
    var control: String = "",           // comandă locală de trimis: "pause" | "cancel" | ""
    var controlRequest: String = "",    // request_id al comenzii locale (idempotent la reluare)
    var pulledRevision: Long = -2L,     // sync_revision până la care am citit aprobările din site
    var remoteReady: Int = 0,           // counters.ready de pe site (aprobări de citit)
    var inventoryDone: Boolean = false,
    var inventorySent: Boolean = false,
    var scannedTotal: Int = 0,          // câte elemente a văzut scanarea (informativ)
    var uploadedBytes: Long = 0L,
    var touchNotified: Boolean = false,
    var updatedAt: Long = createdAt
) {
    val active: Boolean get() = state !in setOf("cancelled", "complete")
    val stopped: Boolean get() = state in setOf("cancelled", "paused", "needs_access")
}

/** O versiune a unui original (id = UUID al versiunii, originalId = UUID stabil al fișierului). */
@Serializable
data class OrgItem(
    val id: String,
    val job: String,
    val originalId: String,
    val uri: String,
    val name: String,
    val folder: String,                 // dosarul relativ, curățat pentru server (≤ 8 segmente)
    val mime: String,
    val bytes: Long,
    val modifiedAt: Long,
    val photo: Boolean,
    val flagged: Boolean,
    val seq: Int,
    val mediaId: Long = 0L,
    val relativePath: String = "",
    val docId: String = "",
    val parentUri: String = "",
    val docPath: String = "",
    val docFlags: Int = 0,
    var reason: String = "ok",
    var sha: String = "",
    var state: String = "pending",
    var operationId: String = UUID.randomUUID().toString(),
    var copyId: String = UUID.randomUUID().toString(),
    var batchRequest: String = "",
    var batched: Boolean = false,
    var published: Boolean = false,
    var extraction: String? = null,     // JSON pentru server (doar fișiere text)
    var copyReceived: Boolean = false,
    var copyExpires: Long = 0L,
    var thumbnailReceived: Boolean = false,
    var analysisJson: String = "",      // analiza site-ului (destination, reason, confidence, status)
    var destination: String = "",
    var approved: Boolean = false,
    var intentSaved: Boolean = false,
    var resultUri: String = "",
    var targetSha: String = "",
    var error: String = "",
    var sent: Map<String, String> = emptyMap(),    // stare → chitanța confirmată de server (JSON)
    var queue: List<String> = emptyList(),         // chitanțe de trimis, în ordine (JSON)
    var updatedAt: Long = 0L
) {
    val terminal: Boolean get() = state in setOf("moved", "skipped", "unavailable")
    /** Chitanța cu starea dată a fost deja confirmată sau așteaptă în coadă. */
    fun hasReceipt(s: String): Boolean = sent.containsKey(s) || queue.any { it.contains("\"state\":\"$s\"") }
}

/** Analiza venită de pe site pentru un element (doar afișare + propunere; nimic nu se aplică singur). */
@Serializable
data class SiteAnalysis(
    val destination: String = "",
    val reason: String = "",
    val confidence: String = "",
    val status: String = "",
    val coverage: String = "",
    val deleteSuggested: Boolean = false
)

class OrganizerLedger private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "organizer_v4.db", null, 1) {

    companion object {
        @Volatile private var instance: OrganizerLedger? = null
        fun get(context: Context): OrganizerLedger =
            instance ?: synchronized(this) { instance ?: OrganizerLedger(context).also { instance = it } }

        /** Crește la orice scriere — ecranul reîncarcă starea când se schimbă. */
        private val _changed = MutableStateFlow(0L)
        val changed: StateFlow<Long> = _changed

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Identitatea stabilă a unui fișier: pentru SAF autoritatea + documentId, altfel uri-ul. */
        fun identity(uri: String): String = try {
            val u = Uri.parse(uri)
            if (u.pathSegments.contains("document")) u.authority + ":" + DocumentsContract.getDocumentId(u) else uri
        } catch (_: Exception) { uri }

        val ACTIVE_STATES = setOf("queued", "inventory", "hashing", "publishing", "uploading", "analyzing", "applying", "failed_retryable", "ready", "needs_permission")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, owner TEXT NOT NULL, created INTEGER NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX jobs_owner ON jobs(owner, created)")
        db.execSQL("CREATE TABLE items(job TEXT NOT NULL, id TEXT NOT NULL, original_id TEXT NOT NULL, uri TEXT NOT NULL, state TEXT NOT NULL, seq INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(job, id))")
        db.execSQL("CREATE INDEX items_state ON items(job, state, seq)")
        db.execSQL("CREATE INDEX items_uri ON items(job, uri)")
        db.execSQL("CREATE TABLE originals(owner TEXT NOT NULL, identity TEXT NOT NULL, original_id TEXT NOT NULL, PRIMARY KEY(owner, identity))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun bump() { _changed.value = _changed.value + 1 }

    // ─────────────────────────── Lucrări ───────────────────────────

    fun job(id: String): OrgJob? = readableDatabase.rawQuery("SELECT payload FROM jobs WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) decodeJob(c.getString(0)) else null
    }

    fun jobs(owner: String, limit: Int = 50): List<OrgJob> =
        readableDatabase.rawQuery("SELECT payload FROM jobs WHERE owner = ? ORDER BY created DESC LIMIT ?", arrayOf(owner, limit.toString())).use { c ->
            val out = ArrayList<OrgJob>()
            while (c.moveToNext()) decodeJob(c.getString(0))?.let { out += it }
            out
        }

    fun saveJob(job: OrgJob) {
        job.updatedAt = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("id", job.id); put("owner", job.owner); put("created", job.createdAt); put("state", job.state)
            put("payload", json.encodeToString(OrgJob.serializer(), job))
        }
        writableDatabase.insertWithOnConflict("jobs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun deleteJob(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("items", "job = ?", arrayOf(id))
            db.delete("jobs", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }

    private fun decodeJob(raw: String): OrgJob? = try { json.decodeFromString(OrgJob.serializer(), raw) } catch (_: Exception) { null }
    private fun decodeItem(raw: String): OrgItem? = try { json.decodeFromString(OrgItem.serializer(), raw) } catch (_: Exception) { null }

    // ─────────────────────────── Elemente ───────────────────────────

    fun items(job: String, states: Set<String>? = null, limit: Int = 100_000): List<OrgItem> {
        val sql = StringBuilder("SELECT payload FROM items WHERE job = ?")
        val args = ArrayList<String>().apply { add(job) }
        if (states != null && states.isNotEmpty()) {
            sql.append(" AND state IN (").append(states.joinToString(",") { "?" }).append(")")
            args += states
        }
        sql.append(" ORDER BY seq LIMIT ?"); args += limit.toString()
        return readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            val out = ArrayList<OrgItem>()
            while (c.moveToNext()) decodeItem(c.getString(0))?.let { out += it }
            out
        }
    }

    fun item(job: String, id: String): OrgItem? = readableDatabase.rawQuery("SELECT payload FROM items WHERE job = ? AND id = ?", arrayOf(job, id)).use { c ->
        if (c.moveToFirst()) decodeItem(c.getString(0)) else null
    }

    fun itemByUri(job: String, uri: String): OrgItem? = readableDatabase.rawQuery("SELECT payload FROM items WHERE job = ? AND uri = ?", arrayOf(job, uri)).use { c ->
        if (c.moveToFirst()) decodeItem(c.getString(0)) else null
    }

    fun saveItem(item: OrgItem) { saveItems(listOf(item)) }

    fun saveItems(list: List<OrgItem>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (item in list) {
                item.updatedAt = now
                val v = ContentValues().apply {
                    put("job", item.job); put("id", item.id); put("original_id", item.originalId); put("uri", item.uri)
                    put("state", item.state); put("seq", item.seq)
                    put("payload", json.encodeToString(OrgItem.serializer(), item))
                }
                db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }

    fun counts(job: String): Map<String, Int> =
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM items WHERE job = ? GROUP BY state", arrayOf(job)).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    fun count(job: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE job = ?", arrayOf(job)).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    /** Elemente care încă au chitanțe în coadă. */
    fun withQueue(job: String, limit: Int = 200): List<OrgItem> =
        readableDatabase.rawQuery("SELECT payload FROM items WHERE job = ? AND payload LIKE '%\"queue\":[\"%' ORDER BY seq LIMIT ?", arrayOf(job, limit.toString())).use { c ->
            val out = ArrayList<OrgItem>()
            while (c.moveToNext()) decodeItem(c.getString(0))?.let { if (it.queue.isNotEmpty()) out += it }
            out
        }

    // ─────────────────────────── Originale ───────────────────────────

    /** UUID-ul stabil al unui fișier (creat o singură dată per cont + identitate). */
    fun originalId(owner: String, uri: String): String {
        val identity = identity(uri)
        readableDatabase.rawQuery("SELECT original_id FROM originals WHERE owner = ? AND identity = ?", arrayOf(owner, identity)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        val id = UUID.randomUUID().toString()
        val v = ContentValues().apply { put("owner", owner); put("identity", identity); put("original_id", id) }
        writableDatabase.insertWithOnConflict("originals", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        readableDatabase.rawQuery("SELECT original_id FROM originals WHERE owner = ? AND identity = ?", arrayOf(owner, identity)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return id
    }

    /** După o mutare, noul uri (SAF) rămâne același original. */
    fun alias(owner: String, originalId: String, uri: String) {
        val v = ContentValues().apply { put("owner", owner); put("identity", identity(uri)); put("original_id", originalId) }
        writableDatabase.insertWithOnConflict("originals", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
