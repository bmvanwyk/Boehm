package io.boehm.adapter

import io.boehm.adapters.jmeter.JMeterParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class JMeterParserTest {

    @Test
    fun `parse valid JMeter CSV returns correct RunResult`() {
        val csv = File("src/test/fixtures/jmeter-sample-output.csv").readText()
        val result = JMeterParser.parse(csv)

        assertEquals("jmeter", result.tool)
        assertEquals("completed", result.status)
        assertEquals("HTTP Request", result.testName)
        assertNotNull(result.summary)

        val summary = result.summary!!
        assertEquals(20, summary.totalRequests)
        assertEquals(5.0, summary.errorRatePct, 0.001) // 1 failure out of 20 = 5%
        assertTrue(summary.throughputReqPerSec > 0, "throughput should be positive")
        assertTrue(summary.latency.minMs > 0, "min latency should be positive")
        assertTrue(summary.latency.p50Ms > 0, "p50 should be positive")
        assertTrue(summary.latency.p99Ms >= summary.latency.p95Ms, "p99 >= p95")
        assertTrue(summary.latency.p95Ms >= summary.latency.p90Ms, "p95 >= p90")
        assertTrue(summary.latency.p90Ms >= summary.latency.p50Ms, "p90 >= p50")
        assertTrue(summary.latency.maxMs >= summary.latency.p99Ms, "max >= p99")
    }

    @Test
    fun `parse computes correct latency percentiles`() {
        // 10 samples with elapsed: 100,200,300,...,1000
        val csv = "timeStamp,elapsed,success\n" +
            (1..10).joinToString("\n") { i ->
                "${1721452000000L + (i - 1) * 100},${i * 100},true"
            }

        val result = JMeterParser.parse(csv)
        val latency = result.summary!!.latency

        // sorted: 100,200,...,1000
        // p50 = ceil(0.5*10)-1 = 4 → index 4 = 500
        assertEquals(500.0, latency.p50Ms, 0.001)
        // p90 = ceil(0.9*10)-1 = 8 → index 8 = 900
        assertEquals(900.0, latency.p90Ms, 0.001)
        // p95 = ceil(0.95*10)-1 = 9 → index 9 = 1000
        assertEquals(1000.0, latency.p95Ms, 0.001)
        // p99 = ceil(0.99*10)-1 = 9 → index 9 = 1000
        assertEquals(1000.0, latency.p99Ms, 0.001)
        assertEquals(100.0, latency.minMs, 0.001)
        assertEquals(1000.0, latency.maxMs, 0.001)
    }

    @Test
    fun `parse computes error rate correctly`() {
        val csv = "timeStamp,elapsed,success\n" +
            "1000,100,true\n" +
            "2000,100,false\n" +
            "3000,100,false\n" +
            "4000,100,true\n"

        val result = JMeterParser.parse(csv)
        assertEquals(4, result.summary!!.totalRequests)
        assertEquals(50.0, result.summary!!.errorRatePct, 0.001)
    }

    @Test
    fun `parse computes throughput from timestamps`() {
        // 10 samples, 100ms apart → 1 second span → 10 req/s
        val csv = "timeStamp,elapsed,success\n" +
            (1..10).joinToString("\n") { i ->
                "${1000L + (i - 1) * 100},50,true"
            }

        val result = JMeterParser.parse(csv)
        // duration = (max_ts - min_ts) / 1000 = 900ms / 1000 = 0.9 → coerced to 1
        // throughput = 10 / 1 = 10
        assertEquals(10.0, result.summary!!.throughputReqPerSec, 0.001)
    }

    @Test
    fun `parse with missing elapsed column throws`() {
        val csv = "timeStamp,success\n1000,true\n2000,false\n"
        assertThrows(Exception::class.java) {
            JMeterParser.parse(csv)
        }
    }

    @Test
    fun `parse with only header throws`() {
        assertThrows(Exception::class.java) {
            JMeterParser.parse("timeStamp,elapsed,success\n")
        }
    }

    @Test
    fun `parse with empty input throws`() {
        assertThrows(Exception::class.java) {
            JMeterParser.parse("")
        }
    }

    @Test
    fun `parse handles quoted fields with embedded commas`() {
        val csv = "timeStamp,elapsed,label,success\n" +
            "1000,150,\"HTTP, GET Request\",true\n" +
            "2000,200,\"HTTP, GET Request\",true\n"

        val result = JMeterParser.parse(csv)
        assertEquals(2, result.summary!!.totalRequests)
        assertEquals("HTTP, GET Request", result.testName)
    }

    @Test
    fun `parse captures response code metadata`() {
        val csv = "timeStamp,elapsed,responseCode,success\n" +
            "1000,100,200,true\n" +
            "2000,200,200,true\n" +
            "3000,150,500,false\n"

        val result = JMeterParser.parse(csv)
        @Suppress("UNCHECKED_CAST")
        val codes = result.metadata["response_codes"] as Map<String, Int>
        assertEquals(2, codes["200"])
        assertEquals(1, codes["500"])
    }

    @Test
    fun `parse with all failures reports 100pct error rate`() {
        val csv = "timeStamp,elapsed,success\n" +
            "1000,100,false\n" +
            "2000,200,false\n"

        val result = JMeterParser.parse(csv)
        assertEquals(100.0, result.summary!!.errorRatePct, 0.001)
    }

    @Test
    fun `parse includes sample count and failed count in metadata`() {
        val csv = "timeStamp,elapsed,success\n" +
            "1000,100,true\n" +
            "2000,200,true\n" +
            "3000,150,false\n"

        val result = JMeterParser.parse(csv)
        assertEquals(3, result.metadata["samples"] as Int)
        assertEquals(1, result.metadata["failed"] as Int)
    }
}
