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
}
