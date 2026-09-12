package com.forja.app.feature.research

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ResearchConnection(val origin: String, val token: String, val accountUid: String? = null) {
    companion object {
        fun online(): ResearchConnection {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                ?: error("Conectează-te în FORJA înainte de trimitere.")
            return ResearchConnection(com.forja.app.BuildConfig.INSIGHTS_URL.trimEnd('/'), "", user.uid)
        }
        fun parse(origin: String, token: String): ResearchConnection {
            val u = origin.trim().toHttpUrl()
            require(u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null && u.encodedPath == "/") { "Enter a server address without a path or login details." }
            require(u.isHttps || (u.scheme == "http" && u.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))) { "A remote server needs HTTPS." }
            require(token.trim().matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "Enter the pairing token supplied by your test server." }
            return ResearchConnection(u.toString().removeSuffix("/"), token.trim())
        }
    }
}

class ResearchTransport(private val connection: ResearchConnection, private val authorized: () -> Boolean = { true }) {
    @Volatile private var cancelled = false
    private val callLock = Any()
    private val activeCalls = mutableSetOf<okhttp3.Call>()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build()
    fun cancel() { synchronized(callLock) { cancelled = true; activeCalls.forEach { it.cancel() } } }
    private fun request(path: String, bytes: ByteArray? = null, type: String = "application/json", extra: Map<String, String> = emptyMap(), delete: Boolean = false): JSONObject {
        check(!cancelled && authorized()) { "Colectarea a fost oprită." }
        val token = if (connection.accountUid != null) {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            require(user?.uid == connection.accountUid) { "Conectează-te în contul folosit pentru această sesiune." }
            com.google.android.gms.tasks.Tasks.await(user!!.getIdToken(false), 10, TimeUnit.SECONDS).token
                ?: error("Sesiunea de cont a expirat. Conectează-te din nou.")
        } else connection.token
        check(!cancelled && authorized()) { "Colectarea a fost oprită." }
        val builder = Request.Builder().url(connection.origin + path).header("Authorization", "Bearer $token")
        extra.forEach { (k, v) -> builder.header(k, v) }
        if (delete) builder.delete() else if (bytes != null) builder.post(bytes.toRequestBody(type.toMediaType()))
        val call = client.newCall(builder.build())
        synchronized(callLock) {
            check(!cancelled && authorized()) { "Colectarea a fost oprită." }
            activeCalls += call
        }
        try { return call.execute().use { response ->
            if (delete && response.code == 404) return@use JSONObject().put("deleted", true)
            val result = JSONObject(response.body?.string() ?: error("The server returned no receipt."))
            require(response.isSuccessful) { result.optString("error", "Server returned HTTP ${response.code}.") }
            result
        } } finally { synchronized(callLock) { activeCalls -= call } }
    }
    fun test() { request("/v2/sessions") }
    fun open(consent: Set<String>, id: String = UUID.randomUUID().toString(), automatic: Boolean = false): String {
        val flags = JSONObject().apply { listOf("location", "app_usage", "files", "photos", "audio").forEach { put(it, it in consent) } }
        request("/v2/sessions", JSONObject().put("session_id", id).put("consent", flags).apply { if (automatic) put("mode", "automatic") }.toString().toByteArray())
        return id
    }
    private fun verify(result: JSONObject, bytes: ByteArray): JSONObject {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(result.getString("sha256") == hash && result.getInt("bytes") == bytes.size) { "The server receipt does not match what was sent." }
        return result
    }
    fun metrics(id: String, data: ByteArray) = verify(request("/v2/sessions/$id/data", data), data)
    fun item(id: String, kind: String, sequence: Int, name: String, mediaType: String, bytes: ByteArray): JSONObject = verify(
        request("/v2/sessions/$id/items?kind=$kind&sequence=$sequence", bytes, "application/octet-stream",
            mapOf("X-File-Name" to URLEncoder.encode(name, "UTF-8").replace("+", "%20"), "X-Media-Type" to mediaType)), bytes)
    fun delete(id: String) { request("/v2/sessions/$id", delete = true) }
}
