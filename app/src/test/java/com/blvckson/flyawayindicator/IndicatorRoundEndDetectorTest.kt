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
    fun ocrMissFallbackRequiresAircraftLossBetReadyTextAndStableRedResult() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        val evidence = readyEvidence(11_900L)

        assertTrue(detector.shouldStartRedMultiplierProbe(2_900L, 11_900L, evidence))
        detector.recordRedMultiplier("1.84x", 2_900L, 11_900L, detector.generation)
        detector.recordRedMultiplier("1.84x", 3_400L, 12_400L, detector.generation)
        assertFalse(detector.isConfirmed(3_400L, 12_400L, evidence))
        detector.recordRedMultiplier("1.84x", 3_800L, 12_800L, detector.generation)
        assertTrue(detector.isConfirmed(3_800L, 12_800L, evidence))
    }

    @Test
    fun cashOutControlBlocksTheFallbackEvenWhenAPlaneIsMissing() {
        val detector = IndicatorRoundEndDetector()
        detector.beginRound(planePresent = true, lastObservedTextAtMs = 10_000L)
        detector.observeFrame(1_100L, 10_100L, false, readyEvidence(10_100L))
        detector.observeFrame(1_400L, 10_400L, false, readyEvidence(10_400L))
        detector.observeFrame(2_900L, 11_900L, false, readyEvidence(11_900L))
        val cashOut = readyEvidence(11_900L, cashOutPresent = true)
        assertFalse(detector.shouldStartRedMultiplierProbe(2_900L, 11_900L, cashOut))
    }

    @Test
    fun anUnrelatedOrStaleOcrSampleCannotStartTheFallback() {
        val detector = IndicatorRoundEndDetector()
        detector.beginRound(planePresent = true, lastObservedTextAtMs = 10_000L)
        detector.observeFrame(1_100L, 10_100L, false, readyEvidence(10_100L))
        detector.observeFrame(1_400L, 10_400L, false, readyEvidence(10_400L))
        detector.observeFrame(2_900L, 11_900L, false, readyEvidence(11_900L))
        val unrelated = AviatorTextEvidence(observedAtMillis = 11_900L)
        assertFalse(detector.shouldStartRedMultiplierProbe(2_900L, 11_900L, unrelated))
        assertFalse(detector.shouldStartRedMultiplierProbe(2_900L, 13_200L, readyEvidence(11_900L)))
    }

    @Test
    fun reacquiredAircraftCancelsThePendingRoundEndCandidate() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        detector.recordRedMultiplier("1.84x", 2_900L, 11_900L, detector.generation)
        detector.observeFrame(3_000L, 12_000L, true, readyEvidence(12_000L))
        detector.recordRedMultiplier("1.84x", 3_800L, 12_800L, detector.generation)
        assertFalse(detector.isConfirmed(3_800L, 12_800L, readyEvidence(12_000L)))
    }

    @Test
    fun callbacksFromAnEarlierRoundAreIgnoredAfterReset() {
        val detector = IndicatorRoundEndDetector()
        establishCandidate(detector)
        val oldGeneration = detector.generation
        detector.reset()
        detector.recordRedMultiplier("1.84x", 3_800L, 12_800L, oldGeneration)
        assertFalse(detector.isConfirmed(3_800L, 12_800L, readyEvidence(12_800L)))
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
