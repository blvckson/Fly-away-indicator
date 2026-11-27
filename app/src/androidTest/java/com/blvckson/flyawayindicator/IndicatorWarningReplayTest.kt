package com.blvckson.flyawayindicator

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end emulator replay through MediaProjection -> ImageReader ->
 * ScreenMonitorService.processFrame() -> real OCR/visual detection -> warning state.
 *
 * The test input is a synthetic Aviator-like animated screen, not recorded gameplay.
 * It verifies that the real monitor recognises the screen, transitions GREEN/RED,
 * warns before the replayed round ends, holds RED for its configured window, and
 * permits one new warning after the next FLEW AWAY result while keeping GREEN visible.
 */
@RunWith(AndroidJUnit4::class)
class IndicatorWarningReplayTest {
    private data class StatusEvent(val status: String, val atMs: Long)

    private data class RoundCase(
        val name: String,
        val durationMs: Long,
        val signalAtMs: Long
    )

    @Test
    fun realScreenMonitorWarnsOnShortMediumLongAndExtremelyLongReplayRounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val events = CopyOnWriteArrayList<StatusEvent>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ScreenMonitorService.ACTION_DEBUG_FRAME) {
                    diagnostics.add(
                        "phase=${intent.getStringExtra("phase")}" +
                            " live=${intent.getBooleanExtra("visualLive", false)}" +
                            " detector=${intent.getBooleanExtra("detectorLive", false)}" +
                            " plane=${intent.getBooleanExtra("planePresent", false)}" +
                            " pScore=${"%.2f".format(java.util.Locale.US, intent.getDoubleExtra("planeScore", 0.0))}" +
                            " scene=${"%.2f".format(java.util.Locale.US, intent.getDoubleExtra("sceneScore", 0.0))}" +
                            " multVisual=${"%.2f".format(java.util.Locale.US, intent.getDoubleExtra("multiplierVisualScore", 0.0))}" +
                            " multSamples=${intent.getIntExtra("multiplierSamples", 0)}" +
                            " airBehaviour=${"%.2f".format(java.util.Locale.US, intent.getDoubleExtra("aircraftBehaviour", 0.0))}" +
                            " text=${intent.getBooleanExtra("textVerified", false)}" +
                            " bounds=${intent.getBooleanExtra("multiplierBounds", false)}" +
                            " round=${intent.getBooleanExtra("roundActive", false)}" +
                            " elapsed=${intent.getLongExtra("roundElapsedMs", 0L)}" +
                            " frames=${intent.getIntExtra("indicatorFrames", 0)}" +
                            " progress=${"%.3f".format(java.util.Locale.US, intent.getDoubleExtra("flightProgress", 0.0))}" +
                            " signals=${intent.getIntExtra("independentSignals", 0)}" +
                            " pre=${intent.getBooleanExtra("preEvidence", false)}" +
                            " hold=${intent.getIntExtra("preHold", 0)}" +
                            " skip=${intent.getBooleanExtra("reacquisitionSkip", false)}"
                    )
                    return
                }
                val status = intent?.getStringExtra(ScreenMonitorService.EXTRA_STATUS) ?: return
                events.add(StatusEvent(status, SystemClock.elapsedRealtime()))
            }
        }

        val statusFilter = IntentFilter(ScreenMonitorService.ACTION_STATUS).apply {
            addAction(ScreenMonitorService.ACTION_DEBUG_FRAME)
        }
        targetContext.registerReceiver(receiver, statusFilter)
        var mainScenario: ActivityScenario<MainActivity>? = null
        var replayActivity: AviatorReplayActivity? = null

        try {
            assertTrue(
                "Overlay permission must be granted by the emulator workflow.",
                Settings.canDrawOverlays(targetContext)
            )

            // Start the actual monitor and accept the real Android MediaProjection dialog.
            mainScenario = ActivityScenario.launch(MainActivity::class.java)
            mainScenario.onActivity { activity ->
                activity.findViewById<android.widget.Button>(R.id.start).performClick()
            }

            val consentButton: UiObject2? =
                device.wait(Until.findObject(By.res("android", "button1")), 15_000L)
                    ?: device.wait(Until.findObject(By.textContains("Start now")), 5_000L)
                    ?: device.wait(Until.findObject(By.textContains("START NOW")), 5_000L)
            assertNotNull("MediaProjection consent dialog did not appear.", consentButton)
            consentButton!!.click()

            val activityManager =
                targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            assertTrue(
                "ScreenMonitorService did not start after MediaProjection consent.",
                waitUntil(20_000L) {
                    @Suppress("DEPRECATION")
                    activityManager.getRunningServices(100).any {
                        it.service.className == ScreenMonitorService::class.java.name
                    }
                }
            )
            mainScenario.close()
            mainScenario = null

            // This activity belongs to the instrumentation APK. The target service
            // still captures it from the physical emulator display through MediaProjection.
            val replayIntent = Intent(targetContext, AviatorReplayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            replayActivity =
                instrumentation.startActivitySync(replayIntent) as AviatorReplayActivity
            replayActivity!!.showCalibrationScene()
            val firstGreen = awaitStatus(
                events = events,
                status = "ROUND VISUAL MONITORING — GREEN",
                afterMs = SystemClock.elapsedRealtime(),
                timeoutMs = 45_000L
            )
            assertNotNull(
                "The real OCR/visual monitor never recognised the synthetic Aviator screen and entered GREEN.",
                firstGreen
            )

            val durationProfiles = listOf(
                RoundCase("short", 9_000L, 4_800L),
                RoundCase("medium", 15_000L, 11_000L),
                RoundCase("long", 27_000L, 21_000L),
                RoundCase("extremely-long", 42_000L, 35_000L)
            )
            // Repeat each duration three times. One pass per duration is not a
            // useful consistency sample; repeated rounds exercise lock release,
            // inter-round GREEN persistence, and warning re-arming.
            val cases = (1..3).flatMap { repetition ->
                durationProfiles.map { profile ->
                    profile.copy(name = "${profile.name}-${repetition}")
                }
            }

            for ((index, roundCase) in cases.withIndex()) {
                val redCountBefore = events.count { it.status == "PRE-FLY-AWAY DETECTED" }
                val roundStartAt = SystemClock.elapsedRealtime()
                replayActivity!!.beginReplayRound(roundCase.durationMs, roundCase.signalAtMs)

                val redEvent = awaitStatus(
                    events = events,
                    status = "PRE-FLY-AWAY DETECTED",
                    afterMs = roundStartAt,
                    timeoutMs = roundCase.durationMs + 4_000L
                )
                assertNotNull(
                    "${roundCase.name}: no RED warning was produced by the live screen-processing pipeline. " +
                        "Latest diagnostics: ${diagnostics.takeLast(10).joinToString(" | ")}",
                    redEvent
                )

                val measuredSignalDelay = redEvent!!.atMs - (roundStartAt + roundCase.signalAtMs)
                val measuredRoundElapsed = redEvent.atMs - roundStartAt
                val warningLeadTime = roundCase.durationMs - measuredRoundElapsed

                assertTrue(
                    "${roundCase.name}: RED appeared before the injected pre-fly signal. " +
                        "signalDelay=${measuredSignalDelay}ms",
                    measuredSignalDelay >= 0L
                )
                assertTrue(
                    "${roundCase.name}: RED was too late after the injected signal. " +
                        "signalDelay=${measuredSignalDelay}ms (limit 4000ms)",
                    measuredSignalDelay <= 4_000L
                )
                assertTrue(
                    "${roundCase.name}: RED did not precede the replayed fly-away by at least 500ms. " +
                        "lead=${warningLeadTime}ms",
                    warningLeadTime >= 500L
                )

                val greenAgain = awaitStatus(
                    events = events,
                    status = "ROUND VISUAL MONITORING — GREEN",
                    afterMs = redEvent.atMs,
                    timeoutMs = 5_000L
                )
                assertNotNull(
                    "${roundCase.name}: RED did not return to GREEN after its alert window.",
                    greenAgain
                )
                assertTrue(
                    "${roundCase.name}: RED returned to GREEN before the configured 1.2-second alert window.",
                    greenAgain!!.atMs - redEvent.atMs >= 1_000L
                )

                // Keep the same Aviator screen visible through FLEW AWAY and the
                // following inter-round gap. GREEN must not disappear to BLACK.
                val endPlusHoldAt = roundStartAt + roundCase.durationMs + 2_300L
                val remainingAfterEnd = (endPlusHoldAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                SystemClock.sleep(remainingAfterEnd)
                val redCountAfter = events.count { it.status == "PRE-FLY-AWAY DETECTED" }
                assertTrue(
                    "${roundCase.name}: more than one RED warning was issued for a single round.",
                    redCountAfter - redCountBefore == 1
                )
                assertTrue(
                    "${roundCase.name}: the screen session incorrectly returned to BLACK between rounds.",
                    events.none {
                        it.status == "WAITING FOR AVIATOR" &&
                            it.atMs in roundStartAt..(roundStartAt + roundCase.durationMs + 2_300L)
                    }
                )

                System.out.println(
                    "AVIATOR_REPLAY_RESULT name=${roundCase.name}" +
                        " signalDelayMs=${measuredSignalDelay}" +
                        " redAtRoundMs=${measuredRoundElapsed}" +
                        " warningLeadMs=${warningLeadTime}" +
                        " redCount=${redCountAfter - redCountBefore}" +
                        " greenReturned=true"
                )

                // FLEW AWAY remains on screen for the inter-round OCR/end detector.
                // Begin the next live screen without leaving the Aviator screen session.
                if (index < cases.lastIndex) {
                    SystemClock.sleep(2_300L)
                }
            }

            // Leave the Aviator screen and verify that BLACK is reserved for a
            // genuinely absent screen, not the gap between its rounds.
            replayActivity!!.showNeutralScene()
            val blackAfterLeavingScreen = awaitStatus(
                events = events,
                status = "WAITING FOR AVIATOR",
                afterMs = SystemClock.elapsedRealtime(),
                timeoutMs = 20_000L
            )
            assertNotNull(
                "The monitor did not reset to BLACK after the Aviator screen was actually left.",
                blackAfterLeavingScreen
            )
        } finally {
            try {
                replayActivity?.runOnUiThread { replayActivity?.finish() }
            } catch (_: Throwable) {}
            try { mainScenario?.close() } catch (_: Throwable) {}
            try { targetContext.stopService(Intent(targetContext, ScreenMonitorService::class.java)) } catch (_: Throwable) {}
            try { targetContext.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    private fun awaitStatus(
        events: List<StatusEvent>,
        status: String,
        afterMs: Long,
        timeoutMs: Long
    ): StatusEvent? {
        var found: StatusEvent? = null
        val completed = waitUntil(timeoutMs) {
            found = events.firstOrNull { it.status == status && it.atMs > afterMs }
            found != null
        }
        return if (completed) found else null
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100L)
        }
        return condition()
    }
}
