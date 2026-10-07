package com.forja.app.core.research

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** MediaStore callbacks; no initial gallery import, no originals sent automatically. */
internal class LabMediaObserver(private val context: Context, private val scope: CoroutineScope) : LabObserver {
    private val capture = LabCapture(context)
    private data class Collection(val uri: Uri, var generation: Long, var lastId: Long = Long.MAX_VALUE)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val collections = mutableListOf<Collection>()
    private var job: Job? = null
    private var registered = false
    private var startedAt = System.currentTimeMillis()
    private var lastWallClock = startedAt
    private val resolver = context.contentResolver
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { changes.trySend(Unit) }
    }
    override fun start() {
        if (!LabArtifacts.mediaPermission(context, "image/jpeg") && !LabArtifacts.mediaPermission(context, "video/mp4")) {
            capture.event("MEDIA", "source_unavailable", JSONObject().put("reason", "Grant media or selected-photo access")); return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.getExternalVolumeNames(context).forEach { volume ->
                val generation = MediaStore.getGeneration(context, volume)
                collections += Collection(MediaStore.Images.Media.getContentUri(volume), generation)
                collections += Collection(MediaStore.Video.Media.getContentUri(volume), generation)
            }
        } else {
            collections += Collection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0)
            collections += Collection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 0)
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        registered = true
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        job = scope.launch {
            for (ignored in changes) {
                delay(300)
                if (!capture.allows("MEDIA")) continue
                try { collections.forEach { queryChanges(it) } }
                catch (e: Exception) { capture.event("MEDIA", "source_unavailable", JSONObject().put("reason", e.javaClass.simpleName)) }
            }
        }
        changes.trySend(Unit)
        capture.event("MEDIA", "observation_started", JSONObject().put("scope", "New or changed accessible MediaStore rows after consent")
            .put("selectedPhotosSupported", Build.VERSION.SDK_INT >= 34).put("originalsAutoUploaded", false))
    }

    private fun queryChanges(collection: Collection) {
        if (!capture.allows("MEDIA")) return
        val mime = if (collection.uri.toString().contains("/video/")) "video/mp4" else "image/jpeg"
        if (!LabArtifacts.mediaPermission(context, mime)) return
        val columns = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= 29) columns += listOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_TAKEN)
        if (Build.VERSION.SDK_INT >= 30) columns += MediaStore.MediaColumns.GENERATION_MODIFIED
        val now = System.currentTimeMillis()
        if (now < lastWallClock) startedAt = now
        lastWallClock = now
        var more = true
        var offset = 0
        var previousFirstId: Long? = null
        while (more && capture.allows("MEDIA")) {
            val args = Bundle()
            if (Build.VERSION.SDK_INT >= 30) {
                args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "(generation_modified > ? OR (generation_modified = ? AND _id > ?)) AND is_pending = 0")
                args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(collection.generation.toString(), collection.generation.toString(), collection.lastId.toString()))
                args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "generation_modified ASC, _id ASC")
                args.putInt(ContentResolver.QUERY_ARG_LIMIT, 200)
            } else {
                args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "date_added >= ? OR date_modified >= ?")
                args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf((startedAt / 1000).toString(), (startedAt / 1000).toString()))
                args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified ASC, _id ASC")
                args.putInt(ContentResolver.QUERY_ARG_LIMIT, 200)
                args.putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            var count = 0
            resolver.query(collection.uri, columns.toTypedArray(), args, null)?.use { cursor ->
                while (cursor.moveToNext() && capture.allows("MEDIA")) {
                    fun number(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
                    fun text(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
                    val id = number(MediaStore.MediaColumns._ID)
                    if (Build.VERSION.SDK_INT < 30 && count == 0) {
                        if (offset > 0 && previousFirstId == id) {
                            capture.event("MEDIA", "observation_limit", JSONObject().put("reason", "Legacy provider does not support query pagination"))
                            more = false; return
                        }
                        previousFirstId = id
                    }
                    val uri = ContentUris.withAppendedId(collection.uri, id)
                    val added = number(MediaStore.MediaColumns.DATE_ADDED) * 1000
                    val modified = number(MediaStore.MediaColumns.DATE_MODIFIED) * 1000
                    val taken = if (Build.VERSION.SDK_INT >= 29) number(MediaStore.MediaColumns.DATE_TAKEN) else 0
                    val metadata = JSONObject().put("mediaStoreId", id).put("filename", text(MediaStore.MediaColumns.DISPLAY_NAME)?.take(512) ?: "media_$id")
                        .put("mime", text(MediaStore.MediaColumns.MIME_TYPE) ?: mime).put("size", number(MediaStore.MediaColumns.SIZE))
                        .put("width", number(MediaStore.MediaColumns.WIDTH)).put("height", number(MediaStore.MediaColumns.HEIGHT))
                        .put("folder", if (Build.VERSION.SDK_INT >= 29) text(MediaStore.MediaColumns.RELATIVE_PATH)?.take(1024) ?: JSONObject.NULL else JSONObject.NULL)
                        .put("creationTime", if (taken > 0) taken else added).put("addedTime", added).put("modifiedTime", modified)
                        .put("originalOnDevice", true).put("originalRequiresRequest", true)
                    val artifactId = LabArtifacts.id(context, "media", uri, capture.deviceId)
                    val old = LabArtifacts.entry(context, artifactId, capture.deviceId)
                    if (old?.optString("fingerprint") != LabArtifacts.hash(metadata.toString())) {
                        LabArtifacts.remember(context, "media", uri, metadata, capture.deviceId)
                        metadata.put("artifactId", artifactId)
                        if (metadata.optString("mime").startsWith("image/")) metadata.put("exif", exif(uri))
                        thumbnail(uri, id, mime)?.let { metadata.put("thumbnail", it) }
                        if (metadata.toString().toByteArray(Charsets.UTF_8).size > 30_000) {
                            metadata.remove("thumbnail"); metadata.put("thumbnailOmitted", "Event size bound")
                        }
                        capture.event("MEDIA", if (old == null) "media_created" else "media_updated", metadata,
                            (if (old == null) added else modified).takeIf { it > 0 } ?: System.currentTimeMillis())
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        collection.generation = number(MediaStore.MediaColumns.GENERATION_MODIFIED)
                        collection.lastId = id
                    }
                    count++
                    if (count >= 200) break
                }
            }
            offset += count
            more = count >= 200
        }
    }

    private fun exif(uri: Uri): JSONObject = runCatching {
        resolver.openInputStream(LabArtifacts.mediaReadUri(context, uri))?.use { stream ->
            val reader = ExifInterface(stream)
            val result = JSONObject()
            listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME)
                .forEach { key -> reader.getAttribute(key)?.let { result.put(key, it.take(128)) } }
            val position = FloatArray(2)
            if (reader.getLatLong(position)) result.put("gps", JSONObject().put("latitude", position[0].toDouble()).put("longitude", position[1].toDouble()))
            result.put("scope", "Metadata Android exposes; location may be redacted")
        } ?: JSONObject()
    }.getOrElse { JSONObject().put("available", false) }

    private fun thumbnail(uri: Uri, id: Long, mime: String): JSONObject? = runCatching {
        val bitmap = if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(192, 192), null)
        else if (mime.startsWith("video/")) @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MICRO_KIND, null)
        else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MICRO_KIND, null)
        bitmap ?: return@runCatching null
        val bytes = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, bytes)
        bitmap.recycle()
        if (bytes.size() > 15_000) return@runCatching null
        JSONObject().put("mime", "image/jpeg").put("bytesBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
    }.getOrNull()
    override fun close() {
        capture.close()
        if (registered) runCatching { resolver.unregisterContentObserver(observer) }
        registered = false; changes.close(); job?.cancel(); job = null
    }
}
