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
    fun detectorConfirmedLiveScreenStartsOneTrackedRound() {
        val machine = IndicatorPhaseMachine()
        assertEquals(IndicatorPhase.BLACK, machine.phase)
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        )
        assertTrue(machine.isRoundActive)
        assertEquals(1L, machine.roundStartGeneration)
        assertEquals(0L, machine.roundEndGeneration)
    }

    @Test
    fun redStaysVisibleForItsDisplayWindow() {
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
    fun redAutomaticallyReturnsToGreenWithoutRearmingTheRound() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        machine.markPreFlyAway(nowMs = 2_000L)

        assertEquals(
            IndicatorPhase.RED,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_199L)
        )
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 3_200L)
        )
        assertTrue(machine.isRoundActive)
        assertEquals(0L, machine.roundEndGeneration)
    }

    @Test
    fun oneAlertIsAllowedPerRoundRegardlessOfItsDuration() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        val roundDurationsMs = listOf(4_000L, 9_000L, 17_000L, 180_000L)

        for ((index, duration) in roundDurationsMs.withIndex()) {
            val start = 1_000L + index * 300_000L
            assertEquals(
                IndicatorPhase.GREEN,
                machine.observeLive(true, false, start)
            )
            assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(start + duration))
            assertEquals(
                IndicatorPhase.GREEN,
                machine.observeLive(true, false, start + duration + 1_200L)
            )
            // Repeated calls, including very late calls on an arbitrarily long
            // flight, must never create another RED before an explicit round end.
            assertEquals(IndicatorPhase.GREEN, machine.markPreFlyAway(start + duration + 2_000L))
            assertEquals(IndicatorPhase.GREEN, machine.markPreFlyAway(start + duration + 120_000L))
            assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, start + duration + 120_001L))
            assertTrue(machine.isRoundActive)
            assertEquals(index.toLong(), machine.roundEndGeneration)

            assertEquals(
                IndicatorPhase.GREEN,
                machine.observeLive(false, true, start + duration + 121_000L)
            )
            assertEquals(index.toLong() + 1L, machine.roundEndGeneration)
        }
    }

    @Test
    fun temporaryDetectionLossKeepsGreenAndDoesNotFinishOrRestartTheRound() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(true, false, 1_000L)
        assertEquals(1L, machine.roundStartGeneration)

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, false, 1_100L))
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, false, 1_200L))
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, false, 1_300L))
        assertTrue(machine.isRoundActive)
        assertEquals(0L, machine.roundEndGeneration)

        // Reacquisition resumes the same round and does not create a new baseline ID.
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_400L))
        assertEquals(1L, machine.roundStartGeneration)
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(1_500L))
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(1_550L))
    }

    @Test
    fun prolongedDetectionLossCannotUnlockASecondAlertAfterRedExpires() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(true, false, 1_000L)
        machine.markPreFlyAway(1_100L)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 2_300L))

        // Missing evidence lasting much longer than the old 1.8-second reset
        // window must not be mistaken for a round end.
        machine.observeLive(false, false, 2_400L)
        machine.observeLive(false, false, 5_000L)
        machine.observeLive(false, false, 30_000L)
        machine.observeLive(false, false, 180_000L)
        assertTrue(machine.isRoundActive)
        assertEquals(0L, machine.roundEndGeneration)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 181_000L))
        assertEquals(IndicatorPhase.GREEN, machine.markPreFlyAway(181_100L))
    }

    @Test
    fun explicitRoundEndKeepsSessionGreenAndReleasesTheLockExactlyOnce() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(true, false, 1_000L)
        machine.markPreFlyAway(1_100L)
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(false, true, 1_200L)
        )
        assertFalse(machine.isRoundActive)
        assertEquals(1L, machine.roundEndGeneration)

        // Repeated/stale end evidence cannot finalize the same round twice.
        machine.observeLive(false, true, 1_300L)
        assertEquals(1L, machine.roundEndGeneration)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_400L))
        assertEquals(2L, machine.roundStartGeneration)
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(1_500L))
    }

    @Test
    fun explicitRoundEndStillFinalizesAfterRedHasExpired() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeLive(true, false, 1_000L)
        machine.markPreFlyAway(1_100L)
        machine.observeLive(true, false, 2_300L)
        assertEquals(IndicatorPhase.GREEN, machine.phase)

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, true, 2_400L))
        assertEquals(1L, machine.roundEndGeneration)
        assertFalse(machine.isRoundActive)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 2_500L))
        assertEquals(2L, machine.roundStartGeneration)
    }

    @Test
    fun staleEndMarkerDoesNotImmediatelyFinalizeTheNextRound() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(true, false, 1_000L)
        machine.markPreFlyAway(1_100L)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, true, 1_200L))
        assertEquals(1L, machine.roundEndGeneration)

        // The same short OCR end-marker episode remains latched and cannot
        // prematurely create a new round while the result marker is still visible.
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, true, 1_300L))
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, true, 1_400L))
        assertEquals(1L, machine.roundStartGeneration)
        assertEquals(1L, machine.roundEndGeneration)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_500L))
        assertEquals(2L, machine.roundStartGeneration)
    }

    @Test
    fun aNewRoundCanIssueItsOwnSingleAlertAfterConfirmedEnd() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(true, false, 1_000L)
        machine.markPreFlyAway(1_100L)
        machine.observeLive(false, true, 1_200L)
        assertEquals(IndicatorPhase.GREEN, machine.phase)

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_300L))
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(1_400L))
        assertEquals(2L, machine.roundStartGeneration)
    }


    @Test
    fun reacquisitionFramesRemainIneligibleForPreFlyScoring() {
        val machine = IndicatorPhaseMachine()
        val gate = IndicatorReacquisitionGate(requiredStableFrames = 2)

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_000L))
        assertFalse(gate.shouldSkipScoring(true, machine.phase, true))

        // A temporary miss invalidates temporal scoring but does not end the round.
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, false, 1_100L))
        assertFalse(gate.shouldSkipScoring(true, machine.phase, false))

        // Both recovered frames are excluded from pre-fly scoring.
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_200L))
        assertTrue(gate.shouldSkipScoring(true, machine.phase, true))
        assertEquals(IndicatorPhase.GREEN, machine.phase)

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_300L))
        assertTrue(gate.shouldSkipScoring(true, machine.phase, true))
        assertEquals(IndicatorPhase.GREEN, machine.phase)

        // Only the next stable frame can resume signal evaluation.
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_400L))
        assertFalse(gate.shouldSkipScoring(true, machine.phase, true))
        assertEquals(IndicatorPhase.GREEN, machine.phase)
    }


    @Test
    fun consistencyFallbackTurnsTheFirstFollowUpLiveCaptureRedOnlyOnce() {
        val machine = IndicatorPhaseMachine()
        assertEquals(
            IndicatorPhase.GREEN,
            machine.observeLive(visualLive = true, endConfirmed = false, nowMs = 1_000L)
        )

        // The accepted live-round start itself remains GREEN.
        assertFalse(machine.markConsistencyWarningIfDue(
            visualLive = true,
            endConfirmed = false,
            isRoundStartFrame = true,
            nowMs = 1_000L
        ))
        assertEquals(IndicatorPhase.GREEN, machine.phase)

        // The very next positively verified live capture issues the shared fallback.
        assertTrue(machine.markConsistencyWarningIfDue(
            visualLive = true,
            endConfirmed = false,
            isRoundStartFrame = false,
            nowMs = 1_001L
        ))
        assertEquals(IndicatorPhase.RED, machine.phase)
        assertTrue(machine.lastAlertWasConsistencyFallback)

        // No second RED can be generated during the same round.
        assertFalse(machine.markConsistencyWarningIfDue(
            visualLive = true,
            endConfirmed = false,
            isRoundStartFrame = false,
            nowMs = 1_002L
        ))
    }

    @Test
    fun consistencyFallbackWaitsForPositiveLiveEvidenceAfterTemporaryLoss() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(true, false, 1_000L)

        assertFalse(machine.markConsistencyWarningIfDue(
            visualLive = false, endConfirmed = false,
            isRoundStartFrame = false, nowMs = 1_100L
        ))
        machine.observeLive(false, false, 1_200L)
        machine.observeLive(false, false, 1_300L)
        machine.observeLive(false, false, 1_400L)
        assertEquals(IndicatorPhase.GREEN, machine.phase)
        assertTrue(machine.isRoundActive)

        // Reacquisition resumes the same round; the pending fallback is not lost.
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_500L))
        assertTrue(machine.markConsistencyWarningIfDue(
            visualLive = true, endConfirmed = false,
            isRoundStartFrame = false, nowMs = 1_501L
        ))
        assertEquals(IndicatorPhase.RED, machine.phase)
        assertEquals(1L, machine.roundStartGeneration)
    }

    @Test
    fun confirmedEndBlocksPendingFallbackAndNextRoundGetsAFreshOne() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(true, false, 1_000L)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, true, 1_100L))
        assertFalse(machine.markConsistencyWarningIfDue(
            visualLive = true, endConfirmed = true,
            isRoundStartFrame = false, nowMs = 1_101L
        ))

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_200L))
        assertTrue(machine.markConsistencyWarningIfDue(
            visualLive = true, endConfirmed = false,
            isRoundStartFrame = false, nowMs = 1_201L
        ))
        assertEquals(2L, machine.roundStartGeneration)
    }


    @Test
    fun greenIsHeldAcrossTheWholeAviatorScreenSessionAndBlackReturnsOnlyOnClose() {
        val machine = IndicatorPhaseMachine()
        assertEquals(IndicatorPhase.BLACK, machine.phase)

        assertEquals(IndicatorPhase.GREEN, machine.observeScreenPresence(true))
        assertEquals(IndicatorPhase.GREEN, machine.observeScreenPresence(true))

        // The plane may be absent between flights while the Aviator screen stays open.
        repeat(20) {
            assertEquals(IndicatorPhase.GREEN, machine.observeLive(
                visualLive = false, endConfirmed = false, nowMs = 2_000L + it * 100L
            ))
        }
        assertFalse(machine.isRoundActive)

        assertEquals(IndicatorPhase.BLACK, machine.observeScreenPresence(false))
        assertFalse(machine.isAviatorScreenActive)
    }

    @Test
    fun redReturnsToGreenAndAConfirmedNextRoundCanIssueItsOwnSingleRed() {
        val machine = IndicatorPhaseMachine(redVisibleDurationMs = 1_200L)
        machine.observeScreenPresence(true)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 1_000L))
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(1_100L))
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 2_300L))
        assertEquals(IndicatorPhase.GREEN, machine.markPreFlyAway(2_400L))

        assertEquals(IndicatorPhase.GREEN, machine.observeLive(false, true, 2_500L))
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(true, false, 2_600L))
        assertEquals(2L, machine.roundStartGeneration)
        assertEquals(IndicatorPhase.RED, machine.markPreFlyAway(2_700L))
    }

}
