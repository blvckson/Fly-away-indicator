package com.blvckson.flyawayindicator

/**
 * Confirms the end of a round when the explicit "FLEW AWAY" OCR event was missed.
 *
 * The aircraft must have been observed in this round, then remain absent for
 * 2.4 seconds, and fresh OCR must confirm the verified multiplier + BET/AUTO
 * ready layout on two distinct observations with CASH OUT absent. This avoids
 * relying on a separate red-only OCR pass to read the final multiplier.
 */
internal class IndicatorRoundEndDetector(
    private val requiredPlaneAbsenceMs: Long = 2_400L,
    private val maximumTextAgeMs: Long = 1_200L,
    private val requiredBetReadyTextSamples: Int = 2
) {
    private var planeSeenInRound = false
    private var planeMissingSinceMs = 0L
    private var lastTextObservedAtMs = 0L
    private var betReadyTextSamples = 0

    init {
        require(requiredPlaneAbsenceMs >= 1L)
        require(maximumTextAgeMs >= 1L)
        require(requiredBetReadyTextSamples >= 2)
    }

    @Synchronized
    fun reset() {
        planeSeenInRound = false
        planeMissingSinceMs = 0L
        lastTextObservedAtMs = 0L
        betReadyTextSamples = 0
    }

    /** Seed identity from the accepted live frame; pre-round OCR is not counted. */
    @Synchronized
    fun beginRound(planePresent: Boolean, lastObservedTextAtMs: Long) {
        reset()
        planeSeenInRound = planePresent
        lastTextObservedAtMs = lastObservedTextAtMs
    }

    @Synchronized
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
            }
            planeSeenInRound = true
        } else if (planeSeenInRound && planeMissingSinceMs == 0L) {
            planeMissingSinceMs = nowElapsedMs
            betReadyTextSamples = 0
        }

        // Only strictly newer OCR results count. A delayed callback or repeatedly
        // reused cached frame cannot manufacture multiple result-screen samples.
        if (evidence.observedAtMillis > lastTextObservedAtMs) {
            lastTextObservedAtMs = evidence.observedAtMillis
            if (isBetReadyEvidence(evidence) && isTextFresh(nowWallMs, evidence)) {
                betReadyTextSamples =
                    (betReadyTextSamples + 1).coerceAtMost(requiredBetReadyTextSamples + 1)
            } else {
                betReadyTextSamples = 0
            }
        }
    }

    /**
     * Confirm only after sustained aircraft absence and repeated, fresh result UI.
     * Brief detector loss, stale/unrelated text, or a visible CASH OUT control cannot
     * release the per-round alert lock.
     */
    @Synchronized
    fun isConfirmed(
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
