package io.boehm.integration

import io.boehm.adapters.tulip.TulipParser
import io.boehm.catalog.*
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipIntegrationTest {
    private val tulipRepo = File("/home/bvwyk/git/Tulip")
    private val baseDir = File("").absolutePath

    private fun makeAdapter(): CatalogAdapter {
        assumeTrue(tulipRepo.exists(), "Tulip repo not found at $tulipRepo")
        assumeTrue(File(tulipRepo, "gradlew").exists(), "Tulip Gradle wrapper not found")

        val wrapper = File("src/test/fixtures/run-real-tulip.sh")
        assumeTrue(wrapper.exists(), "Tulip wrapper script not found")
        wrapper.setExecutable(true)

        val toolDef = ToolDef(
            name = "tulip",
            description = "Real Tulip CLI",
            install = null,
            run = RunDef(command = "${wrapper.absolutePath} --config {{config_file}}"),
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
    fun `tulip CLI runs against httpbin and returns valid RunResult`() {
        val adapter = makeAdapter()
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 50,
            durationSec = 10,
            warmupSec = 2
        )

        val result = adapter.run(plan)
        assertEquals("tulip", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertTrue(result.summary!!.totalRequests > 0)
        assertTrue(result.summary!!.durationSec > 0)

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0)
        }
    }
}
