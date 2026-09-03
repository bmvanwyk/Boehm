package io.boehm.adapters

import io.boehm.model.RunResult
import io.boehm.model.TestPlan
import io.boehm.model.TestType
import io.boehm.model.ValidationError
import java.lang.Process

interface PerfToolAdapter {
    val name: String
    val profile: String
    val supportedTestTypes: List<TestType>
    val version: String
    val toolVersions: List<String>

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan): RunResult

    /**
     * Runs the plan. Implementations that spawn a subprocess must invoke
     * [onProcessStart] once the process exists so the caller can cancel it.
     * Default implementation ignores the listener (in-memory adapters).
     */
    fun run(testPlan: TestPlan, onProcessStart: (Process) -> Unit): RunResult = run(testPlan)
}
