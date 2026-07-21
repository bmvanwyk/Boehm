package io.boehm.integration

import io.boehm.adapters.tulip.TulipAdapter
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipIntegrationTest {
    @Test
    fun `tulip CLI runs against httpbin and returns valid RunResult`() {
        val tulipCheck = try {
            ProcessBuilder("which", "tulip").start().waitFor()
        } catch (_: Exception) { 1 }
        assumeTrue(tulipCheck == 0, "tulip CLI not found on PATH")

        val adapter = TulipAdapter()
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 50,
            durationSec = 10,
            warmupSec = 2
        )

        val result = adapter.run(plan)
        assertEquals("tulip", result.tool)
        assertNotNull(result.summary)
        assertTrue(result.summary!!.totalRequests > 0)
        assertTrue(result.summary!!.throughputReqPerSec > 10)
        assertTrue(result.summary!!.latency.p50Ms > 0)
        assertTrue(result.summary!!.latency.p99Ms > 0)

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0)
        }
    }

    @Test
    fun `tulip CLI non-zero exit when target unreachable`() {
        val tulipCheck = try {
            ProcessBuilder("which", "tulip").start().waitFor()
        } catch (_: Exception) { 1 }
        assumeTrue(tulipCheck == 0, "tulip CLI not found on PATH")

        val adapter = TulipAdapter()
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://nonexistent.example.com:9999/",
            ratePerSec = 10,
            durationSec = 5,
            warmupSec = 1
        )

        val result = adapter.run(plan)
        assertEquals("tulip", result.tool)
        assertEquals("failed", result.status)
    }
}
