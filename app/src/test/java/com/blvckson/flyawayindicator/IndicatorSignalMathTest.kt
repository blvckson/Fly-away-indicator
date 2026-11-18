package com.blvckson.flyawayindicator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorSignalMathTest {

    @Test
    fun medianIsResistantToOneLargeOutlier() {
        assertEquals(
            0.115,
            IndicatorSignalMath.median(listOf(0.10, 0.11, 0.12, 0.99)),
            0.000001
        )
    }

    @Test
    fun relativeGeometryChangeMeasuresWidthAndHeightChanges() {
        val change = IndicatorSignalMath.relativeGeometryChange(
            currentWidth = 0.03,
            currentHeight = 0.06,
            previousWidth = 0.02,
            previousHeight = 0.04
        )
        assertEquals(0.5, change, 0.000001)
    }

    @Test
    fun relativeGeometryChangeIsZeroWithoutValidPriorMeasurements() {
        assertEquals(
            0.0,
            IndicatorSignalMath.relativeGeometryChange(0.03, 0.06, null, 0.04),
            0.000001
        )
    }

    @Test
    fun relativeShapeChangeMeasuresAspectRatioChange() {
        assertEquals(
            0.25,
            IndicatorSignalMath.relativeShapeChange(1.25, 1.0),
            0.000001
        )
    }

    @Test
    fun relativeShapeChangeRejectsInvalidAspectRatios() {
        assertEquals(
            0.0,
            IndicatorSignalMath.relativeShapeChange(Double.NaN, 1.0),
            0.000001
        )
    }

    @Test
    fun adjacentWindowTrendDetectsAnIncrease() {
        val samples = listOf(
            IndicatorSignalSample(500L, 0.10),
            IndicatorSignalSample(700L, 0.10),
            IndicatorSignalSample(820L, 0.40),
            IndicatorSignalSample(950L, 0.40)
        )

        val trend = IndicatorSignalMath.windowTrend(samples, 1000L, 250L, 2)

        assertTrue(trend != null && trend > 0.78)
    }

    @Test
    fun adjacentWindowTrendDetectsADecrease() {
        val samples = listOf(
            IndicatorSignalSample(500L, 0.40),
            IndicatorSignalSample(700L, 0.40),
            IndicatorSignalSample(820L, 0.10),
            IndicatorSignalSample(950L, 0.10)
        )

        val trend = IndicatorSignalMath.windowTrend(samples, 1000L, 250L, 2)

        assertTrue(trend != null && trend < 0.22)
    }

    @Test
    fun timeWeightedTrendIsNotBiasedByUnevenCaptureDensity() {
        val evenlySampled = listOf(
            IndicatorSignalSample(500L, 0.0),
            IndicatorSignalSample(625L, 0.5),
            IndicatorSignalSample(750L, 1.0),
            IndicatorSignalSample(751L, 0.0),
            IndicatorSignalSample(875L, 0.5),
            IndicatorSignalSample(999L, 1.0)
        )
        val burstyCapture = listOf(
            IndicatorSignalSample(500L, 0.0),
            IndicatorSignalSample(750L, 1.0),
            IndicatorSignalSample(751L, 0.0),
            IndicatorSignalSample(752L, 0.0),
            IndicatorSignalSample(999L, 1.0)
        )

        val evenTrend = IndicatorSignalMath.windowTrend(evenlySampled, 1000L, 250L, 2)
        val burstyTrend = IndicatorSignalMath.windowTrend(burstyCapture, 1000L, 250L, 2)

        assertTrue(evenTrend != null && burstyTrend != null)
        assertEquals(evenTrend!!, burstyTrend!!, 0.01)
    }

    @Test
    fun trendIsUnavailableWhenEitherWindowIsUndersampled() {
        val samples = listOf(
            IndicatorSignalSample(500L, 0.10),
            IndicatorSignalSample(820L, 0.40),
            IndicatorSignalSample(950L, 0.40)
        )

        assertNull(IndicatorSignalMath.windowTrend(samples, 1000L, 250L, 2))
    }

    @Test
    fun futureSamplesAreNotCountedInTheTrendWindow() {
        val samples = listOf(
            IndicatorSignalSample(500L, 0.10),
            IndicatorSignalSample(700L, 0.10),
            IndicatorSignalSample(820L, 0.40),
            IndicatorSignalSample(950L, 0.40),
            IndicatorSignalSample(1200L, 1.00)
        )

        val trend = IndicatorSignalMath.windowTrend(samples, 1000L, 250L, 2)

        assertTrue(trend != null && trend > 0.78)
    }

    @Test
    fun roundResetUsesElapsedTimeNotFrameCount() {
        assertFalse(IndicatorSignalMath.shouldResetRound(3999L, 1000L, 3000L))
        assertTrue(IndicatorSignalMath.shouldResetRound(4000L, 1000L, 3000L))
    }

    @Test
    fun resetDoesNotAcceptInvalidOrBackwardsTimestamps() {
        assertFalse(IndicatorSignalMath.shouldResetRound(5000L, 0L, 3000L))
        assertFalse(IndicatorSignalMath.shouldResetRound(900L, 1000L, 3000L))
        assertFalse(IndicatorSignalMath.shouldResetRound(5000L, 1000L, 0L))
    }
}
