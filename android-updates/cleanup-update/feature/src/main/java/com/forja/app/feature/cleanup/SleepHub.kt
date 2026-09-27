package com.forja.app.feature.cleanup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable fun SleepHub() {
    val c=LocalContext.current;val scope=rememberCoroutineScope()
    var owner by remember{mutableStateOf(FileSync.owner())};var refresh by remember{mutableIntStateOf(0)}
    var minutes by remember{mutableIntStateOf(480)};var message by remember{mutableStateOf("")}
    var analysis by remember{mutableStateOf(true)};var consent by remember{mutableStateOf(false)};var privacy by remember{mutableStateOf(false)}
    var legacy by remember{mutableStateOf(false)};var syncing by remember{mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh++;message="Accesul a fost actualizat. Apasă din nou Start."}
    LaunchedEffect(Unit){runCatching{SleepRuntime.read(c,"interruptedAfterRestart")};while(isActive){owner=FileSync.owner();refresh++;delay(1500)}}
    LaunchedEffect(owner){if(owner!=null)while(isActive){runCatching{SleepBridge.refresh(c)};refresh++;delay(15000)}}
    val active=remember(refresh){runCatching{SleepRuntime.active(c)}.getOrNull()}
    val authorized=remember(refresh){runCatching{SleepRuntime.authorized(c)}.getOrDefault(false)}
    val ready=remember(refresh){runCatching{SleepRuntime.ready()}.getOrDefault(false)}
    val sessions=remember(refresh){runCatching{SleepRuntime.sessions(c)}.getOrNull()}
    val latest=(0 until (sessions?.length()?:0)).map{sessions!!.getJSONObject(it)}.maxByOrNull{it.optLong("started_at")}
    val report=remember(refresh,latest?.optString("id")){latest?.optString("id")?.let{SleepBridge.cached(c,it)}}
    fun start(){
        if(owner==null){message="Conectează-te în FORJA, apoi revino la Somn.";return}
        val needed=mutableListOf<String>()
        if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed+=Manifest.permission.RECORD_AUDIO
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed+=Manifest.permission.POST_NOTIFICATIONS
        if(needed.isNotEmpty()){permission.launch(needed.toTypedArray());return}
        try {
            if(!authorized && !consent){message="Confirmă sincronizarea pentru această sesiune.";return}
            if(!authorized && !SleepRuntime.authorize(c,analysis)){message="Activează notificările FORJA și reîncearcă.";return}
            message=if(SleepRuntime.start(c,minutes))"Pornesc microfonul…"else "Nu am putut porni. Verifică sincronizarea și notificările.";refresh++
        }catch(e:Exception){message="Nu am putut porni sesiunea. Verifică permisiunile."}
    }
    if(legacy){
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom=80.dp)) {TextButton(onClick={legacy=false}){Text("← Înapoi la Somn")};Text("Istoricul local folosește estimări, nu măsurători ale fazelor de somn.",Modifier.padding(12.dp),fontSize=12.sp);com.forja.app.feature.sleep.LegacySleepScreen()};return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom=80.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("Somn",fontSize=32.sp,fontWeight=FontWeight.Bold)
        Text(if(active!=null)"Microfon pornit · sincronizare activă"else "Noaptea ta. Sau doar un pui de somn.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape=RoundedCornerShape(26.dp)) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            if(active!=null){
                val elapsed=(System.currentTimeMillis()-active.optLong("started_at"))/60000
                Text("${elapsed.coerceAtLeast(0)} min înregistrate",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Până la "+DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(active.optLong("planned_stop_at"))))
                Button(onClick={SleepRuntime.stop(c);message="Oprire cerută. Trimit segmentele înregistrate.";refresh++},modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Oprește și sincronizează")}
            } else {
                Text(if(minutes<180)"Pauză de somn"else "Somn de noapte",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(30,90,480).forEach{value->FilterChip(selected=minutes==value,onClick={minutes=value},label={Text(if(value==480)"8 h"else "$value min")})}}
                Text("${minutes/60} h ${minutes%60} min",fontSize=26.sp,fontWeight=FontWeight.Bold)
                Slider(value=minutes.toFloat(),onValueChange={minutes=(it/15).toInt().coerceAtLeast(1)*15},valueRange=15f..720f,steps=46)
                if(!authorized){
                    Row {Checkbox(checked=consent,onCheckedChange={consent=it});Text("Trimit înregistrarea în contul meu, unde o pot asculta timp de 24 h.",Modifier.padding(top=12.dp),fontSize=13.sp)}
                    Row {Checkbox(checked=analysis,onCheckedChange={analysis=it});Text("Primește și un raport AI din sunetele înregistrate.",Modifier.padding(top=12.dp),fontSize=13.sp)}
                }
                Button(onClick={start()},enabled=owner!=null&&(authorized||consent),modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Încep somnul acum")}
                Text(if(ready)"Programările din site sunt pregătite."else "Programările din site cer sincronizarea activată pe telefon.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }}
        if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE+"/insights#sleep")))},modifier=Modifier.fillMaxWidth()){Text("Programul meu · noapte și siestă ↗")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Raportul tău",fontSize=22.sp,fontWeight=FontWeight.Bold);TextButton(enabled=!syncing,onClick={syncing=true;scope.launch{try{SleepBridge.refresh(c)}catch(e:Exception){message=e.message.orEmpty()}finally{syncing=false;refresh++}}}){Text(if(syncing)"Se actualizează…"else "Actualizează")}}
        if(report==null){Text(if(latest==null)"Raportul apare aici după prima sesiune."else "Sesiunea este salvată. Aștept audio și analiza din contul tău.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        else SleepReportCard(report){c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE+"/insights#sleep")))}
        val syncStatus=remember(refresh){SleepBridge.status(c)}
        if(!syncStatus.isNullOrBlank())Text(syncStatus,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick={privacy=true}){Text("Confidențialitate și detalii")}
        TextButton(onClick={legacy=true}){Text("Alarme și istoricul local")}
        Spacer(Modifier.height(12.dp))
    }
    if(privacy)AlertDialog(onDismissRequest={privacy=false},title={Text("Somn și audio")},text={Text("Microfonul funcționează numai într-o sesiune pornită de tine sau programată în contul tău, cu notificare și Oprește. Audio este trimis în segmente de maximum 2 minute; pot exista scurte pauze între segmente. Copiile din site expiră după 24 h. Analiza AI folosește doar audio primit și poate greși sau omite sunete. Nu măsoară fazele somnului și nu oferă diagnostic. Dacă Android oprește aplicația sau telefonul repornește, reactivează sincronizarea pentru programări.")},confirmButton={TextButton(onClick={privacy=false}){Text("Am înțeles")}})
}

@Composable private fun SleepReportCard(report:JSONObject,openAudio:()->Unit) {
    Card(shape=RoundedCornerShape(22.dp)){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        val recorded=report.optLong("recorded_ms")/60000;val analyzed=report.optLong("analyzed_ms")/60000
        Text("$recorded min audio primit",fontSize=24.sp,fontWeight=FontWeight.Bold)
        Text("$analyzed min analizate · ${report.optInt("chunk_count")} segmente",fontSize=13.sp)
        val chunks=report.optJSONArray("chunks")
        val transcripts=mutableListOf<String>();val topics=linkedSetOf<String>()
        for(i in 0 until (chunks?.length()?:0)) {
            val result=chunks!!.getJSONObject(i).optJSONObject("result")?:continue
            result.optString("transcript").takeIf{it.isNotBlank()}?.let{transcripts+=it}
            val labels=result.optJSONArray("topics");for(j in 0 until (labels?.length()?:0))labels!!.optJSONObject(j)?.optString("title")?.takeIf{it.isNotBlank()}?.let{topics+=it}
        }
        if(topics.isNotEmpty())Text(topics.take(5).joinToString(" · "),fontWeight=FontWeight.SemiBold)
        transcripts.take(3).forEach{Text(it.take(400),fontSize=14.sp)}
        if(transcripts.isEmpty())Text(if(report.optJSONObject("analysis")?.optInt("pending",0)?:0>0)"Analiza este în curs."else "Nu este disponibilă o transcriere.",fontSize=14.sp)
        val snoring=report.optJSONObject("snoring")
        val snoreStatus=snoring?.optString("status")
        if(snoreStatus=="complete" || snoreStatus=="partial"){
            val snoreMinutes=(snoring!!.optLong("possible_ms")/60000.0)
            Text("Posibil sforăit · ${snoring.optInt("possible_intervals")} intervale",fontWeight=FontWeight.SemiBold)
            Text("${"%.1f".format(snoreMinutes)} min detectate în ${snoring.optLong("analyzed_ms")/60000} min verificate pe telefon.",fontSize=13.sp)
        }else Text("Analiza sunetelor nu este încă disponibilă.",fontSize=13.sp)
        if((chunks?.length()?:0)>0){
            val from=chunks!!.optJSONObject(0)?.optLong("recorded_from",0)?:0
            val to=chunks.optJSONObject(chunks.length()-1)?.optLong("recorded_to",0)?:0
            if(from>0&&to>=from)Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(from))+" — "+DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(to)),fontSize=13.sp)
            OutlinedButton(onClick=openAudio,modifier=Modifier.fillMaxWidth()){Text("Ascultă audio și vezi raportul ↗")}
        }
        Text("Transcrierea și sunetele sunt estimări AI; verifică-le în audio. Fazele somnului nu sunt măsurate.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}
