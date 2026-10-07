package com.forja.app.core.research

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.forja.app.ForjaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ArtifactContent(val filename: String, val mime: String, val bytes: ByteArray, val requiresMediaLocationPermission: Boolean = false)

/** Local URI capabilities, scoped to an authenticated lab association. Never accepts paths supplied remotely. */
object LabArtifacts {
    private const val MAX_ORIGINAL = 8 * 1024 * 1024
    private fun prefs(context: Context) = context.getSharedPreferences("forja_lab_artifacts", Context.MODE_PRIVATE)
    internal fun scope(context: Context, expectedDeviceId: String? = null): String {
        val controller = ForjaApp.from(context).labResearch
        check(controller.isActive()) { "Lab association is not active" }
        val session = checkNotNull(controller.session.value)
        check(expectedDeviceId == null || session.deviceId == expectedDeviceId) { "Artifact observation belongs to a previous association" }
        return associationScope(session)
    }
    private fun associationScope(session: LabAssociation) = hash("${session.ownerUid}\u0000${session.deviceId}\u0000${session.labSessionId}")
    internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun id(context: Context, source: String, uri: Uri, expectedDeviceId: String? = null) = hash("${scope(context, expectedDeviceId)}\u0000$source\u0000$uri")
    internal fun entry(context: Context, artifactId: String, expectedDeviceId: String? = null): JSONObject? {
        return runCatching {
            val raw = prefs(context).getString("entry:${scope(context, expectedDeviceId)}:$artifactId", null) ?: return@runCatching null
            JSONObject(raw)
        }.getOrNull()
    }
    internal fun remember(context: Context, source: String, uri: Uri, metadata: JSONObject, expectedDeviceId: String? = null): String {
        val capturedScope = scope(context, expectedDeviceId)
        val artifactId = hash("$capturedScope\u0000$source\u0000$uri")
        val record = JSONObject().put("scope", capturedScope).put("source", source).put("uri", uri.toString())
            .put("filename", metadata.optString("filename", "artifact")).put("mime", metadata.optString("mime", "application/octet-stream"))
            .put("fingerprint", hash(metadata.toString()))
        check(prefs(context).edit().putString("entry:$capturedScope:$artifactId", record.toString()).commit()) { "Artifact reference could not be saved" }
        return artifactId
    }
    fun grantTree(context: Context, uri: Uri) {
        check(ForjaApp.from(context).labResearch.isActive())
        check(uri.scheme == "content") { "Select a folder through Android's document picker" }
        if (tree(context) != uri) clearTree(context)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val root = checkNotNull(DocumentFile.fromTreeUri(context, uri)) { "Invalid document tree" }
        check(root.canRead() && root.isDirectory) { "The selected folder cannot be read" }
        check(prefs(context).edit().putString("tree:${scope(context)}", uri.toString()).commit())
    }
    fun clearTree(context: Context) {
        clearTree(context, ForjaApp.from(context).labResearch.session.value)
    }
    fun clearTree(context: Context, association: LabAssociation?) {
        if (association == null) return
        val key = "tree:${associationScope(association)}"
        val old = prefs(context).getString(key, null)
        prefs(context).edit().remove(key).commit()
        if (old != null) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    internal fun tree(context: Context): Uri? = prefs(context).getString("tree:${scope(context)}", null)?.let(Uri::parse)

    suspend fun read(context: Context, artifactId: String, source: String): ArtifactContent = withContext(Dispatchers.IO) {
        require(artifactId.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact reference" }
        val canonical = when (source) { "media" -> "MEDIA"; "files" -> "FILE"; else -> error("Unsupported artifact source") }
        check(context.labAllows(canonical)) { "Resume the authorized lab session and enable this source" }
        val associationScope = scope(context)
        val record = checkNotNull(entry(context, artifactId)) { "Artifact is not indexed in this lab session" }
        check(record.getString("scope") == associationScope && record.getString("source") == source) { "Artifact source or session does not match" }
        val uri = Uri.parse(record.getString("uri"))
        check(uri.scheme == "content") { "Only granted content URIs are supported" }
        if (canonical == "FILE") {
            val tree = checkNotNull(tree(context)) { "Folder grant was removed" }
            check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "Folder grant was revoked" }
            // An entry is created only by walking the selected tree. Verify the grant at use time as well.
            check(android.provider.DocumentsContract.getTreeDocumentId(uri) == android.provider.DocumentsContract.getTreeDocumentId(tree)) { "Artifact is outside the granted tree" }
        } else check(mediaPermission(context, record.optString("mime"))) { "Media permission was revoked" }
        val bytes = ByteArrayOutputStream()
        val readUri = if (canonical == "MEDIA") mediaReadUri(context, uri) else uri
        val requiresMediaLocation = readUri != uri
        context.contentResolver.openInputStream(readUri)?.use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                check(context.labAllows(canonical) && scope(context) == associationScope) { "Lab access was revoked during transfer" }
                if (canonical == "MEDIA") check(mediaPermission(context, record.optString("mime"))) { "Media permission revoked during transfer" }
                if (requiresMediaLocation) check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    "Media location permission revoked during transfer"
                }
                if (canonical == "FILE") {
                    val grantedTree = tree(context)
                    check(grantedTree != null && context.contentResolver.persistedUriPermissions.any { it.uri == grantedTree && it.isReadPermission }) {
                        "Folder permission revoked during transfer"
                    }
                }
                val count = stream.read(buffer)
                if (count < 0) break
                check(bytes.size() + count <= MAX_ORIGINAL) { "Original exceeds the authorized 8 MiB transfer limit" }
                bytes.write(buffer, 0, count)
            }
        } ?: error("Artifact is no longer available")
        check(context.labAllows(canonical) && scope(context) == associationScope) { "Lab access was revoked" }
        ArtifactContent(record.getString("filename"), record.getString("mime"), bytes.toByteArray(), requiresMediaLocation)
    }
    fun verifyTransfer(context: Context, artifactId: String, source: String, content: ArtifactContent) {
        val canonical = when (source) { "media" -> "MEDIA"; "files" -> "FILE"; else -> error("Unsupported artifact source") }
        check(context.labAllows(canonical)) { "Lab source or session was revoked" }
        val record = checkNotNull(entry(context, artifactId)) { "Artifact reference unavailable" }
        check(record.getString("scope") == scope(context) && record.getString("source") == source) { "Artifact session changed" }
        val uri = Uri.parse(record.getString("uri"))
        if (canonical == "MEDIA") {
            check(mediaPermission(context, record.optString("mime"))) { "Media permission revoked" }
            if (content.requiresMediaLocationPermission) check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                "Media location permission revoked"
            }
        } else {
            val grantedTree = checkNotNull(tree(context)) { "Folder grant removed" }
            check(context.contentResolver.persistedUriPermissions.any { it.uri == grantedTree && it.isReadPermission }) { "Folder grant revoked" }
            check(android.provider.DocumentsContract.getTreeDocumentId(uri) == android.provider.DocumentsContract.getTreeDocumentId(grantedTree)) { "Folder grant changed" }
        }
        // Reopen to verify an Android 14 selected-photo URI has not been removed from the grant.
        context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: error("Artifact is no longer accessible")
    }
    internal fun mediaPermission(context: Context, mime: String): Boolean {
        fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) {
            has(if (mime.startsWith("video/")) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else has(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    internal fun mediaReadUri(context: Context, uri: Uri): Uri = if (Build.VERSION.SDK_INT >= 29 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
        android.provider.MediaStore.setRequireOriginal(uri) else uri
}
