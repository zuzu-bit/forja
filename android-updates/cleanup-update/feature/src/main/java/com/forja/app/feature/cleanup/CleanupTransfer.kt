package com.forja.app.feature.cleanup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class CleanupInventory(val accept:suspend (List<CleanFile>)->Unit):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<CleanupInventory>
}
internal data class TransferSummary(val batch:String="",val total:Int=0,val sent:Int=0,val blocked:Int=0,val message:String="",val errors:List<String> = emptyList(),val active:Boolean=true) {
    val complete get()=total>0&&sent==total
}
internal class CleanupTransferDb(c:Context):SQLiteOpenHelper(c,"cleanup_selection_v16.db",null,2) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE batches(id TEXT PRIMARY KEY,owner TEXT NOT NULL,device TEXT NOT NULL,created INTEGER NOT NULL,registered INTEGER NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 1,message TEXT NOT NULL DEFAULT '',auto_grant TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,batch TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,folder TEXT NOT NULL,size INTEGER NOT NULL,sha TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'pending',error TEXT NOT NULL DEFAULT '',received INTEGER NOT NULL DEFAULT 0,expires INTEGER NOT NULL DEFAULT 0,UNIQUE(batch,uri))")
        db.execSQL("CREATE INDEX items_batch ON items(batch,status)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion<2)db.execSQL("ALTER TABLE batches ADD COLUMN auto_grant TEXT NOT NULL DEFAULT ''")}
    fun autoGrant(id:String)=readableDatabase.rawQuery("SELECT auto_grant FROM batches WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0)else ""}
    fun autoBatches(grant:String)=readableDatabase.rawQuery("SELECT id FROM batches WHERE auto_grant=? AND active=1",arrayOf(grant)).use{cur->buildList{while(cur.moveToNext())add(cur.getString(0))}}
    fun create(owner:String,device:String,files:List<CleanFile>,id:String=UUID.randomUUID().toString(),autoGrant:String=""):String {
        batch(id)?.let{check(it==(owner to device)&&autoGrant(id)==autoGrant){"Selecție incompatibilă."};return id}
        val db=writableDatabase;db.beginTransaction()
        try{
            db.execSQL("INSERT INTO batches(id,owner,device,created) VALUES(?,?,?,?)",arrayOf<Any>(id,owner,device,System.currentTimeMillis()))
            if(autoGrant.isNotEmpty())db.execSQL("UPDATE batches SET auto_grant=?,registered=1 WHERE id=?",arrayOf(autoGrant,id))
            for(f in CleanupSelection.freeze(files))db.execSQL("INSERT INTO items(id,batch,uri,name,mime,folder,size) VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(f.id,id,f.uri,f.name,f.mime,f.folder,f.size))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return id
    }
    fun batch(id:String):Pair<String,String>?=readableDatabase.rawQuery("SELECT owner,device FROM batches WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0) to it.getString(1)else null}
    fun latest(owner:String):String?=readableDatabase.rawQuery("SELECT id FROM batches WHERE owner=? ORDER BY created DESC LIMIT 1",arrayOf(owner)).use{if(it.moveToFirst())it.getString(0)else null}
    fun pending(id:String):List<TransferItem> = readableDatabase.rawQuery("SELECT id,uri,name,mime,folder,size,sha FROM items WHERE batch=? AND status='pending' ORDER BY rowid",arrayOf(id)).use{cur->buildList{while(cur.moveToNext())add(TransferItem(cur.getString(0),cur.getString(1),cur.getString(2),cur.getString(3),cur.getString(4),cur.getLong(5),cur.getString(6)))}}
    fun inventory(id:String):List<TransferItem> = readableDatabase.rawQuery("SELECT id,uri,name,mime,folder,size,sha FROM items WHERE batch=? ORDER BY rowid",arrayOf(id)).use{cur->buildList{while(cur.moveToNext())add(TransferItem(cur.getString(0),cur.getString(1),cur.getString(2),cur.getString(3),cur.getString(4),cur.getLong(5),cur.getString(6)))}}
    fun registered(id:String)=readableDatabase.rawQuery("SELECT registered FROM batches WHERE id=?",arrayOf(id)).use{it.moveToFirst()&&it.getInt(0)==1}
    fun active(id:String)=readableDatabase.rawQuery("SELECT active FROM batches WHERE id=?",arrayOf(id)).use{it.moveToFirst()&&it.getInt(0)==1}
    fun cancel(id:String){writableDatabase.execSQL("UPDATE batches SET active=0 WHERE id=?",arrayOf(id));message(id,"Transferul acestei selecții a fost oprit. Copiile deja primite expiră normal după 24 h.")}
    fun register(id:String){writableDatabase.execSQL("UPDATE batches SET registered=1 WHERE id=?",arrayOf(id))}
    fun sources(id:String):Pair<Boolean,Boolean> =readableDatabase.rawQuery("SELECT mime FROM items WHERE batch=?",arrayOf(id)).use{cur->var photos=false;var files=false;while(cur.moveToNext()){if(cur.getString(0).startsWith("image/"))photos=true else files=true};photos to files}
    fun update(id:String,values:ContentValues){check(writableDatabase.update("items",values,"id=?",arrayOf(id))==1)}
    fun message(id:String,value:String){writableDatabase.execSQL("UPDATE batches SET message=? WHERE id=?",arrayOf(value,id));CleanupTransfer.changed.value++}
    fun retry(id:String){writableDatabase.execSQL("UPDATE items SET status='pending',error='' WHERE batch=? AND status='blocked'",arrayOf(id))}
    fun summary(id:String):TransferSummary {
        var total=0;var sent=0;var blocked=0
        readableDatabase.rawQuery("SELECT status,COUNT(*) FROM items WHERE batch=? GROUP BY status",arrayOf(id)).use{while(it.moveToNext()){val n=it.getInt(1);total+=n;when(it.getString(0)){"sent"->sent+=n;"blocked"->blocked+=n}}}
        val message=readableDatabase.rawQuery("SELECT message FROM batches WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0)else ""}
        val errors=readableDatabase.rawQuery("SELECT name,error FROM items WHERE batch=? AND error<>'' LIMIT 30",arrayOf(id)).use{cur->buildList{while(cur.moveToNext())add(cur.getString(0)+": "+cur.getString(1))}}
        return TransferSummary(id,total,sent,blocked,message,errors,active(id))
    }
}
internal object CleanupTransfer {
    private val lock=Mutex()
    private val jobs=java.util.concurrent.ConcurrentHashMap<String,Job>()
    val changed=MutableStateFlow(0L)
    private const val TAG="forja-cleanup-selection-v16"
    fun cancel(c:Context,id:String){CleanupTransferDb(c).use{it.cancel(id)};jobs[id]?.cancel();WorkManager.getInstance(c).cancelUniqueWork("$TAG-$id")}
    fun schedule(c:Context,id:String){
        WorkManager.getInstance(c).enqueueUniqueWork("$TAG-$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CleanupTransferWorker>()
            .setInputData(workDataOf("batch" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build())
    }
    suspend fun drain(c:Context,id:String):Boolean {
        if(!lock.tryLock())return true
        val db=CleanupTransferDb(c)
        var listener:FirebaseAuth.AuthStateListener?=null
        try {
            val (uid,device)=db.batch(id)?:return false
            val autoGrant=db.autoGrant(id)
            if(autoGrant.isNotBlank()&&!CleanupAuto.valid(c,uid,autoGrant)){db.cancel(id);return false}
            if(!db.active(id))return false
            if(FileSync.owner()!=uid){db.message(id,"Conectează-te cu contul în care ai început curățenia.");return false}
            val job=currentCoroutineContext()[Job]!!
            jobs[id]=job
            listener=FirebaseAuth.AuthStateListener{if(it.currentUser?.uid!=uid)job.cancel()};FirebaseAuth.getInstance().addAuthStateListener(listener)
            suspend fun request(path:String,method:String="GET",body:ByteArray?=null,headers:Map<String,String> = emptyMap())=FileSync.request(c,uid,path,method,body,if(autoGrant.isBlank())headers else headers+mapOf("X-Cleanup-Run" to id),false)
            val settings="/v2/files/settings/$device"
            if(!db.registered(id)){
                // The dedicated cleanup device serves successive photo and document
                // sessions. The local durable queue is the exact source authorization.
                request(settings,"POST",JSONObject().put("enabled",true).put("photos",true).put("files",true).toString().toByteArray(),mapOf("Content-Type" to "application/json"));db.register(id)
            }
            val retry=CleanupTransferPump.run(object:TransferPort {
                override suspend fun pending()=db.pending(id)
                override suspend fun checkActive(){currentCoroutineContext().ensureActive();check(db.active(id)){"Transfer oprit."};check(FileSync.owner()==uid){"Contul s-a schimbat."};if(autoGrant.isNotBlank())check(CleanupAuto.valid(c,uid,autoGrant)){"Analiza automată a fost oprită pe telefon."}}
                override suspend fun upload(item:TransferItem):TransferReceipt {
                    if(item.size>FileSync.MAX_BYTES)throw TransferProblem(false,false,"Serverul acceptă maximum 25 MB pe fișier. Nu a fost trimis.")
                    db.message(id,"Trimit: ${item.name}")
                    try {
                        val remote=request(settings)
                        val kind=if(item.mime.startsWith("image/"))"photo"else "file"
                        if(!remote.optBoolean("enabled")||!remote.optBoolean(if(kind=="photo")"photos"else "files"))throw TransferProblem(false,true,"Trimiterea este oprită din site. Reactivează acolo și apasă Reîncearcă.")
                        val bytes=try{c.contentResolver.openInputStream(Uri.parse(item.uri))?.use{input->
                            ByteArrayOutputStream().use{out->val buffer=ByteArray(32768);while(true){checkActive();val n=input.read(buffer);if(n<0)break;if(out.size()+n>FileSync.MAX_BYTES)throw TransferProblem(false,false,"Fișierul depășește 25 MB.");out.write(buffer,0,n)};out.toByteArray()}
                        }?:throw TransferProblem(false,false,"Fișierul nu mai este accesibil.")}
                        catch(e:CancellationException){throw e}catch(e:TransferProblem){throw e}catch(e:Exception){throw TransferProblem(false,false,"Nu pot citi originalul: ${e.message}")}
                        if(bytes.isEmpty())throw TransferProblem(false,false,"Fișier gol.")
                        val sha=FileSync.sha(bytes)
                        if(item.sha.isNotBlank()&&sha!=item.sha)throw TransferProblem(false,false,"Originalul s-a modificat după prima încercare. Analizează-l din nou.")
                        db.update(item.id,ContentValues().apply{put("sha",sha)})
                        val mime=item.mime.lowercase().takeIf{it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))}?:"application/octet-stream"
                        fun clean(s:String,max:Int)=s.replace(Regex("[\\x00-\\x1f\\x7f]")," ").take(max)
                        val receipt=request("/v2/files/${item.id}","PUT",bytes,mapOf("X-Device-ID" to device,"X-File-Kind" to kind,"X-File-Name" to FileSync.header(clean(item.name,200).ifBlank{"Fișier"}),"X-File-Folder" to FileSync.header(clean(item.folder.trim('/'),120)),"X-Media-Type" to mime,"X-File-Sha256" to sha))
                        check(receipt.optString("sha256")==sha&&receipt.optLong("bytes")==bytes.size.toLong()){"Serverul nu a confirmat toate datele."}
                        return TransferReceipt(receipt.optString("id"),sha,receipt.optLong("bytes"),receipt.optLong("received_at"),receipt.optLong("expires_at"))
                    }catch(e:FileSync.Failure){
                        val message=when(e.code){404->"Site-ul trebuie actualizat la versiunea cu Poze și documente.";410->"Copia a expirat sau a fost ștearsă din site. O analiză nouă creează o copie nouă.";413->"Limita de mărime a serverului a fost depășită.";507->"Spațiul temporar din site este plin.";else->e.message?:"Server HTTP ${e.code}"}
                        throw TransferProblem(e.code in setOf(408,429)||e.code>=500,e.code !in setOf(410,413),message)
                    }
                }
                override suspend fun confirm(item:TransferItem,receipt:TransferReceipt){
                    db.update(item.id,ContentValues().apply{put("status","sent");put("error","");put("received",receipt.received);put("expires",receipt.expires)})
                    changed.value++
                    // A preview is optional; the complete original is already confirmed on the site.
                    if(item.mime.startsWith("image/"))try{
                        val bitmap=CleanupCatalog(c).bitmap(Uri.parse(item.uri),320)
                        val bytes=try{ByteArrayOutputStream().use{out->bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,out);out.toByteArray()}}finally{bitmap.recycle()}
                        if(bytes.size<=160*1024)request("/v2/files/${item.id}/thumbnail","PUT",bytes,mapOf("X-Device-ID" to device))
                    }catch(e:CancellationException){throw e}catch(_:Exception){}
                }
                override suspend fun failed(item:TransferItem,problem:TransferProblem){
                    db.update(item.id,ContentValues().apply{put("status",if(problem.retryable||problem.stopRound)"pending"else "blocked");put("error",problem.message)})
                    db.message(id,problem.message.orEmpty())
                }
            })
            val summary=db.summary(id)
            if(summary.complete)db.message(id,"Toate cele ${summary.total} originale au fost primite de site. Fiecare copie expiră după 24 h de la primire.")
            else if(summary.blocked>0)db.message(id,"${summary.sent}/${summary.total} primite · ${summary.blocked} fișiere necesită atenție.")
            return retry
        }catch(e:CancellationException){throw e}catch(e:Exception){db.message(id,e.message?:"Transferul va fi reîncercat.");return e !is FileSync.Failure||e.code !in setOf(401,403,404,423)}
        finally{jobs.remove(id);listener?.let{FirebaseAuth.getInstance().removeAuthStateListener(it)};db.close();lock.unlock()}
    }
}
class CleanupTransferWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p) {
    override suspend fun doWork():Result {
        val id=inputData.getString("batch")?:return Result.failure()
        return if(CleanupTransfer.drain(applicationContext,id))Result.retry()else Result.success()
    }
}
