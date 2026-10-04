package com.forja.app.feature.cleanup

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
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
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private fun Context.activity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.activity();else->null}
@Composable internal fun PartnerContactsPanel(data:JSONObject?,owner:String?,onVisibility:()->Unit,refresh:suspend()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();var busy by remember(owner){mutableStateOf(false)};var info by remember(owner){mutableStateOf("")};var consent by remember(owner){mutableStateOf(false)}
 var privacy by remember{mutableStateOf(false)};var settings by remember{mutableStateOf(false)}
 val me=data?.optJSONObject("me");val partner=me?.optJSONObject("partner");val session=me?.optJSONObject("session");val visibility=me?.optJSONObject("visibility");val partnerAllowed=visibility?.optBoolean("configured")!=true||visibility.rows("grants").any{it.optString("id")==partner?.optString("id")&&it.optBoolean("current")}
 fun act(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner){"Contul s-a schimbat."};block();refresh()}catch(e:CancellationException){throw e}catch(e:Exception){info=e.message.orEmpty()}finally{busy=false}}}
 val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
  val location=result[Manifest.permission.ACCESS_COARSE_LOCATION]==true||result[Manifest.permission.ACCESS_FINE_LOCATION]==true;val notices=Build.VERSION.SDK_INT<33||result[Manifest.permission.POST_NOTIFICATIONS]==true
  if(!partnerAllowed){consent=false;onVisibility()}else if(location&&notices&&consent&&partner?.optString("state")=="accepted"&&FileSync.owner()==owner){try{ContextCompat.startForegroundService(c,Intent(c,SocialLocationService::class.java).putExtra("continuous",true).putExtra("audience",partner.getString("id")));consent=false;info="Pornesc partajarea continuă cu partenerul ales."}catch(e:Exception){info=e.message.orEmpty()}}else info="Sunt necesare locația, notificările și acordul pentru partenerul ales."
 }
 LaunchedEffect(partner?.optString("token"),owner){consent=false}
 Text("Mereu aproape",fontSize=22.sp,fontWeight=FontWeight.Bold)
 if(partner==null){
  Text("Alege partenerul din cercul tău.",fontSize=13.sp)
  if(data?.rows("friends").isNullOrEmpty())Text("Adaugă-l mai întâi în Prieteni.",fontSize=13.sp)
  data?.rows("friends")?.forEach{f->OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={act{SocialApi.call(c,"partner",SocialApi.obj("id" to f.getString("id"),"action" to "invite"))}}){Text(f.getString("name"))}}
 }else{
  Text(partner.optString("name"),fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
  when(partner.optString("state")){
   "requested"->Text("Invitație trimisă",fontSize=13.sp)
   "invited"->{Text("Vrea să fiți un cuplu în FORJA.",fontSize=13.sp);Button(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={act{SocialApi.call(c,"partner",SocialApi.obj("id" to partner.getString("id"),"action" to "accept"))}}){Text("Acceptă invitația")};Text("Locația rămâne oprită până o activezi tu.",fontSize=12.sp)}
   "accepted"->{
    if(session?.optBoolean("continuous")==true){
     Text("Partajare continuă activă",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
     if(!SocialLocationService.running&&SocialRecovery.active(c))OutlinedButton(onClick={SocialRecovery.resume(c)}){Text("Reconectează")}
     Button(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={SocialRecovery.stop(c);act{SocialApi.call(c,"session",method="DELETE")}}){Text("Oprește partajarea mea")}
    } else if(session!=null){
     Text("Partajezi acum cu prietenii.",fontSize=13.sp)
     OutlinedButton(enabled=!busy,onClick={SocialRecovery.stop(c);act{SocialApi.call(c,"session",method="DELETE")}}){Text("Oprește sesiunea curentă")}
    } else if(!partnerAllowed){
     Button(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick=onVisibility){Text("Alege cine te vede")}
    } else {
     Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it});Text("Îmi partajez continuu locația doar cu ${partner.optString("name")}, cu notificare și reluare automată, până o opresc.",fontSize=12.sp,modifier=Modifier.weight(1f))}
     Button(modifier=Modifier.fillMaxWidth(),enabled=consent&&!busy,onClick={permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text("Activează partajarea")}
    }
   }
  }
  TextButton(enabled=!busy,onClick={SocialRecovery.stop(c);act{SocialApi.call(c,"partner",SocialApi.obj("id" to partner.getString("id"),"action" to if(partner.optString("state")=="accepted")"disconnect"else "decline"))}}){Text(when(partner.optString("state")){"accepted"->"Încheie asocierea";"requested"->"Retrage invitația";else->"Refuză invitația"})}
 }
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={settings=!settings}){Text(if(settings)"Ascunde setările"else "Setări")};TextButton(onClick={privacy=true}){Text("Confidențialitate")}}
 AnimatedVisibility(settings){Column{
  if(Build.VERSION.SDK_INT>=29&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED)Text("Pentru reluare după restart: locație «Tot timpul».",fontSize=12.sp)
  TextButton(onClick={c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+c.packageName)))}){Text("Deschide setările Android")}
 }}
 if(info.isNotBlank())Text(info,fontSize=12.sp)
 if(privacy)ForjaPrivacyDialog{privacy=false}
}

