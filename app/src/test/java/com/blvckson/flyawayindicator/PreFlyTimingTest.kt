package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreFlyTimingTest {
    @Test
    fun medianBaselineIgnoresOneLargeOpeningSpike() {
        assertEquals(
            0.115,
            PreFlyTiming.medianBaseline(listOf(0.10,0.11,0.12,0.99)),
            0.000001
        )
    }

    @Test
    fun candidatePreservesBuild74SignalWeights() {
        val score=PreFlyTiming.candidate(
            PreFlySignals(trend=0.70,central=0.50,plane=0.80,movement=0.40)
        )
        assertEquals(0.602,score,0.000001)
    }

    @Test
    fun preFlyRequiresBaselineRiseAndCorroboratingSignals() {
        val strong=PreFlySignals(trend=0.72,central=0.20,plane=0.50,movement=0.20)
        val candidate=PreFlyTiming.candidate(strong)
        assertTrue(PreFlyTiming.isEvidence(strong,candidate,0.55,0.30))
    }

    @Test
    fun oneSignalCannotTriggerRed() {
        val weak=PreFlySignals(trend=1.00,central=0.05,plane=0.05,movement=0.05)
        val candidate=PreFlyTiming.candidate(weak)
        assertFalse(PreFlyTiming.isEvidence(weak,candidate,0.80,0.10))
    }

    @Test
    fun weakFrameDecaysHoldByOneInsteadOfClearingIt() {
        assertEquals(2,PreFlyTiming.updatedHold(3,false))
        assertEquals(4,PreFlyTiming.updatedHold(3,true))
    }

    @Test
    fun confirmationAdaptsToCaptureCadence() {
        assertEquals(3,PreFlyTiming.requiredConfirmations(50L))
        assertEquals(2,PreFlyTiming.requiredConfirmations(100L))
        assertEquals(2,PreFlyTiming.requiredConfirmations(220L))
    }
}
