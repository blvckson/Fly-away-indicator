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

internal class IndicatorPhaseMachine(
    private val missingFramesToReset: Int = 3,
    private val redVisibleDurationMs: Long = 1_200L
) {
    var phase: IndicatorPhase = IndicatorPhase.BLACK
        private set

    private var missingLiveFrames = 0
    private var redStartedAtMs = 0L
    // Once the brief RED alert is hidden, keep BLACK during the same live round.
    // Re-arm only after the old live scene disappears or an explicit end marker.
    private var awaitingNextRound = false
    // OCR can retain "FLEW AWAY" for a short period after the next round appears.
    // Consume each end-marker episode once so it resets the old round without
    // repeatedly pushing the newly detected round back to BLACK.
    private var endMarkerLatched = false

    val isAwaitingNextRound: Boolean
        get() = awaitingNextRound

    init {
        require(missingFramesToReset >= 1)
        require(redVisibleDurationMs >= 1L)
    }

    fun observeLive(
        visualLive: Boolean,
        endConfirmed: Boolean,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): IndicatorPhase {
        if (!endConfirmed) endMarkerLatched = false

        // An end marker must end an already active round even if the detector
        // still sees stale aircraft/UI pixels in that same captured frame.
        if (phase != IndicatorPhase.BLACK && endConfirmed && !endMarkerLatched) {
            endMarkerLatched = true
            resetPhase()
            return phase
        }

        // RED is a brief alert, not a state that should remain visible until the
        // crash/end marker. Hide it after the alert window, then hold BLACK until
        // the current live scene has actually ended so it cannot flicker GREEN.
        if (phase == IndicatorPhase.RED && nowMs - redStartedAtMs >= redVisibleDurationMs) {
            phase = IndicatorPhase.BLACK
            awaitingNextRound = true
            missingLiveFrames = 0
        }

        // In BLACK, fresh live evidence starts a round only when the previous
        // round has been cleared. While awaitingNextRound, require consecutive
        // non-live frames before accepting the next live scene.
        if (phase == IndicatorPhase.BLACK) {
            if (awaitingNextRound) {
                if (visualLive) {
                    missingLiveFrames = 0
                } else {
                    missingLiveFrames++
                    if (missingLiveFrames >= missingFramesToReset) {
                        awaitingNextRound = false
                        missingLiveFrames = 0
                    }
                }
                return phase
            }
            if (visualLive) {
                missingLiveFrames = 0
                phase = IndicatorPhase.GREEN
                if (endConfirmed) endMarkerLatched = true
            }
            return phase
        }

        if (visualLive) {
            missingLiveFrames = 0
        } else {
            missingLiveFrames++
            if (missingLiveFrames >= missingFramesToReset) resetPhase()
        }
        return phase
    }

    /**
     * Restore GREEN once, only when the same verified live round is still present.
     * ScreenMonitorService applies the long-round time and one-rearm-per-round gates.
     */
    fun rearmForLongRound(): Boolean {
        if (phase != IndicatorPhase.BLACK || !awaitingNextRound) return false
        phase = IndicatorPhase.GREEN
        awaitingNextRound = false
        missingLiveFrames = 0
        return true
    }

    fun markPreFlyAway(nowMs: Long = System.nanoTime() / 1_000_000L): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN) {
            phase = IndicatorPhase.RED
            redStartedAtMs = nowMs
        }
        return phase
    }

    fun reset() {
        resetPhase()
        endMarkerLatched = false
        awaitingNextRound = false
    }

    private fun resetPhase() {
        phase = IndicatorPhase.BLACK
        missingLiveFrames = 0
        redStartedAtMs = 0L
        awaitingNextRound = false
    }
}
