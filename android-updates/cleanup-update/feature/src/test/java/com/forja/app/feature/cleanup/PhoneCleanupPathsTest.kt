package com.forja.app.feature.cleanup
import org.junit.Assert.*
import org.junit.Test
class PhoneCleanupPathsTest {
    @Test fun photosPreviouslyInForjaFoldersStillGetRealMoveProposals(){val f=CleanFile("content://photo/1","test.jpg","image/jpeg",20,100,path="Pictures/FORJA/Educație/",gallery=true);val p=PhoneCleanupPaths.proposals(listOf(f),mapOf(f.uri to ContentFinding("Educație/Grupa mică","Conținut educațional")));assertEquals(1,p.size);assertEquals("Pictures/Educație · Grupa mică/",p.single().destination)}
    @Test fun confirmedCurrentLocationDoesNotProduceAnotherMove(){val f=CleanFile("content://photo/1","test.jpg","image/jpeg",20,100,path="Pictures/Educație · Grupa mică/",gallery=true);assertTrue(PhoneCleanupPaths.proposals(listOf(f),mapOf(f.uri to ContentFinding("Educație/Grupa mică","Conținut educațional"))).isEmpty())}
    @Test fun categoriesBecomeDistinctRealGalleryAlbums(){assertEquals("Pictures/Educație · Grupa mică/",PhoneCleanupPaths.destination("Pictures/FORJA/Educație/Grupa mică/",true));assertEquals("Pictures/Călătorii · Paris/",PhoneCleanupPaths.destination("Pictures/FORJA/Călătorii/Paris/",true))}
    @Test fun documentsAreOrganizedInsideChosenPhoneFolder(){assertEquals("Educație/Grupa mică",PhoneCleanupPaths.destination("FORJA/Educație/Grupa mică",false))}
    @Test fun alreadyOrganizedPathsAreNotProposedAgain(){assertTrue(PhoneCleanupPaths.alreadyThere("Pictures/Educație/","Pictures/Educație"));assertFalse(PhoneCleanupPaths.alreadyThere("Pictures/FORJA/Educație/","Pictures/Educație/"))}
    @Test fun traversalAndInvalidDirectoryNamesAreRejected(){for(path in listOf("FORJA/../private","FORJA/ok/../../bad","FORJA/a\\b","FORJA//","FORJA/a\u0000b")){try{PhoneCleanupPaths.destination(path,false);fail(path)}catch(_:IllegalArgumentException){}}}
}
