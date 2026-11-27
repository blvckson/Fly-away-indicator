package com.blvckson.flyawayindicator

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Test-only screen source. MediaProjection captures this activity exactly as it
 * captures another app. It draws a controlled Aviator-like scene so the real
 * ML Kit OCR, LiveScreenDetector and ScreenMonitorService pipeline can be exercised
 * without changing production monitoring code.
 *
 * This is synthetic test input, not a substitute for labelled real-game recordings.
 */
class AviatorReplayActivity : Activity() {
    companion object {
        const val ACTION_SHOW_CALIBRATION = "com.blvckson.flyawayindicator.TEST_REPLAY_CALIBRATION"
        const val ACTION_BEGIN_ROUND = "com.blvckson.flyawayindicator.TEST_REPLAY_BEGIN"
        const val ACTION_SHOW_NEUTRAL = "com.blvckson.flyawayindicator.TEST_REPLAY_NEUTRAL"
        const val ACTION_FINISH = "com.blvckson.flyawayindicator.TEST_REPLAY_FINISH"
        const val ACTION_LIFECYCLE = "com.blvckson.flyawayindicator.TEST_REPLAY_LIFECYCLE"
        const val ACTION_ROUND_STARTED = "com.blvckson.flyawayindicator.TEST_REPLAY_ROUND_STARTED"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SIGNAL_AT_MS = "signal_at_ms"
        const val EXTRA_ROUND_STARTED_AT_MS = "round_started_at_ms"
    }

