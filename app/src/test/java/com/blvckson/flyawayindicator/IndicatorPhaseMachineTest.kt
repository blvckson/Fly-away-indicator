package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorPhaseMachineTest {
    @Test
    fun strongAviatorSceneCanConfirmGreenOnSecondFrame() {
        assertTrue(LiveStartPolicy.isConfirmed(
            stableHits = 2, missHits = 0, confidence = 0.40,
            centralScore = 0.12, sceneScore = 0.21, planeScore = 0.15
        ))
    }

    @Test
    fun weakTwoFrameCandidateMustWaitForThirdConfirmation() {
        assertFalse(LiveStartPolicy.isConfirmed(
            stableHits = 2, missHits = 0, confidence = 0.40,
            centralScore = 0.08, sceneScore = 0.17, planeScore = 0.10
        ))
        assertTrue(LiveStartPolicy.isConfirmed(
            stableHits = 3, missHits = 0, confidence = 0.60,
            centralScore = 0.08, sceneScore = 0.17, planeScore = 0.10
        ))
    }

    @Test
    fun missedFrameBlocksFastStart() {
        assertFalse(LiveStartPolicy.isConfirmed(
            stableHits = 2, missHits = 1, confidence = 0.60,
            centralScore = 0.20, sceneScore = 0.30, planeScore = 0.30
        ))
    }

    @Test
    fun detectorConfirmedLiveScreenStartsGreenWithoutAnotherGate() {
        val machine = IndicatorPhaseMachine()
        assertEquals(IndicatorPhase.BLACK, machine.phase)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(visualLive = true, endConfirmed = false))
    }

    @Test
    fun redStaysLatchedUntilTheRoundEnds() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        assertEquals(IndicatorPhase.RED, machine.phase)
        assertEquals(IndicatorPhase.RED, machine.observeLive(visualLive = true, endConfirmed = false))
    }

    @Test
    fun redAlertAutomaticallyDisappearsAfterItsBriefDisplayWindow() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 2_000L)

        assertEquals(
            IndicatorPhase.RED,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_199L)
        )
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_200L)
        )
    }

    @Test
    fun hiddenRedDoesNotImmediatelyTurnGreenAgainDuringTheSameRound() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 2_000L)

        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_200L)
        )
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_300L)
        )
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_400L)
        )
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_500L)
        )
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_600L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_700L)
        )
    }

    @Test
    fun threeConsecutiveMissingFramesReturnRedToBlack() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        assertEquals(IndicatorPhase.RED, machine.observeLive(visualLive = false, endConfirmed = false))
        assertEquals(IndicatorPhase.RED, machine.observeLive(visualLive = false, endConfirmed = false))
        assertEquals(IndicatorPhase.BLACK, machine.observeLive(visualLive = false, endConfirmed = false))
    }

    @Test
    fun explicitRoundEndReturnsToBlackImmediately() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        assertEquals(IndicatorPhase.BLACK, machine.observeLive(visualLive = false, endConfirmed = true))
    }

    @Test
    fun freshLiveEvidenceOverridesStaleOcrEndText() {
        val machine = IndicatorPhaseMachine()
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(visualLive = true, endConfirmed = true))
    }

    @Test
    fun activeRoundEndOverridesStaleLivePixelsImmediately() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = true)
        )
    }

    @Test
    fun staleEndMarkerDoesNotFlickerTheNewRoundBackToBlack() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = true)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = true)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = true)
        )
    }

    @Test
    fun aNewRoundCanTurnGreenAfterThePreviousRoundResets() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        machine.observeLive(visualLive = false, endConfirmed = true)
        assertEquals(IndicatorPhase.BLACK, machine.phase)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(visualLive = true, endConfirmed = true))
    }

    @Test
    fun longRoundCanRearmOnceAfterAnEarlyRedAlertExpires() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)

        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 2_300L)
        )
        assertTrue(machine.isAwaitingNextRound)
        assertTrue(machine.rearmForLongRound())
        assertEquals(IndicatorPhase.GREEN, machine.phase)
        assertFalse(machine.isAwaitingNextRound)
        assertFalse(machine.rearmForLongRound())
    }

    @Test
    fun longRoundCannotRearmAfterAnActualRoundEnd() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)

        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = true, nowMs = 1_200L)
        )
        assertFalse(machine.isAwaitingNextRound)
        assertFalse(machine.rearmForLongRound())
    }
}
