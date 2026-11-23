package com.blvckson.flyawayindicator

/** Owns the three visible states and the guarded early round-start gate. */
internal enum class IndicatorPhase {
    BLACK,
    GREEN,
    RED
}

internal object LiveStartPolicy {
    fun isConfirmed(
        stableHits: Int,
        missHits: Int,
        confidence: Double,
        centralScore: Double,
        sceneScore: Double,
        planeScore: Double
    ): Boolean {
        if (missHits != 0) return false

        val strongTwoFrameStart = stableHits >= 2 &&
            confidence >= 0.40 &&
            centralScore >= 0.10 &&
            sceneScore >= 0.18 &&
            planeScore >= 0.10

        val normalConfirmedStart = stableHits >= 3 && confidence >= 0.60
        return strongTwoFrameStart || normalConfirmedStart
    }
}

/**
 * Owns one alert lifecycle per positively identified game round.
 *
 * A round can issue one GREEN -> RED transition only. Temporary loss of live-screen
 * evidence may hide GREEN, but it never ends the round or releases the alert lock.
 * Only a confirmed round-end signal from the service finalizes the round: either
 * explicit "FLEW AWAY" OCR or the independently corroborated result-state fallback.
 * Detector loss alone never releases the lock. This fails closed against duplicate alerts.
 */
internal class IndicatorPhaseMachine(
    private val missingFramesToHide: Int = 3,
    private val redVisibleDurationMs: Long = 1_200L
) {
    var phase: IndicatorPhase = IndicatorPhase.BLACK
        private set

    private var missingLiveFrames = 0
    private var redStartedAtMs = 0L
    private var roundActive = false
    private var awaitingNextRound = false
    private var endMarkerLatched = false
    private var redIssuedThisRound = false
    private var consistencyFallbackPending = false

    /** True only when the current RED came from the consistency fallback, not signal scoring. */
    var lastAlertWasConsistencyFallback: Boolean = false
        private set

    /** Increments once when a new, positively identified round is first accepted. */
    var roundStartGeneration: Long = 0L
        private set

    /** Increments once when a confirmed round-end signal finalizes an active round. */
    var roundEndGeneration: Long = 0L
        private set

    val isRoundActive: Boolean
        get() = roundActive

    init {
        require(missingFramesToHide >= 1)
        require(redVisibleDurationMs >= 1L)
    }

    fun observeLive(
        visualLive: Boolean,
        endConfirmed: Boolean,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): IndicatorPhase {
        if (!endConfirmed) endMarkerLatched = false

        // Detector/OCR misses alone are not proof that a flight ended. The service
        // supplies endConfirmed only for explicit FLEW AWAY OCR or a corroborated
        // final-result fallback; that confirmed signal releases this round's lock.
        if (roundActive && endConfirmed && !endMarkerLatched) {
            endMarkerLatched = true
            finishRound()
            return phase
        }

        // RED is a short visual alert, not the lifetime of the round lock.
        if (phase == IndicatorPhase.RED &&
            nowMs - redStartedAtMs >= redVisibleDurationMs
        ) {
            phase = IndicatorPhase.BLACK
            awaitingNextRound = true
            clearMissingLive()
        }

        if (phase == IndicatorPhase.BLACK) {
            // After RED, remain BLACK until a confirmed round-end signal is seen.
            if (awaitingNextRound || !visualLive) return phase

            clearMissingLive()
            if (!roundActive) {
                roundActive = true
                roundStartGeneration++
                consistencyFallbackPending = true
                lastAlertWasConsistencyFallback = false
            }
            // A BLACK phase caused by a temporary detector miss may resume GREEN,
            // but an already-issued RED can never be re-armed within this round.
            if (!redIssuedThisRound) phase = IndicatorPhase.GREEN
            if (endConfirmed) endMarkerLatched = true
            return phase
        }

        if (phase == IndicatorPhase.GREEN) {
            if (visualLive) {
                clearMissingLive()
            } else {
                missingLiveFrames++
                if (missingLiveFrames >= missingFramesToHide) {
                    // Hide a stale GREEN overlay, but keep roundActive and the alert
                    // lock intact. Reacquisition resumes this same round; it does not
                    // create a new round or reset its timing/baseline.
                    phase = IndicatorPhase.BLACK
                    clearMissingLive()
                }
            }
        }
        return phase
    }

    /** RED can be issued only once between affirmative round starts and ends. */
    fun markPreFlyAway(nowMs: Long = System.nanoTime() / 1_000_000L): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN && roundActive && !redIssuedThisRound) {
            redIssuedThisRound = true
            consistencyFallbackPending = false
            lastAlertWasConsistencyFallback = false
            phase = IndicatorPhase.RED
            redStartedAtMs = nowMs
        }
        return phase
    }

    /**
     * Issues one consistency warning on the first subsequent positively verified
     * live capture. This is a per-round fallback, not a prediction of the exact
     * pre-fly-away moment. Existing signal scoring may still issue RED earlier.
     */
    fun markConsistencyWarningIfDue(
        visualLive: Boolean,
        endConfirmed: Boolean,
        isRoundStartFrame: Boolean,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): Boolean {
        if (!roundActive || endConfirmed || !consistencyFallbackPending ||
            isRoundStartFrame || !visualLive || phase != IndicatorPhase.GREEN
        ) return false

        val phaseBeforeWarning = phase
        markPreFlyAway(nowMs)
        if (phaseBeforeWarning == IndicatorPhase.GREEN && phase == IndicatorPhase.RED) {
            lastAlertWasConsistencyFallback = true
            return true
        }
        return false
    }

    fun reset() {
        phase = IndicatorPhase.BLACK
        clearMissingLive()
        redStartedAtMs = 0L
        roundActive = false
        awaitingNextRound = false
        endMarkerLatched = false
        redIssuedThisRound = false
        consistencyFallbackPending = false
        lastAlertWasConsistencyFallback = false
    }

    private fun clearMissingLive() {
        missingLiveFrames = 0
    }

    private fun finishRound() {
        val hadRound = roundActive
        phase = IndicatorPhase.BLACK
        clearMissingLive()
        redStartedAtMs = 0L
        roundActive = false
        awaitingNextRound = false
        redIssuedThisRound = false
        consistencyFallbackPending = false
        lastAlertWasConsistencyFallback = false
        if (hadRound) roundEndGeneration++
    }
}
