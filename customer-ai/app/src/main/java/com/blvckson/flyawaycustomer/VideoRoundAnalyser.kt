package com.blvckson.flyawaycustomer

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

data class VideoAnalysis(
    val difference: Double,
    val preSimilarity: Double,
    val redEndMultiplier: String,
    val statement: String,
    val behaviourSignature: String = ""
)

class VideoRoundAnalyser {
    fun analyse(file: java.io.File, previous: List<RoundRecord> = emptyList()): VideoAnalysis {
        if (!file.exists() || file.length() < 1024L)
            return VideoAnalysis(0.0,0.0,"","Recorded video is not yet analyzable.")

        val r=MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            if(duration<200L) return VideoAnalysis(0.0,0.0,"","Round video was too short for analysis.")

            // Analyse the whole saved round, but spend most temporal resolution near its end.
            // The recording is finalized shortly after the stable red ending multiplier is detected.
            // Therefore the last ~850 ms are treated as post-end confirmation, NOT pre-fly-away.
            // Pre-fly-away is explicitly the short 1.2-second window immediately BEFORE that point.
            // Earlier and immediately preceding sections are retained separately for comparison.
            val estimatedEndMs=(duration-850L).coerceAtLeast(0L)
            val preStartMs=(estimatedEndMs-1200L).coerceAtLeast(0L)
            val middleStartMs=(preStartMs-1800L).coerceAtLeast(0L)
            val sampleTimes=java.util.TreeSet<Long>()
            val broadEndMs=middleStartMs
            val broadCount=32
            if(broadEndMs>0L){
                for(i in 0 until broadCount){
                    sampleTimes.add((i.toLong()*broadEndMs)/(broadCount-1L))
                }
            }
            // Dense 80 ms sampling across the final comparison sections approximates slow-motion review.
            var t=middleStartMs
            while(t<=duration){
                sampleTimes.add(t)
                t+=80L
            }
            sampleTimes.add(duration)
            val frames=ArrayList<Bitmap>(sampleTimes.size)
            val frameTimes=ArrayList<Long>(sampleTimes.size)
            for(timeMs in sampleTimes){
                val raw=r.getFrameAtTime(timeMs*1000L,MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val scale=minOf(1.0,320.0/max(raw.width,raw.height).toDouble())
                val b=if(scale<1.0) Bitmap.createScaledBitmap(
                    raw,(raw.width*scale).toInt().coerceAtLeast(2),
                    (raw.height*scale).toInt().coerceAtLeast(2),true
                ) else raw
                if(b!==raw) raw.recycle()
                frames.add(b)
                frameTimes.add(timeMs)
            }
            if(frames.size<8){
                frames.forEach{it.recycle()}
                return VideoAnalysis(0.0,0.0,"","Not enough visual frames for comparison.")
            }

            // Isolate the last small moments BEFORE the estimated fly-away/end marker.
            val preIndices=frameTimes.indices.filter{frameTimes[it]>=preStartMs && frameTimes[it]<estimatedEndMs}
            val middleIndices=frameTimes.indices.filter{frameTimes[it]>=middleStartMs && frameTimes[it]<preStartMs}
            val earlierIndices=frameTimes.indices.filter{frameTimes[it]<middleStartMs}
            if(preIndices.size<4){
                frames.forEach{it.recycle()}
                return VideoAnalysis(0.0,0.0,"","Pre-fly-away segment was too short to analyse reliably; original video retained.")
            }
            val preFrames=preIndices.map{frames[it]}
            val middleFrames=middleIndices.map{frames[it]}
            val earlierFrames=earlierIndices.map{frames[it]}
            val preSignature=signature(preFrames)
            val earlierSignature=signature(if(earlierFrames.size>=3) earlierFrames else middleFrames)
            val middleSignature=signature(if(middleFrames.size>=3) middleFrames else earlierFrames)
            val earlierDifference=visualDifference(earlierSignature,preSignature)
            val adjacentDifference=visualDifference(middleSignature,preSignature)
            val difference=earlierDifference*0.60+adjacentDifference*0.40
            val redEnd=readEndingMultiplier(frames.takeLast(8))

            // Store and compare ONLY the isolated pre-fly-away signature across rounds.
            val currentSig=encode(preSignature)
            val similarity=compareWithHistory(currentSig,previous)
            frames.forEach{it.recycle()}

            val statement=when{
                difference>=0.70 -> "Strong difference: earlier round vs final pre-fly-away moments. The pre-fly-away segment is isolated before the plane flies away."
                difference>=0.45 -> "Clear difference: earlier round vs final pre-fly-away moments. The pre-fly-away segment is isolated before the plane flies away."
                else -> "The isolated final pre-fly-away moments remain visually similar to the earlier sections."
            } + " Pre-fly-away window: approximately 1.2 seconds before the detected ending point; post-ending frames are excluded from the comparison."
            VideoAnalysis(
                difference*100.0,
                similarity*100.0,
                redEnd,
                statement,
                currentSig
            )
        }catch(_:Throwable){
            VideoAnalysis(0.0,0.0,"","Video analysis failed safely; recording is retained.")
        }finally{
            try{r.release()}catch(_:Throwable){}
        }
    }

    
    private fun readEndingMultiplier(frames: List<Bitmap>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // Final multiplier is normally red and near the central game area.
            // Upscale the crop before OCR because recorded analysis frames are downsampled.
            for (frame in frames.asReversed().take(6)) {
                var crop: Bitmap? = null
                var mask: Bitmap? = null
                var enlarged: Bitmap? = null
                try {
                    val left=(frame.width*0.16f).toInt()
                    val top=(frame.height*0.10f).toInt()
                    val right=(frame.width*0.84f).toInt()
                    val bottom=(frame.height*0.60f).toInt()
                    val w=(right-left).coerceAtLeast(1)
                    val h=(bottom-top).coerceAtLeast(1)
                    crop=Bitmap.createBitmap(frame,left,top,w,h)
                    mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    val pixels=IntArray(w*h)
                    crop.getPixels(pixels,0,w,0,0,w,h)
                    for(i in pixels.indices){
                        val color=pixels[i]
                        val rr=Color.red(color); val g=Color.green(color); val b=Color.blue(color)
                        // Preserve red digits as high-contrast white glyphs on black.
                        pixels[i]=if(rr>75 && rr-maxOf(g,b)>14 && rr>g*1.06 && rr>b*1.06) Color.WHITE else Color.BLACK
                    }
                    mask.setPixels(pixels,0,w,0,0,w,h)
                    enlarged=Bitmap.createScaledBitmap(mask,w*2,h*2,true)
                    val text=Tasks.await(
                        recognizer.process(InputImage.fromBitmap(enlarged,0)),
                        450, TimeUnit.MILLISECONDS
                    ).text
                    val cleaned=text.replace(',','.').replace('O','0').replace('o','0')
                        .replace('I','1').replace('l','1')
                    val matcher=Pattern.compile("(\\d{1,7}(?:\\.\\d{1,4})?)\\s*[xX]?").matcher(cleaned)
                    var best=Double.NaN
                    while(matcher.find()){
                        val token=matcher.group(1) ?: continue
                        val v=token.toDoubleOrNull() ?: continue
                        if(v>=1.0 && v<=10000000.0 && (best.isNaN() || v>best)) best=v
                    }
                    if(!best.isNaN()) return "%.2fx".format(Locale.US,best)
                } catch (_: Throwable) {
                    // OCR is best-effort; keep trying the preceding captured ending frame.
                } finally {
                    try { enlarged?.recycle() } catch (_: Throwable) {}
                    try { mask?.recycle() } catch (_: Throwable) {}
                    try { crop?.recycle() } catch (_: Throwable) {}
                }
            }
        } finally {
            recognizer.close()
        }
        return ""
    }

    private data class Sig(
        val red:Double,
        val bright:Double,
        val edge:Double,
        val turn:Double,
        val center:Double
    )

    private fun signature(frames:List<Bitmap>):Sig{
        if(frames.isEmpty()) return Sig(0.0,0.0,0.0,0.0,0.0)
        var red=0.0
        var bright=0.0
        var edge=0.0
        var center=0.0
        var turn=0.0
        var previousAngle=Double.NaN
        var previousX=Double.NaN
        var previousY=Double.NaN

        for(b in frames){
            var rr=0
            var bb=0
            var ee=0
            var n=0
            var sx=0.0
            var sy=0.0
            var sw=0.0
            for(y in 0 until b.height step max(5,b.height/18))
                for(x in 0 until b.width step max(5,b.width/22)){
                    val c=b.getPixel(x,y)
                    val r=(c shr 16) and 255
                    val g=(c shr 8) and 255
                    val bl=c and 255
                    if(r>150 && r>g*1.18 && r>bl*1.18){
                        val q=1.0+(r-max(g,bl)).coerceAtLeast(0)/255.0
                        sx+=x*q; sy+=y*q; sw+=q; rr++
                    }
                    if((r+g+bl)/3>180) bb++
                    if(x+2<b.width){
                        val c2=b.getPixel(x+2,y)
                        val d=abs(((c shr 16)and 255)-((c2 shr 16)and 255))+
                                abs(((c shr 8)and 255)-((c2 shr 8)and 255))+
                                abs((c and 255)-(c2 and 255))
                        if(d>75) ee++
                    }
                    n++
                }
            red+=rr.toDouble()/max(1,n)
            bright+=bb.toDouble()/max(1,n)
            edge+=ee.toDouble()/max(1,n)
            center+=centralSignature(b)

            // Movement component is direction/turn behaviour, not movement speed.
            if(sw>3.0){
                val x=sx/sw
                val y=sy/sw
                if(!previousX.isNaN()){
                    val dx=x-previousX
                    val dy=y-previousY
                    val dist=sqrt(dx*dx+dy*dy)
                    if(dist>1.0){
                        val angle=atan2(dy,dx)
                        if(!previousAngle.isNaN()){
                            val da=abs(angle-previousAngle).let{if(it>Math.PI)2*Math.PI-it else it}
                            turn+=da/Math.PI
                        }
                        previousAngle=angle
                    }
                }
                previousX=x
                previousY=y
            }
        }
        val c=frames.size.toDouble()
        return Sig(red/c,bright/c,edge/c,turn/max(1.0,c-1.0),center/c)
    }

    private fun visualDifference(a:Sig,b:Sig):Double{
        val d=abs(a.red-b.red)*3.0+
                abs(a.bright-b.bright)*1.4+
                abs(a.edge-b.edge)*1.2+
                abs(a.turn-b.turn)*2.0+
                abs(a.center-b.center)*2.0
        return(d/9.6).coerceIn(0.0,1.0)
    }

    private fun stageDifference(frames:List<Bitmap>, split:Int, lateStart:Int):Double {
        if (lateStart <= split || split < 2) return 0.0
        val middle = signature(frames.subList(split, lateStart))
        val late = signature(frames.subList(lateStart, frames.size))
        return visualDifference(middle, late)
    }

    private fun encode(s:Sig):String=
        listOf(s.red,s.bright,s.edge,s.turn,s.center).joinToString(",")

    private fun decode(s:String):Sig?{
        val a=s.split(",").mapNotNull{it.toDoubleOrNull()}
        return if(a.size==5) Sig(a[0],a[1],a[2],a[3],a[4]) else null
    }

    private fun compareWithHistory(current:String,previous:List<RoundRecord>):Double{
        val cur=decode(current) ?: return 0.0
        val old=previous.asSequence().mapNotNull{decode(it.behaviourSignature)}.toList()
        if(old.isEmpty()) return 1.0
        val best=old.maxOf{other->
            val d=abs(cur.red-other.red)*3.0+
                    abs(cur.bright-other.bright)*1.4+
                    abs(cur.edge-other.edge)*1.2+
                    abs(cur.turn-other.turn)*2.0+
                    abs(cur.center-other.center)*2.0
            (1.0-d/9.6).coerceIn(0.0,1.0)
        }
        return best
    }

    private fun centralSignature(b:Bitmap):Double{
        val x0=(b.width*0.22).toInt()
        val x1=(b.width*0.78).toInt()
        val y0=(b.height*0.10).toInt()
        val y1=(b.height*0.48).toInt()
        var total=0
        var n=0
        for(y in y0 until y1 step 8) for(x in x0 until x1 step 8){
            val c=b.getPixel(x,y)
            val r=(c shr 16)and 255
            val g=(c shr 8)and 255
            val bl=c and 255
            if((r+g+bl)/3>165 || (r>145 && r>g*1.2 && r>bl*1.2)) total++
            n++
        }
        return total.toDouble()/max(1,n)
    }
}