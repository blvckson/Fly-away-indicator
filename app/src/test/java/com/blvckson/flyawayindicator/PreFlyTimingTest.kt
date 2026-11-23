package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreFlyTimingTest {
    @Test
    fun medianBaselineIgnoresOneLargeOpeningSpike() {
        assertEquals(0.115, PreFlyTiming.medianBaseline(listOf(0.10, 0.11, 0.12, 0.99)), 0.000001)
    }

    @Test
    fun candidateIncludesTheNewMultiplierAppearanceChannel() {
        val score = PreFlyTiming.candidate(
            PreFlySignals(trend = 0.70, central = 0.50, plane = 0.80, movement = 0.40)
        )
        assertEquals(0.52976, score, 0.000001)
    }

    @Test
    fun eachSignalHasAnIndependentZeroToOneHundredIndex() {
        val scores = PreFlyTiming.signalScores(
            PreFlySignals(
                trend = 1.0, central = 1.0, plane = 1.0, movement = 1.0, multiplier = 1.0
            )
        )
        assertEquals(100, scores.trend)
        assertEquals(100, scores.central)
        assertEquals(100, scores.aircraft)
        assertEquals(100, scores.movement)
        assertEquals(100, scores.multiplier)
        assertEquals(100, scores.combined)
    }

    @Test
    fun neutralTrendIsReportedAsFiftyNotMisrepresentedAsFullEvidence() {
        val scores = PreFlyTiming.signalScores(
            PreFlySignals(trend = 0.5, central = 0.0, plane = 0.0, movement = 0.0)
        )
        assertEquals(50, scores.trend)
        assertEquals(16, scores.combined)
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
    fun launchFramesCannotTriggerTheShortRoundFallback() {
        val strong = PreFlySignals(trend = 0.90, central = 0.80, plane = 0.70, movement = 0.80)
        val candidate = PreFlyTiming.candidate(strong)
        assertFalse(PreFlyTiming.isEvidence(
            strong, candidate, 0.80, 0.50,
            baselineSampleCount = 2, roundFrameCount = 3, flightProgress = 0.20
        ))
        assertFalse(PreFlyTiming.isEvidence(
            strong, candidate, 0.80, 0.50,
            baselineSampleCount = 2, roundFrameCount = 5, flightProgress = 0.04
        ))
    }

    @Test
    fun strongShortRoundEvidenceRemainsAvailableAfterLaunchSamples() {
        val strong = PreFlySignals(trend = 0.90, central = 0.80, plane = 0.70, movement = 0.80)
        val candidate = PreFlyTiming.candidate(strong)
        assertTrue(PreFlyTiming.isEvidence(
            strong, candidate, 0.80, 0.50,
            baselineSampleCount = 2, roundFrameCount = 5, flightProgress = 0.09
        ))
    }

    @Test
    fun normalEvidenceWaitsForMeasuredFlightProgress() {
        val strong = PreFlySignals(trend = 0.72, central = 0.20, plane = 0.50, movement = 0.20)
        val candidate = PreFlyTiming.candidate(strong)
        assertFalse(PreFlyTiming.isEvidence(
            strong, candidate, 0.55, 0.30,
            baselineSampleCount = 4, roundFrameCount = 8, flightProgress = 0.10
        ))
        assertTrue(PreFlyTiming.isEvidence(
            strong, candidate, 0.55, 0.30,
            baselineSampleCount = 4, roundFrameCount = 8, flightProgress = 0.20
        ))
    }

    @Test
    fun multiplierAppearanceIsAnIndependentPreFlySignal() {
        val quiet = PreFlyTiming.candidate(
            PreFlySignals(trend = 0.0, central = 0.0, plane = 0.0, movement = 0.0)
        )
        val appearanceChange = PreFlyTiming.candidate(
            PreFlySignals(trend = 0.0, central = 0.0, plane = 0.0, movement = 0.0, multiplier = 1.0)
        )
        assertEquals(0.0, quiet, 0.000001)
        assertEquals(0.12, appearanceChange, 0.000001)
    }

    @Test
    fun multiplierAppearanceAloneCannotTriggerRed() {
        val signals = PreFlySignals(
            trend = 0.05, central = 0.0, plane = 0.0, movement = 0.0, multiplier = 0.85
        )
        assertEquals(1, PreFlyTiming.evidenceCount(signals))
        assertFalse(PreFlyTiming.isEvidence(
            signals, PreFlyTiming.candidate(signals), 0.8, 0.1,
            baselineSampleCount = 4, roundFrameCount = 10, flightProgress = 0.5
        ))
    }

    @Test
    fun multiplierAppearanceCanCorroborateAnotherPreFlySignal() {
        val signals = PreFlySignals(
            trend = 0.75, central = 0.0, plane = 0.0, movement = 0.0, multiplier = 0.85
        )
        val candidate = PreFlyTiming.candidate(signals)
        assertEquals(2, PreFlyTiming.evidenceCount(signals))
        assertTrue(PreFlyTiming.isEvidence(
            signals, candidate, 0.80, 0.10,
            baselineSampleCount = 4, roundFrameCount = 10, flightProgress = 0.5
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
    fun longRoundConfirmationSurvivesOneWeakCapture() {
        assertEquals(1, PreFlyTiming.updatedLongRoundHold(0, true))
        assertEquals(2, PreFlyTiming.updatedLongRoundHold(1, true))
        assertEquals(1, PreFlyTiming.updatedLongRoundHold(2, false))
        assertEquals(2, PreFlyTiming.updatedLongRoundHold(1, true))
        assertEquals(3, PreFlyTiming.updatedLongRoundHold(2, true))
        assertEquals(3, PreFlyTiming.updatedLongRoundHold(3, true))
        assertEquals(2, PreFlyTiming.requiredConfirmations(100L, 2, longRound = true))
    }

    @Test
    fun confirmationAdaptsToCaptureCadenceAndSignalCorroboration() {
        assertEquals(3, PreFlyTiming.requiredConfirmations(50L, 2))
        assertEquals(2, PreFlyTiming.requiredConfirmations(100L, 2))
        assertEquals(2, PreFlyTiming.requiredConfirmations(50L, 3))
        assertEquals(2, PreFlyTiming.requiredConfirmations(220L, 4))
    }

    @Test
    fun longRoundPathWaitsUntilTheFourXTimeBoundary() {
        val signals = PreFlySignals(
            trend = 0.90, central = 0.30, plane = 0.65, movement = 0.55, multiplier = 0.35
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertTrue(PreFlyTiming.isEvidence(
            signals, candidate, 0.55, 0.30,
            baselineSampleCount = 4,
            roundFrameCount = 180,
            flightProgress = 0.32,
            roundElapsedMs = PreFlyTiming.LONG_ROUND_THRESHOLD_MS
        ))
    }

    @Test
    fun longRoundPathStillWorksForExtremelyLongFlights() {
        val signals = PreFlySignals(
            trend = 0.90, central = 0.30, plane = 0.65, movement = 0.55, multiplier = 0.35
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertTrue(PreFlyTiming.isEvidence(
            signals, candidate, 0.55, 0.30,
            baselineSampleCount = 2,
            roundFrameCount = 1_800,
            flightProgress = 0.42,
            roundElapsedMs = 180_000L
        ))
    }

    @Test
    fun longRoundAcceptsModerateVisualAndAircraftMotionEvidence() {
        val signals = PreFlySignals(
            trend = 0.90, central = 0.15, plane = 0.20, movement = 0.08, multiplier = 0.0
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertTrue(PreFlyTiming.isEvidence(
            signals, candidate, 0.42, 0.24,
            baselineSampleCount = 4,
            roundFrameCount = 180,
            flightProgress = 0.14,
            roundElapsedMs = PreFlyTiming.LONG_ROUND_THRESHOLD_MS
        ))
    }

    @Test
    fun longRoundCanUseMultiplierAppearanceAsAlternativeCorroboration() {
        val signals = PreFlySignals(
            trend = 0.90, central = 0.15, plane = 0.15, movement = 0.0, multiplier = 0.20
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertTrue(PreFlyTiming.isEvidence(
            signals, candidate, 0.42, 0.23,
            baselineSampleCount = 4,
            roundFrameCount = 180,
            flightProgress = 0.14,
            roundElapsedMs = PreFlyTiming.LONG_ROUND_THRESHOLD_MS
        ))
    }

    @Test
    fun longRoundRejectsVisualAndAircraftPresenceWithoutActualMotionOrAppearanceChange() {
        val signals = PreFlySignals(
            trend = 0.90, central = 0.20, plane = 0.80, movement = 0.02, multiplier = 0.0
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertFalse(PreFlyTiming.isEvidence(
            signals, candidate, 0.60, 0.25,
            baselineSampleCount = 4,
            roundFrameCount = 180,
            flightProgress = 0.40,
            roundElapsedMs = PreFlyTiming.LONG_ROUND_THRESHOLD_MS
        ))
    }

    @Test
    fun longRoundRequiresThreeConsecutiveConfirmations() {
        assertEquals(3, PreFlyTiming.requiredConfirmations(
            frameDeltaMs = 220L,
            independentSignals = 5,
            longRound = true
        ))
    }

    @Test
    fun shortAndMediumPathRemainsAvailableImmediatelyBeforeLongRoundBoundary() {
        val signals = PreFlySignals(
            trend = 0.72, central = 0.20, plane = 0.50, movement = 0.20
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertTrue(PreFlyTiming.isEvidence(
            signals, candidate, 0.55, 0.30,
            baselineSampleCount = 4,
            roundFrameCount = 8,
            flightProgress = 0.20,
            roundElapsedMs = PreFlyTiming.LONG_ROUND_THRESHOLD_MS - 1L
        ))
    }

    @Test
    fun longRoundDoesNotFallBackToTheLessStrictNormalPath() {
        val signals = PreFlySignals(
            trend = 0.90, central = 0.20, plane = 0.80, movement = 0.02
        )
        val candidate = PreFlyTiming.candidate(signals)

        assertFalse(PreFlyTiming.isEvidence(
            signals, candidate, 0.60, 0.25,
            baselineSampleCount = 4,
            roundFrameCount = 180,
            flightProgress = 0.40,
            roundElapsedMs = PreFlyTiming.LONG_ROUND_THRESHOLD_MS
        ))
    }
}
