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
        const val ACTION_DEBUG_FRAME="com.blvckson.flyawayindicator.DEBUG_FRAME"
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
    private val multiplierBehaviourTracker=MultiplierBehaviourTracker()
    private val indicatorPhaseMachine=IndicatorPhaseMachine()
    private val indicatorRoundEndDetector=IndicatorRoundEndDetector()
    private val indicatorReacquisitionGate=IndicatorReacquisitionGate(requiredStableFrames=2)
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
    private var indicatorPreScore=0.0
    private var indicatorPreHold=0
    private var indicatorPreDetected=false
    private var indicatorFrames=0
    private var indicatorBaselineScore=0.0
    private var indicatorLastFrameAtMs=0L
    private var indicatorRoundStartedAtMs=0L
    private var indicatorWasLongRound=false
    private var indicatorStartPlaneX=Float.NaN
    private var indicatorStartPlaneY=Float.NaN
    private var indicatorFlightProgress=0.0
    private var lastIndicatorSignalReport="Signal indices /100 — awaiting first live-round sample."
    private var latchedRedSignalReport=""
    // Debug-build-only telemetry for calibrating the synthetic warning replay.
    private var lastDebugFrameBroadcastAtMs=0L

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
        val textEvidence=textVerifier.evidence()
        val multiplierAppearance=if(multiplierBehaviourTracker.needsSample(textEvidence.observedAtMillis)) {
            MultiplierAppearanceSampler.sample(frame,textEvidence.multiplierBounds)
        } else null
        var multiplierBehaviour=multiplierBehaviourTracker.observe(
            multiplierAppearance,textEvidence.observedAtMillis
        )
        val state=detector.inspect(
            frame,textEvidence,multiplierBehaviour.samples,multiplierBehaviour.stabilityScore
        )
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

        val central=centralChange(lastFrame,frame)
        val planePresent = !state.planeX.isNaN() && !state.planeY.isNaN()
        // The detector's verified identity latch survives occasional OCR misses;
        // ongoing live status is then sustained by aircraft and scene evidence.
        val visualLive = state.isLive &&
            multiplierBehaviour.samples >= 2 &&
            state.sceneScore >= 0.16 &&
            state.planeScore >= 0.08 &&
            state.multiplierVisualScore >= 0.07

        // The aircraft/game detector already confirms consecutive live frames.
        // Use one phase controller so no second counter or OCR cooldown delays
        // BLACK -> GREEN, and so stale end-text cannot block the following round.
        val phaseNowMs=SystemClock.elapsedRealtime()
        val wallNowMs=System.currentTimeMillis()
        val previousIndicatorPhase=indicatorPhaseMachine.phase
        val previousRoundStartGeneration=indicatorPhaseMachine.roundStartGeneration
        val previousRoundEndGeneration=indicatorPhaseMachine.roundEndGeneration
        val wasRoundActive=indicatorPhaseMachine.isRoundActive
        val wasAviatorScreenActive=indicatorPhaseMachine.isAviatorScreenActive
        if(wasRoundActive){
            indicatorRoundEndDetector.observeFrame(
                phaseNowMs,wallNowMs,planePresent,textEvidence
            )
        }
        val fallbackEndConfirmed=wasRoundActive && indicatorRoundEndDetector.isConfirmed(
            phaseNowMs,wallNowMs,textEvidence
        )
        val endConfirmed=textEvidence.endConfirmed || fallbackEndConfirmed
        val aviatorScreenPresent=visualLive || endConfirmed ||
            textVerifier.isAviatorScreenVisible(wallNowMs)
        indicatorPhaseMachine.observeScreenPresence(aviatorScreenPresent,nowMs=phaseNowMs)
        val screenSessionEnded=wasAviatorScreenActive &&
            !indicatorPhaseMachine.isAviatorScreenActive
        indicatorPhaseMachine.observeLive(visualLive,endConfirmed,nowMs=phaseNowMs)
        val roundStarted=indicatorPhaseMachine.roundStartGeneration!=previousRoundStartGeneration
        val roundEnded=indicatorPhaseMachine.roundEndGeneration!=previousRoundEndGeneration
        val currentIndicatorPhase=indicatorPhaseMachine.phase

        if(screenSessionEnded){
            // This is a confirmed screen-session expiry after the absence grace,
            // not an individual missed frame. Clear session-scoped trackers so the
            // next Aviator visit starts with a clean identity and round baseline.
            indicatorRoundEndDetector.reset()
            indicatorReacquisitionGate.reset()
            indicatorRoundStartedAtMs=0L
            indicatorWasLongRound=false
            indicatorPreScore=0.0
            indicatorPreHold=0
            indicatorPreDetected=false
            indicatorFrames=0
            indicatorLastFrameAtMs=0L
            indicatorBaselineScore=0.0
            indicatorStartPlaneX=Float.NaN
            indicatorStartPlaneY=Float.NaN
            indicatorFlightProgress=0.0
            lastIndicatorSignalReport="Signal indices /100 — waiting for Aviator screen."
            latchedRedSignalReport=""
            indicatorBaseline.clear()
            indicatorChanges.clear()
            recentChanges.clear()
            lastMove=0.0
            lastPlaneX=Float.NaN
            lastPlaneY=Float.NaN
            detector.resetTracking()
            multiplierBehaviourTracker.reset()
        }

        if(roundStarted){
            // Start fresh lifecycle helpers only for a genuinely new round. Seed
            // aircraft presence from this accepted live frame; do not reset on misses.
            indicatorRoundEndDetector.beginRound(planePresent,textEvidence.observedAtMillis)
            indicatorReacquisitionGate.reset()
            indicatorRoundStartedAtMs=phaseNowMs
            indicatorWasLongRound=false
            indicatorPreScore=0.0
            indicatorPreHold=0
            indicatorPreDetected=false
            indicatorFrames=0
            indicatorLastFrameAtMs=0L
            indicatorBaselineScore=0.0
            indicatorStartPlaneX=if(planePresent) state.planeX else Float.NaN
            indicatorStartPlaneY=if(planePresent) state.planeY else Float.NaN
            indicatorFlightProgress=0.0
            lastIndicatorSignalReport="Signal indices /100 — collecting opening samples."
            latchedRedSignalReport=""
            indicatorBaseline.clear()
            indicatorChanges.clear()
            multiplierBehaviour=multiplierBehaviourTracker.startRoundBaseline()
            recentChanges.clear()
            lastMove=0.0
            lastPlaneX=Float.NaN
            lastPlaneY=Float.NaN
        }else if(previousIndicatorPhase==IndicatorPhase.RED &&
            currentIndicatorPhase==IndicatorPhase.GREEN && !roundEnded){
            // The 1.2-second alert has returned to GREEN, but this is still the same
            // round. Keep its baseline and one-alert lock until an end marker.
            indicatorPreScore=0.0
            indicatorPreHold=0
            indicatorPreDetected=false
        }else if(previousIndicatorPhase==IndicatorPhase.GREEN &&
            currentIndicatorPhase==IndicatorPhase.BLACK && !roundEnded){
            // The Aviator screen-presence lease and absence grace have both expired.
            // The session cleanup above has reset its round-scoped trackers.
            indicatorPreHold=0
            indicatorLastFrameAtMs=0L
            indicatorPreDetected=false
        }else if(roundEnded){
            // OCR FLEW AWAY or the multi-signal fallback releases the round lock.
            // Reset only now, never on a brief or isolated detector dropout.
            indicatorRoundEndDetector.reset()
            indicatorReacquisitionGate.reset()
            indicatorRoundStartedAtMs=0L
            indicatorWasLongRound=false
            indicatorPreScore=0.0
            indicatorPreHold=0
            indicatorPreDetected=false
            indicatorFrames=0
            indicatorLastFrameAtMs=0L
            indicatorBaselineScore=0.0
            indicatorStartPlaneX=Float.NaN
            indicatorStartPlaneY=Float.NaN
            indicatorFlightProgress=0.0
            lastIndicatorSignalReport="Signal indices /100 — waiting for the next round."
            latchedRedSignalReport=""
            indicatorBaseline.clear()
            indicatorChanges.clear()
            recentChanges.clear()
            lastMove=0.0
            lastPlaneX=Float.NaN
            lastPlaneY=Float.NaN
            detector.resetTracking()
            multiplierBehaviourTracker.reset()
        }
        val roundElapsedMs=if(indicatorRoundStartedAtMs>0L)
            (phaseNowMs-indicatorRoundStartedAtMs).coerceAtLeast(0L) else 0L

        val skipForReacquisition=indicatorReacquisitionGate.shouldSkipScoring(
            roundActive=indicatorPhaseMachine.isRoundActive,
            phase=currentIndicatorPhase,
            visualLive=visualLive
        )
        val detectorEvidenceLost=indicatorPhaseMachine.isRoundActive &&
            currentIndicatorPhase!=IndicatorPhase.RED && !visualLive
        if(detectorEvidenceLost){
            // Invalidate temporal evidence after even a one-frame miss. Preserve the
            // per-round baseline, opening plane position and elapsed flight clock.
            indicatorPreHold=0
            indicatorPreScore=0.0
            indicatorLastFrameAtMs=0L
            indicatorChanges.clear()
            indicatorPreDetected=false
            lastMove=0.0
            lastPlaneX=Float.NaN
            lastPlaneY=Float.NaN
        }

        indicatorRoundActive=indicatorPhaseMachine.isRoundActive
        indicatorPreDetected=currentIndicatorPhase==IndicatorPhase.RED
        var debugTrend=0.5
        var debugCentral=central
        var debugAircraft=state.planeScore
        var debugMovement=state.movementScore
        var debugMultiplier=multiplierBehaviour.changeScore
        var debugCandidate=0.0
        var debugEvidence=false
        var debugIndependentSignals=0

        if(indicatorPhaseMachine.phase==IndicatorPhase.GREEN && visualLive && !skipForReacquisition){
            indicatorFrames++
            // Keep trend samples strictly within this confirmed round. The opening
            // frame is retained instead of being discarded by the BLACK -> GREEN reset.
            // Ignore the lobby-to-live screen cut in the trend and central-change
            // channels; it is a round-start transition, not a pre-fly signal.
            val roundJustStarted = roundStarted
            val roundResumed = !roundStarted &&
                previousIndicatorPhase==IndicatorPhase.BLACK &&
                currentIndicatorPhase==IndicatorPhase.GREEN
            val skipTransitionFrame = roundJustStarted || roundResumed
            val indicatorCentralChange=if(skipTransitionFrame) 0.0 else central
            // Ignore both the lobby-to-live cut and a detector-reacquisition cut.
            // Neither is a within-round pre-fly-away visual trend.
            if(!skipTransitionFrame) indicatorChanges.addLast(change)
            while(indicatorChanges.size>10)indicatorChanges.removeFirst()

            // Use monotonic capture cadence, not wall-clock time. This keeps the
            // confirmation responsive on slower devices without assuming a fixed FPS.
            val frameTimeMs=phaseNowMs
            val elapsedSincePrevious=if(indicatorLastFrameAtMs==0L) 50L
                else (frameTimeMs-indicatorLastFrameAtMs).coerceAtLeast(1L)
            if(indicatorLastFrameAtMs>0L && elapsedSincePrevious>700L){
                indicatorPreHold=0
            }
            val frameDeltaMs=elapsedSincePrevious.coerceIn(16L,500L)
            indicatorLastFrameAtMs=frameTimeMs

            // Keep Build 74's adaptive, multi-signal model. Use the recent per-round
            // trend and a robust four-sample opening baseline so short rounds do not
            // spend too long calibrating before the late visual change is evaluated.
            val indicatorTrend=if(indicatorChanges.size>=4) changeTrend(indicatorChanges) else 0.5
            val aircraftSignal=(state.planeScore*0.65+
                state.aircraftBehaviourScore*0.35).coerceIn(0.0,1.0)
            val movementSignal=(lastMove*0.55+
                state.movementScore*0.45).coerceIn(0.0,1.0)
            val signals=PreFlySignals(
                trend=indicatorTrend,
                central=indicatorCentralChange,
                plane=aircraftSignal,
                movement=movementSignal,
                multiplier=multiplierBehaviour.changeScore
            )
            val candidate=PreFlyTiming.candidate(signals)
            debugTrend=indicatorTrend
            debugCentral=indicatorCentralChange
            debugAircraft=aircraftSignal
            debugMovement=movementSignal
            debugMultiplier=multiplierBehaviour.changeScore
            debugCandidate=candidate

            // Two opening observations allow very short rounds to be evaluated
            // earlier. A two-sample baseline uses a stricter, three-signal path.
            if(indicatorBaseline.size<2){
                indicatorBaseline.addLast(candidate)
                indicatorBaselineScore=PreFlyTiming.medianBaseline(indicatorBaseline.toList())
            }
            val baselineSampleCount=indicatorBaseline.size
            val baselineReady=indicatorFrames>=3 && baselineSampleCount>=2

            // Time-aware smoothing gives slower frames more influence without
            // imposing a separate fixed-duration confirmation window.
            val smoothingAlpha=(1.0-kotlin.math.exp(-frameDeltaMs.toDouble()/120.0))
                .coerceIn(0.22,0.68)
            indicatorPreScore=if(indicatorFrames==1) candidate
                else indicatorPreScore*(1.0-smoothingAlpha)+candidate*smoothingAlpha

            val scores=PreFlyTiming.signalScores(signals)
            lastIndicatorSignalReport=scores.diagnosticLine()
            // Measure actual aircraft travel from this round's first confirmed
            // plane position. Normal rounds need more progress than the short-round
            // fallback, preventing ordinary take-off movement from arming RED.
            if(planePresent && indicatorStartPlaneX.isNaN()){
                indicatorStartPlaneX=state.planeX
                indicatorStartPlaneY=state.planeY
            }
            if(planePresent && !indicatorStartPlaneX.isNaN() && !indicatorStartPlaneY.isNaN()){
                val dx=(state.planeX-indicatorStartPlaneX).toDouble()
                val dy=(state.planeY-indicatorStartPlaneY).toDouble()
                val screenDiagonal=sqrt(w.toDouble()*w.toDouble()+h.toDouble()*h.toDouble()).coerceAtLeast(1.0)
                val progress=(sqrt(dx*dx+dy*dy)/screenDiagonal).coerceIn(0.0,1.0)
                indicatorFlightProgress=max(indicatorFlightProgress,progress)
            }
            val independentSignals=PreFlyTiming.evidenceCount(signals)
            debugIndependentSignals=independentSignals
            val preEvidence=!roundResumed && baselineReady && PreFlyTiming.isEvidence(
                signals=signals,
                candidate=candidate,
                smoothedScore=indicatorPreScore,
                baselineScore=indicatorBaselineScore,
                baselineSampleCount=baselineSampleCount,
                roundFrameCount=indicatorFrames,
                flightProgress=indicatorFlightProgress,
                roundElapsedMs=roundElapsedMs
            )
            debugEvidence=preEvidence
            val isLongRound=roundElapsedMs>=PreFlyTiming.LONG_ROUND_THRESHOLD_MS
            if(isLongRound && !indicatorWasLongRound){
                // Do not carry pre-boundary short/medium evidence into the
                // independent long-round three-frame confirmation streak.
                indicatorPreHold=0
            }
            indicatorWasLongRound=isLongRound
            indicatorPreHold=if(isLongRound)
                PreFlyTiming.updatedLongRoundHold(indicatorPreHold,preEvidence)
            else
                PreFlyTiming.updatedHold(indicatorPreHold,preEvidence)

            // Update a rolling normal-behaviour baseline during quiet observations.
            // Freeze it whenever the score rises or multiple channels hint at change.
            if(!roundResumed && baselineReady && !preEvidence && PreFlyTiming.shouldLearnBaseline(
                candidate=candidate,
                baselineScore=indicatorBaselineScore,
                signals=signals
            )){
                indicatorBaseline.addLast(candidate)
                while(indicatorBaseline.size>12)indicatorBaseline.removeFirst()
                indicatorBaselineScore=PreFlyTiming.medianBaseline(indicatorBaseline.toList())
            }

            // Strong three-channel evidence can confirm in two frames even at normal
            // capture cadence; otherwise use three frames, or two on slow capture.
            val requiredConfirmations=PreFlyTiming.requiredConfirmations(
                frameDeltaMs,independentSignals,longRound=isLongRound
            )
            if(indicatorPreHold>=requiredConfirmations &&
                indicatorPhaseMachine.phase==IndicatorPhase.GREEN){
                val phaseBeforeAlert=indicatorPhaseMachine.phase
                indicatorPhaseMachine.markPreFlyAway(phaseNowMs)
                if(phaseBeforeAlert==IndicatorPhase.GREEN &&
                    indicatorPhaseMachine.phase==IndicatorPhase.RED){
                    indicatorPreDetected=true
                    latchedRedSignalReport=lastIndicatorSignalReport
                    // Vibrate only for the single permitted GREEN -> RED transition.
                    vibrateForPreFlyAway()
                }
            }
        }else if(indicatorPhaseMachine.phase==IndicatorPhase.GREEN){
            // Do not accumulate evidence during a miss or either fresh reacquisition
            // frame. The round clock and learned baseline remain intact.
            indicatorPreHold=0
            indicatorLastFrameAtMs=0L
            if(skipForReacquisition) indicatorChanges.clear()
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

        if((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            phaseNowMs-lastDebugFrameBroadcastAtMs>=250L){
            lastDebugFrameBroadcastAtMs=phaseNowMs
            sendBroadcast(
                Intent(ACTION_DEBUG_FRAME).setPackage(packageName)
                    .putExtra("phase",indicatorPhaseMachine.phase.name)
                    .putExtra("screenPresent",aviatorScreenPresent)
                    .putExtra("textVerified",textEvidence.verified)
                    .putExtra("ocrStatus",textVerifier.status())
                    .putExtra("textObservedAt",textEvidence.observedAtMillis)
                    .putExtra("multiplierPresent",textEvidence.multiplierPresent)
                    .putExtra("aviatorLabelPresent",textEvidence.aviatorLabelPresent)
                    .putExtra("multiplierBounds",textEvidence.multiplierBounds!=null)
                    .putExtra("detectorLive",state.isLive)
                    .putExtra("visualLive",visualLive)
                    .putExtra("planePresent",planePresent)
                    .putExtra("planeScore",state.planeScore)
                    .putExtra("sceneScore",state.sceneScore)
                    .putExtra("multiplierVisualScore",state.multiplierVisualScore)
                    .putExtra("multiplierSamples",multiplierBehaviour.samples)
                    .putExtra("aircraftBehaviour",state.aircraftBehaviourScore)
                    .putExtra("movement",state.movementScore)
                    .putExtra("roundActive",indicatorPhaseMachine.isRoundActive)
                    .putExtra("roundElapsedMs",roundElapsedMs)
                    .putExtra("indicatorFrames",indicatorFrames)
                    .putExtra("baselineSamples",indicatorBaseline.size)
                    .putExtra("flightProgress",indicatorFlightProgress)
                    .putExtra("trend",debugTrend)
                    .putExtra("central",debugCentral)
                    .putExtra("aircraftSignal",debugAircraft)
                    .putExtra("movementSignal",debugMovement)
                    .putExtra("multiplierSignal",debugMultiplier)
                    .putExtra("candidate",debugCandidate)
                    .putExtra("baselineScore",indicatorBaselineScore)
                    .putExtra("independentSignals",debugIndependentSignals)
                    .putExtra("preEvidence",debugEvidence)
                    .putExtra("preHold",indicatorPreHold)
                    .putExtra("reacquisitionSkip",skipForReacquisition)
            )
        }

        when(indicatorPhaseMachine.phase){
            IndicatorPhase.BLACK -> publish("WAITING FOR AVIATOR",false,
                "Indicator is black. Waiting for verified live-round evidence.")
            IndicatorPhase.GREEN -> publish("ROUND VISUAL MONITORING — GREEN",false,
                "Aviator live round confirmed. Tracking visual behaviour for the pre-fly-away change.")
            IndicatorPhase.RED -> publish("PRE-FLY-AWAY DETECTED",true,
                "Pre-fly-away visual/behavioural transition detected. Red indicator ON. ${latchedRedSignalReport.ifBlank { lastIndicatorSignalReport }}")
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
        val a=history.toList().takeLast(8)
        if(a.size<2)return 0.5
        val split=max(1,a.size/2)
        val early=a.take(split).average()
        val late=a.drop(split).average()
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
            overlay?.setBackgroundColor(when(indicatorPhaseMachine.phase){
                IndicatorPhase.RED -> 0xFFE53935.toInt()
                IndicatorPhase.GREEN -> 0xFF2E7D32.toInt()
                IndicatorPhase.BLACK -> 0xE6202124.toInt()
            })
        }
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS,status).putExtra(EXTRA_STATEMENT,statement).putExtra(EXTRA_RED,pre).putExtra(EXTRA_ROUNDS,store.all().size))
    }

    private fun vibrateForPreFlyAway(){
        try{
            val vibrator=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            }else{
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
                vibrator.vibrate(VibrationEffect.createOneShot(450L,VibrationEffect.DEFAULT_AMPLITUDE))
            }else{
                @Suppress("DEPRECATION")
                vibrator.vibrate(450L)
            }
        }catch(_:Throwable){
            // Keep the visual red alert even if vibration is unavailable.
        }
    }

    private fun buildOverlayText(status:String,pre:Boolean):String {
        val marker=when(indicatorPhaseMachine.phase){
            IndicatorPhase.BLACK -> "● ⚫ "
            IndicatorPhase.GREEN -> "● 🟢 "
            IndicatorPhase.RED -> "● 🔴 "
        }
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
