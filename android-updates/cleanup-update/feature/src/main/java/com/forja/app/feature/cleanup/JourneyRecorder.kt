package com.forja.app.feature.cleanup

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Durable owner-bound GPS outbox. Sharing grants are checked on the server, independently. */
internal object JourneyRecorder {
 val status=MutableStateFlow("")
 private val lock=Mutex()
 fun prefs(c:Context)=c.getSharedPreferences("journey_v26",Context.MODE_PRIVATE)
 fun active(c:Context)=prefs(c).getBoolean("active",false)&&prefs(c).getString("owner",null)==FileSync.owner()
 fun session(c:Context)=prefs(c).getString("session",null)
 fun start(c:Context){val owner=FileSync.owner()?:return;try{ContextCompat.startForegroundService(c,Intent(c,JourneyLocationService::class.java).setAction("START").putExtra("owner",owner).putExtra("consent",true));status.value="Pornesc explorarea…"}catch(e:Exception){status.value=e.message?:"Explorarea nu a pornit."}}
 fun resume(c:Context,boot:Boolean=false){
  if(!active(c)||JourneyLocationService.running)return
  if(boot&&Build.VERSION.SDK_INT>=29&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
  try{ContextCompat.startForegroundService(c,Intent(c,JourneyLocationService::class.java).setAction("RESUME"))}catch(_:Exception){status.value="Deschide FORJA pentru a relua explorarea."}
 }
 fun stop(c:Context,message:String="Explorarea s-a oprit. Sincronizez punctele deja înregistrate."){val p=prefs(c);val who=p.getString("owner",null);val id=p.getString("session",null);p.edit().putBoolean("active",false).commit();if(who!=null&&id!=null)runCatching{JourneyQueue(c).use{it.end(who,id)}};c.stopService(Intent(c,JourneyLocationService::class.java));runCatching{schedule(c)};status.value=message}
 fun schedule(c:Context){WorkManager.getInstance(c).enqueueUniqueWork("journey-sync-v26",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<JourneySyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
 suspend fun sync(c:Context)=lock.withLock {
  val owner=FileSync.owner()?:return@withLock
  withContext(Dispatchers.IO){JourneyQueue(c).use{db->
   val state=prefs(c);if(!state.getBoolean("active",false)&&state.getString("owner",null)==owner)state.getString("session",null)?.let{db.end(owner,it)}
   repeat(200){if(FileSync.owner()!=owner)return@withContext;val item=db.first(owner)?:return@repeat
    try{SocialApi.call(c,"journey/samples",SocialApi.obj("session" to item.getString("session"),"id" to item.getString("id"),"points" to JSONArray().put(item.getJSONObject("point"))),owner=owner);db.ack(item.getString("id"))}
    catch(e:FileSync.Failure){if(e.code in listOf(400,403,404,409)){status.value=if(e.code==400)"Un punct invalid sau mai vechi de 7 zile a fost omis; sincronizarea continuă." else "Unele puncte nu au fost acceptate: sesiune închisă sau acces revocat.";db.ack(item.getString("id"))}else throw e}
   }
   for(id in db.ended(owner)){if(db.hasSamples(owner,id))continue;if(FileSync.owner()!=owner)return@withContext;try{SocialApi.call(c,"journey/session?session="+android.net.Uri.encode(id),method="DELETE",owner=owner)}catch(e:FileSync.Failure){if(e.code !in listOf(403,404,409))throw e};db.closed(owner,id);if(!state.getBoolean("active",false)&&state.getString("owner",null)==owner&&state.getString("session",null)==id)state.edit().remove("session").commit()}
   if(db.first(owner)!=null)throw java.io.IOException("Sincronizarea continuă cu lotul următor.")
  }}
 }
}
private class JourneyQueue(c:Context):SQLiteOpenHelper(c.applicationContext,"journey-v26.db",null,1){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE samples(id TEXT PRIMARY KEY, owner TEXT NOT NULL, session TEXT NOT NULL, at INTEGER NOT NULL, body TEXT NOT NULL)");db.execSQL("CREATE INDEX sample_order ON samples(owner,at)");db.execSQL("CREATE TABLE closing(owner TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(owner,id))")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
 fun add(owner:String,session:String,point:JSONObject){writableDatabase.insertOrThrow("samples",null,ContentValues().apply{put("id",UUID.randomUUID().toString());put("owner",owner);put("session",session);put("at",point.getLong("at"));put("body",point.toString())})}
 fun first(owner:String):JSONObject?=readableDatabase.query("samples",null,"owner=?",arrayOf(owner),null,null,"at ASC,id ASC","1").use{if(!it.moveToFirst())null else JSONObject().put("id",it.getString(it.getColumnIndexOrThrow("id"))).put("session",it.getString(it.getColumnIndexOrThrow("session"))).put("point",JSONObject(it.getString(it.getColumnIndexOrThrow("body"))))}
 fun ack(id:String){writableDatabase.delete("samples","id=?",arrayOf(id))}
 fun end(owner:String,id:String){writableDatabase.insertWithOnConflict("closing",null,ContentValues().apply{put("owner",owner);put("id",id)},SQLiteDatabase.CONFLICT_IGNORE)}
 fun ended(owner:String):List<String> = readableDatabase.query("closing",arrayOf("id"),"owner=?",arrayOf(owner),null,null,null).use{q->buildList{while(q.moveToNext())add(q.getString(0))}}
 fun hasSamples(owner:String,id:String)=readableDatabase.rawQuery("SELECT 1 FROM samples WHERE owner=? AND session=? LIMIT 1",arrayOf(owner,id)).use{it.moveToFirst()}
 fun closed(owner:String,id:String){writableDatabase.delete("closing","owner=? AND id=?",arrayOf(owner,id))}
}
class JourneySyncWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork():Result=try{JourneyRecorder.sync(applicationContext);Result.success()}catch(e:CancellationException){throw e}catch(_:Exception){Result.retry()}}

/** Private exploration uses its own explicit consent and foreground notification. */
class JourneyLocationService:Service(),LocationListener {
 companion object{@Volatile var running=false;private set}
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private lateinit var locations:LocationManager
 private var owner:String?=null;private var id:String?=null;private var confirmed=false;private var startedAt=0L;private var lastAt=0L
 private val auth=FirebaseAuth.AuthStateListener{if(owner!=null&&FileSync.owner()!=owner)JourneyRecorder.stop(this)}
 override fun onCreate(){super.onCreate();locations=getSystemService(LocationManager::class.java);FirebaseAuth.getInstance().addAuthStateListener(auth)}
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="STOP"){JourneyRecorder.stop(this);return START_NOT_STICKY}
  if(owner!=null)return START_STICKY
  val p=JourneyRecorder.prefs(this);val resume=intent==null||intent.action=="RESUME"
  if(resume&&!JourneyRecorder.active(this)){stopSelf();return START_NOT_STICKY}
  owner=FileSync.owner()
  if(owner==null||(!resume&&(intent?.getBooleanExtra("consent",false)!=true||intent.getStringExtra("owner")!=owner))||!allowed()){stopSelf();return START_NOT_STICKY}
  id=if(resume)p.getString("session",null)else if(JourneyRecorder.active(this))JourneyRecorder.session(this)else UUID.randomUUID().toString()
  if(id==null){stopSelf();return START_NOT_STICKY}
  try{
   val nm=getSystemService(NotificationManager::class.java);check(nm.areNotificationsEnabled()){"Permite notificările pentru explorare."};nm.createNotificationChannel(NotificationChannel("forja-journey","Explorarea mea",NotificationManager.IMPORTANCE_LOW))
   val stop=PendingIntent.getService(this,626,Intent(this,JourneyLocationService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   val open=packageManager.getLaunchIntentForPackage(packageName)?.let{PendingIntent.getActivity(this,627,it,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)}
   val n=Notification.Builder(this,"forja-journey").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("FORJA · explorare personală").setContentText("Înregistrez traseul. Vizibilitatea se alege separat.").setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null,"Oprește explorarea",stop).build()).build()
   if(Build.VERSION.SDK_INT>=29)startForeground(626,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)else startForeground(626,n)
   p.edit().putString("owner",owner).putString("session",id).putBoolean("active",true).commit();running=true
  }catch(e:Exception){JourneyRecorder.status.value=e.message.orEmpty();stopSelf();return START_NOT_STICKY}
  scope.launch{
   try{
    // Only a previously acknowledged session may resume without a network connection.
    // An unacknowledged session must be created online before collecting any coordinates.
    if(p.getString("ack_owner",null)==owner&&p.getString("ack_session",null)==id&&p.getLong("started_at",0)>0){
     startedAt=p.getLong("started_at",0)
    }else{
     withTimeout(20000){JourneyRecorder.sync(this@JourneyLocationService)}
     val result=withTimeout(20000){SocialApi.call(this@JourneyLocationService,"journey/session",SocialApi.obj("id" to id,"consent" to true),owner=owner!!)}
     check(JourneyRecorder.active(this@JourneyLocationService)&&FileSync.owner()==owner&&JourneyRecorder.session(this@JourneyLocationService)==id)
     startedAt=result.getLong("started_at");check(startedAt>0)
     p.edit().putString("ack_owner",owner).putString("ack_session",id).putLong("started_at",startedAt).commit()
    }
    check(JourneyRecorder.active(this@JourneyLocationService)&&FileSync.owner()==owner)
    confirmed=true
    for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))if(locations.isProviderEnabled(provider))locations.requestLocationUpdates(provider,30000,0f,this@JourneyLocationService,Looper.getMainLooper())
    JourneyRecorder.status.value="Explorare activă · aștept o poziție precisă"
    while(isActive){delay(30000);if(!allowed()||!JourneyRecorder.active(this@JourneyLocationService)||FileSync.owner()!=owner){JourneyRecorder.stop(this@JourneyLocationService);break};try{JourneyRecorder.sync(this@JourneyLocationService);val state=SocialApi.call(this@JourneyLocationService,"journey/state",owner=owner!!);if(state.optJSONObject("session")?.optString("id")!=id){JourneyRecorder.stop(this@JourneyLocationService);break}}catch(e:CancellationException){throw e}catch(_:Exception){JourneyRecorder.status.value="Punctele rămân pe telefon până revine conexiunea."}}
   }catch(e:TimeoutCancellationException){JourneyRecorder.stop(this@JourneyLocationService,"Serverul nu a confirmat pornirea explorării. Verifică conexiunea și încearcă din nou.")}catch(e:CancellationException){throw e}catch(e:Exception){JourneyRecorder.stop(this@JourneyLocationService,e.message?:"Explorarea nu a pornit.")}
  }
  return START_STICKY
 }
 private fun allowed()=(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
 override fun onLocationChanged(location:Location){
  if(!confirmed||!JourneyRecorder.active(this)||FileSync.owner()!=owner)return
  val at=location.time;val age=android.os.SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos
  if(!JourneySamplePolicy.accept(at,startedAt,lastAt,System.currentTimeMillis(),age,location.accuracy.toDouble(),location.hasAccuracy(),location.latitude,location.longitude)){JourneyRecorder.status.value="Aștept o poziție precisă pentru traseu.";return}
  lastAt=at;val uid=owner?:return;val session=id?:return
  val point=SocialApi.obj("at" to at,"lat" to location.latitude,"lon" to location.longitude,"accuracy" to location.accuracy.toDouble(),"speed" to location.speed.toDouble().coerceIn(0.0,100.0))
  scope.launch{try{withContext(Dispatchers.IO){JourneyQueue(this@JourneyLocationService).use{it.add(uid,session,point)}}}catch(e:CancellationException){throw e}catch(e:Exception){JourneyRecorder.stop(this@JourneyLocationService,"Nu pot salva traseul pe telefon. Verifică spațiul disponibil.");return@launch};JourneyRecorder.status.value="Poziție salvată · ±${location.accuracy.toInt()} m";try{JourneyRecorder.sync(this@JourneyLocationService)}catch(e:CancellationException){throw e}catch(_:Exception){JourneyRecorder.schedule(this@JourneyLocationService)}}
 }
 @Deprecated("Older Android callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
 override fun onProviderEnabled(provider:String){if(confirmed&&allowed())runCatching{locations.requestLocationUpdates(provider,30000,0f,this,Looper.getMainLooper())}}
 override fun onProviderDisabled(provider:String){JourneyRecorder.status.value="GPS indisponibil. Intervalul lipsă nu este numărat."}
 override fun onDestroy(){running=false;runCatching{locations.removeUpdates(this)};FirebaseAuth.getInstance().removeAuthStateListener(auth);scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
