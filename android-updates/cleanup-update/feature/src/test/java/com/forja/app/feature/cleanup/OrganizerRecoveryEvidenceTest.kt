package com.forja.app.feature.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test
import com.forja.app.feature.cleanup.OrganizerMovePolicy.Presence

class OrganizerRecoveryEvidenceTest {
    @Test fun unavailableProviderCannotProveOriginalRemoval() {
        assertEquals("needs_review", OrganizerMovePolicy.stoppedState(Presence.UNKNOWN,false,Presence.PRESENT,true,true,true))
    }
    @Test fun matchingIndependentCopyCannotProveAnOwnedMove() {
        assertEquals("needs_review", OrganizerMovePolicy.stoppedState(Presence.ABSENT,false,Presence.PRESENT,true,false,true))
    }
    @Test fun hiddenStagingCopyIsNotACompletedDestination() {
        assertEquals("needs_review", OrganizerMovePolicy.stoppedState(Presence.ABSENT,false,Presence.PRESENT,true,true,false))
    }
    @Test fun verifiedFinalCopyDistinguishesRetainedFromRemovedOriginal() {
        assertEquals("copied_pending_removal", OrganizerMovePolicy.stoppedState(Presence.PRESENT,true,Presence.PRESENT,true,true,true))
        assertEquals("moved", OrganizerMovePolicy.stoppedState(Presence.ABSENT,false,Presence.PRESENT,true,true,true))
    }
    @Test fun changedOriginalOrUnverifiedTargetRequiresReview() {
        assertEquals("needs_review", OrganizerMovePolicy.stoppedState(Presence.PRESENT,false,Presence.PRESENT,true,true,true))
        assertEquals("needs_review", OrganizerMovePolicy.stoppedState(Presence.ABSENT,false,Presence.UNKNOWN,false,true,true))
    }
}
