package com.forja.app.feature.cleanup

import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal object FileSyncWire {
    fun build(url:String,token:String,method:String,bytes:ByteArray?,headers:Map<String,String>):Request {
        val contentType=headers.entries.firstOrNull{it.key.equals("Content-Type",true)}?.value?:"application/octet-stream"
        val builder=Request.Builder().url(url).header("Authorization","Bearer $token")
        headers.forEach{(key,value)->builder.header(key,value)}
        // OkHttp's bridge takes Content-Type from the body, not from a manual header.
        return builder.method(method,bytes?.toRequestBody(contentType.toMediaType())).build()
    }
}
