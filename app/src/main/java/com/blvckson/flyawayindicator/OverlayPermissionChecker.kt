package com.blvckson.flyawayindicator

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Checks draw-over-other-apps access.
 *
 * On some Android 8.0/8.1 builds Settings.canDrawOverlays() can report a stale
 * denial even while AppOpsManager has SYSTEM_ALERT_WINDOW set to MODE_ALLOWED.
 * Checking the package-specific app-op directly is a safe compatibility fallback.
 */
object OverlayPermissionChecker {
    @Suppress("DEPRECATION")
    fun appOpMode(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return AppOpsManager.MODE_ALLOWED
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return null
        return try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                context.packageName
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun canDraw(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(context)) return true
        return appOpMode(context) == AppOpsManager.MODE_ALLOWED
    }
}
