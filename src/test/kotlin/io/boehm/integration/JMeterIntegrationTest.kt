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

    private fun jmeterAvailable(): Boolean {
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        val jmeterPath = "${System.getenv("HOME")}/.sdkman/apache-jmeter-5.6.3/bin/jmeter"
        return File(jmeterPath).exists() && javaHome != null
    }

    private fun makeAdapter(): CatalogAdapter {
        val jmeterBin = "${System.getenv("HOME")}/.sdkman/apache-jmeter-5.6.3/bin/jmeter"
        assumeTrue(File(jmeterBin).exists(), "JMeter not found at $jmeterBin")

        val toolDef = ToolDef(
            name = "jmeter",
            description = "Apache JMeter (Java, GUI + CLI)",
            install = null,
            run = RunDef(
                command = "JAVA_HOME=${System.getProperty("java.home")} $jmeterBin " +
                    "-Jtarget_url={{target_url}} " +
                    "-Jthreads={{threads}} " +
                    "-Jduration_sec={{duration_sec}} " +
                    "-n -t {{config_file}} " +
                    "-l {{output_file}} " +
                    "-j {{output_file}}.log"
            ),
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
                        "target_url" to OverrideDef(path = null, default = "httpbin.org"),
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
            targetUrl = "httpbin.org",
            ratePerSec = 5,
            durationSec = 10,
            warmupSec = 0,
            timeoutSec = 120
        )

        val result = adapter.run(plan)

        assertEquals("jmeter", result.tool)
        assertEquals("completed", result.status)
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
