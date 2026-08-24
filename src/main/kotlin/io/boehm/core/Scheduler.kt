package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    // Cancellation state. activeRunId/activeProcess are written by the scheduler
    // thread and read/written by MCP threads via requestCancel — hence atomics.
    private val activeRunId = AtomicReference<String?>(null)
    private val activeProcess = AtomicReference<Process?>(null)
    private val cancelRequested: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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

    /**
     * Cancel a run. Queued/pending runs are flipped to 'cancelled' directly;
     * the currently-running run has its subprocess killed and will be recorded
     * as 'cancelled' when the adapter call returns.
     */
    fun requestCancel(runId: String): Boolean {
        val run = store.getRun(runId) ?: return false
        return when (run.status) {
            "pending", "queued" -> store.cancelQueuedRun(runId)
            "running" -> {
                if (runId != activeRunId.get()) return false
                cancelRequested.add(runId)
                activeProcess.get()?.destroyForcibly()
                true
            }
            else -> false
        }
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
            activeRunId.set(pending.id)
            val result = try {
                adapter.run(testPlan) { p -> activeProcess.set(p) }
            } finally {
                activeProcess.set(null)
                activeRunId.set(null)
            }

            val summaryJson = if (result.summary != null) gson.toJson(result.summary) else null
            val error = if (result.status == "failed") result.metadata["error"]?.toString() else null
            val metadataJson = if (result.metadata.isNotEmpty()) gson.toJson(result.metadata) else null
            val finalStatus = if (cancelRequested.remove(pending.id)) "cancelled" else result.status
            store.updateRunStatus(pending.id, finalStatus, summary = summaryJson,
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
