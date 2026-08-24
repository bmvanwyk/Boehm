package io.boehm.adapters.jmeter

import io.boehm.model.Latency
import io.boehm.model.RunResult
import io.boehm.model.Stats
import io.boehm.model.Summary
import java.time.Instant
import java.util.UUID

/**
 * Parses JMeter CSV (JTL) output into a normalized [RunResult].
 *
 * JMeter writes one row per sample when using the `-l` flag. The CSV
 * has a header row; column order varies by save configuration, so the
 * parser resolves columns by name rather than by fixed index.
 *
 * Recognized columns (all optional except `elapsed`):
 *   - timeStamp   (epoch ms) — used for wall-clock duration
 *   - elapsed      (ms)      — response time per sample
 *   - success      (true/false) — error rate
 *   - responseCode — metadata
 *   - label        — test name
 *   - Latency      — alternative latency source
 *   - Connect      — metadata
 */
object JMeterParser {

    fun parse(rawCsv: String): RunResult {
        val lines = rawCsv.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 2) {
            throw IllegalArgumentException("JMeter CSV must have a header and at least one data row")
        }

        val header = parseCsvLine(lines[0])
        val colIndex = header.mapIndexed { i, name -> name.trim() to i }.toMap()

        val elapsedCol = colIndex["elapsed"]
            ?: throw IllegalArgumentException("Missing required column: elapsed")

        val successCol = colIndex["success"]
        val timestampCol = colIndex["timeStamp"]
        val latencyCol = colIndex["Latency"]
        val labelCol = colIndex["label"]
        val responseCodeCol = colIndex["responseCode"]

        val elapsedValues = mutableListOf<Double>()
        val latencyValues = mutableListOf<Double>()
        val timestamps = mutableListOf<Long>()
        var totalRequests = 0
        var failedRequests = 0
        val responseCodes = mutableMapOf<String, Int>()
        var testName = "unknown"

        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            totalRequests++

            val elapsed = fields[elapsedCol].toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid elapsed value at row $i: '${fields[elapsedCol]}'")
            elapsedValues.add(elapsed)

            if (latencyCol != null) {
                latencyValues.add(fields[latencyCol].toDoubleOrNull() ?: 0.0)
            }

            if (timestampCol != null) {
                fields[timestampCol].toLongOrNull()?.let { timestamps.add(it) }
            }

            val success = successCol?.let { fields.getOrNull(it)?.trim()?.lowercase() } ?: "true"
            if (success == "false") failedRequests++

            if (labelCol != null) {
                val label = fields.getOrNull(labelCol)?.trim() ?: ""
                if (label.isNotBlank() && testName == "unknown") testName = label
            }

            if (responseCodeCol != null) {
                val code = fields.getOrNull(responseCodeCol)?.trim() ?: ""
                if (code.isNotBlank()) responseCodes[code] = responseCodes.getOrDefault(code, 0) + 1
            }
        }

        val errorRatePct = if (totalRequests > 0) (failedRequests.toDouble() / totalRequests) * 100.0 else 0.0

        val durationSec = computeDurationSec(timestamps, elapsedValues)

        val throughput = if (durationSec > 0) totalRequests.toDouble() / durationSec else 0.0

        val latency = computeLatency(elapsedValues)

        val summary = Summary(
            durationSec = durationSec,
            totalRequests = totalRequests,
            throughputReqPerSec = throughput,
            errorRatePct = errorRatePct,
            latency = latency
        )

        val metadata = mutableMapOf<String, Any>()
        metadata["jmeter"] = true
        metadata["samples"] = totalRequests
        metadata["failed"] = failedRequests
        if (responseCodes.isNotEmpty()) metadata["response_codes"] = responseCodes
        if (latencyCol != null && latencyValues.isNotEmpty()) {
            metadata["avg_latency_ms"] = latencyValues.average()
        }

        return RunResult(
            tool = "jmeter",
            testName = testName,
            timestamp = Instant.now().toString(),
            runId = UUID.randomUUID().toString(),
            status = "completed",
            summary = summary,
            rawOutputPath = null,
            metadata = metadata
        )
    }

    private fun computeDurationSec(timestamps: MutableList<Long>, elapsedValues: MutableList<Double>): Int {
        if (timestamps.size >= 2) {
            val minTs = timestamps.min()
            val maxTs = timestamps.max()
            return ((maxTs - minTs) / 1000.0).coerceAtLeast(1.0).toInt()
        }
        // Fallback: sum of elapsed times (less accurate but better than 0)
        return (elapsedValues.sum() / 1000.0).coerceAtLeast(1.0).toInt()
    }

    private fun computeLatency(elapsed: List<Double>): Latency {
        if (elapsed.isEmpty()) {
            return Latency(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val sorted = elapsed.sorted()

        return Latency(
            minMs = sorted.first(),
            p50Ms = percentile(sorted, 50.0),
            p90Ms = percentile(sorted, 90.0),
            p95Ms = percentile(sorted, 95.0),
            p99Ms = percentile(sorted, 99.0),
            maxMs = sorted.last(),
            meanMs = Stats.mean(elapsed),
            stdevMs = Stats.stdev(elapsed)
        )
    }

    /**
     * Nearest-rank percentile method.
     * Uses the formula: ceil(P/100 * N) - 1, clamped to [0, N-1].
     */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val n = sorted.size
        if (n == 1) return sorted[0]

        val rank = kotlin.math.ceil((p / 100.0) * n).toInt() - 1
        val idx = rank.coerceIn(0, n - 1)
        return sorted[idx]
    }

    /**
     * Parses a single CSV line, handling quoted fields with embedded commas.
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in line) {
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes -> inQuotes = false
                ch == ',' && !inQuotes -> {
                    fields.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        fields.add(sb.toString())

        return fields
    }
}
