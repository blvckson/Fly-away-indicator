package com.blvckson.flyawayindicator

import android.content.Context
import java.io.File

data class RoundRecord(
    val round:Int,
    val endingMultiplier:String,
    val videoPath:String,
    val difference:Double,
    val preSimilarity:Double,
    val statement:String
)

class RoundRecordStore(context:Context) {
    private val file=File(context.filesDir,"round_records.tsv")

    @Synchronized fun add(record:RoundRecord){
        val safe=record.statement.replace("\t"," ").replace("\n"," ")
        file.appendText(listOf(record.round,record.endingMultiplier,record.videoPath,record.difference,record.preSimilarity,safe).joinToString("\t")+"\n")
    }

    @Synchronized fun all():List<RoundRecord>{
        if(!file.exists()) return emptyList()
        return file.readLines().mapNotNull{line->
            val a=line.split("\t")
            if(a.size<6) null else RoundRecord(
                a[0].toIntOrNull()?:0,a[1],a[2],
                a[3].toDoubleOrNull()?:0.0,a[4].toDoubleOrNull()?:0.0,a[5]
            )
        }
    }
}
