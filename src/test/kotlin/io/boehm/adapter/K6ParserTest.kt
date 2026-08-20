package io.boehm.adapter

import io.boehm.adapters.k6.K6Parser
import io.boehm.model.RunResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class K6ParserTest {

    private val fixture: String by lazy {
        File("src/test/fixtures/k6-sample-output.jsonl").readText()
    }

    @Test
    fun `parse valid k6 NDJSON returns correct RunResult`() {
        val result = K6Parser.parse(fixture)

        assertEquals("k6", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)

        val summary = result.summary!!
        // 25 duration samples => 25 http_reqs increments.
        assertEquals(25, summary.totalRequests)
        // One request with status 500 => 1/25 = 4%.
        assertEquals(4.0, summary.errorRatePct, 0.001)
        assertTrue(summary.throughputReqPerSec > 0, "throughput should be positive")
        assertTrue(summary.latency.minMs > 0, "min latency should be positive")
        assertTrue(summary.latency.p50Ms > 0, "p50 should be positive")
        // Duration derived from timestamps (10:00:00 -> 10:00:24) => 24s.
        assertEquals(24, summary.durationSec)
    }

    @Test
    fun `latency percentiles are correct and ordered`() {
        val result = K6Parser.parse(fixture)
        val latency = result.summary!!.latency

        // Sorted durations: 10,20,...,250 (n=25)
        // p50 = ceil(0.5*25)-1 = 12 -> 130
        assertEquals(130.0, latency.p50Ms, 0.001)
        // p90 = ceil(0.9*25)-1 = 22 -> 230
        assertEquals(230.0, latency.p90Ms, 0.001)
        // p95 = ceil(0.95*25)-1 = 23 -> 240
        assertEquals(240.0, latency.p95Ms, 0.001)
        // p99 = ceil(0.99*25)-1 = 24 -> 250
        assertEquals(250.0, latency.p99Ms, 0.001)
        assertEquals(10.0, latency.minMs, 0.001)
        assertEquals(250.0, latency.maxMs, 0.001)

        assertTrue(latency.p99Ms >= latency.p95Ms, "p99 >= p95")
        assertTrue(latency.p95Ms >= latency.p90Ms, "p95 >= p90")
        assertTrue(latency.p90Ms >= latency.p50Ms, "p90 >= p50")
        assertTrue(latency.maxMs >= latency.p99Ms, "max >= p99")
    }

    @Test
    fun `error rate computed correctly`() {
        val result = K6Parser.parse(fixture)
        assertEquals(25, result.summary!!.totalRequests)
        assertEquals(4.0, result.summary!!.errorRatePct, 0.001)
    }

    @Test
    fun `throughput is positive`() {
        val result = K6Parser.parse(fixture)
        assertTrue(result.summary!!.throughputReqPerSec > 0, "throughput should be positive")
    }

    @Test
    fun `missing http_req_duration throws`() {
        val ndjson = """
            {"type":"Metric","metric":"http_reqs","data":{"value":1,"tags":{}}}
            {"type":"Metric","metric":"vus","data":{"value":5,"tags":{}}}
        """.trimIndent()
        assertThrows(Exception::class.java) {
            K6Parser.parse(ndjson)
        }
    }

    @Test
    fun `empty input throws`() {
        assertThrows(Exception::class.java) {
            K6Parser.parse("")
        }
    }

    @Test
    fun `whitespace-only input throws`() {
        assertThrows(Exception::class.java) {
            K6Parser.parse("   \n  \n  ")
        }
    }

    @Test
    fun `handles malformed lines gracefully by skipping them`() {
        // Mix of good data points and garbage lines.
        val ndjson = """
            {"type":"Metric","metric":"http_req_duration","data":{"time":"2026-08-20T10:00:00Z","value":100,"tags":{"status":"200"}}}
            {"type":"Metric","metric":"http_reqs","data":{"time":"2026-08-20T10:00:00Z","value":1,"tags":{}}}
            this is a malformed line that should be skipped
            {not valid json
            {"type":"Metric","metric":"http_req_duration","data":{"time":"2026-08-20T10:00:01Z","value":200,"tags":{"status":"200"}}}
            {"type":"Metric","metric":"http_reqs","data":{"time":"2026-08-20T10:00:01Z","value":1,"tags":{}}}
        """.trimIndent()

        val result = K6Parser.parse(ndjson)
        assertEquals(2, result.summary!!.totalRequests)
        assertEquals(2, result.metadata["samples"])
        assertEquals(0.0, result.summary!!.errorRatePct, 0.001)
        assertEquals(100.0, result.summary!!.latency.minMs, 0.001)
        assertEquals(200.0, result.summary!!.latency.maxMs, 0.001)
    }

    @Test
    fun `handles aggregated metric definition lines without value`() {
        val ndjson = """
            {"type":"Metric","metric":"http_req_duration","data":{"type":"Point","contains":"value","thresholds":{}}}
            {"type":"Metric","metric":"http_req_duration","data":{"time":"2026-08-20T10:00:00Z","value":50,"tags":{"status":"200"}}}
            {"type":"Metric","metric":"http_reqs","data":{"time":"2026-08-20T10:00:00Z","value":1,"tags":{}}}
        """.trimIndent()

        val result = K6Parser.parse(ndjson)
        assertEquals(1, result.summary!!.totalRequests)
        assertEquals(50.0, result.summary!!.latency.p50Ms, 0.001)
    }

    @Test
    fun `metadata contains sample counts`() {
        val result: RunResult = K6Parser.parse(fixture)
        assertEquals(25, result.metadata["samples"])
        assertEquals(25, result.metadata["total_requests"])
        assertEquals(1, result.metadata["failed_requests"])
        assertEquals(5, result.metadata["max_vus"])
        @Suppress("UNCHECKED_CAST")
        val codes = result.metadata["response_codes"] as Map<String, Int>
        assertEquals(24, codes["200"])
        assertEquals(1, codes["500"])
    }
}
