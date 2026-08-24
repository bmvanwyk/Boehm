package io.boehm.core

import io.boehm.model.Summary
import kotlin.math.abs

/**
 * Compares two run summaries metric-by-metric. Direction-aware: higher
 * throughput is better, lower latency/error-rate is better. Deltas beyond
 * [REGRESSION_THRESHOLD_PCT] are flagged.
 */
object Comparator {

    private const val REGRESSION_THRESHOLD_PCT = 10.0

    data class MetricDelta(
        val baseline: Double,
        val run: Double,
        val deltaPct: Double?,
        /** One of "regression", "improvement", "unchanged". */
        val verdict: String
    )

    data class Comparison(
        val metrics: Map<String, MetricDelta>,
        val regressions: List<String>,
        val improvements: List<String>
    )

    fun compare(runSummary: Summary, baselineSummary: Summary): Comparison {
        // name -> Triple(baselineValue, runValue, higherIsBetter)
        val raw = linkedMapOf<String, Triple<Double, Double, Boolean>>(
            "throughputReqPerSec" to Triple(
                baselineSummary.throughputReqPerSec, runSummary.throughputReqPerSec, true),
            "errorRatePct" to Triple(
                baselineSummary.errorRatePct, runSummary.errorRatePct, false),
            "p50Ms" to Triple(baselineSummary.latency.p50Ms, runSummary.latency.p50Ms, false),
            "p90Ms" to Triple(baselineSummary.latency.p90Ms, runSummary.latency.p90Ms, false),
            "p95Ms" to Triple(baselineSummary.latency.p95Ms, runSummary.latency.p95Ms, false),
            "p99Ms" to Triple(baselineSummary.latency.p99Ms, runSummary.latency.p99Ms, false),
            "meanMs" to Triple(baselineSummary.latency.meanMs, runSummary.latency.meanMs, false),
            "maxMs" to Triple(baselineSummary.latency.maxMs, runSummary.latency.maxMs, false)
        )

        val metrics = raw.mapValues { (_, t) ->
            val (baselineVal, runVal, higherIsBetter) = t
            val deltaPct = if (baselineVal == 0.0) null else (runVal - baselineVal) / baselineVal * 100.0
            val verdict = if (deltaPct == null || abs(deltaPct) <= REGRESSION_THRESHOLD_PCT) {
                "unchanged"
            } else {
                val improved = if (higherIsBetter) deltaPct > 0 else deltaPct < 0
                if (improved) "improvement" else "regression"
            }
            MetricDelta(baselineVal, runVal, deltaPct, verdict)
        }

        return Comparison(
            metrics = metrics,
            regressions = metrics.filterValues { it.verdict == "regression" }.keys.toList(),
            improvements = metrics.filterValues { it.verdict == "improvement" }.keys.toList()
        )
    }
}
