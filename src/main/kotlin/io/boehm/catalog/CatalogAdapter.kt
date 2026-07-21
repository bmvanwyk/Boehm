package io.boehm.catalog

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class CatalogAdapter(
    private val toolDef: ToolDef,
    private val profileName: String,
    private val baseDir: String = System.getProperty("user.dir"),
    private val parsers: Map<String, (String) -> RunResult> = emptyMap(),
    private val commandOverride: String? = null,
    override val version: String = "0.1.0"
) : PerfToolAdapter {

    private val profile: ProfileDef
        get() = toolDef.profiles[profileName]
            ?: error("Profile '$profileName' not found for tool '${toolDef.name}'")

    override val name: String get() = toolDef.name
    override val supportedTestTypes: List<TestType>
        get() = when {
            toolDef.name == "tulip" -> listOf(TestType.HTTP)
            else -> listOf(TestType.HTTP)
        }
    override val toolVersions: List<String>
        get() = listOf("0.x")

    override fun validate(testPlan: TestPlan): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (profileName !in toolDef.profiles) {
            errors.add(ValidationError("profile", "Unknown profile '$profileName' for tool '${toolDef.name}'"))
            return errors
        }
        if (testPlan.targetUrl.isBlank() && profile.overrides.containsKey("target_url")) {
            errors.add(ValidationError("targetUrl", "must not be empty"))
        }
        if (testPlan.durationSec <= 0 && profile.overrides.containsKey("duration_sec")) {
            errors.add(ValidationError("durationSec", "must be > 0"))
        }
        if (testPlan.ratePerSec <= 0 && profile.overrides.containsKey("rate_per_sec")) {
            errors.add(ValidationError("ratePerSec", "must be > 0"))
        }
        return errors
    }

    override fun run(testPlan: TestPlan): RunResult {
        val profile = profile
        val overrides = resolveOverrides(testPlan)

        val outputDir = File(System.getProperty("user.home"), ".boehm/outputs/${toolDef.name}")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "${Instant.now().toString().replace(":", "-")}.json")

        // Load config template, apply overrides, inject output path, write temp file
        val configAndOutput = if (profile.config != null) {
            val templateFile = File(baseDir, profile.config)
            if (!templateFile.exists()) {
                return failedResult(testPlan, "Config template not found: ${templateFile.absolutePath}")
            }
            val rawTemplate = templateFile.readText()
            val cleanTemplate = stripJsoncComments(rawTemplate)
            val modifiedJson = applyJsonOverrides(cleanTemplate, profile, overrides)

            // Inject output path into config JSON at the path declared in output.path
            val configRoot = JsonParser.parseString(modifiedJson).asJsonObject
            val configPattern = Regex("""\{\{config\.(.+?)\}\}""")
            val configMatch = configPattern.find(profile.output.path)
            if (configMatch != null) {
                val configJsonPath = configMatch.groupValues[1]
                setJsonPath(configRoot, configJsonPath, outputFile.absolutePath)
            }
            val finalJson = GsonBuilder().setPrettyPrinting().create().toJson(configRoot)

            val tmpConfig = writeTempConfig(finalJson)
            val outPath = resolveOutputPath(profile.output.path, tmpConfig, outputFile.absolutePath)
            Pair(tmpConfig, outPath)
        } else {
            Pair(null, resolveOutputPath(profile.output.path, null, outputFile.absolutePath))
        }
        val configFile = configAndOutput.first
        val resolvedOutputPath = configAndOutput.second

        // Build substitution map for command template
        val subs = mutableMapOf<String, String>()
        subs.putAll(overrides)
        if (configFile != null) subs["config_file"] = configFile.absolutePath
        subs["output_file"] = resolvedOutputPath ?: outputFile.absolutePath

        // Execute command
        val rawCommand = commandOverride ?: toolDef.run.command
        val command = substituteCommand(rawCommand, subs)
        val executor = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        val stdout = executor.inputStream.bufferedReader().readText()
        val exitCode = executor.waitFor()

        configFile?.delete()
        configFile?.parentFile?.delete()

        // Read output
        val rawOutput = readOutput(profile.output.path, resolvedOutputPath, stdout)

        // Parse
        val parser = parsers[profile.output.schema]
        if (parser != null) {
            return try {
                parser(rawOutput).copy(rawOutputPath = resolvedOutputPath)
            } catch (e: Exception) {
                failedResult(testPlan, "Parse error: ${e.message}",
                    metadata = mapOf("exitCode" to exitCode, "stdout" to stdout.take(500)))
            }
        }

        return RunResult(
            tool = toolDef.name,
            testName = testPlan.type,
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = "completed",
            summary = null,
            rawOutputPath = resolvedOutputPath,
            metadata = mapOf("exitCode" to exitCode, "stdout" to stdout.take(500))
        )
    }

    private fun resolveOverrides(testPlan: TestPlan): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((name, overrideDef) in profile.overrides) {
            if (overrideDef.default != null) {
                result[name] = overrideDef.default.toString()
            }
        }
        val testPlanMap = mapOf(
            "target_url" to testPlan.targetUrl,
            "rate_per_sec" to testPlan.ratePerSec.toString(),
            "duration_sec" to testPlan.durationSec.toString(),
            "warmup_sec" to testPlan.warmupSec.toString()
        )
        for ((name, value) in testPlanMap) {
            if (name in profile.overrides && value.isNotBlank()) {
                result[name] = value
            }
        }
        for ((name, value) in testPlan.parameters) {
            if (name in profile.overrides) {
                result[name] = value
            }
        }
        return result
    }

    private fun applyJsonOverrides(templateJson: String, profile: ProfileDef, overrides: Map<String, String>): String {
        val root = JsonParser.parseString(templateJson).asJsonObject
        for ((name, value) in overrides) {
            val overrideDef = profile.overrides[name] ?: continue
            val path = overrideDef.path ?: continue
            setJsonPath(root, path, value)
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    private fun setJsonPath(root: JsonObject, path: String, value: String) {
        val parts = path.split(".")
        var current = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            if (!current.has(part) || current.get(part).isJsonNull) {
                current.add(part, JsonObject())
            }
            current = current.getAsJsonObject(part)
        }
        val lastKey = parts.last()
        val original = current.get(lastKey)
        val coerced = coerceJsonValue(value, original)
        current.add(lastKey, coerced)
    }

    private fun coerceJsonValue(value: String, original: JsonElement?): JsonElement {
        if (original == null || original.isJsonNull) {
            return JsonParser.parseString("\"$value\"")
        }
        return when {
            original.isJsonPrimitive && original.asJsonPrimitive.isNumber -> {
                val numStr = original.asNumber.toString()
                if (numStr.contains(".")) {
                    JsonParser.parseString(value.toDoubleOrNull()?.toString() ?: "\"$value\"")
                } else {
                    JsonParser.parseString(value.toLongOrNull()?.toString() ?: "\"$value\"")
                }
            }
            else -> JsonParser.parseString("\"$value\"")
        }
    }

    private fun stripJsoncComments(jsonc: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < jsonc.length) {
            when {
                jsonc[i] == '/' && i + 1 < jsonc.length && jsonc[i + 1] == '/' -> {
                    while (i < jsonc.length && jsonc[i] != '\n') i++
                }
                jsonc[i] == '/' && i + 1 < jsonc.length && jsonc[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < jsonc.length && !(jsonc[i] == '*' && jsonc[i + 1] == '/')) i++
                    i += 2
                }
                else -> { sb.append(jsonc[i]); i++ }
            }
        }
        return sb.toString()
    }

    private fun writeTempConfig(json: String): File {
        val tmpDir = Files.createTempDirectory("boehm-${toolDef.name}-").toFile()
        val configFile = File(tmpDir, "config.jsonc")
        configFile.writeText(json)
        return configFile
    }

    private fun resolveOutputPath(outputPathDecl: String, configFile: File?, defaultOutputPath: String): String? {
        if (outputPathDecl == "stdout") return null
        val configPattern = Regex("""\{\{config\.(.+?)\}\}""")
        val configMatch = configPattern.find(outputPathDecl)
        if (configMatch != null && configFile != null && configFile.exists()) {
            val jsonPath = configMatch.groupValues[1]
            val root = JsonParser.parseString(configFile.readText()).asJsonObject
            val value = resolveJsonPath(root, jsonPath)
            if (value != null) return value
        }
        val outputVarPattern = Regex("""\{\{output_file\}\}""")
        if (outputVarPattern.containsMatchIn(outputPathDecl)) {
            return outputVarPattern.replace(outputPathDecl, defaultOutputPath)
        }
        return defaultOutputPath
    }

    private fun resolveJsonPath(root: JsonObject, path: String): String? {
        var current: com.google.gson.JsonElement = root
        for (part in path.split(".")) {
            if (current is JsonObject && current.has(part)) {
                current = current.get(part)
            } else return null
        }
        return if (current.isJsonPrimitive) current.asString else null
    }

    private fun substituteCommand(command: String, subs: Map<String, String>): String {
        var result = command
        for ((key, value) in subs) {
            result = result.replace("{{${key}}}", value)
        }
        return result
    }

    private fun readOutput(outputPathDecl: String, resolvedPath: String?, stdout: String): String {
        if (outputPathDecl == "stdout") return stdout
        if (resolvedPath != null) {
            val file = File(resolvedPath)
            if (file.exists()) return file.readText()
        }
        return stdout
    }

    private fun failedResult(testPlan: TestPlan, error: String, metadata: Map<String, Any> = emptyMap()): RunResult {
        return RunResult(
            tool = toolDef.name,
            testName = testPlan.type,
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = "failed",
            summary = null,
            rawOutputPath = null,
            metadata = mapOf("error" to error) + metadata
        )
    }
}
