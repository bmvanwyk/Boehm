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

    private fun findK6Command(): String? {
        val fromPath = System.getenv("PATH")
            .split(File.pathSeparator)
            .firstOrNull { p -> File("$p/k6").exists() }
            ?.let { "$it/k6" }
        if (fromPath != null) {
            return "$fromPath run -e TARGET_URL={{target_url}} -e RATE_PER_SEC={{rate_per_sec}} -e DURATION_SEC={{duration_sec}} --out json={{output_file}} {{config_file}}"
        }
        val goBin = "${System.getenv("HOME")}/go/bin/k6"
        if (File(goBin).exists()) {
            return "$goBin run -e TARGET_URL={{target_url}} -e RATE_PER_SEC={{rate_per_sec}} -e DURATION_SEC={{duration_sec}} --out json={{output_file}} {{config_file}}"
        }
        // Docker fallback (podman needs fully-qualified) — skip if image not cached
        val docker = System.getenv("PATH")?.split(File.pathSeparator)?.firstOrNull { File("$it/docker").exists() }
        if (docker != null && isDockerImagePresent("docker.io/grafana/k6:latest")) {
            return "docker run --rm -v {{config_file}}:{{config_file}} -v {{output_file}}:{{output_file}} docker.io/grafana/k6:latest run -e TARGET_URL={{target_url}} -e RATE_PER_SEC={{rate_per_sec}} -e DURATION_SEC={{duration_sec}} --out json={{output_file}} {{config_file}}"
        }
        return null
    }

    private fun isDockerImagePresent(image: String): Boolean {
        return try {
            val proc = ProcessBuilder("docker", "image", "inspect", image).redirectErrorStream(true).start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun makeAdapter(): CatalogAdapter {
        val cmd = findK6Command()
        assumeTrue(cmd != null, "k6 not found (bare metal nor docker image cached)")

        val toolDef = ToolDef(
            name = "k6",
            description = "Load testing tool by Grafana (JavaScript)",
            install = null,
            run = RunDef(command = cmd!!),
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
                        "target_url" to OverrideDef(path = null, default = "https://httpbingo.org/get"),
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
            targetUrl = "https://httpbingo.org/get",
            ratePerSec = 20,
            durationSec = 12,
            warmupSec = 0,
            timeoutSec = 120
        )

        val result = adapter.run(plan)

        assertEquals("k6", result.tool)
        assertEquals("completed", result.status, "should complete, error: ${result.metadata["error"]}, stdout: ${result.metadata["stdout"]}")
        assertNotNull(result.summary, "summary should not be null, error: ${result.metadata["error"]}")
        assertTrue(result.summary!!.totalRequests > 0, "should have requests")
        // httpbingo.org/get always returns 200, so no client-side failed requests.
        assertEquals(0.0, result.summary!!.errorRatePct, 0.001, "should have 0% errors against httpbingo.org")
        assertTrue(result.summary!!.latency.p50Ms > 0, "p50 should be positive")

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists(), "output file should exist")
            assertTrue(outputFile.length() > 0, "output file should not be empty")
        }
    }
}
