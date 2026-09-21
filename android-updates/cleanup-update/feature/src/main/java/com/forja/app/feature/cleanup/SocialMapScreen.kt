package com.forja.app.feature.cleanup

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 var data by remember(owner){mutableStateOf<JSONObject?>(null)};var error by remember(owner){mutableStateOf("")};var busy by remember{mutableStateOf(false)}
 var pane by remember{mutableStateOf("friends")};var expanded by remember{mutableStateOf(false)};var showStart by remember{mutableStateOf(false)};var activity by remember{mutableStateOf("walk")};var minutes by remember{mutableIntStateOf(60)};var consent by remember{mutableStateOf(false)}
 var friend by remember(owner){mutableStateOf<JSONObject?>(null)};var chat by remember{mutableStateOf<List<JSONObject>>(emptyList())};var message by remember{mutableStateOf("")}
 var name by remember(owner){mutableStateOf("")};var invite by remember{mutableStateOf("")};var placeName by remember{mutableStateOf("")};var newPlace by remember{mutableStateOf(false)};var groupName by remember{mutableStateOf("")};var groupFriends by remember{mutableStateOf<Set<String>>(emptySet())};var groupMode by remember{mutableStateOf("walk")};var groupHours by remember{mutableIntStateOf(1)};var newGroup by remember{mutableStateOf(false)};var explore by remember{mutableStateOf(false)}
 var showMore by remember{mutableStateOf(false)};var showInvite by remember{mutableStateOf(false)};var showPrivacy by remember{mutableStateOf(false)};var note by remember{mutableStateOf("")}
 LaunchedEffect(note){if(note.isNotBlank()){delay(2500);note=""}}
 val status by SocialApi.status.collectAsState()
 val map=remember(c){Configuration.getInstance().userAgentValue="FORJA/3.7-online.23 (${c.packageName})";MapView(c).apply{setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true);minZoomLevel=3.0;maxZoomLevel=19.0;controller.setZoom(6.0);controller.setCenter(GeoPoint(45.8,24.9))}}
 suspend fun refresh(){try{val result=SocialApi.call(c,"state");if(FileSync.owner()==owner){data=result;error="";if(name.isBlank())name=result.getJSONObject("me").getString("name")}}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Nu pot încărca harta."}}
 fun action(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{check(FileSync.owner()==owner){"Contul s-a schimbat."};block();refresh()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}
 fun navigate(lat:Double,lon:Double){runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("geo:$lat,$lon?q=$lat,$lon")))}.onFailure{error="Nu există o aplicație de navigare instalată."}}
 fun begin(){try{ContextCompat.startForegroundService(c,Intent(c,SocialLocationService::class.java).putExtra("mode",activity).putExtra("minutes",minutes));showStart=false;consent=false}catch(e:Exception){error=e.message.orEmpty()}}
 val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
  val location=result[Manifest.permission.ACCESS_COARSE_LOCATION]==true||result[Manifest.permission.ACCESS_FINE_LOCATION]==true
  val notifications=Build.VERSION.SDK_INT<33||result[Manifest.permission.POST_NOTIFICATIONS]==true
  if(location&&notifications)begin()else error="Pentru partajare acordă locația și notificările. Poți folosi în continuare harta și prietenii."
 }
 DisposableEffect(Unit){val auth=com.google.firebase.auth.FirebaseAuth.getInstance();val listener=com.google.firebase.auth.FirebaseAuth.AuthStateListener{owner=it.currentUser?.uid};auth.addAuthStateListener(listener);onDispose{auth.removeAuthStateListener(listener)}}
 LaunchedEffect(owner){friend=null;showStart=false;consent=false;newGroup=false;newPlace=false;showInvite=false;showMore=false;invite=""}
 LaunchedEffect(Unit){while(isActive){delay(5000);clock=System.currentTimeMillis()}}
 LaunchedEffect(owner){if(owner==null){error="Conectează-te în FORJA pentru prieteni și activități.";return@LaunchedEffect};while(isActive){refresh();delay(15000)}}
 LaunchedEffect(friend?.optString("id")){chat=emptyList();message="";val id=friend?.optString("id")?:return@LaunchedEffect;while(isActive){try{chat=SocialApi.call(c,"chat?friend="+Uri.encode(id)).rows("messages")}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()};delay(8000)}}
 DisposableEffect(map){map.onResume();onDispose{map.onPause();map.onDetach()}}
 val me=data?.optJSONObject("me");val friends=data?.rows("friends").orEmpty();val session=me?.optJSONObject("session")
 var centered by remember(owner){mutableStateOf(false)}
 val previous=remember(owner){mutableMapOf<String,GeoPoint>()}
 val movements=remember{mutableListOf<android.animation.ValueAnimator>()}
 DisposableEffect(Unit){onDispose{movements.forEach{it.cancel()}}}
 LaunchedEffect(data,explore,pane,clock){
  movements.forEach{it.cancel()};movements.clear();map.overlays.clear()
  if(explore){val cells=me?.optJSONArray("explored")?:JSONArray();for(i in 0 until cells.length()){val a=cells.getJSONArray(i);val lat=a.getDouble(0)+0.0005;val lon=a.getDouble(1)+0.0005;val polygon=Polygon(map);polygon.points=(0..6).map{j->val angle=j*Math.PI/3;GeoPoint(lat+0.00055*kotlin.math.sin(angle),lon+0.0007*kotlin.math.cos(angle))};polygon.fillPaint.color=0x773BBE88;polygon.outlinePaint.color=0xAA248E66.toInt();polygon.outlinePaint.strokeWidth=2f;map.overlays.add(polygon)}}
  fun marker(v:JSONObject,title:String,own:Boolean,click:(()->Unit)?=null){val lat=v.optDouble("lat",Double.NaN);val lon=v.optDouble("lon",Double.NaN);if(!lat.isFinite()||!lon.isFinite())return;val m=Marker(map);val target=GeoPoint(lat,lon);m.position=target;val before=previous.put(title,target)
   if(before!=null&&android.animation.ValueAnimator.areAnimatorsEnabled()&&before.distanceToAsDouble(target) in 2.0..2000.0){val animator=android.animation.ValueAnimator.ofFloat(0f,1f);animator.duration=750;animator.addUpdateListener{a->val f=(a.animatedValue as Float).toDouble();m.position=GeoPoint(before.latitude+(lat-before.latitude)*f,before.longitude+(lon-before.longitude)*f);map.invalidate()};movements.add(animator);animator.start()};m.title=title;m.icon=icon(c,title,own);m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);m.setOnMarkerClickListener{_,_->map.controller.animateTo(m.position);click?.invoke();true};map.overlays.add(m)}
  me?.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000}?.let{marker(it,"Tu",true);if(!centered){map.controller.setZoom(15.0);map.controller.animateTo(GeoPoint(it.getDouble("lat"),it.getDouble("lon")));centered=true}}
  for(f in friends)f.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000}?.let{marker(it,f.getString("name"),false){friend=f}}
  if(pane=="places")for(p in me?.rows("places").orEmpty())marker(p,p.getString("name"),false)
  map.invalidate()
 }
 val ownLocation=me?.optJSONObject("location")
 val ownFresh=ownLocation?.optLong("at",0)?.let{it>clock-120000}==true
 val incoming=data?.rows("incoming").orEmpty()
 fun selectPane(id:String){pane=id;expanded=true;showMore=false}
 fun copyCode(){val code=me?.optString("code").orEmpty();if(code.isNotBlank()){(c.getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cod FORJA",code));note="Cod copiat"}}
 MaterialTheme(colorScheme=darkColorScheme(primary=green,onPrimary=dark,background=dark,surface=Color(0xFF20332A),onSurface=Color(0xFFF1F5E9))){
  Box(Modifier.fillMaxSize().background(dark)){
   AndroidView(factory={map},modifier=Modifier.fillMaxSize())
   Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(shape=RoundedCornerShape(28.dp),shadowElevation=8.dp){
     Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically){
      TextButton(onClick=onBack,modifier=Modifier.widthIn(min=48.dp)){Text("←",fontSize=22.sp,modifier=Modifier.semantics{contentDescription="Înapoi"})}
      Column(Modifier.weight(1f)){
       Text("Împreună",fontWeight=FontWeight.Bold,fontSize=18.sp)
       Text(if(session==null)"Locația ta e oprită"else if(ownFresh)if(session.optBoolean("continuous"))"Doar cu partenerul"else "Cu prietenii tăi"else "Aștept poziția…",fontSize=11.sp,color=green)
      }
      if(session==null)Button(enabled=owner!=null&&!busy,onClick={showStart=true;consent=false}){Text("Partajează")}
      else OutlinedButton(enabled=!busy,onClick={action{SocialRecovery.stop(c);SocialApi.call(c,"session",method="DELETE")}}){Text("Oprește")}
     }
    }
    if(error.isNotBlank())Surface(shape=RoundedCornerShape(16.dp),color=Color(0xFF582F25)){Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),fontSize=12.sp);TextButton(onClick={error=""}){Text("Închide")}}}
    if(session!=null&&!ownFresh&&(status.startsWith("Activează locația")||status.startsWith("GPS indisponibil")))Surface(shape=RoundedCornerShape(16.dp)){
     Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Text("Locația telefonului e oprită",Modifier.weight(1f),fontSize=12.sp);TextButton(onClick={c.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))}){Text("Setări")}}
    }
    if(note.isNotBlank())Surface(shape=RoundedCornerShape(16.dp),color=green){Text(note,Modifier.padding(12.dp),color=dark,fontSize=13.sp)}
   }
   if(pane=="places")Surface(Modifier.align(Alignment.Center),shape=RoundedCornerShape(32.dp),color=dark.copy(alpha=.8f)){Text("＋",Modifier.padding(8.dp),fontSize=24.sp,color=green)}
   Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom){
     Surface(shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.9f)){Text("© OpenStreetMap",Modifier.clickable{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}.padding(8.dp),fontSize=10.sp,color=dark)}
     Surface(shape=RoundedCornerShape(24.dp),shadowElevation=6.dp){Row{
      TextButton(onClick={map.controller.zoomOut()},modifier=Modifier.widthIn(min=48.dp)){Text("−",fontSize=22.sp,modifier=Modifier.semantics{contentDescription="Micșorează harta"})}
      TextButton(onClick={map.controller.zoomIn()},modifier=Modifier.widthIn(min=48.dp)){Text("+",fontSize=22.sp,modifier=Modifier.semantics{contentDescription="Mărește harta"})}
      TextButton(enabled=ownFresh,onClick={ownLocation?.let{map.controller.animateTo(GeoPoint(it.getDouble("lat"),it.getDouble("lon")))}}){Text("Eu")}
     }}
    }
    Surface(shape=RoundedCornerShape(28.dp),shadowElevation=12.dp){Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal=16.dp,vertical=10.dp)){
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.weight(1f).clickable{expanded=!expanded}.padding(vertical=8.dp)){
       Text(when(pane){"lost"->"Telefonul meu";"couple"->"Voi doi";"contacts"->"Agenda ta";"friends"->"Cercul tău";"groups"->"Planuri împreună";"places"->"Locurile tale";else->"Profilul tău"},fontWeight=FontWeight.Bold,fontSize=20.sp)
       if(pane=="friends")Text(if(friends.isEmpty())"Totul începe cu un prieten"else "${friends.size} prieteni"+(if(incoming.isNotEmpty())" · ${incoming.size} invitații"else ""),fontSize=12.sp,color=green)
      }
      TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Restrânge"else "Deschide",fontSize=12.sp)}
     }
     if(!expanded&&pane=="friends"){
      if(friends.isEmpty())Button(modifier=Modifier.fillMaxWidth(),onClick={showInvite=true}){Text("Invită un prieten")}
      else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
       friends.forEach{f->Column(Modifier.width(64.dp).clickable{friend=f},horizontalAlignment=Alignment.CenterHorizontally){Surface(shape=CircleShape,color=Color(0xFF2D4436)){Text(initials(f.getString("name")),Modifier.padding(14.dp),fontWeight=FontWeight.Bold,color=green)};Text(f.getString("name"),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}}
       Column(Modifier.width(64.dp).clickable{showInvite=true},horizontalAlignment=Alignment.CenterHorizontally){Surface(shape=CircleShape,color=green){Text("+",Modifier.padding(horizontal=18.dp,vertical=10.dp),fontSize=24.sp,color=dark)};Text("Invită",fontSize=11.sp)}
      }
     }
     AnimatedVisibility(expanded){Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()).padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      when(pane){
       "couple"->PartnerContactsPanel(data,owner){refresh()}
       "contacts"->ContactsPanel(me,owner){refresh()}
       "lost"->LostPhonePanel(owner)
       "friends"->{
        Button(modifier=Modifier.fillMaxWidth(),onClick={showInvite=true}){Text("Invită un prieten")}
        incoming.forEach{f->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Column(Modifier.fillMaxWidth().padding(12.dp)){
         Text(f.getString("name"),fontWeight=FontWeight.Bold);Text("Vrea să fie în cercul tău",fontSize=12.sp)
         Row{TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "accept"))}}){Text("Acceptă")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "reject"))}}){Text("Refuză")}}
        }}}
        friends.forEach{f->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436),onClick={friend=f}){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
         Surface(shape=CircleShape,color=dark){Text(initials(f.getString("name")),fontSize=18.sp,color=green,modifier=Modifier.padding(12.dp))}
         Column(Modifier.weight(1f)){Text(f.getString("name"),fontWeight=FontWeight.Bold);val l=f.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000};Text(if(l==null)"Nu partajează acum"else "${label(f.optString("mode"))} · ${l.optInt("battery")}% · ${((clock-l.optLong("at"))/1000).coerceAtLeast(0)} s",fontSize=12.sp)};Text("›",fontSize=24.sp)
        }}}
       }
       "places"->{
        Text("Punctul din mijlocul hărții",fontSize=12.sp,color=green)
        Button(modifier=Modifier.fillMaxWidth(),onClick={newPlace=true;placeName=""}){Text("Salvează locul")}
        me?.rows("places")?.forEach{p->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Column(Modifier.fillMaxWidth().padding(12.dp)){
         Text(p.getString("name"),fontWeight=FontWeight.Bold)
         Row{TextButton(onClick={map.controller.animateTo(GeoPoint(p.getDouble("lat"),p.getDouble("lon")));expanded=false}){Text("Pe hartă")};TextButton(onClick={navigate(p.getDouble("lat"),p.getDouble("lon"))}){Text("Traseu")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"place",SocialApi.obj("id" to p.getString("id")),"DELETE")}}){Text("Elimină")}}
        }}}
       }
       "groups"->{
        Button(modifier=Modifier.fillMaxWidth(),enabled=friends.isNotEmpty(),onClick={newGroup=true;groupFriends=emptySet();placeName=""}){Text("Hai afară")}
        if(friends.isEmpty())Text("Invită un prieten pentru primul vostru plan.",fontSize=13.sp)
        data?.rows("groups")?.forEach{g->Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF2D4436)){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
         Text(g.getString("name"),fontWeight=FontWeight.Bold)
         Text(label(g.getString("mode"))+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(g.getLong("at"))),fontSize=12.sp)
         Text(g.getString("place")+" · ${g.strings("going").size}/${g.strings("members").size} vin",fontSize=12.sp)
         Row{TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"group-response",SocialApi.obj("id" to g.getString("id"),"going" to !g.strings("going").contains(owner)))}}){Text(if(g.strings("going").contains(owner))"Nu mai vin"else "Vin și eu")};TextButton(onClick={navigate(g.getDouble("lat"),g.getDouble("lon"))}){Text("Traseu")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"leave-group",SocialApi.obj("id" to g.getString("id")))}}){Text("Ieși")}}
        }}}
       }
       else->{
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
      }
     }}
     HorizontalDivider(Modifier.padding(top=8.dp),color=green.copy(alpha=.12f))
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
      TextButton(onClick={selectPane("friends")}){Text("Prieteni",color=if(pane=="friends")green else Color(0xFFC1CCBD))}
      TextButton(onClick={selectPane("places")}){Text("Locuri",color=if(pane=="places")green else Color(0xFFC1CCBD))}
      TextButton(onClick={showMore=true}){Text("Mai multe",color=if(pane !in listOf("friends","places"))green else Color(0xFFC1CCBD))}
     }
    }}
   }
  }
  if(showMore)ModalBottomSheet(onDismissRequest={showMore=false}){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text("Ce facem azi?",fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=12.dp))
   for((id,title)in listOf("couple" to "Voi doi", "groups" to "Planuri împreună", "contacts" to "Prieteni din contacte", "lost" to "Telefonul meu", "you" to "Profil și explorare"))TextButton(modifier=Modifier.fillMaxWidth(),onClick={selectPane(id)}){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(title,fontSize=16.sp);Text("›")}}
   TextButton(onClick={showMore=false;showPrivacy=true}){Text("Confidențialitate",fontSize=12.sp)}
  }}
  if(showInvite)AlertDialog(onDismissRequest={showInvite=false},title={Text("Mai frumos împreună")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
   OutlinedButton(modifier=Modifier.fillMaxWidth(),enabled=me?.optString("code").orEmpty().isNotBlank(),onClick={copyCode()}){Text("Copiază codul meu")}
   OutlinedTextField(invite,{invite=it},label={Text("Codul prietenului")},singleLine=true,modifier=Modifier.fillMaxWidth())
   if((data?.optInt("outgoing")?:0)>0)Text("${data?.optInt("outgoing")} invitații trimise",fontSize=12.sp)
  }},confirmButton={TextButton(enabled=!busy&&invite.isNotBlank(),onClick={action{SocialApi.call(c,"invite",SocialApi.obj("code" to invite.trim()));invite="";showInvite=false;note="Invitație trimisă"}}){Text("Trimite invitația")}},dismissButton={TextButton(onClick={showInvite=false}){Text("Închide")}})
  if(showStart)AlertDialog(onDismissRequest={showStart=false},title={Text("Ieșim împreună?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(Modifier.horizontalScroll(rememberScrollState())){for(id in listOf("walk","cycle","out"))FilterChip(activity==id,{activity=id},label={Text(label(id))})}
   Text("Pentru cât timp?",fontSize=13.sp)
   Row(Modifier.horizontalScroll(rememberScrollState())){for(n in listOf(30,60,120,240))FilterChip(minutes==n,{minutes=n},label={Text(if(n<60)"$n min"else "${n/60} h")})}
   Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it});Text("Partajez poziția, viteza și bateria cu prietenii acceptați, cu notificare, timp de ${if(minutes<60)"$minutes min"else "${minutes/60} h"}.",fontSize=12.sp,modifier=Modifier.weight(1f))}
   TextButton(onClick={showPrivacy=true}){Text("Confidențialitate")}
  }},confirmButton={TextButton(enabled=consent&&!busy,onClick={permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text("Partajează")}},dismissButton={TextButton(onClick={showStart=false}){Text("Nu acum")}})
  if(newPlace||newGroup)AlertDialog(onDismissRequest={newPlace=false;newGroup=false},title={Text(if(newGroup)"Un plan împreună"else "Loc de păstrat")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
   if(newGroup){
    OutlinedTextField(groupName,{groupName=it},label={Text("Ce facem?")})
    Row(Modifier.horizontalScroll(rememberScrollState())){for(id in listOf("walk","cycle","out"))FilterChip(groupMode==id,{groupMode=id},label={Text(label(id))})}
    Text("Începem peste",fontSize=13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())){for(n in listOf(1,3,24))FilterChip(groupHours==n,{groupHours=n},label={Text(if(n==24)"O zi"else "$n h")})}
    friends.forEach{f->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(f.getString("id") in groupFriends,{checked->groupFriends=if(checked)groupFriends+f.getString("id")else groupFriends-f.getString("id")});Text(f.getString("name"))}}
   }
   OutlinedTextField(placeName,{placeName=it},label={Text(if(newGroup)"Ne vedem la…"else "Numele locului")})
   Text("La punctul din mijlocul hărții",fontSize=12.sp)
   TextButton(onClick={newPlace=false;newGroup=false;expanded=false}){Text("Mută punctul pe hartă")}
  }},confirmButton={TextButton(enabled=!busy&&placeName.isNotBlank()&&(!newGroup||groupName.isNotBlank()&&groupFriends.isNotEmpty()),onClick={val center=map.mapCenter;val group=newGroup;action{SocialApi.call(c,if(group)"group"else "place",SocialApi.obj("name" to if(group)groupName else placeName,"lat" to center.latitude,"lon" to center.longitude).apply{if(group){put("mode",groupMode);put("at",System.currentTimeMillis()+groupHours*3600000L);put("place",placeName);put("friends",JSONArray(groupFriends.toList()))}});newPlace=false;newGroup=false;note=if(group)"Plan creat"else "Loc salvat"}}){Text(if(newGroup)"Invită"else "Salvează")}},dismissButton={TextButton(onClick={newPlace=false;newGroup=false}){Text("Închide")}})
  friend?.let{f->ModalBottomSheet(onDismissRequest={friend=null}){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp).heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
    Surface(shape=CircleShape,color=green){Text(initials(f.getString("name")),Modifier.padding(18.dp),fontSize=24.sp,fontWeight=FontWeight.Bold,color=dark)}
    Text(f.getString("name"),fontSize=26.sp,fontWeight=FontWeight.Bold)
   }
   val live=friends.find{it.optString("id")==f.getString("id")};val l=live?.optJSONObject("location")?.takeIf{it.optLong("at")>clock-120000}
   if(l!=null){
    Text("${label(live.optString("mode"))} · ${l.optInt("battery")}% baterie",color=green)
    Text("Actualizat acum ${((clock-l.optLong("at"))/1000).coerceAtLeast(0)} s · ±${l.optInt("accuracy")} m",fontSize=12.sp)
    live.optJSONObject("checkin")?.let{Text(it.getString("name"))}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={map.controller.setZoom(16.0);map.controller.animateTo(GeoPoint(l.getDouble("lat"),l.getDouble("lon")));friend=null;expanded=false}){Text("Pe hartă")};OutlinedButton(onClick={navigate(l.getDouble("lat"),l.getDouble("lon"))}){Text("Traseu")}}
   }else Text("Nu partajează locația acum.",fontSize=13.sp)
   HorizontalDivider()
   if(chat.isEmpty())Text("Spune-i un salut 👋",fontSize=14.sp)
   chat.takeLast(30).forEach{m->Surface(modifier=Modifier.align(if(m.getString("from")==owner)Alignment.End else Alignment.Start),shape=RoundedCornerShape(16.dp),color=if(m.getString("from")==owner)Color(0xFF3F583C)else Color(0xFF2D4436)){Text(m.getString("text"),Modifier.padding(12.dp),fontSize=14.sp)}}
   OutlinedTextField(message,{message=it.take(1000)},label={Text("Scrie un mesaj…")},modifier=Modifier.fillMaxWidth())
   Button(modifier=Modifier.fillMaxWidth(),enabled=!busy&&message.isNotBlank(),onClick={val draft=message;action{SocialApi.call(c,"chat",SocialApi.obj("friend" to f.getString("id"),"id" to UUID.randomUUID().toString(),"text" to draft));message="";chat=SocialApi.call(c,"chat?friend="+Uri.encode(f.getString("id"))).rows("messages")}}){Text("Trimite")}
   var friendDetails by remember(f.getString("id")){mutableStateOf(false)}
   TextButton(onClick={friendDetails=!friendDetails}){Text(if(friendDetails)"Mai puține"else "Detalii și opțiuni")}
   AnimatedVisibility(friendDetails){Column{
    if(l!=null)Text("${"%.1f".format(l.optDouble("speed")*3.6)} km/h · ${((clock-l.optLong("since"))/60000).coerceAtLeast(0)} min în apropiere",fontSize=12.sp)
    Text("Conversațiile se păstrează 7 zile.",fontSize=12.sp)
    Row{TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "remove"));friend=null}}){Text("Elimină prietenul")};TextButton(enabled=!busy,onClick={action{SocialApi.call(c,"friend",SocialApi.obj("id" to f.getString("id"),"action" to "block"));friend=null}}){Text("Blochează")}}
   }}
  }}}
  if(showPrivacy)ForjaPrivacyDialog{showPrivacy=false}
 }
}
