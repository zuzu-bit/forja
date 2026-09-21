package com.forja.app.feature.cleanup

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class RecoveryDevice(val owner:String,val id:String,val secret:String)
internal object LostPhoneRecovery {
    private const val PREF="forja_lost_phone_v23"
    fun prefs(c:Context)=c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun device(c:Context):RecoveryDevice? {
        val p=prefs(c);val owner=p.getString("owner",null)?:return null
        if(!p.getBoolean("enabled",false)||owner!=FileSync.owner())return null
        return RecoveryDevice(owner,p.getString("id",null)?:return null,p.getString("secret",null)?:return null)
    }
    fun exactPermission(c:Context)=ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    fun notices(c:Context):Boolean {
        val m=c.getSystemService(NotificationManager::class.java)
        return m.areNotificationsEnabled()&&(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&m.getNotificationChannel("forja-recovery")?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    suspend fun call(c:Context,d:RecoveryDevice,path:String,body:JSONObject?=null,method:String=if(body==null)"GET"else "POST"):JSONObject {
        check(device(c)==d){"Găsirea telefonului s-a oprit sau contul s-a schimbat."}
        val result=FileSync.request(c,d.owner,"/v2/recovery/devices/${d.id}/$path",method,body?.toString()?.toByteArray(),mapOf("Content-Type" to "application/json"),false)
        check(device(c)==d){"Găsirea telefonului s-a oprit."};return result
    }
    suspend fun activate(c:Context,name:String) {
        check(exactPermission(c)&&notices(c)){"Permite locația precisă și notificările în Android."}
        check(device(c)==null){"Găsirea este deja activată."}
        val d=RecoveryDevice(checkNotNull(FileSync.owner()){"Conectează-te în FORJA."},UUID.randomUUID().toString(),ByteArray(32).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)})
        val body=SocialApi.obj("name" to name.trim().take(60),"secret" to d.secret,"consent" to true)
        try {
            FileSync.request(c,d.owner,"/v2/recovery/devices/${d.id}/grant","POST",body.toString().toByteArray(),mapOf("Content-Type" to "application/json"),false)
            check(FileSync.owner()==d.owner&&exactPermission(c)&&notices(c))
            prefs(c).edit().putString("owner",d.owner).putString("id",d.id).putString("secret",d.secret).putString("name",name.trim().take(60)).putBoolean("enabled",true).remove("blocked").remove("command").commit()
            resume(c)
        } catch(e:Exception){queue(c,d,"revoke",null);throw e}
    }
    fun resume(c:Context,boot:Boolean=false) {
        if(device(c)==null||LostPhoneService.running)return
        if(boot&&Build.VERSION.SDK_INT>=29&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        if(!exactPermission(c)||!notices(c))return
        runCatching{ContextCompat.startForegroundService(c,Intent(c,LostPhoneService::class.java))}.onFailure{prefs(c).edit().putString("status","Deschide FORJA pentru a reconecta găsirea telefonului.").apply()}
    }
    fun clear(c:Context){prefs(c).edit().putBoolean("enabled",false).remove("command").remove("secret").commit()}
    fun disable(c:Context) {val d=device(c);clear(c);c.stopService(Intent(c,LostPhoneService::class.java));if(d!=null)queue(c,d,"revoke",null)}
    fun stopSearch(c:Context) {
        val d=device(c)?:return;val command=prefs(c).getString("command",null)?:return
        prefs(c).edit().putString("blocked",command).remove("command").commit()
        LostPhoneService.current?.stopSearchLocally();queue(c,d,"stop",command)
    }
    private fun queue(c:Context,d:RecoveryDevice,action:String,command:String?) {
        WorkManager.getInstance(c).enqueueUniqueWork("recovery-${d.id}-$action-${command.orEmpty()}",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<RecoveryRevokeWorker>().setInputData(workDataOf("owner" to d.owner,"id" to d.id,"secret" to d.secret,"action" to action,"command" to command)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
    fun install(app:Application) {
        app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:Activity){resume(app)}
            override fun onActivityCreated(a:Activity,b:Bundle?){};override fun onActivityStarted(a:Activity){};override fun onActivityStopped(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        })
    }
}
class RecoveryRevokeWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p) {
    override suspend fun doWork():Result {
        val uid=inputData.getString("owner")?:return Result.failure();if(uid!=FileSync.owner())return Result.success()
        val id=inputData.getString("id")?:return Result.failure();val secret=inputData.getString("secret")?:return Result.failure();val action=inputData.getString("action")?:return Result.failure()
        val body=SocialApi.obj("secret" to secret);if(action=="stop")body.put("command",inputData.getString("command"))
        return try {FileSync.request(applicationContext,uid,"/v2/recovery/devices/$id/$action","POST",body.toString().toByteArray(),mapOf("Content-Type" to "application/json"),false);Result.success()}
        catch(e:CancellationException){throw e}catch(e:FileSync.Failure){if(e.code in listOf(401,403,404,409))Result.success()else Result.retry()}catch(_:Exception){Result.retry()}
    }
}
