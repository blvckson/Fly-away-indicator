package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreFlyTimingTest {
    @Test
    fun trendCanBeMeasuredFromTwoCurrentRoundSamples() {
        assertEquals(0.5, PreFlyTiming.trend(listOf(0.02)), 0.000001)
        assertEquals(1.0, PreFlyTiming.trend(listOf(0.01, 0.04)), 0.000001)
        assertTrue(PreFlyTiming.trend(listOf(0.04, 0.01)) < 0.5)
    }

    @Test
    fun medianBaselineIgnoresOneLargeOpeningSpike() {
        assertEquals(0.115, PreFlyTiming.medianBaseline(listOf(0.10, 0.11, 0.12, 0.99)), 0.000001)
    }

    @Test
    fun candidatePreservesBuild74SignalWeights() {
        val score = PreFlyTiming.candidate(
            PreFlySignals(trend = 0.70, central = 0.50, plane = 0.80, movement = 0.40)
        )
        assertEquals(0.602, score, 0.000001)
    }

    @Test
    fun eachSignalHasAnIndependentZeroToOneHundredIndex() {
        val scores = PreFlyTiming.signalScores(
            PreFlySignals(trend = 1.0, central = 1.0, plane = 1.0, movement = 1.0)
        )
        assertEquals(100, scores.trend)
        assertEquals(100, scores.central)
        assertEquals(100, scores.aircraft)
        assertEquals(100, scores.movement)
        assertEquals(100, scores.combined)
    }

    @Test
    fun neutralTrendIsReportedAsFiftyNotMisrepresentedAsFullEvidence() {
        val scores = PreFlyTiming.signalScores(
            PreFlySignals(trend = 0.5, central = 0.0, plane = 0.0, movement = 0.0)
        )
        assertEquals(50, scores.trend)
        assertEquals(19, scores.combined)
    }

    @Test
    fun preFlyRequiresBaselineRiseAndCorroboratingSignals() {
        val strong = PreFlySignals(trend = 0.72, central = 0.20, plane = 0.50, movement = 0.20)
        val candidate = PreFlyTiming.candidate(strong)
        assertTrue(PreFlyTiming.isEvidence(strong, candidate, 0.55, 0.30))
    }

    @Test
    fun fastCalibrationStillRequiresThreeIndependentSignals() {
        val strong = PreFlySignals(trend = 0.90, central = 0.80, plane = 0.70, movement = 0.80)
        val candidate = PreFlyTiming.candidate(strong)
        assertTrue(PreFlyTiming.isEvidence(strong, candidate, 0.80, 0.50, baselineSampleCount = 2))

        val weak = PreFlySignals(trend = 0.95, central = 0.05, plane = 0.05, movement = 0.05)
        assertFalse(PreFlyTiming.isEvidence(
            weak, PreFlyTiming.candidate(weak), 0.80, 0.10, baselineSampleCount = 2
        ))
    }

    @Test
    fun oneSignalCannotTriggerRed() {
        val weak = PreFlySignals(trend = 1.0, central = 0.05, plane = 0.05, movement = 0.05)
        val candidate = PreFlyTiming.candidate(weak)
        assertFalse(PreFlyTiming.isEvidence(weak, candidate, 0.80, 0.10))
    }

    @Test
    fun rollingBaselineLearnsNormalDriftButFreezesOnMultipleEarlyHints() {
        val normal = PreFlySignals(trend = 0.52, central = 0.04, plane = 0.04, movement = 0.03)
        assertTrue(PreFlyTiming.shouldLearnBaseline(0.31, 0.30, normal))

        val unusual = PreFlySignals(trend = 0.65, central = 0.12, plane = 0.12, movement = 0.03)
        assertFalse(PreFlyTiming.shouldLearnBaseline(0.33, 0.30, unusual))
    }

    @Test
    fun weakFrameDecaysHoldByOneInsteadOfClearingIt() {
        assertEquals(2, PreFlyTiming.updatedHold(3, false))
        assertEquals(4, PreFlyTiming.updatedHold(3, true))
    }

    @Test
    fun confirmationAdaptsToCaptureCadenceAndSignalCorroboration() {
        assertEquals(3, PreFlyTiming.requiredConfirmations(50L, 2))
        assertEquals(2, PreFlyTiming.requiredConfirmations(100L, 2))
        assertEquals(2, PreFlyTiming.requiredConfirmations(50L, 3))
        assertEquals(2, PreFlyTiming.requiredConfirmations(220L, 4))
    }
}
