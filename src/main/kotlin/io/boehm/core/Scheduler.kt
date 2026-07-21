package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(
    private val store: Store,
    private val adapters: List<PerfToolAdapter>
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val gson = Gson()
    private val adapterMap = adapters.associateBy { it.name }
    private var running = false

    fun start() {
        if (running) return
        running = true
        executor.scheduleWithFixedDelay({ pollQueue() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        executor.shutdown()
    }

    private fun pollQueue() {
        try {
            val pending = store.getPendingOrRunningRun() ?: return
            if (pending.status == "running") return

            store.updateRunStatus(pending.id, "running")

            val scenarioId = pending.scenarioId
            val scenario = store.getScenarioById(scenarioId) ?: return

            val testPlan = gson.fromJson(scenario.testPlan, TestPlan::class.java)
            val adapter = adapterMap[pending.tool]

            if (adapter == null) {
                store.updateRunStatus(pending.id, "failed", error = "Adapter not found: ${pending.tool}")
                return
            }

            val result = adapter.run(testPlan)

            val summaryJson = if (result.summary != null) gson.toJson(result.summary) else null
            store.updateRunStatus(pending.id, result.status, summary = summaryJson,
                error = null, rawOutputPath = result.rawOutputPath)

        } catch (_: Exception) {
        }
    }
}
