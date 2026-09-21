package com.forja.app.feature.cleanup

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object SocialApi {
 suspend fun call(c:Context,path:String,body:JSONObject?=null,method:String=if(body==null)"GET"else "POST",owner:String=checkNotNull(FileSync.owner()){"Conectează-te în FORJA."}):JSONObject = FileSync.request(c,owner,"/v2/social/$path",method,body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false)
 fun obj(vararg values:Pair<String,Any?>)=JSONObject().apply{values.forEach{put(it.first,it.second)}}
 val status=MutableStateFlow("")
}
internal object SocialRecovery {
 fun prefs(c:Context)=c.getSharedPreferences("forja_partner_v22",Context.MODE_PRIVATE)
 fun active(c:Context)=prefs(c).getBoolean("active",false)&&prefs(c).getString("owner",null)==FileSync.owner()
 fun remember(c:Context,uid:String,id:String){prefs(c).edit().putString("owner",uid).putString("session",id).putBoolean("active",true).commit()}
 fun clear(c:Context){prefs(c).edit().remove("session").putBoolean("active",false).commit()}
 fun revoke(c:Context,uid:String,id:String){WorkManager.getInstance(c).enqueueUniqueWork("partner-stop-$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<PartnerStopWorker>().setInputData(workDataOf("owner" to uid,"session" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
 fun stop(c:Context){val p=prefs(c);val uid=p.getString("owner",null);val id=p.getString("session",null);clear(c);if(uid!=null&&id!=null&&uid==FileSync.owner())revoke(c,uid,id);c.stopService(Intent(c,SocialLocationService::class.java));SocialApi.status.value="Partajarea este oprită pe telefon."}
 fun resume(c:Context,boot:Boolean=false){
  if(!active(c)||SocialLocationService.running)return
  if(boot&&Build.VERSION.SDK_INT>=29&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
  try{ContextCompat.startForegroundService(c,Intent(c,SocialLocationService::class.java).setAction("RESUME"))}catch(_:Exception){SocialApi.status.value="Deschide FORJA pentru a relua partajarea cu partenerul."}
 }
 fun install(app:Application){app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks{
  override fun onActivityResumed(a:Activity){resume(app);ContactSync.schedule(app)}
  override fun onActivityCreated(a:Activity,b:Bundle?){};override fun onActivityStarted(a:Activity){};override fun onActivityStopped(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
 })}
}
class PartnerStopWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork():Result {val uid=inputData.getString("owner")?:return Result.failure();val id=inputData.getString("session")?:return Result.failure();if(uid!=FileSync.owner())return Result.success();return try{SocialApi.call(applicationContext,"session?session="+android.net.Uri.encode(id),method="DELETE",owner=uid);Result.success()}catch(e:FileSync.Failure){if(e.code in listOf(401,403,404,409))Result.success()else Result.retry()}catch(e:CancellationException){throw e}catch(_:Exception){Result.retry()}}}
class SocialBootReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(i.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)){SocialRecovery.resume(c,true);LostPhoneRecovery.resume(c,true)}}}
/** Explicit owner opt-in, one accepted partner, visible notification; never starts a new grant remotely. */
class SocialLocationService:Service(),LocationListener {
 companion object{@Volatile var running=false;private set}
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private var owner:String?=null;private var session:String?=null;private var until=0L;private var lastSend=0L;private var sending=false;private var continuous=false
 private lateinit var manager:LocationManager
 private val auth=FirebaseAuth.AuthStateListener{if(owner!=null&&FileSync.owner()!=owner){SocialRecovery.clear(this);SocialApi.status.value="Partajarea s-a oprit: cont schimbat.";stopSelf()}}
 override fun onCreate(){super.onCreate();manager=getSystemService(LocationManager::class.java);FirebaseAuth.getInstance().addAuthStateListener(auth)}
 override fun onBind(i:Intent?):IBinder?=null
 override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int {
  if(i?.action=="STOP"){SocialRecovery.stop(this);stopSelf();return START_NOT_STICKY}
  if(owner!=null)return if(continuous)START_STICKY else START_NOT_STICKY
  val resume=i==null||i.action=="RESUME";continuous=resume||i?.getBooleanExtra("continuous",false)==true
  owner=FileSync.owner();val mode=if(continuous)"partner"else i?.getStringExtra("mode");val minutes=i?.getIntExtra("minutes",60)?:60
  if(owner==null||!permission()||resume&&!SocialRecovery.active(this)||!continuous&&(mode !in listOf("walk","cycle","out")||minutes !in listOf(30,60,120,240))){stopSelf();return START_NOT_STICKY}
  if(continuous&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){SocialRecovery.clear(this);stopSelf();return START_NOT_STICKY}
  try{
   val nm=getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("forja-social","Hartă și activități",NotificationManager.IMPORTANCE_LOW))
   val stop=PendingIntent.getService(this,621,Intent(this,SocialLocationService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   val open=packageManager.getLaunchIntentForPackage(packageName)?.let{PendingIntent.getActivity(this,622,it,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)}
   val n=Notification.Builder(this,"forja-social").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(if(continuous)"FORJA · locație pentru partener"else "FORJA · partajare cu prietenii").setContentText(if(continuous)"Partajare continuă, până o oprești · doar partenerul ales"else "${if(mode=="cycle")"Cycling"else if(mode=="walk")"Mers"else "Ieșire în oraș"} · maximum $minutes minute").setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null,"Oprește · mod fantomă",stop).build()).build()
   if(Build.VERSION.SDK_INT>=29)startForeground(621,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)else startForeground(621,n);running=true
  }catch(e:Exception){SocialApi.status.value="Deschide FORJA pentru partajare: ${e.message}";stopSelf();return START_NOT_STICKY}
  scope.launch{
   try{
    if(resume){session=SocialRecovery.prefs(this@SocialLocationService).getString("session",null);checkNotNull(session)}
    else{val body=SocialApi.obj("mode" to mode,"minutes" to if(continuous)0 else minutes,"consent" to true);if(continuous)body.put("continuous",true).put("audience",i?.getStringExtra("audience"));val s=withTimeout(20000){SocialApi.call(this@SocialLocationService,"session",body,owner=owner!!)};session=s.getString("id");until=s.getLong("until");if(continuous)SocialRecovery.remember(this@SocialLocationService,owner!!,session!!)}
    if(!continuous)scope.launch{delay((until-System.currentTimeMillis()).coerceAtLeast(1));stopSelf()}
    var registered=false
    while(isActive){
     if(FileSync.owner()!=owner||!permission()||continuous&&!getSystemService(NotificationManager::class.java).areNotificationsEnabled()||!continuous&&System.currentTimeMillis()>=until||continuous&&!SocialRecovery.active(this@SocialLocationService)){SocialRecovery.stop(this@SocialLocationService);stopSelf();break}
     try{
      val state=withTimeout(20000){SocialApi.call(this@SocialLocationService,"state",owner=owner!!)};val active=state.getJSONObject("me").optJSONObject("session")
      if(active?.optString("id")!=session||active?.optBoolean("continuous")!=continuous){SocialRecovery.clear(this@SocialLocationService);SocialApi.status.value="Mod fantomă activat sau asociere închisă.";stopSelf();break}
      if(!registered){var providers=0;for(p in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))if(manager.isProviderEnabled(p)){manager.requestLocationUpdates(p,if(continuous)30000 else 15000,0f,this@SocialLocationService,Looper.getMainLooper());providers++};registered=providers>0;SocialApi.status.value=if(registered)"Partajare activă · aștept poziția telefonului"else "Activează locația telefonului; sesiunea așteaptă."}
     }catch(e:FileSync.Failure){if(e.code in listOf(401,403,404,409)){SocialRecovery.clear(this@SocialLocationService);stopSelf();break};SocialApi.status.value="Conexiune întreruptă. Reîncerc automat."}
     catch(e:TimeoutCancellationException){SocialApi.status.value="Serverul nu răspunde. Reîncerc automat."}
     catch(e:CancellationException){throw e}catch(_:Exception){SocialApi.status.value="Fără conexiune. Reîncerc automat; poziția veche expiră."}
     delay(if(continuous)30000 else 15000)
    }
   }catch(e:TimeoutCancellationException){SocialApi.status.value="Serverul nu a confirmat pornirea. Verifică sesiunea și încearcă din nou.";stopSelf()}catch(e:CancellationException){throw e}catch(e:Exception){SocialApi.status.value=e.message?:"Partajarea nu a putut porni.";if(session==null)SocialRecovery.clear(this@SocialLocationService);stopSelf()}
  }
  return if(continuous)START_STICKY else START_NOT_STICKY
 }
 private fun permission()=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 override fun onLocationChanged(loc:Location){
  val now=System.currentTimeMillis();if(sending||now-lastSend<(if(continuous)25000 else 15000)||session==null||!continuous&&now>=until||continuous&&!SocialRecovery.active(this)||FileSync.owner()!=owner||android.os.SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos>90000000000L)return
  sending=true;lastSend=now
  scope.launch{try{val b=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));val battery=((b?.getIntExtra(BatteryManager.EXTRA_LEVEL,0)?:0)*100.0/(b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100).coerceAtLeast(1)).coerceIn(0.0,100.0)
   withTimeout(20000){SocialApi.call(this@SocialLocationService,"location",SocialApi.obj("session" to session,"lat" to loc.latitude,"lon" to loc.longitude,"accuracy" to loc.accuracy.coerceIn(0f,10000f).toDouble(),"speed" to loc.speed.coerceIn(0f,100f).toDouble(),"battery" to battery,"at" to now),owner=owner!!)}
   SocialApi.status.value="Poziție trimisă${if(continuous)" doar partenerului"else ""} · precizie ±${loc.accuracy.toInt()} m"
  }catch(e:TimeoutCancellationException){SocialApi.status.value="Poziție netrimisă. Reîncerc automat."}catch(e:CancellationException){throw e}catch(e:Exception){SocialApi.status.value="Poziție netrimisă: ${e.message}";if(e is FileSync.Failure&&e.code in listOf(401,403,409)){SocialRecovery.clear(this@SocialLocationService);stopSelf()}}finally{sending=false}}
 }
 @Deprecated("Required on older Android") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
 override fun onProviderEnabled(provider:String){if(permission())runCatching{manager.requestLocationUpdates(provider,if(continuous)30000 else 15000,0f,this,Looper.getMainLooper())}}
 override fun onProviderDisabled(provider:String){SocialApi.status.value="GPS indisponibil; poziția expiră după două minute."}
 override fun onDestroy(){
  running=false;runCatching{manager.removeUpdates(this)};FirebaseAuth.getInstance().removeAuthStateListener(auth);scope.cancel()
  val who=owner;val active=session
  if(!continuous&&who!=null&&active!=null&&FileSync.owner()==who)SocialRecovery.revoke(this,who,active)
  stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
 }
}
