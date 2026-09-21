package com.forja.app.feature.cleanup

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CleanupReportCodecTest {
    @Test fun archivedReportsPreserveOriginalUrisPathsTimesAndDuplicateKeeper(){
        val keeper=CleanFile("content://media/1","Original.jpg","image/jpeg",2000,1000,path="DCIM/Camera/",taken=500,width=100,height=80,favorite=true,gallery=true)
        val copy=keeper.copy(uri="content://media/2",name="Copy.jpg",favorite=false)
        val finding=ContentFinding("Educație/Grupa mică","Activități pentru copii","Exemplu",partial=true,strong=false)
        val r=CleanReport(listOf(keeper,copy),listOf(DuplicateGroup(keeper,listOf(copy),"a".repeat(64))),emptyList(),emptyList(),emptyList(),emptyList(),listOf("Analiză parțială"),2,2,mapOf(keeper.uri to finding,copy.uri to finding))
        val restored=CleanupReportCodec.report(JSONObject(CleanupReportCodec.report(r).toString()))
        assertEquals(r.files,restored.files);assertEquals(r.duplicates,restored.duplicates);assertEquals(r.content,restored.content);assertEquals(r.warnings,restored.warnings)
        assertEquals(listOf(copy),restored.duplicateCopies);assertEquals("Pictures/Educație · Grupa mică/",restored.placements.first().destination)
    }
    @Test fun unknownTopicsRemainUncertainAfterBackgroundAnalysis(){
        val f=CleanFile("content://docs/a","Material.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",100,800,parent="content://tree/root",path="Diverse/",flags=512)
        val r=CleanReport(listOf(f),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),0,0,mapOf(f.uri to ContentFinding(null,"Nu există dovezi suficiente",partial=true)))
        val restored=CleanupReportCodec.report(CleanupReportCodec.report(r));assertNull(restored.content.getValue(f.uri).subject);assertTrue(restored.content.getValue(f.uri).partial);assertEquals("De verificat",restored.placements.single().destination);assertEquals(f,restored.files.single())
    }
    @Test fun staleDuplicateReferencesFailClosed(){
        val f=CleanFile("content://media/1","One.jpg","image/jpeg",100,800,gallery=true)
        val r=CleanReport(listOf(f),listOf(DuplicateGroup(f,listOf(f.copy(uri="content://media/missing")),"a".repeat(64))),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),0,0,emptyMap())
        try{CleanupReportCodec.report(CleanupReportCodec.report(r));fail("Missing original must not become actionable")}catch(_:NoSuchElementException){}
    }
}
