package com.forja.app.feature.cleanup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun CleanupScreen(onBack:()->Unit) {
    val c=LocalContext.current;val vm:PhoneCleanupViewModel=viewModel()
    var confirm by remember{mutableStateOf("")}
    var stopTransfer by remember{mutableStateOf(false)}
    var edit by remember{mutableStateOf<Placement?>(null)}
    var account by remember{mutableStateOf(FirebaseAuth.getInstance().currentUser?.email)}
    DisposableEffect(Unit){val auth=FirebaseAuth.getInstance();val listener=FirebaseAuth.AuthStateListener{account=it.currentUser?.email;CleanupTransfer.changed.value++;CleanupAuto.changed.value++};auth.addAuthStateListener(listener);onDispose{auth.removeAuthStateListener(listener)}}
    val launchRef=remember{arrayOfNulls<(IntentSender)->Unit>(1)}
    val write=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->vm.permissionResult(result.resultCode==Activity.RESULT_OK){launchRef[0]?.invoke(it)}}
    val launch:(IntentSender)->Unit={sender->try{write.launch(IntentSenderRequest.Builder(sender).build())}catch(e:Exception){vm.permissionResult(false){};vm.error(e.message.orEmpty())}}
    SideEffect{launchRef[0]=launch}
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)try{
        c.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);vm.folder(uri)
    }catch(_:Exception){vm.error("Alege un dosar în care telefonul permite citirea și mutarea fișierelor.")}}
    fun photoGranted():Boolean {
        fun granted(p:String)=ContextCompat.checkSelfPermission(c,p)==PackageManager.PERMISSION_GRANTED
        return if(Build.VERSION.SDK_INT>=33)granted(Manifest.permission.READ_MEDIA_IMAGES)||(Build.VERSION.SDK_INT>=34&&granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val read=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(photoGranted())vm.analyze()else vm.error("Permite accesul la fotografiile pe care vrei să le organizezi.")}
    fun analyze(){if(vm.source=="photos"&&!photoGranted())read.launch(if(Build.VERSION.SDK_INT>=34)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)else if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))else vm.analyze()}
    val colors=lightColorScheme(primary=Color(0xFF214F43),secondary=Color(0xFF617B36),surface=Color(0xFFF7F4EC),surfaceContainer=Color(0xFFECEFE3),onSurface=Color(0xFF1C362E))
    MaterialTheme(colorScheme=colors){
        Surface(Modifier.fillMaxSize(),color=colors.surface){
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=onBack){Text("‹ Înapoi")};Spacer(Modifier.weight(1f));Text("CURĂȚENIA DE ASTĂZI",fontSize=11.sp,letterSpacing=1.sp)}}
                item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Fă loc.\nPune lucrurile la loc.",fontSize=30.sp,lineHeight=35.sp,fontWeight=FontWeight.SemiBold);Text("Organizează originalele din telefon după conținut. Vezi întâi propunerile, apoi aplică mutările.",fontSize=14.sp)}}
                item{CleanupAutoEntry(vm)}
                item{Card{
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("1 · Alegem ce organizăm",fontWeight=FontWeight.SemiBold)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(vm.source=="photos",{vm.source("photos")},enabled=!vm.busy,label={Text("Fotografii")});FilterChip(vm.source=="files",{vm.source("files")},enabled=!vm.busy,label={Text("Documente și fișiere")})}
                        if(vm.source=="files"){
                            OutlinedButton(onClick={folder.launch(vm.tree.takeIf{it.isNotBlank()}?.let(Uri::parse))},enabled=!vm.busy){Text(if(vm.tree.isBlank())"Alege dosarul din telefon"else "Schimbă dosarul")}
                            if(vm.tree.isNotBlank())Text("Dosar: "+Uri.decode(vm.tree.substringAfterLast('/'))+"\nInclude subdosarele accesibile.",fontSize=12.sp)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(25,50,100,200,0).forEach{n->FilterChip(vm.limit==n,{vm.scope(n)},enabled=!vm.busy,label={Text(if(n==0)"Toate"else "Ultimele $n")})}}
                        Text(if(vm.source=="photos")"Cele mai recent adăugate fotografii la care ai permis accesul."else "Cele mai recent modificate fișiere din dosarul ales.",fontSize=12.sp)
                        HorizontalDivider()
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            FilterChip(vm.manual,{vm.mode(true)},enabled=!vm.busy,label={Text("Manual · fără AI")})
                            FilterChip(!vm.manual,{vm.mode(false)},enabled=!vm.busy,label={Text("Analiză locală")})
                        }
                        Text(if(vm.manual)"Vezi selecția și alegi singur dosarele. Conținutul nu este analizat."else "OCR și clasificare pe telefon. Verifici propunerile înainte de mutare.",fontSize=12.sp)
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(vm.online,{vm.online=it},enabled=!vm.busy);Text("Copii pe site · 24 de ore",fontWeight=FontWeight.Medium)}
                        Text(if(vm.online)"La pornire, TOATE pozele și fișierele din această selecție sunt trimise automat în contul tău FORJA, în paralel cu analiza locală. Copiile expiră la 24 h de la primire. Se pot folosi date mobile."else "Analiza acestei selecții se face doar pe telefon.",fontSize=12.sp)
                        if(vm.online)Text(account?.let{"Cont: $it"}?:"Conectează-te în aplicație pentru a folosi site-ul.",fontSize=12.sp)
                        Button(onClick={analyze()},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text(if(vm.manual)if(vm.online)"Arată selecția și trimite pe site"else "Arată selecția"else if(vm.online)"Analizează și trimite pe site"else "Analizează pe telefon")}
                    }
                }}
                if(vm.stage.isNotBlank()||vm.busy)item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(vm.stage,fontSize=13.sp);if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(vm.analyzing)TextButton(onClick={vm.stopAnalysis()}){Text("Oprește analiza")}}}
                if(vm.transfer.total>0)item{Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFE5EDD9))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Pe site: ${vm.transfer.sent} din ${vm.transfer.total}",fontWeight=FontWeight.SemiBold)
                    LinearProgressIndicator(progress={vm.transfer.sent.toFloat()/vm.transfer.total.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                    Text(vm.transfer.message.ifBlank{"Transferul începe în timpul analizei."},fontSize=12.sp)
                    if(!vm.transfer.complete&&vm.transfer.active){Text("Fișierele rămân în coadă dacă se întrerupe conexiunea. Limitele actuale ale site-ului: 25 MB/fișier, 500 copii și 512 MB pe cont.",fontSize=11.sp);TextButton(onClick={vm.retryTransfer()}){Text("Reîncearcă fișierele rămase")};TextButton(onClick={stopTransfer=true}){Text("Oprește transferul acestei selecții")}}
                    if(vm.transfer.errors.isNotEmpty()){var show by remember{mutableStateOf(false)};TextButton(onClick={show=!show}){Text(if(show)"Ascunde detaliile"else "Vezi fișierele netrimise")};if(show)vm.transfer.errors.forEach{Text(it,fontSize=12.sp,color=colors.error)}}
                    TextButton(onClick={openCleanupUri(c,Uri.parse(FileSync.SITE))}){Text("Vezi pozele și documentele pe site ↗")}
                }}}
                if(vm.notice.isNotBlank())item{Text(vm.notice,fontSize=14.sp,fontWeight=FontWeight.Medium)}
                if(vm.issues.isNotEmpty())item{Column{vm.issues.forEach{Text(it,fontSize=12.sp,color=colors.error)}}}
                val report=vm.report
                if(report!=null){
                    item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("2 · Verifică și aplică pe telefon",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(vm.tab=="organize",{vm.tab("organize")},enabled=!vm.busy,label={Text("Mutări · ${vm.plan.size}")});FilterChip(vm.tab=="duplicates",{vm.tab("duplicates")},enabled=!vm.busy,label={Text("Duplicate · ${report.duplicateCopies.size}")})}
                        Text(if(vm.tab=="organize")"Destinațiile de mai jos sunt dosare reale din telefon. Mutarea nu reduce numărul de octeți ocupați."else "Sunt propuse doar copii identice, verificate din nou înainte de curățare. Păstrăm un original din fiecare grup.",fontSize=12.sp)
                        if(report.warnings.isNotEmpty()){var warnings by remember{mutableStateOf(false)};TextButton(onClick={warnings=!warnings}){Text(if(warnings)"Ascunde limitele analizei"else "Detalii despre selecție și analiză")};if(warnings)report.warnings.forEach{Text(it,fontSize=11.sp)}}
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(vm.selected.isNotEmpty(),{vm.selectAll(it)},enabled=!vm.busy);Text("${vm.selected.size} selectate")}
                        if(!vm.copiesReady)Text("Confirmăm copiile pe site înainte de a muta originalele.",fontSize=12.sp)
                        Button(onClick={confirm=if(vm.tab=="organize")"organize"else "duplicates"},enabled=!vm.busy&&vm.copiesReady&&vm.selected.isNotEmpty(),modifier=Modifier.fillMaxWidth()){
                            Text(if(vm.tab=="organize")if(vm.source=="photos")"Mută în Galeria telefonului"else "Mută în Fișierele telefonului"else if(vm.source=="photos")"Mută duplicatele în coș"else "Separă duplicatele pe telefon")
                        }
                    }}
                    if(vm.tab=="organize")items(vm.plan,key={"plan:"+it.file.uri}){p->CleanupFileRow(p.file,p.destination,p.reason,p.file.uri in vm.selected,!vm.busy,{vm.select(p.file.uri,it)},{edit=p})}
                    else items(report.duplicateCopies,key={"dup:"+it.uri}){f->CleanupFileRow(f,if(f.gallery)"Coșul Galeriei"else "FORJA - De verificat","Duplicat identic · ${cleanupSize(f.bytes)}",f.uri in vm.selected,!vm.busy,{vm.select(f.uri,it)},null)}
                }
                val changed=vm.rows.filter{it.optString("state") in setOf("moved","copied","trashed")}
                if(changed.isNotEmpty())item{Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Ultima modificare pe telefon",fontWeight=FontWeight.SemiBold)
                    val first=changed.first();val media=first.optString("kind")=="media"
                    OutlinedButton(onClick={
                        if(media){val uri=Uri.parse(first.optString("source"));openCleanupUri(c,uri,c.contentResolver.getType(uri)?:"image/*")}
                        else openCleanupUri(c,Uri.parse(first.optString("target_parent")),DocumentsContract.Document.MIME_TYPE_DIR)
                    }){Text(if(media)"Deschide în Galerie"else "Deschide dosarul în Fișiere")}
                    TextButton(onClick={confirm="undo"},enabled=!vm.busy){Text("Anulează ultima modificare")}
                }}}
                item{Spacer(Modifier.height(16.dp))}
            }
        }
        if(stopTransfer)AlertDialog(onDismissRequest={stopTransfer=false},title={Text("Continui doar pe telefon?")},text={Text("Fișierele rămase nu vor mai fi trimise pe site. Copiile deja primite expiră după 24 h. Poți continua organizarea locală.")},confirmButton={TextButton(onClick={stopTransfer=false;vm.stopTransfer()}){Text("Oprește transferul")}},dismissButton={TextButton(onClick={stopTransfer=false}){Text("Continuă trimiterea")}})
        if(confirm.isNotBlank())AlertDialog(onDismissRequest={confirm=""},title={Text(when(confirm){"undo"->"Restaurăm ultima modificare?";"duplicates"->"Curățăm duplicatele selectate?";else->"Aplicăm mutările pe telefon?"})},text={Text(when(confirm){"undo"->"Fișierele vor fi readuse la locurile anterioare, dacă acestea sunt încă accesibile.";"duplicates"->if(vm.source=="photos")"${vm.selected.size} copii identice vor fi mutate în coșul Galeriei. Originalele păstrate nu sunt atinse."else "${vm.selected.size} copii identice vor fi mutate în dosarul FORJA - De verificat, în spațiul telefonului. Le poți verifica acolo înainte de ștergere.";else->"${vm.selected.size} originale vor fi mutate în destinațiile afișate. Schimbarea se va vedea în Galeria sau Fișierele telefonului."}+(if(confirm!="undo"&&vm.rows.any{it.optString("state") in setOf("moved","copied","trashed")})"\nIstoricul de anulare va păstra această nouă operație."else ""))},confirmButton={TextButton(onClick={val action=confirm;confirm="";vm.prepare(action,launch)}){Text("Aplică")}},dismissButton={TextButton(onClick={confirm=""}){Text("Înapoi")}})
        edit?.let{p->var name by remember(p){mutableStateOf(p.destination.removePrefix("Pictures/").removePrefix("Movies/").trim('/'))};AlertDialog(onDismissRequest={edit=null},title={Text("Destinație pe telefon")},text={OutlinedTextField(value=name,onValueChange={name=it},label={Text("Album / subdosar")})},confirmButton={TextButton(onClick={vm.destination(p.file.uri,name);edit=null}){Text("Salvează")}},dismissButton={TextButton(onClick={edit=null}){Text("Anulează")}})}
    }
}

