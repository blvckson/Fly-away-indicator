package com.blvckson.flyawayindicator

import kotlin.math.max
import kotlin.math.min

/** Build-74 adaptive signal blend plus a separate multiplier-appearance channel. */
internal data class PreFlySignals(
    val trend: Double,
    val central: Double,
    val plane: Double,
    val movement: Double,
    /** Appearance-only multiplier signal; numeric multiplier values are never supplied. */
    val multiplier: Double = 0.0
)

internal data class PreFlySignalScores(
    val trend: Int,
    val central: Int,
    val aircraft: Int,
    val movement: Int,
    val multiplier: Int,
    val combined: Int
) {
    fun diagnosticLine(): String =
        "Signal indices /100 — trend $trend, central $central, aircraft $aircraft, movement $movement, multiplier appearance $multiplier; combined $combined."
}

internal object PreFlyTiming {
    /**
     * Nominal 4.00x boundary for the standard Aviator flight curve.
     * The long-round path uses elapsed time only; it never reads multiplier growth.
     */
    const val LONG_ROUND_THRESHOLD_MS = 17_000L

    fun candidate(signals: PreFlySignals): Double =
        // Preserve the pre-existing Build 74 signal proportions in 88% of the
        // score and reserve 12% for multiplier-display appearance behaviour.
        (signals.trend * 0.3344 + signals.central * 0.2112 +
            signals.plane * 0.1408 + signals.movement * 0.1936 +
            signals.multiplier * 0.12).coerceIn(0.0, 1.0)

    /** Report all five independent input levels on the same 0..100 scale. */
    fun signalScores(signals: PreFlySignals): PreFlySignalScores =
        PreFlySignalScores(
            trend = toPercent(signals.trend),
            central = toPercent(signals.central),
            aircraft = toPercent(signals.plane),
            movement = toPercent(signals.movement),
            multiplier = toPercent(signals.multiplier),
            combined = toPercent(candidate(signals))
        )

    private fun toPercent(value: Double): Int =
        // Tiny epsilon prevents a mathematically exact 100% blend from flooring
        // to 99 because of binary floating-point representation.
        (value.coerceIn(0.0, 1.0) * 100.0 + 1e-9).toInt().coerceIn(0, 100)

