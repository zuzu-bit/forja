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
    @Test fun applyingIntentSurvivesCloseAndCannotBeLeasedAgain(){val j=job();db.create(j);add(j,file(1));db.select(j);val row=db.items(j.getString("id")).single().put("state","applying").put("target_name","IMG_1.jpg").put("source_sha256",sha);db.saveItem(j.getString("id"),row);db.close();db=OrganizerLedger(context);assertEquals("applying",db.item(j.getString("id"),row.getString("id"))!!.getString("state"));db.release(j.getString("id"));val other=job();db.create(other);add(other,file(1));assertEquals(0,db.select(other))}
    @Test fun canceledJobCannotBeOverwrittenByStaleWorker(){val j=job();db.create(j);val stale=JSONObject(j.toString());j.put("phase","cancelled");db.save(j,true);stale.put("phase","complete");assertThrows(IllegalStateException::class.java){db.save(stale)};assertEquals("cancelled",db.job(j.getString("id"))!!.getString("phase"))}
    @Test fun receiptRetryPreservesMoveAndCountsOnce(){val j=job();db.create(j);add(j,file(1));db.select(j);val row=db.items(j.getString("id")).single().put("state","moved").put("published",true).put("copy_received",true).put("finding",JSONObject().put("reason","fixture"));db.saveItem(j.getString("id"),row);db.saveItem(j.getString("id"),row);assertEquals(1,db.receipts(j.getString("id")).size);assertEquals(1,db.counts(j.getString("id"))["moved"]);assertEquals(1 to 1,db.totals(j.getString("id")));row.put("reported",true);db.saveItem(j.getString("id"),row);assertTrue(db.receipts(j.getString("id")).isEmpty())}
    @Test fun failedUnavailableIsNeverCountedAsMoved(){val j=job();db.create(j);add(j,file(1));db.select(j);val row=db.items(j.getString("id")).single().put("state","needs_review").put("error","Unavailable");db.saveItem(j.getString("id"),row);assertEquals(0,db.counts(j.getString("id"))["moved"]?:0);assertEquals(1,db.counts(j.getString("id"))["needs_review"])}
    @Test fun allSelectionExceedsOld15000Cap(){val j=job(0);db.create(j);val sql=db.writableDatabase;sql.beginTransaction();try{for(i in 1..15037)add(j,file(i,i.toLong()));sql.setTransactionSuccessful()}finally{sql.endTransaction()};assertEquals(15037,db.select(j));assertEquals(15037,db.count(j.getString("id")))}
    @Test fun ownerIsolationKeepsIdenticalUriSeparate(){val a=db.observe("one",file(1),sha);val b=db.observe("two",file(1),sha);assertNotEquals(a.getString("original_id"),b.getString("original_id"));assertEquals("one",db.known("one",file(1).uri)!!.getString("owner"))}
}
