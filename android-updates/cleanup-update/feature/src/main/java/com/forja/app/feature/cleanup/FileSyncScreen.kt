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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable internal fun FileSyncEntry(){
    val c=LocalContext.current;val p=remember{FileSync.prefs(c)}
    var show by remember{mutableStateOf(false)}
    var enabled by remember{mutableStateOf(p.getBoolean("enabled",false))}
    var status by remember{mutableStateOf(p.getString("status","").orEmpty())}
    DisposableEffect(p){val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->enabled=p.getBoolean("enabled",false);status=p.getString("status","").orEmpty()};p.registerOnSharedPreferenceChangeListener(listener);onDispose{p.unregisterOnSharedPreferenceChangeListener(listener)}}
    LaunchedEffect(Unit){FileSync.schedule(c)}
    TextButton(onClick={show=true},contentPadding=PaddingValues(vertical=0.dp)){
        Text(if(enabled)"Poze și documente pe site · activată"else "Poze și documente pe site · 24 h",fontSize=12.sp)
    }
    if(show)FileSyncDialog(c,p,status,{show=false})
}

@Composable private fun FileSyncDialog(c:Context,p:SharedPreferences,status:String,close:()->Unit){
    var photos by remember{mutableStateOf(p.getBoolean("photos",true))}
    var documents by remember{mutableStateOf(!p.getString("tree","").isNullOrEmpty())}
    var tree by remember{mutableStateOf(p.getString("tree","").orEmpty())}
    var limit by remember{mutableIntStateOf(p.getInt("limit",50))}
    var wifi by remember{mutableStateOf(p.getBoolean("wifi",true))}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun activate(){try{check(photos||documents){"Alege pozele sau un dosar cu fișiere."};check(!documents||tree.isNotBlank()){"Alege dosarul pe care vrei să-l sincronizezi."};FileSync.activate(c,photos,if(documents)tree else "",limit,wifi);error=""}catch(e:Exception){error=e.message.orEmpty()}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)try{c.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);tree=uri.toString();documents=true;error=""}catch(_:Exception){error="Dosarul nu permite acces permanent. Alege alt dosar."}}
    fun photoGranted():Boolean {
        fun granted(permission:String)=ContextCompat.checkSelfPermission(c,permission)==PackageManager.PERMISSION_GRANTED
        return if(Build.VERSION.SDK_INT>=33)granted(Manifest.permission.READ_MEDIA_IMAGES)||(Build.VERSION.SDK_INT>=34&&granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(photoGranted())activate()else error="Permite accesul la fotografiile pe care vrei să le sincronizezi."}
    AlertDialog(onDismissRequest=close,title={Text("Pozele și documentele tale, pe site")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("După activare, FORJA trimite automat copii în același cont de pe site. Fiecare copie se șterge din site după 24 de ore. Originalele rămân pe telefon.",fontSize=13.sp)
            Row{Checkbox(photos,{photos=it});Text("Fotografiile la care ai dat acces",Modifier.padding(top=12.dp),fontSize=13.sp)}
            Row{Checkbox(documents,{documents=it});Text("Fișiere dintr-un dosar ales",Modifier.padding(top=12.dp),fontSize=13.sp)}
            if(documents){OutlinedButton(onClick={picker.launch(tree.takeIf{it.isNotBlank()}?.let(Uri::parse))}){Text(if(tree.isBlank())"Alege dosarul"else "Schimbă dosarul")};if(tree.isNotBlank())Text("Dosar: "+Uri.decode(tree.substringAfterLast('/'))+"\nInclude subdosarele accesibile.",fontSize=11.sp)}
            Text("Ce se verifică la fiecare rundă?",fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(50,100,200,0).forEach{n->FilterChip(selected=n==limit,onClick={limit=n},label={Text(if(n==0)"Toate"else "$n",fontSize=11.sp)})}}
            Text("Cele mai recente poze și fișiere, separat pe sursă. „Toate” verifică maximum 15.000 pe sursă. Fișierele neschimbate sunt trimise o singură dată.",fontSize=11.sp)
            Row{Checkbox(wifi,{wifi=it});Text("Doar pe conexiune fără cost de date (de obicei Wi-Fi)",Modifier.padding(top=10.dp),fontSize=12.sp)}
            Text("Android programează verificările în fundal, începând de la 15 minute, în funcție de rețea și baterie. Prima rundă este cerută imediat. Maximum 25 MB/fișier și 512 MB temporar pe cont.",fontSize=11.sp)
            if(status.isNotBlank())Text(status,fontSize=12.sp)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            TextButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}){Text("Deschide site-ul FORJA")}
            if(p.getBoolean("enabled",false))OutlinedButton(onClick={
                val uid=p.getString("owner",null);val device=p.getString("device",null);FileSync.stop(c)
                if(uid!=null&&device!=null&&uid==FileSync.owner())scope.launch{runCatching{FileSync.request(c,uid,"/v2/files/settings/$device","POST",org.json.JSONObject().put("enabled",false).put("photos",photos).put("files",documents).toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)}}
            }){Text("Oprește trimiterea automată")}
        }
    },confirmButton={TextButton(onClick={
        if(FileSync.owner()==null){error="Conectează-te în aplicație cu contul FORJA folosit pe site.";return@TextButton}
        if(documents&&tree.isBlank()){error="Alege întâi dosarul cu fișiere.";return@TextButton}
        if(photos&&!photoGranted())permission.launch(if(Build.VERSION.SDK_INT>=34)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)else if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))else activate()
    }){Text(if(p.getBoolean("enabled",false))"Salvează și sincronizează"else "Activează trimiterea automată")}},dismissButton={TextButton(onClick=close){Text("Închide")}})
}
