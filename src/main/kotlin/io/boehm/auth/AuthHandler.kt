package io.boehm.auth

import java.security.MessageDigest
import java.util.Base64

class AuthHandler {
    private val tokens = mutableSetOf<String>()

    fun createToken(token: String): String {
        tokens.add(hashToken(token))
        return token
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return tokens.contains(hashToken(token))
    }

    fun loadFromConfig(tokens: List<String>) {
        this.tokens.addAll(tokens.map { hashToken(it) })
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(token.toByteArray()))
    }
}
