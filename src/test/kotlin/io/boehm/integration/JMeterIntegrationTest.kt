package io.boehm.integration

import io.boehm.adapters.jmeter.JMeterParser
import io.boehm.catalog.*
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class JMeterIntegrationTest {
    private val baseDir = File("").absolutePath

    private fun findJMeterCommand(): String? {
        val jmeterBin = "${System.getenv("HOME")}/.sdkman/apache-jmeter-5.6.3/bin/jmeter"
        if (File(jmeterBin).exists()) {
            return "JAVA_HOME=${System.getProperty("java.home")} $jmeterBin " +
                "-Jtarget_url={{target_url}} " +
                "-Jthreads={{threads}} " +
                "-Jduration_sec={{duration_sec}} " +
                "-n -t {{config_file}} " +
                "-l {{output_file}} " +
                "-j {{output_file}}.log"
        }
        // Docker fallback (podman needs fully-qualified) — skip if image not cached and no network
        val docker = System.getenv("PATH")?.split(File.pathSeparator)?.firstOrNull { File("$it/docker").exists() }
        if (docker != null && isDockerImagePresent("docker.io/justb4/jmeter:5.6.3")) {
            return "docker run --rm -v {{config_file}}:{{config_file}} -v {{output_file}}:{{output_file}} -v {{output_file}}.log:{{output_file}}.log docker.io/justb4/jmeter:5.6.3 " +
                "-Jtarget_url={{target_url}} " +
                "-Jthreads={{threads}} " +
                "-Jduration_sec={{duration_sec}} " +
                "-n -t {{config_file}} " +
                "-l {{output_file}} " +
                "-j {{output_file}}.log"
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
        val cmd = findJMeterCommand()
        assumeTrue(cmd != null, "JMeter not found (bare metal nor docker image cached)")

        val toolDef = ToolDef(
            name = "jmeter",
            description = "Apache JMeter (Java, GUI + CLI)",
            install = null,
            run = RunDef(command = cmd!!),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = "HTTP GET via JMeter Thread Group",
                    config = "profiles/jmeter/http-get.jmx",
                    output = OutputDef(
                        path = "{{output_file}}",
                        format = "csv",
                        schema = "jmeter-csv"
                    ),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = null, default = "httpbingo.org"),
                        "threads" to OverrideDef(path = null, default = "5"),
                        "duration_sec" to OverrideDef(path = null, default = "10")
                    )
                )
            )
        )
        val parsers = mapOf("jmeter-csv" to { raw: String -> JMeterParser.parse(raw) })
        return CatalogAdapter(toolDef, "http-get", baseDir, parsers)
    }

    @Test
    fun `jmeter CLI runs against httpbin and returns valid RunResult`() {
        val adapter = makeAdapter()
        val plan = TestPlan(
            type = "http",
            profile = "http-get",
            targetUrl = "httpbingo.org",
            ratePerSec = 5,
            durationSec = 10,
            warmupSec = 0,
            timeoutSec = 120
        )

        val result = adapter.run(plan)

        assertEquals("jmeter", result.tool)
        assertEquals("completed", result.status, "should complete, error: ${result.metadata["error"]}, stdout: ${result.metadata["stdout"]}")
        assertNotNull(result.summary, "summary should not be null, error: ${result.metadata["error"]}")
        assertTrue(result.summary!!.totalRequests > 0, "should have requests")
        assertEquals(0.0, result.summary!!.errorRatePct, 0.001, "should have 0% errors against httpbin")
        assertTrue(result.summary!!.latency.p50Ms > 0, "p50 should be positive")

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists(), "output file should exist")
            assertTrue(outputFile.length() > 0, "output file should not be empty")
        }
    }
}
