package com.forja.app.core.music

import android.content.Context
import android.os.Build
import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jurnalul pornirii muzicii (music-start.md §7, DESIGN-4.4 §3.5): fiecare treaptă încercată, cu playerul, rezultatul
 * și milisecundele. Fără titluri, fără artiști, fără ID-uri media.
 *
 * - În memorie: ultimele 200; pe telefon: ultimele 100 (DataStore „forja_music”, cheia „diag”).
 * - Către serverul FORJA (POST /v1/diag/music): doar cu cont și cu contractul semnat; în așteptare cel mult 150,
 *   trimise câte 50 după fiecare încercare. Serverul le arată în jurnalul de admin (`music [n]`).
 */
internal object MusicLog {
    private const val RING = 200
    private const val KEEP = 100
    private const val PENDING_MAX = 150
    private const val BATCH = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableStateFlow<List<AttemptEvent>>(emptyList())
    /** Cele mai noi la final. */
    val events: StateFlow<List<AttemptEvent>> = _events.asStateFlow()

    private val pending = ArrayList<AttemptEvent>()
    @Volatile private var loaded = false

    enum class Upload { SENT, NOTHING, NO_CONTRACT, NO_ACCOUNT, FAILED }

    fun add(context: Context, e: AttemptEvent) {
        val app = context.applicationContext
        _events.value = (_events.value + e).takeLast(RING)
        scope.launch {
            lock.withLock {
                load(app)
                pending += e
                while (pending.size > PENDING_MAX) pending.removeAt(0)
                persist(app)
            }
        }
    }

    /** După o încercare: trimite ce așteaptă (dacă are voie). */
    fun flush(context: Context) {
        val app = context.applicationContext
        scope.launch { upload(app) }
    }

    /** Încarcă jurnalul salvat (ecranul Probă îl arată și după o repornire). */
    suspend fun ensureLoaded(context: Context) {
        lock.withLock { load(context.applicationContext) }
    }

    suspend fun upload(context: Context): Upload {
        val app = context.applicationContext as? ForjaApp ?: return Upload.FAILED
        return lock.withLock {
            load(app)
            if (pending.isEmpty()) return@withLock Upload.NOTHING
            val uid = try { app.auth.currentUid } catch (_: Exception) { null } ?: return@withLock Upload.NO_ACCOUNT
            if (uid.isBlank()) return@withLock Upload.NO_ACCOUNT
            val signed = try { app.prefs.contractSigned.first() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (!signed) return@withLock Upload.NO_CONTRACT
            while (pending.isNotEmpty()) {
                val batch = pending.take(BATCH)
                val ok = try { app.forjaApi.musicDiag(body(batch)) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                if (!ok) {
                    persist(app)
                    return@withLock Upload.FAILED
                }
                repeat(batch.size) { pending.removeAt(0) }
            }
            persist(app)
            Upload.SENT
        }
    }

    /** Corpul cererii: { device, app, events:[{ at, want, rung, pkg, ver, kind, result, ms, err }] }. */
    fun body(events: List<AttemptEvent>): String = buildJsonObject {
        put("device", device())
        put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        put("events", JsonArray(events.map { encode(it) }))
    }.toString()

    private fun encode(e: AttemptEvent): JsonObject = buildJsonObject {
        put("at", e.at)
        put("want", e.want)
        put("rung", e.rung)
        put("pkg", e.pkg?.let { JsonPrimitive(it.take(100)) } ?: JsonNull)
        put("ver", e.ver?.let { JsonPrimitive(it.take(40)) } ?: JsonNull)
        put("kind", e.kind?.let { JsonPrimitive(it.wire) } ?: JsonNull)
        put("result", e.result.wire)
        put("ms", e.ms)
        put("err", e.err?.let { JsonPrimitive(it.take(120)) } ?: JsonNull)
    }

    private fun decode(line: String): AttemptEvent? = try {
        val o = json.parseToJsonElement(line).jsonObject
        fun str(k: String) = o[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
        AttemptEvent(
            at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
            want = str("want") ?: "",
            rung = str("rung") ?: "",
            pkg = str("pkg"),
            ver = str("ver"),
            kind = MediaKind.entries.firstOrNull { it.wire == str("kind") },
            result = DiagResult.entries.firstOrNull { it.wire == str("result") } ?: DiagResult.ERROR,
            ms = o["ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            err = str("err")
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun load(app: Context) {
        if (loaded) return
        loaded = true
        val (recent, waiting) = try { MusicStats.diag(app) } catch (e: CancellationException) { throw e } catch (_: Exception) { null to null }
        val saved = recent?.lineSequence()?.mapNotNull { decode(it) }?.toList().orEmpty()
        val mem = _events.value
        _events.value = (saved + mem).distinct().sortedBy { it.at }.takeLast(RING)
        val old = waiting?.lineSequence()?.mapNotNull { decode(it) }?.toList().orEmpty()
        pending.addAll(0, old)
        while (pending.size > PENDING_MAX) pending.removeAt(0)
    }

    private suspend fun persist(app: Context) {
        try {
            MusicStats.setDiag(
                app,
                _events.value.takeLast(KEEP).joinToString("\n") { encode(it).toString() },
                pending.joinToString("\n") { encode(it).toString() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    /** Telefonul, pentru jurnal: model, Android, One UI (dacă e Samsung). */
    fun device(): String {
        val oneUi = try {
            Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null).takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
        return listOfNotNull(
            "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60),
            "sdk ${Build.VERSION.SDK_INT}",
            oneUi?.let { "oneui $it" }
        ).joinToString(" · ")
    }

    /** Jurnalul ca text (Copiază / Trimite din ecranul Probă): o linie per treaptă. */
    fun text(events: List<AttemptEvent> = _events.value): String {
        val f = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        val head = "FORJA ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${device()}"
        return (listOf(head) + events.map { e ->
            listOfNotNull(
                f.format(Date(e.at)), e.want, e.rung, e.pkg ?: "-", e.ver, e.kind?.wire, e.result.wire, "${e.ms} ms", e.err
            ).joinToString(" · ")
        }).joinToString("\n")
    }

    suspend fun clear(context: Context) {
        lock.withLock {
            _events.value = emptyList()
            pending.clear()
            loaded = true
            withContext(Dispatchers.IO) { try { MusicStats.setDiag(context.applicationContext, null, null) } catch (_: Exception) { } }
        }
    }
}
