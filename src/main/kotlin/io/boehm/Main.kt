package io.boehm

import io.boehm.adapters.PerfToolAdapter
import io.boehm.adapters.jmeter.JMeterParser
import io.boehm.adapters.k6.K6Parser
import io.boehm.adapters.tulip.TulipParser
import io.boehm.auth.AuthHandler
import io.boehm.catalog.CatalogAdapter
import io.boehm.catalog.CatalogLoader
import io.boehm.core.McpHandler
import io.boehm.core.Store
import io.boehm.model.RunResult
import java.io.File

fun main(args: Array<String>) {
    val baseDir = System.getProperty("user.dir")
    val catalogPath = System.getenv("BOEHM_CATALOG_PATH") ?: "$baseDir/catalog.yaml"
    val dbPath = System.getenv("BOEHM_DB_PATH") ?: "${System.getProperty("user.home")}/.boehm/boehm.db"
    val token = args.find { it.startsWith("--token=") }?.substringAfter("--token=")
        ?: System.getenv("BOEHM_TOKEN")
        ?: error("No token provided. Use --token=<token> or set BOEHM_TOKEN env var.")

    File(dbPath).parentFile.mkdirs()

    val authHandler = AuthHandler()
    authHandler.createToken(token)

    val store = Store(dbPath)

    // Build parser registry
    val parsers: Map<String, (String) -> RunResult> = mapOf(
        "tulip-results" to { raw -> TulipParser.parse(raw) },
        "jmeter-csv" to { raw -> JMeterParser.parse(raw) },
        "k6-jsonl" to { raw -> K6Parser.parse(raw) }
    )

    // Load catalog and create adapters
    val catalog = CatalogLoader(catalogPath).load()
    val adapters: List<PerfToolAdapter> = catalog.tools.flatMap { (name, toolDef) ->
        toolDef.profiles.keys.map { profileName ->
            CatalogAdapter(toolDef, profileName, baseDir, parsers)
        }
    }

    // Pre-register adapters in store so list_adapters works immediately
    adapters.forEach { adapter ->
        val typesJson = """["http"]"""
        store.insertAdapter(adapter.name, typesJson, adapter.version,
            """["0.x"]""")
    }

    val mcpHandler = McpHandler(authHandler, store, adapters)

    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        val response = mcpHandler.handle(line)
        println(response)
        System.out.flush()
    }
}
