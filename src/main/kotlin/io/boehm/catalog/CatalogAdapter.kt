package io.boehm.catalog

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.RunResult
import io.boehm.model.TestPlan
import io.boehm.model.TestType
import io.boehm.model.ValidationError
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Single generic adapter driven by catalog.yaml; the function count scales with template/command/output handling.
@Suppress("TooManyFunctions")
class CatalogAdapter(
    private val toolDef: ToolDef,
    private val profileName: String,
    private val baseDir: String = System.getProperty("user.dir"),
    private val parsers: Map<String, (String) -> RunResult> = emptyMap(),
    private val commandOverride: String? = null,
    override val version: String = "0.1.0"
) : PerfToolAdapter {

    companion object {
        // Process timeout must cover the whole test plus teardown slack.
        private const val TIMEOUT_SLACK_SEC = 10
        // Raw-output retention: keep the newest N files per tool.
        const val MAX_RETAINED_OUTPUTS = 50
        // Seconds to wait for a killed process / its captured output to settle.
        const val PROCESS_SETTLE_SEC = 2L
        // Chars of captured stdout preserved in run metadata on timeout/parse failure.
        const val STDOUT_SNIPPET_CHARS = 500

        fun purgeOldOutputs(outputDir: File, keep: Int = MAX_RETAINED_OUTPUTS) {
            try {
                val files = outputDir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return
                if (files.size > keep) files.take(files.size - keep).forEach { it.delete() }
            } catch (_: Exception) {
                // Retention is best-effort; never fail a run over cleanup.
            }
        }
    }

    override val profile: String get() = profileName

    private val profileDef: ProfileDef
        get() = toolDef.profiles[profileName]
            ?: error("Profile '$profileName' not found for tool '${toolDef.name}'")

    override val name: String get() = toolDef.name
    override val supportedTestTypes: List<TestType>
        get() = toolDef.types.mapNotNull { label -> TestType.entries.find { it.label == label } }.ifEmpty { listOf(TestType.HTTP) }
    override val toolVersions: List<String>
        get() = toolDef.toolVersions.ifEmpty { listOf("0.x") }

    override fun validate(testPlan: TestPlan): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (profileName !in toolDef.profiles) {
            errors.add(ValidationError("profile", "Unknown profile '$profileName' for tool '${toolDef.name}'"))
            return errors
        }
        if (testPlan.targetUrl.isBlank() && profileDef.overrides.containsKey("target_url")) {
            errors.add(ValidationError("targetUrl", "must not be empty"))
        }
        if (testPlan.durationSec <= 0 && profileDef.overrides.containsKey("duration_sec")) {
            errors.add(ValidationError("durationSec", "must be > 0"))
        }
        if (testPlan.ratePerSec <= 0 && profileDef.overrides.containsKey("rate_per_sec")) {
            errors.add(ValidationError("ratePerSec", "must be > 0"))
        }
        if (profileDef.overrides.containsKey("duration_sec")) {
            // warmup_sec only counts when the profile actually supports it (Tulip-only today).
            val warmup = if (profileDef.overrides.containsKey("warmup_sec")) testPlan.warmupSec else 0
            val minTimeout = testPlan.durationSec + warmup + TIMEOUT_SLACK_SEC
            if (testPlan.timeoutSec < minTimeout) {
                val warmupNote = if (profileDef.overrides.containsKey("warmup_sec")) " + warmup_sec" else ""
                errors.add(
                    ValidationError(
                        "timeoutSec",
                        "must be >= duration_sec$warmupNote + $TIMEOUT_SLACK_SEC " +
                            "(got ${testPlan.timeoutSec}, need >= $minTimeout);" +
                            " otherwise the run would be killed mid-test"
                    )
                )
            }
        }
        // Unknown overrides are not hard errors — they are warned in run() metadata
        // to avoid breaking callers with typos while still surfacing the issue.
        return errors
    }

    override fun run(testPlan: TestPlan): RunResult = run(testPlan) { /* no listener */ }

    // Long by design: template rendering, command substitution, subprocess execution, and parsing
    // form one auditable sequence where splitting would obscure the cleanup paths.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    override fun run(testPlan: TestPlan, onProcessStart: (java.lang.Process) -> Unit): RunResult {
        val overrides = resolveOverrides(testPlan)
        val unknownOverrides = testPlan.parameters.keys.filter { it !in profileDef.overrides }

        val outputDir = File(System.getProperty("user.home"), ".boehm/outputs/${toolDef.name}")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "${Instant.now().toString().replace(":", "-")}.json")
        purgeOldOutputs(outputDir)

        // Load config template (JSON-based: apply overrides; script-based: copy as-is)
        val configAndOutput =         if (profileDef.config != null) {
            val templateFile = File(baseDir, profileDef.config)
            if (!templateFile.exists()) {
                return failedResult(testPlan, "Config template not found: ${templateFile.absolutePath}")
            }
            val isJsonConfig = templateFile.name.endsWith(".json") || templateFile.name.endsWith(".jsonc")
            val (preparedJson, outPath) = if (isJsonConfig) {
                val rawTemplate = templateFile.readText()
                val cleanTemplate = stripJsoncComments(rawTemplate)
                val modifiedJson = applyJsonOverrides(cleanTemplate, profileDef, overrides)

                // Inject output path into config JSON at the path declared in output.path
                val configRoot = JsonParser.parseString(modifiedJson).asJsonObject
                val configPattern = Regex("""\{\{config\.(.+?)\}\}""")
                val configMatch = configPattern.find(profileDef.output.path)
                if (configMatch != null) {
                    val configJsonPath = configMatch.groupValues[1]
                    setJsonPath(configRoot, configJsonPath, outputFile.absolutePath)
                }
                val finalJson = GsonBuilder().setPrettyPrinting().create().toJson(configRoot)
                val tmpConfig = writeTempConfig(finalJson)
                val resolvedPath = resolveOutputPath(profileDef.output.path, tmpConfig, outputFile.absolutePath)
                Pair(tmpConfig, resolvedPath)
            } else {
                // Script-based tools: copy template as-is without mutation
                val tmpDir = Files.createTempDirectory("boehm-${toolDef.name}-").toFile()
                val tmpFile = File(tmpDir, templateFile.name)
                templateFile.copyTo(tmpFile, overwrite = true)
                val resolvedPath = resolveOutputPath(profileDef.output.path, null, outputFile.absolutePath)
                Pair(tmpFile, resolvedPath)
            }
            Pair(preparedJson, outPath)
        } else {
            Pair(null, resolveOutputPath(profileDef.output.path, null, outputFile.absolutePath))
        }
        val configFile = configAndOutput.first
        val resolvedOutputPath = configAndOutput.second

        // Build substitution map for command template — always use base outputFile for {{output_file}},
        // not the resolved Gatling path (which is {{output_file}}.results/js/global_stats.json)
        val subs = mutableMapOf<String, String>()
        subs.putAll(overrides)
        if (configFile != null) subs["config_file"] = configFile.absolutePath
        subs["output_file"] = outputFile.absolutePath

        // Execute command, enforcing the test plan timeout
        val rawCommand = commandOverride ?: toolDef.run.command
        val command = substituteCommand(rawCommand, subs)
        val process = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        onProcessStart(process)
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val finished = process.waitFor(testPlan.timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_SETTLE_SEC, TimeUnit.SECONDS)
            val partial = try { stdoutFuture.get(PROCESS_SETTLE_SEC, TimeUnit.SECONDS) } catch (_: Exception) { "" }
            configFile?.delete()
            configFile?.parentFile?.delete()
            return failedResult(testPlan, "timeout after ${testPlan.timeoutSec}s",
                metadata = mapOf("stdout" to partial.take(STDOUT_SNIPPET_CHARS)))
        }
        val stdout = try { stdoutFuture.get(PROCESS_SETTLE_SEC, TimeUnit.SECONDS) } catch (_: Exception) { "" }
        val exitCode = process.exitValue()

        configFile?.delete()
        configFile?.parentFile?.delete()

        // Read output
        val rawOutput = readOutput(profileDef.output.path, resolvedOutputPath, stdout)

        // Parse
        val unknownWarning = if (unknownOverrides.isEmpty()) {
            emptyMap()
        } else {
            mapOf("warnings" to unknownOverrides.map { "unknown override $it ignored" })
        }
        val parser = parsers[profileDef.output.schema]
        if (parser != null) {
            return try {
                val parsed = parser(rawOutput).copy(rawOutputPath = resolvedOutputPath)
                if (unknownWarning.isNotEmpty()) parsed.copy(metadata = parsed.metadata + unknownWarning) else parsed
            } catch (e: Exception) {
                failedResult(testPlan, "Parse error: ${e.message}",
                    metadata = mapOf("exitCode" to exitCode, "stdout" to stdout.take(STDOUT_SNIPPET_CHARS)) + unknownWarning)
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
            metadata = mapOf("exitCode" to exitCode, "stdout" to stdout.take(STDOUT_SNIPPET_CHARS)) + unknownWarning
        )
    }

    private fun resolveOverrides(testPlan: TestPlan): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((name, overrideDef) in profileDef.overrides) {
            if (overrideDef.default != null) {
                val def = overrideDef.default.toString()
                sanitizeOverride(name, def)
                result[name] = def
            }
        }
        val testPlanMap = mapOf(
            "target_url" to testPlan.targetUrl,
            "rate_per_sec" to testPlan.ratePerSec.toString(),
            "duration_sec" to testPlan.durationSec.toString(),
            "warmup_sec" to testPlan.warmupSec.toString()
        )
        for ((name, value) in testPlanMap) {
            if (name in profileDef.overrides && value.isNotBlank()) {
                sanitizeOverride(name, value)
                result[name] = value
            }
        }
        for ((name, value) in testPlan.parameters) {
            if (name in profileDef.overrides) {
                sanitizeOverride(name, value)
                result[name] = value
            }
        }
        return result
    }

    private val shellMetacharRegex = Regex("[;|&`\$(){}<>\\n\\r']")
    private val urlShellRegex = Regex("[;|`\$(){}<>\\n\\r']") // for URLs, allow & and ?=&%# etc., but block '

    /**
     * Reject override values that contain shell metacharacters or otherwise look
     * like command injection. Numeric overrides are parsed as int; target_url is
     * validated as http/https URI (query strings allowed, whitespace still rejected).
     */
    // Every invalid value becomes an IllegalArgumentException by contract; callers surface them as validation errors.
    // The generic catch wraps the URI parser, which throws several unrelated exception types.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun sanitizeOverride(name: String, value: String) {
        if (value.isEmpty()) return
        val shellRegex = if (name == "target_url") urlShellRegex else shellMetacharRegex
        if (shellRegex.containsMatchIn(value)) {
            throw IllegalArgumentException(
                "Invalid override '$name': value contains shell metacharacters: '$value'")
        }
        if (value.contains(Regex("\\s"))) {
            throw IllegalArgumentException(
                "Invalid override '$name': value contains whitespace: '$value'")
        }
        when (name) {
            "target_url" -> {
                // Allow both full URLs (https://host/path?query) and bare hosts (httpbingo.org) for JMeter
                try {
                    if (value.contains("://")) {
                        val uri = java.net.URI(value)
                        require(uri.scheme == "http" || uri.scheme == "https") { "scheme must be http or https" }
                        requireNotNull(uri.host) { "host required" }
                    } else {
                        // Bare host for JMeter — allow host:port
                        val bareHostPattern = Regex("^[a-zA-Z0-9._\\-]+(:\\d+)?$")
                        require(value.matches(bareHostPattern)) { "host must be alphanumeric plus dot/hyphen/underscore, optional :port" }
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid override 'target_url': ${e.message} in '$value'", e)
                }
            }
            "rate_per_sec", "duration_sec", "warmup_sec", "timeout_sec", "threads", "connections" -> {
                if (value.toIntOrNull() == null) {
                    throw IllegalArgumentException(
                        "Invalid override '$name': expected an integer, got '$value'")
                }
            }
        }
    }

    private fun applyJsonOverrides(templateJson: String, profile: ProfileDef, overrides: Map<String, String>): String {
        val root = JsonParser.parseString(templateJson).asJsonObject
        for ((name, value) in overrides) {
            val path = profile.overrides[name]?.path ?: continue
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

    @Suppress("LoopWithTooManyJumpStatements")
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
            // Quote for bash -c to preserve & in query strings, but avoid double-quoting
            // if placeholder is already inside single quotes in the template (e.g. Tulip's --args='--config {{config_file}}')
            val quoted = "'${value.replace("'", "'\\''")}'"
            // First handle already-quoted placeholders
            result = result.replace("'{{${key}}}'", "'$value'")
            result = result.replace("\"{{${key}}}\"", "\"$value\"")
            result = result.replace("{{${key}}}", quoted)
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
