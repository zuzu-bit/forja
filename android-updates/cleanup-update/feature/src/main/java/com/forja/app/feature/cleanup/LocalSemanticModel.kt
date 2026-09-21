package com.forja.app.feature.cleanup

import android.content.Context
import ai.onnxruntime.*
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

internal class WordPiece(private val vocab:Map<String,Int>) {
    private fun cjk(cp:Int)=cp in 0x4e00..0x9fff||cp in 0x3400..0x4dbf||cp in 0x20000..0x2fa1f
    fun tokens(text:String):List<Long> {
        val basic=StringBuilder()
        text.codePoints().forEach { cp ->val type=Character.getType(cp)
            when {
                Character.isWhitespace(cp)||Character.isSpaceChar(cp) -> basic.append(' ')
                Character.isISOControl(cp)||type==Character.FORMAT.toInt()||cp==0xfffd -> Unit
                cjk(cp)||type in setOf(20,21,22,23,24,29,30)||cp in 33..47||cp in 58..64||cp in 91..96||cp in 123..126 -> basic.append(' ').appendCodePoint(cp).append(' ')
                else -> basic.appendCodePoint(cp)
            }
        }
        val out=mutableListOf<Long>()
        for(word in basic.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }){
            if(word.codePointCount(0,word.length)>100){out+=100L;continue}
            var start=0;val ids=mutableListOf<Long>();var failed=false
            while(start<word.length){var end=word.length;var token:Int?=null
                while(end>start){token=vocab[(if(start>0)"##"else "")+word.substring(start,end)];if(token!=null)break;end--}
                if(token==null){failed=true;break};ids+=token.toLong();start=end
            }
            out+=if(failed)listOf(100L)else ids
        };return out
    }
}
internal class LocalSemanticModel(c:Context):Closeable {
    private val env=OrtEnvironment.getEnvironment()
    private val tokenizer=WordPiece(c.assets.open("cleanup_ai/vocab.txt").bufferedReader().use { it.readLines().mapIndexed{i,s->s to i}.toMap() })
    private val session:OrtSession
    private val topics:List<Pair<String,List<FloatArray>>>
    init {
        val directory=File(c.noBackupFilesDir,"cleanup_model").apply{mkdirs()};val model=File(directory,"semantic-v1.onnx")
        val expected="7134da4215fd3e1997f6902f9836e81225f6ed7f8cfc37b0c574c0b04be13fbd"
        fun hash(f:File):String {val d=MessageDigest.getInstance("SHA-256");f.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;d.update(buffer,0,n)}};return d.digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
        if(model.exists()&&hash(model)!=expected)check(model.delete())
        if(!model.exists()){val staging=File(directory,"semantic-v1.part");c.assets.open("cleanup_ai/semantic.onnx").use{input->staging.outputStream().use{input.copyTo(it)}};check(hash(staging)==expected);check(staging.renameTo(model)){"Nu există spațiu pentru modelul local."}}
        session=OrtSession.SessionOptions().use{opts->opts.setIntraOpNumThreads(2);opts.setInterOpNumThreads(1);env.createSession(model.absolutePath,opts)}
        val data=JSONArray(c.assets.open("cleanup_ai/topics.json").bufferedReader().use{it.readText()})
        topics=(0 until data.length()).map{i->val row=data.getJSONObject(i);val prototypes=row.getJSONArray("prototypes");row.getString("category") to (0 until prototypes.length()).map{j->val values=prototypes.getJSONArray(j);FloatArray(values.length()){values.getDouble(it).toFloat()}}}
    }
    private fun embed(ids:List<Long>):FloatArray {
        val tokens=(listOf(101L)+ids.take(126)+102L).toLongArray();val shape=longArrayOf(1,tokens.size.toLong())
        OnnxTensor.createTensor(env,LongBuffer.wrap(tokens),shape).use{input->OnnxTensor.createTensor(env,LongBuffer.wrap(LongArray(tokens.size){1L}),shape).use{mask->session.run(mapOf("input_ids" to input,"attention_mask" to mask)).use{result->
            @Suppress("UNCHECKED_CAST") val output=(result[0].value as Array<FloatArray>)[0]
            val norm=sqrt(output.sumOf{it.toDouble()*it}).coerceAtLeast(1e-12);return FloatArray(output.size){(output[it]/norm).toFloat()}
        }}}
    }
    fun classify(text:String,partial:Boolean,check:()->Unit):ContentFinding {
        val ids=tokenizer.tokens(text.take(120000));if(ids.size<12)return ContentFinding(null,"Prea puțin text lizibil pentru o clasificare riguroasă.",text.take(140),partial)
        val chunks=ids.chunked(126);val selected=if(chunks.size<=24)chunks.indices.toList()else (0..23).map{it*(chunks.size-1)/23}.distinct()
        val incomplete=partial||selected.size<chunks.size||text.length>120000
        val boosts=topics.associate{it.first to ContentDecision.structuralBoost(it.first,text.take(120000))}
        val votes=selected.map{index->check();val vector=embed(chunks[index])
            val scores=topics.map{(name,prototypes)->val base=prototypes.maxOf{p->vector.indices.sumOf{vector[it].toDouble()*p[it]}};name to (base+if(base>=0.16)boosts.getValue(name)else 0.0)}.sortedByDescending{it.second}
            val first=scores[0];ContentDecision.Vote(if(first.second>=0.22&&first.second-scores[1].second>=0.06)first.first else null,first.second,chunks[index].size)
        }
        val choice=ContentDecision.choose(votes,incomplete)
        val subject=choice.category?.let{ContentDecision.refine(it,text)}
        val refined=subject!=null&&subject!=choice.category
        return ContentFinding(subject,choice.reason+(if(refined)" Grupa este menționată explicit în document."else ""),text.take(160),incomplete,choice.strong)
    }
    override fun close(){session.close()}
}
