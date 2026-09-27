package com.forja.app.feature.cleanup

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.ZoneId

/** Calls the reviewed Java recorder without packaging duplicate ABI stubs. */
internal object SleepRuntime {
    private val type get() = Class.forName("com.forja.app.feature.research.SleepAudioState")
    fun read(c:Context,name:String):Any?=type.getMethod(name,Context::class.java).invoke(null,c)
    fun authorized(c:Context)=read(c,"authorized") as Boolean
    fun ready()=Class.forName("com.forja.app.feature.research.TimedRecordingService").getMethod("isReady").invoke(null) as Boolean
    fun active(c:Context)=read(c,"active") as JSONObject?
    fun sessions(c:Context)=read(c,"sessions") as JSONArray
    fun acoustic(c:Context,id:String)=type.getMethod("acoustic",Context::class.java,String::class.java).invoke(null,c,id) as JSONObject?
    fun recordings(c:Context)=read(c,"recordings") as JSONArray
    fun forChunk(c:Context,id:String)=type.getMethod("sessionForChunk",Context::class.java,String::class.java).invoke(null,c,id) as JSONObject?
    fun valid(c:Context,row:JSONObject)=type.getMethod("transferAuthorized",Context::class.java,JSONObject::class.java).invoke(null,c,row) as Boolean
    fun authorize(c:Context,analysis:Boolean)=type.getMethod("authorizeSleep",Context::class.java,Boolean::class.javaPrimitiveType).invoke(null,c,analysis) as Boolean
    fun start(c:Context,minutes:Int)=type.getMethod("startNow",Context::class.java,Int::class.javaPrimitiveType).invoke(null,c,minutes) as Boolean
    fun stop(c:Context){read(c,"stop")}
}

