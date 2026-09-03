package io.boehm.adapters.k6

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.model.Latency
import io.boehm.model.RunResult
import io.boehm.model.Stats
import io.boehm.model.Summary
import java.time.Instant
import java.util.UUID

/**
 * Parses k6 NDJSON output (`k6 run --out json=<file>`) into a normalized [RunResult].
 *
 * k6 streams one JSON object per line. Metric data points are emitted with a
 * `metric` field and a `data` object holding the sample `value` (and sometimes a
 * `time` timestamp and `tags` map). The exact top-level `type` varies between k6
 * versions (`"Metric"` for metric definitions / `"Point"` for streaming samples),
 * so this parser accepts any line that carries a `metric` name plus a numeric
 * `data.value`, and ignores every other line (aggregated summary lines, group /
 * threshold / VU-tracking lines, and malformed input).
 *
 * Derivation rules (matching [io.boehm.adapters.jmeter.JMeterParser]):
 *   - Latency: collect every `http_req_duration` value, sort, and compute
 *     nearest-rank percentiles (p50/p90/p95/p99).
 *   - Total requests: sum the per-sample `http_reqs` counter values (each sample
 *     increments by 1). Falls back to the number of duration samples if no
 *     `http_reqs` points are present.
 *   - Error rate: count `http_req_duration` samples whose `status` tag is >= 400.
 *     Falls back to summing `http_req_failed` counter values when no status tags
 *     are present.
 *   - Throughput: totalRequests / durationSec.
 *   - Duration: span (max - min) of all `data.time` timestamps; coerced to at
 *     least 1 second so throughput is finite.
 */
object K6Parser {

    private const val P50 = 50.0
    private const val P90 = 90.0
    private const val P95 = 95.0
    private const val P99 = 99.0
    private const val MS_PER_SEC = 1000.0
    private const val MIN_DURATION_SEC = 1
    private const val HTTP_ERROR_STATUS_THRESHOLD = 400

    // Branchy by design: per-line skips and fallbacks across k6 versions, collected into one summary.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    fun parse(rawJsonl: String): RunResult {
        val lines = rawJsonl.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            throw IllegalArgumentException("k6 NDJSON input is empty")
        }

        val durationValues = mutableListOf<Double>()
        var totalRequests = 0
        var failedByStatus = 0
        var failedByCounter = 0
        val responseCodes = mutableMapOf<String, Int>()
        val timestamps = mutableListOf<Long>()
        var maxVus = 0
        var sawStatusTag = false

        for (line in lines) {
            val obj: JsonObject = try {
                JsonParser.parseString(line).asJsonObject
            } catch (_: Exception) {
                continue // skip malformed / non-JSON lines
            }

            // Must be a metric data point: needs a metric name and numeric value.
            if (!obj.has("metric")) continue
            val metric = obj.get("metric").asString
            val data = if (obj.has("data") && obj.get("data").isJsonObject) {
                obj.getAsJsonObject("data")
            } else {
                null
            }

            // Collect timestamps for wall-clock duration.
            data?.get("time")?.asString?.let { ts ->
                try {
                    timestamps.add(Instant.parse(ts).toEpochMilli())
                } catch (_: Exception) {
                    // ignore unparseable timestamps
                }
            }

            // Only points carrying a numeric value are samples.
            val valueElem = data?.get("value")
            val value: Double? = if (valueElem != null &&
                valueElem.isJsonPrimitive &&
                valueElem.asJsonPrimitive.isNumber
            ) {
                valueElem.asDouble
            } else {
                null
            }
            if (value == null) continue // aggregated metric definition line

            when (metric) {
                "http_req_duration" -> {
                    durationValues.add(value)
                    val status = data?.getAsJsonObject("tags")
                        ?.get("status")?.asString
                    if (status != null) {
                        sawStatusTag = true
                        responseCodes[status] = responseCodes.getOrDefault(status, 0) + 1
                        val code = status.toIntOrNull()
                        if (code != null && code >= HTTP_ERROR_STATUS_THRESHOLD) failedByStatus++
                    }
                }
                "http_reqs" -> {
                    totalRequests += value.toInt()
                }
                "http_req_failed" -> {
                    if (value > 0) failedByCounter += value.toInt()
                }
                "vus", "vus_max" -> {
                    val v = value.toInt()
                    if (v > maxVus) maxVus = v
                }
                // iterations / iteration_duration / data_received / data_sent and
                // any other metrics are intentionally ignored for summary math.
            }
        }

        if (durationValues.isEmpty()) {
            throw IllegalArgumentException(
                "No http_req_duration data points found in k6 output"
            )
        }

        if (totalRequests == 0) {
            // Fallback: one duration sample corresponds to one request.
            totalRequests = durationValues.size
        }

        val failedRequests = if (sawStatusTag) failedByStatus else failedByCounter

        val sorted = durationValues.sorted()
        val latency = Latency(
            minMs = sorted.first(),
            p50Ms = percentile(sorted, P50),
            p90Ms = percentile(sorted, P90),
            p95Ms = percentile(sorted, P95),
            p99Ms = percentile(sorted, P99),
            maxMs = sorted.last(),
            meanMs = Stats.mean(durationValues),
            stdevMs = Stats.stdev(durationValues)
        )

        val durationSec = if (timestamps.size >= 2) {
            val spanSec = (timestamps.max() - timestamps.min()) / MS_PER_SEC
            spanSec.toInt().coerceAtLeast(MIN_DURATION_SEC)
        } else {
            // No usable timestamps: avoid divide-by-zero in throughput.
            1
        }

        val errorRatePct = if (totalRequests > 0) {
            (failedRequests.toDouble() / totalRequests) * 100.0
        } else {
            0.0
        }
        val throughput = if (durationSec > 0) {
            totalRequests.toDouble() / durationSec
        } else {
            0.0
        }

        val metadata = mutableMapOf<String, Any>()
        metadata["k6"] = true
        metadata["samples"] = durationValues.size
        metadata["total_requests"] = totalRequests
        metadata["failed_requests"] = failedRequests
        metadata["max_vus"] = maxVus
        if (responseCodes.isNotEmpty()) metadata["response_codes"] = responseCodes

        return RunResult(
            tool = "k6",
            testName = "http-get",
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = "completed",
            summary = Summary(
                durationSec = durationSec,
                totalRequests = totalRequests,
                throughputReqPerSec = throughput,
                errorRatePct = errorRatePct,
                latency = latency
            ),
            rawOutputPath = null,
            metadata = metadata
        )
    }

    /**
     * Nearest-rank percentile method (matches [io.boehm.adapters.jmeter.JMeterParser.percentile]).
     * Uses the formula: ceil(P/100 * N) - 1, clamped to [0, N-1].
     */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val n = sorted.size
        if (n == 1) return sorted[0]
        val rank = kotlin.math.ceil((p / 100.0) * n).toInt() - 1
        val idx = rank.coerceIn(0, n - 1)
        return sorted[idx]
    }
}
