package com.forja.app.feature.cleanup

import org.json.JSONObject

internal data class OrganizerSelection(val photos:Boolean,val files:Boolean,val photoCount:Int,val fileCount:Int,val photoFolder:String,val fileFolder:String,val from:Long?,val to:Long?,val recursive:Boolean,val mode:String="local") {
    companion object {
        fun path(raw:String):String {require(raw.length<=200&&!raw.startsWith('/')&&raw.none{it<' '||it=='\u007f'||it in "\\:*?\"<>|"});val s=raw.trimEnd('/');val parts=s.split('/');require(parts.size<=8&&parts.none{it=="."||it==".."||it!=it.trim()}&&(s.isEmpty()||parts.none{it.isEmpty()}));return s}
        fun parse(v:JSONObject):OrganizerSelection {
            val p=v.getBoolean("photos");val f=v.getBoolean("files");require(p||f)
            val pc=v.getInt("photo_count");val fc=v.getInt("file_count");require(pc in 0..15000&&fc in 0..15000)
            val from=if(v.isNull("from"))null else v.getLong("from");val to=if(v.isNull("to"))null else v.getLong("to");require((from==null||from>=0)&&(to==null||to>=0)&&(from==null||to==null||from<to))
            val mode=v.optString("mode","local");require(mode in setOf("manual","local"))
            return OrganizerSelection(p,f,pc,fc,path(v.getString("photo_folder")),path(v.getString("file_folder")),from,to,v.getBoolean("recursive"),mode)
        }
    }
    fun matches(f:CleanFile,source:String):Boolean {
        val folder=if(source=="photos")photoFolder else fileFolder;val current=f.path.trimEnd('/');val inside=current==folder||recursive&&(folder.isEmpty()||current.startsWith("$folder/"));val at=if(source=="photos")f.date else f.modified
        return inside&&(from==null||at>0&&at>=from)&&(to==null||at>0&&at<to)
    }
    fun select(inventory:List<CleanFile>,source:String):List<CleanFile> {
        val count=if(source=="photos")photoCount else fileCount
        return inventory.filter{matches(it,source)}.sortedWith(compareByDescending<CleanFile>{if(source=="photos")it.date else it.modified}.thenBy{it.uri}).let{if(count==0)it else it.take(count)}
    }
}
internal class OrganizerInventoryFilter(val selection:OrganizerSelection,val source:String):kotlin.coroutines.AbstractCoroutineContextElement(Key){companion object Key:kotlin.coroutines.CoroutineContext.Key<OrganizerInventoryFilter>}
