package com.forja.app.core.inventory

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.Writer

// ═══════════════ Inventar 4.3 — ce stă pe disc ═══════════════
// filesDir/inventory/<runId>.json            RunFile: starea rulării (stagiu, progres, eroare) + planul editabil (după Ready)
// filesDir/inventory/<runId>.items.jsonl     elementele (un JSON pe rând) — punctul de reluare după Scanare
// filesDir/inventory/<runId>.clusters.jsonl  grupurile — punctul de reluare după Grupare (șters la Ready)
// filesDir/inventory/<runId>.names.json      numele / verdictele primite până acum — reluare în timpul AI (șters la Ready)
// DataStore „forja_inventory”: `latest_run` = rularea curentă. Nimic din toate astea nu e Bitmap sau Uri: doar id-uri,
// căi, metadate și texte, ca 20 000 de elemente să încapă în câțiva MB.

private val Context.inventoryStore by preferencesDataStore(name = "forja_inventory")

/** Un element al inventarului (poză, video sau document), cu tot ce trebuie pentru grupare și pentru aplicare. */
@Serializable
internal data class ItemRec(
    val id: String,                 // „m:<MediaStore _ID>” sau „d:<16 hex din sha256(uri)>”
    val uri: String,
    val kind: InvKind,
    val takenAt: Long,              // DATE_TAKEN, altfel DATE_MODIFIED×1000 (documente: lastModified)
    val bytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val mime: String = "",
    val name: String = "",
    // poze + video (MediaStore)
    val mediaId: Long = 0L,
    val video: Boolean = false,
    val bucket: String = "",
    val relPath: String = "",
    val favorite: Boolean = false,
    val lat: Double? = null,        // doar sub API 29 (coloanele MediaStore); de la 29 încolo, EXIF pe mostre
    val lon: Double? = null,
    val screenshot: Boolean = false,
    val localReason: DeleteReason? = null,   // verdictul detectorilor locali (înainte de AI)
    // documente (SAF)
    val docId: String = "",
    val parent: String = "",
    val path: String = "",
    val flags: Int = 0
)

@Serializable
internal data class ClusterRec(
    val id: String,                 // „c1”, „c2”… (unic, ≤ 64 caractere)
    val kind: String,               // event | month | received | screens
    val itemIds: List<String>,      // toate elementele, inclusiv cele deja la gunoi (pentru „Păstrează”)
    val from: Long,
    val to: Long,
    val count: Int,                 // elemente vii (fără gunoi) — ce vede AI-ul ca mărime
    val videos: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val place: String? = null,
    val source: String? = null,
    val oldScreens: Int = 0
)

/** Numele unui grup: de la AI (`ai = true`) sau cel de rezervă. */
@Serializable
internal data class NameRec(
    val nume: String,
    val tema: String,
    val categorie: String = "",
    val pastrare: String = "poate",
    val motiv: String = "",
    val ai: Boolean = false
)

/** Verdictul /v1/organize pentru un document (gol = trimis, dar fără răspuns — nu se retrimite la reluare). */
@Serializable
internal data class DocVerdictRec(val dosar: String = "", val categorie: String = "", val delete: Boolean = false, val motiv: String = "")

@Serializable
internal data class NamesFile(
    val clusters: Map<String, NameRec> = emptyMap(),
    val docs: Map<String, DocVerdictRec> = emptyMap(),
    val provider: String? = null
)

@Serializable
internal data class FolderRec(
    val id: String,
    val name: String,
    val theme: String,
    val itemIds: List<String> = emptyList(),
    val special: Boolean = false,
    val user: Boolean = false
)

/** Planul editabil. `reasons`/`origin` există doar pentru elementele din „De aruncat”. */
@Serializable
internal data class PlanDoc(
    val folders: List<FolderRec> = emptyList(),
    val trash: FolderRec = FolderRec(InvRules.TRASH_ID, InvRules.TRASH_NAME, InvRules.TRASH_THEME, special = true),
    val reasons: Map<String, DeleteReason> = emptyMap(),
    val origin: Map<String, String> = emptyMap(),
    val redirects: Map<String, String> = emptyMap(),
    val ghosts: List<FolderRec> = emptyList(),
    val seq: Int = 0,
    val provider: String? = null
)

