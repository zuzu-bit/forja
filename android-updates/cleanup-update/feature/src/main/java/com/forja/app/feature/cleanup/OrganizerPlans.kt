package com.forja.app.feature.cleanup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object OrganizerPlans {
    private val fileLock=Any()
    private fun file(c:Context,id:String):AtomicFile{require(id.matches(Regex("[0-9a-f-]{36}")));return AtomicFile(File(File(c.filesDir,"organizer_plans_v20").apply{mkdirs()},"$id.json"))}
    fun read(c:Context,id:String):JSONObject?=synchronized(fileLock){try{file(c,id).openRead().use{JSONObject(it.readBytes().toString(Charsets.UTF_8))}}catch(_:java.io.FileNotFoundException){null}}
    fun save(c:Context,p:JSONObject)=synchronized(fileLock){val f=file(c,p.getString("id"));val out=f.startWrite();try{out.write(p.toString().toByteArray());f.finishWrite(out);CleanupAuto.changed.value++}catch(e:Throwable){f.failWrite(out);throw e}}
    fun pending(c:Context):List<JSONObject>{val owner=FileSync.owner();return File(c.filesDir,"organizer_plans_v20").listFiles().orEmpty().filter{it.name.endsWith(".json")}.mapNotNull{runCatching{read(c,it.name.removeSuffix(".json"))}.getOrNull()}.filter{it.optString("owner")==owner&&it.optLong("expires_at")>System.currentTimeMillis()&&it.optString("local_state")=="awaiting_phone"&&CleanupAuto.valid(c,owner.orEmpty(),it.optString("grant_id"))}}
    private fun valid(c:Context,p:JSONObject){check(CleanupAuto.valid(c,p.getString("owner"),p.getString("grant_id"))&&CleanupAuto.prefs(c).getBoolean("organize",false)){"Organizarea din web a fost dezactivată."};check(p.getLong("expires_at")>System.currentTimeMillis()){ "Planul a expirat. Cere o analiză nouă."}}
    suspend fun request(c:Context,p:JSONObject,result:JSONObject?=null):JSONObject{valid(c,p);val response=FileSync.request(c,p.getString("owner"),"/v2/organizer/devices/${p.getString("device")}/plans/${p.getString("id")}",if(result==null)"GET"else "POST",result?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false);valid(c,p);return response}
    suspend fun publishEvidence(c:Context,owner:String,grant:String,device:String,id:String,saved:JSONObject){
        val transfers=CleanupTransferDb(c).use{it.inventory(id)}.associateBy{it.uri};val reports=saved.getJSONObject("reports");val items=mutableListOf<JSONObject>()
        fun clean(s:String,n:Int)=s.replace(Regex("[\\x00-\\x1f\\x7f]")," ").take(n)
        for(source in reports.keys()){
            val report=CleanupReportCodec.report(reports.getJSONObject(source));for(f in report.files){
                val t=transfers[f.uri]?:continue;val finding=report.content[f.uri];val subject=runCatching{OrganizerSelection.path(finding?.subject?:"De verificat")}.getOrDefault("De verificat")
                items+=JSONObject().put("id",t.id).put("name",clean(f.name,200)).put("kind",if(f.photo)"photo"else "file").put("source",source).put("folder",clean(f.path,240)).put("destination",subject.ifBlank{"De verificat"}).put("reason",clean(finding?.reason?:"Conținutul nu a putut fi analizat.",300)).put("excerpt",finding?.excerpt?.take(2000)?:"").put("partial",finding?.partial?:true)
            }
        }
        for(chunk in items.chunked(16)){check(CleanupAuto.valid(c,owner,grant));FileSync.request(c,owner,"/v2/organizer/devices/$device/runs/$id/items","POST",JSONObject().put("grant_id",grant).put("items",JSONArray(chunk)).toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)}
    }
    suspend fun poll(c:Context,owner:String,grant:String,device:String){
        if(!CleanupAuto.prefs(c).getBoolean("organize",false))return
        val result=FileSync.request(c,owner,"/v2/organizer/devices/$device/plans","GET",null,emptyMap(),false);check(CleanupAuto.valid(c,owner,grant));val plans=result.getJSONArray("plans")
        for(i in 0 until plans.length()){
            val remote=plans.getJSONObject(i);val id=remote.getString("id");val old=read(c,id)
            if(remote.getString("state") in setOf("complete","error")){if(old!=null){old.put("local_state",remote.getString("state"));save(c,old)};continue}
            val p=old?:remote.put("owner",owner).put("local_state",if(remote.getJSONArray("items").getJSONObject(0).getString("source")=="photos")"awaiting_phone"else "approved")
            check(p.getString("owner")==owner&&p.getString("grant_id")==grant);if(old==null)save(c,p)
            val local=p.getString("local_state")
            if(local in setOf("complete","error")){runCatching{sendResult(c,p)};continue}
            if(local=="awaiting_phone"){if(remote.getString("state")!="awaiting_phone")report(c,p,"awaiting_phone",message="Confirmă mutarea fotografiilor în Curățenie pe telefon; Android protejează galeria.");continue}
            WorkManager.getInstance(c).enqueueUniqueWork("forja-web-plan-$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<OrganizerPlanWorker>().setInputData(workDataOf("id" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag(CleanupAuto.TAG).build())
        }
    }
    suspend fun placements(c:Context,p:JSONObject):Pair<String,List<Placement>> = withContext(Dispatchers.IO){
        valid(c,p);val remote=request(c,p);check(remote.getString("state") in setOf("approved","awaiting_phone")){"Planul este deja aplicat, anulat sau în lucru."}
        val run=p.getString("run");val saved=checkNotNull(CleanupAutoStore(c).read(run));check(saved.getString("owner")==p.getString("owner")&&saved.getString("grant")==p.getString("grant_id"))
        val groups=saved.getJSONObject("groups");val originals=groups.keys().asSequence().flatMap{CleanupReportCodec.files(groups.getJSONArray(it)).asSequence()}.associateBy{it.uri};val transfer=CleanupTransferDb(c).use{it.inventory(run)}.associateBy{it.id}
        val rows=p.getJSONArray("items");val plan=List(rows.length()){index->val item=rows.getJSONObject(index);val t=checkNotNull(transfer[item.getString("id")]);val f=checkNotNull(originals[t.uri]);val destination=OrganizerSelection.path(item.getString("destination"));check(destination.isNotBlank());check(t.sha==item.getString("sha256")){"Copia nu corespunde originalului analizat."};Placement(f,PhoneCleanupPaths.destination(destination,f.gallery,f.video),"Destinație aprobată în propriul cont web")}
        check(plan.isNotEmpty()&&plan.size<=200);saved.optString("tree") to plan
    }
    private suspend fun verifyOriginal(c:Context,p:JSONObject,placement:Placement)=withContext(Dispatchers.IO){
        valid(c,p);val transfers=CleanupTransferDb(c).use{it.inventory(p.getString("run"))};val t=checkNotNull(transfers.find{it.uri==placement.file.uri});val rows=p.getJSONArray("items");val approved=(0 until rows.length()).map{rows.getJSONObject(it)}.first{it.getString("id")==t.id}
        val digest=MessageDigest.getInstance("SHA-256");var size=0L
        checkNotNull(c.contentResolver.openInputStream(Uri.parse(t.uri))).use{input->val buffer=ByteArray(32768);while(true){currentCoroutineContext().ensureActive();valid(c,p);val n=input.read(buffer);if(n<0)break;size+=n;check(size<=FileSync.MAX_BYTES){"Originalul s-a schimbat."};digest.update(buffer,0,n)}}
        check(digest.digest().joinToString(""){"%02x".format(it)}==approved.getString("sha256")){"${placement.file.name}: conținut schimbat; cere o analiză nouă."}
    }
    private suspend fun sendResult(c:Context,p:JSONObject)=request(c,p,JSONObject().put("grant_id",p.getString("grant_id")).put("state",p.getString("local_state")).put("moved",p.optInt("moved")).put("copied",p.optInt("copied")).put("skipped",p.optInt("skipped")).put("message",p.optString("message").replace(Regex("[\\x00-\\x1f\\x7f]")," ").take(800)))
    suspend fun report(c:Context,p:JSONObject,state:String,moved:Int=0,copied:Int=0,skipped:Int=0,message:String=""){
        p.put("local_state",state).put("moved",moved).put("copied",copied).put("skipped",skipped).put("message",message);save(c,p);val received=sendResult(c,p);check(received.getString("state")==state){"Planul a fost anulat sau finalizat între timp."}
    }
    private suspend fun indexDocuments(c:Context,rows:List<JSONObject>)=withContext(Dispatchers.IO){
        for(row in rows.filter{it.optString("kind")=="document"&&it.optString("state") in setOf("moved","copied")})runCatching{
            for(key in listOf("source","result")){val uri=Uri.parse(row.optString(key));if(uri.authority!="com.android.externalstorage.documents")continue
                val id=android.provider.DocumentsContract.getDocumentId(uri);if(id.startsWith("primary:")){val root=android.os.Environment.getExternalStorageDirectory().canonicalFile;val f=File(root,id.removePrefix("primary:")).canonicalFile
                    if(f.path.startsWith(root.path+"/"))android.media.MediaScannerConnection.scanFile(c,arrayOf(f.path),null,null)
                }
            }
        }
    }
    /** Caller holds the cleanup mutex; one durable checkpoint per original prevents replay after a crash. */
    suspend fun apply(c:Context,p:JSONObject,tree:String,plan:List<Placement>,operations:CleanupOperations,progress:(String)->Unit):CleanupOutcome{
        valid(c,p);check(p.optString("local_state") in setOf("approved","awaiting_phone"));val remote=request(c,p);check(remote.getString("state") in setOf("approved","awaiting_phone"))
        // Acquire the server's non-cancellable applying state before any local mutation.
        report(c,p,"applying",message="Telefonul aplică planul aprobat.")
        var moved=0;var copied=0;var skipped=0;val errors=mutableListOf<String>();operations.begin()
        try{
            for(entry in plan){valid(c,p);currentCoroutineContext().ensureActive();request(c,p);verifyOriginal(c,p,entry)
                val result=if(entry.file.gallery)operations.organizeGallery(listOf(entry),progress)else operations.organizeDocuments(Uri.parse(tree),listOf(entry),false,progress)
                moved+=result.moved;copied+=result.copied;skipped+=result.skipped;errors+=result.errors
                p.put("moved",moved).put("copied",copied).put("skipped",skipped);save(c,p);indexDocuments(c,operations.journal())
            }
            val message="$moved originale mutate pe telefon · $copied copii (originalele păstrate) · $skipped nemodificate. "+errors.take(3).joinToString(" ")
            report(c,p,"complete",moved,copied,skipped,message)
        }catch(e:Exception){
            p.put("local_state","error").put("moved",moved).put("copied",copied).put("skipped",skipped).put("message",(e.message?:"Mutare întreruptă.")+" Verifică jurnalul din Curățenie înainte de o analiză nouă.");save(c,p)
            withContext(NonCancellable){runCatching{sendResult(c,p)}};throw e
        }
        CleanupAuto.status(c,p.getString("message"));return CleanupOutcome(moved,copied,skipped,errors)
    }
}
class OrganizerPlanWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){
    override suspend fun doWork():Result {
        val c=applicationContext;val id=inputData.getString("id")?:return Result.failure();var p=OrganizerPlans.read(c,id)?:return Result.failure()
        if(!CleanupAuto.valid(c,p.getString("owner"),p.getString("grant_id")))return Result.success()
        return CleanupAnalysisLock.mutex.withLock{
            p=OrganizerPlans.read(c,id)?:return@withLock Result.success()
            try{
                if(p.optString("local_state")=="applying"){OrganizerPlans.report(c,p,"error",p.optInt("moved"),p.optInt("copied"),p.optInt("skipped"),"Telefonul a fost întrerupt în timpul mutării. Verifică jurnalul; nu repetăm automat operațiile.");return@withLock Result.success()}
                if(p.optString("local_state")!="approved")return@withLock Result.success()
                val(tree,plan)=OrganizerPlans.placements(c,p);check(plan.none{it.file.gallery})
                OrganizerPlans.apply(c,p,tree,plan,CleanupOperations(c)){CleanupAuto.status(c,it)};Result.success()
            }catch(e:CancellationException){throw e}catch(e:Exception){CleanupAuto.status(c,e.message.orEmpty());if(p.optString("local_state")=="approved"&&(e is java.io.IOException||e is FileSync.Failure&&(e.code>=500||e.code in setOf(408,429,423))))Result.retry()else{if(p.optString("local_state")=="approved")runCatching{OrganizerPlans.report(c,p,"error",message=e.message?:"Planul nu poate fi aplicat; cere o analiză nouă.")};Result.success()}}
        }
    }
}
