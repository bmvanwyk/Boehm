package io.boehm.core

import io.boehm.model.TestPlan

class Orchestrator(private val store: Store) {
    private val adapters = mutableMapOf<String, String>()

    fun registerAdapter(tool: String) {
        adapters[tool] = tool
    }

    fun submitRun(tool: String, testName: String, plan: TestPlan): String? {
        val adapters = store.listAdapters()
        if (adapters.none { it.name == tool }) return null

        val planStr = """{"type":"${plan.type}","targetUrl":"${plan.targetUrl}","ratePerSec":${plan.ratePerSec},"durationSec":${plan.durationSec}}"""
        val scenarioId = store.insertScenario(tool, testName, planStr) ?: return null
        return store.insertRun(scenarioId, tool)
    }
}
