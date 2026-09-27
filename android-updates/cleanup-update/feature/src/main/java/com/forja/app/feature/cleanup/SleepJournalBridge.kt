package com.forja.app.feature.cleanup

import android.content.Context
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** A recording interval is a journal entry, not a measured sleep quality/stage. */
internal object SleepJournalPolicy {
    fun valid(id:String,start:Long,end:Long,now:Long):Boolean = runCatching {
        UUID.fromString(id).toString()==id.lowercase() && start>0 && end>start &&
            end-start<=13*60*60*1000L && end<=now+60000L
    }.getOrDefault(false)
    fun localId(owner:String,id:String):Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest((owner+"\u0000"+id).toByteArray(Charsets.UTF_8))).long or Long.MIN_VALUE
    fun cloud(start:Long,end:Long,id:String):Map<String,Any?> = mapOf(
        "startAt" to start,"endAt" to end,"recordingId" to id,"measurement" to "recording_interval",
        "score" to null,"deepMin" to null,"lightMin" to null,"remMin" to null,"movements" to null,
        "snoreEvents" to null,"talkEvents" to null,"soundEvents" to null,
        "summary" to "Interval înregistrat. Raportul audio este în Somn."
    )
}

/** Retains the original Room/dashboard and web journal without a second recorder. */
object SleepJournalBridge {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Mutex()
    private val inFlight=ConcurrentHashMap.newKeySet<String>()

    @JvmStatic fun persist(context:Context,id:String,start:Long,end:Long) {
        val c=context.applicationContext
        val uid=FileSync.owner()?:return
        if(!SleepJournalPolicy.valid(id,start,end,System.currentTimeMillis()))return
        val mark=SleepJournalPolicy.localId(uid,id).toString()
        val saved=c.getSharedPreferences("sleep_journal_v25",Context.MODE_PRIVATE)
        if(saved.getBoolean("local:"+mark,false) && saved.getBoolean("cloud:"+mark,false))return
        if(!inFlight.add(mark))return
        scope.launch {
            try { lock.withLock {
                try { save(c,uid,id,start,end) }
                catch(e:CancellationException){throw e}
                catch(_:Exception){ /* Same stable entry is retried by the existing sleep sync worker. */ }
            }} finally { inFlight.remove(mark) }
        }
    }

    private fun stillOwner(c:Context,uid:String,id:String,start:Long,end:Long):Boolean {
        if(FileSync.owner()!=uid)return false
        val type=Class.forName("com.forja.app.feature.research.SleepAudioState")
        val row=type.getMethod("session",Context::class.java,String::class.java).invoke(null,c,id) as? org.json.JSONObject?:return false
        return row.optString("owner")==uid && row.optLong("started_at")==start && row.optLong("ended_at")==end
    }

    private suspend fun save(c:Context,uid:String,id:String,start:Long,end:Long) {
        if(!stillOwner(c,uid,id,start,end))return
        val localId=SleepJournalPolicy.localId(uid,id)
        val saved=c.getSharedPreferences("sleep_journal_v25",Context.MODE_PRIVATE)
        val mark=localId.toString()
        if(!saved.getBoolean("local:"+mark,false)) {
            val appType=Class.forName("com.forja.app.ForjaApp")
            val companion=appType.getField("Companion").get(null)
            val app=companion.javaClass.getMethod("from",Context::class.java).invoke(companion,c)
            val db=appType.getMethod("getDb").invoke(app)
            val dao=Class.forName("com.forja.app.core.data.db.ForjaDatabase").getMethod("sleepDao").invoke(db)
            val daoType=Class.forName("com.forja.app.core.data.db.SleepDao")
            val entityType=Class.forName("com.forja.app.core.data.db.SleepSessionEntity")
            // Checking the actual ID also survives process death between Room and the preference receipt.
            val entries=withTimeout(10000){(daoType.getMethod("finishedSince",Long::class.javaPrimitiveType).invoke(dao,start) as Flow<*>).first() as List<*>}
            val existing=entries.firstOrNull{it!=null && entityType.getMethod("getId").invoke(it)==localId}
            if(!stillOwner(c,uid,id,start,end))return
            if(existing==null) {
                val entity=entityType.getConstructor(
                    Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,Long::class.javaObjectType,
                    Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java,String::class.java,Long::class.javaPrimitiveType
                ).newInstance(localId,start,end,0,-1,-1,-1,-1,"","Interval înregistrat. Raportul audio este în Somn.",0L)
                invokeSuspend(daoType.getMethod("insert",entityType,Continuation::class.java),dao,entity)
            } else {
                check(entityType.getMethod("getStartAt").invoke(existing)==start && entityType.getMethod("getEndAt").invoke(existing)==end)
            }
            if(!stillOwner(c,uid,id,start,end))return
            saved.edit().putBoolean("local:"+mark,true).commit()
        }
        if(saved.getBoolean("cloud:"+mark,false) || !stillOwner(c,uid,id,start,end))return
        // A revoked upload grant does not erase the local interval or authorize a new upload.
        val row=Class.forName("com.forja.app.feature.research.SleepAudioState")
            .getMethod("session",Context::class.java,String::class.java).invoke(null,c,id) as org.json.JSONObject
        if(!SleepRuntime.valid(c,row))return
        val firestore=Class.forName("com.google.firebase.firestore.FirebaseFirestore")
        val collection=Class.forName("com.google.firebase.firestore.CollectionReference")
        val document=Class.forName("com.google.firebase.firestore.DocumentReference")
        val db=firestore.getMethod("getInstance").invoke(null)
        val users=firestore.getMethod("collection",String::class.java).invoke(db,"users")
        val user=collection.getMethod("document",String::class.java).invoke(users,uid)
        val sleep=document.getMethod("collection",String::class.java).invoke(user,"sleep")
        val record=collection.getMethod("document",String::class.java).invoke(sleep,"audio_"+id)
        if(!stillOwner(c,uid,id,start,end) || !SleepRuntime.valid(c,row))return
        // Firestore owns its offline queue; the receipt is written only after server acknowledgement.
        withTimeout(20000){(document.getMethod("set",Any::class.java).invoke(record,SleepJournalPolicy.cloud(start,end,id)) as Task<*>).await()}
        if(stillOwner(c,uid,id,start,end))saved.edit().putBoolean("cloud:"+mark,true).commit()
    }

    private suspend fun invokeSuspend(method:Method,target:Any,arg:Any):Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(target,arg,continuation) }
            catch(e:InvocationTargetException){throw e.targetException}
        }
}
