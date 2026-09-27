package com.forja.app.feature.cleanup

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.google.firebase.auth.FirebaseAuth

internal object PhoneCleanupPaths {
    // Gallery apps commonly use the final folder name as the album title. Flatten
    // a semantic hierarchy into a distinctive album; keep document subfolders.
    fun destination(raw:String,gallery:Boolean,video:Boolean=false):String {
        val topic=raw.removePrefix("Pictures/FORJA/").removePrefix("Movies/FORJA/").removePrefix("FORJA/").trim('/')
        val parts=topic.split('/').map{it.trim()}.filter{it.isNotBlank()}
        require(parts.isNotEmpty()&&parts.size<=8&&parts.none{it=="."||it==".."||it.any{ch->ch<' '||ch in "\\:*?\"<>|"}}){"Destinație nevalidă."}
        return if(gallery)(if(video)"Movies/"else "Pictures/")+parts.joinToString(" · ").take(150)+"/"else parts.joinToString("/")
    }
    fun alreadyThere(current:String,target:String)=current.trim('/')==target.trim('/')
    fun proposals(files:List<CleanFile>,content:Map<String,ContentFinding>):List<Placement> = files.mapNotNull{f->
        val finding=content[f.uri]?:return@mapNotNull null
        val target=destination("FORJA/"+(finding.subject?:"De verificat"),f.gallery,f.video)
        if(alreadyThere(f.path,target))null else Placement(f,target,finding.reason)
    }
}

