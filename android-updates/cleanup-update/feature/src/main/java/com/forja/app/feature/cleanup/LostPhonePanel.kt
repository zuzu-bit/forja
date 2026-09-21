package com.forja.app.feature.cleanup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

@Composable internal fun LostPhonePanel(owner:String?) {
    val c=LocalContext.current;val scope=rememberCoroutineScope()
    var enabled by remember(owner){mutableStateOf(LostPhoneRecovery.device(c)!=null)}
    var consent by remember(owner){mutableStateOf(false)};var busy by remember(owner){mutableStateOf(false)}
    var info by remember(owner){mutableStateOf("")};var status by remember(owner){mutableStateOf("")}
    var name by remember(owner){mutableStateOf(LostPhoneRecovery.device(c)?.let{LostPhoneRecovery.prefs(c).getString("name",null)}?:(Build.MANUFACTURER+" "+Build.MODEL).take(60))}
    var details by remember{mutableStateOf(false)};var privacy by remember{mutableStateOf(false)}
    LaunchedEffect(owner){while(isActive){enabled=LostPhoneRecovery.device(c)!=null;status=if(enabled)LostPhoneRecovery.prefs(c).getString("status","").orEmpty()else "";delay(2000)}}
    fun activate(){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner&&consent);withTimeout(30000){LostPhoneRecovery.activate(c,name)};enabled=true;consent=false;info="Telefon adăugat."}catch(e:TimeoutCancellationException){info="Activarea nu a fost confirmată. Încearcă din nou."}catch(e:CancellationException){throw e}catch(e:Exception){info=e.message.orEmpty()}finally{busy=false}}}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        if(LostPhoneRecovery.exactPermission(c)&&LostPhoneRecovery.notices(c)&&FileSync.owner()==owner&&consent)activate()
        else info="Permite locația precisă și notificările pentru a continua."
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
            Text("⌖",Modifier.padding(horizontal=18.dp,vertical=10.dp),fontSize=30.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(name,fontWeight=FontWeight.SemiBold)
            Text(if(enabled)"Găsire activată"else "Găsire dezactivată",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
        }
    }
    if(enabled) {
        if(status.isNotBlank())Text(status,fontSize=12.sp)
        Button(modifier=Modifier.fillMaxWidth(),onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE)))}){Text("Deschide panoul meu")}
        if(!LostPhoneService.running)OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={LostPhoneRecovery.resume(c)}){Text("Reconectează telefonul")}
        TextButton(onClick={LostPhoneRecovery.stopSearch(c);info="Căutarea s-a oprit. Găsirea rămâne activată."}){Text("Oprește căutarea curentă")}
        TextButton(onClick={LostPhoneRecovery.disable(c);enabled=false;consent=false;info="Găsire dezactivată."}){Text("Dezactivează găsirea")}
    } else {
        Text("Găsește-l din contul tău, dacă îl pierzi.",fontSize=13.sp)
        OutlinedTextField(name,{name=it.take(60)},label={Text("Numele telefonului")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it});Text("Permit contului meu localizarea din site, cu serviciu în fundal și notificare, până dezactivez găsirea.",fontSize=12.sp,modifier=Modifier.weight(1f))}
        Button(modifier=Modifier.fillMaxWidth(),enabled=!busy&&consent&&owner!=null&&name.isNotBlank(),onClick={permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text(if(busy)"Se activează…"else "Activează găsirea")}
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        TextButton(onClick={details=!details}){Text(if(details)"Ascunde setările"else "Setări")}
        TextButton(onClick={privacy=true}){Text("Confidențialitate")}
    }
    AnimatedVisibility(details){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Telefonul trebuie să aibă internet și locația Android pornită.",fontSize=12.sp)
        if(Build.VERSION.SDK_INT>=29&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED)Text("Pentru reconectare după restart: locație «Tot timpul».",fontSize=12.sp)
        TextButton(onClick={c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+c.packageName)))}){Text("Deschide setările Android")}
        if(!enabled)TextButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE)))}){Text("Deschide panoul meu")}
    }}
    if(info.isNotBlank())Text(info,fontSize=12.sp)
    if(privacy)ForjaPrivacyDialog{privacy=false}
}
