package com.forja.app.core.research

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Optional contact index; explicit source consent and READ_CONTACTS, content-provider changes thereafter. */
internal class LabContactsObserver(private val context: Context, private val scope: CoroutineScope) : LabObserver {
    private val capture = LabCapture(context)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var registered = false
    private val previous = mutableMapOf<String, String>()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { changes.trySend(Unit) }
    }
    private fun permission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    override fun start() {
        if (!permission()) {
            capture.event("CONTACT", "source_unavailable", JSONObject().put("reason", "Contacts permission required")); return
        }
        context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        registered = true
        job = scope.launch {
            for (ignored in changes) {
                delay(700)
                if (!capture.allows("CONTACT") || !permission()) continue
                try { snapshot() }
                catch (e: Exception) { capture.event("CONTACT", "source_unavailable", JSONObject().put("reason", e.javaClass.simpleName)) }
            }
        }
        changes.trySend(Unit)
    }
    private fun snapshot() {
        val contacts = linkedMapOf<String, JSONObject>()
        var truncated = false
        context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
            null, null, "_id ASC")?.use { cursor ->
            while (cursor.moveToNext() && capture.allows("CONTACT")) {
                if (contacts.size >= 500) { truncated = true; break }
                val id = cursor.getString(0)
                contacts[id] = JSONObject().put("contactId", id).put("name", cursor.getString(1)?.take(1024) ?: JSONObject.NULL)
                    .put("modifiedTime", cursor.getLong(2)).put("phones", JSONArray()).put("emails", JSONArray())
            }
        }
        if (contacts.isNotEmpty() && capture.allows("CONTACT")) {
            val ids = contacts.keys.toList()
            val placeholders = ids.joinToString(",") { "?" }
            val selection = "${ContactsContract.Data.CONTACT_ID} IN ($placeholders) AND ${ContactsContract.Data.MIMETYPE} IN (?,?)"
            val args = (ids + ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE + ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE).toTypedArray()
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1), selection, args, null)?.use { cursor ->
                while (cursor.moveToNext() && capture.allows("CONTACT")) {
                    val record = contacts[cursor.getString(0)] ?: continue
                    val kind = if (cursor.getString(1) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) "phones" else "emails"
                    if (record.getJSONArray(kind).length() < 5) cursor.getString(2)?.let { record.getJSONArray(kind).put(it.take(256)) }
                    else record.put("metadataTruncated", true)
                }
            }
        }
        val current = mutableMapOf<String, String>()
        contacts.forEach { (id, metadata) ->
            val fingerprint = LabArtifacts.hash(metadata.toString())
            current[id] = fingerprint
            if (previous[id] != fingerprint) capture.event("CONTACT", if (id in previous) "contact_updated" else "contact_indexed", metadata,
                if (id in previous) metadata.optLong("modifiedTime").takeIf { it > 0 } ?: System.currentTimeMillis() else System.currentTimeMillis())
        }
        if (!truncated) previous.keys.filter { it !in current }.forEach { id ->
            capture.event("CONTACT", "contact_removed", JSONObject().put("contactId", id).put("timestampMeaning", "Removal observed, exact deletion time unavailable"))
        }
        previous.clear(); previous.putAll(current)
        if (truncated) capture.event("CONTACT", "observation_limit", JSONObject().put("indexedContactLimit", 500).put("complete", false))
    }
    override fun close() {
        capture.close()
        if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false; changes.close(); job?.cancel(); job = null
    }
}
