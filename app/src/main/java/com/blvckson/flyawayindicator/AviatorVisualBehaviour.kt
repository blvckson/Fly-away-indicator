package com.blvckson.flyawayindicator

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.ArrayDeque

/** OCR rectangle in normalized full-screen coordinates. No multiplier digits are retained. */
internal data class MultiplierTextBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Double get() = (bottom - top).coerceAtLeast(0.0)
    fun isValid(): Boolean = left >= 0.0 && top >= 0.0 &&
        right <= 1.0 && bottom <= 1.0 && right > left && bottom > top
}

/** This contains only display identity/geometry, never the numeric multiplier value. */
internal data class AviatorTextEvidence(
    val verified: Boolean = false,
    val multiplierPresent: Boolean = false,
    val aviatorLabelPresent: Boolean = false,
    val gameControlsPresent: Boolean = false,
    val endConfirmed: Boolean = false,
    val multiplierBounds: MultiplierTextBounds? = null,
    val observedAtMillis: Long = 0L
)

internal data class MultiplierAppearance(
    val centerX: Double,
    val centerY: Double,
    val fontHeight: Double,
    val inkLuminance: Double,
    val inkRedRatio: Double,
    val contrast: Double
)

/**
 * Samples the pixels underneath the OCR-identified multiplier text. Width and digit
 * content are deliberately excluded so ordinary numerical growth is not a signal.
 */
