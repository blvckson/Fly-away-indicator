package com.blvckson.flyawayindicator

import java.util.Locale

/**
 * Conservative fallback for a missed "FLEW AWAY" OCR event.
 *
 * Detector loss alone is never treated as a round end. The same round must
 * first have shown an aircraft; the aircraft must then be absent continuously,
 * OCR must repeatedly confirm the final-result/BET layout (not CASH OUT), and
 * the red final multiplier must be read consistently.
 */
internal class IndicatorRoundEndDetector(
    private val requiredPlaneAbsenceMs: Long = 1_800L,
    private val requiredFinalResultStableMs: Long = 850L,
    private val maximumTextAgeMs: Long = 1_200L,
    private val requiredBetReadyTextSamples: Int = 2,
    private val requiredFinalMultiplierSamples: Int = 3,
    private val minimumProbeIntervalMs: Long = 450L
) {
    private var planeSeenInRound = false
    private var planeMissingSinceMs = 0L
    private var lastTextObservedAtMs = 0L
    private var betReadyTextSamples = 0
    private var lastProbeAtMs = 0L

    private var lastFinalMultiplier = ""
    private var finalMultiplierSamples = 0
    private var finalMultiplierSinceMs = 0L
    private var lastFinalMultiplierAtMs = 0L

    /** Late OCR callbacks from a previous round are ignored after reset. */
    var generation: Long = 0L
        private set

    init {
        require(requiredPlaneAbsenceMs >= 1L)
        require(requiredFinalResultStableMs >= 1L)
        require(maximumTextAgeMs >= 1L)
        require(requiredBetReadyTextSamples >= 1)
        require(requiredFinalMultiplierSamples >= 2)
        require(minimumProbeIntervalMs >= 1L)
    }

    fun reset() {
        generation++
        planeSeenInRound = false
        planeMissingSinceMs = 0L
        lastTextObservedAtMs = 0L
        betReadyTextSamples = 0
        lastProbeAtMs = 0L
        clearFinalMultiplier()
    }

    /** Seed current round identity without counting pre-round OCR as result text. */
    fun beginRound(planePresent: Boolean, lastObservedTextAtMs: Long) {
        reset()
        planeSeenInRound = planePresent
        lastTextObservedAtMs = lastObservedTextAtMs
    }

    fun observeFrame(
        nowElapsedMs: Long,
        nowWallMs: Long,
        planePresent: Boolean,
        evidence: AviatorTextEvidence
    ) {
        if (planePresent) {
            if (planeMissingSinceMs > 0L) {
                planeMissingSinceMs = 0L
                betReadyTextSamples = 0
                clearFinalMultiplier()
            }
            planeSeenInRound = true
        } else if (planeSeenInRound && planeMissingSinceMs == 0L) {
            planeMissingSinceMs = nowElapsedMs
            betReadyTextSamples = 0
            clearFinalMultiplier()
        }

        if (evidence.observedAtMillis > 0L &&
            evidence.observedAtMillis != lastTextObservedAtMs
        ) {
            lastTextObservedAtMs = evidence.observedAtMillis
            if (isBetReadyEvidence(evidence) && isTextFresh(nowWallMs, evidence)) {
                betReadyTextSamples =
                    (betReadyTextSamples + 1).coerceAtMost(requiredBetReadyTextSamples + 1)
            } else {
                betReadyTextSamples = 0
                clearFinalMultiplier()
            }
        }
    }

    /** Start red-only OCR only after sustained aircraft loss and repeated BET evidence. */
    fun shouldStartRedMultiplierProbe(
        nowElapsedMs: Long,
        nowWallMs: Long,
        evidence: AviatorTextEvidence
    ): Boolean {
        if (!isEligibleForFinalMultiplierRead(nowElapsedMs, nowWallMs, evidence)) return false
        if (lastProbeAtMs > 0L && nowElapsedMs - lastProbeAtMs < minimumProbeIntervalMs) {
            return false
        }
        lastProbeAtMs = nowElapsedMs
        return true
    }

    fun recordRedMultiplier(
        value: String,
        nowElapsedMs: Long,
        nowWallMs: Long,
        expectedGeneration: Long
    ) {
        if (expectedGeneration != generation || value.isBlank() ||
            !planeSeenInRound || planeMissingSinceMs == 0L ||
            betReadyTextSamples < requiredBetReadyTextSamples ||
            nowElapsedMs - planeMissingSinceMs < requiredPlaneAbsenceMs ||
            nowWallMs < lastTextObservedAtMs ||
            nowWallMs - lastTextObservedAtMs > maximumTextAgeMs
        ) return

        val normalized = value.trim().lowercase(Locale.US)
        if (lastFinalMultiplier != normalized ||
            (lastFinalMultiplierAtMs > 0L &&
                nowElapsedMs - lastFinalMultiplierAtMs > maximumTextAgeMs)
        ) {
            lastFinalMultiplier = normalized
            finalMultiplierSamples = 1
            finalMultiplierSinceMs = nowElapsedMs
        } else {
            finalMultiplierSamples =
                (finalMultiplierSamples + 1).coerceAtMost(requiredFinalMultiplierSamples + 1)
        }
        lastFinalMultiplierAtMs = nowElapsedMs
    }

    fun isConfirmed(
        nowElapsedMs: Long,
        nowWallMs: Long,
        evidence: AviatorTextEvidence
    ): Boolean =
        isEligibleForFinalMultiplierRead(nowElapsedMs, nowWallMs, evidence) &&
            finalMultiplierSamples >= requiredFinalMultiplierSamples &&
            finalMultiplierSinceMs > 0L &&
            nowElapsedMs - finalMultiplierSinceMs >= requiredFinalResultStableMs

    private fun isEligibleForFinalMultiplierRead(
        nowElapsedMs: Long,
        nowWallMs: Long,
        evidence: AviatorTextEvidence
    ): Boolean =
        planeSeenInRound &&
            planeMissingSinceMs > 0L &&
            nowElapsedMs - planeMissingSinceMs >= requiredPlaneAbsenceMs &&
            betReadyTextSamples >= requiredBetReadyTextSamples &&
            isBetReadyEvidence(evidence) &&
            isTextFresh(nowWallMs, evidence)

    private fun isBetReadyEvidence(evidence: AviatorTextEvidence): Boolean =
        evidence.verified &&
            evidence.multiplierPresent &&
            evidence.gameControlsPresent &&
            evidence.betButtonPresent &&
            !evidence.cashOutPresent &&
            !evidence.endConfirmed

    private fun isTextFresh(nowWallMs: Long, evidence: AviatorTextEvidence): Boolean =
        evidence.observedAtMillis > 0L &&
            nowWallMs >= evidence.observedAtMillis &&
            nowWallMs - evidence.observedAtMillis <= maximumTextAgeMs

    private fun clearFinalMultiplier() {
        lastFinalMultiplier = ""
        finalMultiplierSamples = 0
        finalMultiplierSinceMs = 0L
        lastFinalMultiplierAtMs = 0L
    }
}

/** Discard temporal pre-fly samples until two consecutive live observations return. */
internal class IndicatorReacquisitionGate(
    private val requiredStableFrames: Int = 2
) {
    private var pending = false
    private var stableFrames = 0

    val isPending: Boolean
        get() = pending

    init {
        require(requiredStableFrames >= 1)
    }

    fun reset() {
        pending = false
        stableFrames = 0
    }

    /** Returns true for recovered frames that must not enter pre-fly scoring. */
    fun shouldSkipScoring(
        roundActive: Boolean,
        phase: IndicatorPhase,
        visualLive: Boolean
    ): Boolean {
        if (!roundActive) {
            reset()
            return false
        }
        if (phase == IndicatorPhase.RED) return false
        if (!visualLive) {
            pending = true
            stableFrames = 0
            return false
        }
        if (phase != IndicatorPhase.GREEN || !pending) return false

        stableFrames++
        if (stableFrames >= requiredStableFrames) {
            pending = false
            stableFrames = 0
        }
        return true
    }
}
