package com.forja.app.core.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** This association is created only after the authenticated lab invitation is accepted. */
data class LabAssociation(
    val deviceId: String,
    val ownerUid: String,
    val labSessionId: String,
    val label: String,
    val consentVersion: String,
    val enabledSources: Set<String> = setOf("DEVICE")
)

internal data class LabRevocation(val ownerUid: String, val deviceId: String, val labSessionId: String)

internal class LabSession(context: Context) {
    private val prefs = context.getSharedPreferences("forja_lab_consent", Context.MODE_PRIVATE)

    fun load(): LabAssociation? {
        return try {
        val obj = JSONObject(prefs.getString("association", null) ?: return null)
        val sources = obj.getJSONArray("enabledSources")
        LabAssociation(
            obj.getString("deviceId"), obj.getString("ownerUid"), obj.getString("labSessionId"),
            obj.getString("label"), obj.getString("consentVersion"),
            (0 until sources.length()).map { sources.getString(it) }.toSet()
        )
        } catch (_: Exception) { null }
    }

    fun save(session: LabAssociation) {
        val value = JSONObject().put("deviceId", session.deviceId).put("ownerUid", session.ownerUid)
            .put("labSessionId", session.labSessionId).put("label", session.label)
            .put("consentVersion", session.consentVersion)
            .put("enabledSources", JSONArray(session.enabledSources.sorted()))
        check(prefs.edit().putString("association", value.toString()).commit()) { "Consimțământul nu a putut fi salvat." }
    }

    fun clear() { check(prefs.edit().remove("association").commit()) { "Consimțământul local nu a putut fi revocat pe disc." } }

    fun revocations(): List<LabRevocation> = try {
        val array = JSONArray(prefs.getString("pendingRevocations", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            LabRevocation(it.getString("ownerUid"), it.getString("deviceId"), it.getString("labSessionId"))
        } }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun addRevocation(session: LabAssociation) {
        writeRevocations((revocations() + LabRevocation(session.ownerUid, session.deviceId, session.labSessionId)).distinctBy { it.deviceId })
    }

    @Synchronized fun removeRevocation(deviceId: String) { writeRevocations(revocations().filterNot { it.deviceId == deviceId }) }

    private fun writeRevocations(values: List<LabRevocation>) {
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("ownerUid", it.ownerUid).put("deviceId", it.deviceId).put("labSessionId", it.labSessionId)) }
        check(prefs.edit().putString("pendingRevocations", array.toString()).commit()) { "Revocarea locală nu a putut fi salvată." }
    }
}

/** Pure policy kept separate so acknowledgement and source validation can be tested on the JVM. */
internal object LabJournalPolicy {
    val sources = setOf("APP", "NOTIFICATION", "MEDIA", "LOCATION", "NETWORK", "BLUETOOTH", "CONTACT", "FILE", "ACTIVITY", "SLEEP", "NUTRITION", "DEVICE")
    fun validType(type: String) = type.matches(Regex("[A-Za-z0-9_.-]{1,80}"))
    fun acknowledged(submitted: Collection<String>, accepted: Collection<String>, duplicates: Collection<String>): Set<String> =
        submitted.toSet().intersect((accepted + duplicates).toSet())
}
