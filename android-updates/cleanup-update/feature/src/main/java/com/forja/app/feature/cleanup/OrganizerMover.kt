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
    enum class Presence { PRESENT, ABSENT, UNKNOWN }
    /** A failed provider read is not evidence that an original was deleted. */
    fun stoppedState(source:Presence,sourceMatches:Boolean,target:Presence,targetMatches:Boolean,targetOwned:Boolean,targetFinal:Boolean):String=when {
        !targetOwned||!targetFinal||target!=Presence.PRESENT||!targetMatches -> "needs_review"
        source==Presence.ABSENT -> "moved"
        source==Presence.PRESENT&&sourceMatches -> "copied_pending_removal"
        else -> "needs_review"
    }
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
    private suspend fun digest(c:Context,uri:Uri,guard:()->Unit):String=OrganizerInventory.hash(c,uri.toString(),guard)
    private data class DocumentProbe(val presence:OrganizerMovePolicy.Presence,val name:String="")
    private fun document(c:Context,uri:Uri):DocumentProbe=try{
        c.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{
            if(it.moveToFirst())DocumentProbe(OrganizerMovePolicy.Presence.PRESENT,it.getString(1).orEmpty())else DocumentProbe(OrganizerMovePolicy.Presence.ABSENT)
        }?:DocumentProbe(OrganizerMovePolicy.Presence.UNKNOWN)
    }catch(_:Exception){DocumentProbe(OrganizerMovePolicy.Presence.UNKNOWN)}
    /** Only a successful, complete parent listing can prove a missing SAF source. */
    private fun sourceDocument(c:Context,job:JSONObject,f:CleanFile):DocumentProbe=try{
        val tree=Uri.parse(job.getString("tree"));val original=DocumentsContract.getDocumentId(Uri.parse(f.uri))
        val parent=Uri.parse(f.parent);val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{q->
            if(q.extras.getBoolean(DocumentsContract.EXTRA_LOADING,false)||q.extras.getString(DocumentsContract.EXTRA_ERROR).orEmpty().isNotBlank())return@use DocumentProbe(OrganizerMovePolicy.Presence.UNKNOWN)
            var result=DocumentProbe(OrganizerMovePolicy.Presence.ABSENT)
            while(q.moveToNext())if(q.getString(0)==original){result=DocumentProbe(OrganizerMovePolicy.Presence.PRESENT,q.getString(1).orEmpty());break}
            result
        }?:DocumentProbe(OrganizerMovePolicy.Presence.UNKNOWN)
    }catch(_:Exception){DocumentProbe(OrganizerMovePolicy.Presence.UNKNOWN)}
    private suspend fun verifiedDigest(c:Context,uri:Uri):String?=try{digest(c,uri,{})}catch(e:CancellationException){throw e}catch(_:Exception){null}
    private fun media(c:Context,uri:Uri):Pair<String,String>? =c.contentResolver.query(uri,arrayOf("relative_path","_display_name"),null,null,null)?.use{if(it.moveToFirst())it.getString(0).orEmpty() to it.getString(1).orEmpty()else null}
    private fun folders(c:Context,tree:Uri,path:String,guard:()->Unit):DocumentFile {
        guard()
        var node=checkNotNull(DocumentFile.fromTreeUri(c,tree));check(node.canWrite()){"Accesul la destinație a fost retras."}
        for(part in OrganizerSelection.path(path).split('/').filter{it.isNotBlank()}){
            val found=node.findFile(part);check(found==null||found.isDirectory){"Un fișier ocupă numele dosarului $part."}
            if(found==null)guard()
            node=found?:checkNotNull(node.createDirectory(part)){"Nu pot crea dosarul $part."}
        };return node
    }
    /** Read-only physical reconciliation, including after cancel/revoke. Never copies or removes files. */
    suspend fun reconcile(c:Context,db:OrganizerLedger,job:JSONObject,row:JSONObject):JSONObject=withContext(Dispatchers.IO){
        val f=CleanupReportCodec.file(row.getJSONObject("file"));val sha=row.optString("sha")
        val source=Uri.parse(f.uri)
        if(f.gallery){
            val current=try{media(c,source)}catch(_:Exception){null}
            val targetPath=row.optString("target_path");val targetName=row.optString("target_name")
            val verified=row.optBoolean("intent_saved")&&targetPath.isNotBlank()&&targetName.isNotBlank()&&current==(targetPath to targetName)&&verifiedDigest(c,source)==sha
            if(verified){row.put("state","moved").put("result_uri",source.toString()).put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),source.toString())}
            else row.put("state","needs_review").put("error","Organizarea a fost oprită. Rezultatul acestei mutări trebuie verificat.")
        }else{
            val original=sourceDocument(c,job,f)
            val result=row.optString("result_uri").takeIf{it.isNotBlank()}?.let(Uri::parse)
            val target=result?.let{document(c,it)}?:DocumentProbe(OrganizerMovePolicy.Presence.UNKNOWN)
            val owned=row.optBoolean("intent_saved")&&row.optBoolean("target_created")&&result!=null&&target.name in setOf(row.optString("target_name"),row.optString("staging_name"))
            val sourceMatches=original.presence==OrganizerMovePolicy.Presence.PRESENT&&verifiedDigest(c,source)==sha
            val targetMatches=target.presence==OrganizerMovePolicy.Presence.PRESENT&&result!=null&&verifiedDigest(c,result)==sha
            val state=OrganizerMovePolicy.stoppedState(original.presence,sourceMatches,target.presence,targetMatches,owned,target.name==row.optString("target_name"))
            row.put("state",state)
            if(state!="needs_review"){
                row.put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),result.toString())
                if(state=="copied_pending_removal")row.put("error","Copia este verificată. Originalul a fost păstrat după oprire.")
            }else row.put("error","Furnizorul nu confirmă rezultatul. Originalele nu au fost modificate la verificare.")
        }
        row.put("reported",false);db.saveItem(job.getString("id"),row);row
    }
    suspend fun alreadyThere(c:Context,job:JSONObject,row:JSONObject,guard:()->Unit):Boolean {
        if(row.optBoolean("intent_saved"))return false
        val f=CleanupReportCodec.file(row.getJSONObject("file"));val target=OrganizerSelection.path(row.getString("destination"))
        val same=if(f.gallery){val path=if(target.startsWith("Pictures/"))target else "Pictures/$target";media(c,Uri.parse(f.uri))?.first?.trim('/')==path.trim('/')}
        else {var node=DocumentFile.fromTreeUri(c,Uri.parse(job.getString("tree")));for(part in target.split('/'))node=node?.findFile(part);node?.findFile(f.name)?.uri?.toString()?.let{OrganizerLedger.identity(it)==OrganizerLedger.identity(f.uri)}?:false}
        return same&&digest(c,Uri.parse(f.uri),guard)==row.getString("sha")
    }
    suspend fun apply(c:Context,db:OrganizerLedger,job:JSONObject,row:JSONObject,checkpoint:suspend()->Unit={},guard:()->Unit):JSONObject=withContext(Dispatchers.IO){
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
            row.put("state","applying").put("target_name",name).put("target_path",path).put("source_sha256",sha).put("intent_saved",true);db.saveItem(job.getString("id"),row)
            checkpoint();guard();check(c.contentResolver.update(source,ContentValues().apply{put(MediaStore.MediaColumns.RELATIVE_PATH,path);put(MediaStore.MediaColumns.DISPLAY_NAME,name)},null,null)==1){"Android nu a confirmat mutarea."}
            check(media(c,source)==(path to name)&&digest(c,source,{})==sha){"Mutarea nu poate fi verificată; verifică originalul."}
            row.put("state","moved").put("result_uri",source.toString()).put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),source.toString());db.saveItem(job.getString("id"),row)
        }else {
            var name=row.optString("target_name").ifBlank{f.name}
            checkpoint();guard()
            val parent=folders(c,Uri.parse(job.getString("tree")),target,guard)
            if(!row.has("target_name")&&parent.findFile(name)!=null)name=OrganizerMovePolicy.name(f.name,row.getString("original_id"))
            if(!row.optBoolean("intent_saved")){
                check(sourceDocument(c,job,f).presence==OrganizerMovePolicy.Presence.PRESENT&&digest(c,source,guard)==sha){"Conținutul originalului s-a schimbat sau nu este accesibil."}
                check(parent.findFile(name)==null){"Destinație ocupată; păstrez ambele originale."}
                guard();row.put("state","applying").put("source_sha256",sha).put("target_name",name).put("target_parent",parent.uri.toString())
                    .put("staging_name",".forja-"+row.getString("operation_id")+".part").put("intent_saved",true);db.saveItem(job.getString("id"),row)
            }
            val original=sourceDocument(c,job,f)
            check(original.presence!=OrganizerMovePolicy.Presence.UNKNOWN){"Furnizorul originalului nu răspunde. Nu presupun că fișierul a fost șters."}
            var result=row.optString("result_uri").takeIf{it.isNotBlank()}?.let(Uri::parse)
            if(original.presence==OrganizerMovePolicy.Presence.ABSENT){
                val known=result?.let{document(c,it)}
                check(row.optBoolean("target_created")&&known?.presence==OrganizerMovePolicy.Presence.PRESENT&&known.name==row.getString("target_name")&&result!=null&&digest(c,result,guard)==sha){"Lipsește dovada unei destinații proprii verificate."}
                row.put("state","moved").put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),result.toString());db.saveItem(job.getString("id"),row);return@withContext row
            }
            check(digest(c,source,guard)==sha){"Conținutul originalului s-a schimbat. Îl păstrez."}
            var staging=row.getString("staging_name")
            if(result==null){
                // An interrupted create with no returned URI is provider ambiguity. A
                // same-name/same-hash file must never be adopted as our destination.
                check(parent.findFile(staging)==null){"Crearea anterioară nu a returnat o identitate sigură. Originalul este păstrat."}
                checkpoint();guard();val created=checkNotNull(parent.createFile(f.mime.ifBlank{"application/octet-stream"},staging)){"Nu pot crea copia; verifică spațiul și accesul."}
                result=created.uri;row.put("result_uri",result.toString()).put("target_created",true).put("target_stage","staging");db.saveItem(job.getString("id"),row)
            }
            val targetUri=checkNotNull(result)
            val current=document(c,targetUri)
            check(row.optBoolean("target_created")&&current.presence==OrganizerMovePolicy.Presence.PRESENT){"Destinația creată nu mai poate fi verificată."}
            // Providers may append a MIME extension when creating a document. The
            // returned, persisted URI establishes ownership; never recover by name.
            if(row.optString("target_stage")=="staging"&&current.name.startsWith("$staging.")){
                staging=current.name;row.put("staging_name",staging);db.saveItem(job.getString("id"),row)
            }
            check(current.name==staging||current.name==row.getString("target_name")){"Identitatea destinației s-a schimbat."}
            if(current.name==staging){
                // Only our journaled hidden staging file may be truncated on a retry.
                if(digest(c,targetUri,guard)!=sha){
                    checkpoint();guard();checkNotNull(c.contentResolver.openInputStream(source)).use{input->checkNotNull(c.contentResolver.openOutputStream(targetUri,"wt")).use{out->
                        val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();guard();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.flush()
                    }}
                    check(digest(c,targetUri,guard)==sha){"Copia nu corespunde originalului. Originalul este păstrat."}
                }
                row.put("state","copied_pending_removal").put("target_sha256",sha);db.saveItem(job.getString("id"),row)
                check(parent.findFile(row.getString("target_name"))==null){"Destinația a fost ocupată între timp. Originalul este păstrat."}
                checkpoint();guard();result=checkNotNull(DocumentsContract.renameDocument(c.contentResolver,targetUri,row.getString("target_name"))){"Copia este verificată, dar redenumirea nu a fost confirmată."}
                row.put("result_uri",result.toString()).put("target_stage","final");db.saveItem(job.getString("id"),row)
            }
            val finalUri=checkNotNull(result)
            check(document(c,finalUri).let{it.presence==OrganizerMovePolicy.Presence.PRESENT&&it.name==row.getString("target_name")}&&digest(c,finalUri,guard)==sha){"Destinația finală nu poate fi confirmată. Originalul este păstrat."}
            row.put("state","copied_pending_removal").put("target_sha256",sha);db.alias(job.getString("owner"),row.getString("original_id"),finalUri.toString());db.saveItem(job.getString("id"),row)
            guard();check(digest(c,source,guard)==sha){"Originalul s-a modificat în timpul copierii; nu îl șterg."}
            checkpoint();guard()
            check(DocumentsContract.deleteDocument(c.contentResolver,source)){"Copia este verificată; furnizorul nu a confirmat eliminarea originalului."}
            check(sourceDocument(c,job,f).presence==OrganizerMovePolicy.Presence.ABSENT&&digest(c,finalUri,{})==sha){"Eliminarea originalului nu poate fi confirmată."}
            row.put("state","moved");db.saveItem(job.getString("id"),row)
        }
        row
    }
}
