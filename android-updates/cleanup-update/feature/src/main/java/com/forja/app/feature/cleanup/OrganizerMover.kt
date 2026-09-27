package com.forja.app.feature.cleanup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import org.json.JSONObject

internal object OrganizerMovePolicy {
    enum class Decision { RESUME, RECORD_MOVED, FINISH_REMOVAL, REVIEW }
    fun reconcile(sourceExists:Boolean,sourceMatches:Boolean,targetExists:Boolean,targetMatches:Boolean,authorized:Boolean):Decision = when {
        targetExists&&!targetMatches -> Decision.REVIEW
        sourceExists&&!sourceMatches -> Decision.REVIEW
        !sourceExists&&targetExists&&targetMatches -> Decision.RECORD_MOVED
        sourceExists&&sourceMatches&&targetExists&&targetMatches&&authorized -> Decision.FINISH_REMOVAL
        sourceExists&&sourceMatches&&!targetExists&&authorized -> Decision.RESUME
        else -> Decision.REVIEW
    }
    fun name(name:String,id:String):String {
        val dot=name.lastIndexOf('.');val base=(if(dot>0)name.substring(0,dot)else name).take(100)
        val extension=if(dot>0)name.substring(dot).take(20)else ""
        return "$base · ${id.replace("-", "").take(8)}$extension"
    }
}

