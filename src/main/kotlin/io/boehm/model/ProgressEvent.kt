package io.boehm.model

data class ProgressEvent(
    val type: String,
    val timestampSec: Double,
    val progressPct: Double,
    val currentStage: String,
    val rollingSummary: Summary?,
    val message: String?
)

data class StageProgress(
    val status: String,
    val durationSec: Int? = null,
    val elapsedSec: Int? = null,
    val estimatedRemainingSec: Int? = null
)

data class ServerStatus(
    val status: String,
    val uptimeSec: Long,
    val queueDepth: Int,
    val currentlyRunning: RunningInfo?,
    val queuedRuns: List<QueuedRunInfo>,
    val serverVersion: String
)

data class RunningInfo(
    val runId: String,
    val tool: String,
    val testName: String,
    val progressPct: Double,
    val currentStage: String,
    val elapsedSec: Int,
    val estimatedRemainingSec: Int
)

data class QueuedRunInfo(
    val runId: String,
    val tool: String,
    val testName: String,
    val position: Int
)

data class RunProgress(
    val runId: String,
    val status: String,
    val progressPct: Double,
    val currentStage: String,
    val stageProgress: Map<String, StageProgress>,
    val rollingSummary: Summary?
)

data class BaselineComparison(
    val verdict: String,
    val deltas: Map<String, Any>,
    val summary: String
)

data class ValidationError(
    val field: String,
    val message: String
)

enum class TestType(val label: String) {
    HTTP("http"),
    DATABASE("database"),
    CUSTOM("custom")
}
