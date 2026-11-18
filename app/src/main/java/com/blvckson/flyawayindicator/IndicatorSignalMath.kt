package com.blvckson.flyawayindicator

/**
 * A visual-change sample tied to Android's monotonic elapsed-realtime clock.
 * Wall-clock adjustments must never change detector windows or round timeouts.
 */
internal data class IndicatorSignalSample(
    val timeMs: Long,
    val value: Double
)

/**
 * Pure, deterministic timing math for the live indicator.
 *
 * Kept separate from Android services so the windowing and reset rules can be
 * verified with ordinary JVM unit tests.
 */
internal object IndicatorSignalMath {

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    /**
     * Relative bounding-box size change between adjacent frames.
     * Missing, zero, or non-finite prior measurements are treated as unavailable.
     */
    fun relativeGeometryChange(
        currentWidth: Double,
        currentHeight: Double,
        previousWidth: Double?,
        previousHeight: Double?
    ): Double {
        if (!currentWidth.isFinite() || !currentHeight.isFinite() ||
            currentWidth <= 0.0 || currentHeight <= 0.0 ||
            previousWidth == null || previousHeight == null ||
            !previousWidth.isFinite() || !previousHeight.isFinite() ||
            previousWidth <= 0.0 || previousHeight <= 0.0
        ) return 0.0

        val widthDelta = kotlin.math.abs(currentWidth - previousWidth) / maxOf(0.002, previousWidth)
        val heightDelta = kotlin.math.abs(currentHeight - previousHeight) / maxOf(0.002, previousHeight)
        return ((widthDelta + heightDelta) / 2.0).coerceIn(0.0, 1.0)
    }

    /** Relative aspect-ratio change, kept separate from translation and scale. */
    fun relativeShapeChange(currentAspect: Double, previousAspect: Double?): Double {
        if (!currentAspect.isFinite() || currentAspect <= 0.0 ||
            previousAspect == null || !previousAspect.isFinite() || previousAspect <= 0.0
        ) return 0.0
        return (kotlin.math.abs(currentAspect - previousAspect) / maxOf(0.10, previousAspect))
            .coerceIn(0.0, 1.0)
    }

    /**
     * Compares two adjacent time windows:
     * [now - 2*window, now - window] against [now - window, now).
     *
     * Returns null rather than a synthetic neutral score when either window
     * lacks enough samples. The caller must not treat missing data as evidence.
     */
    fun windowTrend(
        samples: List<IndicatorSignalSample>,
        nowMs: Long,
        windowMs: Long,
        minimumSamples: Int
    ): Double? {
        if (windowMs <= 0L || minimumSamples <= 0) return null

        val earlier = ArrayList<IndicatorSignalSample>()
        val recent = ArrayList<IndicatorSignalSample>()

        for (sample in samples) {
            val age = nowMs - sample.timeMs
            when {
                age in windowMs..(windowMs * 2L) -> earlier.add(sample)
                age >= 0L && age < windowMs -> recent.add(sample)
            }
        }

        if (earlier.size < minimumSamples || recent.size < minimumSamples) return null
        // Require observations to span a meaningful part of each window. A burst
        // of adjacent frames must not masquerade as a trend across 250/800 ms.
        val minimumCoverageMs = maxOf(1L, windowMs / 2L)
        if (sampleSpanMs(earlier) < minimumCoverageMs ||
            sampleSpanMs(recent) < minimumCoverageMs
        ) return null

        // Time-weight each interval so a burst of high-FPS frames cannot dominate
        // a window merely by contributing more samples than a slower interval.
        val earlyAverage = timeWeightedAverage(earlier)
        val recentAverage = timeWeightedAverage(recent)
        val denominator = maxOf(0.012, earlyAverage + 0.012)
        return ((recentAverage - earlyAverage) / denominator + 0.5).coerceIn(0.0, 1.0)
    }

    private fun sampleSpanMs(samples: List<IndicatorSignalSample>): Long {
        if (samples.size < 2) return 0L
        val times = samples.map { it.timeMs }
        return (times.maxOrNull() ?: 0L) - (times.minOrNull() ?: 0L)
    }

    private fun timeWeightedAverage(samples: List<IndicatorSignalSample>): Double {
        val ordered = samples.sortedBy { it.timeMs }
        var weightedArea = 0.0
        var coveredMs = 0L

        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            val elapsed = current.timeMs - previous.timeMs
            if (elapsed <= 0L) continue

            weightedArea += (previous.value + current.value) * 0.5 * elapsed
            coveredMs += elapsed
        }

        return if (coveredMs > 0L) weightedArea / coveredMs else ordered.map { it.value }.average()
    }

    /**
     * Build 75 activity trend: compare the first and second halves of this
     * round's rolling sample window. No fixed FPS or full wall-clock windows
     * are required.
     */
    fun relativeActivityTrend(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val half = maxOf(1, values.size / 2)
        val earlierAverage = values.take(half).average()
        val recentAverage = values.takeLast(half).average()
        return ((recentAverage - earlierAverage) /
            maxOf(0.012, earlierAverage + 0.012) + 0.5).coerceIn(0.0, 1.0)
    }

    /** Exact weighted signal blend used by the previously successful Build 75. */
    fun build75Candidate(
        indicatorTrend: Double,
        centralChange: Double,
        planeScore: Double,
        movementScore: Double
    ): Double {
        return (indicatorTrend.coerceIn(0.0, 1.0) * 0.36 +
            centralChange.coerceIn(0.0, 1.0) * 0.24 +
            planeScore.coerceIn(0.0, 1.0) * 0.18 +
            movementScore.coerceIn(0.0, 1.0) * 0.22).coerceIn(0.0, 1.0)
    }

    /** Frame-to-frame smoothing from Build 75; independent of capture FPS. */
    fun smoothBuild75Score(previousScore: Double, candidateScore: Double): Double {
        return (previousScore.coerceIn(0.0, 1.0) * 0.72 +
            candidateScore.coerceIn(0.0, 1.0) * 0.28).coerceIn(0.0, 1.0)
    }

    /**
     * Three high-score frames confirm the warning. A weak frame decays the hold
     * by one instead of clearing it, matching the previously successful build.
     */
    fun updateBuild75Hold(currentHold: Int, smoothedScore: Double): Int {
        return if (smoothedScore >= 0.62) {
            (currentHold + 1).coerceAtMost(8)
        } else {
            maxOf(0, currentHold - 1)
        }
    }

    /**
     * A round is reset only after the live-screen evidence has been absent for
     * the complete timeout. This is independent of the device's capture FPS.
     */
    fun shouldResetRound(
        nowMs: Long,
        lastLiveAtMs: Long,
        timeoutMs: Long
    ): Boolean {
        if (lastLiveAtMs <= 0L || timeoutMs <= 0L || nowMs < lastLiveAtMs) return false
        return nowMs - lastLiveAtMs >= timeoutMs
    }
}
