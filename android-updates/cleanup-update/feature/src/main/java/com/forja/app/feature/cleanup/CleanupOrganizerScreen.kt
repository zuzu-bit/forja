package com.forja.app.feature.cleanup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** One source, one batch, one live job. Secondary tools never clutter this screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CleanupOrganizerHome(vm:PhoneCleanupViewModel,onBack:()->Unit,onReview:()->Unit){
    val c=LocalContext.current
    var account by remember{mutableStateOf(FirebaseAuth.getInstance().currentUser)}
    var panel by rememberSaveable{mutableStateOf("")}
    var choosingSource by rememberSaveable{mutableStateOf(false)}
    var confirm by remember{mutableStateOf<String?>(null)}
    var consentOwner by remember{mutableStateOf<String?>(null)}
    var custom by rememberSaveable{mutableStateOf(false)}
    var customValue by rememberSaveable{mutableStateOf("")}
    var permissionAction by remember{mutableStateOf("")}
    var resumeFolder by remember{mutableStateOf(false)}
    var grantAllGallery by remember{mutableStateOf(false)}
    var privacy by rememberSaveable{mutableStateOf(false)}
    val job=vm.organizerJob
    val phase=job?.optString("phase").orEmpty()
    val canNext=OrganizerUiPolicy.canNext(phase)
    val showSource=job==null||choosingSource
    val permissionLauncher=remember{arrayOfNulls<(IntentSender)->Unit>(1)}
    val write=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        vm.permissionResult(result.resultCode==Activity.RESULT_OK){permissionLauncher[0]?.invoke(it)}
    }
    val launchWrite:(IntentSender)->Unit={sender->try{write.launch(IntentSenderRequest.Builder(sender).build())}catch(e:Exception){vm.permissionResult(false){};vm.error(e.message.orEmpty())}}
    SideEffect{permissionLauncher[0]=launchWrite}
    DisposableEffect(Unit){
        val auth=FirebaseAuth.getInstance()
        val listener=FirebaseAuth.AuthStateListener{account=it.currentUser;vm.accountChanged();confirm=null;panel="";choosingSource=false}
        auth.addAuthStateListener(listener);onDispose{auth.removeAuthStateListener(listener)}
    }
    // Poll only while this screen is composed. The worker owns execution and survives navigation.
    LaunchedEffect(account?.uid){while(true){
        try{vm.refreshOrganizer()}catch(e:CancellationException){throw e}catch(e:Exception){vm.error(e.message?:"Progresul nu a putut fi citit.")}
        delay(2000)
    }}
    fun photosGranted():Boolean{
        fun granted(p:String)=ContextCompat.checkSelfPermission(c,p)==PackageManager.PERMISSION_GRANTED
        return if(Build.VERSION.SDK_INT>=33)granted(Manifest.permission.READ_MEDIA_IMAGES)||(Build.VERSION.SDK_INT>=34&&granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun photoPermissions()=if(Build.VERSION.SDK_INT>=34)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)else if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    fun finishResume(){
        if(vm.organizerNeedsGrant(true)||vm.organizerJob?.optString("phase")=="needs_access"){
            consentOwner=account?.uid;grantAllGallery=false;confirm="grant_resume"
        }else vm.continueOrganizer(launchWrite)
    }
    val read=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        val action=permissionAction;permissionAction=""
        if(photosGranted())when(action){"albums"->{panel="albums";vm.loadGalleryFolders()};"start"->{vm.startOrganizer(consentOwner);choosingSource=false};"resume"->finishResume()}
        else vm.error("Alege fotografiile la care FORJA are acces.")
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)try{
        c.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if(resumeFolder){
            resumeFolder=false
            if(uri.toString()==vm.organizerJob?.optString("tree"))finishResume()
            else vm.error("Pentru acest lot alege același dosar. Pentru alt dosar, pornește un lot nou.")
        }else vm.folder(uri)
    }catch(_:Exception){resumeFolder=false;vm.error("Alege un dosar în care poți citi și muta fișiere.")}else resumeFolder=false}
    fun albums(){if(photosGranted()){panel="albums";vm.loadGalleryFolders()}else{permissionAction="albums";read.launch(photoPermissions())}}
    fun resume(){
        val current=vm.organizerJob?:return
        val tree=current.optString("tree")
        when{
            current.optString("source")=="photos"&&!photosGranted()->{permissionAction="resume";read.launch(photoPermissions())}
            current.optString("source")=="files"&&!c.contentResolver.persistedUriPermissions.any{it.uri.toString()==tree&&it.isReadPermission&&it.isWritePermission}->{resumeFolder=true;folder.launch(tree.takeIf{it.isNotBlank()}?.let(Uri::parse))}
            else->finishResume()
        }
    }
    val colors=lightColorScheme(primary=Color(0xFF214F43),secondary=Color(0xFF617B36),surface=Color(0xFFF7F4EC),surfaceContainer=Color(0xFFECEFE3),onSurface=Color(0xFF1C362E))
    MaterialTheme(colorScheme=colors){
        Surface(Modifier.fillMaxSize(),color=colors.surface){
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=onBack){Text("‹ Înapoi")};Spacer(Modifier.weight(1f));TextButton(onClick={panel="options"}){Text("Opțiuni")}}}
                item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    OrganizerFolderMark()
                    Column{Text("Totul la locul lui.",fontSize=25.sp,fontWeight=FontWeight.SemiBold);Text("Galeria și fișierele tale",fontSize=13.sp,color=colors.onSurfaceVariant)}
                }}
                if(job!=null)item{
                    OrganizerProgressCard(job,vm.busy,{resume()},{panel="details"},{confirm="stop"})
                }
                if(showSource)item{Card(shape=RoundedCornerShape(24.dp)){
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text("Ce organizăm?",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            FilterChip(vm.source=="photos",{vm.source("photos")},enabled=!vm.busy,label={Text("Galerie")})
                            FilterChip(vm.source=="files",{vm.source("files")},enabled=!vm.busy,label={Text("Fișiere")})
                        }
                        if(vm.source=="photos"){
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                FilterChip(vm.photoFolder.isBlank(),{vm.photoFolder("")},enabled=!vm.busy,label={Text("Toată galeria")})
                                FilterChip(vm.photoFolder.isNotBlank(),{albums()},enabled=!vm.busy,label={Text("Un album")})
                            }
                            if(vm.photoFolder.isNotBlank())TextButton(onClick={albums()},enabled=!vm.busy){Text(vm.photoFolder,maxLines=2,overflow=TextOverflow.Ellipsis)}
                            Text("Doar fotografiile autorizate în Android.",fontSize=12.sp,color=colors.onSurfaceVariant)
                        }else{
                            OutlinedButton(onClick={folder.launch(vm.tree.takeIf{it.isNotBlank()}?.let(Uri::parse))},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){
                                Text(if(vm.tree.isBlank())"Alege dosarul"else Uri.decode(vm.tree.substringAfterLast('/')).substringAfter(':').ifBlank{"Schimbă dosarul"},maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                            if(vm.tree.isNotBlank())Text("Include subdosarele accesibile.",fontSize=12.sp,color=colors.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        OrganizerBatchChoices(vm.limit,vm.busy,{vm.scope(it)},{customValue=vm.limit.takeIf{it>0}?.toString().orEmpty();custom=true})
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text("Cu AI și copii pe site",fontWeight=FontWeight.Medium);Text(if(vm.online)"Fișiere încărcate în contul tău · 24 h"else "Analiză locală · fără copii online",fontSize=12.sp,color=colors.onSurfaceVariant)}
                            Switch(vm.online,{vm.online=it},enabled=!vm.busy)
                        }
                        Button(onClick={if(account==null)openOrganizerAccount(c)else{consentOwner=account?.uid;grantAllGallery=false;confirm=if(vm.organizerNeedsGrant(false))"grant_start"else "start"}},enabled=!vm.busy&&(account==null||vm.source!="files"||vm.tree.isNotBlank()),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){
                            Text(if(vm.busy)"Pregătim…"else if(account==null)"Conectează-te"else "Organizează")
                        }
                        if(choosingSource)TextButton(onClick={choosingSource=false},modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Înapoi la lot")}
                    }
                }}
                if(job!=null&&canNext&&!showSource)item{Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    OrganizerBatchChoices(vm.limit,vm.busy,{vm.scope(it)},{customValue=vm.limit.takeIf{it>0}?.toString().orEmpty();custom=true})
                    Button(onClick={consentOwner=account?.uid;grantAllGallery=false;confirm=if(vm.organizerNeedsGrant(true))"grant_next"else "next"},enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text(if(vm.limit==0)"Organizează cele rămase"else "Continuă cu următoarele ${vm.limit}")}
                    TextButton(onClick={choosingSource=true},enabled=!vm.busy,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Alege altă sursă")}
                }}
                if(vm.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                if(vm.notice.isNotBlank())item{Text(vm.notice,fontSize=13.sp,color=colors.error)}
                item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton(onClick={openOrganizerSite(c)}){Text("Deschide pe site ↗")}
                    TextButton(onClick={privacy=true}){Text("Confidențialitate")}
                }}
            }
        }
        if(panel.isNotBlank())ModalBottomSheet(onDismissRequest={panel=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
            when(panel){
                "albums"->Column(Modifier.padding(horizontal=20.dp)){
                    Text("Alege albumul",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    if(vm.foldersLoading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=20.dp))
                    else if(vm.galleryFolders.isEmpty())Text("Nu există albume accesibile în selecția Android.",Modifier.padding(vertical=18.dp))
                    LazyColumn(Modifier.heightIn(max=440.dp).navigationBarsPadding()){
                        items(vm.galleryFolders,key={it}){path->TextButton(onClick={vm.photoFolder(path);panel=""},modifier=Modifier.fillMaxWidth()){Text(path,Modifier.fillMaxWidth().padding(vertical=6.dp),maxLines=2)}}
                    }
                }
                "details"->job?.let{OrganizerJobDetails(it)}
                "history"->Column(Modifier.padding(horizontal=20.dp)){
                    Text("Loturile tale",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    LazyColumn(Modifier.heightIn(max=480.dp).navigationBarsPadding()){
                        items(vm.organizerHistory,key={it.getString("id")}){saved->TextButton(onClick={vm.viewOrganizer(saved.getString("id"));choosingSource=false;panel=""},modifier=Modifier.fillMaxWidth()){
                            Column(Modifier.fillMaxWidth().padding(vertical=6.dp)){Text(OrganizerUiPolicy.label(saved.optString("phase")),fontWeight=FontWeight.Medium);Text("${saved.optInt("moved")} / ${saved.optInt("total")} organizate · ${organizerSourceLabel(saved)}",fontSize=12.sp)}
                        }}
                    }
                }
                else->Column(Modifier.padding(horizontal=20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("Organizarea ta",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                    TextButton(onClick={panel="";onReview()},enabled=!vm.busy){Text("Aleg manual · duplicate · anulare")}
                    if(vm.organizerHistory.isNotEmpty())TextButton(onClick={panel="history"}){Text("Loturile anterioare")}
                    CleanupAutoEntry(vm){panel="";onReview()}
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if(custom)AlertDialog(onDismissRequest={custom=false},title={Text("Câte fișiere?")},text={OutlinedTextField(value=customValue,onValueChange={customValue=it.take(8)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),label={Text("Număr de fișiere")},supportingText={Text("De la 1 la ${OrganizerUiPolicy.MAX_BATCH}")},isError=customValue.isNotEmpty()&&OrganizerUiPolicy.customCount(customValue)==null)},confirmButton={TextButton(onClick={OrganizerUiPolicy.customCount(customValue)?.let{vm.scope(it);custom=false}},enabled=OrganizerUiPolicy.customCount(customValue)!=null){Text("Alege")}},dismissButton={TextButton(onClick={custom=false}){Text("Înapoi")}})
        confirm?.let{action->
            if(action.startsWith("grant_")){
                val existing=action!="grant_start"
                val selectedJob=if(existing)job else null
                val kind=selectedJob?.optString("source")?:vm.source
                val album=selectedJob?.optString("folder")?:vm.photoFolder
                val (photos,tree)=vm.organizerGrantScope(existing)
                val widerAlbum=kind=="photos"&&album.isNotBlank()
                AlertDialog(onDismissRequest={if(!vm.busy)confirm=null},title={Text("Permite organizarea")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("Din aplicație și de pe site",fontWeight=FontWeight.SemiBold)
                    if(photos)Text("Galeria autorizată în Android")
                    if(tree.isNotBlank())Text("Dosar: "+Uri.decode(tree.substringAfterLast('/')).substringAfter(':'))
                    if(widerAlbum){
                        Text("Lotul ales: $album",fontSize=12.sp)
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(grantAllGallery,{grantAllGallery=it},enabled=!vm.busy);Text("Permit accesul pentru toată galeria autorizată",Modifier.weight(1f),fontSize=13.sp)}
                    }
                    Text("Doar selecțiile cerute de tine sunt procesate. Poți aproba mutări din site pentru sursele activate și poți opri accesul din Opțiuni.",fontSize=13.sp)
                    if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(vm.notice.isNotBlank())Text(vm.notice,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                }},confirmButton={TextButton(enabled=!vm.busy&&(!widerAlbum||grantAllGallery),onClick={vm.authorizeOrganizerSource(existing,consentOwner){
                    confirm=when(action){"grant_start"->"start";"grant_next"->"next";else->null}
                    if(action=="grant_resume")vm.continueOrganizer(launchWrite)
                }}){Text("Permite")}},dismissButton={TextButton(onClick={confirm=null},enabled=!vm.busy){Text("Anulează")}})
            }else{
            val old=if(action=="next")job else null
            val upload=old?.optBoolean("upload")?:vm.online
            val automatic=old?.optBoolean("automatic")?:true
            val scopeLabel=old?.let(::organizerSourceLabel)?:if(vm.source=="photos")vm.photoFolder.ifBlank{"Galeria autorizată"}else Uri.decode(vm.tree.substringAfterLast('/'))
            AlertDialog(onDismissRequest={confirm=null},title={Text(if(action=="stop")"Oprim acest lot?"else "Organizăm selecția?")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(action=="stop")Text("Fișierele deja organizate rămân la locul lor. Operația începută se verifică înainte de oprire.")
                else{
                    Text(scopeLabel,fontWeight=FontWeight.SemiBold)
                    Text(if(vm.limit==0)"Toate fișierele rămase eligibile."else "Următoarele ${vm.limit} fișiere eligibile.")
                    Text(if(automatic)"Mută automat originalele în dosare numite după conținut. Nu șterge fișiere."else "Pregătește dosare după conținut. Verifici și aprobi înainte de mutare.")
                    if(upload)Text("Încarcă selecția în contul tău pentru analiza AI și previzualizare. Copiile online expiră după 24 h.",fontSize=13.sp)
                    else Text("Analiza rămâne pe telefon. Progresul lotului se sincronizează în cont.",fontSize=13.sp)
                }
            }},confirmButton={TextButton(onClick={
                confirm=null
                when(action){
                    "stop"->vm.cancelOrganizer()
                    "next"->if(consentOwner==FileSync.owner())vm.nextOrganizer()else vm.error("Contul s-a schimbat. Confirmă din nou.")
                    else->{if(vm.source=="photos"&&!photosGranted()){permissionAction="start";read.launch(photoPermissions())}else{vm.startOrganizer(consentOwner);choosingSource=false}}
                }
            }){Text(if(action=="stop")"Oprește lotul"else if(!automatic)"Pregătește selecția"else if(upload)"Încarcă și organizează"else "Organizează")}},dismissButton={TextButton(onClick={confirm=null}){Text("Înapoi")}})
            }
        }
        if(privacy)OrganizerPrivacyDialog{privacy=false}
    }
}

@Composable private fun OrganizerBatchChoices(count:Int,busy:Boolean,onSelect:(Int)->Unit,onCustom:()->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("Din cele rămase",fontSize=13.sp,fontWeight=FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            for(n in listOf(50,100,0))FilterChip(count==n,{onSelect(n)},enabled=!busy,label={Text(if(n==0)"Toate"else "$n")})
            FilterChip(count !in setOf(0,50,100),onCustom,enabled=!busy,label={Text(if(count !in setOf(0,50,100))"$count · schimbă"else "Alt număr")})
        }
    }
}

@Composable private fun OrganizerProgressCard(job:JSONObject,busy:Boolean,onContinue:()->Unit,onDetails:()->Unit,onStop:()->Unit){
    val phase=job.optString("phase");val total=job.optInt("total");val moved=job.optInt("moved");val attention=job.optInt("needs_review")+job.optInt("failed")
    Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFE5EDD9))){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(OrganizerUiPolicy.label(phase),fontSize=20.sp,fontWeight=FontWeight.SemiBold)
        Text(organizerSourceLabel(job),fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(total>0){
            Row(verticalAlignment=Alignment.Bottom){Text("$moved",fontSize=36.sp,fontWeight=FontWeight.SemiBold);Text(" / $total organizate",Modifier.padding(bottom=6.dp),fontSize=14.sp)}
            LinearProgressIndicator(progress={(moved.toFloat()/total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Text("${job.optInt("analyzed")} analizate",fontSize=12.sp)
                if(job.optBoolean("upload"))Text("${job.optInt("uploaded")} pe site",fontSize=12.sp)
                if(attention>0)Text("$attention de verificat",fontSize=12.sp)
            }
        }else if(OrganizerUiPolicy.working(phase))LinearProgressIndicator(Modifier.fillMaxWidth())
        val message=job.optString("message")
        if(message.isNotBlank())Text(message,fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
        if(OrganizerUiPolicy.canContinue(phase))Button(onClick=onContinue,enabled=!busy,modifier=Modifier.fillMaxWidth()){
            Text(when(phase){"needs_permission"->"Confirmă în Android";"needs_access"->"Verifică accesul";else->"Continuă acest lot"})
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            TextButton(onClick=onDetails){Text("Vezi rezultatul")}
            if(phase !in setOf("complete","cancelled"))TextButton(onClick=onStop,enabled=!busy){Text("Oprește")}
        }
    }}
}

@Composable private fun OrganizerJobDetails(job:JSONObject){
    val rows=job.optJSONArray("items")
    val items=if(rows==null)emptyList()else (0 until rows.length()).mapNotNull{rows.optJSONObject(it)}
    Column(Modifier.padding(horizontal=20.dp)){
        Text("Fișiere și dosare",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
        Text(organizerSourceLabel(job),Modifier.padding(vertical=8.dp),fontSize=12.sp)
        if(job.optInt("total")>items.size)Text("${items.size} rezultate afișate din ${job.optInt("total")}. Progresul include întregul lot.",Modifier.padding(bottom=8.dp),fontSize=12.sp)
        if(items.isEmpty())Text("Rezultatele apar aici pe măsură ce lotul avansează.",Modifier.padding(vertical=20.dp))
        LazyColumn(Modifier.heightIn(max=500.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(items.take(100),key={it.optString("id")}){item->Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(16.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(item.optString("name"),fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(OrganizerUiPolicy.itemLabel(item.optString("state")),fontSize=12.sp)
                item.optString("destination").takeIf{it.isNotBlank()}?.let{Text("→ $it",fontSize=13.sp)}
                item.optString("error").takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
            }}
            if(items.size>100)item{Text("Primele 100 de rezultate. Progresul de mai sus include întregul lot.",fontSize=12.sp)}
            item{Spacer(Modifier.height(16.dp))}
        }
    }
}

@Composable private fun OrganizerFolderMark(){
    Canvas(Modifier.size(52.dp)){
        drawRoundRect(Color(0xFFB7C991),Offset(size.width*.04f,size.height*.2f),Size(size.width*.62f,size.height*.56f),CornerRadius(8.dp.toPx()))
        drawRoundRect(Color(0xFF214F43),Offset(size.width*.21f,size.height*.34f),Size(size.width*.74f,size.height*.5f),CornerRadius(8.dp.toPx()))
        drawLine(Color(0xFFF7F4EC),Offset(size.width*.4f,size.height*.58f),Offset(size.width*.51f,size.height*.68f),3.dp.toPx())
        drawLine(Color(0xFFF7F4EC),Offset(size.width*.51f,size.height*.68f),Offset(size.width*.76f,size.height*.47f),3.dp.toPx())
    }
}

@Composable internal fun OrganizerPrivacyDialog(close:()->Unit){
    AlertDialog(onDismissRequest=close,title={Text("Fișierele tale")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Sursa aleasă limitează fiecare lot. Fotografiile urmează accesul Android; documentele rămân în dosarul autorizat.")
        Text("Cu AI și copii pe site: fișierele selectate sunt încărcate efectiv în contul tău și analizate. Copiile online expiră după 24 de ore; progresul organizării se păstrează separat. Instrumentele manuale analizează local, cu încărcare opțională pe site.")
        Text("Fără această opțiune: conținutul se analizează local. Identitatea fișierelor, dosarele și progresul se sincronizează pentru continuare pe telefon și site.")
        Text("Aprobarea lotului permite mutări în dosarele sale. Android poate cere confirmări suplimentare pentru Galerie. Nu se șterg automat originale sau duplicate.")
        Text("O analiză parțială sau o operație ambiguă rămâne de verificat. Un fișier copiat, cu originalul păstrat, nu este raportat ca mutat.")
        Text("La Oprește, nu mai pornesc operații noi. Operația deja începută este verificată, iar rezultatul ei rămâne în istoric.")
    }},confirmButton={TextButton(onClick=close){Text("Închide")}})
}

private fun organizerSourceLabel(job:JSONObject):String=if(job.optString("source")=="photos")job.optString("folder").ifBlank{"Galeria autorizată"}else Uri.decode(job.optString("tree").substringAfterLast('/')).substringAfter(':').ifBlank{"Dosarul ales"}
private fun openOrganizerSite(c:Context){try{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE)))}catch(_:Exception){Toast.makeText(c,"Nu există un browser disponibil.",Toast.LENGTH_SHORT).show()}}
private fun openOrganizerAccount(c:Context){try{c.startActivity(Intent().setClassName(c,"com.forja.app.feature.research.ResearchExportActivity"))}catch(_:Exception){Toast.makeText(c,"Deschide Contul tău din aplicație.",Toast.LENGTH_SHORT).show()}}
