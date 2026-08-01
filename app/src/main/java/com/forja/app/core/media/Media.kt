package com.forja.app.core.media

import coil.intercept.Interceptor
import coil.request.ImageResult
import com.forja.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Media licențiată: când fișierul original (fără watermark) există în stocarea
 * companiei (R2, încărcat de proprietar după licențierea GRATUITĂ din colecția
 * Adobe Stock FREE), aplicația îl folosește automat în locul preview-ului.
 * Numele fișierelor = ID-ul Adobe Stock: 840977260.mp4, 622385753.jpg …
 */
object Media {
    private val client = OkHttpClient()
    val manifest = MutableStateFlow<Set<String>>(emptySet())
    private val idRegex = Regex("_(\\d{6,12})_")

    private val base: String get() = BuildConfig.FORJA_API_URL.trimEnd('/')

    suspend fun refresh() {
        if (BuildConfig.FORJA_API_URL.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/media/_list").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val keys = Json.parseToJsonElement(body).jsonArray
                        .mapNotNull { it.jsonPrimitive.content }
                        .toSet()
                    manifest.value = keys
                }
            } catch (_: Exception) { }
        }
    }

    /** Preview cu watermark → originalul licențiat, dacă există pe server. */
    fun resolve(url: String): String {
        if (BuildConfig.FORJA_API_URL.isBlank()) return url
        if (!url.contains("ftcdn.net")) return url
        val id = idRegex.find(url)?.groupValues?.get(1) ?: return url
        val ext = if (url.endsWith(".mp4")) "mp4" else "jpg"
        val key = "$id.$ext"
        return if (key in manifest.value) "$base/media/$key" else url
    }
}

/** Interceptor Coil: TOATE imaginile trec automat prin înlocuirea cu originalele licențiate. */
object MediaInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        return if (data is String) {
            chain.proceed(chain.request.newBuilder().data(Media.resolve(data)).build())
        } else {
            chain.proceed(chain.request)
        }
    }
}
