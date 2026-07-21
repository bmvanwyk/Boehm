package io.boehm.adapters.tulip

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import java.io.File
import java.time.Instant
import java.util.UUID

class TulipAdapter(
    private val tulipCommand: String = "tulip",
    override val version: String = "0.1.0",
    override val toolVersions: List<String> = listOf("0.x")
) : PerfToolAdapter {
    override val name: String = "tulip"
    override val supportedTestTypes: List<TestType> = listOf(TestType.HTTP)

    override fun validate(testPlan: TestPlan): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (testPlan.targetUrl.isBlank()) {
            errors.add(ValidationError("targetUrl", "must not be empty"))
        }
        if (testPlan.durationSec <= 0) {
            errors.add(ValidationError("durationSec", "must be > 0"))
        }
        if (testPlan.ratePerSec <= 0) {
            errors.add(ValidationError("ratePerSec", "must be > 0"))
        }
        return errors
    }

    override fun run(testPlan: TestPlan): RunResult {
        val outputDir = File(System.getProperty("user.home"), ".boehm/outputs/${testPlan.type}")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "${Instant.now().toString().replace(":", "-")}.json")

        val process = ProcessBuilder(
            tulipCommand,
            "--type", testPlan.type,
            "--target", testPlan.targetUrl,
            "--rate", testPlan.ratePerSec.toString(),
            "--duration", testPlan.durationSec.toString(),
            "--warmup", testPlan.warmupSec.toString(),
            "--output", outputFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            return RunResult(
                tool = name,
                testName = testPlan.type,
                timestamp = Instant.now().toString(),
                runId = UUID.randomUUID().toString(),
                status = "failed",
                summary = null,
                rawOutputPath = outputFile.absolutePath,
                metadata = mapOf("exitCode" to exitCode, "stderr" to stdout.take(1000))
            )
        }

        val rawJson = if (outputFile.exists()) outputFile.readText() else stdout
        return try {
            TulipParser.parse(rawJson).copy(rawOutputPath = outputFile.absolutePath)
        } catch (e: Exception) {
            RunResult(
                tool = name,
                testName = testPlan.type,
                timestamp = Instant.now().toString(),
                runId = UUID.randomUUID().toString(),
                status = "failed",
                summary = null,
                rawOutputPath = outputFile.absolutePath,
                metadata = mapOf("parseError" to (e.message ?: "unknown"))
            )
        }
    }
}
