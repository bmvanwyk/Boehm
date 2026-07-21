package io.boehm.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunResultTest {
    private val gson = GsonBuilder().serializeNulls().create()

    @Test
    fun `RunResult serializes and deserializes correctly`() {
        val original = RunResult(
            tool = "tulip",
            testName = "http-get",
            timestamp = "2026-07-20T10:00:00Z",
            runId = "test-uuid",
            status = "completed",
            summary = Summary(
                durationSec = 30,
                totalRequests = 2850,
                throughputReqPerSec = 95.0,
                errorRatePct = 0.0,
                latency = Latency(
                    minMs = 2.1, p50Ms = 8.5, p90Ms = 22.3,
                    p95Ms = 35.0, p99Ms = 68.1, maxMs = 210.0
                )
            ),
            rawOutputPath = "/tmp/output.json",
            metadata = emptyMap()
        )

        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, RunResult::class.java)

        assertEquals(original.runId, deserialized.runId)
        assertEquals(original.summary!!.latency.p95Ms, deserialized.summary!!.latency.p95Ms, 0.001)
        assertEquals(original.summary!!.throughputReqPerSec, deserialized.summary!!.throughputReqPerSec, 0.001)
    }

    @Test
    fun `RunResult with null summary serializes correctly`() {
        val result = RunResult(
            tool = "tulip", testName = "http-get",
            timestamp = "2026-07-20T10:00:00Z", runId = "uuid",
            status = "queued", summary = null,
            rawOutputPath = null, metadata = emptyMap()
        )
        val json = gson.toJson(result)
        assertTrue(json.contains("\"status\":\"queued\""))
        assertTrue(json.contains("\"summary\":null"))
    }
}
