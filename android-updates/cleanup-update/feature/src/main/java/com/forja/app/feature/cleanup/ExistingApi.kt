package com.forja.app.feature.cleanup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import android.content.Context
import android.content.IntentSender
import android.net.Uri

// Compile-only ABI declarations for the checksum-pinned 3.7-online.13 APK.
// Packaging explicitly excludes these classes; the delivered originals are retained.
internal data class CleanFile(
    val uri:String,val name:String,val mime:String,val bytes:Long,val modified:Long,
    val parent:String="",val path:String="",val taken:Long=0,val width:Int=0,val height:Int=0,
    val favorite:Boolean=false,val gallery:Boolean=false,val flags:Int=0,
) { val photo get()=mime.startsWith("image/");val video get()=mime.startsWith("video/");val date get()=taken.takeIf{it>0}?:modified }
internal data class ContentFinding(val subject:String?,val reason:String,val excerpt:String="",val partial:Boolean=false,val strong:Boolean=false)
internal class CleanReport(val files:List<CleanFile>,val duplicates:List<DuplicateGroup>,val similar:List<SimilarGroup>,val placements:List<Placement>,val screenshots:List<CleanFile>,val large:List<CleanFile>,val warnings:List<String>,val hashed:Int,val visualChecked:Int,val content:Map<String,ContentFinding>) {
    val duplicateCopies get()=duplicates.flatMap{it.copies}
}
internal data class DuplicateGroup(val keeper:CleanFile,val copies:List<CleanFile>,val sha256:String)
internal data class SimilarGroup(val keeper:CleanFile,val others:List<CleanFile>)
internal data class Placement(val file:CleanFile,val destination:String,val reason:String)
internal class CleanupOutcome(moved:Int,copied:Int,skipped:Int,errors:List<String>) {
    val moved:Int get()=error("ABI stub")
    val copied:Int get()=error("ABI stub")
    val skipped:Int get()=error("ABI stub")
    val errors:List<String> get()=error("ABI stub")
}
internal class CleanupScan(c:Context) {
    suspend fun scan(source:String,folder:Uri?,images:Boolean,videos:Boolean,recursive:Boolean,progress:(String)->Unit,partialResult:(CleanReport)->Unit):CleanReport=error("ABI stub")
}
internal class CleanupOperations(c:Context) {
    fun begin():Unit=error("ABI stub")
    fun clearJournal():Unit=error("ABI stub")
    fun journal():List<JSONObject> =error("ABI stub")
    fun writeRequest(uris:List<Uri>):IntentSender?=error("ABI stub")
    fun trashRequest(files:List<CleanFile>,trash:Boolean):IntentSender?=error("ABI stub")
    fun rememberTrash(files:List<CleanFile>):Unit=error("ABI stub")
    suspend fun organizeDocuments(rootUri:Uri,plan:List<Placement>,quarantine:Boolean,progress:(String)->Unit):CleanupOutcome=error("ABI stub")
    suspend fun organizeGallery(plan:List<Placement>,progress:(String)->Unit):CleanupOutcome=error("ABI stub")
    suspend fun verifyDuplicateSelection(report:CleanReport,selected:Set<String>):List<CleanFile> =error("ABI stub")
    suspend fun undoDocuments(progress:(String)->Unit):CleanupOutcome=error("ABI stub")
    suspend fun undoGallery():CleanupOutcome=error("ABI stub")
}
internal class CleanupViewModel(app:Application):AndroidViewModel(app) {
    val busy:Boolean get()=error("ABI stub")
    val report:CleanReport? get()=error("ABI stub")
    val undoRows:List<JSONObject> get()=error("ABI stub")
    fun reset():Unit=error("ABI stub")
}
internal data class VisualPrint(val hash:Long,val red:Double,val green:Double,val blue:Double,val contrast:Double)
