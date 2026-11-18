package com.blvckson.flyawayindicator

/**
 * Owns the indicator's three visible states.
 *
 * The live-screen detector already confirms multiple consecutive aircraft/game
 * frames. Do not add another start-confirmation counter here: that creates a
 * second delay before BLACK -> GREEN. End transitions are intentionally
 * asymmetric: an explicit end marker resets immediately; otherwise three
 * consecutive frames without live evidence reset the indicator.
 */
internal enum class IndicatorPhase {
    BLACK,
    GREEN,
    RED
}

internal class IndicatorPhaseMachine(
    private val missingFramesToReset: Int = 3
) {
    var phase: IndicatorPhase = IndicatorPhase.BLACK
        private set

    private var missingLiveFrames = 0

    init {
        require(missingFramesToReset >= 1)
    }

    fun observeLive(visualLive: Boolean, endConfirmed: Boolean): IndicatorPhase {
        if (visualLive) {
            // Fresh live visual evidence takes priority over stale OCR end text.
            // This lets the next round turn green as soon as its visual gate passes.
            missingLiveFrames = 0
            if (phase == IndicatorPhase.BLACK) {
                phase = IndicatorPhase.GREEN
            }
        } else if (phase != IndicatorPhase.BLACK) {
            if (endConfirmed) {
                reset()
            } else {
                missingLiveFrames++
                if (missingLiveFrames >= missingFramesToReset) {
                    reset()
                }
            }
        }
        return phase
    }

    /** RED is latched for the current round until the round-end reset. */
    fun markPreFlyAway(): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN) {
            phase = IndicatorPhase.RED
        }
        return phase
    }

    fun reset() {
        phase = IndicatorPhase.BLACK
        missingLiveFrames = 0
    }
}
