package com.blvckson.flyawayindicator

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
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
        val replayLifecycleEvents = CopyOnWriteArrayList<String>()
        val overlayHiddenAt = CopyOnWriteArrayList<Long>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ScreenMonitorService.ACTION_DEBUG_FRAME) {
                    diagnostics.add(
                        "phase=${intent.getStringExtra("phase")}" +
                            " screenPresent=${intent.getBooleanExtra("screenPresent", false)}" +
                            " live=${intent.getBooleanExtra("visualLive", false)}" +
                            " detector=${intent.getBooleanExtra("detectorLive", false)}" +
                            " ocr=${intent.getStringExtra("ocrStatus")}" +
                            " textAt=${intent.getLongExtra("textObservedAt", 0L)}" +
                            " multPresent=${intent.getBooleanExtra("multiplierPresent", false)}" +
                            " aviator=${intent.getBooleanExtra("aviatorLabelPresent", false)}" +
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
                if (intent?.action == AviatorReplayActivity.ACTION_LIFECYCLE) {
                    replayLifecycleEvents.add(
                        "${intent.getStringExtra("stage")}:${intent.getStringExtra("replayState")}"
                    )
                    return
                }
                if (intent?.action == ScreenMonitorService.ACTION_DEBUG_OVERLAY_HIDDEN) {
                    overlayHiddenAt.add(SystemClock.elapsedRealtime())
                    return
                }
                val status = intent?.getStringExtra(ScreenMonitorService.EXTRA_STATUS) ?: return
                events.add(StatusEvent(status, SystemClock.elapsedRealtime()))
            }
        }

        val statusFilter = IntentFilter(ScreenMonitorService.ACTION_STATUS).apply {
            addAction(ScreenMonitorService.ACTION_DEBUG_FRAME)
            addAction(AviatorReplayActivity.ACTION_LIFECYCLE)
            addAction(ScreenMonitorService.ACTION_DEBUG_OVERLAY_HIDDEN)
        }
        targetContext.registerReceiver(receiver, statusFilter)
        var mainScenario: ActivityScenario<MainActivity>? = null
        var replayScenario: ActivityScenario<AviatorReplayActivity>? = null

        try {
            assertTrue(
                "Overlay permission check failed: package=${targetContext.packageName}, " +
                    "appOpMode=${OverlayPermissionChecker.appOpMode(targetContext)}",
                OverlayPermissionChecker.canDraw(targetContext)
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
            // Keep the real monitor running, but remove its floating debug overlay
            // from this synthetic replay. The panel itself covers the Aviator title
            // and multiplier in the captured image, which can prevent OCR identity
            // confirmation. This command exists only in debuggable builds.
            targetContext.sendBroadcast(
                Intent(ScreenMonitorService.ACTION_DEBUG_HIDE_OVERLAY)
                    .setPackage(targetContext.packageName)
            )
            assertTrue(
                "The debug overlay panel was not removed before replay capture.",
                waitUntil(3_000L) { overlayHiddenAt.isNotEmpty() }
            )
            SystemClock.sleep(250L)

            // Use a dedicated ActivityScenario for the replay screen. The previous
            // approach launched it inside MainActivity's scenario callback; AndroidX
            // could then restore the tracked MainActivity, leaving the emulator on the
            // launcher while the monitor continued capturing an empty screen.
            replayScenario = ActivityScenario.launch<AviatorReplayActivity>(
                Intent(targetContext, AviatorReplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            // Some Android 8 emulator builds show a one-time immersive-mode
            // confirmation above the Activity. Dismiss it if the system retained
            // that prompt from an earlier replay build.
            device.wait(Until.findObject(By.text("GOT IT")), 1_000L)?.click()
            assertTrue(
                "Synthetic replay Activity did not become the resumed foreground Activity.",
                waitUntil(10_000L) {
                    val resumed = device.executeShellCommand(
                        "dumpsys activity activities | grep mResumedActivity"
                    )
                    resumed.contains("mResumedActivity") &&
                        resumed.contains("AviatorReplayActivity")
                }
            )
            val calibrationRequestedAt = SystemClock.elapsedRealtime()
            replayScenario!!.moveToState(Lifecycle.State.RESUMED)
            replayScenario!!.onActivity { it.showCalibrationForTest() }
            val firstGreen = awaitStatus(
                events = events,
                status = "ROUND VISUAL MONITORING — GREEN",
                afterMs = calibrationRequestedAt - 1L,
                timeoutMs = 45_000L
            )
            if (firstGreen == null) {
                // Capture the exact screen and detector telemetry when identity never
                // becomes GREEN. This keeps a setup failure diagnosable from CI instead
                // of reporting only a generic first-GREEN assertion.
                val screenshotSaved = captureFailureScreenshot(device)
                val foreground = try {
                    device.executeShellCommand("dumpsys activity top | head -n 35").trim()
                } catch (failure: Throwable) {
                    "foreground unavailable: ${failure.javaClass.simpleName}"
                }
                throw AssertionError(
                    "The real OCR/visual monitor never recognised the synthetic Aviator screen and entered GREEN. " +
                        "screenshotSaved=$screenshotSaved; currentPackage=${device.currentPackageName}; " +
                        "foreground=$foreground; statuses=${events.takeLast(8).joinToString { it.status }}; " +
                        "diagnostics count=${diagnostics.size}; first=${diagnostics.take(8).joinToString(" | ")}; " +
                        "last=${diagnostics.takeLast(20).joinToString(" | ")}; " +
                        "replayLifecycle=${replayLifecycleEvents.takeLast(12).joinToString(" | ")}"
                )
            }

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
                replayScenario!!.moveToState(Lifecycle.State.RESUMED)
                assertTrue(
                    "${roundCase.name}: replay Activity left the foreground before round start.",
                    waitUntil(3_000L) {
                        device.executeShellCommand(
                            "dumpsys activity activities | grep mResumedActivity"
                        ).contains("AviatorReplayActivity")
                    }
                )
                var roundStartAt = 0L
                replayScenario!!.onActivity {
                    roundStartAt = it.beginRoundForTest(roundCase.durationMs, roundCase.signalAtMs)
                }
                val diagnosticsBeforeRound = diagnostics.size
                val acceptedLiveRound = waitUntil(3_000L) {
                    diagnostics.drop(diagnosticsBeforeRound).any { it.contains("round=true") }
                }
                if (!acceptedLiveRound) {
                    val screenshotSaved = captureFailureScreenshot(device)
                    val replayState = try {
                        var state = "replay Activity unavailable"
                        replayScenario?.onActivity { state = it.debugStateForTest() }
                        state
                    } catch (failure: Throwable) {
                        "replay state unavailable: ${failure.javaClass.simpleName}"
                    }
                    throw AssertionError(
                        "${roundCase.name}: the real monitor did not accept the replayed live round. " +
                            "screenshotSaved=$screenshotSaved; replayState=$replayState; " +
                            "debugFramesSinceStart=${diagnostics.drop(diagnosticsBeforeRound).size}; " +
                            "last=${diagnostics.takeLast(24).joinToString(" | ")}; " +
                            "statuses=${events.takeLast(8).joinToString { it.status }}"
                    )
                }

                val redEvent = awaitStatus(
                    events = events,
                    status = "PRE-FLY-AWAY DETECTED",
                    afterMs = roundStartAt,
                    timeoutMs = roundCase.durationMs + 4_000L
                )
                val replayStateOnFailure = if (redEvent == null) {
                    try {
                        var state = "replay Activity unavailable"
                        replayScenario?.onActivity { state = it.debugStateForTest() }
                        state
                    } catch (failure: Throwable) {
                        "replay state unavailable: ${failure.javaClass.simpleName}"
                    }
                } else ""
                val foregroundOnFailure = if (redEvent == null) {
                    try {
                        device.executeShellCommand("dumpsys activity top | head -n 35").trim()
                    } catch (failure: Throwable) {
                        "foreground unavailable: ${failure.javaClass.simpleName}"
                    }
                } else ""
                val screenshotSaved = if (redEvent == null) {
                    // Preserve the actual emulator display so a failed replay can be
                    // visually diagnosed instead of guessing from detector counters.
                    captureFailureScreenshot(device)
                } else false
                assertNotNull(
                    "${roundCase.name}: no RED warning was produced by the live screen-processing pipeline. " +
                        "replayState=$replayStateOnFailure; foreground=$foregroundOnFailure; " +
                        "screenshotSaved=$screenshotSaved; diagnostics count=${diagnostics.size}: " +
                        "first=${diagnostics.take(6).joinToString(" | ")}; " +
                        "last=${diagnostics.takeLast(12).joinToString(" | ")}; " +
                        "replayLifecycle=${replayLifecycleEvents.takeLast(12).joinToString(" | ")}",
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
                        it.status == "WAITING FOR GAME SCREEN" &&
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
            val leavingScreenAt = SystemClock.elapsedRealtime()
            replayScenario!!.moveToState(Lifecycle.State.RESUMED)
            replayScenario!!.onActivity { it.showNeutralForTest() }
            val blackAfterLeavingScreen = awaitStatus(
                events = events,
                status = "WAITING FOR GAME SCREEN",
                afterMs = leavingScreenAt - 1L,
                timeoutMs = 20_000L
            )
            assertNotNull(
                "The monitor did not reset to BLACK after the game screen was actually left.",
                blackAfterLeavingScreen
            )
        } finally {
            try { replayScenario?.close() } catch (_: Throwable) {}
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

    private fun captureFailureScreenshot(device: UiDevice): Boolean {
        return try {
            device.executeShellCommand("screencap -p /sdcard/aviator-replay-failure.png")
            device.executeShellCommand("ls -l /sdcard/aviator-replay-failure.png")
                .contains("aviator-replay-failure.png")
        } catch (_: Throwable) {
            false
        }
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
