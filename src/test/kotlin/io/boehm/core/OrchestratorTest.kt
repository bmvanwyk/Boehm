package io.boehm.core

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorTest {
    private lateinit var store: Store
    private lateinit var orchestrator: Orchestrator

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        orchestrator = Orchestrator(store)
        val adapter = object : PerfToolAdapter {
            override val name = "tulip"
            override val supportedTestTypes = listOf(TestType.HTTP)
            override val version = "0.1.0"
            override val toolVersions = listOf("0.x")
            override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()
            override fun run(testPlan: TestPlan) = throw UnsupportedOperationException()
        }
        orchestrator.registerAdapter(adapter)
    }

    @Test
    fun `submitRun with known adapter returns runId`() {
        val plan = TestPlan("http", "https://example.com", 100, 10)
        val runId = orchestrator.submitRun("tulip", "test-1", plan)
        assertNotNull(runId)
    }

    @Test
    fun `submitRun with unknown adapter returns null`() {
        val plan = TestPlan("http", "https://example.com", 100, 10)
        val runId = orchestrator.submitRun("nonexistent", "test-1", plan)
        assertNull(runId)
    }
}
