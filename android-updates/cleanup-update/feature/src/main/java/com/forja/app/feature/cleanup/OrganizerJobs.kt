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
    private fun retryable(e:Exception)=if(e is FileSync.Failure)e.code in setOf(408,429,500,502,503,504)else e is java.io.IOException
    private fun owner()=checkNotNull(FileSync.owner()){"Conectează-te în contul tău."}
    private fun valid(c:Context,j:JSONObject){check(FileSync.owner()==j.getString("owner")){"Contul s-a schimbat."};OrganizerLedger(c).use{db->check(db.job(j.getString("id"))?.optString("phase") !in setOf("cancelled","paused")){"Organizare oprită."}};if(j.optString("grant").isNotBlank())check(CleanupAuto.valid(c,j.getString("owner"),j.getString("grant"))){"Acordul pentru organizare a fost retras."}}
    fun sourceId(c:Context,source:String,tree:String):String {val p=c.getSharedPreferences("organizer_sources_v4",Context.MODE_PRIVATE);val key=source+":"+FileSync.sha(tree.toByteArray());return p.getString(key,null)?:UUID.randomUUID().toString().also{check(p.edit().putString(key,it).commit())}}
    fun needsSourceGrant(c:Context,source:String,tree:String,folder:String,automatic:Boolean):Boolean {val p=CleanupAuto.prefs(c);return !CleanupAuto.enabled(c)||p.getInt("protocol",0)<4||!p.getBoolean(source,false)||source=="files"&&p.getString("tree","")!=tree||automatic&&!p.getBoolean("organize",false)}
    suspend fun authorizeSource(c:Context,source:String,tree:String,folder:String,automatic:Boolean){require(source in setOf("photos","files"));OrganizerSelection.path(folder);val p=CleanupAuto.prefs(c);val inherit=CleanupAuto.enabled(c);CleanupAuto.activate(c,inherit&&p.getBoolean("photos",false)||source=="photos",inherit&&p.getBoolean("files",false)||source=="files",if(source=="files")tree else if(inherit)p.getString("tree","").orEmpty()else "",automatic||inherit&&p.getBoolean("organize",false))}
    suspend fun start(c:Context,source:String,tree:String,folder:String,recursive:Boolean,count:Int,destination:String,upload:Boolean,automatic:Boolean,requestId:String=UUID.randomUUID().toString(),resumeFrom:String?=null):String { val expectedOwner=owner(); return withContext(Dispatchers.IO){
        require(source in setOf("photos","files")&&count in 0..1000);require(source!="files"||tree.isNotBlank())
        val uid=expectedOwner;check(FileSync.owner()==uid){"Contul s-a schimbat."};val path=OrganizerSelection.path(folder);val dest=OrganizerSelection.path(destination);require(dest.isNotBlank())
        val config=JSONObject().put("source",source).put("tree",tree).put("folder",path).put("recursive",recursive).put("count",count).put("destination",dest).put("upload",upload).put("automatic",automatic)
        val signature=FileSync.sha(config.toString().toByteArray());val j=config.put("id",requestId).put("owner",uid).put("device",CleanupAuto.device(c)).put("created",System.currentTimeMillis()).put("phase","queued").put("revision",0).put("request_signature",signature).put("command_id",UUID.randomUUID().toString()).put("message","Pregătesc sursa aleasă.")
        check(FileSync.owner()==uid){"Contul s-a schimbat."};OrganizerLedger(c).use{db->db.create(j);if(resumeFrom!=null)db.seedRemaining(resumeFrom,j)};schedule(c,requestId);requestId
    }}
    fun snapshot(c:Context,id:String):JSONObject?=OrganizerLedger(c).use{db->db.job(id)?.takeIf{it.optString("owner")==FileSync.owner()}?.let{j->
        val counts=db.counts(id);val rows=db.items(id,50);val totals=db.totals(id)
        j.put("total",counts.values.sum()).put("pending",counts.filterKeys{it !in setOf("moved","needs_review","skipped")}.values.sum()).put("moved",counts["moved"]?:0).put("needs_review",counts["needs_review"]?:0).put("failed",counts["failed_retryable"]?:0)
            .put("analyzed",totals.first).put("uploaded",totals.second).put("items",JSONArray(rows.map{r->JSONObject().put("id",r.getString("id")).put("name",r.getJSONObject("file").optString("name")).put("state",r.optString("state")).put("destination",r.optString("destination")).put("error",r.optString("error")).put("finding",r.optJSONObject("finding")?:JSONObject.NULL).put("duplicate",r.optJSONObject("duplicate")?:JSONObject.NULL)}))
    }}
    fun recent(c:Context):List<JSONObject> =FileSync.owner()?.let{uid->OrganizerLedger(c).use{it.jobs(uid)}.mapNotNull{snapshot(c,it.getString("id"))}}?:emptyList()
    suspend fun continueJob(c:Context,id:String):String {
        val expected=owner();val old=checkNotNull(snapshot(c,id));check(old.getString("owner")==expected)
        check(old.optString("phase")!="cancelled"){"Această selecție a fost anulată."}
        if(old.optString("grant").isNotBlank()&&!CleanupAuto.valid(c,expected,old.getString("grant"))){
            check(!needsSourceGrant(c,old.getString("source"),old.getString("tree"),old.getString("folder"),old.getBoolean("automatic"))){"Confirmă accesul sursei."}
            cancel(c,id)
            return start(c,old.getString("source"),old.getString("tree"),old.getString("folder"),old.getBoolean("recursive"),old.getInt("count"),old.getString("destination"),old.getBoolean("upload"),old.getBoolean("automatic"),resumeFrom=id)
        }
        OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(FileSync.owner()==expected);j.put("apply_confirmed",true).put("phase","queued");if(old.optString("phase")=="paused"){j.remove("inventory_sent");queueControl(j,"continue",j.getInt("count"))};db.save(j,true)};schedule(c,id);return id
    }
    suspend fun nextJob(c:Context,id:String,count:Int,requestId:String=UUID.randomUUID().toString()):String {
        val expected=owner();require(count in 0..1000)
        OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(j.getString("owner")==expected&&FileSync.owner()==expected)
            if(j.optString("next_request")==requestId)return id
            check(j.getString("phase") in setOf("complete","needs_review","cancelled"))
            if(j.getString("phase")=="cancelled")return start(c,j.getString("source"),j.getString("tree"),j.getString("folder"),j.getBoolean("recursive"),count,j.getString("destination"),j.getBoolean("upload"),j.getBoolean("automatic"),requestId)
            db.resetInventory(j);j.put("count",count).put("next_request",requestId).put("apply_confirmed",false).put("phase","queued");queueControl(j,"continue",count,requestId);db.save(j,true)
        };schedule(c,id);return id
    }
    private fun queueControl(j:JSONObject,action:String,count:Int,requestId:String=UUID.randomUUID().toString()){j.put("control",JSONObject().put("request_id",requestId).put("action",action).put("count",count))}
    fun cancel(c:Context,id:String){OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(j.getString("owner")==owner());queueControl(j,"cancel",0);j.put("phase","cancelled").put("message","Oprit. Rezultatele verificate sunt păstrate.");db.save(j,true);db.release(id)};WorkManager.getInstance(c).enqueueUniqueWork("$TAG:$id",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<OrganizerJobWorker>().setInputData(workDataOf("id" to id)).addTag(TAG).build())}
    fun galleryUris(c:Context,id:String):List<Uri> =OrganizerLedger(c).use{db->val j=db.job(id)?:return@use emptyList();if(j.optString("owner")!=FileSync.owner()||j.optString("source")!="photos")return@use emptyList();val uris=db.states(id,setOf("ready","applying"),500).map{Uri.parse(it.getJSONObject("file").getString("uri"))};j.put("requested_gallery",JSONArray(uris.map{it.toString()}));db.save(j);uris}
    fun allowGallery(c:Context,id:String,allowed:Boolean){OrganizerLedger(c).use{db->val j=checkNotNull(db.job(id));check(j.getString("owner")==owner());val requested=j.optJSONArray("requested_gallery")?:JSONArray();j.put("gallery_grants",if(allowed)requested else JSONArray()).remove("requested_gallery");j.put("phase",if(allowed)"queued"else "needs_permission").put("apply_confirmed",allowed);db.save(j)};if(allowed)schedule(c,id)}
    fun schedule(c:Context,id:String,append:Boolean=false){WorkManager.getInstance(c).enqueueUniqueWork("$TAG:$id",if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<OrganizerJobWorker>().setInputData(workDataOf("id" to id)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build())}
    private suspend fun api(c:Context,j:JSONObject,suffix:String="",body:JSONObject?=null):JSONObject=FileSync.request(c,j.getString("owner"),"/v2/organizer/devices/${j.getString("device")}/jobs"+suffix,if(body==null)"GET"else "POST",body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false)
    private suspend fun remoteValid(c:Context,j:JSONObject){
        valid(c,j);val remote=api(c,j,"/${j.getString("id")}")
        if(remote.optString("grant_id")!=j.getString("grant")||remote.optString("state") in setOf("paused","cancelled","access_needed")){
            j.put("phase",when(remote.optString("state")){"paused"->"paused";"cancelled"->"cancelled";else->"needs_access"}).put("message","Organizarea a fost oprită din site.")
            OrganizerLedger(c).use{it.save(j,true)};error("Organizarea a fost oprită din site.")
        }
        valid(c,j)
    }
    private suspend fun register(c:Context,db:OrganizerLedger,j:JSONObject){
        if(j.optBoolean("registered"))return
        valid(c,j);val p=CleanupAuto.prefs(c)
        check(!needsSourceGrant(c,j.getString("source"),j.getString("tree"),j.getString("folder"),j.getBoolean("automatic"))){"Confirmă accesul sursei din aplicație."}
        j.put("grant",p.getString("grant",null));db.save(j);valid(c,j)
        val device=FileSync.request(c,j.getString("owner"),"/v2/cleanup/devices/${j.getString("device")}",requireEnabled=false)
        val payload=JSONObject().put("id",j.getString("id")).put("grant_id",j.getString("grant")).put("revision",device.getInt("revision")).put("source",j.getString("source")).put("source_id",sourceId(c,j.getString("source"),if(j.getString("source")=="files")j.getString("tree")else "")).put("scope",JSONObject().put("folder",j.getString("folder")).put("recursive",j.getBoolean("recursive"))).put("destination",j.getString("destination")).put("mode",if(j.getBoolean("upload"))"online"else "local").put("auto_apply",j.getBoolean("automatic")).put("ai_consent",j.getBoolean("upload"))
        val remote=api(c,j,body=payload);val revision=remote.getInt("revision")
        api(c,j,"/${j.getString("id")}/command",JSONObject().put("request_id",j.getString("command_id")).put("revision",revision).put("action","continue").put("count",j.getInt("count")))
        j.put("registered",true).put("remote_command",j.getString("command_id"));db.save(j)
    }
    private suspend fun flushControl(c:Context,db:OrganizerLedger,j:JSONObject){
        val control=j.optJSONObject("control")?:return
        if(!j.optBoolean("registered")){j.remove("control");db.save(j);return}
        if(!control.has("revision")){val remote=api(c,j,"/${j.getString("id")}");control.put("revision",remote.getInt("revision"));db.save(j)}
        api(c,j,"/${j.getString("id")}/command",control)
        j.put("remote_command",control.getString("request_id")).remove("control");db.save(j)
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
        val remote=api(c,j,"/${j.getString("id")}")
        var current=api(c,j,"/${j.getString("id")}/items?ids=${row.getString("id")}").getJSONArray("items").getJSONObject(0)
        if(j.getBoolean("upload")&&!row.optBoolean("intent_saved")&&!current.optBoolean("received")){
            if(!row.optBoolean("copy_refresh"))row.put("copy_id",UUID.randomUUID().toString()).put("copy_received",false).put("thumbnail_received",false).put("copy_refresh",true)
            row.remove("receipt:ready");row.remove("receipt:uploaded");row.remove("receipt:upload_pending")
            OrganizerLedger(c).use{db->db.saveItem(j.getString("id"),row);upload(c,db,j,row)}
            current=api(c,j,"/${j.getString("id")}/items?ids=${row.getString("id")}").getJSONArray("items").getJSONObject(0)
        }
        if(j.getBoolean("upload")&&current.optBoolean("received")){
            val file=current.getJSONObject("file");check(file.getString("sha256")==row.getString("sha")){"Copia nu corespunde originalului."}
            row.put("copy_id",file.getString("id")).put("copy_received",true).remove("copy_refresh")
            OrganizerLedger(c).use{it.saveItem(j.getString("id"),row)}
        }
        current.optJSONObject("duplicate")?.let{row.put("duplicate",it)}
        if(current.optString("destination").isNotBlank()){
            val destination=OrganizerSelection.path(current.getString("destination"))
            if(destination!=row.optString("destination")){check(!row.optBoolean("intent_saved")){"O mutare începută trebuie reconciliată."};row.remove("receipt:ready");row.remove("receipt:applying");row.remove("approval_request");row.remove("approval_ack")}
            row.put("destination",destination)
        }
        if(j.getBoolean("automatic"))return
        if(j.optString("mode")=="manual"){check(current.optString("state")=="ready"){"Aprobă destinația acestui fișier pe site."};return}
        val previous=row.optJSONObject("approval_request")
        val body=if(previous!=null&&(!row.optBoolean("approval_ack")||previous.optInt("revision")==remote.getInt("revision")))previous else JSONObject().put("request_id",UUID.randomUUID().toString()).put("revision",remote.getInt("revision")).put("items",JSONArray().put(JSONObject().put("id",row.getString("id")).put("destination",row.getString("destination")))).put("confirm",true)
        row.put("approval_request",body);OrganizerLedger(c).use{it.saveItem(j.getString("id"),row)}
        api(c,j,"/${j.getString("id")}/approve",body);row.put("approval_ack",true);OrganizerLedger(c).use{it.saveItem(j.getString("id"),row)}
    }
    private suspend fun publishItem(c:Context,db:OrganizerLedger,j:JSONObject,row:JSONObject){
        val f=CleanupReportCodec.file(row.getJSONObject("file"));valid(c,j)
        if(!row.optBoolean("published")){
            val item=JSONObject().put("id",row.getString("id")).put("original_id",row.getString("original_id")).put("version",row.getString("sha")).put("sha256",row.getString("sha")).put("name",f.name.take(200)).put("folder",f.path.take(240)).put("media_type",f.mime.ifBlank{"application/octet-stream"}).put("bytes",f.bytes).put("modified_at",f.modified.coerceAtLeast(0))
            row.optJSONObject("extraction")?.let{item.put("extraction",it)}
            api(c,j,"/${j.getString("id")}/items",JSONObject().put("grant_id",j.getString("grant")).put("items",JSONArray().put(item)))
            val batch=api(c,j,"/${j.getString("id")}/batch",JSONObject().put("request_id",row.optString("batch_request",row.getString("copy_id"))).put("grant_id",j.getString("grant")).put("limit",1).put("ids",JSONArray().put(row.getString("id"))))
            check((batch.optJSONArray("items")?:JSONArray()).let{a->(0 until a.length()).any{val v=a.opt(it);if(v is JSONObject)v.optString("id")==row.getString("id")else v==row.getString("id")}}){"Lotul este ocupat de o operație anterioară."}
            row.put("published",true).remove("extraction");db.saveItem(j.getString("id"),row);report(c,j,row,"analyzed")
        }
    }
    private suspend fun upload(c:Context,db:OrganizerLedger,j:JSONObject,row:JSONObject){
        val f=CleanupReportCodec.file(row.getJSONObject("file"));publishItem(c,db,j,row)
        if(row.optBoolean("copy_received")){thumbnail(c,db,j,row,f);return}
        val refreshing=row.optBoolean("copy_refresh")
        if(!refreshing){report(c,j,row,"upload_pending");row.put("state","upload_pending")};db.saveItem(j.getString("id"),row)
        val bytes=checkNotNull(c.contentResolver.openInputStream(Uri.parse(f.uri))).use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536);while(true){valid(c,j);currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;check(out.size()+n<=FileSync.MAX_BYTES){"Fișierul depășește limita copiei online (25 MB)."};out.write(buffer,0,n)};out.toByteArray()}
        check(FileSync.sha(bytes)==row.getString("sha")){"Original schimbat înainte de upload."}
        val headers=mapOf("X-Device-ID" to j.getString("device"),"X-File-Kind" to if(j.getString("source")=="photos")"photo"else "file","X-File-Name" to FileSync.header(f.name.take(200)),"X-File-Folder" to FileSync.header(f.path.trim('/').take(120)),"X-Media-Type" to f.mime.ifBlank{"application/octet-stream"},"X-File-Sha256" to row.getString("sha"),"X-Organizer-Job" to j.getString("id"),"X-Organizer-Item" to row.getString("id"),"X-Original-ID" to row.getString("original_id"),"X-Original-Version" to row.getString("sha"))
        val result=FileSync.request(c,j.getString("owner"),"/v2/files/${row.getString("copy_id")}","PUT",bytes,headers,false)
        check(result.optString("sha256")==row.getString("sha")&&result.optLong("bytes")==bytes.size.toLong()){"Copia nu a fost confirmată integral."};valid(c,j)
        row.put("copy_received",true).put("state",if(refreshing)"ready"else "uploaded").put("copy_expires",result.optLong("expires_at"));db.saveItem(j.getString("id"),row);thumbnail(c,db,j,row,f);if(!refreshing)report(c,j,row,"uploaded")
    }
    private suspend fun thumbnail(c:Context,db:OrganizerLedger,j:JSONObject,row:JSONObject,f:CleanFile){
        if(!f.photo||row.optBoolean("thumbnail_received"))return
        valid(c,j);val bitmap=CleanupCatalog(c).bitmap(Uri.parse(f.uri),512)
        val bytes=try{java.io.ByteArrayOutputStream().use{out->bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,out);out.toByteArray()}}finally{bitmap.recycle()}
        check(bytes.size<=160*1024){"Previzualizarea imaginii este prea mare."};valid(c,j)
        // Bind the derived preview to the exact original content confirmed for this item.
        check(OrganizerInventory.hash(c,f.uri){valid(c,j)}==row.getString("sha")){"Originalul s-a schimbat înainte de previzualizare."}
        FileSync.request(c,j.getString("owner"),"/v2/files/${row.getString("copy_id")}/thumbnail","PUT",bytes,mapOf("X-Device-ID" to j.getString("device")),false)
        row.put("thumbnail_received",true);db.saveItem(j.getString("id"),row)
    }
    private suspend fun classify(c:Context,j:JSONObject,row:JSONObject){
        val f=CleanupReportCodec.file(row.getJSONObject("file"))
        if(j.getBoolean("upload")){
            FileSync.request(c,j.getString("owner"),"/insights/api/organizer-analysis","POST",JSONObject().put("device",j.getString("device")).put("job",j.getString("id")).put("ids",JSONArray().put(row.getString("id"))).put("consent",true).toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)
            val data=api(c,j,"/${j.getString("id")}/items?ids=${row.getString("id")}");val items=data.getJSONArray("items");val remote=(0 until items.length()).map{items.getJSONObject(it)}.first{it.getString("id")==row.getString("id")}
            val analysis=remote.optJSONObject("analysis")?:remote.optJSONObject("proposal")?:error("Analiza online este încă în lucru.")
            val destination=remote.optString("destination")
            check(destination.isNotBlank()){"Analiza necesită verificarea ta."}
            row.put("finding",analysis).put("destination",OrganizerSelection.path(destination)).put("content_expires_at",System.currentTimeMillis()+86400000L);remote.optJSONObject("duplicate")?.let{row.put("duplicate",it)}
        }else CleanupContent(c).use{content->
            val finding=if(f.photo){val bitmap=CleanupCatalog(c).bitmap(Uri.parse(f.uri));try{content.image(f,bitmap)}finally{bitmap.recycle()}}else content.document(f)
            check(!finding.subject.isNullOrBlank()){"Nu există suficiente dovezi pentru un dosar sigur."}
            row.put("finding",JSONObject().put("reason",finding.reason).put("partial",finding.partial)).put("destination",OrganizerSelection.path(j.getString("destination")+"/"+finding.subject))
        }
    }
    suspend fun process(c:Context,id:String):Boolean=withContext(Dispatchers.IO){CleanupAnalysisLock.mutex.withLock {
        OrganizerLedger(c).use{db->val j=db.job(id)?:return@withLock false
            if(FileSync.owner()!=j.getString("owner"))return@withLock false
            if(j.optString("phase")=="cancelled"||j.optString("grant").isNotBlank()&&!CleanupAuto.valid(c,j.getString("owner"),j.getString("grant"))){
                for(row in db.states(id,setOf("applying","copied_pending_removal"),50)){
                    val result=OrganizerMover.reconcile(c,db,j,row);result.put("reported",false);db.saveItem(id,result)
                }
            }
            // An upload/receipt retry never re-executes an already completed mutation.
            for(row in db.receipts(id))report(c,j,row,row.getString("state"))
            flushControl(c,db,j)
            if(j.optString("phase") in setOf("cancelled","paused","complete"))return@withLock false
            if(needsSourceGrant(c,j.getString("source"),j.getString("tree"),j.getString("folder"),j.getBoolean("automatic"))){j.put("phase","needs_access").put("message","Confirmă accesul sursei din aplicație.");db.save(j);return@withLock false}
            if(j.optString("grant").isNotBlank()&&!CleanupAuto.valid(c,j.getString("owner"),j.getString("grant"))){j.put("phase","needs_access").put("message","Acordul s-a schimbat. Verifică accesul pentru a continua într-o selecție nouă.");db.save(j);return@withLock false}
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
                        if(!row.has("extraction")){row.put("extraction",OrganizerContentEvidence.extractEvidence(c,f,row.getString("sha"))).put("content_expires_at",System.currentTimeMillis()+86400000L);db.saveItem(id,row)}
                        j.put("phase","uploading");db.save(j);upload(c,db,j,row)
                        if(j.optString("mode")=="manual"){row.put("state","awaiting_review");db.saveItem(id,row);continue}
                        classify(c,j,row)
                    }else{classify(c,j,row);publishItem(c,db,j,row)}
                    row.put("state","ready");db.saveItem(id,row)
                    if(j.getBoolean("automatic"))report(c,j,row,"ready")
                }catch(e:CancellationException){throw e}catch(e:Exception){
                    val retry=retryable(e)
                    row.put("state",if(retry)"failed_retryable"else "needs_review").put("error",e.message?.take(300)?:"Fișierul trebuie verificat.");db.saveItem(id,row);runCatching{report(c,j,row,row.getString("state"))};if(retry)throw e
                }
            }
            if(db.allPublished(id)&&!j.optBoolean("inventory_sent")){
                api(c,j,"/$id/items",JSONObject().put("grant_id",j.getString("grant")).put("items",JSONArray()).put("inventory_complete",true).put("inventory_total",db.count(id)).put("inventory_unavailable",db.unavailable(id)));j.put("inventory_sent",true);db.save(j)
            }
            if(j.optString("mode")=="manual")for(row in db.states(id,setOf("awaiting_review"),100)){
                val items=api(c,j,"/$id/items?ids=${row.getString("id")}").getJSONArray("items")
                if(items.length()>0){val current=items.getJSONObject(0);if(current.optString("state")=="ready"&&current.optString("destination").isNotBlank()){row.put("destination",OrganizerSelection.path(current.getString("destination"))).put("state","ready");db.saveItem(id,row);j.put("apply_confirmed",true)}}
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
                        approve(c,j,row)
                        if(OrganizerMover.alreadyThere(c,j,row){valid(c,j)}){row.put("state","skipped").put("error","Fișierul este deja în dosarul potrivit.");db.saveItem(id,row);report(c,j,row,"skipped");continue}
                        report(c,j,row,"ready")
                    }
                    j.put("phase","applying").put("message","Organizez "+row.getJSONObject("file").optString("name"));db.save(j)
                    if(row.getString("state")!="copied_pending_removal")report(c,j,row,"applying")
                    val result=OrganizerMover.apply(c,db,j,row,checkpoint={remoteValid(c,j)}){valid(c,j)};result.put("reported",false);db.saveItem(id,result);report(c,j,result,result.getString("state"))
                }catch(e:CancellationException){throw e}catch(e:Exception){
                    val current=db.item(id,row.getString("id"))?:row
                    val stopped=db.job(id)?.optString("phase")
                    if(stopped in setOf("paused","cancelled","needs_access")){
                        if(stopped!="paused"&&current.optString("state") in setOf("applying","copied_pending_removal")){val result=OrganizerMover.reconcile(c,db,j,current);report(c,j,result,result.getString("state"))}
                        return@withLock false
                    }
                    if(current.optString("state")=="moved")throw e
                    if(retryable(e))throw e
                    current.put("state","needs_review").put("error",e.message?.take(300)?:"Rezultatul trebuie verificat.").put("reported",false);db.saveItem(id,current);runCatching{report(c,j,current,"needs_review")}
                }
            }
            counts=db.counts(id);val pending=counts.filterKeys{it !in setOf("moved","needs_review","skipped")}.values.sum()
            val waiting=(counts["awaiting_review"]?:0)>0&&pending==(counts["awaiting_review"]?:0)
            j.put("phase",if(waiting)"ready"else if(pending>0)"queued"else if((counts["needs_review"]?:0)>0)"needs_review"else "complete").put("message","${counts["moved"]?:0} mutate · ${counts["needs_review"]?:0} de verificat · $pending rămase");db.save(j);pending>0&&!waiting
        }
    }}
    suspend fun pollRemote(c:Context,uid:String,grant:String,device:String){
        OrganizerLedger(c).use{db->for(j in db.jobs(uid)){val id=j.getString("id");if(j.has("control")||db.receipts(id).isNotEmpty()||j.optString("phase") in setOf("queued","inventory","analyzing","uploading","applying","failed_retryable"))schedule(c,id)}}
        val response=FileSync.request(c,uid,"/v2/organizer/devices/$device/jobs",requireEnabled=false);check(CleanupAuto.valid(c,uid,grant));val jobs=response.optJSONArray("jobs")?:return
        for(i in 0 until jobs.length()){
            val r=jobs.getJSONObject(i);val cmd=r.optJSONObject("command")?:continue;val action=cmd.optString("action");val id=r.getString("id")
            val db=OrganizerLedger(c);try{val old=db.job(id)
                if(action in setOf("pause","cancel")){if(old!=null){old.put("phase",if(action=="cancel")"cancelled"else "paused");db.save(old,true);if(action=="cancel")db.release(id)};continue}
                if(action!="continue"||cmd.optString("status")=="complete")continue
                if(old!=null&&old.optString("remote_command")==cmd.optString("request_id")){
                    if(old.optString("mode")=="manual"&&old.optString("phase")=="ready"&&old.optLong("sync_revision")!=r.optLong("sync_revision")){old.put("sync_revision",r.optLong("sync_revision")).put("phase","queued");db.save(old);schedule(c,id)}
                    continue
                }
                if(old!=null){if(old.optString("phase")=="cancelled"||old.has("control"))continue
                    if(old.optString("phase") in setOf("complete","needs_review")){db.resetInventory(old);old.put("count",cmd.getInt("count"))}
                    old.remove("inventory_sent");old.put("remote_command",cmd.getString("request_id")).put("phase","queued").put("apply_confirmed",true);db.save(old,true);schedule(c,id);continue}
                val scope=r.getJSONObject("scope");val tree=if(r.getString("source")=="files")CleanupAuto.prefs(c).getString("tree","").orEmpty()else ""
                if(r.optString("source_id").isNotBlank()&&r.getString("source_id")!=sourceId(c,r.getString("source"),tree))continue
                if(r.optString("grant_id")!=grant)continue
                val j=JSONObject().put("id",id).put("owner",uid).put("device",device).put("grant",grant).put("source",r.getString("source")).put("tree",tree).put("folder",scope.getString("folder")).put("recursive",scope.getBoolean("recursive")).put("count",cmd.getInt("count")).put("destination",r.getString("destination")).put("mode",r.optString("mode")).put("upload",r.optString("mode")!="local").put("automatic",r.getBoolean("auto_apply")).put("registered",true).put("remote_command",cmd.getString("request_id")).put("command_id",cmd.getString("request_id")).put("created",System.currentTimeMillis()).put("phase","queued").put("revision",0).put("request_signature",FileSync.sha(r.toString().toByteArray()))
                db.create(j);schedule(c,id)
            }finally{db.close()}
        }
    }
}
class OrganizerJobWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork():Result {
    val id=inputData.getString("id")?:return Result.failure();val owner=FileSync.owner()?:return Result.success()
    val job=currentCoroutineContext()[Job]!!;val auth=com.google.firebase.auth.FirebaseAuth.getInstance();val listener=com.google.firebase.auth.FirebaseAuth.AuthStateListener{if(it.currentUser?.uid!=owner)job.cancel()};auth.addAuthStateListener(listener)
    return try{if(OrganizerJobs.process(applicationContext,id)){OrganizerJobs.schedule(applicationContext,id,true);Result.success()}else Result.success()}catch(e:CancellationException){throw e}catch(e:Exception){
        val retry=if(e is FileSync.Failure)e.code in setOf(408,429,500,502,503,504)else e is java.io.IOException
        OrganizerLedger(applicationContext).use{db->db.job(id)?.takeIf{it.optString("owner")==owner&&FileSync.owner()==owner&&it.optString("phase") !in setOf("cancelled","paused") }?.let{j->j.put("phase",if(retry)"failed_retryable"else if(e is SecurityException||e is FileSync.Failure&&e.code in setOf(401,403,423))"needs_access"else "needs_review").put("message",e.message?.take(300)?:"Organizarea trebuie reluată.");runCatching{db.save(j)}}}
        if(retry)Result.retry()else Result.success()
    }finally{auth.removeAuthStateListener(listener)}
}}
