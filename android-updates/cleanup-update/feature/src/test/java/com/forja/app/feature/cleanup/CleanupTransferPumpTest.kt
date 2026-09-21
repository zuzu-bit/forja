package com.forja.app.feature.cleanup

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CleanupTransferPumpTest {
    private fun files(n:Int)=List(n){CleanFile("content://phone/$it","item-$it",if(it%2==0)"image/jpeg"else "application/pdf",2048,1234)}
    private class Port(items:List<TransferItem>):TransferPort {
        val remaining=items.toMutableList();val sent=mutableListOf<String>();val attempts=mutableListOf<String>();val failures=mutableListOf<TransferProblem>()
        var interrupted:String?=null;var invalid=false;var cancelled=false;var block:String?=null
        override suspend fun pending()=remaining.toList()
        override suspend fun checkActive(){if(cancelled)throw CancellationException("owner changed")}
        override suspend fun upload(item:TransferItem):TransferReceipt {
            attempts+=item.id
            if(item.id==interrupted){interrupted=null;throw TransferProblem(true,true,"connection lost after PUT")}
            if(item.id==block)throw TransferProblem(false,false,"too large")
            return TransferReceipt(if(invalid)"wrong-id"else item.id,"a".repeat(64),2048,1234,1234+86400000L)
        }
        override suspend fun confirm(item:TransferItem,receipt:TransferReceipt){sent+=item.id;remaining.remove(item)}
        override suspend fun failed(item:TransferItem,problem:TransferProblem){failures+=problem;if(!problem.retryable&&!problem.stopRound)remaining.remove(item)}
    }
    @Test fun everyAnalyzedPhotoAndDocumentIsQueuedEvenPastOld25Limit()=runBlocking {
        val selection=CleanupSelection.freeze(files(200));val p=Port(selection)
        assertFalse(CleanupTransferPump.run(p));assertEquals(200,p.sent.size);assertEquals(selection.map{it.id},p.sent)
    }
    @Test fun duplicateInventoryUrisDoNotCreateExtraCopies(){val f=files(50);assertEquals(50,CleanupSelection.freeze(f+f.take(10)).size)}
    @Test fun aNewAnalysisCreatesNewIdsEvenForCachedOrExpiredFiles(){val f=files(50);val first=CleanupSelection.freeze(f);val next=CleanupSelection.freeze(f);assertEquals(first.map{it.uri},next.map{it.uri});assertTrue(first.map{it.id}.toSet().intersect(next.map{it.id}.toSet()).isEmpty())}
    @Test fun interruptedUploadResumesSameIdWithoutRescanningOrResendingConfirmedFiles()=runBlocking {
        val selection=CleanupSelection.freeze(files(50));val p=Port(selection);p.interrupted=selection[13].id
        assertTrue(CleanupTransferPump.run(p));assertEquals(13,p.sent.size)
        assertFalse(CleanupTransferPump.run(p));assertEquals(selection.map{it.id},p.sent);assertEquals(2,p.attempts.count{it==selection[13].id});assertEquals(1,p.attempts.count{it==selection[0].id})
    }
    @Test fun blockedFileIsReportedAndDoesNotSilentlyDiscardTheRest()=runBlocking {
        val selection=CleanupSelection.freeze(files(50));val p=Port(selection);p.block=selection[4].id
        assertFalse(CleanupTransferPump.run(p));assertEquals(49,p.sent.size);assertEquals(1,p.failures.size);assertFalse(p.sent.contains(selection[4].id))
    }
    @Test fun wrongReceiptNeverMarksAFileReceived()=runBlocking {
        val p=Port(CleanupSelection.freeze(files(2)));p.invalid=true;assertTrue(CleanupTransferPump.run(p));assertTrue(p.sent.isEmpty());assertEquals(2,p.remaining.size)
    }
    @Test fun accountChangeStopsBeforeAnyUpload()=runBlocking {
        val p=Port(CleanupSelection.freeze(files(2)));p.cancelled=true
        try{CleanupTransferPump.run(p);fail("Expected cancellation")}catch(_:CancellationException){}
        assertTrue(p.attempts.isEmpty());assertEquals(2,p.remaining.size)
    }
}