@Composable private fun CleanupFileRow(f:CleanFile,destination:String,reason:String,selected:Boolean,enabled:Boolean,onSelect:(Boolean)->Unit,onEdit:(()->Unit)?){
    val c=LocalContext.current
    Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(selected,onSelect,enabled=enabled)
            if(f.photo){val bitmap by produceState<android.graphics.Bitmap?>(null,f.uri){value=withContext(Dispatchers.IO){runCatching{CleanupCatalog(c).bitmap(Uri.parse(f.uri),100)}.getOrNull()}};val b=bitmap;if(b!=null)Image(b.asImageBitmap(),null,Modifier.size(52.dp).clickable{openCleanupUri(c,Uri.parse(f.uri),f.mime)},contentScale=ContentScale.Crop)}
            Column(Modifier.weight(1f).padding(start=8.dp)){Text(f.name,fontSize=14.sp,fontWeight=FontWeight.Medium);Text(cleanupSize(f.bytes),fontSize=11.sp)}
        }
        Text("Acum: ${f.path.ifBlank{"Dosarul ales"}}",fontSize=11.sp)
        Text("→ $destination",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        Text(reason,fontSize=12.sp)
        Row{TextButton(onClick={openCleanupUri(c,Uri.parse(f.uri),f.mime)}){Text("Deschide")};if(onEdit!=null)TextButton(onClick=onEdit,enabled=enabled){Text("Schimbă destinația")}}
    }}
}
private fun cleanupSize(bytes:Long)=if(bytes<0)"Mărime necunoscută"else if(bytes<1024*1024)"${bytes/1024} KB"else String.format(java.util.Locale.ROOT,"%.1f MB",bytes/(1024.0*1024))
private fun openCleanupUri(c:Context,uri:Uri,mime:String?=null){try{c.startActivity(Intent(Intent.ACTION_VIEW).apply{if(mime==null)data=uri else setDataAndType(uri,mime);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}catch(_:Exception){Toast.makeText(c,"Nu există o aplicație care să deschidă această destinație. Deschide Galeria sau Fișierele telefonului.",Toast.LENGTH_LONG).show()}}
