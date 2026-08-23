package io.boehm.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.boehm.model.TestPlan
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Business logic for Boehm's MCP tools. Each method mirrors one tool exposed by
 * the MCP [io.modelcontextprotocol.kotlin.sdk.server.Server] and returns a
 * [CallToolResult]. The methods are plain suspended functions (not tied to the
 * MCP transport) so they can be unit-tested directly.
 */
class BoehmToolHandlers(
    private val store: Store,
    private val orchestrator: Orchestrator,
    private val startupTime: Long = System.currentTimeMillis()
) {
    private val gson = Gson()

    private val knownPlanFields = setOf(
        "type", "profile", "target_url", "rate_per_sec", "duration_sec", "warmup_sec", "timeout_sec"
    )

    // ── list_adapters ────────────────────────────────────────────────────

    suspend fun listAdapters(@Suppress("UNUSED_PARAMETER") request: CallToolRequest): CallToolResult {
        val adapters = store.listAdapters().map {
            mapOf("name" to it.name, "supported_types" to it.supportedTypes, "version" to it.version)
        }
        return textResult(mapOf("adapters" to adapters))
    }

    // ── run_test ─────────────────────────────────────────────────────────

    suspend fun runTest(request: CallToolRequest): CallToolResult {
        val tool = argString(request, "tool") ?: return errorResult(-32001, "Missing tool")
        val testName = argString(request, "test_name") ?: return errorResult(-32001, "Missing test_name")
        val planJson = argJson(request, "test_plan") ?: return errorResult(-32001, "Missing test_plan")

        val parameters = planJson.entrySet()
            .filterNot { knownPlanFields.contains(it.key) }
            .associate { it.key to (it.value?.toString() ?: "") }

        val plan = TestPlan(
            type = planJson.optString("type") ?: "http",
            profile = planJson.optString("profile") ?: "http-get",
            targetUrl = planJson.optString("target_url") ?: "",
            ratePerSec = planJson.optInt("rate_per_sec") ?: 50,
            durationSec = planJson.optInt("duration_sec") ?: 30,
            warmupSec = planJson.optInt("warmup_sec") ?: 5,
            timeoutSec = planJson.optInt("timeout_sec") ?: 60,
            parameters = parameters
        )

        return when (val result = orchestrator.submitRun(tool, testName, plan)) {
            is Orchestrator.SubmitResult.Queued -> {
                val run = store.getRun(result.runId)
                textResult(mapOf(
                    "runId" to result.runId,
                    "tool" to tool,
                    "testName" to testName,
                    "status" to (run?.status ?: "queued"),
                    "summary" to null
                ))
            }
            is Orchestrator.SubmitResult.UnknownAdapter ->
                errorResult(-32000, "Adapter not found: ${result.tool}",
                    mapOf("available_adapters" to store.listAdapters().map { it.name }))
            is Orchestrator.SubmitResult.UnknownProfile ->
                errorResult(-32001, "Unknown profile '${result.profile}' for tool '${result.tool}'")
            is Orchestrator.SubmitResult.Invalid ->
                errorResult(-32001, "Invalid test plan",
                    mapOf("validation_errors" to result.errors.map { "${it.field}: ${it.message}" }))
        }
    }

    // ── get_run ──────────────────────────────────────────────────────────

    suspend fun getRun(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(-32001, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResult(-32002, "Run not found: $runId")

        val scenarioName = store.getScenarioById(run.scenarioId)?.name ?: ""
        val summary = if (run.summary != null) parseJson(run.summary) else null
        val metadata = if (run.metadata != null) parseJson(run.metadata) else emptyMap<String, Any>()

        return textResult(mapOf(
            "runId" to run.id,
            "tool" to run.tool,
            "testName" to scenarioName,
            "timestamp" to run.createdAt,
            "status" to run.status,
            "summary" to summary,
            "rawOutputPath" to run.rawOutputPath,
            "metadata" to metadata
        ))
    }

    // ── server_status ────────────────────────────────────────────────────

    suspend fun serverStatus(@Suppress("UNUSED_PARAMETER") request: CallToolRequest): CallToolResult {
        val runningRun = store.getPendingOrRunningRun()
        val queuedRuns = store.getQueuedRuns()

        val currentlyRunning = if (runningRun != null && runningRun.status == "running") {
            mapOf(
                "runId" to runningRun.id,
                "tool" to runningRun.tool,
                "testName" to (store.getScenarioById(runningRun.scenarioId)?.name ?: ""),
                "progressPct" to 0.0,
                "currentStage" to "running",
                "elapsedSec" to 0,
                "estimatedRemainingSec" to 0
            )
        } else null

        val queued = queuedRuns.mapIndexed { idx, r ->
            mapOf(
                "runId" to r.id,
                "tool" to r.tool,
                "testName" to (store.getScenarioById(r.scenarioId)?.name ?: ""),
                "position" to (idx + 1)
            )
        }

        return textResult(mapOf(
            "status" to if (runningRun != null) "running" else "idle",
            "uptimeSec" to ((System.currentTimeMillis() - startupTime) / 1000),
            "queueDepth" to queued.size,
            "currentlyRunning" to currentlyRunning,
            "queuedRuns" to queued,
            "serverVersion" to SERVER_VERSION,
            "adapters" to store.listAdapters().map { it.name }
        ))
    }

    // ── get_run_progress ─────────────────────────────────────────────────

    suspend fun getRunProgress(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(-32001, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResult(-32002, "Run not found: $runId")

        val summary = if (run.summary != null) parseJson(run.summary) else null
        return textResult(mapOf(
            "runId" to run.id,
            "status" to run.status,
            "progressPct" to (if (run.status == "completed") 100.0
                else if (run.status == "running") 50.0 else 0.0),
            "currentStage" to (if (run.status == "running") "running" else run.status),
            "stageProgress" to mapOf(
                "warmup" to mapOf("status" to "completed"),
                "running" to mapOf("status" to (if (run.status == "running") "in_progress" else "completed"))
            ),
            "rollingSummary" to summary
        ))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun textResult(payload: Map<String, Any?>): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = gson.toJson(payload))))

    private fun errorResult(code: Int, message: String, data: Map<String, Any>? = null): CallToolResult {
        val error = mutableMapOf<String, Any>("code" to code, "message" to message)
        if (data != null) error["data"] = data
        return CallToolResult(
            content = listOf(TextContent(text = gson.toJson(mapOf("error" to error)))),
            isError = true
        )
    }

    private fun argString(request: CallToolRequest, key: String): String? =
        request.arguments?.get(key)?.let { 
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
        }

    private fun argJson(request: CallToolRequest, key: String): JsonObject? {
        val el = request.arguments?.get(key) ?: return null
        return gson.fromJson(el.toString(), JsonObject::class.java)
    }

    private fun parseJson(json: String): Map<String, Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    companion object {
        const val SERVER_VERSION = "0.1.0"
    }
}

private fun JsonObject.optString(key: String): String? {
    val el = this[key] ?: return null
    return if (el.isJsonNull) null else el.asString
}

private fun JsonObject.optInt(key: String): Int? {
    val el = this[key] ?: return null
    return if (el.isJsonNull) null else el.asInt
}
