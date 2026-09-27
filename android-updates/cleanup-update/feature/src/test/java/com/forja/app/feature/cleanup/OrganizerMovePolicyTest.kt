package com.forja.app.feature.cleanup
import org.junit.Test
import org.junit.Assert.*
class OrganizerMovePolicyTest {
    @Test fun absentDestinationResumesOnlyUnchangedAuthorizedOriginal(){assertEquals(OrganizerMovePolicy.Decision.RESUME,OrganizerMovePolicy.reconcile(true,true,false,false,true));assertEquals(OrganizerMovePolicy.Decision.REVIEW,OrganizerMovePolicy.reconcile(true,true,false,false,false));assertEquals(OrganizerMovePolicy.Decision.REVIEW,OrganizerMovePolicy.reconcile(true,false,false,false,true))}
    @Test fun committedMoveIsRecordedWithoutNewMutationEvenAfterRevocation(){assertEquals(OrganizerMovePolicy.Decision.RECORD_MOVED,OrganizerMovePolicy.reconcile(false,false,true,true,false))}
    @Test fun verifiedCopyRequiresCurrentAuthorizationBeforeOriginalRemoval(){assertEquals(OrganizerMovePolicy.Decision.FINISH_REMOVAL,OrganizerMovePolicy.reconcile(true,true,true,true,true));assertEquals(OrganizerMovePolicy.Decision.REVIEW,OrganizerMovePolicy.reconcile(true,true,true,true,false))}
    @Test fun conflictingOrModifiedFilesAlwaysPreserveOriginal(){for(auth in listOf(false,true)){assertEquals(OrganizerMovePolicy.Decision.REVIEW,OrganizerMovePolicy.reconcile(true,false,true,true,auth));assertEquals(OrganizerMovePolicy.Decision.REVIEW,OrganizerMovePolicy.reconcile(true,true,true,false,auth));assertEquals(OrganizerMovePolicy.Decision.REVIEW,OrganizerMovePolicy.reconcile(false,false,false,false,auth))}}
    @Test fun collisionSuffixIsDeterministicAndKeepsExtension(){assertEquals("Factura · 12345678.pdf",OrganizerMovePolicy.name("Factura.pdf","12345678-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));assertNotEquals(OrganizerMovePolicy.name("Factura.pdf","12345678-a"),OrganizerMovePolicy.name("Factura.pdf","87654321-b"))}
}
