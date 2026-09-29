package com.forja.app.core.inventory

import android.content.Context
import android.net.Uri
import com.forja.app.ForjaApp
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.network.InsightsApi
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Inventarul pe site, pe tot parcursul rulării (mirror, pachetul C): până acum rezumatul pleca doar după o aplicare.
 * Acum `users/{uid}/inventory/{runId}` primește și starea — în analiză (la fiecare schimbare de etapă, cu procentul), gata
 * (dosarele planului, temele, motivele din „De aruncat”, modelul care a dat numele), oprită (cu motivul) —, iar la aplicare
 * motivele eșecurilor și data până la care se recuperează pozele din coș. Cu contractul v4 urcă și coperțile: 4 miniaturi
 * pe dosar, pe serverul site-ului (`_insights/{uid}/inventory/{runId}/`, șterse la revocare și când rularea iese din ultimele 20).
 * O rulare oprită înainte de plan sau un plan aruncat fără aplicare nu rămân pe site.
 */
internal object InvMirror {
    private const val COVER_EDGE = 256
    private const val COVER_MAX = 60_000
    private const val COVER_FOLDERS = 24
    const val TRASH_DAYS = 30L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastStage = ConcurrentHashMap<String, InvStage>()
    /** runId → numele dosarului → cheile coperților urcate. */
    private val covers = ConcurrentHashMap<String, Map<String, List<String>>>()

    fun reasonCode(r: DeleteReason?): String = when (r) {
        DeleteReason.Duplicate -> "duplicate"; DeleteReason.Similar -> "similar"; DeleteReason.Blurry -> "blurry"; DeleteReason.Tiny -> "tiny"
        DeleteReason.OldScreenshot -> "old_screenshot"; DeleteReason.Accidental -> "accidental"; DeleteReason.AiSuggested -> "ai"; DeleteReason.TempFile -> "temp"
        null -> "manual"
    }

    fun stateOf(stage: InvStage): String = stage.name.lowercase()

    /** Cheia coperții: stabilă după numele dosarului (aceeași la „gata” și după aplicare). */
    fun coverKey(folder: String, n: Int): String = "c" + Integer.toHexString(folder.hashCode()).padStart(8, '0') + "-" + n

    fun coversOf(runId: String): Map<String, List<String>> = covers[runId].orEmpty()

    private fun uid(ctx: Context): String? = try { ForjaApp.from(ctx).auth.currentUid } catch (_: Exception) { null }

    /** Analiza a trecut la altă etapă: un document mic (starea, procentul), cel mult o scriere pe etapă. */
    private val starts = ConcurrentHashMap<String, Long>()

    fun stage(ctx: Context, p: InvProgress) {
        if (p.stage !in setOf(InvStage.Scanning, InvStage.Grouping, InvStage.Naming)) return
        if (lastStage.put(p.runId, p.stage) == p.stage) return
        val startedAt = starts.getOrPut(p.runId) { System.currentTimeMillis() }
        io.launch {
            publishRaw(ctx, p.runId, mapOf("id" to p.runId, "kind" to if (p.kind == InvKind.Photos) "photos" else "docs", "startedAt" to startedAt,
                "finishedAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis(), "state" to stateOf(p.stage),
                "progress" to mapOf("done" to p.done, "total" to p.total), "appVersion" to appVersion(), "folders" to emptyList<Any>(),
                "trash" to mapOf("count" to 0, "bytes" to 0L), "moved" to 0, "failed" to 0))
        }
    }

    fun failed(ctx: Context, meta: RunMeta, message: String) {
        lastStage.remove(meta.runId)
        io.launch {
            publishRaw(ctx, meta.runId, mapOf("id" to meta.runId, "kind" to if (meta.kind == InvKind.Photos) "photos" else "docs", "startedAt" to meta.createdAt,
                "finishedAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis(), "state" to "failed", "error" to message.take(InvSummaryDoc.MAX_THEME),
                "appVersion" to appVersion(), "folders" to emptyList<Any>(), "trash" to mapOf("count" to 0, "bytes" to 0L), "moved" to 0, "failed" to 0))
        }
    }

    /** Planul e gata: dosarele cu temele lor și motivele din „De aruncat”; apoi coperțile (v4) și încă o scriere cu ele. */
    fun ready(ctx: Context, base: InvSummaryDoc, folders: List<Triple<String, List<String>, Long>>, uris: Map<String, String>, photos: Boolean) {
        lastStage.remove(base.id)
        io.launch {
            InventorySummary.publish(ctx, base)
            if (!photos) return@launch
            val app = try { ForjaApp.from(ctx) } catch (_: Exception) { return@launch }
            if (!app.prefs.contractAtLeast(4).first()) return@launch
            val engine = CleanupEngine(app, app.prefs)
            val done = HashMap<String, List<String>>()
            for ((name, coverIds, _) in folders.sortedByDescending { it.third }.take(COVER_FOLDERS)) {
                val keys = ArrayList<String>()
                for ((n, id) in coverIds.take(4).withIndex()) {
                    val uri = uris[id] ?: continue
                    val jpeg = try { engine.thumbnailJpeg(Uri.parse(uri), COVER_EDGE, COVER_MAX) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
                    val key = coverKey(name, n)
                    try { InsightsApi.upload("/v2/mirror/cover/${base.id}/$key", jpeg, "application/octet-stream", "PUT"); keys += key }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
                }
                if (keys.isNotEmpty()) done[name] = keys
            }
            if (done.isEmpty()) return@launch
            covers[base.id] = done
            InventorySummary.publish(ctx, base.copy(folders = base.folders.map { f -> f.copy(covers = done[f.name].orEmpty()) }, updatedAt = System.currentTimeMillis()))
        }
    }

    /** O rulare fără nicio aplicare iese de pe site (analiza oprită, planul aruncat). */
    fun discard(ctx: Context, runId: String) {
        lastStage.remove(runId); covers.remove(runId)
        io.launch {
            val uid = uid(ctx) ?: return@launch
            try { FirebaseFirestore.getInstance().collection("users").document(uid).collection("inventory").document(runId).delete() } catch (_: Exception) { }
            dropCovers(runId)
        }
    }

    suspend fun dropCovers(runId: String) {
        try { InsightsApi.json("/v2/mirror/cover/$runId", null, "DELETE") } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun publishRaw(ctx: Context, runId: String, doc: Map<String, Any?>) {
        try {
            val app = ForjaApp.from(ctx)
            if (!app.prefs.contractSigned.first()) return
            val uid = app.auth.currentUid ?: return
            FirebaseFirestore.getInstance().collection("users").document(uid).collection("inventory").document(runId)
                .set(doc, com.google.firebase.firestore.SetOptions.merge())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private fun appVersion(): String = try { com.forja.app.BuildConfig.VERSION_NAME } catch (_: Throwable) { "" }
}
