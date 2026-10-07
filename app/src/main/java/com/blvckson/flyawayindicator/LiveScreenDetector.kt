package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class LiveScreenState(
    val isLive: Boolean,
    val planeScore: Double,
    val multiplierVisualScore: Double,
    val sceneScore: Double
)

class LiveScreenDetector {
    private var lastPlaneX = Float.NaN
    private var lastPlaneY = Float.NaN
    private var stableHits = 0
    private var missHits = 0

    fun inspect(frame: Bitmap): LiveScreenState {
        val w = frame.width
        val h = frame.height
        val x0 = (w * 0.05).toInt()
        val x1 = (w * 0.95).toInt()
        val y0 = (h * 0.08).toInt()
        val y1 = (h * 0.72).toInt()

        var red = 0
        var bright = 0
        var total = 0
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0

        val step = 7
        for (y in y0 until y1 step step) {
            for (x in x0 until x1 step step) {
                val c = frame.getPixel(x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                if (r > 150 && r > g * 1.18 && r > b * 1.18) {
                    val weight = 1.0 + ((r - max(g, b)).coerceAtLeast(0) / 255.0)
                    sx += x * weight
                    sy += y * weight
                    sw += weight
                    red++
                }
                if ((r + g + b) / 3 > 185) bright++
                total++
            }
        }

        val planeScore = (red.toDouble() / max(1, total) * 18.0).coerceIn(0.0, 1.0)
        val brightScore = (bright.toDouble() / max(1, total) * 2.2).coerceIn(0.0, 1.0)
        var movementScore = 0.0
        if (sw > 3.0) {
            val px = sx / sw
            val py = sy / sw
            if (!lastPlaneX.isNaN()) {
                val d = sqrt((px - lastPlaneX) * (px - lastPlaneX) + (py - lastPlaneY) * (py - lastPlaneY))
                movementScore = (d / (w * 0.08)).coerceIn(0.0, 1.0)
            }
            lastPlaneX = px.toFloat()
            lastPlaneY = py.toFloat()
        }

        // Live Aviator screens normally contain a bright central multiplier area
        // plus the moving red plane/aircraft graphic. No numeric multiplier is
        // required for live-screen gating.
        val central = centralAppearance(frame)
        val sceneScore = planeScore * 0.58 + central * 0.30 + brightScore * 0.12
        val candidate = sceneScore >= 0.28 && (planeScore >= 0.18 || movementScore >= 0.18)

        if (candidate) {
            stableHits = (stableHits + 1).coerceAtMost(4)
            missHits = 0
        } else {
            missHits = (missHits + 1).coerceAtMost(5)
            stableHits = max(0, stableHits - 1)
        }

        val live = stableHits >= 1 && missHits < 3
        return LiveScreenState(live, planeScore, central, sceneScore)
    }

    private fun centralAppearance(frame: Bitmap): Double {
        val w = frame.width
        val h = frame.height
        val x0 = (w * 0.22).toInt()
        val x1 = (w * 0.78).toInt()
        val y0 = (h * 0.12).toInt()
        val y1 = (h * 0.48).toInt()
        var bright = 0
        var red = 0
        var n = 0
        for (y in y0 until y1 step 9) for (x in x0 until x1 step 9) {
            val c = frame.getPixel(x, y)
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            if ((r + g + b) / 3 > 160) bright++
            if (r > 145 && r > g * 1.20 && r > b * 1.20) red++
            n++
        }
        return ((bright.toDouble() / max(1, n)) * 0.65 +
                (red.toDouble() / max(1, n)) * 5.0).coerceIn(0.0, 1.0)
    }
}
