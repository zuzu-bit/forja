package com.forja.app.feature.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

internal class CleanupContent(private val c:Context):Closeable {
    private val text=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val labels=ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.70f).build())
    private var semantic:LocalSemanticModel?=null
    private suspend fun classify(value:String,partial:Boolean):ContentFinding {currentCoroutineContext().ensureActive();val context=currentCoroutineContext();if(semantic==null)semantic=LocalSemanticModel(c);return semantic!!.classify(value,partial){context.ensureActive()}}
    private suspend fun ocr(bitmap:Bitmap):String=withContext(NonCancellable){text.process(InputImage.fromBitmap(bitmap,0)).await().text}
    suspend fun image(f:CleanFile,bitmap:Bitmap):ContentFinding {
        val recognized=ocr(bitmap);currentCoroutineContext().ensureActive()
        if(recognized.length>=40){val found=classify(recognized,false);if(found.subject!=null)return found.copy(reason="Text citit din fotografie. "+found.reason)}
        val detected=withContext(NonCancellable){labels.process(InputImage.fromBitmap(bitmap,0)).await()};currentCoroutineContext().ensureActive()
        val subjects=mapOf(
            "Fotografii cu oameni" to setOf("Person","Crowd","Selfie"),
            "Mâncare" to setOf("Food","Meal","Cuisine","Fruit","Vegetable","Cake","Bread","Cookie","Pizza","Sushi","Coffee","Bento","Juice"),
            "Animale" to setOf("Cat","Dog","Bird","Pet","Animal","Insect","Horse","Cattle","Penguin"),
            "Natură" to setOf("Flower","Plant","Flora","Forest","Mountain","Lake","Waterfall","Beach","Garden","Sky","Sunset","River","Jungle","Glacier","Desert","Volcano","Cliff"),
            "Sport" to setOf("Sports","Gymnastics","Running","Cycling","Soccer","Swimming","Bicycle","Skiing","Skateboard","Surfing","Badminton"),
            "Clădiri și locuri" to setOf("Building","Bridge","Skyscraper","Tower","Palace","Monument","Ruins"),
            "Obiecte și produse" to setOf("Shoe","Jewellery","Product","Computer","Mobile phone","Dress","Chair","Tableware"),
            "Documente fotografiate" to setOf("Receipt","Paper","Passport"),
            "Capturi de ecran" to setOf("Screenshot")
        )
        val evidence=subjects.mapValues { (_,words)->detected.filter{it.text in words}.map{it.confidence}.sortedDescending() }
        val scores=evidence.map{(name,values)->name to VisualDecision.score(values)}.sortedByDescending{it.second}
        val top=scores.first();val clear=top.second>=0.82f&&top.second-scores[1].second>=0.08f
        if(clear){
            val subject=VisualDecision.refine(top.first,detected.associate{it.text to it.confidence})
            val count=evidence.getValue(top.first).size
            return ContentFinding(subject,"${top.first}: $count indicii vizuale concordante."+(if(subject!=top.first)" Subcategoria este susținută de un element vizual distinct."else ""),recognized.take(140),strong=top.second>=0.93f&&top.second-scores[1].second>=0.14f)
        }
        val alternatives=scores.filter{it.second>=0.70f}.take(2).joinToString{it.first}
        return ContentFinding(null,if(alternatives.isNotBlank())"Teme vizuale apropiate: $alternatives. Verifică fotografia și alege dosarul."else "Nu există suficiente indicii vizuale sau textuale pentru o temă sigură.",recognized.take(140))
    }
    private suspend fun temporary(f:CleanFile):File {
        require(f.bytes<=30*1024*1024L){"Document mai mare de 30 MB; necesită verificare separată."};val file=File.createTempFile("forja-read-",".tmp",c.cacheDir)
        try{c.contentResolver.openInputStream(Uri.parse(f.uri)).use{input->requireNotNull(input);file.outputStream().use{output->val buffer=ByteArray(65536);var size=0L;while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=30*1024*1024L){"Document prea mare pentru analiza locală."};output.write(buffer,0,n)}}};return file}catch(e:Exception){file.delete();throw e}
    }
    suspend fun document(f:CleanFile):ContentFinding {
        val ext=f.name.substringAfterLast('.',"").lowercase()
        if(ext !in setOf("pdf","docx","pptx","xlsx","odt","ods","odp","txt","csv","md","html","htm","xml","json")&&!f.mime.startsWith("text/"))return ContentFinding(null,"Formatul nu permite analiza locală a conținutului în această versiune.")
        val file=temporary(f)
        try{val(value,partial)=when(ext){"pdf"->pdf(file);"docx","pptx","xlsx","odt","ods","odp"->office(file);else->{val all=file.inputStream().use{input->val bytes=ByteArray(minOf(file.length(),240001).toInt());var n=0;while(n<bytes.size){val got=input.read(bytes,n,bytes.size-n);if(got<0)break;n+=got};String(bytes,0,n,Charsets.UTF_8)};all.take(120000) to(all.length>120000||file.length()>240000)}}
            if(value.length<40)return ContentFinding(null,"Nu am găsit suficient text lizibil. Documentul poate fi protejat sau scanat neclar.",partial=partial)
            return classify(value,partial)
        }finally{file.delete()}
    }
    private suspend fun office(file:File):Pair<String,Boolean> {
        val content=StringBuilder();var bytes=0;var count=0;var partial=false
        ZipFile(file).use{zip->val entries=zip.entries().asSequence().filter{e->e.name=="word/document.xml"||e.name=="content.xml"||e.name=="xl/sharedStrings.xml"||Regex("ppt/slides/slide\\d+\\.xml").matches(e.name)||Regex("xl/worksheets/sheet\\d+\\.xml").matches(e.name)}.toList().sortedBy{it.name}
            for(e in entries){currentCoroutineContext().ensureActive();if(++count>100||content.length>120000){partial=true;break}
                val data=zip.getInputStream(e).use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=12*1024*1024){"Document prea complex pentru analiza locală."};out.write(buffer,0,n)};out.toByteArray()}
                val xml=String(data,Charsets.UTF_8);require(!xml.contains("<!DOCTYPE",true)&&!xml.contains("<!ENTITY",true)){"Document XML cu declarații externe; analiza a fost oprită."}
                val parser=Xml.newPullParser();parser.setInput(xml.reader());var event=parser.eventType;val tags=ArrayDeque<String>()
                while(event!=XmlPullParser.END_DOCUMENT){currentCoroutineContext().ensureActive()
                    if(event==XmlPullParser.START_TAG)tags.addLast(parser.name.substringAfter(':'))
                    if(event==XmlPullParser.END_TAG&&tags.isNotEmpty())tags.removeLast()
                    if(event==XmlPullParser.TEXT&&tags.lastOrNull() in setOf("t","p","span","h","a")){content.append(parser.text).append(' ');if(content.length>120000){partial=true;break}}
                    event=parser.next()
                }
            }
        };return content.toString().take(120000) to partial
    }
    private suspend fun pdf(file:File):Pair<String,Boolean> {
        PDFBoxResourceLoader.init(c);val content=StringBuilder();var partial=false
        PDDocument.load(file).use{document->require(!document.isEncrypted||document.currentAccessPermission.canExtractContent()){ "PDF protejat; textul nu poate fi citit." }
            val pages=document.numberOfPages;val chosen=if(pages<=40)(0 until pages).toList()else(0..39).map{it*(pages-1)/39}.distinct();partial=chosen.size<pages;var renderer:PdfRenderer?=null
            try{for(i in chosen){currentCoroutineContext().ensureActive();if(content.length>=120000){partial=true;break}
                val stripper=PDFTextStripper().apply{startPage=i+1;endPage=i+1};var pageText=stripper.getText(document)
                if(pageText.trim().length<40){if(renderer==null)renderer=PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY));renderer!!.openPage(i).use{page->val scale=1600.0/maxOf(page.width,page.height);val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    try{bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);pageText=ocr(bitmap)}finally{bitmap.recycle()}}}
                content.append(pageText).append('\n')
            }}finally{renderer?.close()}
        };return content.toString().take(120000) to partial
    }
    override fun close(){text.close();labels.close();semantic?.close()}
}
internal object VisualDecision {
    fun refine(category:String,labels:Map<String,Float>):String {
        val mapping=when(category){
            "Animale"->mapOf("Dog" to "Câini","Cat" to "Pisici","Bird" to "Păsări")
            "Natură"->mapOf("Flower" to "Flori","Mountain" to "Munte","Beach" to "Plajă","Forest" to "Pădure")
            "Sport"->mapOf("Cycling" to "Ciclism","Swimming" to "Înot","Soccer" to "Fotbal","Skiing" to "Schi")
            else->emptyMap()
        }
        val scores=mapping.map{(label,folder)->folder to (labels[label]?:0f)}.sortedByDescending{it.second}
        val best=scores.firstOrNull()?:return category
        return if(best.second>=0.90f&&best.second-(scores.getOrNull(1)?.second?:0f)>=0.12f)"$category/${best.first}"else category
    }
    fun score(values:List<Float>):Float {
        val sorted=values.distinct().sortedDescending();if(sorted.isEmpty())return 0f
        // Several compatible detections support a category; a single weak detection cannot be promoted.
        return (sorted[0]+sorted.drop(1).take(2).sum()*0.035f).coerceAtMost(1f)
    }
}
