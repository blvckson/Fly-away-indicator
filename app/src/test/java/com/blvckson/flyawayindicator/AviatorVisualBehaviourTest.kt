package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AviatorVisualBehaviourTest {
    @Test
    fun steadyMultiplierAppearanceDoesNotLookLikeAChange() {
        val tracker = MultiplierBehaviourTracker()
        val steady = MultiplierAppearance(
            centerX = 0.50,
            centerY = 0.30,
            fontHeight = 0.035,
            inkLuminance = 235.0,
            inkRedRatio = 0.08,
            contrast = 0.72
        )
        for (i in 1L..4L) tracker.observe(steady, i * 300L)
        val state = tracker.observe(steady, 1500L)
        assertTrue(state.present)
        assertTrue(state.samples >= 5)
        assertEquals(0.0, state.changeScore, 0.000001)
    }

    @Test
    fun multiplierPositionFontAndColourChangesAreDetected() {
        val tracker = MultiplierBehaviourTracker()
        val steady = MultiplierAppearance(0.50, 0.30, 0.035, 235.0, 0.08, 0.72)
        for (i in 1L..4L) tracker.observe(steady, i * 300L)
        val changed = MultiplierAppearance(0.50, 0.36, 0.045, 175.0, 0.58, 0.35)
        val state = tracker.observe(changed, 1500L)
        assertTrue(state.changeScore >= 0.12)
        assertTrue(state.stabilityScore < 0.88)
    }

    @Test
    fun repeatedCaptureOfTheSameOcrSampleDoesNotFakeExtraHistory() {
        val tracker = MultiplierBehaviourTracker()
        val sample = MultiplierAppearance(0.5, 0.3, 0.04, 230.0, 0.1, 0.7)
        val first = tracker.observe(sample, 1000L)
        val repeated = tracker.observe(sample, 1000L)
        assertEquals(first.samples, repeated.samples)
    }

    @Test
    fun roundStartReseedsMultiplierBaselineWithoutLosingIdentitySamples() {
        val tracker = MultiplierBehaviourTracker()
        val first = MultiplierAppearance(0.5, 0.3, 0.04, 230.0, 0.1, 0.7)
        for (i in 1L..3L) tracker.observe(first, i * 300L)
        val before = tracker.observe(first, 1200L)
        val seeded = tracker.startRoundBaseline()
        assertEquals(before.samples, seeded.samples)
        assertEquals(0.0, seeded.changeScore, 0.000001)
    }

    @Test
    fun aircraftShapeAndTrajectoryContinuityBuildBehaviourConfidence() {
        val tracker = AircraftBehaviourTracker()
        tracker.observe(0.25, 0.48, 0.035, 0.022, 1.6)
        val second = tracker.observe(0.27, 0.46, 0.035, 0.022, 1.6)
        val third = tracker.observe(0.29, 0.44, 0.035, 0.022, 1.6)
        assertEquals(3, third.persistedFrames)
        assertTrue(second.score >= 0.48)
        assertTrue(third.score > second.score)
        assertTrue(third.movementScore > 0.0)
    }

    @Test
    fun unrelatedTextOrMissingMultiplierCannotConfirmAviatorIdentity() {
        assertFalse(AviatorIdentityPolicy.isConfirmed(
            textVerified = true,
            multiplierPresent = false,
            aviatorLabelPresent = true,
            gameControlsPresent = true,
            multiplierBounds = MultiplierTextBounds(0.20, 0.28, 0.80, 0.32),
            multiplierSamples = 5,
            multiplierStabilityScore = 0.9,
            aircraftBehaviourScore = 0.9,
            sceneScore = 0.9
        ))
    }

    @Test
    fun multiplierTextAloneCannotConfirmWithoutAircraftBehaviour() {
        assertFalse(AviatorIdentityPolicy.isConfirmed(
            textVerified = true,
            multiplierPresent = true,
            aviatorLabelPresent = true,
            gameControlsPresent = true,
            multiplierSamples = 5,
            multiplierStabilityScore = 0.9,
            aircraftBehaviourScore = 0.2,
            sceneScore = 0.9
        ))
    }

    @Test
    fun unstableMultiplierPlacementOrAppearanceCannotConfirmTheScreen() {
        assertFalse(AviatorIdentityPolicy.isConfirmed(
            textVerified = true,
            multiplierPresent = true,
            aviatorLabelPresent = true,
            gameControlsPresent = true,
            multiplierBounds = MultiplierTextBounds(0.20, 0.28, 0.80, 0.32),
            multiplierSamples = 3,
            multiplierStabilityScore = 0.20,
            aircraftBehaviourScore = 0.9,
            sceneScore = 0.9
        ))
    }

    @Test
    fun unstableOpeningMultiplierAppearanceHasLowIdentityStability() {
        val tracker = MultiplierBehaviourTracker()
        tracker.observe(MultiplierAppearance(0.50, 0.30, 0.035, 235.0, 0.08, 0.72), 300L)
        val changed = tracker.observe(
            MultiplierAppearance(0.50, 0.36, 0.045, 175.0, 0.58, 0.35), 600L
        )
        assertTrue(changed.stabilityScore < 0.55)
    }

    @Test
    fun multiplierTextOutsideTheExpectedDisplayRegionIsRejected() {
        assertFalse(AviatorIdentityPolicy.isConfirmed(
            textVerified = true,
            multiplierPresent = true,
            aviatorLabelPresent = true,
            gameControlsPresent = true,
            multiplierBounds = MultiplierTextBounds(0.05, 0.80, 0.45, 0.84),
            multiplierSamples = 5,
            multiplierStabilityScore = 0.9,
            aircraftBehaviourScore = 0.9,
            sceneScore = 0.9
        ))
    }

    @Test
    fun aviatorIdentityRequiresVerifiedTextMultiplierAndPlaneBehaviour() {
        assertTrue(AviatorIdentityPolicy.isConfirmed(
            textVerified = true,
            multiplierPresent = true,
            aviatorLabelPresent = false,
            gameControlsPresent = true,
            multiplierBounds = MultiplierTextBounds(0.20, 0.28, 0.80, 0.32),
            multiplierSamples = 2,
            multiplierStabilityScore = 0.9,
            aircraftBehaviourScore = 0.65,
            sceneScore = 0.30
        ))
    }
}
