package io.boehm.core

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SchedulerTest {
    private lateinit var store: Store
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    }

    @Test
    fun `scheduler runs a queued run and completes it`() {
        val adapter = TestAdapter()
        scheduler = Scheduler(store, listOf(adapter))
        scheduler.start()

        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":5}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")

        val run = waitForStatus(runId, "completed", 5)
        assertEquals("completed", run!!.status)
        scheduler.stop()
    }

    @Test
    fun `scheduler serializes two runs`() {
        val adapter = TestAdapter()
        scheduler = Scheduler(store, listOf(adapter))
        scheduler.start()

        val plan1 = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":1}"""
        val plan2 = """{"type":"http","targetUrl":"https://other.com","ratePerSec":100,"durationSec":1}"""
        val s1 = store.insertScenario("tulip", "test-1", plan1)!!
        val s2 = store.insertScenario("tulip", "test-2", plan2)!!
        val r1 = store.insertRun(s1, "tulip")!!
        val r2 = store.insertRun(s2, "tulip")!!

        store.updateRunStatus(r1, "queued")
        store.updateRunStatus(r2, "queued")

        val run1 = waitForStatus(r1, "completed", 5)
        val run2 = waitForStatus(r2, "completed", 5)
        assertEquals("completed", run1!!.status)
        assertEquals("completed", run2!!.status)
        assertTrue(run1.completedAt!! < run2.completedAt!!)
        scheduler.stop()
    }

    private fun waitForStatus(runId: String, expectedStatus: String, timeoutSec: Long): RunRow? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            val run = store.getRun(runId)
            if (run != null && run.status == expectedStatus) return run
            Thread.sleep(50)
        }
        return store.getRun(runId)
    }
}

class TestAdapter() : PerfToolAdapter {
    override val name = "tulip"
    override val supportedTestTypes = listOf(TestType.HTTP)
    override val version = "0.1.0"
    override val toolVersions = listOf("0.x")

    override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()

    override fun run(testPlan: TestPlan): RunResult {
        Thread.sleep(100)
        return RunResult(
            tool = "tulip", testName = "test",
            timestamp = java.time.Instant.now().toString(),
            runId = java.util.UUID.randomUUID().toString(),
            status = "completed",
            summary = Summary(1, 100, 100.0, 0.0,
                Latency(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)),
            rawOutputPath = null,
            metadata = emptyMap()
        )
    }
}
