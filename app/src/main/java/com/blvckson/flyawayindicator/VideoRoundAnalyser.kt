package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max

data class VideoAnalysis(
    val difference: Double,
    val preSimilarity: Double,
    val redEndMultiplier: String,
    val statement: String
)

class VideoRoundAnalyser {
    fun analyse(file: File): VideoAnalysis {
        if (!file.exists() || file.length() < 1024L) {
            return VideoAnalysis(0.0, 0.0, "", "Recorded video is not yet analyzable.")
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration < 200L) return VideoAnalysis(0.0, 0.0, "", "Round video was too short for analysis.")

            val frames = ArrayList<Bitmap>()
            val maxFrames = 140
            var t = 0L
            while (t < duration * 1000L && frames.size < maxFrames) {
                val b = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                if (b != null) frames.add(b)
                t += 40_000L
            }

            if (frames.size < 5) return VideoAnalysis(0.0, 0.0, "", "Not enough visual frames for comparison.")

            val split = (frames.size * 0.60).toInt().coerceAtLeast(1)
            val lateStart = (frames.size * 0.65).toInt().coerceAtMost(frames.size - 1)
            val early = frames.subList(0, split)
            val late = frames.subList(lateStart, frames.size)

            val earlySignature = signature(early)
            val lateSignature = signature(late)
            val difference = visualDifference(earlySignature, lateSignature)
            val similarity = crossRoundProxy(lateSignature)

            val ending = extractRedEnding(frames)
            frames.forEach { it.recycle() }

            val statement = when {
                difference >= 0.70 -> "Strong visual/behavioural difference between earlier stages and the pre-fly-away stage."
                difference >= 0.45 -> "Clear visual/behavioural difference between earlier stages and the pre-fly-away stage."
                else -> "Only a modest visual/behavioural difference was found in this round."
            }
            VideoAnalysis(difference * 100.0, similarity * 100.0, ending, statement)
        } catch (_: Throwable) {
            VideoAnalysis(0.0, 0.0, "", "Video analysis failed safely; recording is retained.")
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    private data class Sig(
        val red: Double, val bright: Double, val edge: Double,
        val motion: Double, val center: Double
    )

    private fun signature(frames: List<Bitmap>): Sig {
        if (frames.isEmpty()) return Sig(0.0,0.0,0.0,0.0,0.0)
        var red = 0.0
        var bright = 0.0
        var edge = 0.0
        var motion = 0.0
        var center = 0.0
        var previous: Bitmap? = null
        for (b in frames) {
            var rr=0; var bb=0; var e=0; var n=0
            for (y in 0 until b.height step max(8,b.height/12))
                for (x in 0 until b.width step max(8,b.width/14)) {
                    val c=b.getPixel(x,y)
                    val r=(c shr 16) and 255; val g=(c shr 8) and 255; val bl=c and 255
                    if(r>150 && r>g*1.18 && r>bl*1.18) rr++
                    if((r+g+bl)/3>180) bb++
                    if(x+2<b.width) {
                        val c2=b.getPixel(x+2,y)
                        val d=abs(((c shr 16) and 255)-((c2 shr 16) and 255))+
                              abs(((c shr 8) and 255)-((c2 shr 8) and 255))+
                              abs((c and 255)-(c2 and 255))
                        if(d>75) e++
                    }
                    n++
                }
            red += rr.toDouble()/max(1,n)
            bright += bb.toDouble()/max(1,n)
            edge += e.toDouble()/max(1,n)
            center += centralSignature(b)
            if(previous!=null) motion += frameDifference(previous,b)
            previous=b
        }
        val count=frames.size.toDouble()
        return Sig(red/count,bright/count,edge/count,motion/max(1.0,count-1.0),center/count)
    }

    private fun visualDifference(a: Sig,b: Sig):Double {
        val d = abs(a.red-b.red)*3.0 +
                abs(a.bright-b.bright)*1.4 +
                abs(a.edge-b.edge)*1.2 +
                abs(a.motion-b.motion)*1.8 +
                abs(a.center-b.center)*2.0
        return (d/9.4).coerceIn(0.0,1.0)
    }

    private fun crossRoundProxy(s: Sig):Double {
        val variability=(s.red+s.bright+s.edge+s.motion+s.center)/5.0
        return (1.0-variability*0.35).coerceIn(0.0,1.0)
    }

    private fun centralSignature(b:Bitmap):Double {
        val x0=(b.width*0.22).toInt(); val x1=(b.width*0.78).toInt()
        val y0=(b.height*0.10).toInt(); val y1=(b.height*0.48).toInt()
        var total=0; var n=0
        for(y in y0 until y1 step 10) for(x in x0 until x1 step 10){
            val c=b.getPixel(x,y); val r=(c shr 16) and 255; val g=(c shr 8) and 255; val bl=c and 255
            if((r+g+bl)/3>165 || (r>145 && r>g*1.2 && r>bl*1.2)) total++
            n++
        }
        return total.toDouble()/max(1,n)
    }

    private fun frameDifference(a:Bitmap,b:Bitmap):Double {
        val sx=10; val sy=8
        var total=0; var n=0
        for(j in 0 until sy) for(i in 0 until sx){
            val x=i*(b.width-1)/(sx-1); val y=j*(b.height-1)/(sy-1)
            val ca=a.getPixel(x,y); val cb=b.getPixel(x,y)
            total+=abs(((ca shr 16) and 255)-((cb shr 16) and 255))
            total+=abs(((ca shr 8) and 255)-((cb shr 8) and 255))
            total+=abs((ca and 255)-(cb and 255))
            n+=3
        }
        return total.toDouble()/max(1,n*255)
    }

    private fun extractRedEnding(frames: List<Bitmap>): String = ""
}
