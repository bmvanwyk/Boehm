package io.boehm.adapter

import io.boehm.adapters.tulip.TulipAdapter
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipAdapterTest {
    private val mockScript = File("src/test/fixtures/mock-tulip.sh").absolutePath

    @Test
    fun `adapter name and version are set`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        assertEquals("tulip", adapter.name)
        assertTrue(adapter.version.isNotBlank())
    }

    @Test
    fun `run executes CLI and returns RunResult`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 100,
            durationSec = 5,
            warmupSec = 1
        )
        val result = adapter.run(plan)
        assertEquals("tulip", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertTrue(result.summary!!.totalRequests > 0)
    }

    @Test
    fun `validate rejects missing targetUrl`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "",
            ratePerSec = 100,
            durationSec = 30
        )
        val errors = adapter.validate(plan)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `validate rejects zero duration`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://example.com",
            ratePerSec = 100,
            durationSec = 0
        )
        val errors = adapter.validate(plan)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `validate accepts valid plan`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 100,
            durationSec = 10,
            warmupSec = 2
        )
        val errors = adapter.validate(plan)
        assertTrue(errors.isEmpty())
    }
}