@Serializable
internal data class RunMeta(
    val runId: String,
    val kind: InvKind,
    val createdAt: Long,
    val lastN: Int? = null,
    val bucketId: Long? = null,
    val tree: String? = null,
    val stage: InvStage = InvStage.Scanning,
    val done: Int = 0,
    val total: Int = InvRules.PROGRESS_SCALE,
    val error: String? = null,
    val attempts: Int = 0,
    val scanAttempts: Int = 0,
    val spanScan: Float = 0.7f,
    val spanGroup: Float = 0.05f,
    val estScanSec: Int = 0,
    val estGroupSec: Int = 0,
    val estAiSec: Int = 0,
    val folders: Int = 0,
    val itemCount: Int = 0,
    val trashCount: Int = 0,
    val trashBytes: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
internal data class RunFile(val meta: RunMeta, val plan: PlanDoc? = null)

internal object InventoryStore {
    private val KEY_LATEST = stringPreferencesKey("latest_run")

    // Fără valorile implicite pe disc: un element are ~150 de octeți în loc de ~290 (20 000 de elemente ≈ 3 MB).
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false; coerceInputValues = true; isLenient = true }

    fun dir(ctx: Context): File = File(ctx.filesDir, "inventory").also { it.mkdirs() }
    private fun runFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun itemsFile(ctx: Context, id: String) = File(dir(ctx), "$id.items.jsonl")
    private fun clustersFile(ctx: Context, id: String) = File(dir(ctx), "$id.clusters.jsonl")
    private fun namesFile(ctx: Context, id: String) = File(dir(ctx), "$id.names.json")

    // ───────────── pointerul (DataStore „forja_inventory”) ─────────────

    suspend fun latest(ctx: Context): String? =
        try { ctx.inventoryStore.data.first()[KEY_LATEST]?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    suspend fun setLatest(ctx: Context, runId: String?) {
        try { ctx.inventoryStore.edit { if (runId == null) it.remove(KEY_LATEST) else it[KEY_LATEST] = runId } } catch (_: Exception) { }
    }

    // ───────────── fișierele unei rulări (blocante: se cheamă de pe Dispatchers.IO) ─────────────

    fun readRun(ctx: Context, runId: String): RunFile? = readJson(runFile(ctx, runId), RunFile.serializer())
    fun writeRun(ctx: Context, run: RunFile) = writeJson(runFile(ctx, run.meta.runId), RunFile.serializer(), run)

    fun readItems(ctx: Context, runId: String): List<ItemRec>? = readLines(itemsFile(ctx, runId), ItemRec.serializer())
    fun writeItems(ctx: Context, runId: String, items: List<ItemRec>) = writeLines(itemsFile(ctx, runId), ItemRec.serializer(), items)

    fun readClusters(ctx: Context, runId: String): List<ClusterRec>? = readLines(clustersFile(ctx, runId), ClusterRec.serializer())
    fun writeClusters(ctx: Context, runId: String, clusters: List<ClusterRec>) =
        writeLines(clustersFile(ctx, runId), ClusterRec.serializer(), clusters)

    fun readNames(ctx: Context, runId: String): NamesFile? = readJson(namesFile(ctx, runId), NamesFile.serializer())
    fun writeNames(ctx: Context, runId: String, names: NamesFile) = writeJson(namesFile(ctx, runId), NamesFile.serializer(), names)

    /** După Ready: grupurile și numele nu mai trebuie (planul și elementele rămân). */
    fun dropCheckpoints(ctx: Context, runId: String) {
        clustersFile(ctx, runId).delete()
        namesFile(ctx, runId).delete()
    }

    fun deleteRun(ctx: Context, runId: String) {
        listOf(runFile(ctx, runId), itemsFile(ctx, runId), clustersFile(ctx, runId), namesFile(ctx, runId)).forEach { it.delete() }
        dir(ctx).listFiles()?.forEach { if (it.name.startsWith("$runId.")) it.delete() }
    }

    /** Șterge tot ce nu ține de [keep] (rulări vechi, fișiere temporare rămase). */
    fun deleteAllExcept(ctx: Context, keep: String?) {
        dir(ctx).listFiles()?.forEach { f -> if (keep == null || !f.name.startsWith("$keep.")) f.delete() }
    }

    private fun <T> readJson(f: File, s: KSerializer<T>): T? =
        try { if (f.isFile) json.decodeFromString(s, f.readText()) else null } catch (_: Exception) { null }

    private fun <T> writeJson(f: File, s: KSerializer<T>, value: T) = atomic(f) { it.write(json.encodeToString(s, value)) }

    /** Un JSON pe rând: la 20 000 de elemente nu ținem tot fișierul ca un singur String în memorie. */
    private fun <T> readLines(f: File, s: KSerializer<T>): List<T>? = try {
        if (!f.isFile) null else f.bufferedReader().useLines { lines ->
            val out = ArrayList<T>()
            for (line in lines) if (line.isNotBlank()) out += json.decodeFromString(s, line)
            out
        }
    } catch (_: Exception) { null }

    private fun <T> writeLines(f: File, s: KSerializer<T>, list: List<T>) = atomic(f) { w ->
        for (v in list) { w.write(json.encodeToString(s, v)); w.write("\n") }
    }

    /** Scriere atomică: fișier temporar + redenumire, ca un proces ucis să nu lase un JSON pe jumătate. */
    private fun atomic(f: File, body: (Writer) -> Unit) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use(body)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

}
