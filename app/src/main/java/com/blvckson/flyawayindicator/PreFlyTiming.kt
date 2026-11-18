package com.blvckson.flyawayindicator

import kotlin.math.max
import kotlin.math.min

/** Build-74 weighted signal blend. Inputs are independent 0..1 signal indices. */
internal data class PreFlySignals(
    val trend: Double,
    val central: Double,
    val plane: Double,
    val movement: Double
)

internal data class PreFlySignalScores(
    val trend: Int,
    val central: Int,
    val aircraft: Int,
    val movement: Int,
    val combined: Int
) {
    fun diagnosticLine(): String =
        "Signal indices /100 — trend $trend, central $central, aircraft $aircraft, movement $movement; combined $combined."
}

internal object PreFlyTiming {
    fun candidate(signals: PreFlySignals): Double =
        (signals.trend * 0.38 + signals.central * 0.24 +
            signals.plane * 0.16 + signals.movement * 0.22).coerceIn(0.0, 1.0)

    /** Report all four independent input levels on the same 0..100 scale. */
    fun signalScores(signals: PreFlySignals): PreFlySignalScores =
        PreFlySignalScores(
            trend = toPercent(signals.trend),
            central = toPercent(signals.central),
            aircraft = toPercent(signals.plane),
            movement = toPercent(signals.movement),
            combined = toPercent(candidate(signals))
        )

    private fun toPercent(value: Double): Int =
        (value.coerceIn(0.0, 1.0) * 100.0).toInt().coerceIn(0, 100)

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
            (if (signals.movement >= 0.11) 1 else 0)

    fun isEvidence(
        signals: PreFlySignals,
        candidate: Double,
        smoothedScore: Double,
        baselineScore: Double,
        baselineSampleCount: Int = 4
    ): Boolean {
        val trendEvidence = signals.trend >= 0.62
        val centralEvidence = signals.central >= 0.13
        val planeEvidence = signals.plane >= 0.10
        val movementEvidence = signals.movement >= 0.11
        val count = evidenceCount(signals)
        val pairedEvidence =
            (trendEvidence && (centralEvidence || movementEvidence || planeEvidence)) ||
                (centralEvidence && movementEvidence) ||
                (movementEvidence && planeEvidence)

        val baselineRise = candidate - baselineScore

        // With only two opening samples, allow a very strong short-round path,
        // but demand three independent channels and a larger baseline departure.
        val fastShortRoundEvidence = baselineSampleCount == 2 &&
            signals.trend >= 0.70 &&
            count >= 3 &&
            candidate >= max(0.38, baselineScore * 1.25) &&
            baselineRise >= 0.075 &&
            smoothedScore >= max(0.42, baselineScore + 0.06)

        val normalEvidence = baselineSampleCount >= 3 &&
            candidate >= max(0.34, baselineScore * 1.28) &&
            baselineRise >= 0.06 &&
            smoothedScore >= max(0.40, baselineScore + 0.05) &&
            count >= 2

        return pairedEvidence && (fastShortRoundEvidence || normalEvidence)
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
                (if (signals.movement >= 0.08) 1 else 0)
        return candidate - baselineScore < 0.035 && earlyHints < 2
    }

    fun updatedHold(current: Int, evidence: Boolean): Int =
        if (evidence) min(8, current + 1) else max(0, current - 1)

    /** Strong three-channel events can confirm in two frames; otherwise retain three. */
    fun requiredConfirmations(frameDeltaMs: Long, independentSignals: Int): Int =
        if (frameDeltaMs >= 90L || independentSignals >= 3) 2 else 3
}
