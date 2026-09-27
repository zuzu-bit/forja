package com.forja.app.feature.cleanup

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.core.view.doOnLayout
import org.osmdroid.views.CustomZoomButtonsController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date
import java.util.UUID

internal fun JSONObject.rows(key:String):List<JSONObject>{val a=optJSONArray(key)?:return emptyList();return (0 until a.length()).map{a.getJSONObject(it)}}
internal fun JSONObject.strings(key:String):List<String>{val a=optJSONArray(key)?:return emptyList();return (0 until a.length()).map{a.getString(it)}}
private fun label(mode:String)=when(mode){"partner"->"Cuplu";"cycle"->"Bicicletă";"walk"->"Mers";else->"În oraș"}
private val green=Color(0xFFD3EBAB)
private val dark=Color(0xFF15241D)
private fun initials(name:String)=name.trim().split(Regex("\\s+")).take(2).mapNotNull{it.firstOrNull()}.joinToString("").uppercase().take(2)
private fun icon(c:Context,name:String,own:Boolean):BitmapDrawable {
 val size=(60*c.resources.displayMetrics.density).toInt();val b=Bitmap.createBitmap(size,size+size/6,Bitmap.Config.ARGB_8888);val canvas=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG);val r=size/2f
 p.color=if(own)0xFFD3EBAB.toInt()else 0xFFFFCC8A.toInt();canvas.drawCircle(r,r,r-3,p);p.color=0xFF15241D.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=4f;canvas.drawCircle(r,r,r-3,p);p.style=Paint.Style.FILL;p.textSize=size*.30f;p.textAlign=Paint.Align.CENTER;p.isFakeBoldText=true;canvas.drawText(initials(name),r,r-(p.ascent()+p.descent())/2,p);canvas.drawCircle(r,size+2f,size/12f,p);return BitmapDrawable(c.resources,b)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SocialMapScreen(onBack:()->Unit={}) {
 val c=LocalContext.current;val scope=rememberCoroutineScope();var owner by remember{mutableStateOf(FileSync.owner())};var clock by remember{mutableLongStateOf(System.currentTimeMillis())}
 var journalPages by remember(owner){mutableIntStateOf(1)};val journalLock=remember(owner){kotlinx.coroutines.sync.Mutex()};var journal by remember(owner){mutableStateOf<JSONObject?>(null)};var journalOwner by remember(owner){mutableStateOf<String?>(null)};var selectedVisit by remember(owner){mutableStateOf<JSONObject?>(null)}
 var vectorReady by remember(owner){mutableStateOf(false)};var vectorError by remember(owner){mutableStateOf(false)};var threeD by remember(owner){mutableStateOf(false)};var vectorLoading by remember(owner){mutableStateOf(false)};var vectorCenter by remember(owner){mutableStateOf<GeoPoint?>(null)}
 var data by remember(owner){mutableStateOf<JSONObject?>(null)};var error by remember(owner){mutableStateOf("")};var busy by remember{mutableStateOf(false)}
 var pane by remember{mutableStateOf("friends")};var sheet by remember{mutableStateOf<String?>(null)};var pickingPlace by remember{mutableStateOf<String?>(null)};var selectedPoint by remember{mutableStateOf<GeoPoint?>(null)};var activity by remember{mutableStateOf("walk")};var minutes by remember{mutableIntStateOf(60)};var consent by remember{mutableStateOf(false)}
 var friend by remember(owner){mutableStateOf<JSONObject?>(null)};var chat by remember{mutableStateOf<List<JSONObject>>(emptyList())};var message by remember{mutableStateOf("")}
 var name by remember(owner){mutableStateOf("")};var invite by remember{mutableStateOf("")};var placeName by remember{mutableStateOf("")};var groupName by remember{mutableStateOf("")};var groupFriends by remember{mutableStateOf<Set<String>>(emptySet())};var groupMode by remember{mutableStateOf("walk")};var groupHours by remember{mutableIntStateOf(1)};var explore by remember{mutableStateOf(false)}
 var showPrivacy by remember{mutableStateOf(false)};var note by remember{mutableStateOf("")}
 LaunchedEffect(note){if(note.isNotBlank()){delay(2500);note=""}}
 val status by SocialApi.status.collectAsState()
 val map=remember(c){Configuration.getInstance().userAgentValue="FORJA/3.7-online.23 (${c.packageName})";MapView(c).apply{setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true);zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER);minZoomLevel=3.0;maxZoomLevel=19.0;controller.setZoom(7.0);controller.setCenter(GeoPoint(45.8,24.9))}}
 val vector=remember(c,owner){JourneyMapView(c){event->when(event.optString("type")){
  "loading"->{vectorReady=false;vectorError=false;threeD=false;vectorLoading=false}
  "ready"->{vectorReady=true;vectorError=false;threeD=false;vectorLoading=false}
  "mode"->{threeD=event.optString("mode")=="3d";vectorLoading=event.optBoolean("loading")}
  "error"->{if(!event.optBoolean("recoverable")){vectorReady=false;vectorError=true};threeD=false;vectorLoading=false;note=event.optString("message","Harta 2D rămâne disponibilă.")}
  "move"->{val p=event.optJSONObject("point");if(p!=null&&p.optDouble("lat").isFinite()&&p.optDouble("lon").isFinite())vectorCenter=GeoPoint(p.optDouble("lat"),p.optDouble("lon"))}
  "pick"->{val p=event.optJSONObject("item");if(p!=null)when(p.optString("kind")){"person"->{friend=data?.rows("friends")?.find{it.optString("id")==p.optString("id")};if(friend!=null)sheet="friend"};"visit"->{selectedVisit=journal?.rows("visits")?.find{it.optString("id")==p.optString("id")};if(selectedVisit!=null)sheet=if(journalOwner==null)"visit"else"shared-visit"};"place"->{selectedPoint=GeoPoint(p.optDouble("lat"),p.optDouble("lon"));sheet="places"}}}
 }}}
 DisposableEffect(vector){onDispose{vector.close()}}
 suspend fun refreshJourney(more:Boolean=false){journalLock.lock();try{val target=journalOwner;try{
  var cursor=if(more)journal?.optString("next_cursor")?.takeUnless{it=="null"||it.isBlank()}else null
  if(more&&cursor==null)return
  var combined:JSONObject?=if(more)journal else null
  val count=if(more)1 else journalPages
  for(n in 0 until count){val query=buildList{if(target!=null)add("owner="+Uri.encode(target));if(cursor!=null)add("cursor="+Uri.encode(cursor))}.joinToString("&")
   val result=SocialApi.call(c,"journey/state"+if(query.isEmpty())""else"?$query")
   if(FileSync.owner()!=owner||target!=journalOwner)return
   combined=mergeJourney(combined,result);cursor=result.optString("next_cursor").takeUnless{it=="null"||it.isBlank()};if(cursor==null)break
  }
  journal=combined;if(more)journalPages++
 }catch(e:CancellationException){throw e}catch(e:Exception){if(target!=null){journal=null;selectedVisit=null;sheet="shared-history"};error=e.message.orEmpty()}}
 finally{journalLock.unlock()}}

 fun focus(lat:Double,lon:Double,zoom:Double=16.0){map.controller.setZoom(zoom);map.controller.animateTo(GeoPoint(lat,lon));vector.focus(lat,lon,zoom)}
 suspend fun refresh(){try{val result=SocialApi.call(c,"state");if(FileSync.owner()==owner){data=result;error="";if(name.isBlank())name=result.getJSONObject("me").getString("name")}}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Nu pot încărca harta."}}
 fun action(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner){"Contul s-a schimbat."};block();refresh()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}
 fun navigate(lat:Double,lon:Double){runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("geo:$lat,$lon?q=$lat,$lon")))}.onFailure{error="Nu există o aplicație de navigare instalată."}}
 fun begin(){val v=data?.optJSONObject("me")?.optJSONObject("visibility");if(v?.optBoolean("configured")==true&&v.rows("grants").none{it.optBoolean("current")}){consent=false;sheet="visibility";return};try{ContextCompat.startForegroundService(c,Intent(c,SocialLocationService::class.java).putExtra("mode",activity).putExtra("minutes",minutes));sheet=null;consent=false}catch(e:Exception){error=e.message.orEmpty()}}
 val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
  val location=result[Manifest.permission.ACCESS_COARSE_LOCATION]==true||result[Manifest.permission.ACCESS_FINE_LOCATION]==true
  val notifications=Build.VERSION.SDK_INT<33||result[Manifest.permission.POST_NOTIFICATIONS]==true
  if(location&&notifications)begin()else error="Pentru partajare acordă locația și notificările. Poți folosi în continuare harta și prietenii."
 }
 DisposableEffect(Unit){val auth=com.google.firebase.auth.FirebaseAuth.getInstance();val listener=com.google.firebase.auth.FirebaseAuth.AuthStateListener{owner=it.currentUser?.uid};auth.addAuthStateListener(listener);onDispose{auth.removeAuthStateListener(listener)}}
 LaunchedEffect(owner){friend=null;sheet=null;consent=false;pickingPlace=null;selectedPoint=null;invite=""}
 LaunchedEffect(Unit){while(isActive){delay(5000);clock=System.currentTimeMillis()}}
 LaunchedEffect(owner){JourneyRecorder.resume(c);if(owner==null){error="Conectează-te în FORJA pentru prieteni și activități.";return@LaunchedEffect};while(isActive){refresh();refreshJourney();delay(15000)}}
 LaunchedEffect(friend?.optString("id")){chat=emptyList();message="";val id=friend?.optString("id")?:return@LaunchedEffect;while(isActive){try{chat=SocialApi.call(c,"chat?friend="+Uri.encode(id)).rows("messages")}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()};delay(8000)}}
 DisposableEffect(map){map.onResume();onDispose{map.onPause();map.onDetach()}}
 val me=data?.optJSONObject("me");val friends=data?.rows("friends").orEmpty();val session=me?.optJSONObject("session")
 var centered by remember(owner){mutableStateOf(false)}
 val previous=remember(owner){mutableMapOf<String,GeoPoint>()}
 val movements=remember{mutableListOf<android.animation.ValueAnimator>()}
 DisposableEffect(Unit){onDispose{movements.forEach{it.cancel()}}}
 LaunchedEffect(data,journal,explore,pane,clock,vectorReady){
  vector.data(journeyMapData(data,journal,clock,explore))
  movements.forEach{it.cancel()};movements.clear();map.overlays.clear()
  if(explore){val cells=me?.optJSONArray("explored")?:JSONArray();for(i in 0 until cells.length()){val a=cells.getJSONArray(i);val lat=a.getDouble(0)+0.0005;val lon=a.getDouble(1)+0.0005;val polygon=Polygon(map);polygon.points=(0..6).map{j->val angle=j*Math.PI/3;GeoPoint(lat+0.00055*kotlin.math.sin(angle),lon+0.0007*kotlin.math.cos(angle))};polygon.fillPaint.color=0x773BBE88;polygon.outlinePaint.color=0xAA248E66.toInt();polygon.outlinePaint.strokeWidth=2f;map.overlays.add(polygon)}}
  fun marker(v:JSONObject,title:String,own:Boolean,click:(()->Unit)?=null){val lat=v.optDouble("lat",Double.NaN);val lon=v.optDouble("lon",Double.NaN);if(!lat.isFinite()||!lon.isFinite())return;val m=Marker(map);val target=GeoPoint(lat,lon);m.position=target;val before=previous.put(title,target)
   if(before!=null&&android.animation.ValueAnimator.areAnimatorsEnabled()&&before.distanceToAsDouble(target) in 2.0..2000.0){val animator=android.animation.ValueAnimator.ofFloat(0f,1f);animator.duration=750;animator.addUpdateListener{a->val f=(a.animatedValue as Float).toDouble();m.position=GeoPoint(before.latitude+(lat-before.latitude)*f,before.longitude+(lon-before.longitude)*f);map.invalidate()};movements.add(animator);animator.start()};m.title=title;m.icon=icon(c,title,own);m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);m.setOnMarkerClickListener{_,_->map.controller.animateTo(m.position);click?.invoke();true};map.overlays.add(m)}
  me?.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000}?.let{marker(it,"Tu",true);if(!centered){focus(it.getDouble("lat"),it.getDouble("lon"),15.0);centered=true}}
  for(f in friends)f.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000}?.let{marker(it,f.getString("name"),false){if(pickingPlace==null){friend=f;sheet="friend"}}}
  if(pane=="places")for(p in me?.rows("places").orEmpty())marker(p,p.getString("name"),false)
  for(f in journal?.optJSONObject("routes")?.rows("features").orEmpty()){val points=f.optJSONObject("geometry")?.optJSONArray("coordinates")?:continue;val line=Polyline(map);line.setPoints((0 until points.length()).map{val a=points.getJSONArray(it);GeoPoint(a.getDouble(1),a.getDouble(0))});line.outlinePaint.color=0xff4f92ff.toInt();line.outlinePaint.strokeWidth=6f;map.overlays.add(line)}
  for(f in journal?.optJSONObject("zones")?.rows("features").orEmpty()){val points=f.optJSONObject("geometry")?.optJSONArray("coordinates")?.optJSONArray(0)?:continue;val area=Polygon(map);area.points=(0 until points.length()).map{val a=points.getJSONArray(it);GeoPoint(a.getDouble(1),a.getDouble(0))};area.fillPaint.color=0x4471c99b;area.outlinePaint.color=0x9937875d.toInt();map.overlays.add(area)}
  for(v in journal?.rows("visits").orEmpty()){val marker=Marker(map);marker.position=GeoPoint(v.getDouble("lat"),v.getDouble("lon"));marker.title=v.optString("name","Loc vizitat");marker.icon=icon(c,"★",false);marker.setOnMarkerClickListener{_,_->selectedVisit=v;sheet=if(journalOwner==null)"visit"else"shared-visit";true};map.overlays.add(marker)}
  map.invalidate()
 }
 val ownLocation=me?.optJSONObject("location")
 val ownFresh=ownLocation?.optLong("at",0)?.let{it>clock-120000}==true
 val incoming=data?.rows("incoming").orEmpty()
 // MainNav draws ForjaTabBar over the NavHost: 54dp home + 10dp top + 16dp padding.
 // Keep map controls above that bar, in addition to the system navigation inset.
 val hostTabBarHeight=80.dp
 val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
 fun openSheet(id:String){sheet=id;pickingPlace=null;if(id!="friend")friend=null;pane=if(id in listOf("places","place","group"))"places"else "friends"}
 fun closeSheet(){sheet=null;friend=null}
 fun choosePlace(kind:String){selectedPoint?.let{map.controller.animateTo(it)};pickingPlace=kind;pane="places";closeSheet()}
 fun copyCode(){val code=me?.optString("code").orEmpty();if(code.isNotBlank()){(c.getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cod FORJA",code));note="Cod copiat"}}
 BackHandler(enabled=pickingPlace!=null&&sheet==null){val kind=pickingPlace;openSheet(if(kind=="group")"groups"else "places")}
 val sheetTitle=when(sheet){"journey"->"Explorare";"visibility"->"Vizibilitate";"visit"->"Locul meu";"shared-history"->"Istoric partajat";"shared-visit"->"Loc partajat";"menu"->"Pe hartă";"friends"->"Cercul tău";"couple"->"Voi doi";"contacts"->"Prieteni din contacte";"lost"->"Telefonul meu";"places"->"Locurile tale";"groups"->"Planuri împreună";"you"->"Profil și explorare";"invite"->"Mai frumos împreună";"share"->"Ieșim împreună?";"place"->"Loc de păstrat";"group"->"Un plan împreună";"friend"->"";else->"Conexiune"}
 MaterialTheme(colorScheme=darkColorScheme(primary=green,onPrimary=dark,background=dark,surface=Color(0xFF20332A),onSurface=Color(0xFFF1F5E9))){
  Box(Modifier.fillMaxSize().background(dark)){
   AndroidView(factory={map.apply{doOnLayout{if(!centered){controller.setZoom(7.0);controller.setCenter(GeoPoint(45.8,24.9))}}}},modifier=Modifier.fillMaxSize())
   if(!vectorError)AndroidView(factory={vector},modifier=Modifier.fillMaxSize().zIndex(if(vectorReady)0f else -1f))
   Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
     Surface(onClick={openSheet("menu")},shape=CircleShape,color=dark,shadowElevation=3.dp){
      Row(Modifier.heightIn(min=48.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
       MapGlyph("menu",green);Text("Hartă",fontWeight=FontWeight.SemiBold,fontSize=14.sp)
      }
     }
     if(vectorReady)OutlinedButton(enabled=!vectorLoading,onClick={vector.mode(!threeD)},modifier=Modifier.heightIn(min=48.dp)){Text(if(vectorLoading)"…"else if(threeD)"2D"else"3D")}
     if(session==null&&data?.optJSONObject("me")?.optJSONObject("visibility")?.rows("grants").isNullOrEmpty())Button(enabled=owner!=null&&!busy,onClick={consent=false;val v=me?.optJSONObject("visibility");openSheet(if(v?.optBoolean("configured")==true&&v.rows("grants").none{it.optBoolean("current")})"visibility"else"share")},modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=18.dp,vertical=10.dp)){
      MapGlyph("share",dark);Spacer(Modifier.width(8.dp));Text("Partajează",fontSize=14.sp)
     }else Surface(onClick={SocialRecovery.stop(c);action{SocialApi.call(c,"session",method="DELETE")}},shape=CircleShape,color=dark,shadowElevation=3.dp){
      Row(Modifier.heightIn(min=48.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
       Box(Modifier.size(7.dp).clip(CircleShape).background(if(ownFresh)green else Color(0xFFFFCC8A)))
       Text("Oprește partajarea",color=green,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
      }
     }
    }
    me?.optJSONObject("visibility")?.takeIf{it.optBoolean("ghost")}?.let{v->val recipients=v.rows("grants").filter{it.optBoolean("current")&&it.optBoolean("ghost")}.mapNotNull{g->friends.find{it.optString("id")==g.optString("id")}?.optString("name")};Surface(onClick={openSheet("visibility")},shape=RoundedCornerShape(12.dp),color=dark){Text(if(recipients.isEmpty())"Fantomă · locație privată"else"Fantomă · "+(if(ownFresh)"vizibil pentru "else"acces pentru ")+recipients.joinToString(", "),Modifier.padding(10.dp),fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)}}
    if(error.isNotBlank()&&sheet==null)Surface(onClick={openSheet("status")},shape=RoundedCornerShape(16.dp),color=Color(0xFF582F25)){
     Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(" ›",fontSize=18.sp)}
    }
    if(session!=null&&!ownFresh&&(status.startsWith("Activează locația")||status.startsWith("GPS indisponibil")))Surface(onClick={c.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))},shape=RoundedCornerShape(16.dp)){
     Text("Pornește locația telefonului  ›",Modifier.padding(12.dp),fontSize=12.sp)
    }
   }
   if(pickingPlace!=null)Box(Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(dark.copy(alpha=.9f)),contentAlignment=Alignment.Center){MapGlyph("pin",green)}
   Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=hostTabBarHeight+10.dp,start=16.dp,end=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
    if(note.isNotBlank()&&sheet==null)Surface(shape=RoundedCornerShape(16.dp),color=green){Text(note,Modifier.padding(horizontal=16.dp,vertical=10.dp),color=dark,fontSize=13.sp)}
    if(pickingPlace!=null){
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
      Button(modifier=Modifier.weight(1f).heightIn(min=52.dp),onClick={val kind=pickingPlace;selectedPoint=if(vectorReady)vectorCenter?:GeoPoint(map.mapCenter.latitude,map.mapCenter.longitude)else GeoPoint(map.mapCenter.latitude,map.mapCenter.longitude);pickingPlace=null;openSheet(kind?:"place")}){Text("Folosește acest loc")}
      Surface(onClick={pickingPlace=null},shape=CircleShape,color=dark){Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){MapGlyph("close",green,description="Anulează alegerea locului")}}
     }
    }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
     Surface(onClick={openSheet(if(friends.isEmpty()&&incoming.isEmpty())"invite"else "friends")},modifier=Modifier.weight(1f),shape=CircleShape,color=dark,shadowElevation=3.dp){
      Row(Modifier.heightIn(min=54.dp).padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
       if(friends.isEmpty())MapGlyph("people",green)else Surface(shape=CircleShape,color=Color(0xFF2D4436)){Text(initials(friends.first().optString("name")),Modifier.padding(7.dp),fontSize=12.sp,color=green,fontWeight=FontWeight.Bold)}
       Text(if(friends.isEmpty()&&incoming.isEmpty())"Invită un prieten"else "Cercul tău",modifier=Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
       if(incoming.isNotEmpty())Surface(shape=CircleShape,color=green){Text("${incoming.size}",Modifier.padding(horizontal=7.dp,vertical=3.dp),fontSize=12.sp,color=dark)}else if(friends.isNotEmpty())Text("${friends.size}",fontSize=13.sp,color=green)
       MapGlyph("up",green,Modifier.size(18.dp))
      }
     }
     if(ownFresh)Surface(onClick={ownLocation?.let{focus(it.getDouble("lat"),it.getDouble("lon"))}},shape=CircleShape,color=dark,shadowElevation=3.dp){Box(Modifier.size(50.dp),contentAlignment=Alignment.Center){MapGlyph("locate",green,description="Centrează pe locația mea")}}
    }
    Text("© OpenStreetMap contributors",modifier=Modifier.align(Alignment.End).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha=.88f)).clickable{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}.padding(horizontal=5.dp,vertical=2.dp),fontSize=9.sp,color=dark)
   }
  }
  // One sheet owns navigation. Opening a tool replaces its content, never stacks another sheet.
  if(sheet!=null)ModalBottomSheet(onDismissRequest={closeSheet()},sheetState=sheetState,containerColor=Color(0xFF20332A)){
   Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal=20.dp).padding(bottom=20.dp).heightIn(max=560.dp).verticalScroll(remember(sheet){ScrollState(0)}),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
     if(sheet!="menu")IconButton(onClick={openSheet("menu")}){MapGlyph("back",green,description="Instrumentele hărții")}
     Text(sheetTitle,Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold)
     IconButton(onClick={closeSheet()}){MapGlyph("close",green,description="Închide")}
    }
    if(error.isNotBlank())Surface(shape=RoundedCornerShape(14.dp),color=Color(0xFF582F25)){Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),fontSize=12.sp);TextButton(onClick={error=""}){Text("Închide")}}}
    if(note.isNotBlank())Text(note,fontSize=13.sp,color=green)
    when(sheet){
     "menu"->{
      if(session!=null)Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(session.optBoolean("continuous"))"Doar cu partenerul"else "Cu prietenii acceptați",fontWeight=FontWeight.SemiBold);Text(if(ownFresh)"Locație actualizată"else "Aștept poziția…",fontSize=12.sp)};TextButton(onClick={SocialRecovery.stop(c);action{SocialApi.call(c,"session",method="DELETE")}}){Text("Oprește")}}}
      MapMenuRow("sun","Harta mea · explorează"){journalOwner=null;journalPages=1;journal=null;action{refreshJourney()};openSheet("journey")}
      MapMenuRow("share","Cine mă vede"){openSheet("visibility")}
      if(session==null)MapMenuRow("share","Partajare temporară"){consent=false;val v=me?.optJSONObject("visibility");openSheet(if(v?.optBoolean("configured")==true&&v.rows("grants").none{it.optBoolean("current")})"visibility"else"share")}
      MapMenuRow("people","Cercul tău",if(incoming.isEmpty())"${friends.size} prieteni"else "${incoming.size} invitații"){openSheet("friends")}
      MapMenuRow("heart","Voi doi"){openSheet("couple")}
      MapMenuRow("pin","Locuri salvate"){openSheet("places")}
      MapMenuRow("sun","Planuri împreună"){openSheet("groups")}
      MapMenuRow("contacts","Prieteni din contacte"){openSheet("contacts")}
      MapMenuRow("phone","Telefonul meu"){openSheet("lost")}
      MapMenuRow("profile","Profil și explorare"){openSheet("you")}
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={closeSheet();onBack()}){Text("Activitățile mele",fontSize=12.sp)};TextButton(onClick={showPrivacy=true}){Text("Confidențialitate",fontSize=12.sp)}}
     }
       "journey"->JourneyPanel(journal,owner,{refreshJourney()},{refreshJourney(true)}){selectedVisit=it;sheet="visit"}
       "visibility"->VisibilityPanel(data,owner,{journalOwner=null;journalPages=1;journal=null;action{refreshJourney()};openSheet("journey")}){refresh()}
       "visit"->selectedVisit?.let{v->JourneyVisitPanel(v,friends,owner,{refreshJourney()}){navigate(v.getDouble("lat"),v.getDouble("lon"))}}
       "shared-history"->{Text("Istoric partajat",fontSize=18.sp);TextButton(onClick={closeSheet()}){Text("Arată traseele pe hartă")};journal?.rows("visits")?.forEach{v->TextButton(onClick={selectedVisit=v;sheet="shared-visit"}){Text(v.optString("name","Loc vizitat"))}};if(journal?.optString("next_cursor")?.takeUnless{it=="null"||it.isBlank()}!=null)TextButton(onClick={action{refreshJourney(true)}}){Text("Încarcă mai mult")};TextButton(onClick={journalOwner=null;journalPages=1;journal=null;action{refreshJourney()};openSheet("journey")}){Text("Înapoi la harta mea")}}
       "shared-visit"->selectedVisit?.let{v->Text(v.optString("name","Loc vizitat"));Text("%.1f h confirmate".format(v.optLong("observed_ms")/3600000.0));Button(onClick={navigate(v.getDouble("lat"),v.getDouble("lon"))}){Text("Hai aici ↗")}}
       "couple"->PartnerContactsPanel(data,owner,{openSheet("visibility")}){refresh()}
       "contacts"->ContactsPanel(me,owner){refresh()}
       "lost"->LostPhonePanel(owner)
       "friends"->{
        Button(modifier=Modifier.fillMaxWidth(),onClick={openSheet("invite")}){Text("Invită un prieten")}
        incoming.forEach{f->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Column(Modifier.fillMaxWidth().padding(12.dp)){
         Text(f.getString("name"),fontWeight=FontWeight.Bold);Text("Vrea să fie în cercul tău",fontSize=12.sp)
         Row{TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "accept"))}}){Text("Acceptă")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "reject"))}}){Text("Refuză")}}
        }}}
        friends.forEach{f->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436),onClick={friend=f;sheet="friend"}){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
         Surface(shape=CircleShape,color=dark){Text(initials(f.getString("name")),fontSize=18.sp,color=green,modifier=Modifier.padding(12.dp))}
         Column(Modifier.weight(1f)){Text(f.getString("name"),fontWeight=FontWeight.Bold);val l=f.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000};Text(if(l==null)"Nu partajează acum"else "${label(f.optString("mode"))} · ${((clock-l.optLong("at"))/1000).coerceAtLeast(0)} s",fontSize=12.sp)};Text("›",fontSize=24.sp)
        }}}
       }
       "places"->{

        Button(modifier=Modifier.fillMaxWidth(),onClick={placeName="";selectedPoint=null;choosePlace("place")}){Text("Salvează locul")}
        me?.rows("places")?.forEach{p->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Column(Modifier.fillMaxWidth().padding(12.dp)){
         Text(p.getString("name"),fontWeight=FontWeight.Bold)
         Row{TextButton(onClick={focus(p.getDouble("lat"),p.getDouble("lon"));closeSheet()}){Text("Pe hartă")};TextButton(onClick={navigate(p.getDouble("lat"),p.getDouble("lon"))}){Text("Traseu")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"place",SocialApi.obj("id" to p.getString("id")),"DELETE")}}){Text("Elimină")}}
        }}}
       }
       "groups"->{
        Button(modifier=Modifier.fillMaxWidth(),enabled=friends.isNotEmpty(),onClick={groupName="";groupFriends=emptySet();placeName="";selectedPoint=null;choosePlace("group")}){Text("Hai afară")}
        if(friends.isEmpty())Text("Invită un prieten pentru primul vostru plan.",fontSize=13.sp)
        data?.rows("groups")?.forEach{g->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
         Text(g.getString("name"),fontWeight=FontWeight.Bold)
         Text(label(g.getString("mode"))+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(g.getLong("at"))),fontSize=12.sp)
         Text(g.getString("place")+" · ${g.strings("going").size}/${g.strings("members").size} vin",fontSize=12.sp)
         Row{TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"group-response",SocialApi.obj("id" to g.getString("id"),"going" to !g.strings("going").contains(owner)))}}){Text(if(g.strings("going").contains(owner))"Nu mai vin"else "Vin și eu")};TextButton(onClick={navigate(g.getDouble("lat"),g.getDouble("lon"))}){Text("Traseu")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"leave-group",SocialApi.obj("id" to g.getString("id")))}}){Text("Ieși")}}
        }}}
       }
       "you"->{
        OutlinedTextField(name,{name=it.take(40)},label={Text("Numele tău")},singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(enabled=!busy&&name.isNotBlank(),onClick={action{SocialApi.call(c,"profile",SocialApi.obj("name" to name));note="Nume salvat"}}){Text("Salvează")}
        OutlinedButton(onClick={copyCode()}){Text("Copiază codul meu")}
        if(session!=null){OutlinedTextField(placeName,{placeName=it.take(80)},label={Text("Unde ești?")},modifier=Modifier.fillMaxWidth());TextButton(enabled=!busy&&placeName.isNotBlank(),onClick={action{SocialApi.call(c,"checkin",SocialApi.obj("name" to placeName));note="Loc adăugat"}}){Text("Spune-le prietenilor")}}
        Text("${me?.optJSONArray("explored")?.length()?:0} zone explorate",fontSize=20.sp,fontWeight=FontWeight.Bold,color=green)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("Arată explorarea");Switch(explore,{explore=it})}
        me?.rows("history")?.take(5)?.forEach{h->Text("${label(h.getString("mode"))} · ${"%.2f".format(h.optDouble("metres")/1000)} km · ${(h.getLong("to")-h.getLong("from"))/60000} min",fontSize=13.sp)}
        me?.strings("blocked")?.forEach{id->TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to id,"action" to "unblock"))}}){Text("Deblochează …${id.takeLast(6)}")}}
        if(status.isNotBlank())Text(status,fontSize=12.sp)
        TextButton(onClick={showPrivacy=true}){Text("Confidențialitate")}
        TextButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(FileSync.SITE)))}){Text("Deschide FORJA pe web ↗")}
       }
     "invite"->{
      OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=me?.optString("code").orEmpty().isNotBlank(),onClick={copyCode()}){Text("Copiază codul meu")}
      OutlinedTextField(invite,{invite=it},label={Text("Codul prietenului")},singleLine=true,modifier=Modifier.fillMaxWidth())
      Button(modifier=Modifier.fillMaxWidth(),enabled=!busy&&invite.isNotBlank(),onClick={action{SocialApi.call(c,"invite",SocialApi.obj("code" to invite.trim()));invite="";openSheet("friends");note="Invitație trimisă"}}){Text("Trimite invitația")}
      if((data?.optInt("outgoing")?:0)>0)Text("${data?.optInt("outgoing")} invitații trimise",fontSize=12.sp)
     }
     "share"->{
      Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){for(id in listOf("walk","cycle","out"))FilterChip(activity==id,{activity=id},label={Text(label(id))})}
      Text("Pentru cât timp?",fontSize=13.sp)
      Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){for(n in listOf(30,60,120,240))FilterChip(minutes==n,{minutes=n},label={Text(if(n<60)"$n min"else "${n/60} h")})}
      Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it});Text("Partajez poziția, viteza și bateria cu "+(if(me?.optJSONObject("visibility")?.optBoolean("configured")==true)"persoanele alese"else"prietenii acceptați")+", cu notificare, timp de ${if(minutes<60)"$minutes min"else "${minutes/60} h"}.",fontSize=12.sp,modifier=Modifier.weight(1f))}
      Button(modifier=Modifier.fillMaxWidth(),enabled=consent&&!busy,onClick={permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text("Partajează")}
      TextButton(onClick={showPrivacy=true}){Text("Confidențialitate")}
     }
     "place","group"->{
      val group=sheet=="group"
      if(group){
       OutlinedTextField(groupName,{groupName=it},label={Text("Ce facem?")},modifier=Modifier.fillMaxWidth())
       Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){for(id in listOf("walk","cycle","out"))FilterChip(groupMode==id,{groupMode=id},label={Text(label(id))})}
       Text("Începem peste",fontSize=13.sp)
       Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){for(n in listOf(1,3,24))FilterChip(groupHours==n,{groupHours=n},label={Text(if(n==24)"O zi"else "$n h")})}
       friends.forEach{f->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(f.getString("id") in groupFriends,{checked->groupFriends=if(checked)groupFriends+f.getString("id")else groupFriends-f.getString("id")});Text(f.getString("name"))}}
      }
      OutlinedTextField(placeName,{placeName=it},label={Text(if(group)"Ne vedem la…"else "Numele locului")},modifier=Modifier.fillMaxWidth())
      TextButton(onClick={choosePlace(if(group)"group"else "place")}){Text("Mută punctul pe hartă")}
      Button(modifier=Modifier.fillMaxWidth(),enabled=!busy&&placeName.isNotBlank()&&(!group||groupName.isNotBlank()&&groupFriends.isNotEmpty()),onClick={val center=selectedPoint?:(if(vectorReady)vectorCenter else null)?:GeoPoint(map.mapCenter.latitude,map.mapCenter.longitude);action{SocialApi.call(c,if(group)"group"else "place",SocialApi.obj("name" to if(group)groupName else placeName,"lat" to center.latitude,"lon" to center.longitude).apply{if(group){put("mode",groupMode);put("at",System.currentTimeMillis()+groupHours*3600000L);put("place",placeName);put("friends",JSONArray(groupFriends.toList()))}});openSheet(if(group)"groups"else "places");note=if(group)"Plan creat"else "Loc salvat"}}){Text(if(group)"Invită"else "Salvează")}
     }
     "friend"->friend?.let{f->
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
    Surface(shape=CircleShape,color=green){Text(initials(f.getString("name")),Modifier.padding(18.dp),fontSize=24.sp,fontWeight=FontWeight.Bold,color=dark)}
    Text(f.getString("name"),fontSize=26.sp,fontWeight=FontWeight.Bold)
   }
   val live=friends.find{it.optString("id")==f.getString("id")};val l=live?.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000}
   if(l!=null){
    Text("${label(live.optString("mode"))}"+(if(l.has("battery")&&!l.isNull("battery"))" · ${l.optInt("battery")}% baterie"else ""),color=green)
    Text("Actualizat acum ${((clock-l.optLong("at"))/1000).coerceAtLeast(0)} s · ±${l.optInt("accuracy")} m",fontSize=12.sp)
    live.optJSONObject("checkin")?.let{Text(it.getString("name"))}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={focus(l.getDouble("lat"),l.getDouble("lon"));closeSheet()}){Text("Pe hartă")};OutlinedButton(onClick={navigate(l.getDouble("lat"),l.getDouble("lon"))}){Text("Traseu")}}
   }else Text("Nu partajează locația acum.",fontSize=13.sp)
   TextButton(onClick={journalOwner=f.getString("id");journalPages=1;journal=null;selectedVisit=null;action{refreshJourney()};sheet="shared-history"}){Text("Istoricul partajat cu mine")}
   HorizontalDivider()
   if(chat.isEmpty())Text("Spune-i un salut 👋",fontSize=14.sp)
   chat.takeLast(30).forEach{m->Surface(modifier=Modifier.align(if(m.getString("from")==owner)Alignment.End else Alignment.Start),shape=RoundedCornerShape(16.dp),color=if(m.getString("from")==owner)Color(0xFF3F583C)else Color(0xFF2D4436)){Column(Modifier.padding(12.dp)){Text(m.getString("text"),fontSize=14.sp);m.optJSONObject("place")?.let{p->val lat=p.optDouble("lat");val lon=p.optDouble("lon");if(lat.isFinite()&&lon.isFinite()){
    Row{TextButton(onClick={navigate(lat,lon)}){Text("Hai aici ↗")};TextButton(onClick={focus(lat,lon);closeSheet()}){Text("Pe hartă")}}
    TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"place",SocialApi.obj("name" to p.optString("name","Loc recomandat"),"lat" to lat,"lon" to lon));note="Loc salvat"}}){Text("Păstrează pentru mai târziu")}
   }}}}}
   OutlinedTextField(message,{message=it.take(1000)},label={Text("Scrie un mesaj…")},modifier=Modifier.fillMaxWidth())
   Button(modifier=Modifier.fillMaxWidth(),enabled=!busy&&message.isNotBlank(),onClick={val draft=message;action{SocialApi.call(c,"chat",SocialApi.obj("friend" to f.getString("id"),"id" to UUID.randomUUID().toString(),"text" to draft));message="";chat=SocialApi.call(c,"chat?friend="+Uri.encode(f.getString("id"))).rows("messages")}}){Text("Trimite")}
   var friendDetails by remember(f.getString("id")){mutableStateOf(false)}
   TextButton(onClick={friendDetails=!friendDetails}){Text(if(friendDetails)"Mai puține"else "Detalii și opțiuni")}
   AnimatedVisibility(friendDetails){Column{
    if(l!=null)Text("${"%.1f".format(l.optDouble("speed")*3.6)} km/h · ${((clock-l.optLong("since"))/60000).coerceAtLeast(0)} min în apropiere",fontSize=12.sp)
    Text("Conversațiile se păstrează 7 zile.",fontSize=12.sp)
    Row{TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "remove"));openSheet("friends")}}){Text("Elimină prietenul")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "block"));openSheet("friends")}}){Text("Blochează")}}
   }}
     }
     "status"->{
      if(status.isNotBlank())Text(status,fontSize=13.sp)
      Button(enabled=!busy,onClick={action{refresh()}}){Text("Reîncearcă")}
     }
     else->Unit
    }
   }
  }
  if(showPrivacy)ForjaPrivacyDialog{showPrivacy=false}
 }
}

