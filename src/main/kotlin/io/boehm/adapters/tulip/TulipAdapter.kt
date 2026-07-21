package io.boehm.adapters.tulip

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import java.io.File
import java.nio.file.Files
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

        val tmpDir = Files.createTempDirectory("boehm-tulip-").toFile()
        val configFile = File(tmpDir, "config.jsonc")

        val configJson = buildConfigJson(testPlan, outputFile.absolutePath)
        configFile.writeText(configJson)

        val process = ProcessBuilder(
            tulipCommand,
            "--config", configFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        configFile.delete()
        tmpDir.delete()

        // Try to read output file regardless of exit code
        // (Tulip may produce valid output even if report generation fails after)
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
                rawOutputPath = if (outputFile.exists()) outputFile.absolutePath else null,
                metadata = mapOf(
                    "parseError" to (e.message ?: "unknown"),
                    "exitCode" to exitCode,
                    "stdout" to stdout.take(500)
                )
            )
        }
    }

    internal fun buildConfigJson(testPlan: TestPlan, outputPath: String): String {
        val config = mapOf(
            "actions" to mapOf(
                "output_filename" to outputPath,
                "report_filename" to "",
                "user_class" to "org.example.user.TestHttpUser",
                "user_params" to mapOf(
                    "url" to testPlan.targetUrl
                ),
                "user_actions" to mapOf(
                    "0" to "onStart",
                    "3" to "GET",
                    "100" to "onStop"
                )
            ),
            "contexts" to mapOf(
                "default" to mapOf(
                    "enabled" to true,
                    "num_users" to maxOf(testPlan.ratePerSec.toInt() / 10, 1),
                    "num_threads" to maxOf(testPlan.ratePerSec.toInt() / 50, 1)
                )
            ),
            "benchmarks" to mapOf(
                "boehm-benchmark" to mapOf(
                    "enabled" to true,
                    "aps_rate" to testPlan.ratePerSec.toDouble(),
                    "warmup_duration1" to testPlan.warmupSec.toLong(),
                    "warmup_duration2" to 0L,
                    "benchmark_duration" to testPlan.durationSec.toLong(),
                    "benchmark_iterations" to 1,
                    "scenario_actions" to listOf(
                        mapOf(
                            "id" to 3,
                            "weight" to 0
                        )
                    )
                )
            )
        )

        return JsonParser.parseString(
            GsonBuilder().setPrettyPrinting().create().toJson(config)
        ).asJsonObject.toString()
    }
}