@Composable internal fun ContactsPanel(me:JSONObject?,owner:String?,refresh:suspend()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();var message by remember(owner){mutableStateOf("")};var busy by remember(owner){mutableStateOf(false)};var book by remember(owner){mutableStateOf<List<PhoneContact>>(emptyList())};var matches by remember(owner){mutableStateOf(ContactSync.matches(c))}
 var setup by remember{mutableStateOf(false)};var privacy by remember{mutableStateOf(false)};var editNumber by remember(owner){mutableStateOf(false)};var countrySettings by remember{mutableStateOf(false)}
 var country by remember(owner){mutableStateOf(ContactSync.prefs(c).getString("region","RO")?:"RO")};var search by remember(owner){mutableStateOf("")};var shown by remember(owner){mutableIntStateOf(50)};var matchesShown by remember(owner){mutableIntStateOf(50)};var localAllowed by remember{mutableStateOf(ContactSync.allowed(c))};var syncConsent by remember(owner){mutableStateOf(ContactSync.enabled(c))};var directoryConsent by remember(owner){mutableStateOf(false)}
 var phone by remember(owner){mutableStateOf(FirebaseAuth.getInstance().currentUser?.phoneNumber.orEmpty())};var code by remember(owner){mutableStateOf("")};var verification by remember(owner){mutableStateOf<String?>(null)};var lastSMS by remember{mutableLongStateOf(0)};var verified by remember(owner){mutableStateOf(!FirebaseAuth.getInstance().currentUser?.phoneNumber.isNullOrBlank())}
 fun act(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner){"Contul s-a schimbat."};block();refresh()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message.orEmpty()}finally{busy=false}}}
 fun readBook(){act{book=ContactSync.read(c,country.uppercase());shown=50;message="${book.size} numere în agenda ta."}}
 val readPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->localAllowed=ok;if(ok)readBook()else message="Accesul la agendă este oprit. Poți folosi codurile de invitație."}
 fun link(credential:PhoneAuthCredential){act{val user=checkNotNull(FirebaseAuth.getInstance().currentUser);check(user.uid==owner);if(user.providerData.any{it.providerId=="phone"}){ContactSync.stop(c);matches=emptyList();SocialApi.call(c,"contacts/discovery",method="DELETE",owner=user.uid);user.updatePhoneNumber(credential).await()}else user.linkWithCredential(credential).await();check(FileSync.owner()==owner);user.getIdToken(true).await();phone=user.phoneNumber.orEmpty();verified=true;editNumber=false;verification=null;code="";message="Număr verificat."}}
 Text(if(matches.isEmpty())"Ne găsim mai ușor"else "${matches.size} contacte în FORJA",fontSize=21.sp,fontWeight=FontWeight.Bold)
 Button(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={setup=!setup}){Text(if(setup)"Închide configurarea"else "Găsește-mi prietenii")}
 OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={if(ContactSync.allowed(c)){localAllowed=true;readBook()}else readPermission.launch(Manifest.permission.READ_CONTACTS)}){Text(if(localAllowed)"Deschide agenda"else "Permite accesul la contacte")}
 if(ContactSync.enabled(c)){
  Text("Sincronizare zilnică activă",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
  TextButton(onClick={ContactSync.stop(c);matches=emptyList();syncConsent=false;message="Sincronizare oprită."}){Text("Oprește sincronizarea")}
 }
 AnimatedVisibility(setup){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
 if(!verified||editNumber){
 Text("Verifică numărul tău",fontWeight=FontWeight.Bold)
 OutlinedTextField(phone,{phone=it;verified=false;verification=null;code=""},label={Text("Numărul tău · +40…")},singleLine=true,modifier=Modifier.fillMaxWidth())
 TextButton(enabled=!busy&&Regex("\\+[1-9][0-9]{7,14}").matches(phone),onClick={
  val activity=c.activity();if(activity==null){message="Deschide acest ecran în aplicație."}else if(System.currentTimeMillis()-lastSMS<60000){message="Așteaptă un minut înainte să ceri alt SMS."}else{
   lastSMS=System.currentTimeMillis();verification=null;code="";message="Solicit codul SMS…";val requestingOwner=owner
   try{val callbacks=object:PhoneAuthProvider.OnVerificationStateChangedCallbacks(){
    override fun onVerificationCompleted(credential:PhoneAuthCredential){if(FileSync.owner()==requestingOwner)link(credential)}
    override fun onVerificationFailed(e:FirebaseException){if(FileSync.owner()==requestingOwner)message="Nu am putut verifica numărul. ${e.message.orEmpty()}"}
    override fun onCodeSent(id:String,token:PhoneAuthProvider.ForceResendingToken){if(FileSync.owner()==requestingOwner){verification=id;message="Introdu codul primit prin SMS."}}
   };PhoneAuthProvider.verifyPhoneNumber(PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance()).setPhoneNumber(phone).setTimeout(60L,TimeUnit.SECONDS).setActivity(activity).setCallbacks(callbacks).build())}catch(e:Exception){message=e.message.orEmpty()}
  }
 }){Text(if(verified)"Verifică din nou"else "Trimite codul SMS")}
 if(verification!=null){OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("Cod SMS")},modifier=Modifier.fillMaxWidth());Button(enabled=!busy&&code.length==6,onClick={link(PhoneAuthProvider.getCredential(verification!!,code))}){Text("Confirmă numărul")}}
 }else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("Număr verificat",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary);TextButton(onClick={editNumber=true}){Text("Schimbă")}}
 if(me?.optBoolean("discoverable")==true){Text("Poți fi găsit după număr",fontSize=12.sp);TextButton(enabled=!busy,onClick={ContactSync.stop(c);matches=emptyList();act{SocialApi.call(c,"contacts/discovery",method="DELETE");message="Nu mai poți fi găsit după număr."}}){Text("Nu mai permite găsirea după număr")}}
 else if(verified){Row(verticalAlignment=Alignment.CenterVertically){Checkbox(directoryConsent,{directoryConsent=it});Text("Cei care au numărul meu mă pot găsi în FORJA.",fontSize=12.sp,modifier=Modifier.weight(1f))};Button(enabled=directoryConsent&&verified&&!busy,onClick={act{ContactSync.discovery(c);message="Găsirea după număr a fost activată."}}){Text("Permite găsirea")}}
 if(me?.optBoolean("discoverable")==true){
 Row(verticalAlignment=Alignment.CenterVertically){Checkbox(syncConsent,{syncConsent=it});Text("Compară online numerele din agendă, fără nume. Repetă zilnic până opresc sincronizarea.",fontSize=12.sp,modifier=Modifier.weight(1f))}
 Button(enabled=!busy&&syncConsent&&ContactSync.allowed(c)&&me?.optBoolean("discoverable")==true,onClick={act{ContactSync.enable(c,country);ContactSync.sync(c,true);matches=ContactSync.matches(c);message=ContactSync.prefs(c).getString("message","").orEmpty()}}){Text("Sincronizează acum")}
 }
 TextButton(onClick={countrySettings=!countrySettings}){Text("Țară · $country") }
 if(countrySettings)OutlinedTextField(country,{country=it.uppercase().take(2)},label={Text("Cod țară · RO")},singleLine=true,modifier=Modifier.fillMaxWidth())
 }}
 TextButton(onClick={privacy=true}){Text("Confidențialitate")}
 if(matches.isNotEmpty()){Text("Pe cine recunoști?",fontWeight=FontWeight.Bold);matches.take(matchesShown).forEach{m->Text("${m.optString("contact_name")} · ${m.optString("number")}\nFORJA: ${m.optString("name")}",fontSize=13.sp);val friend=me?.strings("friends")?.contains(m.optString("id"))==true||m.optBoolean("friend");TextButton(enabled=!busy&&!friend&&!m.optBoolean("pending"),onClick={act{val number=m.getString("number");val fresh=SocialApi.call(c,"contacts/match",SocialApi.obj("numbers" to org.json.JSONArray(listOf(number)),"consent" to true)).rows("matches").firstOrNull{it.optString("id")==m.optString("id")};checkNotNull(fresh){"Contactul nu mai poate fi găsit."};SocialApi.call(c,"contacts/invite",SocialApi.obj("id" to fresh.getString("id"),"proof" to fresh.getString("proof")));matches=matches.map{if(it.optString("id")==m.optString("id"))JSONObject(it.toString()).put("pending",true)else it};message="Cerere trimisă."}}){Text(if(friend)"Sunteți prieteni"else if(m.optBoolean("pending"))"Cerere trimisă"else "Adaugă")}};if(matchesShown<matches.size)TextButton(onClick={matchesShown+=50}){Text("Arată încă 50 contacte FORJA")}}
 if(book.isNotEmpty()){OutlinedTextField(search,{search=it;shown=50},label={Text("Caută în agenda locală")},modifier=Modifier.fillMaxWidth());val filtered=book.filter{it.name.contains(search,true)||it.number.contains(search)||it.normalized?.contains(search)==true};Text("${filtered.size} numere · afișez ${shown.coerceAtMost(filtered.size)}",fontSize=12.sp);filtered.take(shown).forEach{entry->Text(entry.name,fontWeight=FontWeight.Bold);Text(entry.number,fontSize=12.sp);TextButton(onClick={runCatching{c.startActivity(Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",entry.normalized?:entry.number,null)).putExtra("sms_body","Hai în FORJA! Codul meu de invitație: "+me?.optString("code").orEmpty()))}.onFailure{message="Nu există o aplicație SMS disponibilă."}}){Text("Invită prin SMS")}};if(shown<filtered.size)TextButton(onClick={shown+=50}){Text("Arată încă 50")}}
 if(message.isNotBlank())Text(message,fontSize=12.sp)
 if(privacy)ForjaPrivacyDialog{privacy=false}
}
