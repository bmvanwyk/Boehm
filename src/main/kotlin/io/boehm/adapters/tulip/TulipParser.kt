package io.boehm.adapters.tulip

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.model.Latency
import io.boehm.model.RunResult
import io.boehm.model.Summary
import java.time.Instant
import java.util.UUID

object TulipParser {
    fun parse(rawJson: String): RunResult {
        val root = JsonParser.parseString(rawJson).asJsonObject

        if (!root.has("latencyMs")) {
            throw IllegalArgumentException("Missing required field: latencyMs")
        }
        val latencyObj = root.getAsJsonObject("latencyMs")
        val latency = Latency(
            minMs = latencyObj.get("min").asDouble,
            p50Ms = latencyObj.get("p50").asDouble,
            p90Ms = latencyObj.get("p90").asDouble,
            p95Ms = latencyObj.get("p95").asDouble,
            p99Ms = latencyObj.get("p99").asDouble,
            maxMs = latencyObj.get("max").asDouble
        )

        if (!root.has("duration")) {
            throw IllegalArgumentException("Missing required field: duration")
        }
        val summary = Summary(
            durationSec = root.get("duration").asInt,
            totalRequests = root.get("totalRequests").asInt,
            throughputReqPerSec = root.get("throughputPerSec").asDouble,
            errorRatePct = root.get("errorRatePct").asDouble,
            latency = latency
        )

        val metadata = if (root.has("metadata") && !root.get("metadata").isJsonNull) {
            root.getAsJsonObject("metadata").entrySet().associate {
                it.key to extractValue(it.value)
            }
        } else emptyMap()

        return RunResult(
            tool = "tulip",
            testName = root.get("testName")?.asString ?: "unknown",
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = root.get("status")?.asString ?: "completed",
            summary = summary,
            rawOutputPath = root.get("rawOutputPath")?.asString,
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
