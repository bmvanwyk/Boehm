package io.boehm.model

data class RunResult(
    val tool: String,
    val testName: String,
    val timestamp: String,
    val runId: String,
    val status: String,
    val summary: Summary?,
    val rawOutputPath: String?,
    val metadata: Map<String, Any> = emptyMap()
)

data class Summary(
    val durationSec: Int,
    val totalRequests: Int,
    val throughputReqPerSec: Double,
    val errorRatePct: Double,
    val latency: Latency
)

data class Latency(
    val minMs: Double,
    val p50Ms: Double,
    val p90Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double
)
