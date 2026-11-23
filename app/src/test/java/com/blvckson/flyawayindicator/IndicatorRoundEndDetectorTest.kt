package com.blvckson.flyawayindicator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorRoundEndDetectorTest {
    private fun readyEvidence(observedAt: Long, cashOutPresent: Boolean = false) =
        AviatorTextEvidence(
            verified = true,
            multiplierPresent = true,
            gameControlsPresent = true,
            betButtonPresent = true,
            cashOutPresent = cashOutPresent,
            endConfirmed = false,
            observedAtMillis = observedAt
        )

    private fun establishCandidate(detector: IndicatorRoundEndDetector) {
        detector.beginRound(planePresent = true, lastObservedTextAtMs = 10_000L)
        detector.observeFrame(1_100L, 10_100L, false, readyEvidence(10_100L))
        detector.observeFrame(1_400L, 10_400L, false, readyEvidence(10_400L))
        detector.observeFrame(2_900L, 11_900L, false, readyEvidence(11_900L))
    }

    @Test
    fun ocrMissFallbackConfirmsAfterSustainedPlaneLossAndFreshBetReadyText() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        val evidence = readyEvidence(11_900L)

        // Plane absence began at 1,100 ms. At 3,499 ms it is still 1 ms
        // short of the 2.4-second safety window; at 3,500 ms it is confirmed.
        assertFalse(detector.isConfirmed(3_499L, 12_500L, evidence))
        assertTrue(detector.isConfirmed(3_500L, 12_500L, evidence))
    }

    @Test
    fun cashOutControlBlocksTheFallbackEvenWhenAPlaneIsMissing() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        val cashOut = readyEvidence(11_900L, cashOutPresent = true)
        assertFalse(detector.isConfirmed(3_500L, 12_500L, cashOut))
    }

    @Test
    fun unrelatedOrStaleOcrCannotConfirmTheFallback() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        val unrelated = AviatorTextEvidence(observedAtMillis = 11_900L)
        assertFalse(detector.isConfirmed(3_500L, 12_500L, unrelated))
        assertFalse(detector.isConfirmed(3_500L, 13_200L, readyEvidence(11_900L)))
    }

    @Test
    fun reacquiredAircraftCancelsThePendingRoundEndCandidate() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        detector.observeFrame(3_000L, 12_000L, true, readyEvidence(12_000L))
        assertFalse(detector.isConfirmed(5_000L, 14_000L, readyEvidence(14_000L)))
    }

    @Test
    fun cachedPreRoundOcrDoesNotCountAsPostRoundEndEvidence() {
        val detector = IndicatorRoundEndDetector(maximumTextAgeMs = 5_000L)
        detector.beginRound(planePresent = true, lastObservedTextAtMs = 10_000L)
        val firstPostRoundSample = readyEvidence(10_100L)
        detector.observeFrame(1_100L, 10_100L, false, firstPostRoundSample)

        // The 2.4-second absence window has elapsed, but repeating the same cached
        // OCR timestamp still counts as only one post-round observation.
        detector.observeFrame(3_500L, 12_500L, false, firstPostRoundSample)
        assertFalse(detector.isConfirmed(3_500L, 12_500L, firstPostRoundSample))

        val secondPostRoundSample = readyEvidence(12_501L)
        detector.observeFrame(3_501L, 12_501L, false, secondPostRoundSample)
        assertTrue(detector.isConfirmed(3_501L, 12_501L, secondPostRoundSample))
    }

    @Test
    fun anUnseenAircraftCannotCauseAFalseRoundEnd() {
        val detector = IndicatorRoundEndDetector()
        detector.beginRound(planePresent = false, lastObservedTextAtMs = 10_000L)
        detector.observeFrame(5_000L, 15_000L, false, readyEvidence(15_000L))
        detector.observeFrame(5_500L, 15_500L, false, readyEvidence(15_500L))
        assertFalse(detector.isConfirmed(10_000L, 20_000L, readyEvidence(20_000L)))
    }

    @Test
    fun aOneOrTwoFrameMissRequiresTwoFreshFramesBeforeScoringResumes() {
        val gate = IndicatorReacquisitionGate(requiredStableFrames = 2)
        assertFalse(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))

        gate.shouldSkipScoring(true, IndicatorPhase.GREEN, false)
        assertTrue(gate.isPending)
        assertTrue(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
        assertTrue(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
        assertFalse(gate.isPending)
        assertFalse(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
    }

    @Test
    fun longerLossAlsoWaitsForTwoStableFramesAfterBlackToGreenResume() {
        val gate = IndicatorReacquisitionGate(requiredStableFrames = 2)
        gate.shouldSkipScoring(true, IndicatorPhase.GREEN, false)
        gate.shouldSkipScoring(true, IndicatorPhase.GREEN, false)
        gate.shouldSkipScoring(true, IndicatorPhase.BLACK, false)
        assertTrue(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
        assertTrue(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
        assertFalse(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
    }

    @Test
    fun aFreshRoundDoesNotInheritThePreviousRoundsReacquisitionHold() {
        val gate = IndicatorReacquisitionGate(requiredStableFrames = 2)
        gate.shouldSkipScoring(true, IndicatorPhase.GREEN, false)
        assertTrue(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
        assertFalse(gate.shouldSkipScoring(false, IndicatorPhase.BLACK, false))
        assertFalse(gate.shouldSkipScoring(true, IndicatorPhase.GREEN, true))
    }
}