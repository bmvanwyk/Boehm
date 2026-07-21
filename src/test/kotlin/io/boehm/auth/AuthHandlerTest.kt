package io.boehm.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthHandlerTest {
    @Test
    fun `valid token returns true`() {
        val handler = AuthHandler()
        handler.createToken("test-token")
        assertTrue(handler.validateToken("test-token"))
    }

    @Test
    fun `invalid token returns false`() {
        val handler = AuthHandler()
        handler.createToken("real-token")
        assertFalse(handler.validateToken("fake-token"))
    }

    @Test
    fun `missing token returns false`() {
        val handler = AuthHandler()
        assertFalse(handler.validateToken(null))
    }

    @Test
    fun `empty token returns false`() {
        val handler = AuthHandler()
        assertFalse(handler.validateToken(""))
    }

    @Test
    fun `multiple tokens can be created and validated`() {
        val handler = AuthHandler()
        handler.createToken("token-1")
        handler.createToken("token-2")
        assertTrue(handler.validateToken("token-1"))
        assertTrue(handler.validateToken("token-2"))
    }

    @Test
    fun `loadFromConfig adds multiple tokens`() {
        val handler = AuthHandler()
        handler.loadFromConfig(listOf("key-1", "key-2"))
        assertTrue(handler.validateToken("key-1"))
        assertTrue(handler.validateToken("key-2"))
        assertFalse(handler.validateToken("key-3"))
    }

    @Test
    fun `createToken returns the original token`() {
        val handler = AuthHandler()
        val result = handler.createToken("my-token")
        assertEquals("my-token", result)
    }
}
