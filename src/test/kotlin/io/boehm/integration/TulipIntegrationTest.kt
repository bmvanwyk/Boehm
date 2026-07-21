package io.boehm.integration

import io.boehm.adapters.tulip.TulipAdapter
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipIntegrationTest {
    private val tulipRepo = File("/home/bvwyk/git/Tulip")

    @Test
    fun `tulip CLI runs against httpbin and returns valid RunResult`() {
        assumeTrue(tulipRepo.exists(), "Tulip repo not found at $tulipRepo")
        assumeTrue(File(tulipRepo, "gradlew").exists(), "Tulip Gradle wrapper not found")

        val wrapper = File("src/test/fixtures/run-real-tulip.sh")
        assumeTrue(wrapper.exists(), "Tulip wrapper script not found")
        wrapper.setExecutable(true)

        val adapter = TulipAdapter(tulipCommand = wrapper.absolutePath)
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
