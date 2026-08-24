package io.boehm.core

import io.boehm.model.Latency
import io.boehm.model.Summary
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComparatorTest {
    private fun summary(throughput: Double, errorPct: Double, p95: Double) = Summary(
        durationSec = 30, totalRequests = 1000,
        throughputReqPerSec = throughput, errorRatePct = errorPct,
        latency = Latency(1.0, 10.0, 20.0, p95, 40.0, 50.0, meanMs = 15.0, stdevMs = 4.0)
    )

    @Test
    fun `flags throughput drop above threshold as regression`() {
        val verdicts = Comparator.compare(
            runSummary = summary(80.0, 0.5, 20.0),
            baselineSummary = summary(100.0, 0.5, 20.0)
        )
        val throughput = verdicts.metrics["throughputReqPerSec"]!!
        assertEquals("regression", throughput.verdict)
        assertEquals(-20.0, throughput.deltaPct!!, 0.001)
    }

    @Test
    fun `flags latency rise as regression and error rate drop as improvement`() {
        val verdicts = Comparator.compare(
            runSummary = summary(100.0, 0.1, 25.0),
            baselineSummary = summary(100.0, 1.0, 20.0)
        )
        assertEquals("regression", verdicts.metrics["p95Ms"]!!.verdict)
        assertEquals("improvement", verdicts.metrics["errorRatePct"]!!.verdict)
        assertEquals(listOf("p95Ms"), verdicts.regressions)
        assertEquals(listOf("errorRatePct"), verdicts.improvements)
    }

    @Test
    fun `within-threshold deltas are unchanged`() {
        val verdicts = Comparator.compare(
            runSummary = summary(105.0, 0.5, 21.0),
            baselineSummary = summary(100.0, 0.5, 20.0)
        )
        assertEquals("unchanged", verdicts.metrics["throughputReqPerSec"]!!.verdict)
        assertTrue(verdicts.regressions.isEmpty())
    }

    @Test
    fun `zero baseline yields null delta`() {
        val verdicts = Comparator.compare(
            runSummary = summary(100.0, 0.0, 20.0),
            baselineSummary = summary(0.0, 0.0, 0.0)
        )
        assertNull(verdicts.metrics["throughputReqPerSec"]!!.deltaPct)
    }
}
