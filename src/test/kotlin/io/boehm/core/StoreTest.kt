package io.boehm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class StoreTest {
    private lateinit var store: Store
    private val dbPath = "/tmp/boehm-test-${UUID.randomUUID()}.db"

    @BeforeEach
    fun setUp() {
        store = Store(dbPath)
    }

    @AfterEach
    fun tearDown() {
        File(dbPath).delete()
    }

    @Test
    fun `schema initializes and tables exist`() {
        assertTrue(store.listAdapters().isEmpty())
    }

    @Test
    fun `insert and retrieve adapter`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val adapters = store.listAdapters()
        assertEquals(1, adapters.size)
        assertEquals("tulip", adapters[0].name)
    }

    @Test
    fun `insert and retrieve scenario`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        assertNotNull(scenarioId)

        val scenario = store.getScenario("tulip", "test-1")
        assertNotNull(scenario)
        assertEquals(scenarioId, scenario!!.id)
    }

    @Test
    fun `insert and retrieve run`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        val runId = store.insertRun(scenarioId!!, "tulip")
        assertNotNull(runId)

        val run = store.getRun(runId!!)
        assertNotNull(run)
        assertEquals("pending", run!!.status)
    }

    @Test
    fun `update run status`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        val runId = store.insertRun(scenarioId!!, "tulip")

        store.updateRunStatus(runId!!, "completed", """{"durationSec":30}""")
        val run = store.getRun(runId)
        assertEquals("completed", run!!.status)
        assertNotNull(run.completedAt)
    }

    @Test
    fun `list runs by scenario`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        store.insertRun(scenarioId!!, "tulip")
        store.insertRun(scenarioId, "tulip")

        val runs = store.listRuns(scenarioId)
        assertEquals(2, runs.size)
    }

    @Test
    fun `scenario and run timestamps are ISO-8601 with Z suffix`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "ts-test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!

        val scenario = store.getScenarioById(sid)!!
        val run = store.getRun(rid)!!
        assertTrue(scenario.createdAt.endsWith("Z") && scenario.createdAt.contains('T'),
            "scenario created_at not ISO-8601: ${scenario.createdAt}")
        assertTrue(run.createdAt.endsWith("Z") && run.createdAt.contains('T'),
            "run created_at not ISO-8601: ${run.createdAt}")
    }

    @Test
    fun `runs created later sort before earlier runs in listRuns DESC order`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "sort-test", """{"type":"http"}""")!!
        val r1 = store.insertRun(sid, "tulip")!!
        Thread.sleep(50)
        val r2 = store.insertRun(sid, "tulip")!!
        val runs = store.listRuns(sid)  // ORDER BY created_at DESC
        assertEquals(r2, runs[0].id)
        assertEquals(r1, runs[1].id)
    }

    @Test
    fun `getPendingOrRunningRun returns null when empty`() {
        assertNull(store.getPendingOrRunningRun())
    }

    @Test
    fun `getPendingOrRunningRun returns queued run in order`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val sid = store.insertScenario("tulip", "test-1", plan)!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "queued")

        val run = store.getPendingOrRunningRun()
        assertNotNull(run)
        assertEquals(rid, run!!.id)
        assertTrue(run.status in listOf("pending", "queued", "running"))
    }

    @Test
    fun `getQueuedRuns returns empty when no queued runs`() {
        assertTrue(store.getQueuedRuns().isEmpty())
    }

    @Test
    fun `insertScenario updates stored plan when resubmitted with different config`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val p1 = """{"type":"http","ratePerSec":100}"""
        val p2 = """{"type":"http","ratePerSec":500}"""
        val id1 = store.insertScenario("tulip", "test-1", p1)!!
        val id2 = store.insertScenario("tulip", "test-1", p2)!!

        // Same scenario identity (name-keyed), but the stored plan must reflect the latest submission
        assertEquals(id1, id2)
        assertTrue(
            store.getScenarioById(id1)!!.testPlan.contains("500"),
            "expected updated plan persisted, got: ${store.getScenarioById(id1)!!.testPlan}"
        )
    }

    @Test
    fun `getScenarioById returns null for missing id`() {
        assertNull(store.getScenarioById("nonexistent"))
    }

    @Test
    fun `setBaseline replaces previous baseline for scenario`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "b-test", """{"type":"http"}""")!!
        val r1 = store.insertRun(sid, "tulip")!!
        val r2 = store.insertRun(sid, "tulip")!!

        store.setBaseline(sid, r1)
        assertEquals(r1, store.getBaselineRunId(sid))
        store.setBaseline(sid, r2)
        assertEquals(r2, store.getBaselineRunId(sid))
    }

    @Test
    fun `getBaselineRunId returns null when unset`() {
        assertNull(store.getBaselineRunId("no-such-scenario"))
    }

    @Test
    fun `listRecentRuns filters by tool testName and limits`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        store.insertAdapter("k6", """["http"]""", "0.1.0", """["0.x"]""")
        val s1 = store.insertScenario("tulip", "alpha", """{"type":"http"}""")!!
        val s2 = store.insertScenario("k6", "beta", """{"type":"http"}""")!!
        store.updateRunStatus(store.insertRun(s1, "tulip")!!, "completed")
        store.updateRunStatus(store.insertRun(s2, "k6")!!, "completed")
        store.updateRunStatus(store.insertRun(s2, "k6")!!, "completed")

        assertEquals(3, store.listRecentRuns(null, null, 10).size)
        assertEquals(1, store.listRecentRuns("tulip", null, 10).size)
        assertEquals(2, store.listRecentRuns(null, "beta", 10).size)
        assertEquals(1, store.listRecentRuns(null, null, 1).size)
    }

    @Test
    fun `cancelQueuedRun cancels only non-started runs`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "c-test", """{"type":"http"}""")!!
        val queued = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(queued, "queued")
        val running = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(running, "running")

        assertTrue(store.cancelQueuedRun(queued))
        assertEquals("cancelled", store.getRun(queued)!!.status)
        assertFalse(store.cancelQueuedRun(running))   // already started — scheduler handles it
        assertFalse(store.cancelQueuedRun("no-such-run"))
    }

    @Test
    fun `close does not throw`() {
        store.close()
        assertDoesNotThrow { store.close() }
    }

    @Test
    fun `failInterruptedRuns marks running runs as failed`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val scenarioId = store.insertScenario("tulip", "t1", """{"type":"http"}""")!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "running")

        val count = store.failInterruptedRuns()

        assertEquals(1, count)
        val run = store.getRun(runId)!!
        assertEquals("failed", run.status)
        assertTrue(run.error!!.contains("interrupted"))
        assertNotNull(run.completedAt)
    }

    @Test
    fun `schema_version is updated on startup`() {
        assertEquals(1, store.getSchemaVersion())
        store.setSchemaVersion(2)
        assertEquals(2, store.getSchemaVersion())
    }
}
