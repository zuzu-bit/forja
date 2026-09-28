// TEMP STUB (package F) — deleted at merge; real API = package B
package com.forja.app.core.inventory

enum class InvKind { Photos, Documents }
enum class InvStage { Scanning, Grouping, Naming, Ready, Applying, Done, Failed }
data class BinTick(val name: String?, val count: Int)          // name null = încă nebotezat
data class InvProgress(val runId: String, val kind: InvKind, val stage: InvStage, val done: Int, val total: Int,
                       val etaSec: Int?, val recent: List<android.net.Uri>, val bins: List<BinTick>, val error: String? = null)
enum class DeleteReason { Duplicate, Similar, Blurry, Tiny, OldScreenshot, Accidental, AiSuggested, TempFile }
data class InvItem(val id: String, val uri: android.net.Uri, val kind: InvKind, val takenAt: Long, val bytes: Long, val width: Int,
                   val height: Int, val mime: String, val name: String, val reason: DeleteReason? = null)
data class InvFolder(val id: String, val name: String, val theme: String, val itemIds: List<String>, val cover: List<String>,
                     val special: Boolean = false)      // special = „De aruncat”
data class InvPlan(val runId: String, val kind: InvKind, val createdAt: Long, val items: Map<String, InvItem>,
                   val folders: List<InvFolder>, val trash: InvFolder, val provider: String?)
data class InvScope(val all: Boolean = true, val lastN: Int? = null, val bucketId: Long? = null, val tree: android.net.Uri? = null)
sealed interface InvEvent { data class Done(val runId: String, val folders: Int, val trashBytes: Long) : InvEvent; data class Failed(val message: String) : InvEvent }
data class ApplyResult(val moved: Int, val trashed: Int, val failed: Int, val freedBytes: Long)
object Inventory {
    val progress: kotlinx.coroutines.flow.StateFlow<InvProgress?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    val plan: kotlinx.coroutines.flow.StateFlow<InvPlan?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    val events: kotlinx.coroutines.flow.SharedFlow<InvEvent> = kotlinx.coroutines.flow.MutableSharedFlow()
    fun start(context: android.content.Context, kind: InvKind, scope: InvScope): String = ""
    fun cancel(context: android.content.Context) {}
    suspend fun load(context: android.content.Context): InvPlan? = null
    suspend fun estimateSec(context: android.content.Context, kind: InvKind, scope: InvScope): Int = 0
    suspend fun rename(folderId: String, name: String) {}
    suspend fun move(itemIds: List<String>, toFolderId: String) {}
    suspend fun toTrash(itemIds: List<String>) {}
    suspend fun keep(itemIds: List<String>) {}            // scoate din „De aruncat”, înapoi în dosarul de origine sau „Diverse”
    suspend fun newFolder(name: String, itemIds: List<String>): String = ""
    suspend fun merge(fromId: String, intoId: String) {}
    suspend fun dissolve(folderId: String) {}              // elementele merg în „Diverse · <an>”
    fun writeRequest(context: android.content.Context): android.content.IntentSender? = null   // toate mutările (API 30+), null dacă nu e nevoie
    fun trashRequest(context: android.content.Context): android.content.IntentSender? = null   // tot „De aruncat” (API 30+)
    suspend fun apply(context: android.content.Context, onProgress: (Int, Int) -> Unit): ApplyResult = ApplyResult(0, 0, 0, 0L)   // după ce dialogurile au fost acceptate
    suspend fun clear(context: android.content.Context) {}
}
