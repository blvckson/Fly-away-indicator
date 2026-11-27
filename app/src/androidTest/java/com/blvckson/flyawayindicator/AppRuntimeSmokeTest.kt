package com.blvckson.flyawayindicator

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime smoke tests on an Android 8.1 emulator.
 *
 * These tests exercise the real Activity, system MediaProjection consent dialog,
 * foreground Service, overlay permission path and ImageReader frame-capture startup.
 * They do not claim to validate Aviator detection or warning timing without labelled
 * real-round footage.
 */
@RunWith(AndroidJUnit4::class)
class AppRuntimeSmokeTest {

    @Test
    fun homeScreenRendersPrimaryControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        try {
            scenario.onActivity { activity ->
                assertEquals("Fly Away Indicator", activity.findViewById<TextView>(R.id.title).text)
                assertTrue(activity.findViewById<Button>(R.id.start).isShown)
                assertTrue(activity.findViewById<Button>(R.id.stop).isShown)
                assertTrue(activity.findViewById<Button>(R.id.analysis).isShown)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun stopButtonIsSafeBeforeMonitoringHasStarted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        try {
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.stop).performClick()
                assertEquals(
                    "Monitor stopped.",
                    activity.findViewById<TextView>(R.id.status).text.toString()
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun mediaProjectionConsentStartsForegroundServiceAndScreenCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "The emulator workflow must grant SYSTEM_ALERT_WINDOW before this test.",
            Settings.canDrawOverlays(context)
        )

        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

        try {
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.start).performClick()
            }

            // Android 8.1 displays MediaProjection consent as a system dialog.
            // Prefer its standard positive-button resource; use visible text as a
            // fallback for emulator image variations.
            val consentButton: UiObject2? =
                device.wait(Until.findObject(By.res("android", "button1")), 15_000L)
                    ?: device.wait(Until.findObject(By.textContains("Start now")), 5_000L)
                    ?: device.wait(Until.findObject(By.textContains("START NOW")), 5_000L)
            assertNotNull("MediaProjection consent dialog did not appear.", consentButton)
            consentButton!!.click()

            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            assertTrue(
                "ScreenMonitorService did not remain running after MediaProjection consent.",
                waitUntil(20_000L) {
                    @Suppress("DEPRECATION")
                    activityManager.getRunningServices(100).any {
                        it.service.className == ScreenMonitorService::class.java.name
                    }
                }
            )

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertTrue(
                "Foreground-service notification was not posted.",
                waitUntil(5_000L) {
                    notificationManager.activeNotifications.any { it.id == 7 }
                }
            )

            // A WAITING FOR GAME SCREEN status is published by processFrame(). Seeing it
            // proves the emulator's MediaProjection -> VirtualDisplay -> ImageReader
            // callback reached the app's real frame-processing loop.
            assertTrue(
                "No captured frame reached the monitor's processFrame() status path.",
                device.wait(Until.hasObject(By.textContains("WAITING FOR GAME SCREEN")), 20_000L)
            )
        } finally {
            context.stopService(Intent(context, ScreenMonitorService::class.java))
            scenario.close()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(200L)
        }
        return condition()
    }
}