    fun medianBaseline(samples: List<Double>): Double {
        val sorted = samples.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    fun evidenceCount(signals: PreFlySignals): Int =
        (if (signals.trend >= 0.62) 1 else 0) +
            (if (signals.central >= 0.13) 1 else 0) +
            (if (signals.plane >= 0.10) 1 else 0) +
            (if (signals.movement >= 0.11) 1 else 0) +
        (if (signals.multiplier >= 0.12) 1 else 0)

    fun isEvidence(
        signals: PreFlySignals,
        candidate: Double,
        smoothedScore: Double,
        baselineScore: Double,
        baselineSampleCount: Int = 4,
        roundFrameCount: Int = 8,
        flightProgress: Double = 1.0,
        roundElapsedMs: Long = 0L
    ): Boolean {
        val trendEvidence = signals.trend >= 0.62
        val centralEvidence = signals.central >= 0.13
        val planeEvidence = signals.plane >= 0.10
        val movementEvidence = signals.movement >= 0.11
        val multiplierEvidence = signals.multiplier >= 0.12
        val count = evidenceCount(signals)
        val pairedEvidence =
            (trendEvidence && (centralEvidence || movementEvidence || planeEvidence || multiplierEvidence)) ||
                (centralEvidence && (movementEvidence || multiplierEvidence)) ||
                (movementEvidence && (planeEvidence || multiplierEvidence)) ||
                (planeEvidence && multiplierEvidence)

        val baselineRise = candidate - baselineScore

        // The launch animation produces simultaneous movement and visual change.
        // Do not evaluate the short-round fallback until four real intra-round
        // observations exist, and require measurable movement away from the
        // aircraft's own opening position.
        val beforeLongRound = roundElapsedMs < LONG_ROUND_THRESHOLD_MS
        val fastShortRoundEvidence = beforeLongRound &&
            baselineSampleCount in 2..4 &&
            roundFrameCount >= 5 &&
            signals.trend >= 0.76 &&
            signals.central >= 0.13 &&
            signals.plane >= 0.10 &&
            signals.movement >= 0.11 &&
            count >= 3 &&
            (multiplierEvidence || count >= 4) &&
            candidate >= max(0.38, baselineScore * 1.25) &&
            baselineRise >= 0.075 &&
            flightProgress >= 0.08 &&
            smoothedScore >= max(0.42, baselineScore + 0.06)

        // The normal path additionally requires the aircraft to have advanced
        // beyond the take-off region. This is a relative path-progress measure,
        // not a fixed multiplier or a far-edge position cutoff.
        val normalEvidence = beforeLongRound &&
            baselineSampleCount >= 3 &&
            roundFrameCount >= 8 &&
            flightProgress >= 0.18 &&
            candidate >= max(0.34, baselineScore * 1.28) &&
            baselineRise >= 0.06 &&
            smoothedScore >= max(0.40, baselineScore + 0.05) &&
            count >= 2

        // Once a round passes the nominal 4x duration, stop using the short/medium
        // path. Long rounds need a larger relative rise and corroboration from
        // actual visual change plus aircraft motion or multiplier appearance.
        // This path has no upper time limit, so very long flights use the same model.
        val longRoundEvidence = roundElapsedMs >= LONG_ROUND_THRESHOLD_MS &&
            baselineSampleCount >= 2 &&
            roundFrameCount >= 16 &&
            flightProgress >= 0.18 &&
            candidate >= max(0.42, baselineScore * 1.34) &&
            baselineRise >= 0.075 &&
            smoothedScore >= max(0.46, baselineScore + 0.06) &&
            count >= 3 &&
            hasLongRoundCorroboration(signals)

        return pairedEvidence &&
            (fastShortRoundEvidence || normalEvidence || longRoundEvidence)
    }

    private fun hasLongRoundCorroboration(signals: PreFlySignals): Boolean {
        val visualChange = signals.trend >= 0.62 || signals.central >= 0.13
        val aircraftMotion = signals.plane >= 0.10 && signals.movement >= 0.11
        val multiplierAppearance = signals.multiplier >= 0.12
        return visualChange &&
            (aircraftMotion ||
                (multiplierAppearance && (signals.plane >= 0.10 || signals.movement >= 0.11)))
    }

    /**
     * Keep a rolling baseline for long rounds, but freeze learning when the
     * score is rising or multiple channels hint at an unusual event.
     */
    fun shouldLearnBaseline(
        candidate: Double,
        baselineScore: Double,
        signals: PreFlySignals
    ): Boolean {
        val earlyHints =
            (if (signals.trend >= 0.58) 1 else 0) +
                (if (signals.central >= 0.10) 1 else 0) +
                (if (signals.plane >= 0.08) 1 else 0) +
                (if (signals.movement >= 0.08) 1 else 0) +
                (if (signals.multiplier >= 0.10) 1 else 0)
        return candidate - baselineScore < 0.035 && earlyHints < 2
    }

    fun updatedHold(current: Int, evidence: Boolean): Int =
        if (evidence) min(8, current + 1) else max(0, current - 1)

    /**
     * Long-round confirmation is intentionally consecutive: one weak frame
     * clears the streak instead of allowing separated hints to accumulate.
     */
    fun updatedLongRoundHold(current: Int, evidence: Boolean): Int =
        if (evidence) min(3, current + 1) else 0

    /** Long-round warnings require three consecutive confirmations to reject noise. */
    fun requiredConfirmations(
        frameDeltaMs: Long,
        independentSignals: Int,
        longRound: Boolean = false
    ): Int =
        if (longRound) 3
        else if (frameDeltaMs >= 90L || independentSignals >= 3) 2 else 3
}
