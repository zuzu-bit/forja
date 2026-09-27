package com.forja.app.feature.cleanup

import android.content.Context
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class OrganizerLedgerTest {
    private lateinit var context:Context
    private lateinit var db:OrganizerLedger
    private val owner="fixture-owner"
    private val sha="a".repeat(64)
    @Before fun setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("organizer_v4.db");db=OrganizerLedger(context)}
    @After fun close(){db.close()}
    private fun file(n:Int,modified:Long=10)=CleanFile("content://media/external/images/media/$n","IMG_$n.jpg","image/jpeg",64,modified,path="DCIM/",gallery=true)
    private fun job(count:Int=1)=JSONObject().put("id",UUID.randomUUID().toString()).put("owner",owner).put("created",100).put("phase","queued").put("revision",0).put("count",count).put("request_signature","scope-$count")
    private fun add(j:JSONObject,f:CleanFile)=db.observe(owner,f,sha).also{db.inventory(j.getString("id"),it)}
    @Test fun identicalBytesRemainSeparateOriginals(){val j=job(0);db.create(j);val a=add(j,file(1));val b=add(j,file(2));assertNotEquals(a.getString("original_id"),b.getString("original_id"));assertEquals(2,db.select(j))}
    @Test fun sameRequestReturnsSameFrozenBatch(){val j=job();db.create(j);add(j,file(1));db.select(j);val first=db.items(j.getString("id")).single().getString("id");add(j,file(2,100));db.create(JSONObject(j.toString()));db.select(j);assertEquals(first,db.items(j.getString("id")).single().getString("id"))}
    @Test fun overlappingJobsCannotLeaseSameOriginal(){val a=job();val b=job();db.create(a);db.create(b);val f=file(1);add(a,f);add(b,f);assertEquals(1,db.select(a));assertEquals(0,db.select(b))}
    @Test fun movedAliasAndTimestampChangeStayExcluded(){val a=job();db.create(a);add(a,file(1));db.select(a);val row=db.items(a.getString("id")).single().put("state","moved").put("sha",sha);db.saveItem(a.getString("id"),row);db.alias(owner,row.getString("original_id"),file(2).uri);val b=job();db.create(b);val moved=db.observe(owner,file(2,999),sha);db.inventory(b.getString("id"),moved);assertEquals(row.getString("id"),moved.getString("id"));assertEquals(0,db.select(b))}
    @Test fun changedContentCreatesVersionWithSameOriginal(){val old=db.observe(owner,file(1),sha);val newer=db.observe(owner,file(1,11),"b".repeat(64));assertEquals(old.getString("original_id"),newer.getString("original_id"));assertNotEquals(old.getString("id"),newer.getString("id"))}
    @Test fun verifiedContentChangeWinsOverUnchangedSizeAndTimestamp(){
        val old=db.observe(owner,file(1),sha)
        val newer=db.observe(owner,file(1),"b".repeat(64))
        assertEquals(old.getString("original_id"),newer.getString("original_id"))
        assertNotEquals("A verified hash change is a new content version even when the provider preserves its stamp",old.getString("id"),newer.getString("id"))
        assertEquals("b".repeat(64),newer.getString("sha"))
    }
    @Test fun applyingIntentSurvivesCloseAndCannotBeLeasedAgain(){val j=job();db.create(j);add(j,file(1));db.select(j);val row=db.items(j.getString("id")).single().put("state","applying").put("target_name","IMG_1.jpg").put("source_sha256",sha);db.saveItem(j.getString("id"),row);db.close();db=OrganizerLedger(context);assertEquals("applying",db.item(j.getString("id"),row.getString("id"))!!.getString("state"));db.release(j.getString("id"));val other=job();db.create(other);add(other,file(1));assertEquals(0,db.select(other))}
    @Test fun canceledJobCannotBeOverwrittenByStaleWorker(){val j=job();db.create(j);val stale=JSONObject(j.toString());j.put("phase","cancelled");db.save(j,true);stale.put("phase","complete");assertThrows(IllegalStateException::class.java){db.save(stale)};assertEquals("cancelled",db.job(j.getString("id"))!!.getString("phase"))}
    @Test fun receiptRetryPreservesMoveAndCountsOnce(){val j=job();db.create(j);add(j,file(1));db.select(j);val row=db.items(j.getString("id")).single().put("state","moved").put("published",true).put("copy_received",true).put("finding",JSONObject().put("reason","fixture"));db.saveItem(j.getString("id"),row);db.saveItem(j.getString("id"),row);assertEquals(1,db.receipts(j.getString("id")).size);assertEquals(1,db.counts(j.getString("id"))["moved"]);assertEquals(1 to 1,db.totals(j.getString("id")));row.put("reported",true);db.saveItem(j.getString("id"),row);assertTrue(db.receipts(j.getString("id")).isEmpty())}
    @Test fun failedUnavailableIsNeverCountedAsMoved(){val j=job();db.create(j);add(j,file(1));db.select(j);val row=db.items(j.getString("id")).single().put("state","needs_review").put("error","Unavailable");db.saveItem(j.getString("id"),row);assertEquals(0,db.counts(j.getString("id"))["moved"]?:0);assertEquals(1,db.counts(j.getString("id"))["needs_review"])}
    @Test fun allSelectionExceedsOld15000Cap(){
        val j=job(0);db.create(j);val sql=db.writableDatabase
        sql.beginTransaction();try{for(i in 1..15037)add(j,file(i,i.toLong()));sql.setTransactionSuccessful()}finally{sql.endTransaction()}
        assertEquals(15037,db.select(j));assertEquals(15037,db.count(j.getString("id")))
        // The manifest and all leases survive process loss; replays neither truncate nor duplicate.
        db.close();db=OrganizerLedger(context)
        val restored=db.job(j.getString("id"))!!
        assertEquals(15037,db.select(restored))
        assertEquals(15037,db.items(j.getString("id"),16000).map{it.getString("id")}.toSet().size)
        val other=job(0);db.create(other)
        for(i in listOf(1,7500,15000,15037))add(other,file(i,i.toLong()))
        assertEquals(0,db.select(other))
    }
    @Test fun crashDuringBatchSelectionRollsBackBothItemsAndLeases(){
        val j=job(0);db.create(j);add(j,file(1));add(j,file(2))
        db.writableDatabase.execSQL("CREATE TRIGGER fixture_fail_batch BEFORE INSERT ON job_items WHEN (SELECT COUNT(*) FROM job_items)=1 BEGIN SELECT RAISE(ABORT,'fixture interruption'); END")
        try{db.select(j);fail("Injected interruption must abort selection")}catch(_:android.database.sqlite.SQLiteException){}
        assertEquals(0,db.count(j.getString("id")))
        assertFalse(db.job(j.getString("id"))!!.optBoolean("selection_frozen"))
        db.writableDatabase.execSQL("DROP TRIGGER fixture_fail_batch")
        val other=job(0);db.create(other);add(other,file(1));add(other,file(2))
        assertEquals("No orphan lease may survive a failed batch",2,db.select(other))
    }
    @Test fun crashBetweenItemAndVersionPersistenceCannotCreateFalseMove(){
        val j=job();db.create(j);add(j,file(1));db.select(j)
        val row=db.items(j.getString("id")).single().put("state","moved").put("target_sha256",sha)
        db.writableDatabase.execSQL("CREATE TRIGGER fixture_fail_version BEFORE UPDATE ON versions BEGIN SELECT RAISE(ABORT,'fixture interruption'); END")
        try{db.saveItem(j.getString("id"),row);fail("Injected interruption must abort receipt")}catch(_:android.database.sqlite.SQLiteException){}
        assertEquals("pending",db.item(j.getString("id"),row.getString("id"))!!.getString("state"))
        assertEquals("pending",db.known(owner,file(1).uri)!!.getString("state"))
        assertEquals(0,db.counts(j.getString("id"))["moved"]?:0)
    }
    @Test fun regrantKeepsExactRemainingSelectionWithoutOldTransferReceipts(){
        val old=job(2);db.create(old);add(old,file(1));add(old,file(2));db.select(old)
        val rows=db.items(old.getString("id"));db.saveItem(old.getString("id"),rows[0].put("state","moved"))
        val remaining=rows[1].put("state","ready").put("published",true).put("copy_received",true).put("receipt:ready",JSONObject().put("fixture",true))
        val priorCopy=remaining.getString("copy_id");db.saveItem(old.getString("id"),remaining);db.release(old.getString("id"))
        db.observe(owner,file(999,999),sha) // Newly arrived, but not part of the consented remaining batch.
        val next=job(2);db.create(next);db.seedRemaining(old.getString("id"),next);db.select(next)
        val selected=db.items(next.getString("id")).single()
        assertEquals(remaining.getString("id"),selected.getString("id"));assertNotEquals(priorCopy,selected.getString("copy_id"))
        assertFalse(selected.optBoolean("copy_received"));assertFalse(selected.optBoolean("published"));assertFalse(selected.has("receipt:ready"))
    }
    @Test fun nextCommandAddsOnlyNewVersionsToSameLogicalJob(){
        val j=job(1);db.create(j);add(j,file(1));db.select(j);val first=db.items(j.getString("id")).single();db.saveItem(j.getString("id"),first.put("state","moved"))
        db.resetInventory(j);add(j,file(1));add(j,file(2));assertEquals(2,db.select(j))
        assertEquals(1,db.counts(j.getString("id"))["moved"]);assertEquals(1,db.counts(j.getString("id"))["pending"])
    }
    @Test fun ownerIsolationKeepsIdenticalUriSeparate(){val a=db.observe("one",file(1),sha);val b=db.observe("two",file(1),sha);assertNotEquals(a.getString("original_id"),b.getString("original_id"));assertEquals("one",db.known("one",file(1).uri)!!.getString("owner"))}
}