@Composable private fun MapMenuRow(icon:String,title:String,subtitle:String?=null,onClick:()->Unit){
 Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=Color(0xFF294033)){
  Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
   MapGlyph(icon,green)
   Text(title,Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Medium)
   if(!subtitle.isNullOrBlank())Text(subtitle,fontSize=11.sp,color=green)
   Text("›",fontSize=20.sp,color=green)
  }
 }
}

/** Small vector controls, without adding an icon dependency to the patched module. */
@Composable private fun MapGlyph(kind:String,color:Color,modifier:Modifier=Modifier.size(22.dp),description:String?=null){
 androidx.compose.foundation.Canvas(modifier.then(if(description==null)Modifier else Modifier.semantics{contentDescription=description})){
  val u=size.minDimension/24f;val w=1.8f*u
  fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(x1*u,y1*u),Offset(x2*u,y2*u),w,StrokeCap.Round)
  fun circle(x:Float,y:Float,r:Float)=drawCircle(color,r*u,Offset(x*u,y*u),style=Stroke(w))
  when(kind){
   "menu"->{line(4f,6f,20f,6f);line(4f,12f,20f,12f);line(4f,18f,14f,18f)}
   "close"->{line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
   "back"->{line(15f,5f,8f,12f);line(8f,12f,15f,19f)}
   "up"->{line(5f,15f,12f,8f);line(12f,8f,19f,15f)}
   "locate"->{circle(12f,12f,7f);circle(12f,12f,2f);line(12f,2f,12f,5f);line(12f,19f,12f,22f);line(2f,12f,5f,12f);line(19f,12f,22f,12f)}
   "share"->{circle(6f,12f,2.4f);circle(18f,5f,2.4f);circle(18f,19f,2.4f);line(8f,11f,16f,6f);line(8f,13f,16f,18f)}
   "people","contacts"->{circle(9f,7f,3f);drawArc(color,180f,180f,false,Offset(2f*u,13f*u),androidx.compose.ui.geometry.Size(14f*u,12f*u),style=Stroke(w));line(17f,4f,19f,6f);line(19f,6f,17f,9f);line(18f,13f,21f,17f);line(21f,17f,21f,20f)}
   "profile"->{circle(12f,7f,3.5f);drawArc(color,180f,180f,false,Offset(4f*u,14f*u),androidx.compose.ui.geometry.Size(16f*u,12f*u),style=Stroke(w))}
   "phone"->{drawRoundRect(color,Offset(6f*u,2f*u),androidx.compose.ui.geometry.Size(12f*u,20f*u),androidx.compose.ui.geometry.CornerRadius(2f*u),style=Stroke(w));line(10f,18f,14f,18f)}
   "pin"->{circle(12f,9f,6f);circle(12f,9f,2f);line(7f,13f,12f,22f);line(17f,13f,12f,22f)}
   "heart"->{val path=androidx.compose.ui.graphics.Path().apply{moveTo(12f*u,21f*u);cubicTo(2f*u,14f*u,0f,7f*u,6f*u,4f*u);cubicTo(9f*u,3f*u,11f*u,5f*u,12f*u,7f*u);cubicTo(13f*u,5f*u,15f*u,3f*u,18f*u,4f*u);cubicTo(24f*u,7f*u,22f*u,14f*u,12f*u,21f*u)};drawPath(path,color,style=Stroke(w))}
   else->{circle(12f,12f,5f);line(12f,1f,12f,4f);line(12f,20f,12f,23f);line(1f,12f,4f,12f);line(20f,12f,23f,12f);line(4f,4f,6f,6f);line(18f,18f,20f,20f);line(18f,6f,20f,4f);line(4f,20f,6f,18f)}
  }
 }
}
