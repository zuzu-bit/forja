package com.forja.app.feature.cleanup

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONObject

/** A visible, owner-armed receiver. GPS is requested only for a live owner command. */
class LostPhoneService:Service(),LocationListener {
    companion object { @Volatile var running=false;private set;internal var current:LostPhoneService?=null;private const val ID=627 }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var d:RecoveryDevice?=null;private var command:JSONObject?=null;private var loop:Job?=null;private var deadline:Job?=null;private var sending:Job?=null
    private var lastSend=0L;private var lastFixElapsed=0L;private var startedElapsed=0L;private val registered=mutableSetOf<String>();private var noticeText=""
    private lateinit var manager:LocationManager
    private val auth=FirebaseAuth.AuthStateListener{if(d!=null&&FileSync.owner()!=d?.owner){LostPhoneRecovery.clear(this);stopSelf()}}
    override fun onCreate(){super.onCreate();manager=getSystemService(LocationManager::class.java);FirebaseAuth.getInstance().addAuthStateListener(auth);current=this}
    override fun onBind(i:Intent?):IBinder?=null
    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int {
        if(i?.action=="DISABLE"){LostPhoneRecovery.disable(this);stopSelf();return START_NOT_STICKY}
        if(i?.action=="STOP_SEARCH"){LostPhoneRecovery.stopSearch(this);if(!running){stopSelf();return START_NOT_STICKY};return START_STICKY}
        if(loop!=null)return START_STICKY
        d=LostPhoneRecovery.device(this)
        if(d==null||!LostPhoneRecovery.exactPermission(this)||!LostPhoneRecovery.notices(this)){stopSelf();return START_NOT_STICKY}
        try {
            val m=getSystemService(NotificationManager::class.java);m.createNotificationChannel(NotificationChannel("forja-recovery","Găsirea propriului telefon",NotificationManager.IMPORTANCE_LOW))
            val n=notification("Pregătit pentru cererile tale din site · GPS oprit pentru găsire")
            if(Build.VERSION.SDK_INT>=29)startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)else startForeground(ID,n)
            running=true
        }catch(_:Exception){stopSelf();return START_NOT_STICKY}
        loop=scope.launch {
            val owner=d!!
            while(isActive&&LostPhoneRecovery.device(this@LostPhoneService)==owner) {
                try {
                    if(!LostPhoneRecovery.exactPermission(this@LostPhoneService)||!LostPhoneRecovery.notices(this@LostPhoneService)){LostPhoneRecovery.disable(this@LostPhoneService);break}
                    val status=if(!manager.isLocationEnabledCompat())"location_off"else if(command!=null)"locating"else "ready"
                    val result=withTimeout(20000){LostPhoneRecovery.call(this@LostPhoneService,owner,"poll",SocialApi.obj("secret" to owner.secret,"status" to status))}
                    val next=result.optJSONObject("command")
                    if(next==null||next.optLong("until")<=System.currentTimeMillis()||LostPhoneRecovery.prefs(this@LostPhoneService).getString("blocked",null)==next.optString("id"))stopSearchLocally()
                    else if(!manager.isLocationEnabledCompat()){stopSearchLocally();updateNotice("Cerere primită · locația Android este dezactivată")}
                    else {
                        if(command?.optString("id")!=next.optString("id")) {
                            stopSearchLocally();check(next.optString("phase")=="active"||next.optLong("start_before")>System.currentTimeMillis())
                            command=next;startedElapsed=SystemClock.elapsedRealtimeNanos();lastSend=0;lastFixElapsed=0
                            LostPhoneRecovery.prefs(this@LostPhoneService).edit().putString("command",next.getString("id")).commit()
                            updateNotice("Localizare pornită din contul tău · aștept poziția GPS")
                            try{withTimeout(20000){LostPhoneRecovery.call(this@LostPhoneService,owner,"status",SocialApi.obj("secret" to owner.secret,"command" to next.getString("id"),"status" to "locating"))}}catch(e:Exception){stopSearchLocally();throw e}
                            check(LostPhoneRecovery.device(this@LostPhoneService)==owner)
                            deadline=scope.launch{delay((next.getLong("until")-System.currentTimeMillis()).coerceAtLeast(1));stopSearchLocally()}
                        }
                        registerProviders()
                    }
                }catch(e:TimeoutCancellationException){updateNotice(if(command==null)"Fără răspuns de la site · reîncerc automat"else "Localizare activă · conexiune întreruptă")}
                catch(e:CancellationException){throw e}
                catch(e:FileSync.Failure){if(e.code in listOf(401,403,404)){LostPhoneRecovery.clear(this@LostPhoneService);stopSelf();break};if(e.code==409)stopSearchLocally();updateNotice("Căutarea așteaptă reconectarea la site")}
                catch(_:Exception){updateNotice("Găsire activată · conexiune întreruptă; reîncerc")}
                delay(30000)
            }
            stopSelf()
        }
        return START_STICKY
    }
    private fun LocationManager.isLocationEnabledCompat()=if(Build.VERSION.SDK_INT>=28)isLocationEnabled else isProviderEnabled(LocationManager.GPS_PROVIDER)||isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    private fun registerProviders() {
        if(command==null||!LostPhoneRecovery.exactPermission(this))return
        for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))if(provider !in registered&&manager.isProviderEnabled(provider)){
            manager.requestLocationUpdates(provider,3000L,0f,this,Looper.getMainLooper());registered.add(provider)
        }
    }
    internal fun stopSearchLocally() {
        deadline?.cancel();deadline=null;sending?.cancel();sending=null;command=null;registered.clear();runCatching{manager.removeUpdates(this)}
        LostPhoneRecovery.prefs(this).edit().remove("command").apply()
        if(running)updateNotice("Pregătit pentru cererile tale din site · GPS oprit pentru găsire")
    }
    override fun onLocationChanged(loc:Location) {
        val owner=d?:return;val active=command?:return;val id=active.optString("id");val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtimeNanos()
        if(LostPhoneRecovery.device(this)!=owner||LostPhoneRecovery.prefs(this).getString("blocked",null)==id||now>=active.optLong("until")||sending?.isActive==true||now-lastSend<10000||!loc.hasAccuracy()||loc.elapsedRealtimeNanos<=lastFixElapsed||loc.elapsedRealtimeNanos<startedElapsed||elapsed-loc.elapsedRealtimeNanos !in 0..90000000000L)return
        if(!LostPhoneRecovery.exactPermission(this)||!LostPhoneRecovery.notices(this)){LostPhoneRecovery.disable(this);return}
        lastSend=now;lastFixElapsed=loc.elapsedRealtimeNanos
        sending=scope.launch {
            try {
                val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED));val level=(100.0*(battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,0)?:0)/(battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100).coerceAtLeast(1)).coerceIn(0.0,100.0)
                val at=now-(elapsed-loc.elapsedRealtimeNanos)/1000000
                withTimeout(20000){LostPhoneRecovery.call(this@LostPhoneService,owner,"position",SocialApi.obj("secret" to owner.secret,"command" to id,"lat" to loc.latitude,"lon" to loc.longitude,"accuracy" to loc.accuracy.coerceIn(0f,10000f).toDouble(),"at" to at,"battery" to level))}
                if(command?.optString("id")==id)updateNotice("Poziție trimisă în contul tău · precizie ±${loc.accuracy.toInt()} m")
            }catch(e:TimeoutCancellationException){updateNotice("Poziție netrimisă · aștept conexiunea")}
            catch(e:CancellationException){throw e}
            catch(e:FileSync.Failure){if(e.code==409)stopSearchLocally();else if(e.code in listOf(401,403,404)){LostPhoneRecovery.clear(this@LostPhoneService);stopSelf()}}
            catch(_:Exception){updateNotice("Poziție netrimisă · aștept conexiunea")}
        }
    }
    private fun notification(text:String):Notification {
        fun action(code:Int,name:String)=PendingIntent.getService(this,code,Intent(this,LostPhoneService::class.java).setAction(name),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=packageManager.getLaunchIntentForPackage(packageName)?.let{PendingIntent.getActivity(this,630,it,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)}
        return Notification.Builder(this,"forja-recovery").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("FORJA · găsirea propriului telefon").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).apply{
            if(command!=null)addAction(Notification.Action.Builder(null,"Oprește căutarea",action(628,"STOP_SEARCH")).build())
            addAction(Notification.Action.Builder(null,"Dezactivează găsirea",action(629,"DISABLE")).build())
        }.build()
    }
    private fun updateNotice(text:String){if(text==noticeText)return;noticeText=text;LostPhoneRecovery.prefs(this).edit().putString("status",text).apply();if(running)getSystemService(NotificationManager::class.java).notify(ID,notification(text))}
    @Deprecated("Required on older Android") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
    override fun onProviderEnabled(provider:String){if(command!=null)runCatching{registerProviders()}}
    override fun onProviderDisabled(provider:String){registered.remove(provider);updateNotice("Localizarea Android nu furnizează momentan o poziție")}
    override fun onDestroy(){running=false;if(current===this)current=null;stopSearchLocally();scope.cancel();FirebaseAuth.getInstance().removeAuthStateListener(auth);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