internal object MultiplierAppearanceSampler {
    fun sample(frame: Bitmap, bounds: MultiplierTextBounds?): MultiplierAppearance? {
        if (bounds == null || !bounds.isValid() || frame.width < 2 || frame.height < 2) return null
        val left = (bounds.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (bounds.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (bounds.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (bounds.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
        val padX = max(3, (right - left) / 5)
        val padY = max(3, (bottom - top) / 3)
        val outerLeft = max(0, left - padX)
        val outerTop = max(0, top - padY)
        val outerRight = min(frame.width, right + padX)
        val outerBottom = min(frame.height, bottom + padY)

        var backgroundSum = 0.0
        var backgroundCount = 0
        for (y in outerTop until outerBottom step 2) {
            for (x in outerLeft until outerRight step 2) {
                if (x in left until right && y in top until bottom) continue
                val c = frame.getPixel(x, y)
                backgroundSum += luminance(c)
                backgroundCount++
            }
        }
        if (backgroundCount == 0) return null
        val background = backgroundSum / backgroundCount

        var inkLuminance = 0.0
        var inkRed = 0.0
        var inkCount = 0
        var allLuminance = 0.0
        var allRed = 0.0
        var allCount = 0
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val c = frame.getPixel(x, y)
                val l = luminance(c)
                val red = ((c shr 16) and 255).toDouble() / 255.0
                allLuminance += l
                allRed += red
                allCount++
                if (abs(l - background) >= 28.0) {
                    inkLuminance += l
                    inkRed += red
                    inkCount++
                }
            }
        }
        if (allCount == 0) return null
        if (inkCount == 0) {
            inkLuminance = allLuminance
            inkRed = allRed
            inkCount = allCount
        }
        val inkMean = inkLuminance / inkCount
        return MultiplierAppearance(
            centerX = bounds.centerX,
            centerY = bounds.centerY,
            fontHeight = bounds.height,
            inkLuminance = inkMean,
            inkRedRatio = (inkRed / inkCount).coerceIn(0.0, 1.0),
            contrast = (abs(inkMean - background) / 255.0).coerceIn(0.0, 1.0)
        )
    }

    private fun luminance(color: Int): Double {
        val r = (color shr 16) and 255
        val g = (color shr 8) and 255
        val b = color and 255
        return (r * 0.299 + g * 0.587 + b * 0.114)
    }
}

internal data class MultiplierBehaviourState(
    val present: Boolean = false,
    val changeScore: Double = 0.0,
    val stabilityScore: Double = 0.0,
    val samples: Int = 0
)

/**
 * Per-round baseline for multiplier display appearance. It measures layout, font
 * height, colour and contrast changes only; it never receives or compares digits.
 */
internal class MultiplierBehaviourTracker {
    private val opening = ArrayDeque<MultiplierAppearance>()
    private var baseline: MultiplierAppearance? = null
    private var lastObservedAt = Long.MIN_VALUE
    private var sampleCount = 0
    private var lastAppearance: MultiplierAppearance? = null
    private var lastState = MultiplierBehaviourState()

    fun reset() {
        opening.clear()
        baseline = null
        lastObservedAt = Long.MIN_VALUE
        sampleCount = 0
        lastAppearance = null
        lastState = MultiplierBehaviourState()
    }

    /** Seed the next round from its first verified display, excluding lobby-to-live change. */
    fun startRoundBaseline(): MultiplierBehaviourState {
        opening.clear()
        baseline = lastAppearance
        lastState = MultiplierBehaviourState(
            present = lastAppearance != null,
            changeScore = 0.0,
            stabilityScore = 1.0,
            samples = sampleCount
        )
        return lastState
    }

    fun needsSample(observedAtMillis: Long): Boolean =
        observedAtMillis > 0L && observedAtMillis != lastObservedAt

    fun observe(appearance: MultiplierAppearance?, observedAtMillis: Long): MultiplierBehaviourState {
        if (observedAtMillis <= 0L || observedAtMillis == lastObservedAt) return lastState
        lastObservedAt = observedAtMillis
        if (appearance == null) {
            lastState = MultiplierBehaviourState(false, 0.0, 0.0, sampleCount)
            return lastState
        }
        lastAppearance = appearance
        sampleCount++

        if (baseline == null) {
            opening.addLast(appearance)
            if (opening.size >= 4) baseline = average(opening)
            lastState = MultiplierBehaviourState(true, 0.0, 1.0, sampleCount)
            return lastState
        }

        val ref = baseline!!
        val xShift = (abs(appearance.centerX - ref.centerX) / 0.055).coerceIn(0.0, 1.0)
        val yShift = (abs(appearance.centerY - ref.centerY) / 0.055).coerceIn(0.0, 1.0)
        val heightShift = (abs(appearance.fontHeight - ref.fontHeight) /
            max(0.012, ref.fontHeight * 0.22)).coerceIn(0.0, 1.0)
        val colourShift = (abs(appearance.inkLuminance - ref.inkLuminance) / 48.0).coerceIn(0.0, 1.0)
        val redShift = (abs(appearance.inkRedRatio - ref.inkRedRatio) / 0.28).coerceIn(0.0, 1.0)
        val contrastShift = (abs(appearance.contrast - ref.contrast) / 0.24).coerceIn(0.0, 1.0)
        val change = (xShift * 0.18 + yShift * 0.20 + heightShift * 0.16 +
            colourShift * 0.18 + redShift * 0.13 + contrastShift * 0.15).coerceIn(0.0, 1.0)

        // Learn slow ordinary drift, but do not absorb a potentially meaningful change.
        if (change < 0.12) baseline = blend(ref, appearance, 0.035)
        lastState = MultiplierBehaviourState(
            present = true,
            changeScore = change,
            stabilityScore = (1.0 - change).coerceIn(0.0, 1.0),
            samples = sampleCount
        )
        return lastState
    }

    private fun average(items: Iterable<MultiplierAppearance>): MultiplierAppearance {
        val values = items.toList()
        val n = values.size.coerceAtLeast(1)
        return MultiplierAppearance(
            values.sumOf { it.centerX } / n,
            values.sumOf { it.centerY } / n,
            values.sumOf { it.fontHeight } / n,
            values.sumOf { it.inkLuminance } / n,
            values.sumOf { it.inkRedRatio } / n,
            values.sumOf { it.contrast } / n
        )
    }

    private fun blend(a: MultiplierAppearance, b: MultiplierAppearance, amount: Double) =
        MultiplierAppearance(
            a.centerX + (b.centerX - a.centerX) * amount,
            a.centerY + (b.centerY - a.centerY) * amount,
            a.fontHeight + (b.fontHeight - a.fontHeight) * amount,
            a.inkLuminance + (b.inkLuminance - a.inkLuminance) * amount,
            a.inkRedRatio + (b.inkRedRatio - a.inkRedRatio) * amount,
            a.contrast + (b.contrast - a.contrast) * amount
        )
}

internal data class AircraftBehaviourState(
    val score: Double = 0.0,
    val movementScore: Double = 0.0,
    val persistedFrames: Int = 0
)

/** Tracks aircraft geometry continuity and trajectory consistency across frames. */
internal class AircraftBehaviourTracker {
    private var previousX = Double.NaN
    private var previousY = Double.NaN
    private var previousWidth = 0.0
    private var previousHeight = 0.0
    private var previousAspect = 0.0
    private var previousDx = 0.0
    private var previousDy = 0.0
    private var stableFrames = 0
    private var smoothedMovement = 0.0

    fun reset() {
        previousX = Double.NaN
        previousY = Double.NaN
        previousWidth = 0.0
        previousHeight = 0.0
        previousAspect = 0.0
        previousDx = 0.0
        previousDy = 0.0
        stableFrames = 0
        smoothedMovement = 0.0
    }

    fun miss(): AircraftBehaviourState {
        stableFrames = 0
        previousX = Double.NaN
        previousY = Double.NaN
        previousDx = 0.0
        previousDy = 0.0
        smoothedMovement *= 0.5
        return AircraftBehaviourState()
    }

    fun observe(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        aspect: Double
    ): AircraftBehaviourState {
        if (previousX.isNaN()) {
            previousX = x
            previousY = y
            previousWidth = width
            previousHeight = height
            previousAspect = aspect
            stableFrames = 1
            return AircraftBehaviourState(0.42, 0.0, stableFrames)
        }

        val dx = x - previousX
        val dy = y - previousY
        val distance = sqrt(dx * dx + dy * dy)
        val area = (width * height).coerceAtLeast(0.000001)
        val previousArea = (previousWidth * previousHeight).coerceAtLeast(0.000001)
        val areaRatio = area / previousArea
        val sizeContinuity = if (areaRatio in 0.20..5.0) {
            (1.0 - abs(kotlin.math.ln(areaRatio)) / 2.0).coerceIn(0.0, 1.0)
        } else 0.0
        val aspectContinuity = (1.0 - abs(aspect - previousAspect) /
            max(0.35, previousAspect)).coerceIn(0.0, 1.0)
        val shapeContinuity = (sizeContinuity * 0.45 + aspectContinuity * 0.55)
            .coerceIn(0.0, 1.0)
        val plausibleStep = distance <= 0.22 && shapeContinuity >= 0.12
        stableFrames = if (plausibleStep) min(8, stableFrames + 1) else 1

        val oldMagnitude = sqrt(previousDx * previousDx + previousDy * previousDy)
        val newMagnitude = distance
        val directionConsistency = if (oldMagnitude > 0.001 && newMagnitude > 0.001) {
            ((previousDx * dx + previousDy * dy) / (oldMagnitude * newMagnitude) * 0.5 + 0.5)
                .coerceIn(0.0, 1.0)
        } else 0.5
        val rawMovement = (distance / 0.018).coerceIn(0.0, 1.0) * (0.65 + 0.35 * directionConsistency)
        smoothedMovement = smoothedMovement * 0.55 + rawMovement * 0.45
        val persistence = (stableFrames / 4.0).coerceIn(0.0, 1.0)
        val score = (persistence * 0.48 + shapeContinuity * 0.34 +
            directionConsistency * 0.18).coerceIn(0.0, 1.0)

        previousX = x
        previousY = y
        previousWidth = width
        previousHeight = height
        previousAspect = aspect
        previousDx = dx
        previousDy = dy
        return AircraftBehaviourState(score, smoothedMovement, stableFrames)
    }
}

/** Require independent text, multiplier-layout and aircraft-behaviour evidence. */
internal object AviatorIdentityPolicy {
    fun isConfirmed(
        textVerified: Boolean,
        multiplierPresent: Boolean,
        aviatorLabelPresent: Boolean,
        gameControlsPresent: Boolean,
        multiplierBounds: MultiplierTextBounds?,
        multiplierSamples: Int,
        aircraftBehaviourScore: Double,
        sceneScore: Double
    ): Boolean {
        val bounds = multiplierBounds
        val expectedMultiplierRegion = bounds != null && bounds.isValid() &&
            bounds.centerX in 0.18..0.82 &&
            bounds.centerY in 0.10..0.60 &&
            bounds.height in 0.006..0.16
        val textIdentity = textVerified && multiplierPresent && expectedMultiplierRegion &&
            (aviatorLabelPresent || gameControlsPresent) && multiplierSamples >= 2
        return textIdentity && aircraftBehaviourScore >= 0.48 && sceneScore >= 0.14
    }
}
