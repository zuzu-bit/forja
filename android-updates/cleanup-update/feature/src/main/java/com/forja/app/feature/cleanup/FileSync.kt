package com.forja.app.feature.cleanup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object FileSync {
    const val SITE="https://forja-insights.forja-22e7ea2d.workers.dev"
    const val MAX_BYTES=25*1024*1024
    const val WORK="forja-file-sync-v15"
    val lock=Mutex()
    private val calls=java.util.Collections.synchronizedSet(mutableSetOf<Call>())
    private val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).writeTimeout(120,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS).build()
    fun prefs(c:Context)=c.getSharedPreferences("file_sync_v15",Context.MODE_PRIVATE)
    fun status(c:Context,s:String){prefs(c).edit().putString("status",s).apply()}
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun owner()=FirebaseAuth.getInstance().currentUser?.uid
    fun stop(c:Context){prefs(c).edit().putBoolean("enabled",false).putString("status","Trimiterea automată este oprită pe acest telefon.").commit();WorkManager.getInstance(c).cancelAllWorkByTag(WORK);synchronized(calls){calls.toList().forEach{it.cancel()}}}
    fun activate(c:Context,photos:Boolean,tree:String,limit:Int,wifi:Boolean){
        val uid=checkNotNull(owner()){"Conectează-te în aplicație cu contul FORJA."};val p=prefs(c)
        val device=p.getString("device",null)?:UUID.randomUUID().toString()
        // Replacing the job cancels the old transfer before adopting the explicitly selected sources.
        stop(c)
        p.edit().putString("owner",uid).putString("device",device).putBoolean("photos",photos).putString("tree",tree).putInt("limit",limit).putBoolean("wifi",wifi).putString("generation",UUID.randomUUID().toString()).putBoolean("enabled",true).putBoolean("register",true).putString("status","Activată. Așteaptă conexiunea și condițiile telefonului.").commit()
        schedule(c,true)
    }
    fun schedule(c:Context,now:Boolean=false){
        val p=prefs(c);if(!p.getBoolean("enabled",false)||p.getString("owner",null)!=owner())return
        val constraints=Constraints.Builder().setRequiredNetworkType(if(p.getBoolean("wifi",true))NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        val wm=WorkManager.getInstance(c)
        wm.enqueueUniquePeriodicWork(WORK,ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<FileSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).addTag(WORK).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        if(now)wm.enqueueUniqueWork(WORK+"-now",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<FileSyncWorker>().setConstraints(constraints).addTag(WORK).build())
    }
    class Failure(val code:Int,message:String):IOException(message)
    suspend fun request(c:Context,uid:String,path:String,method:String="GET",body:ByteArray?=null,headers:Map<String,String> = emptyMap(),requireEnabled:Boolean=true,generation:String?=null):JSONObject {
        fun valid(){if(generation!=null)check(prefs(c).getString("generation",null)==generation){"Setările de sincronizare s-au schimbat."};check(owner()==uid){"Contul s-a schimbat. Reactivează sincronizarea în contul dorit."};if(requireEnabled)check(prefs(c).getBoolean("enabled",false)&&prefs(c).getString("owner",null)==uid){"Sincronizare oprită."}}
        valid();val token=FirebaseAuth.getInstance().currentUser!!.getIdToken(false).await().token?:error("Autentifică-te din nou.");valid()
        val request=FileSyncWire.build(SITE+path,token,method,body,headers)
        return suspendCancellableCoroutine{cont->
            val call=http.newCall(request);calls.add(call);cont.invokeOnCancellation{call.cancel();calls.remove(call)}
            call.enqueue(object:Callback{
                override fun onFailure(call:Call,e:IOException){calls.remove(call);if(cont.isActive)cont.resumeWithException(e)}
                override fun onResponse(call:Call,response:Response){calls.remove(call);try{response.use{
                    valid();val raw=it.body?.string().orEmpty();val data=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
                    if(!it.isSuccessful)throw Failure(it.code,data.optString("error","Server HTTP ${it.code}"))
                    if(cont.isActive)cont.resume(data)
                }}catch(e:Exception){if(cont.isActive)cont.resumeWithException(e)}}
            })
        }
    }
    fun header(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
}

/** Public constructor is used by the existing WorkManager runtime in the preserved APK. */
class FileSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=FileSync.lock.withLock {
        val c=applicationContext;val p=FileSync.prefs(c);val uid=p.getString("owner",null)?:return@withLock Result.success()
        if(!p.getBoolean("enabled",false))return@withLock Result.success()
        if(FileSync.owner()!=uid){FileSync.status(c,"Conectează-te cu contul în care ai activat sincronizarea.");return@withLock Result.success()}
        val generation=p.getString("generation",null)?:return@withLock Result.success()
        suspend fun request(path:String,method:String="GET",body:ByteArray?=null,headers:Map<String,String> = emptyMap())=FileSync.request(c,uid,path,method,body,headers,true,generation)
        val job=currentCoroutineContext()[Job]!!
        val auth=FirebaseAuth.getInstance();val listener=FirebaseAuth.AuthStateListener{if(it.currentUser?.uid!=uid)job.cancel()};auth.addAuthStateListener(listener)
        try {
            val device=p.getString("device",null)?:error("Reactivează sincronizarea.")
            val wantPhotos=p.getBoolean("photos",false);val tree=p.getString("tree","").orEmpty();val settingsPath="/v2/files/settings/$device"
            if(p.getBoolean("register",false)){
                request(settingsPath,"POST",JSONObject().put("enabled",true).put("photos",wantPhotos).put("files",tree.isNotEmpty()).toString().toByteArray(),mapOf("Content-Type" to "application/json"))
                if(p.getString("generation",null)==generation)p.edit().putBoolean("register",false).commit()
            }
            val remote=request(settingsPath)
            if(!remote.optBoolean("enabled")){FileSync.status(c,"Sincronizarea este pusă pe pauză din site.");return@withLock Result.success()}
            val catalog=CleanupCatalog(c,p.getInt("limit",50));val candidates=mutableListOf<Pair<CleanFile,String>>()
            FileSync.status(c,"Verific pozele și dosarul autorizat…")
            if(wantPhotos&&remote.optBoolean("photos"))candidates+=catalog.gallery(true,false).first.map{it to "photo"}
            if(tree.isNotEmpty()&&remote.optBoolean("files"))candidates+=catalog.folder(Uri.parse(tree),true).first.map{it to "file"}
            val ledger=c.getSharedPreferences("file_sync_ledger_"+FileSync.sha(uid.toByteArray()).take(24),Context.MODE_PRIVATE)
            val until=SystemClock.elapsedRealtime()+8*60*1000;var sent=0;var oversized=0;var unreadable=0;var deferred=0
            for((file,kind) in candidates.distinctBy{it.first.uri}){
                currentCoroutineContext().ensureActive()
                check(p.getBoolean("enabled",false)&&p.getString("generation",null)==generation&&FileSync.owner()==uid){"Sincronizare oprită."}
                if(sent>=25||SystemClock.elapsedRealtime()>until){deferred++;continue}
                if(file.bytes>FileSync.MAX_BYTES){oversized++;continue}
                val key=FileSync.sha(file.uri.toByteArray());val old=runCatching{JSONObject(ledger.getString(key,"{}")!!)}.getOrElse{JSONObject()}
                val signature="${file.modified}:${file.bytes}"
                if(file.modified>0&&file.bytes>0&&old.optBoolean("done")&&old.optString("signature")==signature)continue
                if(!ledger.contains(key)&&ledger.all.size>=15000)error("Registrul de sincronizare a atins 15.000 de fișiere. Fișierele deja trimise rămân protejate de retrimitere.")
                val bytes=try{c.contentResolver.openInputStream(Uri.parse(file.uri))?.use{input->
                    val out=ByteArrayOutputStream();val buffer=ByteArray(32*1024)
                    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;if(out.size()+n>FileSync.MAX_BYTES)throw FileSync.Failure(413,"Fișier prea mare");out.write(buffer,0,n)};out.toByteArray()
                }?:throw IOException("Fișier inaccesibil")
                }catch(e:CancellationException){throw e}catch(e:FileSync.Failure){oversized++;continue}catch(_:Exception){unreadable++;continue}
                if(bytes.isEmpty()){unreadable++;continue}
                val digest=FileSync.sha(bytes)
                if(old.optBoolean("done")&&old.optString("sha")==digest){old.put("signature",signature);ledger.edit().putString(key,old.toString()).commit();continue}
                val id=if(old.optString("sha")==digest&&old.has("id"))old.getString("id")else UUID.randomUUID().toString()
                val pending=JSONObject().put("id",id).put("sha",digest).put("signature",signature).put("done",false)
                check(ledger.edit().putString(key,pending.toString()).commit()){"Nu pot salva starea transferului."}
                val mime=file.mime.lowercase().takeIf{it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))}?:"application/octet-stream"
                val name=file.name.replace(Regex("[\\x00-\\x1f\\x7f]")," ").take(200).ifBlank{"Fișier"}
                val folder=file.path.trim('/').replace(Regex("[\\x00-\\x1f\\x7f]")," ").take(120)
                FileSync.status(c,"Încarc: $name · $sent trimise în această rundă")
                val receipt=try{request("/v2/files/$id","PUT",bytes,mapOf("X-Device-ID" to device,"X-File-Kind" to kind,"X-File-Name" to FileSync.header(name),"X-File-Folder" to FileSync.header(folder),"X-Media-Type" to mime,"X-File-Sha256" to digest))}
                catch(e:FileSync.Failure){if(e.code==410){pending.put("done",true);ledger.edit().putString(key,pending.toString()).commit();continue};throw e}
                check(receipt.optString("id")==id&&receipt.optString("sha256")==digest&&receipt.optLong("bytes")==bytes.size.toLong()){"Serverul nu a confirmat fișierul complet."}
                pending.put("done",true);check(ledger.edit().putString(key,pending.toString()).commit()){"Nu pot salva confirmarea transferului."};sent++
                if(mime.startsWith("image/"))try{
                    val bitmap=catalog.bitmap(Uri.parse(file.uri),320)
                    val thumb=try{ByteArrayOutputStream().use{out->bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,out);out.toByteArray()}}finally{bitmap.recycle()}
                    if(thumb.size<=160*1024)request("/v2/files/$id/thumbnail","PUT",thumb,mapOf("X-Device-ID" to device))
                }catch(e:CancellationException){throw e}catch(_:Exception){/* Original remains available if a preview cannot be generated. */}
            }
            val detail=listOfNotNull(if(deferred>0)"$deferred rămase pentru runda următoare"else null,if(oversized>0)"$oversized peste 25 MB"else null,if(unreadable>0)"$unreadable inaccesibile sau goale"else null).joinToString(" · ")
            FileSync.status(c,"$sent fișiere noi trimise. Copiile expiră după 24 h."+(if(detail.isEmpty())""else "\n$detail")+"\nVerificat: "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date()))
            Result.success()
        }catch(e:CancellationException){throw e}catch(e:Exception){
            if(p.getBoolean("enabled",false)&&p.getString("generation",null)==generation)FileSync.status(c,e.message?:"Transferul nu a reușit. Reîncerc automat.")
            if(e is FileSync.Failure&&e.code in setOf(401,403,423))Result.success()else Result.retry()
        }finally{auth.removeAuthStateListener(listener)}
    }
}
