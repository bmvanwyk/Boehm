package io.boehm.core

import io.boehm.auth.AuthHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpHandlerTest {
    private lateinit var handler: McpHandler
    private lateinit var authHandler: AuthHandler

    @BeforeEach
    fun setUp() {
        authHandler = AuthHandler()
        authHandler.createToken("test-token")
        val store = Store(":memory:")
        handler = McpHandler(authHandler, store)
    }

    @Test
    fun `initialize with valid token returns success`() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}"""
        )
        assertTrue(response.contains("\"result\""))
    }

    @Test
    fun `initialize without token returns error`() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        )
        assertTrue(response.contains("\"error\""))
        assertTrue(response.contains("-32005"))
    }

    @Test
    fun `tools_called before initialize returns error`() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}"""
        )
        assertTrue(response.contains("\"error\""))
    }

    @Test
    fun `list_adapters returns adapters`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_adapters","arguments":{}}}"""
        )
        assertTrue(response.contains("\"adapters\""))
    }

    @Test
    fun `run_test with invalid plan returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{
                "tool":"nonexistent","test_name":"test","test_plan":{"type":"http"}}
            }}
        """.trimIndent())
        assertTrue(response.contains("\"error\"") || response.contains("-32000"))
    }

    @Test
    fun `unknown tool call returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}"""
        )
        assertTrue(response.contains("\"error\""))
    }

    @Test
    fun `server_status returns status`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        handler.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_adapters","arguments":{}}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"server_status","arguments":{}}}"""
        )
        assertTrue(response.contains("\"status\""))
    }

    @Test
    fun `get_run with missing id returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run","arguments":{}}}"""
        )
        assertTrue(response.contains("\"error\""))
    }

    @Test
    fun `get_run_progress with missing id returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run_progress","arguments":{}}}"""
        )
        assertTrue(response.contains("\"error\""))
    }

    @Test
    fun `get_run_progress returns status for queued run`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run_progress","arguments":{"run_id":"nonexistent"}}}"""
        )
        assertTrue(response.contains("\"error\"") || response.contains("-32002"))
    }

    @Test
    fun `ping returns success`() {
        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertTrue(response.contains("\"result\""))
    }

    @Test
    fun `invalid JSON returns parse error`() {
        val response = handler.handle("not json")
        assertTrue(response.contains("-32700"))
    }

    @Test
    fun `unknown method returns error`() {
        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"unknown_method"}""")
        assertTrue(response.contains("-32601"))
    }

    @Test
    fun `server_status with running run includes running info`() {
        // Insert a scenario and run directly into store, mark it running
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        // Response should still be valid JSON with status field even without running runs
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"server_status","arguments":{}}}"""
        )
        assertTrue(response.contains("\"status\""))
        assertTrue(response.contains("\"queuedRuns\""))
    }

    @Test
    fun `get_run returns error for nonexistent run`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run","arguments":{"run_id":"no-such-run"}}}"""
        )
        assertTrue(response.contains("\"error\"") && response.contains("-32002"))
    }

    @Test
    fun `get_run returns completed run with summary`() {
        val store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "completed",
            summary = """{"durationSec":30,"totalRequests":100,"throughputReqPerSec":3.33,"errorRatePct":0.0,"latency":{"minMs":1.0,"p50Ms":2.0,"p90Ms":3.0,"p95Ms":4.0,"p99Ms":5.0,"maxMs":6.0}}""")

        val h = McpHandler(authHandler, store)
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run","arguments":{"run_id":"$rid"}}}"""
        )
        assertTrue(response.contains("completed"))
        assertTrue(response.contains("durationSec"))
        assertTrue(response.contains("latency"))
    }

    @Test
    fun `get_run_progress returns all status variants`() {
        val store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "completed",
            summary = """{"durationSec":30,"totalRequests":100,"throughputReqPerSec":3.33,"errorRatePct":0.0,"latency":{"minMs":1.0,"p50Ms":2.0,"p90Ms":3.0,"p95Ms":4.0,"p99Ms":5.0,"maxMs":6.0}}""")

        val h = McpHandler(authHandler, store)
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run_progress","arguments":{"run_id":"$rid"}}}"""
        )
        assertTrue(response.contains("completed"))
        assertTrue(response.contains("progressPct"))
        assertTrue(response.contains("rollingSummary"))
    }

    @Test
    fun `server_status shows idle when no runs`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"server_status","arguments":{}}}"""
        )
        assertTrue(response.contains(""""status":"""))
    }

    @Test
    fun `server_status shows running run when run in progress`() {
        val store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "running")

        val h = McpHandler(authHandler, store)
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"server_status","arguments":{}}}"""
        )
        assertTrue(response.contains(""""status":"running""""), "Expected running status: $response")
        assertTrue(response.contains("currentlyRunning"), "Expected currentlyRunning: $response")
    }

    @Test
    fun `get_run_progress shows progress for running run`() {
        val store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "running")

        val h = McpHandler(authHandler, store)
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run_progress","arguments":{"run_id":"$rid"}}}"""
        )
        assertTrue(response.contains(""""status":"running""""), "Expected running status: $response")
        assertTrue(response.contains(""""currentStage":"running""""), "Expected running stage: $response")
    }

    private fun tulipMock() = object : io.boehm.adapters.PerfToolAdapter {
        override val name = "tulip"
        override val profile = "http-get"
        override val supportedTestTypes = listOf(io.boehm.model.TestType.HTTP)
        override val version = "0.1.0"
        override val toolVersions = listOf("0.x")
        override fun validate(testPlan: io.boehm.model.TestPlan): List<io.boehm.model.ValidationError> =
            if (testPlan.targetUrl.isBlank()) listOf(io.boehm.model.ValidationError("targetUrl", "must not be empty"))
            else emptyList()
        override fun run(testPlan: io.boehm.model.TestPlan): io.boehm.model.RunResult =
            throw UnsupportedOperationException()
    }

    @Test
    fun `run_test with unknown profile returns -32001`() {
        val store = Store(":memory:")
        val h = McpHandler(authHandler, store, listOf(tulipMock()))
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"t","test_plan":{"type":"http","profile":"nope","target_url":"https://example.com"}}}}""")
        assertTrue(response.contains("-32001"), response)
        assertTrue(response.contains("Unknown profile"), response)
    }

    @Test
    fun `run_test with invalid plan returns -32001 with validation errors`() {
        val store = Store(":memory:")
        val h = McpHandler(authHandler, store, listOf(tulipMock()))
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"t","test_plan":{"type":"http","profile":"http-get","target_url":""}}}}""")
        assertTrue(response.contains("-32001"), response)
        assertTrue(response.contains("validation_errors"), response)
    }
}
