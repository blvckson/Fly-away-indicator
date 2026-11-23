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
 * Owns one indicator lifecycle per verified round.
 *
 * A round can issue at most one GREEN -> RED transition. Hiding the short RED
 * display never rearms GREEN. A round is released only by an explicit end marker
 * or by live-screen absence that persists for both a minimum frame count and a
 * minimum elapsed duration, so transient detector misses cannot unlock another
 * alert in the same flight.
 */
internal class IndicatorPhaseMachine(
    private val missingFramesToReset: Int = 3,
    private val redVisibleDurationMs: Long = 1_200L,
    private val missingLiveDurationMs: Long = 1_800L
) {
    var phase: IndicatorPhase = IndicatorPhase.BLACK
        private set

    private var missingLiveFrames = 0
    private var missingLiveSinceMs: Long? = null
    private var redStartedAtMs = 0L
    private var awaitingNextRound = false
    private var endMarkerLatched = false
    private var redIssuedThisRound = false

    /** Increments exactly once whenever an active round is genuinely finalized. */
    var roundEndGeneration: Long = 0L
        private set

    init {
        require(missingFramesToReset >= 1)
        require(redVisibleDurationMs >= 1L)
        require(missingLiveDurationMs >= 1L)
    }

    fun observeLive(
        visualLive: Boolean,
        endConfirmed: Boolean,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): IndicatorPhase {
        if (!endConfirmed) endMarkerLatched = false

        val roundInProgress = phase != IndicatorPhase.BLACK ||
            awaitingNextRound || redIssuedThisRound

        // A verified end marker can finish a round immediately, including after
        // the brief RED display has already returned to BLACK.
        if (roundInProgress && endConfirmed && !endMarkerLatched) {
            endMarkerLatched = true
            finishRound()
            return phase
        }

        // RED is a brief visual alert. Its expiry does not release the per-round
        // alert lock or allow GREEN to reappear in the same flight.
        if (phase == IndicatorPhase.RED &&
            nowMs - redStartedAtMs >= redVisibleDurationMs
        ) {
            phase = IndicatorPhase.BLACK
            awaitingNextRound = true
        }

        if (phase == IndicatorPhase.BLACK) {
            if (awaitingNextRound) {
                if (visualLive) {
                    clearMissingLive()
                } else if (hasSustainedMissingLive(nowMs)) {
                    finishRound()
                }
                return phase
            }

            if (visualLive) {
                clearMissingLive()
                phase = IndicatorPhase.GREEN
                if (endConfirmed) endMarkerLatched = true
            }
            return phase
        }

        if (visualLive) {
            clearMissingLive()
        } else if (hasSustainedMissingLive(nowMs)) {
            finishRound()
        }
        return phase
    }

    /**
     * RED can be issued only once between genuine round starts/ends.
     * Repeated evidence, delayed callbacks, or BLACK after the alert window
     * cannot issue a second alert for the same round.
     */
    fun markPreFlyAway(nowMs: Long = System.nanoTime() / 1_000_000L): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN && !redIssuedThisRound) {
            redIssuedThisRound = true
            phase = IndicatorPhase.RED
            redStartedAtMs = nowMs
        }
        return phase
    }

    fun reset() {
        phase = IndicatorPhase.BLACK
        clearMissingLive()
        redStartedAtMs = 0L
        awaitingNextRound = false
        endMarkerLatched = false
        redIssuedThisRound = false
    }

    private fun hasSustainedMissingLive(nowMs: Long): Boolean {
        missingLiveFrames++
        if (missingLiveSinceMs == null) missingLiveSinceMs = nowMs
        val missingDuration = (nowMs - (missingLiveSinceMs ?: nowMs)).coerceAtLeast(0L)
        return missingLiveFrames >= missingFramesToReset &&
            missingDuration >= missingLiveDurationMs
    }

    private fun clearMissingLive() {
        missingLiveFrames = 0
        missingLiveSinceMs = null
    }

    private fun finishRound() {
        val hadRound = phase != IndicatorPhase.BLACK ||
            awaitingNextRound || redIssuedThisRound
        phase = IndicatorPhase.BLACK
        clearMissingLive()
        redStartedAtMs = 0L
        awaitingNextRound = false
        redIssuedThisRound = false
        if (hadRound) roundEndGeneration++
    }
}
