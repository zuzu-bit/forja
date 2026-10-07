package com.forja.app.core.research

import org.junit.Assert.*
import org.junit.Test

class LabJournalPolicyTest {
    @Test fun acknowledgementOnlyMarksIdsActuallySubmitted() {
        assertEquals(setOf("event-1", "event-2"), LabJournalPolicy.acknowledged(
            listOf("event-1", "event-2", "event-3"), listOf("event-1", "another-device-event"), listOf("event-2", "old-event")))
    }
    @Test fun missingAcknowledgementStaysPending() {
        assertTrue(LabJournalPolicy.acknowledged(listOf("pending"), emptyList(), listOf("unrelated")).isEmpty())
    }
    @Test fun predefinedSourcesExcludeShellAndTypeDoesNotAllowCommands() {
        assertFalse("SHELL" in LabJournalPolicy.sources)
        assertFalse(LabJournalPolicy.validType("sh -c rm -rf /"))
        assertFalse(LabJournalPolicy.validType(""))
        assertTrue(LabJournalPolicy.validType("sleep_audio_event"))
    }
}
