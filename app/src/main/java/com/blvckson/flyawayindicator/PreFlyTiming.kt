package com.blvckson.flyawayindicator

import kotlin.math.max
import kotlin.math.min

/**
 * Build-74-style pre-fly scoring: an adaptive round baseline plus corroborating
 * trend, central-scene, aircraft-confidence and movement evidence.
 */
internal data class PreFlySignals(
    val trend: Double,
    val central: Double,
    val plane: Double,
    val movement: Double
)

internal object PreFlyTiming {
    fun candidate(signals: PreFlySignals): Double =
        (signals.trend * 0.38 +
            signals.central * 0.24 +
            signals.plane * 0.16 +
            signals.movement * 0.22).coerceIn(0.0, 1.0)

    fun medianBaseline(samples: List<Double>): Double {
        val sorted=samples.filter { it.isFinite() }.sorted()
        if(sorted.isEmpty()) return 0.0
        val middle=sorted.size/2
        return if(sorted.size%2==0){
            (sorted[middle-1]+sorted[middle])/2.0
        }else{
            sorted[middle]
        }
    }

    fun isEvidence(
        signals: PreFlySignals,
        candidate: Double,
        smoothedScore: Double,
        baselineScore: Double
    ): Boolean {
        val trendEvidence=signals.trend>=0.62
        val centralEvidence=signals.central>=0.13
        val planeEvidence=signals.plane>=0.10
        val movementEvidence=signals.movement>=0.11
        val evidenceCount=(if(trendEvidence)1 else 0)+
            (if(centralEvidence)1 else 0)+
            (if(planeEvidence)1 else 0)+
            (if(movementEvidence)1 else 0)
        val pairedEvidence=
            (trendEvidence && (centralEvidence || movementEvidence || planeEvidence)) ||
            (centralEvidence && movementEvidence) ||
            (movementEvidence && planeEvidence)

        val baselineRise=candidate-baselineScore
        val aboveBaseline=candidate>=max(0.34,baselineScore*1.28) &&
            baselineRise>=0.06
        val smoothedRise=smoothedScore>=max(0.40,baselineScore+0.05)
        return aboveBaseline && smoothedRise && evidenceCount>=2 && pairedEvidence
    }

    fun updatedHold(current: Int, evidence: Boolean): Int =
        if(evidence) min(8,current+1) else max(0,current-1)

    /** Slow capture gets two corroborated samples; normal capture gets three. */
    fun requiredConfirmations(frameDeltaMs: Long): Int =
        if(frameDeltaMs>=180L) 2 else 3
}
