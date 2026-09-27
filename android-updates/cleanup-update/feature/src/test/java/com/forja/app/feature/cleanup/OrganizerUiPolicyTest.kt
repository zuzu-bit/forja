package com.forja.app.feature.cleanup

import org.junit.Assert.*
import org.junit.Test

class OrganizerUiPolicyTest {
    @Test fun customNumberDoesNotAccidentallySelectEverything(){
        for(value in listOf(""," ","0","-1","1.5","1e3","1001","2147483648","12x"))assertNull(value,OrganizerUiPolicy.customCount(value))
        assertEquals(1,OrganizerUiPolicy.customCount("1"));assertEquals(1000,OrganizerUiPolicy.customCount(" 1000 "))
    }
    @Test fun incompleteActiveJobsCannotBeReplacedByNextBatch(){
        for(phase in listOf("queued","inventory","analyzing","uploading","applying","ready","needs_permission","needs_access","failed_retryable","paused"))assertFalse(phase,OrganizerUiPolicy.canNext(phase))
        assertTrue(OrganizerUiPolicy.canNext("complete"));assertTrue(OrganizerUiPolicy.canNext("needs_review"));assertTrue(OrganizerUiPolicy.canNext("cancelled"))
    }
    @Test fun permissionAndRetryRemainExplicitActions(){
        assertTrue(OrganizerUiPolicy.canContinue("needs_permission"));assertTrue(OrganizerUiPolicy.canContinue("failed_retryable"))
        assertTrue(OrganizerUiPolicy.canContinue("paused"))
        assertTrue(OrganizerUiPolicy.canContinue("needs_access"))
        assertFalse(OrganizerUiPolicy.canContinue("applying"));assertFalse(OrganizerUiPolicy.canContinue("complete"));assertFalse(OrganizerUiPolicy.canContinue("cancelled"))
        assertFalse(OrganizerUiPolicy.canContinue("needs_review"))
    }
    @Test fun copyIsNeverLabelledAsCompletedMove(){
        assertEquals("Copiat · original păstrat",OrganizerUiPolicy.itemLabel("copied_pending_removal"))
        assertNotEquals(OrganizerUiPolicy.itemLabel("moved"),OrganizerUiPolicy.itemLabel("copied_pending_removal"))
    }
}
