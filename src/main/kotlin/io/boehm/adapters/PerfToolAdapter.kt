package io.boehm.adapters

import io.boehm.model.*

interface PerfToolAdapter {
    val name: String
    val supportedTestTypes: List<TestType>
    val version: String
    val toolVersions: List<String>

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan): RunResult
}
