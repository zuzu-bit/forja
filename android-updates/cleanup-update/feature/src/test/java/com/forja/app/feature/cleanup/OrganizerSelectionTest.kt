package com.forja.app.feature.cleanup
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class OrganizerSelectionTest {
    private fun selection(count:Int=50,folder:String="",from:Long?=null,to:Long?=null,recursive:Boolean=true)=OrganizerSelection(true,true,count,2,folder,"Educație",from,to,recursive)
    private fun photo(n:Int,path:String="DCIM/Camera/",modified:Long=n.toLong(),taken:Long=0)=CleanFile("content://photos/$n","$n.jpg","image/jpeg",100,modified,path=path,taken=taken,gallery=true)
    @Test fun filtersBeforeRecentLimit(){val files=(1..100).map{photo(it,if(it<=60)"Pictures/Școală/"else "DCIM/Camera/")};val selected=selection(50,"Pictures/Școală").select(files,"photos");assertEquals(50,selected.size);assertEquals("60.jpg",selected.first().name);assertEquals("11.jpg",selected.last().name)}
    @Test fun folderBoundaryIsExact(){val files=listOf(photo(1,"Educație/"),photo(2,"Educație/Sub/"),photo(3,"Educație2/"));assertEquals(listOf("2.jpg","1.jpg"),selection(50,"Educație").select(files,"photos").map{it.name});assertEquals(listOf("1.jpg"),selection(50,"Educație",recursive=false).select(files,"photos").map{it.name})}
    @Test fun timeRangeIsStartInclusiveEndExclusiveAndOmitsUnknownDates(){val files=listOf(photo(1,modified=0),photo(2,modified=20),photo(3,modified=30),photo(4,modified=40));assertEquals(listOf("3.jpg","2.jpg"),selection(from=20,to=40).select(files,"photos").map{it.name})}
    @Test fun photoDateUsesCaptureThenModification(){val files=listOf(photo(1,modified=100,taken=10),photo(2,modified=20));assertEquals(listOf("2.jpg","1.jpg"),selection().select(files,"photos").map{it.name})}
    @Test fun documentCountAndFolderAreIndependent(){val files=(1..10).map{photo(it,"Educație/")};assertEquals(2,selection(count=50).select(files,"files").size);assertEquals("10.jpg",selection().select(files,"files").first().name)}
    @Test fun allScopeRetainsEveryMatchingItemUpToDocumentedInventoryBound(){val files=(1..16000).map{photo(it)};assertEquals(15000,selection(0).select(files,"photos").size)}
    @Test fun malformedPathsCannotEscapeChosenFolder(){for(path in listOf("../private","/storage","a//b","a/../b","a\\b","a\u0000b"," a","a/ b","a/b/c/d/e/f/g/h/i")){assertThrows(IllegalArgumentException::class.java){OrganizerSelection.path(path)}};assertEquals("Educație/Fișe",OrganizerSelection.path("Educație/Fișe/"))}
    @Test fun malformedRemoteSelectionsAreRejected(){val v=JSONObject().put("photos",true).put("files",false).put("photo_count",50).put("file_count",100).put("photo_folder","").put("file_folder","").put("from",JSONObject.NULL).put("to",JSONObject.NULL).put("recursive",true);assertEquals(50,OrganizerSelection.parse(v).photoCount);v.put("photo_count",-1);assertThrows(IllegalArgumentException::class.java){OrganizerSelection.parse(v)}}
}
