package io.boehm.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.boehm.model.TestPlan
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Business logic for Boehm's MCP tools. Each method mirrors one tool exposed by
 * the MCP [io.modelcontextprotocol.kotlin.sdk.server.Server] and returns a
 * [CallToolResult]. The methods are plain suspended functions (not tied to the
 * MCP transport) so they can be unit-tested directly.
 */
// One suspend function per MCP tool plus shared helpers; the function count scales with the tool surface.
// The class is a thin translation layer over Orchestrator/Store/Comparator.
@Suppress("TooManyFunctions")
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
            mapOf("name" to it.name, "profile" to it.profile, "supported_types" to it.supportedTypes, "version" to it.version)
        }
        return textResult(mapOf("adapters" to adapters))
    }

    // ── run_test ─────────────────────────────────────────────────────────

    suspend fun runTest(request: CallToolRequest): CallToolResult {
        val tool = argString(request, "tool") ?: return errorResult(INVALID_PARAMS, "Missing tool")
        val testName = argString(request, "test_name") ?: return errorResult(INVALID_PARAMS, "Missing test_name")
        val planJson = argJson(request, "test_plan") ?: return errorResult(INVALID_PARAMS, "Missing test_plan")
        val plan = parseTestPlan(planJson)

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
                errorResult(UNKNOWN_ADAPTER, "Adapter not found: ${result.tool} (removed: vegeta, wrk — see CHANGELOG.md)",
                    mapOf("available_adapters" to store.listAdapters().map { it.name }))
            is Orchestrator.SubmitResult.UnknownProfile ->
                errorResult(INVALID_PARAMS, "Unknown profile '${result.profile}' for tool '${result.tool}'")
            is Orchestrator.SubmitResult.Invalid ->
                errorResult(INVALID_PARAMS, "Invalid test plan",
                    mapOf("validation_errors" to result.errors.map { "${it.field}: ${it.message}" }))
        }
    }

    // ── get_run ──────────────────────────────────────────────────────────

    suspend fun getRun(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(INVALID_PARAMS, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResult(NOT_FOUND, "Run not found: $runId")

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
            val est = estimateProgress(runningRun)
            mapOf(
                "runId" to runningRun.id,
                "tool" to runningRun.tool,
                "testName" to (store.getScenarioById(runningRun.scenarioId)?.name ?: ""),
                "progressPct" to est.pct,
                "currentStage" to est.stage,
                "elapsedSec" to est.elapsedSec,
                "estimatedRemainingSec" to est.remainingSec
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
            "uptimeSec" to ((System.currentTimeMillis() - startupTime) / MS_PER_SEC),
            "queueDepth" to queued.size,
            "currentlyRunning" to currentlyRunning,
            "queuedRuns" to queued,
            "serverVersion" to SERVER_VERSION,
            "adapters" to store.listAdapters().map { it.name }
        ))
    }

    // ── get_run_progress ─────────────────────────────────────────────────

    private data class ProgressEstimate(val pct: Double, val stage: String, val elapsedSec: Long, val remainingSec: Long)

    private fun estimateProgress(run: RunRow): ProgressEstimate {
        if (run.status == "completed") return ProgressEstimate(PCT_COMPLETE, "completed", 0, 0)
        if (run.status != "running") return ProgressEstimate(0.0, run.status, 0, 0)

        val startedAt = run.startedAt?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        } ?: return ProgressEstimate(0.0, "running", 0, 0)

        val elapsed = Duration.between(startedAt, Instant.now()).seconds
        // Prefer the per-run plan snapshot; fall back to the scenario plan for old runs.
        val scenario = store.getScenarioById(run.scenarioId)
        val plan = (run.testPlan ?: scenario?.testPlan)?.let {
            try { gson.fromJson(it, JsonObject::class.java) } catch (_: Exception) { null }
        }
        // warmup_sec is Tulip-only at execution time; other tools ignore it, so progress must too.
        val warmup = if (run.tool == "tulip") plan?.optInt("warmupSec") ?: 0 else 0
        val duration = plan?.optInt("durationSec") ?: 0
        val total = (warmup + duration).coerceAtLeast(1)

        val pct = (elapsed.toDouble() / total * PCT_COMPLETE).coerceIn(PCT_NONE, PCT_ALMOST_DONE)
        val stage = if (elapsed < warmup) "warmup" else "measuring"
        return ProgressEstimate(pct, stage, elapsed, (total - elapsed).coerceAtLeast(0))
    }

    suspend fun getRunProgress(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(INVALID_PARAMS, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResult(NOT_FOUND, "Run not found: $runId")

        val est = estimateProgress(run)
        val summary = if (run.summary != null) parseJson(run.summary) else null
        return textResult(mapOf(
            "runId" to run.id,
            "status" to run.status,
            "progressPct" to est.pct,
            "currentStage" to est.stage,
            "elapsedSec" to est.elapsedSec,
            "estimatedRemainingSec" to est.remainingSec,
            "rollingSummary" to summary
        ))
    }

    // ── list_runs ────────────────────────────────────────────────────────

    suspend fun listRuns(request: CallToolRequest): CallToolResult {
        val tool = argString(request, "tool")
        val testName = argString(request, "test_name")
        val limit = request.arguments?.get("limit")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        } ?: DEFAULT_LIST_LIMIT

        val runs = store.listRecentRuns(tool, testName, limit.coerceIn(MIN_LIST_LIMIT, MAX_LIST_LIMIT)).map { run ->
            mapOf(
                "runId" to run.id,
                "tool" to run.tool,
                "testName" to (store.getScenarioById(run.scenarioId)?.name ?: ""),
                "status" to run.status,
                "createdAt" to run.createdAt,
                "summary" to (if (run.summary != null) parseJson(run.summary) else null)
            )
        }
        return textResult(mapOf("runs" to runs))
    }

    // ── tag_baseline ─────────────────────────────────────────────────────

    suspend fun tagBaseline(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(INVALID_PARAMS, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResult(NOT_FOUND, "Run not found: $runId")
        if (run.status != "completed" || run.summary == null) {
            return errorResult(
                NOT_CANCELLABLE,
                "Run $runId is not taggable as baseline (status=${run.status}; needs a completed run with a summary)"
            )
        }
        store.setBaseline(run.scenarioId, run.id)
        return textResult(mapOf(
            "runId" to run.id,
            "scenarioId" to run.scenarioId,
            "taggedAsBaseline" to true
        ))
    }

    // ── compare_runs ─────────────────────────────────────────────────────

    private fun parseSummary(json: String): io.boehm.model.Summary? = try {
        gson.fromJson(json, io.boehm.model.Summary::class.java)
    } catch (_: Exception) { null }

    /** Builds a TestPlan from the raw test_plan JSON; unknown keys become free-form parameters. */
    private fun parseTestPlan(planJson: JsonObject): TestPlan {
        val parameters = planJson.entrySet()
            .filterNot { knownPlanFields.contains(it.key) }
            .associate {
                it.key to when {
                    it.value.isJsonPrimitive -> it.value.asJsonPrimitive.let { p -> if (p.isString) p.asString else p.toString() }
                    it.value.isJsonNull -> ""
                    else -> it.value.toString()
                }
            }
        return TestPlan(
            type = planJson.optString("type") ?: "http",
            profile = planJson.optString("profile") ?: "http-get",
            targetUrl = planJson.optString("target_url") ?: "",
            ratePerSec = planJson.optInt("rate_per_sec") ?: DEFAULT_RATE_PER_SEC,
            durationSec = planJson.optInt("duration_sec") ?: DEFAULT_DURATION_SEC,
            warmupSec = planJson.optInt("warmup_sec") ?: DEFAULT_WARMUP_SEC,
            timeoutSec = planJson.optInt("timeout_sec") ?: DEFAULT_TIMEOUT_SEC,
            parameters = parameters
        )
    }

    private data class BaselinePair(val baseline: RunRow, val baselineSummary: io.boehm.model.Summary)

    /** Resolves the tagged (or explicit) baseline run plus its parsed summary. Null means "no usable baseline". */
    private fun resolveBaseline(run: RunRow, baselineRunId: String?): BaselinePair? {
        val id = baselineRunId ?: store.getBaselineRunId(run.scenarioId) ?: return null
        val baseline = store.getRun(id) ?: return null
        val summary = baseline.summary?.let { parseSummary(it) } ?: return null
        return BaselinePair(baseline, summary)
    }

    suspend fun compareRuns(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(INVALID_PARAMS, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResult(NOT_FOUND, "Run not found: $runId")
        val runSummary = run.summary?.let { parseSummary(it) }
            ?: return errorResult(NOT_CANCELLABLE, "Run $runId has no summary (status=${run.status})")

        val explicitId = argString(request, "baseline_run_id")
        val baselinePair = resolveBaseline(run, explicitId)
        if (baselinePair == null) {
            return if (explicitId != null && store.getRun(explicitId) == null) {
                errorResult(NOT_FOUND, "Baseline run not found: $explicitId")
            } else if (explicitId != null) {
                errorResult(NOT_CANCELLABLE, "Baseline run $explicitId has no summary")
            } else {
                errorResult(
                    NO_BASELINE,
                    "No baseline tagged for this scenario; pass baseline_run_id or call tag_baseline first"
                )
            }
        }
        val (baseline, baselineSummary) = baselinePair

        val comparison = Comparator.compare(runSummary, baselineSummary)
        // Gatling p90 is a p75 approximation (see GatlingParser): exclude it from verdicts.
        val gatlingInvolved = run.tool == "gatling" || baseline.tool == "gatling"
        val metricsOut = if (gatlingInvolved) comparison.metrics.filterKeys { it != "p90Ms" } else comparison.metrics
        return textResult(mapOf(
            "runId" to run.id,
            "baselineRunId" to baseline.id,
            "p90ExcludedForGatling" to gatlingInvolved,
            "metrics" to metricsOut.mapValues { (_, d) ->
                mapOf(
                    "baseline" to d.baseline,
                    "run" to d.run,
                    "deltaPct" to d.deltaPct,
                    "verdict" to d.verdict
                )
            },
            "regressions" to metricsOut.filterValues { it.verdict == "regression" }.keys.toList(),
            "improvements" to metricsOut.filterValues { it.verdict == "improvement" }.keys.toList()
        ))
    }

    // ── cancel_run ───────────────────────────────────────────────────────

    suspend fun cancelRun(request: CallToolRequest): CallToolResult {
        val runId = argString(request, "run_id") ?: return errorResult(INVALID_PARAMS, "Missing run_id")
        return when (val result = orchestrator.cancelRun(runId)) {
            is Orchestrator.CancelResult.Cancelled ->
                textResult(mapOf("runId" to runId, "status" to "cancelling"))
            is Orchestrator.CancelResult.NotFound ->
                errorResult(NOT_FOUND, "Run not found: ${result.runId}")
            is Orchestrator.CancelResult.NotCancellable ->
                errorResult(NOT_CANCELLABLE, "Run ${result.runId} cannot be cancelled (status=${result.status})")
        }
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

        // JSON-RPC-style error codes returned in handler error payloads.
        const val UNKNOWN_ADAPTER = -32000
        const val INVALID_PARAMS = -32001
        const val NOT_FOUND = -32002
        const val NOT_CANCELLABLE = -32003
        const val NO_BASELINE = -32004

        // Defaults applied when the test_plan omits a field.
        const val DEFAULT_RATE_PER_SEC = 50
        const val DEFAULT_DURATION_SEC = 30
        const val DEFAULT_WARMUP_SEC = 5
        const val DEFAULT_TIMEOUT_SEC = 60

        // Progress estimation bounds.
        const val PCT_NONE = 0.0
        const val PCT_COMPLETE = 100.0
        const val PCT_ALMOST_DONE = 99.0
        const val MS_PER_SEC = 1000

        // list_runs paging bounds.
        const val DEFAULT_LIST_LIMIT = 20
        const val MIN_LIST_LIMIT = 1
        const val MAX_LIST_LIMIT = 100
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
