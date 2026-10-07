package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

class RedMultiplierReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false

    fun inspect(frame: Bitmap, callback: (String) -> Unit) {
        if (busy) return
        busy = true
        val left=(frame.width*0.16f).toInt()
        val top=(frame.height*0.12f).toInt()
        val right=(frame.width*0.84f).toInt()
        val bottom=(frame.height*0.58f).toInt()
        val w=(right-left).coerceAtLeast(1)
        val h=(bottom-top).coerceAtLeast(1)
        val crop=Bitmap.createBitmap(frame,left,top,w,h)
        val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val px=IntArray(w*h)
        crop.getPixels(px,0,w,0,0,w,h)
        for(i in px.indices){
            val c=px[i]
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            val red=r>110 && r-maxOf(g,b)>25 && r>g*1.12 && r>b*1.12
            px[i]=if(red) Color.WHITE else Color.BLACK
        }
        mask.setPixels(px,0,w,0,0,w,h)
        crop.recycle()
        recognizer.process(InputImage.fromBitmap(mask,0))
            .addOnSuccessListener { text ->
                val cleaned=text.text.replace(',','.').replace('O','0').replace('o','0').replace('I','1').replace('l','1')
                val m=Pattern.compile("(\\d{1,7}(?:\\.\\d{1,4})?)\\s*[xX]?").matcher(cleaned)
                var best=Double.NaN
                while(m.find()){
                    val v=m.group(1)?.toDoubleOrNull() ?: continue
                    if(v>=1.0 && v<=10000000.0 && (best.isNaN() || v>best)) best=v
                }
                if(!best.isNaN()) callback("%.2fx".format(Locale.US,best))
            }
            .addOnCompleteListener { mask.recycle(); busy=false }
    }

    fun close(){ recognizer.close() }
}