class PhoneCleanupViewModel(app:Application):AndroidViewModel(app) {
    private val c=app.applicationContext
    private val prefs=c.getSharedPreferences("cleanup_scope_v14",Context.MODE_PRIVATE)
    internal val operations=CleanupOperations(c)
    var limit by mutableIntStateOf(prefs.getInt("limit",50).takeIf{it in 0..1000}?:50);private set
    var source by mutableStateOf("photos");private set
    var tree by mutableStateOf(prefs.getString("phone_tree","").orEmpty());private set
    var photoFolder by mutableStateOf("");private set
    var galleryFolders by mutableStateOf<List<String>>(emptyList());private set
    var foldersLoading by mutableStateOf(false);private set
    internal var organizerJob by mutableStateOf<JSONObject?>(null);private set
    internal var organizerHistory by mutableStateOf<List<JSONObject>>(emptyList());private set
    private var organizerId:String?=null
    private var organizerOwner=FileSync.owner()
    private var organizerWork:Job?=null
    private var organizerEpoch=0L
    private var organizerReadEpoch=0L
    private var pendingOrganizer:String?=null
    private var pendingOrganizerOwner:String?=null
    private val ownerListener=FirebaseAuth.AuthStateListener{accountChanged()}
    var manual by mutableStateOf(false);private set
    fun mode(value:Boolean){if(busy)return;manual=value;resetReport()}
    var online by mutableStateOf(true)
    var busy by mutableStateOf(false);private set
    var analyzing by mutableStateOf(false);private set
    var stage by mutableStateOf("");private set
    var notice by mutableStateOf("");private set
    internal var report by mutableStateOf<CleanReport?>(null);private set
    internal var plan by mutableStateOf<List<Placement>>(emptyList());private set
    var selected by mutableStateOf<Set<String>>(emptySet());private set
    internal var transfer by mutableStateOf(TransferSummary());private set
    internal var rows by mutableStateOf(operations.journal());private set
    var issues by mutableStateOf<List<String>>(emptyList());private set
    var tab by mutableStateOf("organize")
    private var scanJob:Job?=null
    private var webPlan:JSONObject?=null
    private var batch:String?=null
    private var batchOwner:String?=null
    private var pendingAction=""
    private var pendingPlan:List<Placement> = emptyList()
    private var pendingFiles:List<CleanFile> = emptyList()
    private var permissionGroups=ArrayDeque<List<Uri>>()
    private var undoRows:List<JSONObject> = emptyList()
    init {
        FirebaseAuth.getInstance().addAuthStateListener(ownerListener)
        viewModelScope.launch {
            FileSync.owner()?.let{owner->withContext(Dispatchers.IO){CleanupTransferDb(c).use{it.latest(owner)}}?.let{batch=it;batchOwner=owner}}
            CleanupTransfer.changed.collect{
                val id=batch
                if(id!=null&&batchOwner==FileSync.owner())transfer=withContext(Dispatchers.IO){CleanupTransferDb(c).use{it.summary(id)}}
                else transfer=TransferSummary()
            }
        }
    }
    override fun onCleared(){FirebaseAuth.getInstance().removeAuthStateListener(ownerListener);super.onCleared()}
    fun scope(n:Int){if(busy)return;require(n in 0..15000);limit=n;prefs.edit().putInt("limit",n).apply();resetReport()}
    fun source(value:String){if(busy)return;source=value;resetReport()}
    fun folder(uri:Uri){tree=uri.toString();prefs.edit().putString("phone_tree",tree).apply();source("files")}
    fun photoFolder(value:String){if(busy)return;photoFolder=value.trim('/');resetReport()}
    /** Account changes never restore another account's result or pending write approval. */
    fun accountChanged(){
        val owner=FileSync.owner()
        if(owner==organizerOwner)return
        organizerOwner=owner;organizerEpoch++;organizerReadEpoch++;organizerWork?.cancel();scanJob?.cancel()
        organizerId=null;organizerJob=null;organizerHistory=emptyList();pendingOrganizer=null;pendingOrganizerOwner=null
        pendingAction="";permissionGroups.clear();batch=null;batchOwner=null;transfer=TransferSummary()
        busy=false;analyzing=false;stage="";resetReport()
    }
    suspend fun refreshOrganizer(){
        accountChanged();val owner=FileSync.owner();val readEpoch=++organizerReadEpoch
        val recent=withContext(Dispatchers.IO){OrganizerJobs.recent(c)}
        if(FileSync.owner()!=owner||readEpoch!=organizerReadEpoch)return
        organizerHistory=recent
        if(organizerId==null||recent.none{it.optString("id")==organizerId})organizerId=recent.firstOrNull()?.optString("id")
        val id=organizerId
        val value=id?.let{withContext(Dispatchers.IO){OrganizerJobs.snapshot(c,it)}}
        if(FileSync.owner()==owner&&readEpoch==organizerReadEpoch&&id==organizerId&&value?.toString()!=organizerJob?.toString())organizerJob=value
    }
    fun viewOrganizer(id:String){if(busy)return;organizerId=id;viewModelScope.launch{refreshOrganizer()}}
    fun loadGalleryFolders(){
        if(foldersLoading)return
        foldersLoading=true
        viewModelScope.launch{try{
            galleryFolders=withContext(Dispatchers.IO){
                check(Build.VERSION.SDK_INT>=29){"Alege un dosar din Fișiere pe acest telefon."}
                val paths=sortedSetOf<String>()
                c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("relative_path"),if(Build.VERSION.SDK_INT>=30)"is_trashed=0 AND is_pending=0"else null,null,null)?.use{cursor->
                    while(cursor.moveToNext()){ensureActive();cursor.getString(0)?.trim('/')?.takeIf{it.isNotBlank()}?.let{paths+=it}}
                }?:throw IllegalStateException("Galeria nu este accesibilă.")
                paths.toList()
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){notice=e.message.orEmpty()}finally{foldersLoading=false}}
    }
    fun organizerNeedsGrant(existingJob:Boolean):Boolean {
        val job=if(existingJob)organizerJob else null
        return OrganizerJobs.needsSourceGrant(c,job?.optString("source")?:source,job?.optString("tree")?:tree,job?.optString("folder")?:photoFolder,job?.optBoolean("automatic")?:true)
    }
    fun organizerGrantScope(existingJob:Boolean):Pair<Boolean,String> {
        val job=if(existingJob)organizerJob else null
        val kind=job?.optString("source")?:source
        val p=CleanupAuto.prefs(c);val enabled=CleanupAuto.enabled(c)
        val photos=kind=="photos"||enabled&&p.getBoolean("photos",false)
        val folder=if(kind=="files")job?.optString("tree")?:tree else if(enabled&&p.getBoolean("files",false))p.getString("tree","").orEmpty()else ""
        return photos to folder
    }
    fun authorizeOrganizerSource(existingJob:Boolean,expectedOwner:String?,onReady:()->Unit){
        if(busy)return
        if(expectedOwner==null||expectedOwner!=FileSync.owner()){notice="Contul s-a schimbat. Confirmă din nou accesul.";return}
        val job=if(existingJob)organizerJob else null
        val s=job?.optString("source")?:source;val t=job?.optString("tree")?:tree
        val f=job?.optString("folder")?:if(s=="photos")photoFolder else "";val automatic=job?.optBoolean("automatic")?:true
        val epoch=++organizerEpoch;busy=true;notice=""
        organizerWork=viewModelScope.launch{try{
            OrganizerJobs.authorizeSource(c,s,t,f,automatic)
            check(FileSync.owner()==expectedOwner){"Contul s-a schimbat."}
            if(epoch==organizerEpoch){busy=false;onReady()}
        }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==organizerEpoch)notice=e.message?:"Accesul nu a putut fi salvat."}finally{if(epoch==organizerEpoch){busy=false;organizerWork=null}}}
    }
    fun startOrganizer(expectedOwner:String?){
        if(busy)return
        if(expectedOwner!=FileSync.owner()){notice="Contul s-a schimbat. Confirmă din nou selecția.";return}
        if(expectedOwner==null){notice="Conectează-te pentru a păstra progresul în cont.";return}
        if(source=="files"&&tree.isBlank()){notice="Alege dosarul de organizat.";return}
        val s=source;val t=tree;val f=if(s=="photos")photoFolder else "";val n=limit;val upload=online
        val requestId=UUID.randomUUID().toString()
        val epoch=++organizerEpoch;busy=true;notice=""
        organizerWork=viewModelScope.launch{try{
            val id=OrganizerJobs.start(c,s,t,f,true,n,"FORJA",upload,true,requestId)
            check(FileSync.owner()==expectedOwner){"Contul s-a schimbat."}
            organizerId=id;refreshOrganizer()
        }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==organizerEpoch)notice=e.message?:"Nu am putut porni organizarea."}finally{if(epoch==organizerEpoch){busy=false;organizerWork=null}}}
    }
    fun nextOrganizer(){
        val current=organizerJob?:return;if(busy)return
        val owner=FileSync.owner();val id=current.getString("id");val n=limit
        val epoch=++organizerEpoch;busy=true;notice=""
        organizerWork=viewModelScope.launch{try{
            val next=OrganizerJobs.nextJob(c,id,n,UUID.randomUUID().toString());check(FileSync.owner()==owner){"Contul s-a schimbat."};organizerId=next;refreshOrganizer()
        }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==organizerEpoch)notice=e.message?:"Nu am putut pregăti următorul lot."}finally{if(epoch==organizerEpoch){busy=false;organizerWork=null}}}
    }
    fun cancelOrganizer(){
        val id=organizerJob?.optString("id")?:return
        viewModelScope.launch{try{withContext(Dispatchers.IO){OrganizerJobs.cancel(c,id)};refreshOrganizer()}catch(e:CancellationException){throw e}catch(e:Exception){notice=e.message.orEmpty()}}
    }
    fun continueOrganizer(launch:(IntentSender)->Unit){
        val job=organizerJob?:return;if(busy)return
        val id=job.getString("id");val owner=FileSync.owner();val epoch=++organizerEpoch;busy=true;notice=""
        organizerWork=viewModelScope.launch{try{
            val uris=withContext(Dispatchers.IO){OrganizerJobs.galleryUris(c,id)}
            check(FileSync.owner()==owner){"Contul s-a schimbat."}
            if(uris.isNotEmpty()){
                val sender=operations.writeRequest(uris)
                check(sender!=null){"Mutarea în Galerie cere Android 11 sau mai nou. Poți folosi dosarul din Fișiere."}
                pendingOrganizer=id;pendingOrganizerOwner=owner;launch(sender)
            }else{val resumed=withContext(Dispatchers.IO){OrganizerJobs.continueJob(c,id)};check(FileSync.owner()==owner){"Contul s-a schimbat."};organizerId=resumed;refreshOrganizer();busy=false}
        }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==organizerEpoch){notice=e.message.orEmpty();busy=false}}finally{if(epoch==organizerEpoch)organizerWork=null}}
    }
    private fun resetReport(){webPlan=null;report=null;plan=emptyList();selected=emptySet();notice="";issues=emptyList()}
    fun select(uri:String,value:Boolean){if(webPlan!=null){notice="Modifică selecția în site și creează un plan nou.";return};selected=if(value)selected+uri else selected-uri}
    fun destination(uri:String,value:String){if(webPlan!=null){notice="Destinațiile acestui plan sunt aprobate în site.";return};try{plan=plan.map{p->if(p.file.uri==uri)Placement(p.file,PhoneCleanupPaths.destination(if(p.file.gallery)(if(p.file.video)"Movies/FORJA/"else "Pictures/FORJA/")+value else "FORJA/$value",p.file.gallery,p.file.video),"Dosar ales de tine")else p}}catch(e:Exception){notice=e.message.orEmpty()}}
    fun selectAll(value:Boolean){if(webPlan!=null)return;selected=if(value)(if(tab=="organize")plan.map{it.file.uri}else report?.duplicateCopies.orEmpty().map{it.uri}).toSet()else emptySet()}
    fun tab(value:String){if(webPlan!=null&&value!="organize")return;tab=value;selected=if(value=="organize")plan.map{it.file.uri}.toSet()else emptySet()}
    internal fun selectedPlan()=plan.filter{it.file.uri in selected}
    fun error(value:String){notice=value}
    fun loadWebPlan(id:String){
        if(busy)return
        busy=true;viewModelScope.launch{try{
            val p=withContext(Dispatchers.IO){checkNotNull(OrganizerPlans.read(c,id))}
            val(folder,proposals)=OrganizerPlans.placements(c,p);check(proposals.all{it.file.gallery})
            webPlan=p;source="photos";tree=folder;plan=proposals;selected=proposals.map{it.file.uri}.toSet();tab="organize";batch=p.getString("run");batchOwner=p.getString("owner")
            report=CleanReport(proposals.map{it.file},emptyList(),emptyList(),proposals,emptyList(),emptyList(),emptyList(),0,0,emptyMap())
            transfer=withContext(Dispatchers.IO){CleanupTransferDb(c).use{it.summary(batch!!)}}
            stage="Plan aprobat din laptop · ${proposals.size} fotografii";notice="Apasă Aplică pe telefon, apoi confirmă solicitarea Android. Se mută fotografiile originale în albumele alese pe site."
        }catch(e:Exception){notice=e.message.orEmpty()}finally{busy=false}}
    }
    fun loadAutomatic(id:String,kind:String){
        if(busy)return
        viewModelScope.launch{
            try{
                val uid=checkNotNull(FileSync.owner());val saved=withContext(Dispatchers.IO){checkNotNull(CleanupAutoStore(c).read(id))}
                check(saved.getString("owner")==uid&&saved.getLong("created")>System.currentTimeMillis()-86400000L)
                val restored=CleanupReportCodec.report(saved.getJSONObject("reports").getJSONObject(kind))
                webPlan=null;manual=saved.optString("mode")=="manual";source=kind;tree=saved.optString("tree");report=restored;plan=if(manual)ManualInventory.placements(restored.files)else PhoneCleanupPaths.proposals(restored.files,restored.content);selected=if(manual)emptySet()else plan.map{it.file.uri}.toSet();tab="organize";batch=id;batchOwner=uid
                transfer=withContext(Dispatchers.IO){CleanupTransferDb(c).use{it.summary(id)}}
                notice="Propuneri pregătite automat. Verifică destinațiile înainte de a le aplica pe telefon.";stage="${restored.files.size} fișiere analizate automat"
            }catch(e:Exception){notice=e.message?:"Raportul nu mai este disponibil."}
        }
    }
    fun analyze(){
        if(busy)return
        val uid=FileSync.owner()
        if(online&&uid==null){notice="Conectează-te în FORJA cu același cont folosit pe site sau alege analiza doar pe telefon.";return}
        if(source=="files"&&tree.isBlank()){notice="Alege dosarul din telefon pe care îl organizăm.";return}
        val upload=online;val manualMode=manual;val selectedSource=source;val selectedTree=tree
        busy=true;analyzing=true;resetReport();stage="Inventariez selecția…";tab="organize";batch=null;transfer=TransferSummary()
        CleanupScope.limit=limit;CleanupScope.includeVideos=false
        scanJob=viewModelScope.launch {
            try {
                val queue:suspend(List<CleanFile>)->Unit={files->
                    if(upload&&files.isNotEmpty()){
                        check(FileSync.owner()==uid){"Contul s-a schimbat înaintea transferului."}
                        val device=prefs.getString("cleanup_device",null)?:UUID.randomUUID().toString().also{check(prefs.edit().putString("cleanup_device",it).commit())}
                        // Replace the old independent rescan with a frozen queue for this action.
                        if(FileSync.prefs(c).getBoolean("enabled",false))FileSync.stop(c)
                        val id=CleanupTransferDb(c).use{it.create(uid!!,device,files)}
                        withContext(Dispatchers.Main){batch=id;batchOwner=uid;transfer=TransferSummary(id,files.size)}
                        CleanupTransfer.changed.value++;CleanupTransfer.schedule(c,id)
                        viewModelScope.launch(Dispatchers.IO){if(CleanupTransfer.drain(c,id))CleanupTransfer.schedule(c,id)}
                    }
                }
                val result=CleanupAnalysisLock.mutex.withLock{if(manualMode)ManualInventory.scan(c,selectedSource,selectedTree.takeIf{it.isNotBlank()}?.let(Uri::parse),queue)else withContext(CleanupInventory(queue)){CleanupScan(c).scan(selectedSource,selectedTree.takeIf{it.isNotBlank()}?.let(Uri::parse),true,false,true,{s->viewModelScope.launch{stage=s}},{})}}
                report=result
                // The retained legacy report suppressed every file under a FORJA
                // folder. Rebuild from content so those originals can be organized too.
                plan=if(manualMode)ManualInventory.placements(result.files)else PhoneCleanupPaths.proposals(result.files,result.content)
                selected=if(manualMode)emptySet()else plan.map{it.file.uri}.toSet()
                stage="${if(manualMode)"Inventar pregătit"else "Analiză terminată"} · ${result.files.size} fișiere"
                notice=if(manualMode)"Alege fișierele și editează dosarele destinație. Nu s-a analizat conținutul."else if(plan.isEmpty())"Nu sunt mutări propuse pentru această selecție. Poți verifica duplicatele."else "${plan.size} mutări propuse. Verifică destinațiile și aplică pe telefon."
            }catch(e:CancellationException){stage="Analiză oprită. Transferul selecției deja pregătite continuă.";throw e}
            catch(e:Exception){notice=e.message?:"Analiza nu a reușit."}
            finally{busy=false;analyzing=false;scanJob=null}
        }
    }
    fun stopAnalysis(){scanJob?.cancel()}
    fun retryTransfer(){val id=batch?:return;if(batchOwner!=FileSync.owner())return;viewModelScope.launch(Dispatchers.IO){CleanupTransferDb(c).use{it.retry(id)};CleanupTransfer.schedule(c,id);CleanupTransfer.drain(c,id)}}
    fun stopTransfer(){val id=batch?:return;if(batchOwner!=FileSync.owner())return;viewModelScope.launch(Dispatchers.IO){CleanupTransfer.cancel(c,id)}}
    val copiesReady get()=webPlan!=null||batch==null||batchOwner==FileSync.owner()&&(transfer.complete||!transfer.active)

    /** Prepare an immutable, reviewed plan before requesting Android's write consent. */
    fun prepare(action:String,launch:(IntentSender)->Unit){
        if(busy)return
        if(action!="undo"&&webPlan==null&&!copiesReady){notice="Așteaptă confirmarea copiilor pe site înainte de modificarea originalelor.";return}
        busy=true;notice="";pendingAction=action
        viewModelScope.launch {
            try {
                pendingPlan=if(action=="organize")selectedPlan().filter{!PhoneCleanupPaths.alreadyThere(it.file.path,it.destination)}else emptyList()
                if(action=="organize")check(pendingPlan.isNotEmpty()&&pendingPlan.all{it.destination.isNotBlank()}){"Alege un dosar destinație diferit pentru fișierele selectate."}
                pendingFiles=if(action=="duplicates")operations.verifyDuplicateSelection(checkNotNull(report),selected)else emptyList()
                undoRows=if(action=="undo")operations.journal().filter{it.optString("state") in setOf("moved","copied","trashed")}else emptyList()
                val uris=when(action){"organize"->pendingPlan.filter{it.file.gallery}.map{Uri.parse(it.file.uri)};"duplicates"->emptyList();else->undoRows.filter{it.optString("kind")=="media"&&it.optString("state")!="trashed"}.map{Uri.parse(it.getString("source"))}}
                permissionGroups=ArrayDeque(uris.distinct().chunked(500))
                nextPermission(launch)
            }catch(e:Exception){notice=e.message.orEmpty();busy=false;pendingAction=""}
        }
    }
    private fun nextPermission(launch:(IntentSender)->Unit){
        if(permissionGroups.isNotEmpty()){
            val sender=operations.writeRequest(permissionGroups.removeFirst())
            if(sender!=null){launch(sender);return}
            if(Build.VERSION.SDK_INT<30&&source=="photos"){busy=false;notice="Pentru mutare sigură în Galerie este necesar Android 11 sau mai nou. Poți organiza folderul prin Fișiere.";return}
            nextPermission(launch);return
        }
        if(pendingAction=="duplicates"&&pendingFiles.any{it.gallery}){
            val sender=operations.trashRequest(pendingFiles,true)
            if(sender==null){notice="Coșul galeriei nu este disponibil pe acest telefon.";busy=false;return}
            pendingAction="trashResult";launch(sender);return
        }
        if(pendingAction=="undo"&&undoRows.any{it.optString("state")=="trashed"}){
            val uris=undoRows.filter{it.optString("state")=="trashed"}.map{Uri.parse(it.getString("source"))}
            if(Build.VERSION.SDK_INT>=30){pendingAction="untrashResult";launch(android.provider.MediaStore.createTrashRequest(c.contentResolver,uris,false).intentSender);return}
        }
        execute()
    }
    fun permissionResult(ok:Boolean,launch:(IntentSender)->Unit){
        pendingOrganizer?.let{id->
            val owner=pendingOrganizerOwner;pendingOrganizer=null;pendingOrganizerOwner=null
            if(owner!=FileSync.owner()){busy=false;notice="Contul s-a schimbat. Aprobarea nu a fost aplicată.";return}
            val epoch=++organizerEpoch
            organizerWork=viewModelScope.launch{try{
                val resumed=withContext(Dispatchers.IO){OrganizerJobs.allowGallery(c,id,ok);if(ok)OrganizerJobs.continueJob(c,id)else id}
                check(FileSync.owner()==owner){"Contul s-a schimbat."};organizerId=resumed
                refreshOrganizer();if(!ok)notice="Mutările așteaptă confirmarea ta în Android."
            }catch(e:CancellationException){throw e}catch(e:Exception){if(epoch==organizerEpoch)notice=e.message.orEmpty()}finally{if(epoch==organizerEpoch){busy=false;organizerWork=null}}}
            return
        }
        if(pendingAction.isBlank()){busy=false;notice="Ecranul s-a redeschis. Confirmă din nou din organizare.";return}
        if(!ok){busy=false;pendingAction="";notice="Modificarea nu a fost confirmată în Android.";return}
        try{nextPermission(launch)}catch(e:Exception){busy=false;notice=e.message.orEmpty()}
    }
    private fun execute(){viewModelScope.launch {CleanupAnalysisLock.mutex.withLock {
        try {
            val progress:(String)->Unit={s->viewModelScope.launch{stage=s}}
            val action=pendingAction
            if(action !in setOf("undo","untrashResult")&&webPlan==null)operations.begin()
            val result=when(action){
                "organize"->if(webPlan!=null)OrganizerPlans.apply(c,webPlan!!,tree,pendingPlan,operations,progress)else if(pendingPlan.any{it.file.gallery})operations.organizeGallery(pendingPlan,progress)else operations.organizeDocuments(Uri.parse(tree),pendingPlan,false,progress)
                "duplicates"->operations.organizeDocuments(Uri.parse(tree),pendingFiles.map{Placement(it,"FORJA - De verificat","Duplicat identic verificat")},true,progress)
                "trashResult"->{operations.rememberTrash(pendingFiles);notice="${pendingFiles.size} duplicate trimise în coșul Galeriei. Spațiul se eliberează când coșul este golit.";null}
                "untrashResult"->{operations.clearJournal();notice="Fotografiile au fost restaurate din coș.";null}
                "undo"->if(undoRows.any{it.optString("kind")=="media"})operations.undoGallery()else operations.undoDocuments(progress)
                else->null
            }
            if(result!=null){
                notice="${result.moved} fișiere ${if(action=="undo")"restaurate"else "mutate efectiv pe telefon"}."
                if(result.copied>0)notice+=" ${result.copied} copii create; furnizorul a păstrat originalele. Acestea nu au fost curățate."
                if(result.skipped>0)notice+=" ${result.skipped} fișiere nemodificate; vezi detaliile."
                if(action=="duplicates")notice+=" Duplicatele sunt în «FORJA - De verificat». Spațiul nu este încă eliberat."
                issues=result.errors
            }
            rows=operations.journal();indexDocumentImages(rows)
            if(action !in setOf("undo","untrashResult"))batch?.let{id->withContext(Dispatchers.IO){val store=CleanupAutoStore(c);store.read(id)?.takeIf{it.optString("owner")==FileSync.owner()}?.let{saved->val reviewed=saved.optJSONObject("reviewed")?:JSONObject();reviewed.put(source,true);saved.put("reviewed",reviewed);store.write(id,saved)}}}
            // A subsequent analysis reads the real new paths. Never apply a stale plan twice.
            webPlan=null;report=null;plan=emptyList();selected=emptySet();stage=""
        }catch(e:CancellationException){throw e}catch(e:Exception){notice=e.message?:"Modificarea nu a reușit."}
        finally{rows=operations.journal();busy=false;pendingAction=""}
    }}}
    private suspend fun indexDocumentImages(rows:List<JSONObject>)=withContext(Dispatchers.IO){
        for(row in rows.filter{it.optString("kind")=="document"&&it.optString("state") in setOf("moved","copied","undone")})runCatching{
            for(key in listOf("source","result")){
                val uri=Uri.parse(row.optString(key));if(uri.authority!="com.android.externalstorage.documents")continue
                val id=DocumentsContract.getDocumentId(uri)
                if(id.startsWith("primary:")){val root=Environment.getExternalStorageDirectory().canonicalFile;val file=File(root,id.removePrefix("primary:")).canonicalFile
                    if(file.path.startsWith(root.path+"/"))MediaScannerConnection.scanFile(c,arrayOf(file.path),null,null)
                }
            }
        }
    }
}
