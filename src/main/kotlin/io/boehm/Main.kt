package io.boehm

import io.boehm.adapters.PerfToolAdapter
import io.boehm.adapters.jmeter.JMeterParser
import io.boehm.adapters.k6.K6Parser
import io.boehm.adapters.tulip.TulipParser
import io.boehm.catalog.CatalogLoader
import io.boehm.catalog.buildAdapters
import io.boehm.core.BoehmToolHandlers
import io.boehm.core.Orchestrator
import io.boehm.core.Store
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val baseDir = System.getProperty("user.dir")
    val catalogPath = System.getenv("BOEHM_CATALOG_PATH") ?: "$baseDir/catalog.yaml"
    val dbPath = System.getenv("BOEHM_DB_PATH") ?: "${System.getProperty("user.home")}/.boehm/boehm.db"
    val token = args.find { it.startsWith("--token=") }?.substringAfter("--token=")
        ?: System.getenv("BOEHM_TOKEN")

    if (token.isNullOrBlank()) {
        System.err.println("No token provided. Use --token=<token> or set BOEHM_TOKEN env var.")
        exitProcess(1)
    }
    // Option A (per SPEC): token is validated at startup. stdio MCP servers rely
    // on the spawning process for transport security, so no per-request auth is needed.
    @Suppress("UNUSED_VARIABLE")
    val expectedToken = token

    File(dbPath).parentFile.mkdirs()
    val store = Store(dbPath)

    // Build parser registry
    val parsers: Map<String, (String) -> io.boehm.model.RunResult> = mapOf(
        "tulip-results" to { raw -> TulipParser.parse(raw) },
        "jmeter-csv" to { raw -> JMeterParser.parse(raw) },
        "k6-jsonl" to { raw -> K6Parser.parse(raw) }
    )

    // Load catalog and create adapters (only profiles whose output schema has a parser)
    val catalog = CatalogLoader(catalogPath).load()
    val adapters = buildAdapters(catalog, baseDir, parsers)

    // Pre-register adapters in store so list_adapters works immediately
    adapters.forEach { adapter ->
        val typesJson = """["http"]"""
        store.insertAdapter(adapter.name, typesJson, adapter.version, """["0.x"]""")
    }

    val orchestrator = Orchestrator(store)
    adapters.forEach { orchestrator.registerAdapter(it) }

    val handlers = BoehmToolHandlers(store, orchestrator)

    val server = buildServer(handlers)

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}

/**
 * Build the MCP [Server] and register Boehm's five tools. Kept separate from
 * [main] so tests can construct a server (or just the handlers) without stdio.
 */
fun buildServer(handlers: BoehmToolHandlers): Server {
    val server = Server(
        serverInfo = Implementation(name = "boehm", version = BoehmToolHandlers.SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    server.addTool(
        name = "list_adapters",
        description = "List all registered performance test adapters and their supported test types",
        inputSchema = ToolSchema()
    ) { request: CallToolRequest -> handlers.listAdapters(request) }

    server.addTool(
        name = "run_test",
        description = "Queue a performance test run",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("tool") { put("type", "string") }
                putJsonObject("test_name") { put("type", "string") }
                putJsonObject("test_plan") {
                    put("type", "object")
                    put("additionalProperties", true)
                }
            },
            required = listOf("tool", "test_name", "test_plan")
        )
    ) { request: CallToolRequest -> handlers.runTest(request) }

    server.addTool(
        name = "get_run",
        description = "Get the full result of a performance test run by run id",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("run_id") { put("type", "string") }
            },
            required = listOf("run_id")
        )
    ) { request: CallToolRequest -> handlers.getRun(request) }

    server.addTool(
        name = "server_status",
        description = "Get server status: queue depth, currently running run, uptime, registered adapters",
        inputSchema = ToolSchema()
    ) { request: CallToolRequest -> handlers.serverStatus(request) }

    server.addTool(
        name = "get_run_progress",
        description = "Get progress information for a performance test run by run id",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("run_id") { put("type", "string") }
            },
            required = listOf("run_id")
        )
    ) { request: CallToolRequest -> handlers.getRunProgress(request) }

    return server
}
