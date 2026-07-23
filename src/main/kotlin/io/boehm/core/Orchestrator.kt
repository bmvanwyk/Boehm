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

    private val gson = Gson()
    private var scheduler: Scheduler? = null
    private val adapters = mutableMapOf<String, PerfToolAdapter>() // key: "tool:profile"

    fun registerAdapter(adapter: PerfToolAdapter) {
        adapters["${adapter.name}:${adapter.profile}"] = adapter
        store.insertAdapter(adapter.name,
            gson.toJson(adapter.supportedTestTypes.map { it.label }),
            adapter.version, gson.toJson(adapter.toolVersions))
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
        val runId = store.insertRun(scenarioId, tool)
            ?: return SubmitResult.Invalid(listOf(ValidationError("run", "could not persist run")))
        store.updateRunStatus(runId, "queued")

        if (scheduler == null) {
            scheduler = Scheduler(store, adapters.values.toList())
            scheduler!!.start()
        }

        return SubmitResult.Queued(runId)
    }
}
