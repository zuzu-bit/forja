package com.forja.app.feature.cleanup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun CleanupAutoEntry(vm:PhoneCleanupViewModel){
    val c=LocalContext.current;val p=remember{CleanupAuto.prefs(c)};val scope=rememberCoroutineScope()
    var show by remember{mutableStateOf(false)};var stop by remember{mutableStateOf(false)}
    var enabled by remember{mutableStateOf(CleanupAuto.enabled(c))};var status by remember{mutableStateOf(if(enabled)p.getString("status","").orEmpty()else "")}
    var webPlans by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var reports by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    val updates by CleanupAuto.changed.collectAsState()
    LaunchedEffect(updates){webPlans=withContext(Dispatchers.IO){OrganizerPlans.pending(c)};enabled=CleanupAuto.enabled(c);status=if(enabled)p.getString("status","").orEmpty()else "";reports=withContext(Dispatchers.IO){FileSync.owner()?.let{CleanupAutoStore(c).recent(it)}?:emptyList()}}
    DisposableEffect(p){val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->enabled=CleanupAuto.enabled(c);status=if(enabled)p.getString("status","").orEmpty()else ""};p.registerOnSharedPreferenceChangeListener(listener);onDispose{p.unregisterOnSharedPreferenceChangeListener(listener)}}
    LaunchedEffect(Unit){CleanupAuto.schedule(c,true)}
    Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Organizează din laptop",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        Text("Cere din site ultimele 50/100, un număr ales sau toate elementele accesibile, după folder și perioadă. Analiză locală + copii temporare 24 h. Aprobi destinațiile din laptop; Android confirmă aici mutările din Galerie.",fontSize=12.sp)
        Button(onClick={c.startActivity(Intent().setClassName(c,"com.forja.app.feature.research.WebPairActivity"))},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Configurare: Somn, audio și fișiere")}
        if(status.isNotBlank())Text(status,fontSize=12.sp)
        if(enabled){
            Text("Programările pot întârzia din cauza bateriei sau rețelei. Telefonul verifică periodic schimbările din site.",fontSize=11.sp)
            Row{TextButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE)))}){Text("Alege orele pe site ↗")};TextButton(onClick={CleanupAuto.schedule(c,true)}){Text("Verifică programul")}}
            TextButton(onClick={stop=true}){Text("Dezactivează analiza automată")}
        }
        webPlans.forEach{p->OutlinedButton(onClick={vm.loadWebPlan(p.getString("id"))},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Confirmă planul din laptop · ${p.getJSONArray("items").length()} poze")}}
        reports.forEach{saved->val items=saved.getJSONObject("reports");for(kind in listOf("photos","files"))if(items.has(kind)&&saved.optJSONObject("reviewed")?.optBoolean(kind)!=true){
            val report=items.getJSONObject(kind);val count=report.getJSONArray("files").length();val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(saved.getLong("created")))
            OutlinedButton(onClick={vm.loadAutomatic(saved.getString("id"),kind)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Vezi propunerile · $count ${if(kind=="photos")"poze"else "fișiere"} · $time",fontSize=12.sp)}
        }}
    }}
    if(show)CleanupAutoDialog(vm){show=false}
    if(stop)AlertDialog(onDismissRequest={stop=false},title={Text("Dezactivăm analiza automată?")},text={Text("Nu se vor mai analiza și încărca selecții la orele programate. Copiile deja primite pe site expiră normal după 24 h.")},confirmButton={TextButton(onClick={stop=false;scope.launch(Dispatchers.IO){CleanupAuto.stop(c)}}){Text("Dezactivează")}},dismissButton={TextButton(onClick={stop=false}){Text("Înapoi")}})
}
@Composable private fun CleanupAutoDialog(vm:PhoneCleanupViewModel,close:()->Unit){
    val c=LocalContext.current;val p=remember{CleanupAuto.prefs(c)};val scope=rememberCoroutineScope()
    var photos by remember{mutableStateOf(if(CleanupAuto.enabled(c))p.getBoolean("photos",true)else vm.source=="photos")}
    var files by remember{mutableStateOf(if(CleanupAuto.enabled(c))p.getBoolean("files",false)else vm.source=="files")}
    var tree by remember{mutableStateOf(if(CleanupAuto.enabled(c))p.getString("tree","").orEmpty()else vm.tree)}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    fun activate(){busy=true;error="";scope.launch{try{withContext(Dispatchers.IO){CleanupAuto.activate(c,photos,files,tree)};close()}catch(e:Exception){error=if(e is FileSync.Failure&&e.code==404)"Publică întâi actualizarea v17 a site-ului, apoi activează aici."else e.message.orEmpty()}finally{busy=false}}}
    fun granted():Boolean{
        fun has(s:String)=ContextCompat.checkSelfPermission(c,s)==PackageManager.PERMISSION_GRANTED
        return if(Build.VERSION.SDK_INT>=33)has(Manifest.permission.READ_MEDIA_IMAGES)||(Build.VERSION.SDK_INT>=34&&has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))else has(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(granted())activate()else error="Permite accesul la fotografiile pe care vrei să le analizezi automat."}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)try{c.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);tree=uri.toString();files=true}catch(_:Exception){error="Alege un dosar accesibil din telefon."}}
    AlertDialog(onDismissRequest={if(!busy)close()},title={Text("Analizează-mi automat")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Activezi o dată sursele pe acest telefon. Din site alegi ultimele 25/50, orele, zilele și opțiunea Wi-Fi. Sunt încărcate copii în același cont FORJA, păstrate 24 h.",fontSize=13.sp)
        Row{Checkbox(photos,{photos=it},enabled=!busy);Text("Fotografiile autorizate",Modifier.padding(top=12.dp))}
        Row{Checkbox(files,{files=it},enabled=!busy);Text("Fișierele din dosarul ales",Modifier.padding(top=12.dp))}
        if(files){OutlinedButton(onClick={picker.launch(tree.takeIf{it.isNotBlank()}?.let(Uri::parse))},enabled=!busy){Text(if(tree.isBlank())"Alege dosarul"else "Schimbă dosarul")};if(tree.isNotBlank())Text(Uri.decode(tree.substringAfterLast('/')),fontSize=11.sp)}
        Text("Analiza automată nu mută și nu șterge originalele. Maximum 25 MB/fișier și 512 MB temporar pe cont. Dacă permiți din site, se pot folosi date mobile.",fontSize=11.sp)
        if(CleanupAuto.enabled(c))Text("Salvarea unor surse noi cere configurarea din nou a programului pe site.",fontSize=11.sp)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={TextButton(enabled=!busy,onClick={
        if(FileSync.owner()==null){error="Conectează-te cu contul FORJA folosit pe site.";return@TextButton}
        if(!photos&&!files){error="Alege cel puțin o sursă.";return@TextButton}
        if(files&&tree.isBlank()){error="Alege dosarul cu fișiere.";return@TextButton}
        if(photos&&!granted())permission.launch(if(Build.VERSION.SDK_INT>=34)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)else if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))else activate()
    }){Text("Activează pe acest telefon")}},dismissButton={TextButton(onClick=close,enabled=!busy){Text("Înapoi")}})
}
