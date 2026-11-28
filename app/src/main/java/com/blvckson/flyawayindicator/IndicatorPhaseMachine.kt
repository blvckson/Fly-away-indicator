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
 * Owns one alert lifecycle per positively identified game round inside an Aviator
 * screen session. Each round can issue one GREEN -> RED transition only.
 * Temporary loss of aircraft/live-frame evidence does not hide GREEN or release
 * the per-round alert lock. Only a confirmed round-end signal releases that lock;
 * screen-presence evidence alone controls when the indicator resets to BLACK.
 */
internal class IndicatorPhaseMachine(
    private val redVisibleDurationMs: Long = 1_200L,
    private val screenMissingGraceMs: Long = 6_000L
) {
    var phase: IndicatorPhase = IndicatorPhase.BLACK
        private set

    private var aviatorScreenActive = false
    private var redStartedAtMs = 0L
    private var roundActive = false
    private var endMarkerLatched = false
    private var redIssuedThisRound = false
    private var screenMissingSinceMs: Long? = null

    /** Increments once when a new, positively identified round is first accepted. */
    var roundStartGeneration: Long = 0L
        private set

    /** Increments once when a confirmed round-end signal finalizes an active round. */
    var roundEndGeneration: Long = 0L
        private set

    val isRoundActive: Boolean
        get() = roundActive

    val isAviatorScreenActive: Boolean
        get() = aviatorScreenActive

    init {
        require(redVisibleDurationMs >= 1L)
        require(screenMissingGraceMs >= 1L)
    }

    /**
     * Owns the outer screen-session state. Black is used only before the Aviator
     * screen is detected or after its presence evidence expires. Once active,
     * GREEN remains visible between rounds; RED is the only temporary change.
     */
    fun observeScreenPresence(
        screenPresent: Boolean,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): IndicatorPhase {
        if (!screenPresent) {
            if (aviatorScreenActive) {
                val missingSince = screenMissingSinceMs
                if (missingSince == null || nowMs < missingSince) {
                    screenMissingSinceMs = nowMs
                } else if (nowMs - missingSince >= screenMissingGraceMs) {
                    // Close the whole session only after continuous absence. Short OCR
                    // gaps between rounds must not reset the phase machine or round lock.
                    reset()
                }
            }
            return phase
        }

        // Any fresh positive evidence cancels the pending close timer.
        screenMissingSinceMs = null
        if (!aviatorScreenActive) {
            aviatorScreenActive = true
            phase = IndicatorPhase.GREEN
            redStartedAtMs = 0L
            roundActive = false
            endMarkerLatched = false
            redIssuedThisRound = false
        }
        return phase
    }

    fun observeLive(
        visualLive: Boolean,
        endConfirmed: Boolean,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ): IndicatorPhase {
        // A positively identified live round can establish the screen session
        // even before the first OCR identity sample is available.
        if (visualLive && !aviatorScreenActive) observeScreenPresence(true, nowMs)

        if (!endConfirmed) endMarkerLatched = false

        // A confirmed result ends only the per-round lock. It does not close the
        // Aviator screen session, so the indicator returns/remains GREEN.
        if (endConfirmed) {
            if (roundActive && !endMarkerLatched) {
                endMarkerLatched = true
                finishRound()
                return phase
            }
            if (!roundActive) endMarkerLatched = true
        }

        if (!aviatorScreenActive) return IndicatorPhase.BLACK

        // Keep the existing RED display duration, then return directly to GREEN.
        // The current round remains locked against another RED until its end.
        if (phase == IndicatorPhase.RED &&
            nowMs - redStartedAtMs >= redVisibleDurationMs
        ) {
            phase = IndicatorPhase.GREEN
            redStartedAtMs = 0L
        }

        // A new positively-live round starts a new alert lifecycle without BLACK.
        if (!roundActive && visualLive && !endMarkerLatched) {
            roundActive = true
            roundStartGeneration++
            redIssuedThisRound = false
            phase = IndicatorPhase.GREEN
        }

        // Temporary detector/OCR misses do not hide GREEN. Only screen presence
        // evidence controls the GREEN -> BLACK session reset.
        if (phase == IndicatorPhase.BLACK) phase = IndicatorPhase.GREEN
        return phase
    }

    /** RED can be issued only once between affirmative round starts and ends. */
    fun markPreFlyAway(nowMs: Long = System.nanoTime() / 1_000_000L): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN && roundActive && !redIssuedThisRound) {
            redIssuedThisRound = true
            phase = IndicatorPhase.RED
            redStartedAtMs = nowMs
        }
        return phase
    }

    fun reset() {
        aviatorScreenActive = false
        phase = IndicatorPhase.BLACK
        redStartedAtMs = 0L
        roundActive = false
        endMarkerLatched = false
        redIssuedThisRound = false
        screenMissingSinceMs = null
    }

    private fun finishRound() {
        val hadRound = roundActive
        phase = if (aviatorScreenActive) IndicatorPhase.GREEN else IndicatorPhase.BLACK
        redStartedAtMs = 0L
        roundActive = false
        redIssuedThisRound = false
        if (hadRound) roundEndGeneration++
    }
}
