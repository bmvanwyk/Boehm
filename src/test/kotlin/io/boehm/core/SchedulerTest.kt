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

        val plan = """{"type":"http","profile":"http-get","targetUrl":"https://example.com","ratePerSec":100,"durationSec":5}"""
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

        val plan1 = """{"type":"http","profile":"http-get","targetUrl":"https://example.com","ratePerSec":100,"durationSec":1}"""
        val plan2 = """{"type":"http","profile":"http-get","targetUrl":"https://other.com","ratePerSec":100,"durationSec":1}"""
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

    @Test
    fun `scheduler executes adapter matching plan profile`() {
        val httpAdapter = TestAdapter()
        val demoAdapter = object : PerfToolAdapter {
            override val name = "tulip"
            override val profile = "demo"
            override val supportedTestTypes = listOf(TestType.HTTP)
            override val version = "0.1.0"
            override val toolVersions = listOf("0.x")
            override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()
            override fun run(testPlan: TestPlan) = RunResult(
                tool = "tulip", testName = "test",
                timestamp = java.time.Instant.now().toString(),
                runId = java.util.UUID.randomUUID().toString(),
                status = "failed", summary = null, rawOutputPath = null,
                metadata = mapOf("error" to "demo adapter ran")
            )
        }
        scheduler = Scheduler(store, listOf(httpAdapter, demoAdapter))
        scheduler.start()

        val plan = """{"type":"http","profile":"demo","targetUrl":"https://example.com"}"""
        val scenarioId = store.insertScenario("tulip", "test-demo", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")

        val run = waitForStatus(runId, "failed", 5)
        assertEquals("failed", run!!.status)
        scheduler.stop()
    }

    @Test
    fun `scheduler marks run failed when adapter throws and continues queue`() {
        val bad = TestAdapter(shouldThrow = true)
        val good = TestAdapter(profile = "demo")
        scheduler = Scheduler(store, listOf(bad, good))
        scheduler.start()

        val p1 = """{"type":"http","profile":"http-get"}"""
        val p2 = """{"type":"http","profile":"demo"}"""
        val s1 = store.insertScenario("tulip", "t1", p1)!!
        val s2 = store.insertScenario("tulip", "t2", p2)!!
        val r1 = store.insertRun(s1, "tulip")!!
        val r2 = store.insertRun(s2, "tulip")!!
        store.updateRunStatus(r1, "queued")
        store.updateRunStatus(r2, "queued")

        val run1 = waitForStatus(r1, "failed", 5)
        assertTrue(run1!!.error!!.contains("boom"), "expected boom in: ${run1.error}")
        val run2 = waitForStatus(r2, "completed", 5)
        assertEquals("completed", run2!!.status)
        scheduler.stop()
    }

    @Test
    fun `scheduler marks run failed when scenario is missing`() {
        scheduler = Scheduler(store, listOf(TestAdapter()))
        scheduler.start()
        val runId = store.insertRun("no-such-scenario", "tulip")!!
        store.updateRunStatus(runId, "queued")
        val run = waitForStatus(runId, "failed", 5)
        assertTrue(run!!.error!!.contains("Scenario not found"), "got: ${run.error}")
        scheduler.stop()
    }

    @Test
    fun `scheduler marks run failed when no adapter matches`() {
        scheduler = Scheduler(store, emptyList())
        scheduler.start()
        val plan = """{"type":"http","profile":"http-get"}"""
        val scenarioId = store.insertScenario("tulip", "t1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")
        val run = waitForStatus(runId, "failed", 5)
        assertTrue(run!!.error!!.contains("Adapter not found"), "got: ${run.error}")
        scheduler.stop()
    }

    @Test
    fun `start marks interrupted running runs as failed`() {
        val plan = """{"type":"http","profile":"http-get"}"""
        val scenarioId = store.insertScenario("tulip", "t1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "running")

        scheduler = Scheduler(store, listOf(TestAdapter()))
        scheduler.start()

        val run = waitForStatus(runId, "failed", 5)
        assertEquals("failed", run!!.status)
        assertTrue(run.error!!.contains("interrupted"), "got: ${run.error}")
        scheduler.stop()
    }

    @Test
    fun `scheduler persists adapter-reported failure error`() {
        scheduler = Scheduler(store, listOf(TestAdapter(resultStatus = "failed", resultError = "kaboom")))
        scheduler.start()
        val plan = """{"type":"http","profile":"http-get"}"""
        val scenarioId = store.insertScenario("tulip", "t1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")
        val run = waitForStatus(runId, "failed", 5)
        assertEquals("kaboom", run!!.error)
        scheduler.stop()
    }

    // ── cancellation ─────────────────────────────────────────────────────

    @Test
    fun `cancel queued run prevents execution`() {
        val slow = TestAdapter(sleepMs = 400)
        scheduler = Scheduler(store, listOf(slow))
        scheduler.start()

        val s1 = store.insertScenario("tulip", "q1", """{"type":"http","profile":"http-get"}""")!!
        val s2 = store.insertScenario("tulip", "q2", """{"type":"http","profile":"demo"}""")!!
        val r1 = store.insertRun(s1, "tulip")!!
        val r2 = store.insertRun(s2, "tulip")!!
        store.updateRunStatus(r1, "queued")
        store.updateRunStatus(r2, "queued")

        assertTrue(scheduler.requestCancel(r2))

        waitForStatus(r1, "completed", 5)
        Thread.sleep(600)  // give the scheduler a poll cycle to prove it never ran r2
        assertEquals("cancelled", store.getRun(r2)!!.status)
        scheduler.stop()
    }

    @Test
    fun `cancel running run kills work and records cancelled`() {
        val slow = TestAdapter(sleepMs = 3000)
        scheduler = Scheduler(store, listOf(slow))
        scheduler.start()

        val s1 = store.insertScenario("tulip", "r1", """{"type":"http","profile":"http-get"}""")!!
        val r1 = store.insertRun(s1, "tulip")!!
        store.updateRunStatus(r1, "queued")

        waitForStatus(r1, "running", 5)
        assertTrue(scheduler.requestCancel(r1))
        val run = waitForStatus(r1, "cancelled", 10)
        assertEquals("cancelled", run!!.status)
        scheduler.stop()
    }
}

class TestAdapter(
    override val profile: String = "http-get",
    private val resultStatus: String = "completed",
    private val resultError: String? = null,
    private val shouldThrow: Boolean = false,
    private val sleepMs: Long = 100
) : PerfToolAdapter {
    override val name = "tulip"
    override val supportedTestTypes = listOf(TestType.HTTP)
    override val version = "0.1.0"
    override val toolVersions = listOf("0.x")

    override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()

    override fun run(testPlan: TestPlan): RunResult =
        run(testPlan) { /* no process to report in mock */ }

    override fun run(testPlan: TestPlan, onProcessStart: (Process) -> Unit): RunResult {
        if (shouldThrow) throw RuntimeException("boom")
        Thread.sleep(sleepMs)
        return RunResult(
            tool = "tulip", testName = "test",
            timestamp = java.time.Instant.now().toString(),
            runId = java.util.UUID.randomUUID().toString(),
            status = resultStatus,
            summary = if (resultStatus == "completed") Summary(1, 100, 100.0, 0.0,
                Latency(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)) else null,
            rawOutputPath = null,
            metadata = if (resultError != null) mapOf("error" to resultError) else emptyMap()
        )
    }
}