object SleepBridge {
    @JvmStatic fun scoreText(value:Int)=if(value<0)"—" else value.toString()
    private const val WORK="forja-sleep-reports-v25"
    private val lock=Mutex()
    private val alarmScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val alarmBusy=AtomicBoolean(false)
    fun prefs(c:Context)=c.getSharedPreferences("sleep_reports_v25",Context.MODE_PRIVATE)
    private fun key(uid:String,id:String)=FileSync.sha(uid.toByteArray()).take(20)+":"+id
    fun cached(c:Context,id:String):JSONObject? {
        val uid=FileSync.owner()?:return null
        return prefs(c).getString(key(uid,id),null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    }
    fun status(c:Context)=FileSync.owner()?.let{prefs(c).getString(key(it,"status"),"")}?:"Conectează-te pentru raportul tău."
    private fun status(c:Context,uid:String,text:String){if(uid==FileSync.owner())prefs(c).edit().putString(key(uid,"status"),text).apply()}
    private suspend fun api(c:Context,uid:String,path:String,body:JSONObject?=null):JSONObject = FileSync.request(c,uid,"/v2/sleep"+path,if(body==null)"GET"else "POST",body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false)
    private suspend fun register(c:Context,uid:String,row:JSONObject) {
        check(SleepRuntime.valid(c,row)){"Sincronizarea somnului este oprită pentru acest cont."}
        api(c,uid,"/sessions",JSONObject().put("id",row.getString("id")).put("device_id",row.getString("device_id")).put("started_at",row.getLong("started_at")).put("planned_stop_at",row.getLong("planned_stop_at")).put("analysis_consent",row.optBoolean("analysis_consent")))
        prefs(c).edit().putBoolean(key(uid,"registered:"+row.getString("id")),true).commit()
    }
    /** Runs on the existing upload worker before it creates the recording session. */
    @JvmStatic fun reserveBeforeUpload(c:Context,recordingId:String) {
        val row=SleepRuntime.forChunk(c,recordingId)?:return
        val uid=checkNotNull(FileSync.owner())
        runBlocking(Dispatchers.IO) {
            register(c,uid,row)
            api(c,uid,"/sessions/${row.getString("id")}/reserve",JSONObject().put("recording_session_id",recordingId))
            SleepRuntime.acoustic(c,recordingId)
            check(SleepRuntime.valid(c,row)){"Autorizarea somnului s-a schimbat."}
        }
    }
    @JvmStatic fun sync(c:Context) {
        if(FileSync.owner()==null || runCatching{SleepRuntime.sessions(c).length()==0}.getOrDefault(true))return
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager=WorkManager.getInstance(c)
        manager.enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SleepSyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        manager.enqueueUniquePeriodicWork(WORK+"-refresh",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SleepSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    /** Original alarm preference, triggered at the saved clock time, without inferred sleep stages. */
    @JvmStatic fun checkAlarm(c:Context) {
        if(!alarmBusy.compareAndSet(false,true))return
        alarmScope.launch {
            try {
                val active=SleepRuntime.active(c)?:SleepRuntime.sessions(c).let{rows->
                    (0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.has("ended_at") && System.currentTimeMillis()-it.optLong("ended_at") in 0..60000}.maxByOrNull{it.optLong("ended_at")}
                }?:return@launch
                val appType=Class.forName("com.forja.app.ForjaApp")
                val companion=appType.getField("Companion").get(null)
                val app=companion.javaClass.getMethod("from",Context::class.java).invoke(companion,c)
                val settings=appType.getMethod("getPrefs").invoke(app)
                suspend fun setting(name:String):Any?=withTimeout(3000){(settings.javaClass.getMethod(name).invoke(settings) as Flow<*>).first()}
                if(setting("getAlarmEnabled")!=true)return@launch
                val hour=setting("getAlarmHour") as Int;val minute=setting("getAlarmMinute") as Int
                val zone=ZoneId.systemDefault();val start=Instant.ofEpochMilli(active.getLong("started_at")).atZone(zone)
                var alarm=start.toLocalDate().atTime(hour,minute).atZone(zone)
                if(!alarm.isAfter(start))alarm=alarm.plusDays(1)
                val at=alarm.toInstant().toEpochMilli();val now=System.currentTimeMillis()
                val mark="alarm:"+active.getString("id")+":"+at
                if(now<at || now-at>60000 || prefs(c).getBoolean(mark,false) || active.has("ended_at") && active.optLong("ended_at")<at)return@launch
                if(!prefs(c).edit().putBoolean(mark,true).commit())return@launch
                val manager=c.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("sleep_alarm_v25","Alarmă de somn",NotificationManager.IMPORTANCE_HIGH))
                val open=Intent().setClassName(c.packageName,"com.forja.app.core.sleep.AlarmActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending=PendingIntent.getActivity(c,7314,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(7314,Notification.Builder(c,"sleep_alarm_v25").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Bună dimineața")
                    .setContentText("Este ora aleasă pentru trezire.").setCategory(Notification.CATEGORY_ALARM).setContentIntent(pending).setFullScreenIntent(pending,true).setAutoCancel(true).build())
            }catch(_:Exception){}finally{alarmBusy.set(false)}
        }
    }
    suspend fun refresh(c:Context):Boolean=withContext(Dispatchers.IO){lock.withLock {
        val uid=FileSync.owner()?:return@withLock false
        val sessions=SleepRuntime.sessions(c);val recordings=SleepRuntime.recordings(c)
        val byId=(0 until recordings.length()).map{recordings.getJSONObject(it)}.associateBy{it.getString("id")}
        var pending=false;var attachmentBudget=3
        for(index in 0 until sessions.length()) {
            val row=sessions.getJSONObject(index);if(!SleepRuntime.valid(c,row))continue
            if(row.has("ended_at"))SleepJournalBridge.persist(c,row.getString("id"),row.getLong("started_at"),row.getLong("ended_at"))
            val id=row.getString("id");val expired=key(uid,"expired:$id")
            if(prefs(c).getBoolean(expired,false))continue
            try {
                register(c,uid,row)
                if(row.has("ended_at"))api(c,uid,"/sessions/$id/finish",JSONObject().put("ended_at",row.getLong("ended_at")))
                val chunks=row.optJSONArray("chunks")?:JSONArray()
                for(chunkIndex in 0 until chunks.length()) {
                    val recordingId=chunks.getString(chunkIndex);val recording=byId[recordingId]
                    when(recording?.optString("state")) {
                        "uploaded"->{
                            val ack=key(uid,"attached:$recordingId");val retry=key(uid,"retry:$recordingId")
                            if(!prefs(c).getBoolean(ack,false) && System.currentTimeMillis()>=prefs(c).getLong(retry,0)){
                                if(attachmentBudget<=0){pending=true;continue};attachmentBudget--
                                val attachment=JSONObject().put("recording_session_id",recordingId)
                                SleepRuntime.acoustic(c,recordingId)?.let{attachment.put("acoustic",it)}
                                check(SleepRuntime.valid(c,row)){"Autorizarea somnului s-a schimbat."}
                                val receipt=try {api(c,uid,"/sessions/$id/chunks",attachment)}catch(e:FileSync.Failure){
                                    if(e.code==404 || e.code==410){prefs(c).edit().putBoolean(ack,true).commit();continue}
                                    if(e.code in setOf(401,403))throw e
                                    prefs(c).edit().putLong(retry,System.currentTimeMillis()+180000).commit();pending=true;continue
                                }
                                val state=receipt.optString("state")
                                if(state in setOf("complete","partial","skipped","rejected"))prefs(c).edit().putBoolean(ack,true).remove(retry).commit()
                                else {prefs(c).edit().putLong(retry,System.currentTimeMillis()+receipt.optLong("retry_after_ms",180000).coerceIn(15000,180000)).commit();pending=true}
                            }else if(!prefs(c).getBoolean(ack,false))pending=true
                        }
                        "cancelled","interrupted"->{ }
                        else->{pending=true}
                    }
                }
                val report=api(c,uid,"/sessions/$id")
                check(FileSync.owner()==uid){"Contul s-a schimbat."}
                prefs(c).edit().putString(key(uid,id),report.toString()).apply()
                if(report.optJSONObject("analysis")?.optInt("pending",0)?:0>0)pending=true
            } catch(e:CancellationException){throw e}
            catch(e:FileSync.Failure){
                if(e.code in setOf(404,410) && prefs(c).getBoolean(key(uid,"registered:$id"),false) || e.code==400 && System.currentTimeMillis()-row.optLong("started_at")>36*3600000L){
                    prefs(c).edit().putBoolean(expired,true).remove(key(uid,id)).commit()
                }else if(e.code in setOf(401,403))throw e
                else {pending=true;failed(c,e)}
            }
        }
        val list=api(c,uid,"/sessions")
        prefs(c).edit().putString(key(uid,"list"),list.toString()).apply()
        status(c,uid,if(pending)"Audio și raportul se sincronizează."else "Raport actualizat.")
        pending
    }}
    internal fun failed(c:Context,e:Exception){FileSync.owner()?.let{status(c,it,e.message?.take(160)?:"Sincronizarea se reia când revine conexiunea.")}}
}

class SleepSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=try {if(SleepBridge.refresh(applicationContext))Result.retry()else Result.success()}
    catch(e:CancellationException){throw e}
    catch(e:Exception){SleepBridge.failed(applicationContext,e);if(e is FileSync.Failure && e.code in setOf(401,403,410))Result.success()else Result.retry()}
}
