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
}
