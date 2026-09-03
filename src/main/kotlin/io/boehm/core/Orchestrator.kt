package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan
import io.boehm.model.ValidationError

class Orchestrator(private val store: Store) {

    sealed class SubmitResult {
        data class Queued(val runId: String) : SubmitResult()
        data class UnknownAdapter(val tool: String) : SubmitResult()
        data class UnknownProfile(val tool: String, val profile: String) : SubmitResult()
        data class Invalid(val errors: List<ValidationError>) : SubmitResult()
    }

    sealed class CancelResult {
        data class Cancelled(val runId: String) : CancelResult()
        data class NotFound(val runId: String) : CancelResult()
        data class NotCancellable(val runId: String, val status: String) : CancelResult()
    }

    private val gson = Gson()
    private var scheduler: Scheduler? = null
    private val adapters = mutableMapOf<String, PerfToolAdapter>() // key: "tool:profile"

    fun registerAdapter(adapter: PerfToolAdapter) {
        adapters["${adapter.name}:${adapter.profile}"] = adapter
        store.insertAdapter(adapter.name,
            gson.toJson(adapter.supportedTestTypes.map { it.label }),
            adapter.version, gson.toJson(adapter.toolVersions), adapter.profile)
    }

    fun submitRun(tool: String, testName: String, testPlan: TestPlan): SubmitResult {
        if (adapters.values.none { it.name == tool }) {
            return SubmitResult.UnknownAdapter(tool)
        }
        val adapter = adapters["$tool:${testPlan.profile}"]
            ?: return SubmitResult.UnknownProfile(tool, testPlan.profile)

        val errors = adapter.validate(testPlan)
        if (errors.isNotEmpty()) return SubmitResult.Invalid(errors)

        val planJson = gson.toJson(testPlan)
        val scenarioId = store.insertScenario(tool, testName, planJson)
            ?: return SubmitResult.Invalid(listOf(ValidationError("testName", "could not persist scenario")))
        val runId = store.insertRun(scenarioId, tool, planJson)
            ?: return SubmitResult.Invalid(listOf(ValidationError("run", "could not persist run")))
        store.updateRunStatus(runId, "queued")

        ensureScheduler()

        return SubmitResult.Queued(runId)
    }

    fun cancelRun(runId: String): CancelResult {
        val run = store.getRun(runId) ?: return CancelResult.NotFound(runId)
        if (run.status !in listOf("pending", "queued", "running")) {
            return CancelResult.NotCancellable(runId, run.status)
        }
        ensureScheduler()
        val ok = scheduler!!.requestCancel(runId)
        return if (ok) CancelResult.Cancelled(runId) else CancelResult.NotCancellable(runId, run.status)
    }

    private fun ensureScheduler() {
        if (scheduler == null) {
            scheduler = Scheduler(store, adapters.values.toList())
            scheduler!!.start()
        }
    }
}
