package com.forja.app.feature.cleanup

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Independent of expiring cloud copies. One original may have several immutable versions. */
internal class OrganizerLedger(c:Context):SQLiteOpenHelper(c,"organizer_v4.db",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,owner TEXT NOT NULL,created INTEGER NOT NULL,phase TEXT NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX jobs_owner ON jobs(owner,created)")
        db.execSQL("CREATE TABLE originals(id TEXT PRIMARY KEY,owner TEXT NOT NULL,current_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE aliases(owner TEXT NOT NULL,identity TEXT NOT NULL,original_id TEXT NOT NULL,PRIMARY KEY(owner,identity))")
        db.execSQL("CREATE TABLE versions(id TEXT PRIMARY KEY,original_id TEXT NOT NULL,owner TEXT NOT NULL,stamp TEXT NOT NULL,sha TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'pending',lease TEXT NOT NULL DEFAULT '',modified INTEGER NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX eligible_versions ON versions(owner,lease,state,modified)")
        db.execSQL("CREATE TABLE manifest(job TEXT NOT NULL,item TEXT NOT NULL,PRIMARY KEY(job,item))")
        db.execSQL("CREATE TABLE job_items(job TEXT NOT NULL,item TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,analyzed INTEGER NOT NULL DEFAULT 0,uploaded INTEGER NOT NULL DEFAULT 0,published INTEGER NOT NULL DEFAULT 0,reported INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(job,item))")
        db.execSQL("CREATE TABLE directories(job TEXT NOT NULL,uri TEXT NOT NULL,path TEXT NOT NULL,depth INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(job,uri))")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=Unit
    private fun query(sql:String,args:Array<String>)=readableDatabase.rawQuery(sql,args)
    fun job(id:String):JSONObject?=query("SELECT payload FROM jobs WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}
    fun jobs(owner:String):List<JSONObject> =query("SELECT payload FROM jobs WHERE owner=? ORDER BY created DESC LIMIT 100",arrayOf(owner)).use{q->buildList{while(q.moveToNext())add(JSONObject(q.getString(0)))}}
    fun create(job:JSONObject):JSONObject {
        val db=writableDatabase;db.beginTransaction();try{
            val old=job(job.getString("id"));if(old!=null){check(old.getString("owner")==job.getString("owner")&&old.getString("request_signature")==job.getString("request_signature")){"Cerere refolosită cu altă selecție."};db.setTransactionSuccessful();return old}
            db.execSQL("INSERT INTO jobs(id,owner,created,phase,payload) VALUES(?,?,?,?,?)",arrayOf(job.getString("id"),job.getString("owner"),job.getLong("created"),job.getString("phase"),job.toString()));db.setTransactionSuccessful();return job
        }finally{db.endTransaction()}
    }
    fun save(job:JSONObject,control:Boolean=false){job.put("revision",job.optLong("revision")+1);check(writableDatabase.update("jobs",ContentValues().apply{put("phase",job.getString("phase"));put("payload",job.toString())},if(control)"id=?"else "id=? AND (phase NOT IN ('cancelled','paused') OR phase=?)",if(control)arrayOf(job.getString("id"))else arrayOf(job.getString("id"),job.getString("phase")))==1){"Jobul a fost oprit între timp."};CleanupAuto.changed.value++}
    fun mutate(id:String,block:(JSONObject)->Unit):JSONObject {val db=writableDatabase;db.beginTransaction();try{val j=checkNotNull(job(id));block(j);save(j);db.setTransactionSuccessful();return j}finally{db.endTransaction()}}
    companion object {
        fun identity(uri:String):String=runCatching{val u=Uri.parse(uri);if(u.pathSegments.contains("document"))u.authority+":"+DocumentsContract.getDocumentId(u)else uri}.getOrDefault(uri)
        fun stamp(f:CleanFile)="${f.bytes}:${f.modified}"
        val terminal=setOf("moved","skipped")
    }
    fun known(owner:String,uri:String):JSONObject?=query("SELECT v.payload FROM aliases a JOIN originals o ON o.id=a.original_id JOIN versions v ON v.id=o.current_id WHERE a.owner=? AND a.identity=?",arrayOf(owner,identity(uri))).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}
    fun observe(owner:String,f:CleanFile,verifiedSha:String?=null):JSONObject {
        val db=writableDatabase;db.beginTransaction();try{
            val previous=known(owner,f.uri);
            val same=previous!=null&&(stamp(f)==previous.optString("stamp") || verifiedSha!=null&&verifiedSha==previous.optString("sha"))
            val row=if(same)previous!! else JSONObject().put("id",UUID.randomUUID().toString()).put("original_id",previous?.getString("original_id")?:UUID.randomUUID().toString()).put("owner",owner).put("state","pending").put("sha",verifiedSha?:"")
            row.put("file",CleanupReportCodec.file(f)).put("stamp",stamp(f))
            val id=row.getString("id");val original=row.getString("original_id")
            if(!same){db.execSQL("INSERT INTO versions(id,original_id,owner,stamp,sha,state,modified,payload) VALUES(?,?,?,?,?,?,?,?)",arrayOf(id,original,owner,stamp(f),row.optString("sha"),"pending",f.modified,row.toString()))
                db.execSQL("INSERT OR REPLACE INTO originals(id,owner,current_id) VALUES(?,?,?)",arrayOf(original,owner,id))
            }else db.execSQL("UPDATE versions SET stamp=?,modified=?,payload=? WHERE id=?",arrayOf(stamp(f),f.modified,row.toString(),id))
            db.execSQL("INSERT OR REPLACE INTO aliases(owner,identity,original_id) VALUES(?,?,?)",arrayOf(owner,identity(f.uri),original))
            db.setTransactionSuccessful();return row
        }finally{db.endTransaction()}
    }
    fun inventory(job:String,row:JSONObject){writableDatabase.execSQL("INSERT OR IGNORE INTO manifest(job,item) VALUES(?,?)",arrayOf(job,row.getString("id")))}
    fun select(job:JSONObject):Int {
        val db=writableDatabase;db.beginTransaction();try{
            val id=job.getString("id");val requested=job.getInt("count")
            val existing=count(id);if(job.optBoolean("selection_frozen")){db.setTransactionSuccessful();return existing}
            val candidates=query("SELECT v.id,v.payload FROM manifest m JOIN versions v ON v.id=m.item JOIN originals o ON o.current_id=v.id WHERE m.job=? AND v.owner=? AND v.lease='' AND v.state NOT IN ('moved','skipped') ORDER BY v.modified DESC,v.id LIMIT ?",arrayOf(id,job.getString("owner"),if(requested==0)"-1"else requested.toString()))
            candidates.use{q->while(q.moveToNext()){
                val item=q.getString(0);val row=JSONObject(q.getString(1)).put("state","pending").put("operation_id",UUID.randomUUID().toString()).put("copy_id",UUID.randomUUID().toString())
                db.execSQL("UPDATE versions SET lease=? WHERE id=? AND lease=''",arrayOf(id,item))
                db.execSQL("INSERT OR IGNORE INTO job_items(job,item,state,payload) VALUES(?,?,?,?)",arrayOf(id,item,"pending",row.toString()))
            }}
            job.put("selection_frozen",true).put("phase","analyzing");save(job);db.setTransactionSuccessful();return count(id)
        }finally{db.endTransaction()}
    }
    fun totals(job:String):Pair<Int,Int> =query("SELECT COALESCE(SUM(analyzed),0),COALESCE(SUM(uploaded),0) FROM job_items WHERE job=?",arrayOf(job)).use{it.moveToFirst();it.getInt(0) to it.getInt(1)}
    fun count(job:String)=query("SELECT COUNT(*) FROM job_items WHERE job=?",arrayOf(job)).use{it.moveToFirst();it.getInt(0)}
    fun items(job:String,limit:Int=1000):List<JSONObject> =query("SELECT payload FROM job_items WHERE job=? ORDER BY rowid LIMIT ?",arrayOf(job,limit.toString())).use{q->buildList{while(q.moveToNext())add(JSONObject(q.getString(0)))}}
    fun states(job:String,states:Set<String>,limit:Int=10):List<JSONObject> =query("SELECT payload FROM job_items WHERE job=? AND state IN ("+states.joinToString(","){"?"}+") ORDER BY rowid LIMIT ?",arrayOf(job,*states.toTypedArray(),limit.toString())).use{q->buildList{while(q.moveToNext())add(JSONObject(q.getString(0)))}}
    fun receipts(job:String):List<JSONObject> =query("SELECT payload FROM job_items WHERE job=? AND state IN ('moved','copied_pending_removal','needs_review') AND reported=0 AND published=1 ORDER BY rowid LIMIT 50",arrayOf(job)).use{q->buildList{while(q.moveToNext())add(JSONObject(q.getString(0)))}}
    fun allPublished(job:String)=query("SELECT COUNT(*) FROM job_items WHERE job=? AND published=0 AND state NOT IN ('needs_review','skipped')",arrayOf(job)).use{it.moveToFirst();it.getInt(0)==0}
    fun unfinished(job:String,limit:Int=10):List<JSONObject> =query("SELECT payload FROM job_items WHERE job=? AND state NOT IN ('moved','needs_review','skipped') ORDER BY rowid LIMIT ?",arrayOf(job,limit.toString())).use{q->buildList{while(q.moveToNext())add(JSONObject(q.getString(0)))}}
    fun item(job:String,id:String):JSONObject?=query("SELECT payload FROM job_items WHERE job=? AND item=?",arrayOf(job,id)).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}
    fun saveItem(job:String,row:JSONObject){val db=writableDatabase;db.beginTransaction();try{
        val id=row.getString("id");db.execSQL("UPDATE job_items SET state=?,payload=?,analyzed=?,uploaded=?,published=?,reported=? WHERE job=? AND item=?",arrayOf<Any>(row.getString("state"),row.toString(),if(row.has("finding"))1 else 0,if(row.optBoolean("copy_received"))1 else 0,if(row.optBoolean("published"))1 else 0,if(row.optBoolean("reported"))1 else 0,job,id))
        db.execSQL("UPDATE versions SET state=?,sha=?,payload=? WHERE id=?",arrayOf(row.getString("state"),row.optString("sha"),row.toString(),id))
        db.setTransactionSuccessful();CleanupAuto.changed.value++
    }finally{db.endTransaction()}}
    fun alias(owner:String,original:String,uri:String){writableDatabase.execSQL("INSERT OR REPLACE INTO aliases(owner,identity,original_id) VALUES(?,?,?)",arrayOf(owner,identity(uri),original))}
    fun counts(job:String):Map<String,Int> =query("SELECT state,COUNT(*) FROM job_items WHERE job=? GROUP BY state",arrayOf(job)).use{q->buildMap{while(q.moveToNext())put(q.getString(0),q.getInt(1))}}
    fun release(job:String){writableDatabase.execSQL("UPDATE versions SET lease='' WHERE lease=? AND state NOT IN ('applying','copied_pending_removal','moved','needs_review')",arrayOf(job))}
    fun directory(job:String,uri:String,path:String,depth:Int){writableDatabase.execSQL("INSERT OR IGNORE INTO directories(job,uri,path,depth) VALUES(?,?,?,?)",arrayOf(job,uri,path,depth))}
    fun nextDirectory(job:String):Triple<String,String,Int>?=query("SELECT uri,path,depth FROM directories WHERE job=? AND done=0 ORDER BY rowid LIMIT 1",arrayOf(job)).use{if(it.moveToFirst())Triple(it.getString(0),it.getString(1),it.getInt(2))else null}
    fun directoryDone(job:String,uri:String){writableDatabase.execSQL("UPDATE directories SET done=1 WHERE job=? AND uri=?",arrayOf(job,uri))}
}
