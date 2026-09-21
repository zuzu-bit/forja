package com.forja.app.feature.cleanup

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal object CleanupAnalysisLock {val mutex=Mutex()}
internal class CleanupScanInput(val files:List<CleanFile>):AbstractCoroutineContextElement(Key){companion object Key:CoroutineContext.Key<CleanupScanInput>}
internal object CleanupReportCodec {
    fun file(f:CleanFile)=JSONObject().put("uri",f.uri).put("name",f.name).put("mime",f.mime).put("bytes",f.bytes).put("modified",f.modified).put("parent",f.parent).put("path",f.path).put("taken",f.taken).put("width",f.width).put("height",f.height).put("favorite",f.favorite).put("gallery",f.gallery).put("flags",f.flags)
    fun file(v:JSONObject)=CleanFile(v.getString("uri"),v.getString("name"),v.getString("mime"),v.getLong("bytes"),v.getLong("modified"),v.optString("parent"),v.optString("path"),v.optLong("taken"),v.optInt("width"),v.optInt("height"),v.optBoolean("favorite"),v.optBoolean("gallery"),v.optInt("flags"))
    fun files(v:JSONArray)=List(v.length()){file(v.getJSONObject(it))}
    fun report(r:CleanReport):JSONObject {
        val content=JSONObject();r.content.forEach{(uri,f)->content.put(uri,JSONObject().put("subject",f.subject?:JSONObject.NULL).put("reason",f.reason).put("excerpt",f.excerpt.take(2000)).put("partial",f.partial).put("strong",f.strong))}
        val groups=r.duplicates.map{g->JSONObject().put("keeper",g.keeper.uri).put("copies",JSONArray(g.copies.map{it.uri})).put("sha",g.sha256)}
        return JSONObject().put("files",JSONArray(r.files.map(::file))).put("content",content).put("duplicates",JSONArray(groups)).put("warnings",JSONArray(r.warnings))
    }
    fun report(v:JSONObject):CleanReport {
        val files=files(v.getJSONArray("files"));val byUri=files.associateBy{it.uri};val raw=v.getJSONObject("content")
        val content=raw.keys().asSequence().associateWith{uri->val f=raw.getJSONObject(uri);ContentFinding(if(f.isNull("subject"))null else f.getString("subject"),f.getString("reason"),f.optString("excerpt"),f.optBoolean("partial"),f.optBoolean("strong"))}
        val groups=v.getJSONArray("duplicates");val duplicates=List(groups.length()){val g=groups.getJSONObject(it);val copies=g.getJSONArray("copies");DuplicateGroup(byUri.getValue(g.getString("keeper")),List(copies.length()){i->byUri.getValue(copies.getString(i))},g.getString("sha"))}
        val warnings=v.getJSONArray("warnings")
        return CleanReport(files,duplicates,emptyList(),PhoneCleanupPaths.proposals(files,content),emptyList(),emptyList(),List(warnings.length()){warnings.getString(it)},0,0,content)
    }
}
internal class CleanupAutoStore(c:Context){
    private companion object {val lock=Any()}
    private val root=File(c.filesDir,"cleanup_auto_v17").apply{mkdirs()}
    private fun file(id:String):AtomicFile{require(id.matches(Regex("[0-9a-f-]{36}")));return AtomicFile(File(root,"$id.json"))}
    fun read(id:String):JSONObject?=synchronized(lock){try{file(id).openRead().use{JSONObject(it.readBytes().toString(Charsets.UTF_8))}}catch(_:java.io.FileNotFoundException){null}}
    fun write(id:String,value:JSONObject)=synchronized(lock){val f=file(id);val out=f.startWrite();try{out.write(value.toString().toByteArray());f.finishWrite(out);CleanupAuto.changed.value++}catch(e:Throwable){f.failWrite(out);throw e}}
    fun recent(owner:String):List<JSONObject> =root.listFiles().orEmpty().filter{it.name.endsWith(".json")}.mapNotNull{f->runCatching{read(f.name.removeSuffix(".json"))}.getOrNull()?.takeIf{it.optString("owner")==owner&&it.optLong("created")>System.currentTimeMillis()-86400000L}}.sortedByDescending{it.optLong("created")}.take(8)
    fun prune(){root.listFiles().orEmpty().filter{it.lastModified()<System.currentTimeMillis()-2*86400000L}.forEach{it.delete()}}
}
