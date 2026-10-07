package com.forja.app.core.research

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Only the folder selected through SAF. Providers without change notifications are checked once per minute. */
internal class LabFileObserver(private val context: Context, private val scope: CoroutineScope) : LabObserver {
    private val capture = LabCapture(context)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var timer: Job? = null
    private var registered = false
    private val previous = mutableMapOf<String, String>()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { changes.trySend(Unit) }
    }
    override fun start() {
        val uri = LabArtifacts.tree(context)
        if (uri == null || context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
            capture.event("FILE", "source_unavailable", JSONObject().put("reason", "Choose a folder in Device / Lab Access")); return
        }
        runCatching { context.contentResolver.registerContentObserver(uri, true, observer); registered = true }
        job = scope.launch {
            for (ignored in changes) {
                delay(500)
                if (!capture.allows("FILE")) continue
                try { scan(uri) }
                catch (e: Exception) { capture.event("FILE", "source_unavailable", JSONObject().put("reason", e.javaClass.simpleName)) }
            }
        }
        timer = scope.launch { while (capture.allows("FILE")) { delay(60_000); changes.trySend(Unit) } }
        changes.trySend(Unit)
    }
    private fun scan(tree: Uri) {
        check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "Folder permission revoked" }
        val root = checkNotNull(DocumentFile.fromTreeUri(context, tree))
        var visited = 0
        var truncated = false
        val current = mutableMapOf<String, String>()
        fun walk(directory: DocumentFile, folder: String, depth: Int) {
            if (!capture.allows("FILE")) return
            if (depth > 6) { truncated = true; return }
            for (file in directory.listFiles()) {
                if (!capture.allows("FILE")) return
                if (++visited > 500) { truncated = true; return }
                if (file.isDirectory) { walk(file, "$folder/${file.name.orEmpty().take(256)}", depth + 1); continue }
                if (!file.isFile || !file.canRead()) continue
                val metadata = JSONObject().put("filename", file.name?.take(512) ?: "document")
                    .put("mime", file.type ?: "application/octet-stream").put("size", file.length())
                    .put("modifiedTime", file.lastModified()).put("folder", folder.take(2048))
                    .put("originalOnDevice", true).put("originalRequiresRequest", true)
                val id = LabArtifacts.id(context, "files", file.uri, capture.deviceId)
                current[id] = metadata.optString("filename")
                val old = LabArtifacts.entry(context, id, capture.deviceId)
                if (old?.optString("fingerprint") != LabArtifacts.hash(metadata.toString())) {
                    LabArtifacts.remember(context, "files", file.uri, metadata, capture.deviceId)
                    metadata.put("artifactId", id).put("reference", id)
                    capture.event("FILE", if (old == null) "file_indexed" else "file_updated", metadata,
                        if (old == null) System.currentTimeMillis() else file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
                }
            }
        }
        walk(root, root.name.orEmpty().take(256), 0)
        if (!truncated) previous.filterKeys { it !in current }.forEach { (id, filename) ->
            capture.event("FILE", "file_removed", JSONObject().put("artifactId", id).put("filename", filename)
                .put("timestampMeaning", "Removal observed, exact deletion time unavailable"))
        }
        previous.clear(); previous.putAll(current)
        if (truncated) capture.event("FILE", "observation_limit", JSONObject().put("maxEntries", 500).put("maxDepth", 6).put("complete", false))
    }
    override fun close() {
        capture.close()
        if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false; changes.close(); timer?.cancel(); job?.cancel(); timer = null; job = null
    }
}
