package io.boehm.integration

import io.boehm.adapters.k6.K6Parser
import io.boehm.catalog.*
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class K6IntegrationTest {
    private val baseDir = File("").absolutePath

    private fun k6Path(): String? {
        val fromPath = System.getenv("PATH")
            .split(File.pathSeparator)
            .firstOrNull { p -> File("$p/k6").exists() }
            ?.let { "$it/k6" }
        if (fromPath != null) return fromPath
        val goBin = "${System.getenv("HOME")}/go/bin/k6"
        return if (File(goBin).exists()) goBin else null
    }

    private fun makeAdapter(): CatalogAdapter {
        val k6Bin = k6Path()
        assumeTrue(k6Bin != null, "k6 binary not found on PATH or ~/go/bin/k6")

        val toolDef = ToolDef(
            name = "k6",
            description = "Load testing tool by Grafana (JavaScript)",
            install = null,
            run = RunDef(
                command = "$k6Bin run " +
                    "-e TARGET_URL={{target_url}} " +
                    "-e RATE_PER_SEC={{rate_per_sec}} " +
                    "-e DURATION_SEC={{duration_sec}} " +
                    "--out json={{output_file}} " +
                    "{{config_file}}"
            ),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = "HTTP GET with configurable rate and duration",
                    config = "profiles/k6/http-get.js",
                    output = OutputDef(
                        path = "{{output_file}}",
                        format = "jsonl",
                        schema = "k6-jsonl"
                    ),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = null, default = "https://httpbin.org/get"),
                        "rate_per_sec" to OverrideDef(path = null, default = "50"),
                        "duration_sec" to OverrideDef(path = null, default = "30")
                    )
                )
            )
        )
        val parsers = mapOf("k6-jsonl" to { raw: String -> K6Parser.parse(raw) })
        return CatalogAdapter(toolDef, "http-get", baseDir, parsers)
    }

    @Test
    fun `k6 CLI runs against httpbin and returns valid RunResult`() {
        val adapter = makeAdapter()
        val plan = TestPlan(
            type = "http",
            profile = "http-get",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 20,
            durationSec = 12,
            warmupSec = 0,
            timeoutSec = 120
        )

        val result = adapter.run(plan)

        assertEquals("k6", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary, "summary should not be null, error: ${result.metadata["error"]}")
        assertTrue(result.summary!!.totalRequests > 0, "should have requests")
        // httpbin/get always returns 200, so no client-side failed requests.
        assertEquals(0.0, result.summary!!.errorRatePct, 0.001, "should have 0% errors against httpbin")
        assertTrue(result.summary!!.latency.p50Ms > 0, "p50 should be positive")

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists(), "output file should exist")
            assertTrue(outputFile.length() > 0, "output file should not be empty")
        }
    }
}
