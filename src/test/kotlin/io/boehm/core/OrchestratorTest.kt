package io.boehm.core

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorTest {
    private lateinit var store: Store
    private lateinit var orchestrator: Orchestrator

    private fun mockAdapter(
        toolName: String,
        profileName: String,
        failValidation: Boolean = false,
        validatedBy: MutableList<String>? = null
    ): PerfToolAdapter {
        return object : PerfToolAdapter {
            override val name = toolName
            override val profile = profileName
            override val supportedTestTypes = listOf(TestType.HTTP)
            override val version = "0.1.0"
            override val toolVersions = listOf("0.x")
            override fun validate(testPlan: TestPlan): List<ValidationError> {
                validatedBy?.add(profileName)
                return if (failValidation) listOf(ValidationError("targetUrl", "must not be empty"))
                else emptyList()
            }
            override fun run(testPlan: TestPlan): RunResult = throw UnsupportedOperationException()
        }
    }

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        orchestrator = Orchestrator(store)
    }

    @Test
    fun `submitRun with known adapter and profile returns Queued`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get"))
        val plan = TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.Queued)
    }

    @Test
    fun `submitRun with unknown adapter returns UnknownAdapter`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get"))
        val plan = TestPlan(type = "http", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("nonexistent", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.UnknownAdapter)
    }

    @Test
    fun `submitRun with unknown profile returns UnknownProfile`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get"))
        val plan = TestPlan(type = "http", profile = "no-such-profile", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.UnknownProfile)
    }

    @Test
    fun `submitRun with invalid plan returns Invalid with errors`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get", failValidation = true))
        val plan = TestPlan(type = "http", profile = "http-get", targetUrl = "")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.Invalid)
        assertEquals("targetUrl", (result as Orchestrator.SubmitResult.Invalid).errors[0].field)
    }

    @Test
    fun `submitRun dispatches to adapter matching plan profile`() {
        val validatedBy = mutableListOf<String>()
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get", validatedBy = validatedBy))
        orchestrator.registerAdapter(mockAdapter("tulip", "demo", validatedBy = validatedBy))
        val plan = TestPlan(type = "http", profile = "demo", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.Queued)
        assertEquals(listOf("demo"), validatedBy)
    }
}
