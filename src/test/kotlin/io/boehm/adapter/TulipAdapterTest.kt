package io.boehm.adapter

import io.boehm.adapters.tulip.TulipParser
import io.boehm.catalog.CatalogAdapter
import io.boehm.catalog.*
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipAdapterTest {
    private val baseDir = File("").absolutePath
    private val mockScript = File("src/test/fixtures/mock-tulip.sh").absolutePath

    private fun makeAdapter(): CatalogAdapter {
        val toolDef = ToolDef(
            name = "tulip",
            description = "Test tool",
            install = null,
            run = RunDef(command = "$mockScript --config {{config_file}}"),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = null,
                    config = "profiles/tulip/http-get.jsonc",
                    output = OutputDef(
                        path = "{{config.actions.output_filename}}",
                        format = "json",
                        schema = "tulip-results"
                    ),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = "actions.user_params.url", default = "https://httpbin.org/get"),
                        "rate_per_sec" to OverrideDef(path = "benchmarks.boehm-benchmark.aps_rate", default = 50),
                        "duration_sec" to OverrideDef(path = "benchmarks.boehm-benchmark.benchmark_duration", default = 30),
                        "warmup_sec" to OverrideDef(path = "benchmarks.boehm-benchmark.warmup_duration1", default = 5)
                    )
                )
            )
        )
        val parsers = mapOf("tulip-results" to { raw: String -> TulipParser.parse(raw) })
        return CatalogAdapter(toolDef, "http-get", baseDir, parsers)
    }

    @Test
    fun `adapter name and version are set`() {
        val adapter = makeAdapter()
        assertEquals("tulip", adapter.name)
        assertTrue(adapter.version.isNotBlank())
    }

    @Test
    fun `run executes CLI and returns RunResult`() {
        val adapter = makeAdapter()
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
        val adapter = makeAdapter()
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
        val adapter = makeAdapter()
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
        val adapter = makeAdapter()
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
