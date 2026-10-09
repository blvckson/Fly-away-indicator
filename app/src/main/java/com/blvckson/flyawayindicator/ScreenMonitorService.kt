package com.blvckson.flyawayindicator

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class ScreenMonitorService : Service() {
    companion object {
        const val ACTION_STATUS="com.blvckson.flyawayindicator.STATUS"
        const val EXTRA_STATUS="status"
        const val EXTRA_STATEMENT="statement"
        const val EXTRA_ROUNDS="rounds"
        const val EXTRA_RED="red"
    }

    private var projection: MediaProjection?=null
    private var reader:ImageReader?=null
    private var recorder:RoundVideoRecorder?=null
    private val detector=LiveScreenDetector()
    private val videoAnalyser=VideoRoundAnalyser()
    private lateinit var analysisThread:HandlerThread
    private lateinit var analysisHandler:Handler
    private var nextRound=1
    private val redReader=RedMultiplierReader()
    private lateinit var store:RoundRecordStore
    private val thread=HandlerThread("FlyAwayCapture",Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler:Handler
    private lateinit var main:Handler
    private var overlay:TextView?=null
    private var wm:WindowManager?=null
    private var round=0
    private var recording=false
    private var missing=0
    private var liveMisses=0
    private var liveConfirmHits=0
    private var roundFrames=0
    private var lastFrame:Bitmap?=null
    private var lastPlaneX=Float.NaN
    private var lastPlaneY=Float.NaN
    private var lastMove=0.0
    private var preScore=0.0
    private var preHold=0
    private var endingMultiplier=""
    private var lastRedRead=0L
    private val recentChanges=ArrayDeque<Double>()

    override fun onCreate(){
        super.onCreate()
        store=RoundRecordStore(this)
        nextRound=(store.all().maxOfOrNull{it.round}?:0)+1
        analysisThread=HandlerThread("FlyAwayRoundAnalysis",Process.THREAD_PRIORITY_BACKGROUND)
        analysisThread.start()
        analysisHandler=Handler(analysisThread.looper)
        thread.start()
        handler=Handler(thread.looper)
        main=Handler(Looper.getMainLooper())
        showOverlay()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val code=intent?.getIntExtra("resultCode",-1) ?: -1
        val data=intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val pm=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=pm.getMediaProjection(code,data)
        if(projection==null)return START_NOT_STICKY
        startForeground(7,notification())
        startCapture()
        return START_STICKY
    }

    private fun startCapture(){
        if(reader!=null)return
        val m=resources.displayMetrics
        val w=m.widthPixels
        val h=m.heightPixels
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3)
        reader!!.setOnImageAvailableListener({ir->
            val image=ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try{
                val p=image.planes[0]
                val row=p.rowStride
                val stride=p.pixelStride
                val padded=w+(row-stride*w)/stride
                val raw=Bitmap.createBitmap(padded,h,Bitmap.Config.ARGB_8888)
                p.buffer.rewind()
                raw.copyPixelsFromBuffer(p.buffer)
                val frame=if(padded==w) raw else Bitmap.createBitmap(raw,0,0,w,h)
                if(frame!==raw)raw.recycle()
                processFrame(frame,w,h,m.densityDpi)
                frame.recycle()
            }catch(_:Throwable){}finally{image.close()}
        },handler)
        projection?.createVirtualDisplay("FlyAwayAnalysis",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
    }

    private fun processFrame(frame:Bitmap,w:Int,h:Int,density:Int){
        val state=detector.inspect(frame)
        val change=lastFrame?.let{frameDifference(it,frame)} ?: 0.0
        recentChanges.addLast(change)
        while(recentChanges.size>24)recentChanges.removeFirst()

        if(!state.planeX.isNaN() && !state.planeY.isNaN()){
            missing=0
            if(!lastPlaneX.isNaN()){
                val d=sqrt((state.planeX-lastPlaneX)*(state.planeX-lastPlaneX)+(state.planeY-lastPlaneY)*(state.planeY-lastPlaneY))
                lastMove=(lastMove*0.65+(d/(w*0.08)).coerceIn(0.0,1.0)*0.35)
            }
            lastPlaneX=state.planeX
            lastPlaneY=state.planeY
        }else missing++

        val trend=changeTrend()
        val central=centralChange(lastFrame,frame)
        val preCandidate=(trend*0.36+central*0.24+state.planeScore*0.18+lastMove*0.22).coerceIn(0.0,1.0)
        preScore=preScore*0.72+preCandidate*0.28
        if(preScore>=0.62)preHold++ else preHold=max(0,preHold-1)

        val credibleLive = state.isLive && state.sceneScore >= 0.28 && (state.planeScore >= 0.15 || lastMove >= 0.12)
        if(credibleLive){
            liveMisses=0
            liveConfirmHits=(liveConfirmHits+1).coerceAtMost(6)
            if(!recording && liveConfirmHits >= 3) startRound(w,h,density)
        }else{
            liveConfirmHits=max(0,liveConfirmHits-1)
            liveMisses=(liveMisses+1).coerceAtMost(6)
        }

        if(recording){
            if(System.currentTimeMillis()-lastRedRead>=80L){
                lastRedRead=System.currentTimeMillis()
                redReader.inspect(frame){v->endingMultiplier=v}
            }
            if(preHold>=3){
                publish("PRE-FLY-AWAY DETECTED",true,"Pre-fly-away visual/behavioural transition detected. Red indicator ON.")
            }else{
                publish("RECORDING ROUND $round",false,"Whole Aviator live screen is being recorded; visual and plane behaviour are being tracked.")
            }
            if(liveMisses>=4 && roundFrames>=12)finishRound()
            else roundFrames++
        }

        lastFrame?.recycle()
        lastFrame=Bitmap.createBitmap(frame)
    }

    private fun startRound(w:Int,h:Int,density:Int){
        round=nextRound++
        roundFrames=0
        liveConfirmHits=0
        missing=0
        liveMisses=0
        preScore=0.0
        preHold=0
        endingMultiplier=""
        lastRedRead=0L
        lastPlaneX=Float.NaN
        lastPlaneY=Float.NaN
        recentChanges.clear()
        recorder=RoundVideoRecorder(projection!!,this)
        val file=recorder?.start(w,h,density)
        recording=file!=null
        if(recording)publish("RECORDING ROUND $round",false,"Aviator live screen detected — recording started automatically.")
        else publish("LIVE SCREEN DETECTED",false,"Aviator live screen detected, but video recorder could not start.")
    }

    private fun finishRound(){
        if(!recording)return
        val savedRound=round
        val savedEnding=endingMultiplier
        val file=recorder?.stop()
        recording=false
        val previous=store.all()
        round=0
        missing=0
        liveMisses=0
        liveConfirmHits=0
        roundFrames=0
        preHold=0
        preScore=0.0
        analysisHandler.post{
            val analysis=if(file!=null) videoAnalyser.analyse(file,previous)
            else VideoAnalysis(0.0,0.0,"","No video file was produced.")
            val finalMultiplier=if(savedEnding.isNotBlank())savedEnding else analysis.redEndMultiplier
            val statement="Difference earlier → pre-fly-away: %.0f%%. Pre-fly-away visual consistency against recorded rounds: %.0f%%. %s".format(analysis.difference,analysis.preSimilarity,analysis.statement)
            store.add(RoundRecord(savedRound,finalMultiplier,file?.absolutePath?:"",analysis.difference,analysis.preSimilarity,statement,analysis.behaviourSignature))
            publish("ROUND $savedRound SAVED",false,statement)
        }
    }

    private fun changeTrend():Double{
        if(recentChanges.isEmpty())return 0.0
        val a=recentChanges.toList()
        val early=a.take(max(1,a.size/2)).average()
        val late=a.takeLast(max(1,a.size/2)).average()
        return ((late-early)/max(0.012,early+0.012)+0.5).coerceIn(0.0,1.0)
    }

    private fun centralChange(a:Bitmap?,b:Bitmap):Double{
        if(a==null)return 0.0
        val x0=(b.width*0.20).toInt();val x1=(b.width*0.80).toInt()
        val y0=(b.height*0.08).toInt();val y1=(b.height*0.52).toInt()
        var total=0;var n=0
        for(y in y0 until y1 step 9)for(x in x0 until x1 step 9){
            val ca=a.getPixel(x,y);val cb=b.getPixel(x,y)
            total+=abs(((ca shr 16)and 255)-((cb shr 16)and 255))
            total+=abs(((ca shr 8)and 255)-((cb shr 8)and 255))
            total+=abs((ca and 255)-(cb and 255));n+=3
        }
        return (total.toDouble()/max(1,n*255)*7.0).coerceIn(0.0,1.0)
    }

    private fun frameDifference(a:Bitmap,b:Bitmap):Double{
        val sx=12;val sy=9
        var total=0;var n=0
        for(j in 0 until sy)for(i in 0 until sx){
            val x=i*(b.width-1)/(sx-1);val y=j*(b.height-1)/(sy-1)
            val ca=a.getPixel(x,y);val cb=b.getPixel(x,y)
            total+=abs(((ca shr 16)and 255)-((cb shr 16)and 255))
            total+=abs(((ca shr 8)and 255)-((cb shr 8)and 255))
            total+=abs((ca and 255)-(cb and 255));n+=3
        }
        return total.toDouble()/max(1,n*255)
    }

    private fun publish(status:String,pre:Boolean,statement:String){
        main.post{
            overlay?.text=(if(pre)"●  🔴 " else "●  ")+status
            overlay?.setTextColor(0xFFFFFFFF.toInt())
            overlay?.setBackgroundColor(when { pre -> 0xFFE53935.toInt(); recording -> 0xFF2E7D32.toInt(); else -> 0xCC202124.toInt() })
        }
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS,status).putExtra(EXTRA_STATEMENT,statement).putExtra(EXTRA_RED,pre).putExtra(EXTRA_ROUNDS,store.all().size))
    }

    private fun showOverlay(){
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val tv=TextView(this);tv.text="●  WAITING FOR AVIATOR";tv.textSize=13f
        tv.setTextColor(0xFFFFFFFF.toInt());tv.setBackgroundColor(0xCC202124.toInt());tv.setPadding(18,10,18,10)
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.START;lp.x=24;lp.y=80
        var dx=0f;var dy=0f;var sx=0;var sy=0
        tv.setOnTouchListener{_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{dx=e.rawX;dy=e.rawY;sx=lp.x;sy=lp.y;true}
            MotionEvent.ACTION_MOVE->{lp.x=sx+(e.rawX-dx).toInt();lp.y=sy+(e.rawY-dy).toInt();wm?.updateViewLayout(tv,lp);true}
            else->true
        }}
        wm?.addView(tv,lp);overlay=tv
    }

    private fun notification():Notification{
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("scan","Fly Away Indicator",NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this,"scan").setContentTitle("Fly Away Indicator").setContentText("Monitoring Aviator live screen").setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    override fun onDestroy(){
        try{finishRound()}catch(_:Throwable){}
        reader?.close();reader=null;projection?.stop();projection=null
        recorder?.release();recorder=null
        lastFrame?.recycle();lastFrame=null
        redReader.close()
        overlay?.let{wm?.removeView(it)};overlay=null
        thread.quitSafely()
        if(::analysisThread.isInitialized) analysisThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent:Intent?):IBinder?=null
}
