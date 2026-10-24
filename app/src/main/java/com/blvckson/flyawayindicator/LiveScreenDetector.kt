package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class LiveScreenState(
    val isLive: Boolean,
    val planeScore: Double,
    val multiplierVisualScore: Double,
    val sceneScore: Double,
    val planeX: Float = Float.NaN,
    val planeY: Float = Float.NaN
)

/**
 * Conservative Aviator scene gate. A screen change alone is never evidence of a
 * round: we require a compact red aircraft-like visual in the flight region and
 * a separate central game/multiplier-area signal over several consecutive frames.
 */
class LiveScreenDetector {
    private var lastPlaneX = Float.NaN
    private var lastPlaneY = Float.NaN
    private var stableHits = 0
    private var missHits = 0
    private var confidence = 0.0

    fun inspect(frame: Bitmap): LiveScreenState {
        val w = frame.width
        val h = frame.height
        // Use a denser scan so a small red plane on a portrait phone is not
        // skipped between sample points. OCR remains a separate start gate.
        val step = max(2, minOf(w, h) / 240)
        val gx0 = (w * 0.04).toInt()
        val gx1 = (w * 0.96).toInt()
        val gy0 = (h * 0.08).toInt()
        val gy1 = (h * 0.74).toInt()
        val cols = max(1, (gx1 - gx0 + step - 1) / step)
        val rows = max(1, (gy1 - gy0 + step - 1) / step)
        val redMask = BooleanArray(cols * rows)
        var bright = 0
        var total = 0

        for (gy in 0 until rows) {
            val y = (gy0 + gy * step).coerceAtMost(h - 1)
            for (gx in 0 until cols) {
                val x = (gx0 + gx * step).coerceAtMost(w - 1)
                val c = frame.getPixel(x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                redMask[gy * cols + gx] =
                    r > 155 && r > g * 1.28 && r > b * 1.25 && (r - g) > 45
                if ((r + g + b) / 3 > 185) bright++
                total++
            }
        }

        // Find compact connected red objects rather than treating all red pixels
        // on a changing screen (including red text/buttons) as an aircraft.
        val seen = BooleanArray(redMask.size)
        var bestCount = 0
        var bestX = Float.NaN
        var bestY = Float.NaN
        var bestAspect = 0.0
        val queue = IntArray(redMask.size)
        for (start in redMask.indices) {
            if (!redMask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var count = 0
            var minX = cols
            var maxX = 0
            var minY = rows
            var maxY = 0
            var sumX = 0.0
            var sumY = 0.0
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % cols
                val y = idx / cols
                count++
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                sumX += x; sumY += y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until cols || ny !in 0 until rows) continue
                    val ni = ny * cols + nx
                    if (redMask[ni] && !seen[ni]) {
                        seen[ni] = true
                        queue[tail++] = ni
                    }
                }
            }
            val bw = max(1, maxX - minX + 1)
            val bh = max(1, maxY - minY + 1)
            val aspect = bw.toDouble() / bh
            // Exclude broad red labels/buttons and tiny noise. Aircraft icon sizes
            // vary with screen scale, so these are deliberately coarse bounds.
            // Scale bounds with the sampled screen grid. Different phones and
            // display scaling can make the same aircraft occupy different pixel counts.
            val maxComponentCount=max(160, redMask.size/110)
            val maxComponentWidth=max(30, cols/7)
            val maxComponentHeight=max(22, rows/6)
            val compact = count in 2..maxComponentCount &&
                bw <= maxComponentWidth && bh <= maxComponentHeight &&
                aspect in 0.30..5.2
            val cx = gx0 + (sumX / count * step).toFloat()
            val cy = gy0 + (sumY / count * step).toFloat()
            // Ignore the very bottom UI and the far edges where unrelated red
            // buttons/status indicators are commonly found.
            val inFlightArea = cx in (w * 0.12f)..(w * 0.88f) &&
                cy in (h * 0.12f)..(h * 0.68f)
            if (compact && inFlightArea && count > bestCount) {
                bestCount = count
                bestX = cx
                bestY = cy
                bestAspect = aspect
            }
        }

        var movementScore = 0.0
        if (!bestX.isNaN() && !lastPlaneX.isNaN()) {
            val d = sqrt((bestX - lastPlaneX) * (bestX - lastPlaneX) +
                (bestY - lastPlaneY) * (bestY - lastPlaneY))
            movementScore = (d / (w * 0.025)).coerceIn(0.0, 1.0)
        }
        if (!bestX.isNaN()) {
            lastPlaneX = bestX
            lastPlaneY = bestY
        } else {
            lastPlaneX = Float.NaN
            lastPlaneY = Float.NaN
        }

        val central = centralAppearance(frame)
        val planeScore = if (bestCount > 0) (bestCount / 16.0).coerceIn(0.08, 1.0) else 0.0
        val brightScore = bright.toDouble() / max(1, total)
        val sceneScore = (planeScore * 0.48 + central * 0.42 +
            brightScore.coerceIn(0.0, 1.0) * 0.10).coerceIn(0.0, 1.0)

        // A compact aircraft-like red object AND the central game area are both
        // mandatory. Require repeat confirmation; movement is supporting evidence,
        // not a hard requirement, to tolerate paused/low-frame-rate capture.
        val candidate = bestCount in 2..160 &&
            bestAspect in 0.35..4.8 &&
            central >= 0.07 &&
            sceneScore >= 0.16

        if (candidate) {
            stableHits = (stableHits + 1).coerceAtMost(6)
            missHits = 0
            confidence = (confidence + 0.20).coerceAtMost(1.0)
        } else {
            missHits = (missHits + 1).coerceAtMost(6)
            stableHits = max(0, stableHits - 1)
            confidence = (confidence - 0.22).coerceAtLeast(0.0)
        }

        val live = confidence >= 0.60 && stableHits >= 4 && missHits < 2
        return LiveScreenState(
            live, planeScore, central, sceneScore,
            bestX, bestY
        )
    }

    private fun centralAppearance(frame: Bitmap): Double {
        val w = frame.width
        val h = frame.height
        val x0 = (w * 0.24).toInt()
        val x1 = (w * 0.76).toInt()
        val y0 = (h * 0.14).toInt()
        val y1 = (h * 0.50).toInt()
        var bright = 0
        var red = 0
        var varied = 0
        var n = 0
        var previous = -1
        for (y in y0 until y1 step 8) for (x in x0 until x1 step 8) {
            val c = frame.getPixel(x, y)
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            if ((r + g + b) / 3 > 160) bright++
            if (r > 145 && r > g * 1.20 && r > b * 1.20) red++
            val luminance = (r * 3 + g * 6 + b) / 10
            if (previous >= 0 && abs(luminance - previous) > 24) varied++
            previous = luminance
            n++
        }
        val brightRatio = bright.toDouble() / max(1, n)
        val redRatio = red.toDouble() / max(1, n)
        val variation = varied.toDouble() / max(1, n)
        return (brightRatio * 0.55 + redRatio * 1.8 + variation * 0.35)
            .coerceIn(0.0, 1.0)
    }
}
