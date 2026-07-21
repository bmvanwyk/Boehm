package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan

class Orchestrator(private val store: Store) {
    private val gson = Gson()
    private var scheduler: Scheduler? = null
    private val adapters = mutableMapOf<String, PerfToolAdapter>()

    fun registerAdapter(adapter: PerfToolAdapter) {
        adapters[adapter.name] = adapter
        store.insertAdapter(adapter.name,
            gson.toJson(adapter.supportedTestTypes.map { it.label }),
            adapter.version, gson.toJson(adapter.toolVersions))
    }

    fun submitRun(tool: String, testName: String, testPlan: TestPlan): String? {
        val adapter = adapters[tool] ?: return null

        val errors = adapter.validate(testPlan)
        if (errors.isNotEmpty()) return null

        val planJson = gson.toJson(testPlan)
        val scenarioId = store.insertScenario(tool, testName, planJson) ?: return null

        val runId = store.insertRun(scenarioId, tool) ?: return null
        store.updateRunStatus(runId, "queued")

        if (scheduler == null) {
            scheduler = Scheduler(store, adapters.values.toList())
            scheduler!!.start()
        }

        return runId
    }
}
