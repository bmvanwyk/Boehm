package io.boehm.adapters.gatling

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.model.Latency
import io.boehm.model.RunResult
import io.boehm.model.Summary
import java.time.Instant
import java.util.UUID

/**
 * Parses Gatling `js/global_stats.json` into a normalized [RunResult].
 *
 * Gatling (3.x) writes `global_stats.json` with string-encoded numbers:
 * ```
 * {
 *   "name": "Global Information",
 *   "numberOfRequests": {"total":"1000","ok":"998","ko":"2"},
 *   "minResponseTime": {"total":"10", ...},
 *   "maxResponseTime": {"total":"500", ...},
 *   "meanResponseTime": {"total":"50", ...},
 *   "standardDeviation": {"total":"20", ...},
 *   "percentiles1": {"total":"45", ...},  // 50th
 *   "percentiles2": {"total":"60", ...},  // 75th -> mapped to p90
 *   "percentiles3": {"total":"100", ...}, // 95th
 *   "percentiles4": {"total":"200", ...}, // 99th
 *   "meanNumberOfRequestsPerSecond": {"total":"33.3", ...}
 * }
 * ```
 * `total` may be "-" when ko == 0. Percentile mapping: p50=percentiles1,
 * p90≈percentiles2 (75th, closest available), p95=percentiles3, p99=percentiles4.
 * Throughput from `meanNumberOfRequestsPerSecond.total`; duration = total / throughput.
 */
object GatlingParser {

    fun parse(rawJson: String): RunResult {
        if (rawJson.isBlank()) throw IllegalArgumentException("Gatling JSON input is empty")
        val root = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON: ${e.message}")
        }

        // Gatling sometimes nests stats under "stats" key (stats.js) vs flat global_stats.json
        val stats = if (root.has("stats") && root.get("stats").isJsonObject) {
            root.getAsJsonObject("stats")
        } else {
            root
        }

        val numReq = stats.getAsJsonObject("numberOfRequests")
            ?: throw IllegalArgumentException("Missing numberOfRequests")
        val totalReq = parseLong(numReq.get("total")) ?: throw IllegalArgumentException("Missing numberOfRequests.total")
        val koReq = parseLong(numReq.get("ko")) ?: 0
        val okReq = parseLong(numReq.get("ok")) ?: (totalReq - koReq)

        if (totalReq == 0L) throw IllegalArgumentException("numberOfRequests.total is 0")

        val errorRatePct = (koReq.toDouble() / totalReq) * 100.0

        val throughput = stats.getAsJsonObject("meanNumberOfRequestsPerSecond")
            ?.let { parseDouble(it.get("total")) } ?: 0.0

        val durationSec = if (throughput > 0) {
            kotlin.math.round(totalReq.toDouble() / throughput).toInt().coerceAtLeast(1)
        } else {
            1
        }

        val latency = parseLatency(stats)

        val testName = stats.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "http-get"
        // Normalize "Global Information" to http-get for consistency with catalog
        val normalizedName = if (testName == "Global Information") "http-get" else testName

        val metadata = mutableMapOf<String, Any>()
        metadata["gatling"] = true
        metadata["total_requests"] = totalReq
        metadata["ok_requests"] = okReq
        metadata["ko_requests"] = koReq
        metadata["throughput"] = throughput
        // Gatling p90 is actually 75th percentile (closest available) — expose p75 and flag approximation
        val p75raw = stats.getAsJsonObject("percentiles2")?.let { parseDouble(it.get("total")) } ?: 0.0
        metadata["p75Ms"] = p75raw
        metadata["p90IsP75Approximation"] = true
        if (stats.has("group1")) metadata["group1"] = stats.getAsJsonObject("group1").toString()

        return RunResult(
            tool = "gatling",
            testName = normalizedName,
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = "completed",
            summary = Summary(
                durationSec = durationSec,
                totalRequests = totalReq.toInt(),
                throughputReqPerSec = throughput,
                errorRatePct = errorRatePct,
                latency = latency
            ),
            rawOutputPath = null,
            metadata = metadata
        )
    }

    private fun parseLatency(stats: JsonObject): Latency {
        fun field(name: String): Double {
            val obj = stats.getAsJsonObject(name) ?: return 0.0
            return parseDouble(obj.get("total")) ?: 0.0
        }

        val minMs = field("minResponseTime")
        val maxMs = field("maxResponseTime")
        val meanMs = field("meanResponseTime")
        val stdevMs = field("standardDeviation")
        val p50 = field("percentiles1")
        val p90approx = field("percentiles2") // 75th, closest to 90th available
        val p95 = field("percentiles3")
        val p99 = field("percentiles4")

        return Latency(
            minMs = minMs,
            p50Ms = p50,
            p90Ms = p90approx,
            p95Ms = p95,
            p99Ms = p99,
            maxMs = maxMs,
            meanMs = meanMs,
            stdevMs = stdevMs
        )
    }

    private fun parseDouble(elem: com.google.gson.JsonElement?): Double? {
        if (elem == null || elem.isJsonNull) return null
        return try {
            when {
                elem.isJsonPrimitive && elem.asJsonPrimitive.isNumber -> elem.asDouble
                elem.isJsonPrimitive && elem.asJsonPrimitive.isString -> {
                    val s = elem.asString.trim()
                    if (s == "-" || s.isEmpty()) null else s.toDoubleOrNull()
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun parseLong(elem: com.google.gson.JsonElement?): Long? {
        if (elem == null || elem.isJsonNull) return null
        return if (elem.isJsonPrimitive) {
            elem.asJsonPrimitive.let { p ->
                if (p.isNumber) p.asLong else p.asString.trim().let { if (it == "-" || it.isEmpty()) null else it.toLongOrNull() }
            }
        } else null
    }
}
