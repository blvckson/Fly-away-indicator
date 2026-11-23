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
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        )
    }

    @Test
    fun redStaysLatchedForItsDisplayWindow() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        assertEquals(IndicatorPhase.RED, machine.phase)
        assertEquals(
            IndicatorPhase.RED,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 2_299L)
        )
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
    fun repeatedPreFlyCallsCannotIssueAnotherRedInTheSameRound() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        machine.markPreFlyAway(nowMs = 1_150L)
        assertEquals(IndicatorPhase.RED, machine.phase)

        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 2_300L)
        )
        assertEquals(IndicatorPhase.BLACK, machine.markPreFlyAway(nowMs = 2_400L))
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 18_000L)
        )
        assertEquals(IndicatorPhase.BLACK, machine.markPreFlyAway(nowMs = 18_100L))
        assertEquals(0L, machine.roundEndGeneration)
    }

    @Test
    fun threeBriefMissingFramesDoNotEndAnActiveRound() {
        val machine = IndicatorPhaseMachine(missingLiveDurationMs = 1_800L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)

        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 1_100L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 1_200L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 1_300L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_400L)
        )
        assertEquals(0L, machine.roundEndGeneration)
    }

    @Test
    fun sustainedMissingLiveEvidenceEndsTheRoundOnlyAfterTheTimeAndFrameGates() {
        val machine = IndicatorPhaseMachine(
            missingFramesToReset = 3,
            missingLiveDurationMs = 1_800L
        )
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)

        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 1_100L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 2_000L)
        )
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 2_900L)
        )
        assertEquals(1L, machine.roundEndGeneration)
    }

    @Test
    fun hiddenRedDoesNotRearmGreenDuringTheSameLongRound() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 2_000L)

        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_200L)
        )
        machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_300L)
        machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_500L)
        machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_700L)
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_800L)
        )
        assertEquals(IndicatorPhase.BLACK, machine.markPreFlyAway(nowMs = 18_000L))
        assertEquals(0L, machine.roundEndGeneration)
    }

    @Test
    fun naturalRoundEndAfterRedExpiresResetsTheAlertLockExactlyOnce() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 2_300L)
        )

        machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 2_400L)
        machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 3_300L)
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = false, nowMs = 4_200L)
        )
        assertEquals(1L, machine.roundEndGeneration)

        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 4_300L)
        )
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(nowMs = 4_400L))
        assertEquals(1L, machine.roundEndGeneration)
    }

    @Test
    fun explicitRoundEndReturnsToBlackImmediately() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = true, nowMs = 1_200L)
        )
        assertEquals(1L, machine.roundEndGeneration)
    }

    @Test
    fun explicitEndAfterRedExpiryStillFinalizesTheRound() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 2_300L)
        assertEquals(IndicatorPhase.BLACK, machine.phase)

        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = true, nowMs = 2_400L)
        )
        assertEquals(1L, machine.roundEndGeneration)
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 2_500L)
        )
    }

    @Test
    fun freshLiveEvidenceOverridesStaleOcrEndTextWhenNoRoundIsActive() {
        val machine = IndicatorPhaseMachine()
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = true, nowMs = 1_000L)
        )
    }

    @Test
    fun activeRoundEndOverridesStaleLivePixelsImmediately() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = true, endConfirmed = true, nowMs = 1_200L)
        )
        assertEquals(1L, machine.roundEndGeneration)
    }

    @Test
    fun staleEndMarkerDoesNotFlickerTheNewRoundBackToBlack() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        assertEquals(
            IndicatorPhase.BLACK,
            machine.observeLive(visualLive = false, endConfirmed = true, nowMs = 1_200L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = true, nowMs = 1_300L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = true, nowMs = 1_400L)
        )
    }

    @Test
    fun aNewRoundCanIssueOneNewRedAfterThePreviousRoundResets() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 1_100L)
        machine.observeLive(visualLive = false, endConfirmed = true, nowMs = 1_200L)
        assertEquals(IndicatorPhase.BLACK, machine.phase)
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_300L)
        )
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(nowMs = 1_400L))
    }
}
