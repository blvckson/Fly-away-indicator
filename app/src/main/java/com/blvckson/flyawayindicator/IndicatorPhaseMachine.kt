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
            missingLiveFrames = 0
            if (phase == IndicatorPhase.BLACK) {
                phase = IndicatorPhase.GREEN
            }
        } else if (phase != IndicatorPhase.BLACK) {
            if (endConfirmed) {
                reset()
            } else {
                missingLiveFrames++
                if (missingLiveFrames >= missingFramesToReset) reset()
            }
        }
        return phase
    }

    fun markPreFlyAway(): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN) phase = IndicatorPhase.RED
        return phase
    }

    fun reset() {
        phase = IndicatorPhase.BLACK
        missingLiveFrames = 0
    }
}
