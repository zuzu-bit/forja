package com.forja.app.feature.cleanup

import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Durable v4 coordinator shared by phone actions and authenticated website commands. */
internal object OrganizerJobs {
    const val TAG="forja-organizer-v4"
    private fun owner()=checkNotNull(FileSync.owner()){"Conectează-te în contul tău."}
    private fun valid(c:Context,j:JSONObject){check(FileSync.owner()==j.getString("owner")){"Contul s-a schimbat."};OrganizerLedger(c).use{db->check(db.job(j.getString("id"))?.optString("phase") !in setOf("cancelled","paused")){"Organizare oprită."}};if(j.optString("grant").isNotBlank())check(CleanupAuto.valid(c,j.getString("owner"),j.getString("grant"))){"Acordul pentru organizare a fost retras."}}
    fun sourceId(c:Context,source:String,tree:String):String {val p=c.getSharedPreferences("organizer_sources_v4",Context.MODE_PRIVATE);val key=source+":"+FileSync.sha(tree.toByteArray());return p.getString(key,null)?:UUID.randomUUID().toString().also{check(p.edit().putString(key,it).commit())}}
    fun needsSourceGrant(c:Context,source:String,tree:String,folder:String,automatic:Boolean):Boolean {val p=CleanupAuto.prefs(c);return !CleanupAuto.enabled(c)||p.getInt("protocol",0)<4||!p.getBoolean(source,false)||source=="files"&&p.getString("tree","")!=tree||automatic&&!p.getBoolean("organize",false)}
    suspend fun authorizeSource(c:Context,source:String,tree:String,folder:String,automatic:Boolean){require(source in setOf("photos","files"));OrganizerSelection.path(folder);val p=CleanupAuto.prefs(c);val inherit=CleanupAuto.enabled(c);CleanupAuto.activate(c,inherit&&p.getBoolean("photos",false)||source=="photos",inherit&&p.getBoolean("files",false)||source=="files",if(source=="files")tree else if(inherit)p.getString("tree","").orEmpty()else "",automatic||inherit&&p.getBoolean("organize",false))}
    suspend fun start(c:Context,source:String,tree:String,folder:String,recursive:Boolean,count:Int,destination:String,upload:Boolean,automatic:Boolean,requestId:String=UUID.randomUUID().toString()):String=withContext(Dispatchers.IO){
        require(source in setOf("photos","files")&&count in 0..1000);require(source!="files"||tree.isNotBlank())
        val uid=owner();val path=OrganizerSelection.path(folder);val dest=OrganizerSelection.path(destination);require(dest.isNotBlank())
        val config=JSONObject().put("source",source).put("tree",tree).put("folder",path).put("recursive",recursive).put("count",count).put("destination",dest).put("upload",upload).put("automatic",automatic)
        val signature=FileSync.sha(config.toString().toByteArray());val j=config.put("id",requestId).put("owner",uid).put("device",CleanupAuto.device(c)).put("created",System.currentTimeMillis()).put("phase","queued").put("revision",0).put("request_signature",signature).put("command_id",UUID.randomUUID().toString()).put("message","Pregătesc sursa aleasă.")
        OrganizerLedger(c).use{it.create(j)};schedule(c,requestId);requestId
    }
    fun snapshot(c:Context,id:String):JSONObject?=OrganizerLedger(c).use{db->db.job(id)?.takeIf{it.optString("owner")==FileSync.owner()}?.let{j->
        val counts=db.counts(id);val rows=db.items(id,50);val totals=db.totals(id)
        j.put("total",counts.values.sum()).put("pending",counts.filterKeys{it !in setOf("moved","needs_review","skipped")}.values.sum()).put("moved",counts["moved"]?:0).put("needs_review",counts["needs_review"]?:0).put("failed",counts["failed_retryable"]?:0)
            .put("analyzed",totals.first).put("uploaded",totals.second).put("items",JSONArray(rows.map{r->JSONObject().put("id",r.getString("id")).put("name",r.getJSONObject("file").optString("name")).put("state",r.optString("state")).put("destination",r.optString("destination")).put("error",r.optString("error"))}))
    }}
    fun recent(c:Context):List<JSONObject> =FileSync.owner()?.let{uid->OrganizerLedger(c).use{it.jobs(uid)}.mapNotNull{snapshot(c,it.getString("id"))}}?:emptyList()
    fun continueJob(c:Context,id:String){OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(j.getString("owner")==owner());check(j.optString("phase")!="cancelled"){"Această selecție a fost anulată."};j.put("apply_confirmed",true).put("phase","queued");db.save(j,true)};schedule(c,id)}
    suspend fun nextJob(c:Context,id:String,count:Int,requestId:String=UUID.randomUUID().toString()):String {val j=checkNotNull(snapshot(c,id));check(j.getString("phase") in setOf("complete","needs_review","cancelled"));return start(c,j.getString("source"),j.getString("tree"),j.getString("folder"),j.getBoolean("recursive"),count,j.getString("destination"),j.getBoolean("upload"),j.getBoolean("automatic"),requestId)}
    fun cancel(c:Context,id:String){OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(j.getString("owner")==owner());j.put("phase","cancelled").put("message","Oprit. Rezultatele verificate sunt păstrate.");db.save(j,true);db.release(id)};WorkManager.getInstance(c).cancelUniqueWork("$TAG:$id")}
    fun galleryUris(c:Context,id:String):List<Uri> =OrganizerLedger(c).use{db->val j=db.job(id)?:return@use emptyList();if(j.optString("owner")!=FileSync.owner()||j.optString("source")!="photos")return@use emptyList();val uris=db.states(id,setOf("ready","applying"),500).map{Uri.parse(it.getJSONObject("file").getString("uri"))};j.put("requested_gallery",JSONArray(uris.map{it.toString()}));db.save(j);uris}
    fun allowGallery(c:Context,id:String,allowed:Boolean){OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(j.getString("owner")==owner());val requested=j.optJSONArray("requested_gallery")?:JSONArray();j.put("gallery_grants",if(allowed)requested else JSONArray()).remove("requested_gallery");j.put("phase",if(allowed)"queued"else "needs_permission").put("apply_confirmed",allowed);db.save(j)};if(allowed)schedule(c,id)}
    fun schedule(c:Context,id:String,append:Boolean=false){WorkManager.getInstance(c).enqueueUniqueWork("$TAG:$id",if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<OrganizerJobWorker>().setInputData(workDataOf("id" to id)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build())}
    private suspend fun api(c:Context,j:JSONObject,suffix:String="",body:JSONObject?=null):JSONObject=FileSync.request(c,j.getString("owner"),"/v2/organizer/devices/${j.getString("device")}/jobs"+suffix,if(body==null)"GET"else "POST",body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false)
    private suspend fun register(c:Context,db:OrganizerLedger,j:JSONObject){
        if(j.optBoolean("registered"))return
        valid(c,j);val p=CleanupAuto.prefs(c)
        check(!needsSourceGrant(c,j.getString("source"),j.getString("tree"),j.getString("folder"),j.getBoolean("automatic"))){"Confirmă accesul sursei din aplicație."}
        j.put("grant",p.getString("grant",null));db.save(j);valid(c,j)
        val device=FileSync.request(c,j.getString("owner"),"/v2/cleanup/devices/${j.getString("device")}",requireEnabled=false)
        val payload=JSONObject().put("id",j.getString("id")).put("grant_id",j.getString("grant")).put("revision",device.getInt("revision")).put("source",j.getString("source")).put("source_id",sourceId(c,j.getString("source"),if(j.getString("source")=="files")j.getString("tree")else "")).put("scope",JSONObject().put("folder",j.getString("folder")).put("recursive",j.getBoolean("recursive"))).put("destination",j.getString("destination")).put("mode",if(j.getBoolean("upload"))"online"else "local").put("auto_apply",j.getBoolean("automatic")).put("ai_consent",j.getBoolean("upload"))
        val remote=api(c,j,body=payload);val revision=remote.getInt("revision")
        api(c,j,"/${j.getString("id")}/command",JSONObject().put("request_id",j.getString("command_id")).put("revision",revision).put("action","continue").put("count",j.getInt("count")))
        j.put("registered",true);db.save(j)
    }
    private fun receipt(j:JSONObject,row:JSONObject,state:String)=JSONObject().put("grant_id",j.getString("grant")).put("receipts",JSONArray().put(JSONObject().put("id",row.getString("id")).put("operation_id",row.getString("operation_id")).put("state",state).put("source_sha256",row.getString("sha")).apply{if(row.has("destination"))put("destination",row.getString("destination"));if(row.has("target_sha256"))put("target_sha256",row.getString("target_sha256"));if(row.has("finding"))put("reason",row.getJSONObject("finding").optString("reason","Conținut analizat pe telefon.").take(300));if(row.optBoolean("copy_received"))put("file_id",row.getString("copy_id"));if(row.optString("error").isNotBlank())put("message",row.optString("error").take(300))}))
    private suspend fun report(c:Context,j:JSONObject,row:JSONObject,state:String){
        if(!j.optBoolean("registered")||!row.optBoolean("published"))return
        val key="receipt:"+state;val body=row.optJSONObject(key)?:receipt(j,row,state).also{row.put(key,it)}
        if(state=="applying")row.put("state","applying")
        OrganizerLedger(c).use{it.saveItem(j.getString("id"),row)}
        api(c,j,"/${j.getString("id")}/receipts",body)
        if(state in setOf("moved","needs_review","copied_pending_removal","skipped"))row.put("reported",true)
        if(state=="failed_retryable")row.put("attempt_failed",true)
        OrganizerLedger(c).use{it.saveItem(j.getString("id"),row)}
    }
    private suspend fun approve(c:Context,j:JSONObject,row:JSONObject){
        if(j.getBoolean("automatic"))return
        val remote=api(c,j,"/${j.getString("id")}")
        api(c,j,"/${j.getString("id")}/approve",JSONObject().put("request_id",row.getString("operation_id")).put("revision",remote.getInt("revision")).put("items",JSONArray().put(JSONObject().put("id",row.getString("id")).put("destination",row.getString("destination")))).put("confirm",true))
    }
    private suspend fun publishItem(c:Context,db:OrganizerLedger,j:JSONObject,row:JSONObject){
        val f=CleanupReportCodec.file(row.getJSONObject("file"));valid(c,j)
        if(!row.optBoolean("published")){
            val item=JSONObject().put("id",row.getString("id")).put("original_id",row.getString("original_id")).put("version",row.getString("sha")).put("sha256",row.getString("sha")).put("name",f.name.take(200)).put("folder",f.path.take(240)).put("media_type",f.mime.ifBlank{"application/octet-stream"}).put("bytes",f.bytes).put("modified_at",f.modified.coerceAtLeast(0))
            row.optJSONObject("extraction")?.let{item.put("extraction",it)}
            api(c,j,"/${j.getString("id")}/items",JSONObject().put("grant_id",j.getString("grant")).put("items",JSONArray().put(item)))
            val batch=api(c,j,"/${j.getString("id")}/batch",JSONObject().put("request_id",row.optString("batch_request",row.getString("copy_id"))).put("grant_id",j.getString("grant")).put("limit",1).put("ids",JSONArray().put(row.getString("id"))))
            check((batch.optJSONArray("items")?:JSONArray()).let{a->(0 until a.length()).any{val v=a.opt(it);if(v is JSONObject)v.optString("id")==row.getString("id")else v==row.getString("id")}}){"Lotul este ocupat de o operație anterioară."}
            row.put("published",true);db.saveItem(j.getString("id"),row);report(c,j,row,"analyzed")
        }
    }
    private suspend fun upload(c:Context,db:OrganizerLedger,j:JSONObject,row:JSONObject){
        val f=CleanupReportCodec.file(row.getJSONObject("file"));publishItem(c,db,j,row)
        if(row.optBoolean("copy_received"))return
        report(c,j,row,"upload_pending");row.put("state","upload_pending");db.saveItem(j.getString("id"),row)
        val bytes=checkNotNull(c.contentResolver.openInputStream(Uri.parse(f.uri))).use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536);while(true){valid(c,j);currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;check(out.size()+n<=FileSync.MAX_BYTES){"Fișierul depășește limita copiei online (25 MB)."};out.write(buffer,0,n)};out.toByteArray()}
        check(FileSync.sha(bytes)==row.getString("sha")){"Original schimbat înainte de upload."}
        val headers=mapOf("X-Device-ID" to j.getString("device"),"X-File-Kind" to if(f.photo)"photo"else "file","X-File-Name" to FileSync.header(f.name.take(200)),"X-File-Folder" to FileSync.header(f.path.trim('/').take(120)),"X-Media-Type" to f.mime.ifBlank{"application/octet-stream"},"X-File-Sha256" to row.getString("sha"),"X-Organizer-Job" to j.getString("id"),"X-Organizer-Item" to row.getString("id"),"X-Original-ID" to row.getString("original_id"),"X-Original-Version" to row.getString("sha"))
        val result=FileSync.request(c,j.getString("owner"),"/v2/files/${row.getString("copy_id")}","PUT",bytes,headers,false)
        check(result.optString("sha256")==row.getString("sha")&&result.optLong("bytes")==bytes.size.toLong()){"Copia nu a fost confirmată integral."};valid(c,j)
        row.put("copy_received",true).put("state","uploaded").put("copy_expires",result.optLong("expires_at"));db.saveItem(j.getString("id"),row);report(c,j,row,"uploaded")
    }
    private suspend fun classify(c:Context,j:JSONObject,row:JSONObject){
        val f=CleanupReportCodec.file(row.getJSONObject("file"))
        if(j.getBoolean("upload")){
            FileSync.request(c,j.getString("owner"),"/insights/api/organizer-analysis","POST",JSONObject().put("device",j.getString("device")).put("job",j.getString("id")).put("ids",JSONArray().put(row.getString("id"))).put("consent",true).toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)
            val data=api(c,j,"/${j.getString("id")}/items?ids=${row.getString("id")}");val items=data.getJSONArray("items");val remote=(0 until items.length()).map{items.getJSONObject(it)}.first{it.getString("id")==row.getString("id")}
            val analysis=remote.optJSONObject("analysis")?:remote.optJSONObject("proposal")?:error("Analiza online este încă în lucru.")
            val destination=remote.optString("destination")
            check(destination.isNotBlank()){"Analiza necesită verificarea ta."}
            row.put("finding",analysis).put("destination",OrganizerSelection.path(destination))
        }else CleanupContent(c).use{content->
            val finding=if(f.photo){val bitmap=CleanupCatalog(c).bitmap(Uri.parse(f.uri));try{content.image(f,bitmap)}finally{bitmap.recycle()}}else content.document(f)
            check(!finding.subject.isNullOrBlank()){"Nu există suficiente dovezi pentru un dosar sigur."}
            row.put("finding",JSONObject().put("reason",finding.reason).put("partial",finding.partial)).put("destination",OrganizerSelection.path(j.getString("destination")+"/"+finding.subject))
        }
    }
    suspend fun process(c:Context,id:String):Boolean=withContext(Dispatchers.IO){CleanupAnalysisLock.mutex.withLock {
        OrganizerLedger(c).use{db->val j=db.job(id)?:return@withLock false
            if(FileSync.owner()!=j.getString("owner"))return@withLock false
            // An upload/receipt retry never re-executes an already completed mutation.
            for(row in db.receipts(id))report(c,j,row,row.getString("state"))
            if(j.optString("phase") in setOf("cancelled","paused","complete"))return@withLock false
            if(needsSourceGrant(c,j.getString("source"),j.getString("tree"),j.getString("folder"),j.getBoolean("automatic"))){j.put("phase","needs_access").put("message","Confirmă accesul sursei din aplicație.");db.save(j);return@withLock false}
            valid(c,j);register(c,db,j);j.put("phase","inventory");db.save(j);OrganizerInventory.scan(c,db,j){valid(c,j)};db.select(j)
            val preparedStates=setOf("pending","analyzed","upload_pending","uploaded","failed_retryable")
            val readyBefore=db.counts(id)["ready"]?:0
            val preparing=if(readyBefore>=500)emptyList()else db.states(id,preparedStates,if(j.getBoolean("upload"))3 else 10)
            for(row in preparing){
                valid(c,j);currentCoroutineContext().ensureActive()
                try {
                    if(row.optBoolean("attempt_failed")){row.put("operation_id",UUID.randomUUID().toString()).put("batch_request",UUID.randomUUID().toString()).put("published",false).put("attempt_failed",false);row.keys().asSequence().filter{it.startsWith("receipt:")}.toList().forEach{row.remove(it)};db.saveItem(id,row)}
                    val f=CleanupReportCodec.file(row.getJSONObject("file"));j.put("phase","analyzing").put("message","Analizez ${f.name}");db.save(j)
                    if(row.optString("sha").isBlank()){row.put("sha",OrganizerInventory.hash(c,f.uri){valid(c,j)});row.put("state","analyzed");db.saveItem(id,row)}
                    if(j.getBoolean("upload")){
                        if(!row.has("extraction")){row.put("extraction",OrganizerContentEvidence.extractEvidence(c,f,row.getString("sha")));db.saveItem(id,row)}
                        j.put("phase","uploading");db.save(j);upload(c,db,j,row);classify(c,j,row)
                    }else{classify(c,j,row);publishItem(c,db,j,row)}
                    row.put("state","ready");db.saveItem(id,row)
                    if(j.getBoolean("automatic"))report(c,j,row,"ready")
                }catch(e:CancellationException){throw e}catch(e:Exception){
                    val retry=e is java.io.IOException||e is FileSync.Failure&&e.code in setOf(408,429,500,502,503,504)
                    row.put("state",if(retry)"failed_retryable"else "needs_review").put("error",e.message?.take(300)?:"Fișierul trebuie verificat.");db.saveItem(id,row);runCatching{report(c,j,row,row.getString("state"))};if(retry)throw e
                }
            }
            if(db.allPublished(id)&&!j.optBoolean("inventory_sent")){
                api(c,j,"/$id/items",JSONObject().put("grant_id",j.getString("grant")).put("items",JSONArray()).put("inventory_complete",true).put("inventory_total",db.count(id)));j.put("inventory_sent",true);db.save(j)
            }
            var counts=db.counts(id);val toPrepare=counts.filterKeys{it in preparedStates}.values.sum();val ready=counts["ready"]?:0
            if(j.getString("source")=="photos" && toPrepare>0 && ready<500){j.put("phase","queued");db.save(j);return@withLock true}
            if(!j.getBoolean("automatic")&&!j.optBoolean("apply_confirmed")&&ready>0){j.put("phase","ready").put("message","Destinațiile sunt pregătite. Continuă pentru aplicare.");db.save(j);return@withLock false}
            val grants=j.optJSONArray("gallery_grants")?:JSONArray();val allowed=(0 until grants.length()).map{grants.getString(it)}.toSet()
            val moving=db.states(id,setOf("ready","applying","copied_pending_removal"),20)
            if(j.getString("source")=="photos"&&moving.any{it.getJSONObject("file").getString("uri") !in allowed}){j.put("phase","needs_permission").put("message","Confirmă modificarea grupului de fotografii în Android.");db.save(j);return@withLock false}
            for(row in moving){
                valid(c,j);currentCoroutineContext().ensureActive()
                try{
                    if(row.getString("state")=="ready"){
                        if(OrganizerMover.alreadyThere(c,j,row){valid(c,j)}){row.put("state","skipped").put("error","Fișierul este deja în dosarul potrivit.");db.saveItem(id,row);report(c,j,row,"skipped");continue}
                        approve(c,j,row);report(c,j,row,"ready")
                    }
                    j.put("phase","applying").put("message","Organizez "+row.getJSONObject("file").optString("name"));db.save(j)
                    if(row.getString("state")!="copied_pending_removal")report(c,j,row,"applying")
                    val result=OrganizerMover.apply(c,db,j,row){valid(c,j)};result.put("reported",false);db.saveItem(id,result);report(c,j,result,result.getString("state"))
                }catch(e:CancellationException){throw e}catch(e:Exception){
                    val current=db.item(id,row.getString("id"))?:row
                    if(current.optString("state")=="moved")throw e
                    if(e is java.io.IOException||e is FileSync.Failure&&e.code in setOf(408,429,500,502,503,504))throw e
                    current.put("state","needs_review").put("error",e.message?.take(300)?:"Rezultatul trebuie verificat.").put("reported",false);db.saveItem(id,current);runCatching{report(c,j,current,"needs_review")}
                }
            }
            counts=db.counts(id);val pending=counts.filterKeys{it !in setOf("moved","needs_review","skipped")}.values.sum()
            j.put("phase",if(pending>0)"queued"else if((counts["needs_review"]?:0)>0)"needs_review"else "complete").put("message","${counts["moved"]?:0} mutate · ${counts["needs_review"]?:0} de verificat · $pending rămase");db.save(j);pending>0
        }
    }}
    suspend fun pollRemote(c:Context,uid:String,grant:String,device:String){
        val response=FileSync.request(c,uid,"/v2/organizer/devices/$device/jobs",requireEnabled=false);check(CleanupAuto.valid(c,uid,grant));val jobs=response.optJSONArray("jobs")?:return
        for(i in 0 until jobs.length()){
            val r=jobs.getJSONObject(i);val cmd=r.optJSONObject("command")?:continue;val action=cmd.optString("action");val id=r.getString("id")
            val db=OrganizerLedger(c);try{val old=db.job(id)
                if(action in setOf("pause","cancel")){if(old!=null){old.put("phase",if(action=="cancel")"cancelled"else "paused");db.save(old,true);db.release(id)};continue}
                if(action!="continue"||cmd.optString("status")=="complete"||old!=null&&old.optString("remote_command")==cmd.optString("request_id"))continue
                if(old!=null){old.put("remote_command",cmd.getString("request_id")).put("phase","queued").put("apply_confirmed",true);db.save(old,true);schedule(c,id);continue}
                val scope=r.getJSONObject("scope");val tree=if(r.getString("source")=="files")CleanupAuto.prefs(c).getString("tree","").orEmpty()else ""
                val j=JSONObject().put("id",id).put("owner",uid).put("device",device).put("grant",grant).put("source",r.getString("source")).put("tree",tree).put("folder",scope.getString("folder")).put("recursive",scope.getBoolean("recursive")).put("count",cmd.getInt("count")).put("destination",r.getString("destination")).put("upload",r.optString("mode")=="online").put("automatic",r.getBoolean("auto_apply")).put("registered",true).put("remote_command",cmd.getString("request_id")).put("command_id",cmd.getString("request_id")).put("created",System.currentTimeMillis()).put("phase","queued").put("revision",0).put("request_signature",FileSync.sha(r.toString().toByteArray()))
                db.create(j);schedule(c,id)
            }finally{db.close()}
        }
    }
}
class OrganizerJobWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork():Result {val id=inputData.getString("id")?:return Result.failure();return try{if(OrganizerJobs.process(applicationContext,id)){OrganizerJobs.schedule(applicationContext,id,true);Result.success()}else Result.success()}catch(e:CancellationException){throw e}catch(e:Exception){Result.retry()}}}
