package com.forja.app.feature.cleanup

/** A scan captures one immutable choice before reading any file content. */
internal object CleanupScope {
    @Volatile var limit:Int=50
    @Volatile var includeVideos:Boolean=false
    fun latest(files:List<CleanFile>,count:Int):List<CleanFile> {
        require(count in setOf(0,50,100,200))
        val ordered=files.distinctBy { it.uri }.sortedWith(compareByDescending<CleanFile> { it.modified.coerceAtLeast(0) }.thenBy { it.uri })
        return if(count==0)ordered else ordered.take(count)
    }
}
