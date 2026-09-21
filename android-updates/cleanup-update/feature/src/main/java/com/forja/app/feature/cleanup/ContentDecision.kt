package com.forja.app.feature.cleanup

import java.text.Normalizer
import java.util.Locale

internal object ContentDecision {
    data class Vote(val category:String?,val score:Double,val tokens:Int)
    data class Choice(val category:String?,val strong:Boolean,val reason:String)
    fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
    fun structuralBoost(category:String,text:String):Double {
        val t=normalize(text)
        if(category=="Muncă/CV"){
            val sections=listOf("experienta profesionala","studii","competente","certificari")
            val count=sections.count { Regex("\\b"+Regex.escape(it)+"\\b").containsMatchIn(t) }
            return if(t.contains("curriculum vitae")&&count>=3)0.06 else 0.0
        }
        val cues=when(category){
            "Financiar/Facturi" -> listOf("factura","furnizor","beneficiar","scadenta","cif","iban")
            "Financiar/Bonuri" -> listOf("bon fiscal","casa de marcat","rest","numerar")
            "Educație/Preșcolar" -> listOf("gradinita","grupa mica","grupa mijlocie","grupa mare","obiective operationale","joc didactic")
            "Bucătărie/Rețete" -> listOf("ingrediente","mod de preparare","cuptor","portii")
            "Acte/Contracte" -> listOf("partile","obligatii","semnaturi","prezentul contract")
            else -> emptyList()
        }
        val count=cues.count { Regex("\\b"+Regex.escape(it)+"\\b").containsMatchIn(t) }
        return if(count>=3)0.04 else if(count>=2)0.02 else 0.0
    }
    fun choose(votes:List<Vote>,partial:Boolean):Choice {
        val total=votes.sumOf { it.tokens.coerceAtLeast(0) };if(total<12)return Choice(null,false,"Prea puțin text pentru o propunere riguroasă.")
        val groups=votes.filter { it.category!=null }.groupBy { it.category!! }
        val top=groups.maxByOrNull { (_,v)->v.sumOf { it.tokens } }?:return Choice(null,false,"Tema nu este suficient de clară; verifică documentul.")
        val support=top.value.sumOf { it.tokens }.toDouble()/total
        if(support<0.66)return Choice(null,false,"Documentul conține mai multe teme sau indicii contradictorii. Verifică înainte de mutare.")
        val clear=!partial&&support>=0.85&&top.value.all { it.score>=0.35 }
        return Choice(top.key,clear,"Tema este susținută de ${top.value.size} din ${votes.size} fragmente, ponderate după cantitatea de text."+(if(partial)" Analiză parțială."else ""))
    }
    fun refine(category:String,text:String):String {
        if(category!="Educație/Preșcolar")return category
        val t=normalize(text)
        val groups=listOf("mica" to "Grupa mică","mijlocie" to "Grupa mijlocie","mare" to "Grupa mare").filter { Regex("\\bgrupa\\s+"+it.first+"\\b").containsMatchIn(t) }
        return if(groups.size==1)"$category/${groups.single().second}"else category
    }
}
