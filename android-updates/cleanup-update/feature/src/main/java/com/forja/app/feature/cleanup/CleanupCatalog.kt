package com.forja.app.feature.cleanup

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

internal class CleanupCatalog(private val c:Context,private val scopeLimit:Int?) {
    constructor(c:Context):this(c,null)
    suspend fun gallery(images:Boolean,videos:Boolean):Pair<List<CleanFile>,List<String>> {
        currentCoroutineContext()[CleanupScanInput]?.let{return it.files to listOf("Selecția fixată pentru analiza programată.")}
        val filter=currentCoroutineContext()[OrganizerInventoryFilter]
        val limit=scopeLimit?:CleanupScope.limit;val withVideo=CleanupScope.includeVideos&&videos
        val out=mutableListOf<Pair<CleanFile,Long>>();val warnings=mutableListOf<String>()
        for(collection in listOfNotNull(if(images)MediaStore.Images.Media.EXTERNAL_CONTENT_URI else null,if(withVideo)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else null)) {
            currentCoroutineContext().ensureActive()
            val cols=mutableListOf("_id","_display_name","mime_type","_size","date_modified","date_added","width","height","datetaken")
            if(Build.VERSION.SDK_INT>=29)cols+="relative_path"
            if(Build.VERSION.SDK_INT>=30)cols+="is_favorite"
            try { c.contentResolver.query(collection,cols.toTypedArray(),if(Build.VERSION.SDK_INT>=30)"is_trashed=0 AND is_pending=0"else null,null,"date_added DESC, _id DESC")?.use { cursor ->
                fun str(k:String)=cursor.getColumnIndex(k).let { if(it>=0&&!cursor.isNull(it))cursor.getString(it).orEmpty()else "" }
                fun number(k:String)=cursor.getColumnIndex(k).let { if(it>=0&&!cursor.isNull(it))cursor.getLong(it)else 0L }
                var read=0
                while(cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val f=CleanFile(ContentUris.withAppendedId(collection,number("_id")).toString(),str("_display_name"),str("mime_type"),number("_size"),number("date_modified")*1000,path=str("relative_path"),taken=number("datetaken"),width=number("width").toInt(),height=number("height").toInt(),favorite=number("is_favorite")==1L,gallery=true)
                    if(filter!=null&&!filter.selection.matches(f,filter.source))continue
                    if(read++>=if(limit==0)15000 else limit)break
                    out+=f to number("date_added")
                }
                if(limit==0&&read>15000)warnings+="Inventarul este limitat la 15.000 de elemente pe tip media."
            }?:run{warnings+="Galeria nu a returnat un inventar."}
            }catch(e:java.util.concurrent.CancellationException){throw e}catch(_:Exception){warnings+="O parte din galerie nu este accesibilă. Verifică permisiunile."}
        }
        val ordered=out.distinctBy { it.first.uri }.sortedWith(compareByDescending<Pair<CleanFile,Long>> { it.second }.thenByDescending { it.first.uri.substringAfterLast('/').toLongOrNull()?:0 })
        val selected=(if(limit==0)ordered else ordered.take(limit)).map { it.first }
        if(limit>0)warnings+="Analizez ${selected.size} dintre cele mai recent adăugate elemente accesibile. Duplicatele sunt căutate doar în această selecție."
        if(!withVideo)warnings+="Selecția galeriei conține doar fotografii."
        currentCoroutineContext()[CleanupInventory]?.accept?.invoke(selected)
        return selected to warnings.distinct()
    }
    suspend fun folder(tree:Uri,recursive:Boolean):Pair<List<CleanFile>,List<String>> {
        currentCoroutineContext()[CleanupScanInput]?.let{return it.files to listOf("Selecția fixată pentru analiza programată.")}
        val filter=currentCoroutineContext()[OrganizerInventoryFilter]
        val limit=scopeLimit?:CleanupScope.limit;val root=requireNotNull(DocumentFile.fromTreeUri(c,tree));check(root.canRead()){"Dosarul nu mai este accesibil. Alege-l din nou."}
        val out=mutableListOf<CleanFile>();val warnings=mutableListOf<String>();val visited=mutableSetOf<String>()
        val queue=ArrayDeque<Triple<Uri,String,Int>>();queue.add(Triple(root.uri,"",0))
        val ignored=setOf(".git","node_modules","android",".gradle",".idea","forja - de verificat")
        while(queue.isNotEmpty()&&out.size<15000) {
            currentCoroutineContext().ensureActive();val(parent,path,depth)=queue.removeFirst();if(!visited.add(parent.toString()))continue
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
            try{c.contentResolver.query(children,arrayOf("document_id","_display_name","mime_type","_size","last_modified","flags"),null,null,null)?.use { cursor ->
                while(cursor.moveToNext()&&out.size<15000){currentCoroutineContext().ensureActive()
                    val uri=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));val name=cursor.getString(1).orEmpty();val mime=cursor.getString(2).orEmpty()
                    if(mime==DocumentsContract.Document.MIME_TYPE_DIR){if(recursive&&name.lowercase() !in ignored){if(depth<8)queue.add(Triple(uri,"$path$name/",depth+1))else warnings+="Subdosare mai adânci de 8 niveluri nu au fost parcurse."}}
                    else if(cursor.getInt(5) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT==0){val f=CleanFile(uri.toString(),name,mime,if(cursor.isNull(3))-1 else cursor.getLong(3),if(cursor.isNull(4))0 else cursor.getLong(4),parent.toString(),path,flags=cursor.getInt(5));if(filter==null||filter.selection.matches(f,filter.source))out+=f}
                    else warnings+="Documentele virtuale au fost omise."
                }
            }?:run{warnings+="Un subdosar nu a putut fi citit."}
            }catch(e:java.util.concurrent.CancellationException){throw e}catch(_:Exception){warnings+="Un subdosar nu a putut fi citit."}
        }
        if(out.size>=15000)warnings+="Au fost parcurse metadatele a 15.000 de fișiere; restrânge dosarul pentru a verifica restul."
        val selected=CleanupScope.latest(out,limit)
        if(limit>0)warnings+="Analizez conținutul celor ${selected.size} fișiere cu data modificării cea mai recentă, din ${out.size} fișiere inventariate. Duplicatele sunt căutate doar în selecție."
        if(selected.any { it.modified<=0 })warnings+="Unele fișiere nu au o dată disponibilă; sunt așezate după cele datate."
        currentCoroutineContext()[CleanupInventory]?.accept?.invoke(selected)
        return selected to warnings.distinct()
    }
    fun bitmap(uri:Uri,max:Int=1400):Bitmap {
        if(Build.VERSION.SDK_INT>=28)return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver,uri)){decoder,info,_ ->decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;val scale=max.toDouble()/maxOf(info.size.width,info.size.height);if(scale<1)decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))}
        val o=BitmapFactory.Options().apply { inJustDecodeBounds=true };c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,o) };o.inJustDecodeBounds=false;o.inSampleSize=1
        while(maxOf(o.outWidth,o.outHeight)/o.inSampleSize>max)o.inSampleSize*=2
        return requireNotNull(c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,o) })
    }
    fun visual(bitmap:Bitmap):VisualPrint {
        val tiny=Bitmap.createScaledBitmap(bitmap,9,8,true);val pixels=IntArray(72);tiny.getPixels(pixels,0,9,0,0,9,8);if(tiny!==bitmap)tiny.recycle()
        val gray=pixels.map { ((it shr 16 and 255)*0.299+(it shr 8 and 255)*0.587+(it and 255)*0.114) };var hash=0L
        for(y in 0..7)for(x in 0..7)if(gray[y*9+x]>gray[y*9+x+1])hash=hash or(1L shl(y*8+x))
        val mean=gray.average();return VisualPrint(hash,pixels.map{(it shr 16 and 255)/255.0}.average(),pixels.map{(it shr 8 and 255)/255.0}.average(),pixels.map{(it and 255)/255.0}.average(),sqrt(gray.sumOf{(it-mean)*(it-mean)}/72))
    }
}
