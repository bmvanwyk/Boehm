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
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "boehm-scheduler").also { it.isDaemon = true }
    }
    private val gson = Gson()
    private val adapterMap = adapters.associateBy { "${it.name}:${it.profile}" }
    private var running = false

    fun start() {
        if (running) return
        running = true
        val interrupted = store.failInterruptedRuns()
        if (interrupted > 0) {
            System.err.println("boehm: marked $interrupted interrupted run(s) as failed")
        }
        executor.scheduleWithFixedDelay({ pollQueue() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        executor.shutdown()
    }

    private fun pollQueue() {
        val pending = store.getPendingOrRunningRun() ?: return
        if (pending.status == "running") return

        try {
            val scenario = store.getScenarioById(pending.scenarioId)
            if (scenario == null) {
                store.updateRunStatus(pending.id, "failed",
                    error = "Scenario not found: ${pending.scenarioId}")
                return
            }

            val testPlan = gson.fromJson(scenario.testPlan, TestPlan::class.java)
            val adapter = adapterMap["${pending.tool}:${testPlan.profile}"]
            if (adapter == null) {
                store.updateRunStatus(pending.id, "failed",
                    error = "Adapter not found: ${pending.tool}:${testPlan.profile}")
                return
            }

            store.updateRunStatus(pending.id, "running")
            val result = adapter.run(testPlan)

            val summaryJson = if (result.summary != null) gson.toJson(result.summary) else null
            val error = if (result.status == "failed") result.metadata["error"]?.toString() else null
            val metadataJson = if (result.metadata.isNotEmpty()) gson.toJson(result.metadata) else null
            store.updateRunStatus(pending.id, result.status, summary = summaryJson,
                error = error, rawOutputPath = result.rawOutputPath, metadata = metadataJson)
        } catch (e: Exception) {
            try {
                store.updateRunStatus(pending.id, "failed",
                    error = "${e.javaClass.simpleName}: ${e.message}")
            } catch (_: Exception) {
                // Store unavailable; nothing more we can do.
            }
        }
    }
}
