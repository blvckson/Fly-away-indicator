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
    // OCR can retain "FLEW AWAY" for a short period after the next round appears.
    // Consume each end-marker episode once so it resets the old round without
    // repeatedly pushing the newly detected round back to BLACK.
    private var endMarkerLatched = false

    init {
        require(missingFramesToReset >= 1)
    }

    fun observeLive(visualLive: Boolean, endConfirmed: Boolean): IndicatorPhase {
        if (!endConfirmed) endMarkerLatched = false

        // An end marker must end an already active round even if the detector
        // still sees stale aircraft/UI pixels in that same captured frame.
        if (phase != IndicatorPhase.BLACK && endConfirmed && !endMarkerLatched) {
            endMarkerLatched = true
            resetPhase()
            return phase
        }

        // In BLACK, fresh live evidence wins over stale OCR from the preceding
        // round. Latch that OCR episode so it cannot flicker the new round.
        if (phase == IndicatorPhase.BLACK) {
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

    fun markPreFlyAway(): IndicatorPhase {
        if (phase == IndicatorPhase.GREEN) phase = IndicatorPhase.RED
        return phase
    }

    fun reset() {
        resetPhase()
        endMarkerLatched = false
    }

    private fun resetPhase() {
        phase = IndicatorPhase.BLACK
        missingLiveFrames = 0
    }
}
