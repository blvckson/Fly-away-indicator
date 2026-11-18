package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Test

class IndicatorPhaseMachineTest {
    @Test
    fun detectorConfirmedLiveScreenStartsGreenWithoutAnotherTwoFrameGate() {
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
    fun aNewRoundCanTurnGreenAfterThePreviousRoundResets() {
        val machine = IndicatorPhaseMachine()
        machine.observeLive(visualLive = true, endConfirmed = false)
        machine.markPreFlyAway()
        machine.observeLive(visualLive = false, endConfirmed = true)

        assertEquals(IndicatorPhase.BLACK, machine.phase)
        assertEquals(IndicatorPhase.GREEN, machine.observeLive(visualLive = true, endConfirmed = true))
    }
}
