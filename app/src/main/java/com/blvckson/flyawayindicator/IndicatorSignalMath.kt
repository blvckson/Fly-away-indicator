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
     * Detect a sustained, baseline-relative visual surge.
     *
     * A trend increase must be corroborated by at least one independent visual
     * cue: central-area change, aircraft movement, bounding-box geometry change,
     * or aircraft silhouette/aspect-ratio change. Aircraft presence alone is not
     * enough. Thresholds are intentionally lower than the former 0.64/0.78 gates
     * because those gates produced no red transitions in the user's device test.
     */
    fun shouldTriggerPreFlySurge(
        baselineReady: Boolean,
        shortBaselineReady: Boolean,
        standardTrend: Double?,
        shortTrend: Double?,
        candidateScore: Double,
        baselineScore: Double,
        centralChange: Double,
        movementScore: Double,
        geometryChange: Double,
        shapeChange: Double
    ): Boolean {
        val corroboratingSignals =
            (if (centralChange >= 0.06) 1 else 0) +
            (if (movementScore >= 0.05) 1 else 0) +
            (if (geometryChange >= 0.04) 1 else 0) +
            (if (shapeChange >= 0.06) 1 else 0)

        if (corroboratingSignals == 0) return false

        val normalBaselineRise =
            candidateScore >= maxOf(0.19, baselineScore * 1.06) &&
            candidateScore - baselineScore >= 0.012
        val normalSurge = baselineReady &&
            standardTrend != null && standardTrend >= 0.58 &&
            normalBaselineRise

        val shortSurge = shortBaselineReady &&
            shortTrend != null && shortTrend >= 0.70 &&
            candidateScore >= maxOf(0.24, baselineScore + 0.045)

        return normalSurge || shortSurge
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
