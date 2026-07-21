package io.boehm.adapters.tulip

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.model.Latency
import io.boehm.model.RunResult
import io.boehm.model.Summary
import java.time.Instant
import java.util.UUID

object TulipParser {
    private const val NS_TO_MS = 1_000_000.0

    fun parse(rawJson: String): RunResult {
        val root = JsonParser.parseString(rawJson).asJsonObject

        val results: JsonArray = root.getAsJsonArray("results")
            ?: throw IllegalArgumentException("Missing required field: results")

        if (results.size() == 0) {
            throw IllegalArgumentException("results array is empty")
        }

        val first = results.get(0).asJsonObject

        if (!first.has("duration")) {
            throw IllegalArgumentException("Missing required field: duration")
        }

        val durationSec = first.get("duration").asInt
        val numActions = first.get("num_actions")?.asInt ?: 0
        val numFailed = first.get("num_failed")?.asInt ?: 0
        val errorRatePct = if (numActions > 0) (numFailed.toDouble() / numActions) * 100.0 else 0.0

        val latency = parseLatency(first)

        val summary = Summary(
            durationSec = durationSec,
            totalRequests = numActions,
            throughputReqPerSec = first.get("avg_aps")?.asDouble ?: 0.0,
            errorRatePct = errorRatePct,
            latency = latency
        )

        val metadata = mutableMapOf<String, Any>()
        if (first.has("bm_name")) metadata["benchmark"] = first.get("bm_name").asString
        if (first.has("row_id")) metadata["row"] = first.get("row_id").asInt
        if (first.has("context_name")) metadata["context"] = first.get("context_name").asString
        if (first.has("sd_rt")) metadata["sd_rt_ns"] = first.get("sd_rt").asDouble
        if (first.has("avg_rt")) metadata["avg_rt_ns"] = first.get("avg_rt").asDouble
        if (first.has("num_threads")) metadata["threads"] = first.get("num_threads").asInt
        if (first.has("num_users")) metadata["users"] = first.get("num_users").asInt
        if (root.has("timestamp")) metadata["test_timestamp"] = root.get("timestamp").asString
        if (root.has("version")) metadata["tulip_version"] = root.get("version").asString
        if (first.has("hdr_histogram_rt")) metadata["hdr_histogram"] = first.get("hdr_histogram_rt").asString

        return RunResult(
            tool = "tulip",
            testName = first.get("bm_name")?.asString ?: "unknown",
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = "completed",
            summary = summary,
            rawOutputPath = null,
            metadata = metadata
        )
    }

    fun parseProgressLine(jsonLine: String): Map<String, Any>? {
        return try {
            val obj = JsonParser.parseString(jsonLine).asJsonObject
            if (obj.has("type") && obj.get("type").asString == "progress") {
                obj.entrySet().associate { it.key to extractValue(it.value) }
            } else null
        } catch (_: Exception) { null }
    }

    private fun parseLatency(result: JsonObject): Latency {
        val percentiles = result.getAsJsonObject("percentiles_rt")
        val p50 = if (percentiles != null && percentiles.has("50.0")) percentiles.get("50.0").asDouble / NS_TO_MS else 0.0
        val p90 = if (percentiles != null && percentiles.has("90.0")) percentiles.get("90.0").asDouble / NS_TO_MS else 0.0
        val p95 = if (percentiles != null && percentiles.has("95.0")) percentiles.get("95.0").asDouble / NS_TO_MS else 0.0
        val p99 = if (percentiles != null && percentiles.has("99.0")) percentiles.get("99.0").asDouble / NS_TO_MS else 0.0

        val minRt = if (result.has("min_rt")) result.get("min_rt").asDouble / NS_TO_MS else 0.0
        val maxRt = if (result.has("max_rt")) result.get("max_rt").asDouble / NS_TO_MS else 0.0

        return Latency(
            minMs = minRt,
            p50Ms = p50,
            p90Ms = p90,
            p95Ms = p95,
            p99Ms = p99,
            maxMs = maxRt
        )
    }

    private fun extractValue(element: com.google.gson.JsonElement): Any {
        return when {
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                when {
                    prim.isNumber -> prim.asDouble
                    prim.isBoolean -> prim.asBoolean
                    else -> prim.asString
                }
            }
            element.isJsonNull -> "null"
            else -> element.toString()
        }
    }
}
