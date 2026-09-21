package com.forja.app.feature.cleanup
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/** Inventory only: no OCR, content classification, hashing or AI. */
internal object ManualInventory {
 fun report(files:List<CleanFile>,warnings:List<String> = emptyList())=CleanReport(files,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),warnings,0,0,files.associate{it.uri to ContentFinding(null,"Organizare manuală · alege destinația", "",false,false)})
 suspend fun scan(c:Context,source:String,tree:Uri?,hook:suspend(List<CleanFile>)->Unit):CleanReport=withContext(Dispatchers.IO){
  val catalog=CleanupCatalog(c);val (files,warnings)=if(source=="photos")catalog.gallery(true,false)else catalog.folder(checkNotNull(tree),true)
  hook(files);report(files,warnings)
 }
 fun placements(files:List<CleanFile>)=files.map{Placement(it,it.path,"Alege un dosar; originalul rămâne la loc până confirmi")}
}
