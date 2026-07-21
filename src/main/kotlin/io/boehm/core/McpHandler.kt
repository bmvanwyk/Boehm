package io.boehm.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.adapters.PerfToolAdapter
import io.boehm.auth.AuthHandler
import io.boehm.model.TestPlan

class McpHandler(
    private val authHandler: AuthHandler,
    private val store: Store,
    private val adapters: List<PerfToolAdapter> = emptyList(),
    private val serverVersion: String = "0.1.0",
    private val startupTime: Long = System.currentTimeMillis()
) {
    private val gson = Gson()
    private var initialized = false
    private var orchestrator: Orchestrator? = null

    fun handle(request: String): String {
        return try {
            val json = JsonParser.parseString(request).asJsonObject
            val id = json.get("id")
            val method = json.get("method")?.asString ?: return errorResponse(id, -32600, "Invalid request")

            when (method) {
                "initialize" -> handleInitialize(json, id)
                "tools/call" -> handleToolCall(json, id)
                "ping" -> gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "result" to emptyMap<String, Any>()))
                else -> errorResponse(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"""
        }
    }

    private fun handleInitialize(json: JsonObject, id: Any): String {
        val params = json.getAsJsonObject("params") ?: return errorResponse(id, -32602, "Invalid params")
        val token = params.get("auth_token")?.asString

        if (!authHandler.validateToken(token)) {
            return errorResponse(id, -32005, "Authentication failed")
        }

        initialized = true
        if (orchestrator == null) {
            val orch = Orchestrator(store)
            adapters.forEach { orch.registerAdapter(it) }
            orchestrator = orch
        }

        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "protocolVersion" to "2024-11-05",
                "serverInfo" to mapOf("name" to "boehm", "version" to serverVersion)
            )
        ))
    }

    private fun handleToolCall(json: JsonObject, id: Any): String {
        if (!initialized) {
            return errorResponse(id, -32006, "Not initialized. Call initialize first.")
        }

        val params = json.getAsJsonObject("params") ?: return errorResponse(id, -32602, "Invalid params")
        val toolName = params.get("name")?.asString ?: return errorResponse(id, -32602, "Missing tool name")
        val args = params.getAsJsonObject("arguments") ?: JsonObject()

        return when (toolName) {
            "list_adapters" -> handleListAdapters(id)
            "run_test" -> handleRunTest(args, id)
            "get_run" -> handleGetRun(args, id)
            "server_status" -> handleServerStatus(id)
            "get_run_progress" -> handleGetRunProgress(args, id)
            else -> errorResponse(id, -32602, "Unknown tool: $toolName")
        }
    }

    private fun handleListAdapters(id: Any): String {
        val adapters = store.listAdapters().map {
            mapOf("name" to it.name, "supported_types" to it.supportedTypes, "version" to it.version)
        }
        return gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "result" to mapOf("adapters" to adapters)))
    }

    private fun handleRunTest(args: JsonObject, id: Any): String {
        val tool = args.get("tool")?.asString ?: return errorResponse(id, -32001, "Missing tool")
        val testName = args.get("test_name")?.asString ?: return errorResponse(id, -32001, "Missing test_name")
        val testPlanJson = args.getAsJsonObject("test_plan") ?: return errorResponse(id, -32001, "Missing test_plan")

        val knownFields = setOf("type", "profile", "target_url", "rate_per_sec", "duration_sec", "warmup_sec", "timeout_sec")
        val parameters = testPlanJson.entrySet()
            .filterNot { knownFields.contains(it.key) }
            .associate { it.key to (it.value?.asString ?: "") }

        val plan = TestPlan(
            type = testPlanJson.get("type")?.asString ?: "http",
            profile = testPlanJson.get("profile")?.asString ?: "http-get",
            targetUrl = testPlanJson.get("target_url")?.asString ?: "",
            ratePerSec = testPlanJson.get("rate_per_sec")?.asInt ?: 50,
            durationSec = testPlanJson.get("duration_sec")?.asInt ?: 30,
            warmupSec = testPlanJson.get("warmup_sec")?.asInt ?: 5,
            timeoutSec = testPlanJson.get("timeout_sec")?.asInt ?: 60,
            parameters = parameters
        )

        val orchestrator = orchestrator!!
        val runId = orchestrator.submitRun(tool, testName, plan)
        if (runId == null) {
            return errorResponse(id, -32000, "Adapter not found: $tool",
                mapOf("available_adapters" to store.listAdapters().map { it.name }))
        }

        val run = store.getRun(runId)
        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "runId" to runId,
                "tool" to tool,
                "testName" to testName,
                "status" to (run?.status ?: "queued"),
                "summary" to null
            )
        ))
    }

    private fun handleGetRun(args: JsonObject, id: Any): String {
        val runId = args.get("run_id")?.asString ?: return errorResponse(id, -32001, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResponse(id, -32002, "Run not found: $runId")

        val summary = if (run.summary != null) parseJson(run.summary) else null
        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "runId" to run.id,
                "tool" to run.tool,
                "testName" to "",
                "timestamp" to run.createdAt,
                "status" to run.status,
                "summary" to summary,
                "rawOutputPath" to run.rawOutputPath,
                "metadata" to (if (run.metadata != null) parseJson(run.metadata) else emptyMap<String, Any>())
            )
        ))
    }

    private fun handleServerStatus(id: Any): String {
        val runningRun = store.getPendingOrRunningRun()
        val queuedRuns = store.getQueuedRuns()

        val currentlyRunning = if (runningRun != null && runningRun.status == "running") {
            mapOf(
                "runId" to runningRun.id,
                "tool" to runningRun.tool,
                "testName" to "",
                "progressPct" to 0.0,
                "currentStage" to "running",
                "elapsedSec" to 0,
                "estimatedRemainingSec" to 0
            )
        } else null

        val queued = queuedRuns.mapIndexed { idx, r ->
            mapOf("runId" to r.id, "tool" to r.tool, "testName" to "", "position" to (idx + 1))
        }

        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "status" to if (runningRun != null) "running" else "idle",
                "uptimeSec" to ((System.currentTimeMillis() - startupTime) / 1000),
                "queueDepth" to queued.size,
                "currentlyRunning" to currentlyRunning,
                "queuedRuns" to queued,
                "serverVersion" to serverVersion,
                "adapters" to store.listAdapters().map { it.name }
            )
        ))
    }

    private fun handleGetRunProgress(args: JsonObject, id: Any): String {
        val runId = args.get("run_id")?.asString ?: return errorResponse(id, -32001, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResponse(id, -32002, "Run not found: $runId")

        val summary = if (run.summary != null) parseJson(run.summary) else null
        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "runId" to run.id,
                "status" to run.status,
                "progressPct" to (if (run.status == "completed") 100.0 else if (run.status == "running") 50.0 else 0.0),
                "currentStage" to (if (run.status == "running") "running" else run.status),
                "stageProgress" to mapOf(
                    "warmup" to mapOf("status" to "completed"),
                    "running" to mapOf("status" to (if (run.status == "running") "in_progress" else "completed"))
                ),
                "rollingSummary" to summary
            )
        ))
    }

    private fun parseJson(json: String): Map<String, Any> {
        return try {
            gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun errorResponse(id: Any?, code: Int, message: String, data: Map<String, Any>? = null): String {
        val error = mutableMapOf<String, Any>("code" to code, "message" to message)
        if (data != null) error["data"] = data
        val resp = mutableMapOf<String, Any>("jsonrpc" to "2.0", "error" to error)
        if (id != null) resp["id"] = id
        return gson.toJson(resp)
    }
}
