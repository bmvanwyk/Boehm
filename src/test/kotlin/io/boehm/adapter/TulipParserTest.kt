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
        assertTrue(result.summary!!.totalRequests > 0)
        assertTrue(result.summary!!.throughputReqPerSec > 0)
        assertTrue(result.summary!!.latency.p50Ms > 0)
        assertTrue(result.summary!!.latency.p99Ms > 0)
    }

    @Test
    fun `parse with missing fields throws`() {
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
