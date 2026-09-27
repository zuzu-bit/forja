package com.forja.app.core.sync

import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import com.google.firebase.auth.FirebaseAuth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * Drumul telefonului către site (contractul /v2/sessions al worker-ului forja-insights), doar modul online:
 * baza = InsightsApi.base, token Bearer al contului pe fiecare cerere. Fiecare chitanță (bytes + sha256)
 * este verificată față de ce s-a trimis.
 */
class SyncTransport(private val accountUid: String, private val authorized: () -> Boolean = { true }) {
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    private fun gate() {
        check(!cancelled && authorized()) { "Sincronizarea a fost oprită." }
        val user = FirebaseAuth.getInstance().currentUser
        require(user?.uid == accountUid) { "Conectează-te în contul folosit pentru această sesiune." }
    }

    /** GET /v2/sessions — verificarea legăturii. */
    suspend fun test(): JsonObject { gate(); return InsightsApi.json("/v2/sessions") }

    /** POST /v2/sessions {session_id, consent{location,app_usage,files,photos,audio}, mode:"automatic"?} */
    suspend fun open(consent: Set<String>, id: String = UUID.randomUUID().toString(), automatic: Boolean = false): String {
        gate()
        val body = buildJsonObject {
            put("session_id", id)
            put("consent", buildJsonObject {
                listOf("location", "app_usage", "files", "photos", "audio").forEach { put(it, it in consent) }
            })
            if (automatic) put("mode", "automatic")
        }
        InsightsApi.json("/v2/sessions", body)
        return id
    }

    private fun verify(result: JsonObject, bytes: ByteArray): JsonObject {
        val hash = sha256(bytes)
        val gotHash = result["sha256"]?.jsonPrimitive?.contentOrNull
        val gotBytes = result["bytes"]?.jsonPrimitive?.intOrNull
        require(gotHash == hash && gotBytes == bytes.size) { "Chitanța serverului nu corespunde cu ce s-a trimis." }
        return result
    }

    /** POST /v2/sessions/{id}/data — metricile JSON (application/json). */
    suspend fun metrics(id: String, data: ByteArray): JsonObject {
        gate()
        return verify(InsightsApi.upload("/v2/sessions/$id/data", data, "application/json", "POST"), data)
    }

    /** POST /v2/sessions/{id}/items?kind=photo|file|audio&sequence=n — octeți bruți cu X-File-Name / X-Media-Type. */
    suspend fun item(id: String, kind: String, sequence: Int, name: String, mediaType: String, bytes: ByteArray): JsonObject {
        gate()
        return verify(
            InsightsApi.upload(
                "/v2/sessions/$id/items?kind=$kind&sequence=$sequence", bytes, "application/octet-stream", "POST",
                mapOf("X-File-Name" to InsightsApi.header(name), "X-Media-Type" to mediaType)
            ), bytes
        )
    }

    /** DELETE /v2/sessions/{id} — 404 înseamnă deja ștearsă. */
    suspend fun delete(id: String) {
        gate()
        try { InsightsApi.json("/v2/sessions/$id", null, "DELETE") }
        catch (e: InsightsFailure) { if (e.code != 404) throw e }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
