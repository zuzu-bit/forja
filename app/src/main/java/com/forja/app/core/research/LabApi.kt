package com.forja.app.core.research

import com.forja.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LabApiException(val code: Int, message: String) : IOException(message)
internal class LabApi {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val base = BuildConfig.FORJA_API_URL.trimEnd('/')
    val available: Boolean get() = base.startsWith("https://")

    suspend fun request(uid: String, method: String, path: String, body: JSONObject? = null, preflight: (() -> Unit)? = null): JSONObject {
        return requestAttempt(uid, method, path, body, false, preflight)
    }

    private suspend fun requestAttempt(uid: String, method: String, path: String, body: JSONObject?, forceRefresh: Boolean, preflight: (() -> Unit)?): JSONObject {
        check(available) { "Modulul Lab necesită serverul FORJA prin HTTPS." }
        val user = FirebaseAuth.getInstance().currentUser
        if (user?.uid != uid) throw LabApiException(401, "Contul asociat nu mai este autentificat.")
        val token = user.getIdToken(forceRefresh).await().token ?: throw LabApiException(401, "Autentificare indisponibilă.")
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) throw LabApiException(401, "Contul s-a schimbat.")
        val request = Request.Builder().url("$base/v1/research$path")
            .header("Authorization", "Bearer $token")
            .method(method, if (method == "POST") (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()) else null)
            .build()
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) throw LabApiException(401, "Contul s-a schimbat.")
        preflight?.invoke()
        val response = await(client.newCall(request))
        response.use {
            val raw = it.body?.string().orEmpty()
            val json = try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            if (it.code == 401 && !forceRefresh) return requestAttempt(uid, method, path, body, true, preflight)
            if (!it.isSuccessful) throw LabApiException(it.code, json.optString("error", "Server FORJA: ${it.code}"))
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) throw LabApiException(401, "Contul s-a schimbat.")
            return json
        }
    }

    suspend fun upload(session: LabAssociation, events: List<LabEvent>, preflight: (() -> Unit)? = null): JSONObject {
        val values = JSONArray()
        events.forEach { event -> values.put(JSONObject()
            .put("eventId", event.eventId).put("deviceId", event.deviceId).put("source", event.source)
            .put("type", event.type).put("sourceTimestamp", event.sourceTimestamp)
            .put("receivedTimestamp", event.receivedTimestamp).put("sequenceNumber", event.sequenceNumber)
            .put("payload", JSONObject(event.payload)).put("syncState", "pending")) }
        return request(session.ownerUid, "POST", "/devices/${session.deviceId}/events", JSONObject().put("events", values), preflight)
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
            }
        })
    }
}
