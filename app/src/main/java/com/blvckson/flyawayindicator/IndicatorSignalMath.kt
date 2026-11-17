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

        val earlier = ArrayList<Double>()
        val recent = ArrayList<Double>()

        for (sample in samples) {
            val age = nowMs - sample.timeMs
            when {
                age in windowMs..(windowMs * 2L) -> earlier.add(sample.value)
                age >= 0L && age < windowMs -> recent.add(sample.value)
            }
        }

        if (earlier.size < minimumSamples || recent.size < minimumSamples) return null

        val earlyAverage = earlier.average()
        val recentAverage = recent.average()
        val denominator = maxOf(0.012, earlyAverage + 0.012)
        return ((recentAverage - earlyAverage) / denominator + 0.5).coerceIn(0.0, 1.0)
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
