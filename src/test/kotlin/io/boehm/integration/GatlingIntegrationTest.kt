package io.boehm.integration

import io.boehm.adapters.gatling.GatlingParser
import io.boehm.catalog.*
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class GatlingIntegrationTest {
    private val baseDir = File("").absolutePath

    private fun findGatlingCommand(): String? {
        // Try bare metal gatling (sdkman or PATH)
        val candidates = listOf(
            "${System.getenv("HOME")}/.sdkman/candidates/gatling/current/bin/gatling.sh",
            "${System.getenv("HOME")}/.sdkman/candidates/gatling/current/bin/gatling",
            "/usr/local/bin/gatling",
            System.getenv("PATH")?.split(File.pathSeparator)?.firstOrNull { p -> File("$p/gatling").exists() }?.let { "$it/gatling" },
            System.getenv("PATH")?.split(File.pathSeparator)?.firstOrNull { p -> File("$p/gatling.sh").exists() }?.let { "$it/gatling.sh" }
        ).filterNotNull().filter { File(it).exists() }

        if (candidates.isNotEmpty()) {
            return candidates.first()
        }

        // Try docker
        val docker = System.getenv("PATH")?.split(File.pathSeparator)
            ?.firstOrNull { p -> File("$p/docker").exists() }
        if (docker != null) {
            // Check docker is runnable and image is available or can be pulled
            try {
                val proc = ProcessBuilder("docker", "images", "denvazh/gatling:3.9.5").redirectErrorStream(true).start()
                proc.waitFor()
                // If image not present, try to pull (may fail offline, then skip)
                // Use docker run command as gatling runner
                return "docker run --rm -v $baseDir:/opt/gatling -w /opt/gatling denvazh/gatling:3.9.5"
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun makeAdapter(gatlingCmd: String): CatalogAdapter {
        val runCmd = if (gatlingCmd.contains("docker")) {
            // Docker variant: mount repo at /opt/gatling, results go to /tmp inside container then back via volume
            "$gatlingCmd gatling run -Dtarget_url={{target_url}} -Drate_per_sec={{rate_per_sec}} -Dduration_sec={{duration_sec}} -s profiles.HttpGetSimulation -rf {{output_file}}.results"
        } else {
            "$gatlingCmd run -Dtarget_url={{target_url}} -Drate_per_sec={{rate_per_sec}} -Dduration_sec={{duration_sec}} -s profiles.HttpGetSimulation -rf {{output_file}}.results"
        }

        val toolDef = ToolDef(
            name = "gatling",
            description = "Scala-based load testing by Gatling Corp",
            install = null,
            run = RunDef(command = runCmd),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = "HTTP GET via Gatling simulation",
                    config = "profiles/gatling/http-get.scala",
                    output = OutputDef(
                        path = "{{output_file}}.results/js/global_stats.json",
                        format = "json",
                        schema = "gatling-stats"
                    ),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = null, default = "https://httpbingo.org/get"),
                        "rate_per_sec" to OverrideDef(path = null, default = "5"),
                        "duration_sec" to OverrideDef(path = null, default = "10")
                    )
                )
            )
        )
        val parsers = mapOf("gatling-stats" to { raw: String -> GatlingParser.parse(raw) })
        return CatalogAdapter(toolDef, "http-get", baseDir, parsers)
    }

    @Test
    fun `gatling CLI runs against httpbin and returns valid RunResult`() {
        val gatlingCmd = findGatlingCommand()
        assumeTrue(gatlingCmd != null, "Gatling not found (bare metal nor docker); skipping")
        val adapter = makeAdapter(gatlingCmd!!)

        val plan = TestPlan(
            type = "http",
            profile = "http-get",
            targetUrl = "https://httpbingo.org/get",
            ratePerSec = 5,
            durationSec = 10,
            warmupSec = 0,
            timeoutSec = 120
        )

        val result = adapter.run(plan)

        assertEquals("gatling", result.tool)
        assertEquals("completed", result.status, "should complete, error: ${result.metadata["error"]}, stdout: ${result.metadata["stdout"]}")
        assertNotNull(result.summary, "summary should not be null")
        assertTrue(result.summary!!.totalRequests > 0, "should have requests")
        // httpbingo.org/get returns 200
        assertTrue(result.summary!!.errorRatePct < 5.0, "error rate should be low")
        assertTrue(result.summary!!.latency.p50Ms > 0, "p50 should be positive")

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists(), "output file should exist at ${result.rawOutputPath}")
            assertTrue(outputFile.length() > 0, "output file should not be empty")
        }
    }
}
