package com.forja.app.feature.cleanup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class PhoneContact(val name:String,val number:String,val normalized:String?)
internal object ContactSync {
 private const val WORK="forja-contacts-v22";val mutex=Mutex()
 fun prefs(c:Context)=c.getSharedPreferences(WORK,Context.MODE_PRIVATE)
 fun allowed(c:Context)=ContextCompat.checkSelfPermission(c,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
 fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)&&prefs(c).getString("owner",null)==FileSync.owner()
 suspend fun read(c:Context,region:String):List<PhoneContact> = withContext(Dispatchers.IO){
  check(allowed(c)){"Permite citirea agendei în Android."};require(Regex("[A-Z]{2}").matches(region)){"Folosește codul țării, de exemplu RO."}
  val out=linkedMapOf<String,PhoneContact>()
  c.contentResolver.query(Phone.CONTENT_URI,arrayOf(Phone.DISPLAY_NAME_PRIMARY,Phone.NUMBER),null,null,Phone.DISPLAY_NAME_PRIMARY+" COLLATE LOCALIZED ASC")?.use{cur->
   while(cur.moveToNext()){currentCoroutineContext().ensureActive();val name=cur.getString(0).orEmpty();val raw=cur.getString(1).orEmpty();if(raw.isBlank())continue;val normalized=PhoneNumberUtils.formatNumberToE164(raw,region);val key=normalized?:raw;out.putIfAbsent(key,PhoneContact(name.ifBlank{raw},raw,normalized))}
  };out.values.toList()
 }
 suspend fun discovery(c:Context){val uid=checkNotNull(FileSync.owner());val user=checkNotNull(FirebaseAuth.getInstance().currentUser);check(!user.phoneNumber.isNullOrBlank()){"Verifică întâi numărul tău prin SMS."};user.getIdToken(true).await();check(FileSync.owner()==uid);SocialApi.call(c,"contacts/discovery",SocialApi.obj("consent" to true),owner=uid)}
 fun enable(c:Context,region:String){check(allowed(c));prefs(c).edit().putString("owner",FileSync.owner()).putString("region",region).putString("generation",java.util.UUID.randomUUID().toString()).putBoolean("enabled",true).commit();schedule(c)}
 fun stop(c:Context){prefs(c).edit().putBoolean("enabled",false).remove("generation").remove("matches").putString("message","Sincronizarea agendei este oprită.").commit();WorkManager.getInstance(c).cancelUniqueWork(WORK)}
 fun schedule(c:Context){if(!enabled(c))return;if(!allowed(c)){stop(c);return};WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK,ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ContactSyncWorker>(24,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())}
 suspend fun sync(c:Context,manual:Boolean=false):JSONArray=mutex.withLock{
  val uid=checkNotNull(FileSync.owner());check(enabled(c)&&allowed(c));val p=prefs(c);val generation=p.getString("generation",null)
  fun valid(){check(enabled(c)&&allowed(c)&&FileSync.owner()==uid&&p.getString("generation",null)==generation){"Sincronizarea contactelor s-a oprit."}}
  val all=read(c,p.getString("region","RO")!!).filter{it.normalized!=null};valid();val out=JSONArray()
  // The full agenda is read locally; the documented daily server quota bounds matching.
  val chosen=all.take(10000);var completed=0
  for(batch in chosen.chunked(200)){valid();val response=SocialApi.call(c,"contacts/match",SocialApi.obj("numbers" to JSONArray(batch.map{it.normalized}),"consent" to true),owner=uid);valid();for(m in response.rows("matches")){val entry=batch.getOrNull(m.getInt("index"))?:continue;out.put(JSONObject(m.toString()).put("number",entry.normalized).put("contact_name",entry.name))};completed+=batch.size}
  valid();p.edit().putString("matches",out.toString()).putLong("last",System.currentTimeMillis()).putString("message","${all.size} numere valide în agendă · $completed verificate · ${out.length()} persoane găsite"+(if(all.size>10000)". Limita zilnică: 10.000 numere."else "")).commit();out
 }
 fun matches(c:Context):List<JSONObject>{if(!enabled(c))return emptyList();return runCatching{val a=JSONArray(prefs(c).getString("matches","[]"));(0 until a.length()).map{a.getJSONObject(it)}}.getOrDefault(emptyList())}
}
class ContactSyncWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork():Result {val c=applicationContext;if(!ContactSync.enabled(c)||!ContactSync.allowed(c)){ContactSync.stop(c);return Result.success()};if(System.currentTimeMillis()-ContactSync.prefs(c).getLong("last",0)<23*3600000L)return Result.success();return try{ContactSync.sync(c);Result.success()}catch(e:FileSync.Failure){ContactSync.prefs(c).edit().putString("message",e.message).apply();if(e.code in listOf(401,403,429))Result.success()else Result.retry()}catch(e:CancellationException){throw e}catch(e:Exception){ContactSync.prefs(c).edit().putString("message",e.message).apply();Result.retry()}}}
