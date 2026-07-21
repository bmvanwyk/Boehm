package io.boehm.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProgressEventTest {

    @Test
    fun `ProgressEvent stores all fields`() {
        val summary = Summary(30, 100, 50.0, 0.5, Latency(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        val event = ProgressEvent(
            type = "progress",
            timestampSec = 15.5,
            progressPct = 50.0,
            currentStage = "running",
            rollingSummary = summary,
            message = "halfway"
        )
        assertEquals("progress", event.type)
        assertEquals(15.5, event.timestampSec)
        assertEquals(50.0, event.progressPct)
        assertEquals("running", event.currentStage)
        assertNotNull(event.rollingSummary)
        assertEquals("halfway", event.message)
    }

    @Test
    fun `ProgressEvent with null rollingSummary`() {
        val event = ProgressEvent("progress", 0.0, 0.0, "idle", null, null)
        assertNull(event.rollingSummary)
        assertNull(event.message)
    }

    @Test
    fun `StageProgress stores all fields`() {
        val sp = StageProgress("completed", durationSec = 30, elapsedSec = 30, estimatedRemainingSec = 0)
        assertEquals("completed", sp.status)
        assertEquals(30, sp.durationSec)
        assertEquals(30, sp.elapsedSec)
        assertEquals(0, sp.estimatedRemainingSec)
    }

    @Test
    fun `StageProgress with defaults`() {
        val sp = StageProgress("running")
        assertEquals("running", sp.status)
        assertNull(sp.durationSec)
        assertNull(sp.elapsedSec)
        assertNull(sp.estimatedRemainingSec)
    }

    @Test
    fun `ServerStatus stores all fields`() {
        val running = RunningInfo("r-1", "tulip", "test", 50.0, "running", 15, 15)
        val queued = listOf(QueuedRunInfo("q-1", "tulip", "other", 1))
        val status = ServerStatus("running", 120, 1, running, queued, "0.1.0")
        assertEquals("running", status.status)
        assertEquals(120, status.uptimeSec)
        assertEquals(1, status.queueDepth)
        assertNotNull(status.currentlyRunning)
        assertEquals(1, status.queuedRuns.size)
    }

    @Test
    fun `ServerStatus with no running run`() {
        val status = ServerStatus("idle", 60, 0, null, emptyList(), "0.1.0")
        assertNull(status.currentlyRunning)
        assertTrue(status.queuedRuns.isEmpty())
    }

    @Test
    fun `RunningInfo stores all fields`() {
        val info = RunningInfo("r-1", "tulip", "bench", 75.0, "warmup", 10, 20)
        assertEquals("r-1", info.runId)
        assertEquals("tulip", info.tool)
        assertEquals("bench", info.testName)
        assertEquals(75.0, info.progressPct)
        assertEquals("warmup", info.currentStage)
        assertEquals(10, info.elapsedSec)
        assertEquals(20, info.estimatedRemainingSec)
    }

    @Test
    fun `QueuedRunInfo stores all fields`() {
        val q = QueuedRunInfo("q-1", "tulip", "test", 1)
        assertEquals("q-1", q.runId)
        assertEquals(1, q.position)
    }

    @Test
    fun `RunProgress stores all fields`() {
        val stages = mapOf("warmup" to StageProgress("completed"), "running" to StageProgress("in_progress"))
        val rp = RunProgress("r-1", "running", 50.0, "benchmarking", stages, null)
        assertEquals("r-1", rp.runId)
        assertEquals(50.0, rp.progressPct)
        assertEquals(2, rp.stageProgress.size)
        assertEquals("completed", rp.stageProgress["warmup"]!!.status)
    }

    @Test
    fun `BaselineComparison stores all fields`() {
        val bc = BaselineComparison("regression", mapOf("p95" to 15.0), "P95 increased by 15ms")
        assertEquals("regression", bc.verdict)
        assertEquals(1, bc.deltas.size)
        assertTrue(bc.summary.contains("15ms"))
    }

    @Test
    fun `ValidationError stores all fields`() {
        val err = ValidationError("targetUrl", "must not be empty")
        assertEquals("targetUrl", err.field)
        assertEquals("must not be empty", err.message)
    }

    @Test
    fun `TestType enum has correct labels`() {
        assertEquals("http", TestType.HTTP.label)
        assertEquals("database", TestType.DATABASE.label)
        assertEquals("custom", TestType.CUSTOM.label)
        assertEquals(3, TestType.entries.size)
    }
}
