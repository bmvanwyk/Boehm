package io.boehm.adapter

import io.boehm.adapters.gatling.GatlingParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class GatlingParserTest {

    @Test
    fun `parse valid gatling JSON returns correct RunResult`() {
        val json = File("src/test/fixtures/gatling-sample-output.json").readText()
        val result = GatlingParser.parse(json)

        assertEquals("gatling", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertEquals(1500, result.summary!!.totalRequests)
        assertEquals(1.0, result.summary!!.errorRatePct, 0.001) // 15/1500 =1%
        assertEquals(50.0, result.summary!!.throughputReqPerSec, 0.001)
        assertEquals(30, result.summary!!.durationSec) // 1500/50 =30
        assertEquals(5.0, result.summary!!.latency.minMs, 0.001)
        assertEquals(800.0, result.summary!!.latency.maxMs, 0.001)
        assertEquals(45.0, result.summary!!.latency.meanMs, 0.001)
        assertEquals(25.0, result.summary!!.latency.stdevMs, 0.001)
        assertEquals(40.0, result.summary!!.latency.p50Ms, 0.001)
        assertEquals(60.0, result.summary!!.latency.p90Ms, 0.001)
        assertEquals(110.0, result.summary!!.latency.p95Ms, 0.001)
        assertEquals(250.0, result.summary!!.latency.p99Ms, 0.001)
    }

    @Test
    fun `latency percentiles are ordered`() {
        val json = File("src/test/fixtures/gatling-sample-output.json").readText()
        val result = GatlingParser.parse(json)
        val l = result.summary!!.latency
        assertTrue(l.p50Ms <= l.p90Ms)
        assertTrue(l.p90Ms <= l.p95Ms)
        assertTrue(l.p95Ms <= l.p99Ms)
    }

    @Test
    fun `handles nested stats object`() {
        val inner = File("src/test/fixtures/gatling-sample-output.json").readText()
        val wrapped = """{"stats": $inner}"""
        val result = GatlingParser.parse(wrapped)
        assertEquals(1500, result.summary!!.totalRequests)
    }

    @Test
    fun `handles string numbers and dash for ko`() {
        val json = """
            {
              "name": "Global Information",
              "numberOfRequests": {"total":"100","ok":"100","ko":"-"},
              "minResponseTime": {"total":"10"},
              "maxResponseTime": {"total":"100"},
              "meanResponseTime": {"total":"30"},
              "standardDeviation": {"total":"5"},
              "percentiles1": {"total":"25"},
              "percentiles2": {"total":"40"},
              "percentiles3": {"total":"70"},
              "percentiles4": {"total":"95"},
              "meanNumberOfRequestsPerSecond": {"total":"10.0"}
            }
        """.trimIndent()
        val result = GatlingParser.parse(json)
        assertEquals(0.0, result.summary!!.errorRatePct, 0.001)
        assertEquals(100, result.summary!!.totalRequests)
    }

    @Test
    fun `throws on empty input`() {
        assertThrows(IllegalArgumentException::class.java) { GatlingParser.parse("") }
        assertThrows(IllegalArgumentException::class.java) { GatlingParser.parse("  ") }
    }

    @Test
    fun `throws on invalid json`() {
        assertThrows(IllegalArgumentException::class.java) { GatlingParser.parse("not json") }
    }

    @Test
    fun `throws when missing numberOfRequests`() {
        assertThrows(IllegalArgumentException::class.java) { GatlingParser.parse("""{"name":"Global Information"}""") }
    }

    @Test
    fun `metadata contains gatling flag and counts`() {
        val json = File("src/test/fixtures/gatling-sample-output.json").readText()
        val result = GatlingParser.parse(json)
        assertEquals(true, result.metadata["gatling"])
        assertEquals(1500L, result.metadata["total_requests"])
    }
}
