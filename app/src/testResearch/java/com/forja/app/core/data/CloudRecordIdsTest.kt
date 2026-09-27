package com.forja.app.core.data

import org.junit.Assert.*
import org.junit.Test

class CloudRecordIdsTest {
    @Test fun copyDoesNotOverwriteOriginalOrAnotherInstallation() {
        val first = CloudRecordIds("0123456789abcdef")
        val second = CloudRecordIds("fedcba9876543210")
        val original = CloudRecordIds(null)
        for (kind in listOf("m", "s", "a")) {
            assertEquals("${kind}1", original.record(kind, 1))
            assertNotEquals(original.record(kind, 1), first.record(kind, 1))
            assertNotEquals(first.record(kind, 1), second.record(kind, 1))
        }
    }

    @Test fun sameInstallationKeepsIdsStableAcrossRestart() {
        assertEquals(CloudRecordIds("0123456789abcdef").record("m", 7),
            CloudRecordIds("0123456789abcdef").record("m", 7))
    }

    @Test fun recordingIdsFitExistingServerLimitsWithoutTruncation() {
        val id = CloudRecordIds("0123456789abcdef").record("s", Long.MAX_VALUE)
        assertTrue(id.length <= 40)
        assertTrue(id.matches(Regex("[0-9a-zA-Z_-]+")))
    }
}
