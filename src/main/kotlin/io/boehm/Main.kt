package io.boehm

import io.boehm.auth.AuthHandler
import io.boehm.core.McpHandler
import io.boehm.core.Store
import java.io.File

fun main(args: Array<String>) {
    val dbPath = System.getenv("BOEHM_DB_PATH") ?: "${System.getProperty("user.home")}/.boehm/boehm.db"
    val token = args.find { it.startsWith("--token=") }?.substringAfter("--token=")
        ?: System.getenv("BOEHM_TOKEN")
        ?: error("No token provided. Use --token=<token> or set BOEHM_TOKEN env var.")

    File(dbPath).parentFile.mkdirs()

    val authHandler = AuthHandler()
    authHandler.createToken(token)

    val store = Store(dbPath)
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")

    val mcpHandler = McpHandler(authHandler, store)

    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        val response = mcpHandler.handle(line)
        println(response)
        System.out.flush()
    }
}
