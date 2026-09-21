package com.forja.app.feature.cleanup
import org.junit.Assert.*
import org.junit.Test
class AnalysisTest {
    private fun file(id:String,time:Long)=CleanFile(id,"$id.txt","text/plain",1,time)
    @Test fun picksNewest50BeforeAnyContentWork(){val all=(0..79).map{file(it.toString(),it.toLong())}.shuffled();val chosen=CleanupScope.latest(all,50);assertEquals(50,chosen.size);assertEquals(79L,chosen.first().modified);assertEquals(30L,chosen.last().modified)}
    @Test fun unknownDatesComeLastAndUrisAreUnique(){val f=file("a",42);assertEquals(listOf("a","b"),CleanupScope.latest(listOf(file("b",0),f,f),50).map{it.uri})}
    @Test fun allKeepsEveryAccessibleFile(){assertEquals(230,CleanupScope.latest((0..229).map{file("$it",it.toLong())},0).size)}
    @Test fun noFixedFiveFragmentMajorityForTinyFooters(){val votes=listOf(ContentDecision.Vote("Educație/Preșcolar",.6,126))+List(5){ContentDecision.Vote("Financiar/Facturi",.4,4)};assertEquals("Educație/Preșcolar",ContentDecision.choose(votes,false).category)}
    @Test fun competingTopicsNeedReview(){val votes=listOf(ContentDecision.Vote("a",.7,126),ContentDecision.Vote("b",.7,126));assertNull(ContentDecision.choose(votes,false).category)}
    @Test fun partialDocumentsNeverBulkSelectAsClear(){assertFalse(ContentDecision.choose(listOf(ContentDecision.Vote("a",.8,126)),true).strong)}
    @Test fun lowEvidenceCannotBecomeClearByOneStrongParagraph(){assertFalse(ContentDecision.choose(listOf(ContentDecision.Vote("a",.8,126),ContentDecision.Vote("a",.24,126)),false).strong)}
    @Test fun explicitKindergartenGroupRefinesOnlyWhenUnambiguous(){assertEquals("Educație/Preșcolar/Grupa mare",ContentDecision.refine("Educație/Preșcolar","Fișă didactică pentru grupa mare."));assertEquals("Educație/Preșcolar",ContentDecision.refine("Educație/Preșcolar","Grupa mare și grupa mică."))}
    @Test fun structuralEvidenceNeedsSeveralIndependentCues(){assertEquals(0.0,ContentDecision.structuralBoost("Financiar/Facturi","factura factura factura"),0.0);assertEquals(.04,ContentDecision.structuralBoost("Financiar/Facturi","Factură, furnizor, beneficiar și CIF"),.0001)}
    @Test fun weakVisualEvidenceNeverBecomesAConfidentCategory(){assertEquals(0f,VisualDecision.score(emptyList()),0f);assertTrue(VisualDecision.score(listOf(.72f))<.82f);assertTrue(VisualDecision.score(listOf(.9f,.8f))>VisualDecision.score(listOf(.9f)))}
    @Test fun conflictingAnimalTypesStayInTheirSharedFolder(){assertEquals("Animale",VisualDecision.refine("Animale",mapOf("Dog" to .96f,"Cat" to .94f)));assertEquals("Animale/Câini",VisualDecision.refine("Animale",mapOf("Dog" to .97f,"Cat" to .3f)))}
    @Test fun cvNeedsTitleAndIndependentSections(){assertEquals(.06,ContentDecision.structuralBoost("Muncă/CV","Curriculum vitae. Experiență profesională. Studii. Competențe."),.0001);assertEquals(0.0,ContentDecision.structuralBoost("Muncă/CV","Studii despre competențe și experiența profesională a contabililor."),0.0)}
}
