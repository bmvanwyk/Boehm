package io.boehm.adapter

import io.boehm.adapters.tulip.TulipParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipParserTest {
    @Test
    fun `parse valid Tulip JSON output returns correct RunResult`() {
        val json = File("src/test/fixtures/tulip-sample-output.json").readText()
        val result = TulipParser.parse(json)

        assertEquals("tulip", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertEquals(30, result.summary!!.durationSec)
        assertEquals(2850, result.summary!!.totalRequests)
        assertEquals(95.0, result.summary!!.throughputReqPerSec, 0.001)
        assertEquals(0.0, result.summary!!.errorRatePct, 0.001)
        assertEquals(2.1, result.summary!!.latency.minMs, 0.001)
        assertEquals(8.5, result.summary!!.latency.p50Ms, 0.001)
        assertEquals(22.3, result.summary!!.latency.p90Ms, 0.001)
        assertEquals(35.0, result.summary!!.latency.p95Ms, 0.001)
        assertEquals(68.1, result.summary!!.latency.p99Ms, 0.001)
        assertEquals(210.0, result.summary!!.latency.maxMs, 0.001)
    }

    @Test
    fun `parse with missing results array throws`() {
        assertThrows(Exception::class.java) {
            TulipParser.parse("{}")
        }
    }

    @Test
    fun `parse with invalid JSON throws`() {
        assertThrows(Exception::class.java) {
            TulipParser.parse("not json")
        }
    }

    @Test
    fun `parse with empty results array throws`() {
        assertThrows(Exception::class.java) {
            TulipParser.parse("""{"results":[]}""")
        }
    }

    @Test
    fun `parse with missing duration throws`() {
        assertThrows(Exception::class.java) {
            TulipParser.parse("""{"results":[{"num_actions":100}]}""")
        }
    }

    @Test
    fun `parse handles missing optional fields`() {
        val json = """{"results":[{"duration":10}]}"""
        val result = TulipParser.parse(json)
        assertEquals("completed", result.status)
        assertEquals(0, result.summary!!.totalRequests)
        assertEquals(0.0, result.summary!!.errorRatePct)
    }

    @Test
    fun `parseProgressLine returns null for non-progress`() {
        assertNull(TulipParser.parseProgressLine("""{"type":"result"}"""))
    }

    @Test
    fun `parseProgressLine parses progress object`() {
        val result = TulipParser.parseProgressLine("""{"type":"progress","pct":50.0}""")
        assertNotNull(result)
        assertEquals("50.0", result!!["pct"].toString())
    }

    @Test
    fun `parseProgressLine returns null for invalid JSON`() {
        assertNull(TulipParser.parseProgressLine("not json"))
    }

    @Test
    fun `parse includes all metadata fields`() {
        val json = """{"version":"1.0","timestamp":"2026-07-21","results":[{"bm_name":"test","row_id":0,"context_name":"ctx","sd_rt":5000000.0,"avg_rt":8500000.0,"num_threads":4,"num_users":10,"duration":30,"hdr_histogram_rt":"HIST","num_actions":100,"num_failed":0,"avg_aps":3.33,"min_rt":1000000.0,"max_rt":50000000.0,"percentiles_rt":{"50.0":5000000.0}}]}"""
        val result = TulipParser.parse(json)
        assertEquals("test", result.testName)
        assertNotNull(result.metadata["benchmark"])
        assertNotNull(result.metadata["row"])
        assertNotNull(result.metadata["context"])
        assertNotNull(result.metadata["sd_rt_ns"])
        assertNotNull(result.metadata["avg_rt_ns"])
        assertNotNull(result.metadata["threads"])
        assertNotNull(result.metadata["users"])
        assertNotNull(result.metadata["test_timestamp"])
        assertNotNull(result.metadata["tulip_version"])
        assertNotNull(result.metadata["hdr_histogram"])
    }
}