/** Each destructive step has a committed intention and a verified, independently resumable result. */
internal object OrganizerMover {
    private suspend fun digest(c:Context,uri:Uri,guard:()->Unit):String? = try{OrganizerInventory.hash(c,uri.toString(),guard)}catch(e:CancellationException){throw e}catch(_:java.io.FileNotFoundException){null}
    private fun media(c:Context,uri:Uri):Pair<String,String>? =c.contentResolver.query(uri,arrayOf("relative_path","_display_name"),null,null,null)?.use{if(it.moveToFirst())it.getString(0).orEmpty() to it.getString(1).orEmpty()else null}
    private fun folders(c:Context,tree:Uri,path:String):DocumentFile {
        var node=checkNotNull(DocumentFile.fromTreeUri(c,tree));check(node.canWrite()){"Accesul la destinație a fost retras."}
        for(part in OrganizerSelection.path(path).split('/').filter{it.isNotBlank()}){
            val found=node.findFile(part);check(found==null||found.isDirectory){"Un fișier ocupă numele dosarului $part."}
            node=found?:checkNotNull(node.createDirectory(part)){"Nu pot crea dosarul $part."}
        };return node
    }
    suspend fun alreadyThere(c:Context,job:JSONObject,row:JSONObject,guard:()->Unit):Boolean {
        if(row.optBoolean("intent_saved"))return false
        val f=CleanupReportCodec.file(row.getJSONObject("file"));val target=OrganizerSelection.path(row.getString("destination"))
        val same=if(f.gallery){val path=if(target.startsWith("Pictures/"))target else "Pictures/$target";media(c,Uri.parse(f.uri))?.first?.trim('/')==path.trim('/')}
        else {var node=DocumentFile.fromTreeUri(c,Uri.parse(job.getString("tree")));for(part in target.split('/'))node=node?.findFile(part);node?.findFile(f.name)?.uri?.toString()?.let{OrganizerLedger.identity(it)==OrganizerLedger.identity(f.uri)}?:false}
        return same&&digest(c,Uri.parse(f.uri),guard)==row.getString("sha")
    }
    suspend fun apply(c:Context,db:OrganizerLedger,job:JSONObject,row:JSONObject,guard:()->Unit):JSONObject=withContext(Dispatchers.IO){
        val f=CleanupReportCodec.file(row.getJSONObject("file"));val source=Uri.parse(f.uri);val sha=row.getString("sha")
        val target=OrganizerSelection.path(row.getString("destination"));check(target.isNotBlank())
        if(f.gallery){
            check(Build.VERSION.SDK_INT>=29){"Mutarea galeriei necesită Android 10+; folosește sursa Fișiere."}
            val path=if(target.startsWith("Pictures/"))target.trimEnd('/')+"/" else "Pictures/"+target.trimEnd('/')+"/"
            var name=row.optString("target_name").ifBlank{f.name}
            val current=media(c,source);val actual=if(current!=null)digest(c,source,guard)else null
            if(current!=null&&current.first.trim('/')==path.trim('/')&&current.second==name&&actual==sha){row.put("state","moved").put("result_uri",source.toString()).put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),source.toString());db.saveItem(job.getString("id"),row);return@withContext row}
            check(current!=null&&actual==sha){"Originalul s-a schimbat sau nu mai este disponibil."};guard()
            // A collision is never an excuse to overwrite another original.
            val collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            fun occupied(candidate:String)=c.contentResolver.query(collection,arrayOf("_id"),"relative_path=? AND _display_name=?",arrayOf(path,candidate),null)?.use{it.moveToFirst()}?:false
            if(occupied(name)&&!row.has("target_name"))name=OrganizerMovePolicy.name(f.name,row.getString("original_id"))
            check(!occupied(name)){"Destinație ocupată; originalul a fost păstrat."}
            row.put("state","applying").put("target_name",name).put("target_path",path).put("source_sha256",sha);db.saveItem(job.getString("id"),row)
            guard();check(c.contentResolver.update(source,ContentValues().apply{put(MediaStore.MediaColumns.RELATIVE_PATH,path);put(MediaStore.MediaColumns.DISPLAY_NAME,name)},null,null)==1){"Android nu a confirmat mutarea."}
            check(media(c,source)==(path to name)&&digest(c,source,{})==sha){"Mutarea nu poate fi verificată; verifică originalul."}
            row.put("state","moved").put("result_uri",source.toString()).put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),source.toString());db.saveItem(job.getString("id"),row)
        }else {
            var name=row.optString("target_name").ifBlank{f.name}
            val parent=folders(c,Uri.parse(job.getString("tree")),target)
            if(!row.has("target_name")&&parent.findFile(name)!=null)name=OrganizerMovePolicy.name(f.name,row.getString("original_id"))
            var destination=row.optString("result_uri").takeIf{it.isNotBlank()}?.let{DocumentFile.fromSingleUri(c,Uri.parse(it))}?.takeIf{it.exists()}?:parent.findFile(name)
            val sourceHash=digest(c,source,guard);val targetHash=destination?.let{digest(c,it.uri,guard)}
            if(!row.optBoolean("intent_saved")){
                check(sourceHash==sha){"Conținutul originalului s-a schimbat."};check(destination==null){"Destinație ocupată; păstrez ambele originale."}
                guard();row.put("state","applying").put("source_sha256",sha).put("target_name",name).put("target_parent",parent.uri.toString()).put("intent_saved",true);db.saveItem(job.getString("id"),row)
            }
            when(OrganizerMovePolicy.reconcile(sourceHash!=null,sourceHash==sha,destination!=null,targetHash==sha,runCatching{guard();true}.getOrDefault(false))){
                OrganizerMovePolicy.Decision.REVIEW -> error("Rezultat ambiguu sau conținut schimbat. Originalele au fost păstrate.")
                OrganizerMovePolicy.Decision.RECORD_MOVED -> {
                    row.put("state","moved").put("result_uri",destination!!.uri.toString()).put("target_sha256",sha)
                    db.alias(job.getString("owner"),row.getString("original_id"),destination.uri.toString());db.saveItem(job.getString("id"),row);return@withContext row
                }
                OrganizerMovePolicy.Decision.RESUME -> {
                    guard();destination=checkNotNull(parent.createFile(f.mime.ifBlank{"application/octet-stream"},name)){"Nu pot crea copia; verifică spațiul și accesul."}
                    row.put("result_uri",destination!!.uri.toString());db.alias(job.getString("owner"),row.getString("original_id"),destination!!.uri.toString());db.saveItem(job.getString("id"),row)
                    checkNotNull(c.contentResolver.openInputStream(source)).use{input->checkNotNull(c.contentResolver.openOutputStream(destination!!.uri,"w")).use{out->
                        val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();guard();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.flush()
                    }}
                    check(digest(c,destination!!.uri,guard)==sha){"Copia nu corespunde originalului. Originalul este păstrat."}
                }
                OrganizerMovePolicy.Decision.FINISH_REMOVAL -> Unit
            }
            row.put("state","copied_pending_removal").put("result_uri",destination!!.uri.toString()).put("target_sha256",sha);db.saveItem(job.getString("id"),row)
            guard();check(digest(c,source,guard)==sha){"Originalul s-a modificat în timpul copierii; nu îl șterg."}
            check(DocumentsContract.deleteDocument(c.contentResolver,source)){"Copia este verificată; furnizorul nu a confirmat eliminarea originalului."}
            check(digest(c,source,{})==null&&digest(c,destination!!.uri,{})==sha){"Eliminarea originalului nu poate fi confirmată."}
            row.put("state","moved");db.alias(job.getString("owner"),row.getString("original_id"),destination!!.uri.toString());db.saveItem(job.getString("id"),row)
        }
        row
    }
}
