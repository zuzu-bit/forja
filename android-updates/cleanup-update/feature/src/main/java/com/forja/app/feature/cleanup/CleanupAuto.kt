package com.forja.app.feature.cleanup

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object CleanupAuto {
    const val TAG="forja-auto-cleanup-v17"
    val changed=MutableStateFlow(0L)
    fun prefs(c:Context)=c.getSharedPreferences("cleanup_auto_v17",Context.MODE_PRIVATE)
    fun valid(c:Context,owner:String,grant:String):Boolean {val p=prefs(c);return p.getBoolean("enabled",false)&&p.getString("owner",null)==owner&&FileSync.owner()==owner&&p.getString("grant",null)==grant}
    fun enabled(c:Context):Boolean{val p=prefs(c);return p.getBoolean("enabled",false)&&p.getString("owner",null)==FileSync.owner()}
    fun status(c:Context,s:String){prefs(c).edit().putString("status",s).apply();changed.value++}
    fun device(c:Context):String {val p=c.getSharedPreferences("cleanup_scope_v14",Context.MODE_PRIVATE);return p.getString("cleanup_device",null)?:UUID.randomUUID().toString().also{check(p.edit().putString("cleanup_device",it).commit())}}
    suspend fun activate(c:Context,photos:Boolean,files:Boolean,tree:String,organize:Boolean=false){
        check(photos||files){"Alege cel puțin o sursă."};check(!files||tree.isNotBlank()){"Alege dosarul cu fișiere."}
        val uid=checkNotNull(FileSync.owner()){"Conectează-te în FORJA."};val id=device(c);val grant=UUID.randomUUID().toString()
        val body=JSONObject().put("id",grant).put("enabled",true).put("photos",photos).put("files",files).put("label",Build.MODEL.take(80)).put("protocol",3).put("organize",organize)
        stopLocal(c)
        FileSync.request(c,uid,"/v2/cleanup/devices/$id/grant","POST",body.toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)
        FileSync.request(c,uid,"/v2/files/settings/$id","POST",JSONObject().put("enabled",true).put("photos",true).put("files",true).toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)
        check(FileSync.owner()==uid){"Contul s-a schimbat."}
        check(prefs(c).edit().putString("owner",uid).putString("device",id).putString("grant",grant).putBoolean("enabled",true).putBoolean("organize",organize).putInt("protocol",3).putBoolean("photos",photos).putBoolean("files",files).putString("tree",if(files)tree else "").putString("status","Telefon autorizat. Alege selecția sau orele în site → Poze și documente.").commit())
        if(FileSync.prefs(c).getBoolean("enabled",false))FileSync.stop(c)
        schedule(c,true);changed.value++
    }
    fun stopLocal(c:Context){
        val p=prefs(c);val old=p.getString("grant",null);p.edit().putBoolean("enabled",false).commit();WorkManager.getInstance(c).cancelAllWorkByTag(TAG)
        if(old!=null)CleanupTransferDb(c).use{it.autoBatches(old)}.forEach{CleanupTransfer.cancel(c,it)}
        status(c,"Analiza automată este oprită pe acest telefon.")
    }
    suspend fun stop(c:Context){
        val p=prefs(c);val owner=p.getString("owner",null);val id=p.getString("device",null);val photos=p.getBoolean("photos",false);val files=p.getBoolean("files",false)
        stopLocal(c)
        if(owner!=null&&id!=null&&owner==FileSync.owner())try{
            FileSync.request(c,owner,"/v2/cleanup/devices/$id/grant","POST",JSONObject().put("id",UUID.randomUUID().toString()).put("enabled",false).put("photos",photos).put("files",files).put("label",Build.MODEL.take(80)).toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)
        }catch(e:CancellationException){throw e}catch(_:Exception){status(c,"Oprită pe telefon. Site-ul nu a primit încă dezactivarea; telefonul nu va executa programul.")}
    }
    fun schedule(c:Context,now:Boolean=false){
        if(!enabled(c))return;val wm=WorkManager.getInstance(c)
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        wm.enqueueUniquePeriodicWork(TAG,ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<CleanupAutoPollWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).addTag(TAG).build())
        if(now)wm.enqueueUniqueWork("$TAG-check",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<CleanupAutoPollWorker>().setConstraints(constraints).addTag(TAG).build())
    }
    private val pollLock=kotlinx.coroutines.sync.Mutex()
    suspend fun poll(c:Context):Boolean {if(!pollLock.tryLock())return false;try{return pollOnce(c)}finally{pollLock.unlock()}}
    private suspend fun pollOnce(c:Context):Boolean {
        val p=prefs(c);val uid=p.getString("owner",null)?:return false;val grant=p.getString("grant",null)?:return false;val device=p.getString("device",null)?:return false
        if(!valid(c,uid,grant))return false
        suspend fun request(path:String,body:JSONObject?=null):JSONObject{check(valid(c,uid,grant));val result=FileSync.request(c,uid,path,if(body==null)"GET"else "POST",body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false);check(valid(c,uid,grant));return result}
        try{
            val d=request("/v2/cleanup/devices/$device");check(d.getJSONObject("grant").getString("id")==grant){"Activarea s-a schimbat. Reactivează pe telefon."}
            val s=d.getJSONObject("schedule");status(c,if(s.optBoolean("enabled"))"Program: ${s.getInt("count")} din fiecare sursă · ${s.getJSONArray("times").join(", ").replace("\"","")} · ${s.getString("timezone")}"else "Telefon pregătit. Cere o selecție sau programeaz-o din site.")
            val result=request("/v2/cleanup/devices/$device/claim",JSONObject().put("grant_id",grant))
            OrganizerPlans.poll(c,uid,grant,device)
            val wm=WorkManager.getInstance(c);val run=result.optJSONObject("run")
            if(run!=null){
                val rs=run.getJSONObject("schedule");if(run.has("selection"))OrganizerSelection.parse(run.getJSONObject("selection"))else check(rs.getInt("count") in setOf(25,50));check(!rs.optBoolean("photos")||p.getBoolean("photos",false));check(!rs.optBoolean("files")||p.getBoolean("files",false))
                val constraints=Constraints.Builder().setRequiredNetworkType(if(rs.optBoolean("wifi_only",true))NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
                wm.enqueueUniqueWork("$TAG-run-${run.getString("id")}",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CleanupAutoRunWorker>().setInputData(workDataOf("run" to run.getString("id"),"grant" to grant,"owner" to uid,"device" to device)).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build())
            }
            val next=result.optLong("next_at",0);val server=result.getLong("server_at")
            if(next>server)wm.enqueueUniqueWork("$TAG-at-${d.getInt("revision")}-$next",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CleanupAutoPollWorker>().setInitialDelay(next-server,TimeUnit.MILLISECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).addTag(TAG).build())
            return false
        }catch(e:CancellationException){throw e}catch(e:Exception){status(c,if(e is FileSync.Failure&&e.code==404)"Actualizează site-ul cu pachetul v20 pentru Curățenie automată."else e.message?:"Nu pot prelua programul.");return e !is FileSync.Failure||e.code in setOf(408,429)||e.code>=500}
    }
}
class CleanupAutoPollWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork()=if(CleanupAuto.poll(applicationContext))Result.retry()else Result.success()}
class CleanupAutoRunWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){
    override suspend fun doWork():Result {
        val c=applicationContext;val id=inputData.getString("run")?:return Result.failure();val uid=inputData.getString("owner")?:return Result.failure();val grant=inputData.getString("grant")?:return Result.failure();val device=inputData.getString("device")?:return Result.failure()
        if(!CleanupAuto.valid(c,uid,grant))return Result.success()
        val job=currentCoroutineContext()[Job]!!;val auth=FirebaseAuth.getInstance();val listener=FirebaseAuth.AuthStateListener{if(it.currentUser?.uid!=uid)job.cancel()};auth.addAuthStateListener(listener)
        try{return CleanupAnalysisLock.mutex.withLock {
            suspend fun request(body:JSONObject?=null):JSONObject{
                check(CleanupAuto.valid(c,uid,grant)){"Analiza automată a fost oprită."}
                val response=FileSync.request(c,uid,"/v2/cleanup/devices/$device/runs/$id",if(body==null)"GET"else "POST",body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false)
                check(CleanupAuto.valid(c,uid,grant));return response
            }
            val run=request();if(run.getString("phase") in setOf("complete","error"))return@withLock Result.success()
            val schedule=run.getJSONObject("schedule");val selection=run.optJSONObject("selection")?.let(OrganizerSelection::parse);val count=if(selection!=null)0 else schedule.getInt("count");check(selection!=null||count in setOf(25,50))
            val prefs=CleanupAuto.prefs(c);check(!schedule.optBoolean("photos")||prefs.getBoolean("photos",false));check(!schedule.optBoolean("files")||prefs.getBoolean("files",false))
            val tree=prefs.getString("tree","").orEmpty();val store=CleanupAutoStore(c);store.prune()
            var state=store.read(id)
            if(state==null){
                val groups=JSONObject();val warnings=JSONObject();val catalog=CleanupCatalog(c,count)
                if(schedule.optBoolean("photos")){val result=if(selection==null)catalog.gallery(true,false)else withContext(OrganizerInventoryFilter(selection,"photos")){catalog.gallery(true,false)};groups.put("photos",JSONArray((selection?.select(result.first,"photos")?:result.first).map(CleanupReportCodec::file)));warnings.put("photos",JSONArray(result.second))}
                if(schedule.optBoolean("files")){check(tree.isNotBlank());val result=if(selection==null)catalog.folder(Uri.parse(tree),true)else withContext(OrganizerInventoryFilter(selection,"files")){catalog.folder(Uri.parse(tree),true)};groups.put("files",JSONArray((selection?.select(result.first,"files")?:result.first).map(CleanupReportCodec::file)));warnings.put("files",JSONArray(result.second))}
                state=JSONObject().put("id",id).put("owner",uid).put("grant",grant).put("created",System.currentTimeMillis()).put("tree",tree).put("groups",groups).put("warnings",warnings).put("reports",JSONObject()).put("mode",selection?.mode?:"local")
                store.write(id,state)
            }
            val saved=state;check(saved.getString("owner")==uid&&saved.getString("grant")==grant)
            val groups=saved.getJSONObject("groups");val sources=groups.keys().asSequence().toList().sorted();val all=sources.flatMap{CleanupReportCodec.files(groups.getJSONArray(it))}
            CleanupTransferDb(c).use{it.create(uid,device,all,id,grant)}
            suspend fun publish(phase:String,message:String){
                val reports=saved.getJSONObject("reports");val restored=reports.keys().asSequence().map{CleanupReportCodec.report(reports.getJSONObject(it))}.toList()
                val summary=CleanupTransferDb(c).use{it.summary(id)}
                fun clean(s:String,max:Int)=s.replace(Regex("[\\x00-\\x1f\\x7f]")," ").take(max)
                val proposals=restored.flatMap{PhoneCleanupPaths.proposals(it.files,it.content)}.take(100).map{p->JSONObject().put("name",clean(p.file.name,100)).put("destination",clean(p.destination,120)).put("reason",clean(p.reason,120)).put("kind",if(p.file.photo)"photo"else "file")}
                request(JSONObject().put("grant_id",grant).put("phase",phase).put("analyzed",restored.sumOf{it.files.size}).put("proposals",JSONArray(proposals)).put("duplicates",restored.sumOf{it.duplicateCopies.size}).put("uploaded",summary.sent).put("total",all.size).put("message",clean(message,400)))
            }
            if(run.getString("phase")!="analyzed")publish("analyzing","Analiza locală și încărcarea selecției sunt în curs.")
            coroutineScope {
                val upload=async(Dispatchers.IO){CleanupTransfer.drain(c,id)}
                for(source in sources){
                    if(saved.getJSONObject("reports").has(source))continue
                    request() // A changed web schedule cannot start another source from an old run.
                    val files=CleanupReportCodec.files(groups.getJSONArray(source))
                    val report=if(run.optJSONObject("selection")?.optString("mode")=="manual")ManualInventory.report(files)else withContext(CleanupScanInput(files)){CleanupScan(c).scan(source,tree.takeIf{it.isNotBlank()}?.let(Uri::parse),true,false,true,{s->CleanupAuto.status(c,s)},{})}
                    val encoded=CleanupReportCodec.report(report);val warnings=saved.getJSONObject("warnings").optJSONArray(source)
                    if(warnings!=null)for(i in 0 until warnings.length())encoded.getJSONArray("warnings").put(warnings.getString(i))
                    saved.getJSONObject("reports").put(source,encoded);store.write(id,saved)
                }
                if(prefs.getInt("protocol",1)>=2)OrganizerPlans.publishEvidence(c,uid,grant,device,id,saved)
                publish("analyzed","Propunerile sunt gata în telefon și pe site. Originalele nu au fost modificate.")
                val retry=upload.await();val summary=CleanupTransferDb(c).use{it.summary(id)}
                if(retry){CleanupAuto.status(c,"Analiza este gata. Transferul continuă când conexiunea permite.");return@coroutineScope Result.retry()}
                publish("complete",if(summary.sent==summary.total)"Analiză și transfer terminate. Vezi propunerile în telefon."else "Analiza este gata; ${summary.sent}/${summary.total} fișiere au fost primite. Verifică detaliile transferului în aplicație.")
                CleanupAuto.status(c,"Analiză automată terminată · ${all.size} analizate · ${summary.sent}/${summary.total} pe site. Vezi propunerile de mai jos.")
                Result.success()
            }
        }}catch(e:CancellationException){throw e}catch(e:Exception){
            CleanupAuto.status(c,e.message?:"Analiza automată va fi reîncercată.")
            // Intake pause is temporary: retain the frozen selection and receipts
            // so the same run can resume after the website accepts data again.
            if(e is FileSync.Failure&&e.code in setOf(401,403,404,409,410)){CleanupTransfer.cancel(c,id);return Result.success()}
            return Result.retry()
        }finally{auth.removeAuthStateListener(listener)}
    }
}
