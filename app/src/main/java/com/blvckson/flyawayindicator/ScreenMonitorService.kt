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
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        const val ACTION_SHOW_OVERLAY="com.blvckson.flyawayindicator.SHOW_OVERLAY"
    }

    private var projection: MediaProjection?=null
    private var reader:ImageReader?=null
    private var recorder:RoundVideoRecorder?=null
    private val detector=LiveScreenDetector()
    private val textVerifier=AviatorTextVerifier()
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
    private var preview:ImageView?=null
    private var overlayContainer:LinearLayout?=null
    private var previewBitmap:Bitmap?=null
    private var lastPreviewUpdate=0L
    private var previewWidth=220
    private var previewHeight=390
    private var wm:WindowManager?=null
    private val overlayEvents=ArrayDeque<String>()
    private var lastLoggedStatus=""
    private var lastPublishedStatus=""
    private var lastPublishedStatement=""
    private var lastPublishedPre=false
    private var overlayExpanded=true
    private var overlayTextSize=13f
    private var pinchStartDistance=0f
    private var pinchStartSize=13f
    private var touchDownX=0f
    private var touchDownY=0f
    private var touchStartX=0
    private var touchStartY=0
    private var touchMoved=false
    private val overlayPrefs by lazy { getSharedPreferences("overlay_display", MODE_PRIVATE) }
    private var round=0
    private var recording=false
    private var missing=0
    private var liveMisses=0
    private var liveConfirmHits=0
    private var roundFrames=0
    private var planeSeenInRound=false
    private var planeMissingSinceMs=0L
    private var lastFrame:Bitmap?=null
    private var lastPlaneX=Float.NaN
    private var lastPlaneY=Float.NaN
    private var lastMove=0.0
    private var preScore=0.0
    private var preHold=0
    private var endingMultiplier=""
    private var lastEndingCandidate=""
    @Volatile private var endingMultiplierHits=0
    private var stableEndingFrames=0
    @Volatile private var stableEndingSinceMs=0L
    private var lastRedRead=0L
    private val recentChanges=ArrayDeque<Double>()
    // The indicator remains independent from recording/saving. Its own early-round
    // baseline prevents ordinary take-off animation from immediately turning it red.
    private val indicatorChanges=ArrayDeque<Double>()
    private val indicatorBaseline=ArrayDeque<Double>()
    private var indicatorRoundActive=false
    private var indicatorLiveHits=0
    private var indicatorMisses=0
    private var indicatorPreScore=0.0
    private var indicatorPreHold=0
    private var indicatorPreDetected=false
    private var indicatorFrames=0
    private var indicatorBaselineScore=0.0

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
        overlayTextSize=overlayPrefs.getFloat("text_size",13f).coerceIn(10f,24f)
        overlayExpanded=overlayPrefs.getBoolean("expanded",true)
        showOverlay()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        startForeground(7,notification())
        if(intent?.action==ACTION_SHOW_OVERLAY){
            if(overlayContainer==null) showOverlay()
            return START_STICKY
        }
        val code=intent?.getIntExtra("resultCode",-1) ?: -1
        val data=intent?.getParcelableExtra<Intent>("data")
        if(data==null){
            if(projection!=null && reader==null) startCapture()
            return START_STICKY
        }
        if(projection==null){
            val pm=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection=try { pm.getMediaProjection(code,data) } catch (_:Throwable) { null }
        }
        if(projection==null){
            publish("SCREEN CAPTURE PERMISSION REQUIRED",false,
                "Monitoring paused. Open Fly Away Indicator and press START MONITOR to grant screen-capture permission again.")
            return START_STICKY
        }
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
                updateLivePreview(frame)
                processFrame(frame,w,h,m.densityDpi)
                frame.recycle()
            }catch(_:Throwable){}finally{image.close()}
        },handler)
        projection?.createVirtualDisplay("FlyAwayAnalysis",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
    }

    private fun processFrame(frame:Bitmap,w:Int,h:Int,density:Int){
        textVerifier.inspect(frame)
        val state=detector.inspect(frame)
        val change=lastFrame?.let{frameDifference(it,frame)} ?: 0.0
        recentChanges.addLast(change)
        while(recentChanges.size>24)recentChanges.removeFirst()
        indicatorChanges.addLast(change)
        while(indicatorChanges.size>36)indicatorChanges.removeFirst()

        if(!state.planeX.isNaN() && !state.planeY.isNaN()){
            missing=0
            if(!lastPlaneX.isNaN()){
                val d=sqrt((state.planeX-lastPlaneX)*(state.planeX-lastPlaneX)+(state.planeY-lastPlaneY)*(state.planeY-lastPlaneY))
                lastMove=(lastMove*0.65+(d/(w*0.08)).coerceIn(0.0,1.0)*0.35)
            }
            lastPlaneX=state.planeX
            lastPlaneY=state.planeY
        }else missing++

        val central=centralChange(lastFrame,frame)
        val planePresent = !state.planeX.isNaN() && !state.planeY.isNaN()
        val visualLive = state.isLive &&
            state.sceneScore >= 0.16 &&
            state.planeScore >= 0.08 &&
            state.multiplierVisualScore >= 0.07

        // Start/end the colour detector independently from video recording.
        // Do not let low-threshold movement alone trigger the pre-fly-away warning.
        if(visualLive){
            indicatorLiveHits=(indicatorLiveHits+1).coerceAtMost(4)
            indicatorMisses=0
            if(!indicatorRoundActive && indicatorLiveHits>=2){
                indicatorRoundActive=true
                indicatorPreScore=0.0
                indicatorPreHold=0
                indicatorPreDetected=false
                indicatorFrames=0
                indicatorBaselineScore=0.0
                indicatorBaseline.clear()
                indicatorChanges.clear()
            }
        }else{
            indicatorLiveHits=0
            if(indicatorRoundActive) indicatorMisses=(indicatorMisses+1).coerceAtMost(24)
        }

        if(indicatorRoundActive){
            // Restore the signal blend used by Build 75, which the user reported
            // as detecting the pre-fly-away period well in short rounds. Keep it
            // isolated from recording/saving, but do not add a baseline delay that
            // can suppress the transition entirely.
            val indicatorTrend=changeTrend(recentChanges)
            val candidate=(indicatorTrend*0.36+central*0.24+
                state.planeScore*0.18+lastMove*0.22).coerceIn(0.0,1.0)
            indicatorPreScore=indicatorPreScore*0.72+candidate*0.28
            if(indicatorPreScore>=0.62) {
                indicatorPreHold=(indicatorPreHold+1).coerceAtMost(8)
            } else {
                indicatorPreHold=max(0,indicatorPreHold-1)
            }
            if(indicatorPreHold>=3) indicatorPreDetected=true

            if(textVerifier.isEndConfirmed() || indicatorMisses>=18){
                indicatorRoundActive=false
                indicatorLiveHits=0
                indicatorMisses=0
                indicatorPreScore=0.0
                indicatorPreHold=0
                indicatorPreDetected=false
                indicatorFrames=0
                indicatorBaselineScore=0.0
                indicatorBaseline.clear()
                indicatorChanges.clear()
            }
        }

        // Round video recording, saving, and post-round analysis are intentionally
        // disabled: they consumed CPU/storage and interfered with the user's priority,
        // the live pre-fly-away colour indicator. Keep monitoring independent.
        liveConfirmHits=0

        if(recording){
            val frameNow=System.currentTimeMillis()
            if(planePresent){
                planeSeenInRound=true
                planeMissingSinceMs=0L
                liveMisses=0
            }else{
                liveMisses=(liveMisses+1).coerceAtMost(60)
                if(planeSeenInRound && planeMissingSinceMs==0L) planeMissingSinceMs=frameNow
            }
            if(System.currentTimeMillis()-lastRedRead>=80L){
                lastRedRead=System.currentTimeMillis()
                redReader.inspect(frame){v->
                    if(v.isNotBlank()){
                        endingMultiplier=v
                        if(v==lastEndingCandidate) {
                            endingMultiplierHits=(endingMultiplierHits+1).coerceAtMost(12)
                        } else {
                            lastEndingCandidate=v
                            endingMultiplierHits=1
                            stableEndingSinceMs=System.currentTimeMillis()
                        }
                    }
                }
            }
            if(endingMultiplierHits>=3) stableEndingFrames++ else stableEndingFrames=0
            if(indicatorPreDetected){
                publish("PRE-FLY-AWAY DETECTED",true,"Pre-fly-away visual/behavioural transition detected. Red indicator ON.")
            }else{
                publish("RECORDING ROUND $round",false,"Whole Aviator live screen is being recorded; visual and plane behaviour are being tracked.")
            }
            roundFrames++
            val now=System.currentTimeMillis()
            val redMultiplierFrozen = endingMultiplierHits >= 3 &&
                stableEndingSinceMs > 0L && now-stableEndingSinceMs >= 850L
            val explicitEnd = textVerifier.isEndConfirmed() && roundFrames >= 8
            val sustainedPlaneDisappearance = planeSeenInRound && !planePresent &&
                planeMissingSinceMs > 0L && now-planeMissingSinceMs >= 1800L &&
                (endingMultiplier.isNotBlank() || textVerifier.isEndConfirmed())
            if(roundFrames >= 20 && (redMultiplierFrozen || explicitEnd || sustainedPlaneDisappearance)) finishRound()
        }

        if(!recording && indicatorRoundActive){
            if(indicatorPreDetected){
                publish("PRE-FLY-AWAY DETECTED",true,
                    "Pre-fly-away visual/behavioural transition detected. Red indicator ON.")
            }else{
                publish("ROUND VISUAL MONITORING — GREEN",false,
                    "Tracking visual behaviour independently of round recording/saving.")
            }
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
        lastEndingCandidate=""
        endingMultiplierHits=0
        stableEndingFrames=0
        stableEndingSinceMs=0L
        lastRedRead=0L
        lastPlaneX=Float.NaN
        lastPlaneY=Float.NaN
        planeSeenInRound=false
        planeMissingSinceMs=0L
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
        val file=try { recorder?.stop() } catch (_:Throwable) { null }
        recorder=null
        recording=false
        round=0
        missing=0
        liveMisses=0
        liveConfirmHits=0
        roundFrames=0
        preHold=0
        preScore=0.0
        lastPlaneX=Float.NaN
        lastPlaneY=Float.NaN
        planeSeenInRound=false
        planeMissingSinceMs=0L
        endingMultiplier=""
        lastEndingCandidate=""
        endingMultiplierHits=0
        stableEndingFrames=0
        stableEndingSinceMs=0L
        val savedPath=file?.absolutePath?:""
        publish("ROUND $savedRound END CONFIRMED — SAVING",false,
            "Round ended: FLEW AWAY / plane disappearance plus a stable red final multiplier. Next-round detection remains active.")
        val previous=store.all()
        try {
            store.add(RoundRecord(savedRound,savedEnding,savedPath,0.0,0.0,
                if(savedPath.isNotBlank()) "Round video finalized; visual analysis pending." else "Round detected, but video finalization failed."))
            publish("ROUND $savedRound SAVED — ANALYSIS PENDING",false,
                if(savedPath.isNotBlank()) "Round $savedRound video saved; analysis queued in the background."
                else "Round $savedRound was registered, but its video file could not be finalized.")
        } catch (e:Throwable) {
            publish("ROUND $savedRound SAVE ERROR",false,"Could not persist round $savedRound: ${e.javaClass.simpleName}")
            return
        }
        analysisHandler.post{
            val analysis=if(file!=null) videoAnalyser.analyse(file,previous)
            else VideoAnalysis(0.0,0.0,"","No video file was produced.")
            val finalMultiplier=if(savedEnding.isNotBlank())savedEnding else analysis.redEndMultiplier
            val statement="Difference earlier → pre-fly-away: %.0f%%. Pre-fly-away visual consistency against recorded rounds: %.0f%%. %s".format(analysis.difference,analysis.preSimilarity,analysis.statement)
            try {
                store.add(RoundRecord(savedRound,finalMultiplier,savedPath,analysis.difference,analysis.preSimilarity,statement,analysis.behaviourSignature))
                publish("ROUND $savedRound SAVED",false,statement)
            } catch (e:Throwable) {
                publish("ROUND $savedRound SAVED — ANALYSIS UPDATE FAILED",false,
                    "The saved-round entry was retained; analysis update failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun changeTrend(history:ArrayDeque<Double> = recentChanges):Double{
        if(history.isEmpty())return 0.0
        val a=history.toList()
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
        if(status==lastPublishedStatus && pre==lastPublishedPre && statement==lastPublishedStatement) return
        lastPublishedStatus=status
        lastPublishedPre=pre
        lastPublishedStatement=statement
        if(status!=lastLoggedStatus){
            val stamp=SimpleDateFormat("HH:mm:ss",Locale.US).format(Date())
            overlayEvents.addLast("$stamp  $status")
            while(overlayEvents.size>6) overlayEvents.removeFirst()
            lastLoggedStatus=status
        } else if(status.contains("SAVED") && statement.isNotBlank() &&
            (overlayEvents.isEmpty() || !overlayEvents.last().contains("analysis complete",true))) {
            val stamp=SimpleDateFormat("HH:mm:ss",Locale.US).format(Date())
            if(status=="ROUND $round SAVED") {
                overlayEvents.addLast("$stamp  Round analysis complete")
                while(overlayEvents.size>6) overlayEvents.removeFirst()
            }
        }
        main.post{
            overlay?.text=buildOverlayText(status,pre)
            overlay?.textSize=overlayTextSize
            overlay?.setTextColor(0xFFFFFFFF.toInt())
            overlay?.setBackgroundColor(when { pre -> 0xFFE53935.toInt(); indicatorRoundActive || recording -> 0xFF2E7D32.toInt(); else -> 0xE6202124.toInt() })
        }
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS,status).putExtra(EXTRA_STATEMENT,statement).putExtra(EXTRA_RED,pre).putExtra(EXTRA_ROUNDS,store.all().size))
    }

    private fun buildOverlayText(status:String,pre:Boolean):String {
        val marker=if(pre) "● 🔴 " else if(recording) "● REC " else "● "
        if(!overlayExpanded) return marker+status+"\nTap to expand · pinch to resize"
        val history=overlayEvents.toList().takeLast(5).joinToString("\n")
        val current=if(history.isBlank()) "Waiting for Aviator screen confirmation" else history
        val recordingLine=if(recording) "Recorder: ON · Round $round · Frames $roundFrames" else "Recorder: OFF"
        val multiplier=if(endingMultiplier.isBlank()) "Final multiplier: reading / not confirmed" else "Final multiplier: $endingMultiplier"
        val analysis=if(status.contains("ANALYSIS PENDING")) "Analysis: queued" else if(status.contains("analysis complete",true) || status.contains("SAVED",true)) "Analysis: processing / saved" else "Analysis: waiting for round end"
        return "$marker$status\n$recordingLine\n$multiplier\n$analysis\n— Recent activity (oldest → newest) —\n$current\nDrag to move · tap to collapse · pinch to resize"
    }

    private fun updateLivePreview(frame:Bitmap){
        val now=System.currentTimeMillis()
        // Refresh the optional live preview less often to reduce UI/bitmap overhead.
        if(now-lastPreviewUpdate<300L)return
        lastPreviewUpdate=now
        val copy=try{Bitmap.createScaledBitmap(frame,previewWidth,previewHeight,true)}catch(_:Throwable){return}
        main.post{
            val old=previewBitmap
            previewBitmap=copy
            preview?.setImageBitmap(copy)
            if(old!==copy)old?.recycle()
        }
    }

    private fun showOverlay(){
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val panel=LinearLayout(this)
        panel.orientation=LinearLayout.VERTICAL
        panel.setBackgroundColor(0xE6202124.toInt())
        val titleBar=LinearLayout(this)
        titleBar.orientation=LinearLayout.HORIZONTAL
        titleBar.gravity=Gravity.CENTER_VERTICAL
        val title=TextView(this)
        title.text="Fly Away Indicator"
        title.textSize=14f
        title.setTextColor(0xFFFFFFFF.toInt())
        title.setPadding(10,6,10,6)
        title.layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
        val close=TextView(this)
        close.text="✕"
        close.textSize=22f
        close.gravity=Gravity.CENTER
        close.setTextColor(0xFFFFFFFF.toInt())
        close.contentDescription="Hide floating display"
        close.setPadding(14,5,14,5)
        close.isClickable=true
        close.isFocusable=true
        titleBar.addView(title)
        titleBar.addView(close)
        val image=ImageView(this)
        image.scaleType=ImageView.ScaleType.FIT_CENTER
        image.setBackgroundColor(0xFF101010.toInt())
        image.layoutParams=LinearLayout.LayoutParams(previewWidth,previewHeight)
        val label=TextView(this)
        label.text="● WAITING FOR AVIATOR\nLIVE CAPTURE PREVIEW — waiting for first frame\nDrag to move · tap to hide/show preview · pinch to resize"
        label.textSize=overlayTextSize
        label.setTextColor(0xFFFFFFFF.toInt())
        label.setPadding(10,8,10,8)
        panel.addView(titleBar)
        panel.addView(image)
        panel.addView(label)
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.START
        lp.x=overlayPrefs.getInt("x",24).coerceAtLeast(0)
        lp.y=overlayPrefs.getInt("y",80).coerceAtLeast(0)
        close.setOnClickListener{
            try { wm?.removeView(panel) } catch (_:Throwable) {}
            overlayContainer=null
            overlay=null
            preview=null
        }
        panel.setOnTouchListener{_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                touchDownX=e.rawX;touchDownY=e.rawY;touchStartX=lp.x;touchStartY=lp.y;touchMoved=false
                true
            }
            MotionEvent.ACTION_POINTER_DOWN->{
                if(e.pointerCount>=2){
                    pinchStartDistance=pointerDistance(e)
                    pinchStartSize=previewWidth.toFloat()
                }
                true
            }
            MotionEvent.ACTION_MOVE->{
                if(e.pointerCount>=2 && pinchStartDistance>0f){
                    val distance=pointerDistance(e)
                    if(distance>0f){
                        val scale=(distance/pinchStartDistance).coerceIn(0.65f,1.7f)
                        previewWidth=(pinchStartSize*scale).toInt().coerceIn(120,420)
                        previewHeight=(previewWidth*resources.displayMetrics.heightPixels.toFloat()/resources.displayMetrics.widthPixels.toFloat()).toInt().coerceIn(180,760)
                        image.layoutParams=LinearLayout.LayoutParams(previewWidth,previewHeight)
                        previewWidth=previewWidth
                        previewHeight=previewHeight
                        previewBitmap?.let{bmp->
                            val resized=try{Bitmap.createScaledBitmap(bmp,previewWidth,previewHeight,true)}catch(_:Throwable){null}
                            if(resized!=null){previewBitmap=resized;image.setImageBitmap(resized)}
                        }
                        panel.requestLayout()
                        overlayPrefs.edit().putInt("preview_width",previewWidth).putInt("preview_height",previewHeight).apply()
                        touchMoved=true
                    }
                }else if(e.pointerCount==1){
                    val dx=e.rawX-touchDownX;val dy=e.rawY-touchDownY
                    if(kotlin.math.abs(dx)>6 || kotlin.math.abs(dy)>6) touchMoved=true
                    lp.x=(touchStartX+dx.toInt()).coerceAtLeast(0)
                    lp.y=(touchStartY+dy.toInt()).coerceAtLeast(0)
                    wm?.updateViewLayout(panel,lp)
                }
                true
            }
            MotionEvent.ACTION_POINTER_UP->{pinchStartDistance=0f;true}
            MotionEvent.ACTION_UP->{
                if(!touchMoved){
                    val visible=image.visibility==android.view.View.VISIBLE
                    image.visibility=if(visible) android.view.View.GONE else android.view.View.VISIBLE
                    label.text=buildOverlayText(if(recording) "RECORDING ROUND $round" else "WAITING FOR AVIATOR",indicatorPreDetected)
                    if(!visible) label.text=label.text.toString()+"\nLive preview: actual captured screen"
                }
                overlayPrefs.edit().putInt("x",lp.x).putInt("y",lp.y).apply()
                pinchStartDistance=0f
                true
            }
            else->true
        }}
        wm?.addView(panel,lp)
        overlayContainer=panel
        overlay=label
        preview=image
        previewWidth=overlayPrefs.getInt("preview_width",previewWidth).coerceIn(120,420)
        previewHeight=overlayPrefs.getInt("preview_height",previewHeight).coerceIn(180,760)
        image.layoutParams=LinearLayout.LayoutParams(previewWidth,previewHeight)
    }

    private fun pointerDistance(e:MotionEvent):Float{
        if(e.pointerCount<2)return 0f
        val dx=e.getX(0)-e.getX(1)
        val dy=e.getY(0)-e.getY(1)
        return kotlin.math.sqrt(dx*dx+dy*dy)
    }

    private fun notification():Notification{
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("scan","Fly Away Indicator",NotificationManager.IMPORTANCE_LOW))
        val restoreIntent=Intent(this,ScreenMonitorService::class.java).setAction(ACTION_SHOW_OVERLAY)
        val restorePending=PendingIntent.getService(
            this, 701, restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this,"scan")
            .setContentTitle("Fly Away Indicator")
            .setContentText("Monitoring Aviator live screen · tap to show floating display")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(restorePending)
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent:Intent?){
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy(){
        try{finishRound()}catch(_:Throwable){}
        reader?.close();reader=null;projection?.stop();projection=null
        recorder?.release();recorder=null
        lastFrame?.recycle();lastFrame=null
        redReader.close()
        textVerifier.close()
        overlayContainer?.let{wm?.removeView(it)};overlayContainer=null;overlay=null;preview=null
        previewBitmap?.recycle();previewBitmap=null
        thread.quitSafely()
        if(::analysisThread.isInitialized) analysisThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent:Intent?):IBinder?=null
}
