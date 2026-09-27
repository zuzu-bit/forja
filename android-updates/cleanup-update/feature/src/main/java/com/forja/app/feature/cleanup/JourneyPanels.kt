package com.forja.app.feature.cleanup

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun journeyCollection()=JSONObject().put("type","FeatureCollection").put("features",JSONArray())
internal fun journeyPoint(kind:String,id:String,lat:Double,lon:Double,name:String)=JSONObject().put("type","Feature").put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray().put(lon).put(lat))).put("properties",JSONObject().put("id",id).put("kind",kind).put("name",name).put("label",name.split(' ').take(2).joinToString(""){it.take(1)}))
internal fun journeyMapData(social:JSONObject?,journal:JSONObject?,now:Long,legacyExplored:Boolean=false):JSONObject{
 val people=journeyCollection();val me=social?.optJSONObject("me")
 val persons=buildList{if(me!=null)add(JSONObject(me.toString()).put("name","Tu"));addAll(social?.rows("friends").orEmpty())}
 for(p in persons){val l=p.optJSONObject("location")?:continue;if(l.optLong("at")<=now-120000)continue;people.getJSONArray("features").put(journeyPoint("person",p.optString("id"),l.optDouble("lat"),l.optDouble("lon"),p.optString("name")))}
 val visits=journeyCollection();for(v in journal?.rows("visits").orEmpty())visits.getJSONArray("features").put(journeyPoint("visit",v.optString("id"),v.optDouble("lat"),v.optDouble("lon"),v.optString("name","Loc vizitat")))
 val places=journeyCollection();for(p in me?.rows("places").orEmpty())places.getJSONArray("features").put(journeyPoint("place",p.optString("id"),p.optDouble("lat"),p.optDouble("lon"),p.optString("name")))
 val zones=JSONObject((journal?.optJSONObject("zones")?:journeyCollection()).toString())
 if(legacyExplored){val cells=me?.optJSONArray("explored")?:JSONArray();for(n in 0 until cells.length()){val cell=cells.getJSONArray(n);val lat=cell.getDouble(0);val lon=cell.getDouble(1);val ring=JSONArray();for((x,y) in listOf(lon to lat,(lon+.001) to lat,(lon+.001) to (lat+.001),lon to (lat+.001),lon to lat))ring.put(JSONArray().put(x).put(y));zones.getJSONArray("features").put(JSONObject().put("type","Feature").put("properties",JSONObject().put("id","legacy-$n")).put("geometry",JSONObject().put("type","Polygon").put("coordinates",JSONArray().put(ring))))}}
 return JSONObject().put("people",people).put("visits",visits).put("places",places).put("routes",journal?.optJSONObject("routes")?:journeyCollection()).put("zones",zones)
}
@Composable internal fun JourneyPanel(journal:JSONObject?,owner:String?,refresh:suspend()->Unit,loadMore:suspend()->Unit,onVisit:(JSONObject)->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();var consent by remember(owner){mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var erase by remember{mutableStateOf(false)}
 val status by JourneyRecorder.status.collectAsState()
 fun act(update:Boolean=true,block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner);block();if(update)refresh()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}
 val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(consent&&FileSync.owner()==owner&&(result[Manifest.permission.ACCESS_FINE_LOCATION]==true||result[Manifest.permission.ACCESS_COARSE_LOCATION]==true)&&(Build.VERSION.SDK_INT<33||result[Manifest.permission.POST_NOTIFICATIONS]==true)){JourneyRecorder.start(c);consent=false}else error="Permite locația și notificările pentru explorare."}
 Text("Harta mea",style=MaterialTheme.typography.headlineSmall)
 Text("━━ Trasee  ▩ Zone  ● Vizite > 5 h",fontSize=13.sp)
 if(JourneyRecorder.active(c))Button(onClick={JourneyRecorder.stop(c);act{JourneyRecorder.sync(c)}}){Text("Oprește explorarea")}
 else{
  Row{Checkbox(consent,{consent=it});Text("Păstrez traseele și locurile în contul meu, inclusiv cu ecranul blocat. Partajarea se alege separat.",fontSize=12.sp,modifier=Modifier.weight(1f))}
  Button(enabled=consent&&!busy&&owner!=null,onClick={permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text("Explorează")}
 }
 if(status.isNotBlank())Text(status,fontSize=12.sp)
 Text("${journal?.rows("visits")?.size?:0} locuri vizitate · ${journal?.optJSONObject("zones")?.optJSONArray("features")?.length()?:0} celule explorate",fontSize=13.sp)
 for(v in journal?.rows("visits").orEmpty())OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onVisit(v)}){Text(v.optString("name").ifBlank{"Loc vizitat"}+" · "+(v.optLong("observed_ms")/3600000.0).let{"%.1f h".format(it)})}
 if(journal?.optString("next_cursor")?.takeUnless{it=="null"||it.isBlank()}!=null)TextButton(enabled=!busy,onClick={act(false){loadMore()}}){Text("Încarcă mai mult din istoric")}
 Text("Grilă de ${journal?.optJSONObject("rules")?.optInt("grid_m")?:200} m proiectați. Golurile GPS nu sunt timp petrecut într-un loc. Harta arată observații, nu străzi parcurse integral.",fontSize=12.sp)
 TextButton(enabled=!busy,onClick={erase=true}){Text("Șterge istoricul meu")}
 if(error.isNotBlank())Text(error,fontSize=12.sp)
 if(erase)AlertDialog(onDismissRequest={erase=false},title={Text("Ștergi istoricul?")},text={Text("Traseele, zonele și vizitele salvate vor fi șterse din cont. Explorarea se oprește.")},confirmButton={TextButton(onClick={erase=false;JourneyRecorder.stop(c);act{SocialApi.call(c,"journey/history",method="DELETE");JourneyRecorder.schedule(c)}}){Text("Șterge")}},dismissButton={TextButton(onClick={erase=false}){Text("Renunță")}})
}
@Composable internal fun JourneyVisitPanel(visit:JSONObject,friends:List<JSONObject>,owner:String?,refresh:suspend()->Unit,onNavigate:()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();var name by remember(visit.optString("id")){mutableStateOf(visit.optString("name"))};var rating by remember(visit.optString("id")){mutableIntStateOf(visit.optInt("rating",0))};var info by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var recommend by remember{mutableStateOf(false)}
 fun act(update:Boolean=true,block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner);block();if(update)refresh()}catch(e:CancellationException){throw e}catch(e:Exception){info=e.message.orEmpty()}finally{busy=false}}}
 Text(name.ifBlank{"Loc vizitat"},style=MaterialTheme.typography.headlineSmall)
 Text("%.1f ore confirmate".format(visit.optLong("observed_ms")/3600000.0),fontSize=14.sp)
 Button(onClick=onNavigate){Text("Hai aici ↗")}
 OutlinedTextField(name,{name=it.take(80)},label={Text("Numele locului")},modifier=Modifier.fillMaxWidth())
 Row(Modifier.horizontalScroll(rememberScrollState())){(1..5).forEach{n->TextButton(onClick={rating=n}){Text(if(n<=rating)"★"else"☆")}}}
 Button(enabled=!busy&&name.isNotBlank()&&rating in 1..5,onClick={act{SocialApi.call(c,"journey/visits/"+visit.getString("id"),SocialApi.obj("name" to name,"rating" to rating),"PATCH");info="Loc salvat"}}){Text("Salvează")}
 TextButton(enabled=!busy,onClick={act{SocialApi.call(c,"place",SocialApi.obj("name" to name.ifBlank{"Loc vizitat"},"lat" to visit.getDouble("lat"),"lon" to visit.getDouble("lon")));info="Loc păstrat pentru mai târziu"}}){Text("Păstrează pentru mai târziu")}
 TextButton(onClick={recommend=!recommend}){Text("Recomandă unui prieten")}
 if(recommend)friends.forEach{f->OutlinedButton(enabled=!busy,onClick={act{SocialApi.call(c,"journey/recommend",SocialApi.obj("visit" to visit.getString("id"),"friend" to f.getString("id"),"id" to UUID.randomUUID().toString()));info="Recomandare trimisă";recommend=false}}){Text(f.optString("name"))}}
 if(info.isNotBlank())Text(info,fontSize=12.sp)
}
@Composable internal fun VisibilityPanel(data:JSONObject?,owner:String?,onExplore:()->Unit,refresh:suspend()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();var revision by remember(owner){mutableLongStateOf(0)};var ghost by remember(owner){mutableStateOf(true)};var grants by remember(owner){mutableStateOf<Map<String,JSONObject>>(emptyMap())};var consent by remember(owner){mutableStateOf(false)};var ready by remember{mutableStateOf(false)};var info by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
 suspend fun reload(){val state=SocialApi.call(c,"visibility");if(FileSync.owner()==owner){ghost=state.optBoolean("ghost",true);revision=state.optLong("revision",0);grants=state.rows("grants").associateBy{it.getString("id")};consent=false;ready=true}}
 LaunchedEffect(owner){try{reload()}catch(e:CancellationException){throw e}catch(e:Exception){info=e.message.orEmpty()}}
 Text("Cine mă vede",style=MaterialTheme.typography.headlineSmall)
 Row{Switch(ghost,{ghost=it;consent=false});Text("Fantomă",Modifier.padding(12.dp))}
 Text(if(ghost)"Vizibil doar pentru excepțiile alese mai jos."else"Vizibilitatea urmează acordurile și sesiunea activă.",fontSize=12.sp)
 for(f in data?.rows("friends").orEmpty()){
  val id=f.getString("id");val grant=grants[id]?:JSONObject().put("id",id).put("current",false).put("ghost",false).put("history",false)
  Text(f.optString("name"),style=MaterialTheme.typography.titleMedium)
  for(key in listOf("current","ghost","history"))Row{
   Checkbox(grant.optBoolean(key),{value->val updated=JSONObject(grant.toString()).put(key,value);if(key=="current"&&!value)updated.put("ghost",false);grants=grants+(id to updated);consent=false},enabled=key!="ghost"||grant.optBoolean("current"))
   Text(when(key){"current"->"Locația curentă";"ghost"->"Inclusiv în Fantomă";else->"Trasee și locuri din istoric"},Modifier.padding(top=12.dp),fontSize=13.sp)
  }
 }
 Row{Checkbox(consent,{consent=it});Text("Permit accesul ales pentru fiecare persoană. Istoricul este un acord separat.",fontSize=12.sp,modifier=Modifier.weight(1f))}
 Button(enabled=ready&&consent&&!busy,onClick={busy=true;scope.launch{try{check(FileSync.owner()==owner);SocialApi.call(c,"visibility",SocialApi.obj("ghost" to ghost,"grants" to JSONArray(grants.values.toList()),"consent" to true,"revision" to revision));refresh();reload();info="Vizibilitate salvată";consent=false}catch(e:CancellationException){throw e}catch(e:Exception){consent=false;info=e.message.orEmpty();if(e is FileSync.Failure&&e.code==409){ready=false;try{reload();info="Vizibilitatea s-a schimbat. Alege și confirmă din nou persoanele."}catch(reloadError:CancellationException){throw reloadError}catch(reloadError:Exception){info=reloadError.message.orEmpty()}}}finally{busy=false}}}){Text("Salvează")}
 TextButton(enabled=!busy,onClick={SocialRecovery.stop(c);busy=true;scope.launch{try{SocialApi.call(c,"session",method="DELETE");grants=emptyMap();ghost=true;refresh();reload();info="Toate partajările și accesul la istoric au fost oprite."}catch(e:CancellationException){throw e}catch(e:Exception){info=e.message.orEmpty()}finally{busy=false}}}){Text("Oprește toate partajările")}
 Text("Locația curentă folosește explorarea pornită explicit. Acordul singur nu pornește GPS-ul. Istoricul se acordă separat.",fontSize=12.sp)
 if(!JourneyRecorder.active(c))OutlinedButton(onClick=onExplore){Text("Pornește explorarea")}
 Text("Explorarea personală poate continua în privat. O etichetă de familie nu acordă acces.",fontSize=12.sp)
 if(info.isNotBlank())Text(info,fontSize=12.sp)
}

internal fun mergeJourney(old:JSONObject?,page:JSONObject):JSONObject{if(old==null)return page;val merged=JSONObject(page.toString());for(key in listOf("routes","zones")){val all=old.optJSONObject(key)?.rows("features").orEmpty()+page.optJSONObject(key)?.rows("features").orEmpty();merged.put(key,journeyCollection().put("features",JSONArray(all.associateBy{it.optJSONObject("properties")?.optString("id")}.values.toList())))};merged.put("visits",JSONArray((old.rows("visits")+page.rows("visits")).associateBy{it.optString("id")}.values.toList()));return merged}