    private lateinit var replayView: AviatorReplayView
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW_CALIBRATION -> replayView.showCalibrationScene()
                ACTION_BEGIN_ROUND -> {
                    val startedAt = replayView.beginReplayRound(
                        intent.getLongExtra(EXTRA_DURATION_MS, 0L),
                        intent.getLongExtra(EXTRA_SIGNAL_AT_MS, Long.MAX_VALUE)
                    )
                    sendBroadcast(
                        Intent(ACTION_ROUND_STARTED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_ROUND_STARTED_AT_MS, startedAt)
                    )
                }
                ACTION_SHOW_NEUTRAL -> replayView.showNeutralScene()
                ACTION_FINISH -> finish()
            }
        }
    }

    /** Direct controls used by instrumentation; avoids broadcast timing races. */
    fun showCalibrationForTest() = replayView.showCalibrationScene()

    fun beginRoundForTest(durationMs: Long, signalAtMs: Long): Long {
        replayView.beginReplayRound(durationMs, signalAtMs)
        return SystemClock.elapsedRealtime()
    }

    fun showNeutralForTest() = replayView.showNeutralScene()

    fun debugStateForTest(): String = replayView.debugState()

    private fun reportLifecycle(stage: String) {
        try {
            sendBroadcast(
                Intent(ACTION_LIFECYCLE)
                    .setPackage(packageName)
                    .putExtra("stage", stage)
                    .putExtra(
                        "replayState",
                        if (::replayView.isInitialized) replayView.debugState() else "not-initialized"
                    )
            )
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        reportLifecycle("RESUMED")
    }

    override fun onPause() {
        reportLifecycle("PAUSED")
        super.onPause()
    }

    override fun onStop() {
        reportLifecycle("STOPPED")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        replayView = AviatorReplayView(this)
        setContentView(replayView)
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_CALIBRATION)
            addAction(ACTION_BEGIN_ROUND)
            addAction(ACTION_SHOW_NEUTRAL)
            addAction(ACTION_FINISH)
        }
        registerReceiver(commandReceiver, filter)
    }

    override fun onDestroy() {
        reportLifecycle("DESTROYED")
        try { unregisterReceiver(commandReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }
}

private class AviatorReplayView(context: android.content.Context) : View(context) {
    private enum class Stage { NEUTRAL, CALIBRATION, FLIGHT, END }

    @Volatile private var stage = Stage.NEUTRAL
    @Volatile private var calibrationStartedAt = SystemClock.elapsedRealtime()
    @Volatile private var roundStartedAt = 0L
    @Volatile private var roundDurationMs = 0L
    @Volatile private var signalAtMs = Long.MAX_VALUE

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val planePath = Path()

    fun showNeutralScene() {
        stage = Stage.NEUTRAL
        postInvalidate()
    }

    fun debugState(): String {
        val elapsed = if (roundStartedAt > 0L)
            (SystemClock.elapsedRealtime() - roundStartedAt).coerceAtLeast(0L) else 0L
        val progress = if (roundDurationMs > 0L)
            (elapsed.toDouble() / roundDurationMs).coerceIn(0.0, 1.0) else 0.0
        return "stage=$stage size=${width}x${height} elapsedMs=$elapsed " +
            "durationMs=$roundDurationMs progress=$progress signalAtMs=$signalAtMs"
    }

    fun showCalibrationScene() {
        calibrationStartedAt = SystemClock.elapsedRealtime()
        stage = Stage.CALIBRATION
        postInvalidate()
    }

    fun beginReplayRound(durationMs: Long, signalAtMs: Long): Long {
        require(durationMs > signalAtMs && signalAtMs >= 0L)
        roundDurationMs = durationMs
        this.signalAtMs = signalAtMs
        roundStartedAt = SystemClock.elapsedRealtime()
        stage = Stage.FLIGHT
        postInvalidate()
        return roundStartedAt
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val now = SystemClock.elapsedRealtime()

        if (stage == Stage.NEUTRAL) {
            canvas.drawColor(Color.rgb(12, 16, 25))
            return
        }

        val elapsed = when (stage) {
            Stage.FLIGHT, Stage.END -> (now - roundStartedAt).coerceAtLeast(0L)
            else -> (now - calibrationStartedAt).coerceAtLeast(0L)
        }
        val ended = stage == Stage.END || (stage == Stage.FLIGHT && elapsed >= roundDurationMs)
        if (stage == Stage.FLIGHT && elapsed >= roundDurationMs) stage = Stage.END

        val progress = if (stage == Stage.CALIBRATION) 0f else
            (elapsed.toFloat() / roundDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val ramp = if (stage == Stage.FLIGHT && elapsed >= signalAtMs) {
            ((elapsed - signalAtMs).toFloat() / 900f).coerceIn(0f, 1f)
        } else 0f
        // A controlled, clearly measurable pre-warning event starts at signalAtMs.
        // The faster rise and repeated panel pulse provide sustained trend/central
        // evidence, rather than requiring a late, barely-visible change to accumulate.
        // This only strengthens the debug replay input; production scoring is untouched.
        val pulse = if (ramp >= 0.70f) {
            (sin((elapsed / 410.0) * 2.0 * PI) * 55.0).toFloat()
        } else 0f
        val intensity = (210f * ramp * ramp + pulse).coerceIn(0f, 220f)

        canvas.drawColor(Color.rgb(9, 14, 24))

        // Flight area and its background deliberately stay below the red-mask
        // threshold, leaving the compact aircraft as the only red component.
        fill.color = Color.rgb(
            (24f + intensity).toInt().coerceIn(0, 255),
            (31f + intensity).toInt().coerceIn(0, 255),
            (48f + intensity).toInt().coerceIn(0, 255)
        )
        val panel = RectF(w * 0.17f, h * 0.14f, w * 0.83f, h * 0.52f)
        canvas.drawRoundRect(panel, 22f, 22f, fill)

        // Stable scene details make the synthetic screen visually structured;
        // the animated brightness/pulse changes are confined to the real flight area.
        stroke.color = Color.argb(125, 210, 225, 240)
        stroke.strokeWidth = 2f
        for (i in 1..4) {
            val y = h * (0.18f + i * 0.065f)
            canvas.drawLine(w * 0.20f, y, w * 0.80f, y, stroke)
        }
        for (i in 1..5) {
            val x = w * (0.20f + i * 0.10f)
            canvas.drawLine(x, h * 0.16f, x, h * 0.50f, stroke)
        }
        stroke.color = Color.argb(220, 220, 235, 250)
        stroke.strokeWidth = 7f
        val wave = Path()
        for (i in 0..30) {
            val x = w * (0.21f + i / 30f * 0.58f)
            val y = h * 0.40f - (h * 0.025f) *
                sin(i / 30f * 2.5 * PI).toFloat() -
                h * 0.010f * ramp * sin(i / 30f * 8.0 * PI).toFloat()
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        canvas.drawPath(wave, stroke)

        text.textSize = h * 0.043f
        text.color = Color.WHITE
        canvas.drawText("AVIATOR", w * 0.50f, h * 0.155f, text)

        val multiplierColor = Color.rgb(
            255,
            (255f - 170f * ramp).toInt().coerceIn(0, 255),
            (255f - 220f * ramp).toInt().coerceIn(0, 255)
        )
        text.color = multiplierColor
        text.textSize = h * (0.075f + 0.020f * ramp)
        canvas.drawText("1.00x", w * 0.50f, h * (0.285f - 0.012f * ramp), text)

        text.color = Color.WHITE
        text.textSize = h * 0.020f
        canvas.drawText("BET", w * 0.31f, h * 0.79f, text)
        canvas.drawText("AUTO", w * 0.50f, h * 0.79f, text)
        // Once FLEW AWAY is displayed, the result screen no longer exposes CASH OUT.
        // This lets the production round-end fallback verify the returned BET/AUTO UI
        // when OCR misses the explicit FLEW AWAY text.
        if (!ended) canvas.drawText("CASH OUT", w * 0.70f, h * 0.79f, text)

        if (!ended && stage != Stage.CALIBRATION) {
            // Keep the synthetic aircraft clear of the top-left floating overlay area.
            // A larger, clean red component makes this controlled test target robust
            // across emulator resolutions while preserving a smooth round trajectory.
            // Give the replay aircraft enough measured travel by each injected
            // pre-warning time to exercise the production flightProgress gate.
            // This is test-only motion; no production thresholds are relaxed.
            val planeX = w * (0.51f + 0.38f * progress + 0.04f * ramp).coerceAtMost(0.86f)
            val planeY = h * (0.66f - 0.70f * progress - 0.04f * ramp).coerceAtLeast(0.15f)
            drawAircraft(canvas, planeX, planeY, w * 0.090f)
        } else if (ended) {
            text.color = Color.WHITE
            text.textSize = h * 0.044f
            canvas.drawText("FLEW AWAY", w * 0.50f, h * 0.39f, text)
        }

        // The real monitor captures the screen on its ImageReader callback. Keep
        // a steady replay cadence instead of relying on the emulator's refresh rate.
        postInvalidateDelayed(70L)
    }

    private fun drawAircraft(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        planePath.reset()
        planePath.moveTo(cx - size * 0.50f, cy)
        planePath.lineTo(cx - size * 0.13f, cy - size * 0.13f)
        planePath.lineTo(cx + size * 0.10f, cy - size * 0.13f)
        planePath.lineTo(cx + size * 0.26f, cy - size * 0.34f)
        planePath.lineTo(cx + size * 0.39f, cy - size * 0.34f)
        planePath.lineTo(cx + size * 0.30f, cy)
        planePath.lineTo(cx + size * 0.39f, cy + size * 0.34f)
        planePath.lineTo(cx + size * 0.26f, cy + size * 0.34f)
        planePath.lineTo(cx + size * 0.10f, cy + size * 0.13f)
        planePath.lineTo(cx - size * 0.13f, cy + size * 0.13f)
        planePath.close()
        fill.color = Color.rgb(255, 45, 35)
        canvas.drawPath(planePath, fill)
    }
}
