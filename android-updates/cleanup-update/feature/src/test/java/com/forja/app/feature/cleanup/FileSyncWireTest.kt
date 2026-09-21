package com.forja.app.feature.cleanup

import org.junit.Test
import org.junit.Assert.*
import okio.Buffer

class FileSyncWireTest {
    @Test fun activationIsJsonOnTheWireRatherThanBinary() {
        val bytes="{\"enabled\":true,\"photos\":true,\"files\":false}".toByteArray()
        val r=FileSyncWire.build("https://example.invalid/v2/files/settings/device","fixture-token","POST",bytes,mapOf("Content-Type" to "application/json"))
        assertEquals("application/json",r.body!!.contentType().toString())
        assertEquals("Bearer fixture-token",r.header("Authorization"))
        val buffer=Buffer();r.body!!.writeTo(buffer);assertArrayEquals(bytes,buffer.readByteArray())
    }
    @Test fun fileUploadPreservesAllBytesAndCarriesOriginalMimeAsMetadata() {
        val bytes=ByteArray(4096){(it%256).toByte()}
        val r=FileSyncWire.build("https://example.invalid/v2/files/file","fixture-token","PUT",bytes,mapOf("X-Media-Type" to "application/pdf","X-File-Name" to "Plan%20rom%C3%A2nesc.pdf"))
        assertEquals("application/octet-stream",r.body!!.contentType().toString())
        assertEquals("application/pdf",r.header("X-Media-Type"));assertEquals(bytes.size.toLong(),r.body!!.contentLength())
        val buffer=Buffer();r.body!!.writeTo(buffer);assertArrayEquals(bytes,buffer.readByteArray())
    }
    @Test fun pollingUsesAuthenticatedGetWithoutUploadBody(){
        val r=FileSyncWire.build("https://example.invalid/v2/files/settings/device","fixture-token","GET",null,emptyMap())
        assertNull(r.body);assertEquals("GET",r.method);assertEquals("Bearer fixture-token",r.header("Authorization"))
    }
}
