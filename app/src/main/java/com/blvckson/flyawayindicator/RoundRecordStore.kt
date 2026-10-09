package com.blvckson.flyawayindicator

import android.content.Context
import java.io.File

data class RoundRecord(
    val round:Int,
    val endingMultiplier:String,
    val videoPath:String,
    val difference:Double,
    val preSimilarity:Double,
    val statement:String,
    val behaviourSignature:String = ""
)

class RoundRecordStore(context:Context) {
    private val file=File(context.filesDir,"round_records.tsv")

    @Synchronized fun add(record:RoundRecord){
        // Upsert by round number: the immediate placeholder is replaced by its completed analysis.
        val existing=all().filterNot{it.round==record.round}
        val updated=(existing+record).sortedBy{it.round}
        val lines=updated.map{r->
            val safe=r.statement.replace("\t"," ").replace("\n"," ")
            val sig=r.behaviourSignature.replace("\t"," ").replace("\n"," ")
            listOf(r.round,r.endingMultiplier,r.videoPath,r.difference,r.preSimilarity,safe,sig).joinToString("\t")
        }
        file.writeText(if(lines.isEmpty()) "" else lines.joinToString("\n")+"\n")
    }

    @Synchronized fun all():List<RoundRecord>{
        if(!file.exists()) return emptyList()
        return file.readLines().mapNotNull{line->
            val a=line.split("\t")
            if(a.size<6) null else RoundRecord(
                a[0].toIntOrNull()?:0,a[1],a[2],
                a[3].toDoubleOrNull()?:0.0,a[4].toDoubleOrNull()?:0.0,a[5],
                if(a.size>=7) a[6] else ""
            )
        }.distinctBy{it.round}.sortedBy{it.round}
    }
}