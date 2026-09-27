package com.forja.app.feature.cleanup

import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest

internal object OrganizerInventory {
    suspend fun hash(c:Context,uri:String,guard:()->Unit={}):String=withContext(Dispatchers.IO){
        val digest=MessageDigest.getInstance("SHA-256");checkNotNull(c.contentResolver.openInputStream(Uri.parse(uri))).use{input->
            val buf=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();guard();val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)}
        };digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun inScope(path:String,folder:String,recursive:Boolean):Boolean {val p=path.trim('/');val f=folder.trim('/');return p==f || recursive&&(f.isBlank()||p.startsWith("$f/"))}
    private suspend fun accept(c:Context,db:OrganizerLedger,j:JSONObject,f:CleanFile,guard:()->Unit){
        if(!inScope(f.path,j.getString("folder"),j.getBoolean("recursive"))||f.name.startsWith(".forja-"))return
        val excluded=j.optJSONArray("excluded_roots")?:org.json.JSONArray()
        if((0 until excluded.length()).any{inScope(f.path,excluded.getString(it),true)})return
        val previous=db.known(j.getString("owner"),f.uri)
        val verify=if(previous!=null&&previous.optString("stamp")!=OrganizerLedger.stamp(f)&&previous.optString("sha").isNotBlank())hash(c,f.uri,guard)else null
        val row=db.observe(j.getString("owner"),f,verify);db.inventory(j.getString("id"),row)
    }
    suspend fun scan(c:Context,db:OrganizerLedger,j:JSONObject,guard:()->Unit)=withContext(Dispatchers.IO){
        if(j.optBoolean("inventory_done"))return@withContext
        j.put("excluded_roots",org.json.JSONArray(db.destinationRoots(j.getString("owner"),j.getString("source"),j.getString("tree"))))
        if(j.getString("source")=="photos")gallery(c,db,j,guard)else documents(c,db,j,guard)
        j.put("inventory_done",true);db.save(j)
    }
    private suspend fun gallery(c:Context,db:OrganizerLedger,j:JSONObject,guard:()->Unit){
        val volumes=if(Build.VERSION.SDK_INT>=29)MediaStore.getExternalVolumeNames(c).sorted()else listOf("external")
        for(volume in volumes){
            val collection=if(Build.VERSION.SDK_INT>=29)MediaStore.Images.Media.getContentUri(volume)else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val marker="cursor:"+collection;var last=j.optLong(marker,0)
            while(true){guard();currentCoroutineContext().ensureActive()
                val columns=mutableListOf("_id","_display_name","mime_type","_size","date_modified","width","height","datetaken")
                if(Build.VERSION.SDK_INT>=29)columns+="relative_path"
                if(Build.VERSION.SDK_INT>=30)columns+="is_favorite"
                val args=Bundle().apply{putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"_id>?"+(if(Build.VERSION.SDK_INT>=30)" AND is_trashed=0 AND is_pending=0"else ""));putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arrayOf(last.toString()));putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"_id ASC");putInt(ContentResolver.QUERY_ARG_LIMIT,500)}
                var read=0
                checkNotNull(c.contentResolver.query(collection,columns.toTypedArray(),args,null)){"Galeria nu a returnat inventarul."}.use{q->
                    fun str(k:String)=q.getColumnIndex(k).let{if(it>=0&&!q.isNull(it))q.getString(it).orEmpty()else ""}
                    fun number(k:String)=q.getColumnIndex(k).let{if(it>=0&&!q.isNull(it))q.getLong(it)else 0L}
                    while(read<500&&q.moveToNext()){guard();val id=number("_id");check(id>last){"Furnizorul galeriei nu respectă continuarea inventarului."}
                        val f=CleanFile(ContentUris.withAppendedId(collection,id).toString(),str("_display_name"),str("mime_type"),number("_size"),number("date_modified")*1000,path=str("relative_path"),taken=number("datetaken"),width=number("width").toInt(),height=number("height").toInt(),favorite=number("is_favorite")==1L,gallery=true)
                        accept(c,db,j,f,guard);last=id;read++
                    }
                }
                j.put(marker,last);db.save(j)
                if(read<500)break
            }
        }
    }
    private suspend fun documents(c:Context,db:OrganizerLedger,j:JSONObject,guard:()->Unit){
        val tree=Uri.parse(j.getString("tree"));val root=checkNotNull(DocumentFile.fromTreeUri(c,tree));check(root.canRead()){"Alege din nou dosarul autorizat."}
        db.directory(j.getString("id"),root.uri.toString(),"",0)
        while(true){guard();currentCoroutineContext().ensureActive();val next=db.nextDirectory(j.getString("id"))?:break
            val(parent,path,depth)=next;val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(Uri.parse(parent)))
            checkNotNull(c.contentResolver.query(children,arrayOf("document_id","_display_name","mime_type","_size","last_modified","flags"),null,null,null)){"Un subdosar nu poate fi citit."}.use{q->
                while(q.moveToNext()){guard();currentCoroutineContext().ensureActive();val name=q.getString(1).orEmpty();val mime=q.getString(2).orEmpty();val uri=DocumentsContract.buildDocumentUriUsingTree(tree,q.getString(0))
                    if(mime==DocumentsContract.Document.MIME_TYPE_DIR){
                        if(j.getBoolean("recursive")||(j.getString("folder")=="$path$name"||j.getString("folder").startsWith("$path$name/"))){check(depth<128){"Dosar cu prea multe niveluri; selectează o sursă mai precisă."};db.directory(j.getString("id"),uri.toString(),"$path$name/",depth+1)}
                    }else if(q.getInt(5) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT==0){
                        accept(c,db,j,CleanFile(uri.toString(),name,mime,if(q.isNull(3))-1 else q.getLong(3),if(q.isNull(4))0 else q.getLong(4),parent,path,flags=q.getInt(5)),guard)
                    }
                }
            }
            db.directoryDone(j.getString("id"),parent)
        }
    }
}
