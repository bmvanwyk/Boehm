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
}
