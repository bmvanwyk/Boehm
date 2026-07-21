# Phase 1: MCP Server + Tulip Adapter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working MCP server that accepts `run_test` calls, shells out to Tulip CLI, persists results to SQLite, and returns structured data.

**Architecture:** Kotlin/JVM JSON-RPC server over stdio with bearer token auth. 5 MCP tools (`list_adapters`, `run_test`, `get_run`, `server_status`, `get_run_progress`). Tulip adapter shells out to CLI, parses native JSON into normalized `RunResult`. Serial run queue prevents overlapping tests.

**Tech Stack:** Kotlin, Gradle, SQLite (sqlite-jdbc), Gson for JSON, JUnit 5, Tulip CLI

---

## File Structure

```
Boehm/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/kotlin/io/boehm/
│   │   ├── Main.kt                  # Entry point: stdio read loop
│   │   ├── auth/
│   │   │   └── AuthHandler.kt       # Bearer token validation
│   │   ├── core/
│   │   │   ├── McpHandler.kt        # JSON-RPC message dispatcher
│   │   │   ├── Orchestrator.kt      # Test plan routing, run lifecycle
│   │   │   ├── Scheduler.kt         # Serial run queue
│   │   │   └── Store.kt             # SQLite operations
│   │   ├── model/
│   │   │   ├── TestPlan.kt          # Input: how to run a test
│   │   │   ├── RunResult.kt         # Output: normalized result + summary
│   │   │   └── ProgressEvent.kt     # Live progress during execution
│   │   └── adapters/
│   │       ├── PerfToolAdapter.kt   # Interface all adapters implement
│   │       └── tulip/
│   │           ├── TulipAdapter.kt  # CLI exec, output capture
│   │           └── TulipParser.kt   # Parse Tulip JSON → RunResult
│   └── test/kotlin/io/boehm/
│       ├── adapter/
│       │   └── TulipParserTest.kt
│       ├── auth/
│       │   └── AuthHandlerTest.kt
│       ├── core/
│       │   ├── McpHandlerTest.kt
│       │   ├── OrchestratorTest.kt
│       │   ├── SchedulerTest.kt
│       │   └── StoreTest.kt
│       ├── fixtures/
│       │   └── tulip-sample-output.json  # Stored Tulip CLI output for parser tests
│       └── integration/
│           └── TulipIntegrationTest.kt
```

---

### Task 1: Gradle project scaffold + model classes

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `src/main/kotlin/io/boehm/model/TestPlan.kt`
- Create: `src/main/kotlin/io/boehm/model/RunResult.kt`
- Create: `src/main/kotlin/io/boehm/model/ProgressEvent.kt`
- Create: `src/test/kotlin/io/boehm/model/RunResultTest.kt`

- [ ] **Step 1: Write the failing test for model classes**

```kotlin
// src/test/kotlin/io/boehm/model/RunResultTest.kt
package io.boehm.model

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunResultTest {
    private val gson = Gson()

    @Test
    fun `RunResult serializes and deserializes correctly`() {
        val original = RunResult(
            tool = "tulip",
            testName = "http-get",
            timestamp = "2026-07-20T10:00:00Z",
            runId = "test-uuid",
            status = "completed",
            summary = Summary(
                durationSec = 30,
                totalRequests = 2850,
                throughputReqPerSec = 95.0,
                errorRatePct = 0.0,
                latency = Latency(
                    minMs = 2.1, p50Ms = 8.5, p90Ms = 22.3,
                    p95Ms = 35.0, p99Ms = 68.1, maxMs = 210.0
                )
            ),
            rawOutputPath = "/tmp/output.json",
            metadata = emptyMap()
        )

        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, RunResult::class.java)

        assertEquals(original.runId, deserialized.runId)
        assertEquals(original.summary.latency.p95Ms, deserialized.summary.latency.p95Ms, 0.001)
        assertEquals(original.summary.throughputReqPerSec, deserialized.summary.throughputReqPerSec, 0.001)
    }

    @Test
    fun `RunResult with null summary serializes correctly`() {
        val result = RunResult(
            tool = "tulip", testName = "http-get",
            timestamp = "2026-07-20T10:00:00Z", runId = "uuid",
            status = "queued", summary = null,
            rawOutputPath = null, metadata = emptyMap()
        )
        val json = Gson().toJson(result)
        assertTrue(json.contains("\"status\":\"queued\""))
        assertTrue(json.contains("\"summary\":null"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "io.boehm.model.RunResultTest" 2>&1 | head -20`
Expected: FAIL — classes not found

- [ ] **Step 3: Write model classes**

```kotlin
// src/main/kotlin/io/boehm/model/TestPlan.kt
package io.boehm.model

data class TestPlan(
    val type: String,
    val targetUrl: String,
    val ratePerSec: Int,
    val durationSec: Int,
    val warmupSec: Int = 5,
    val timeoutSec: Int = 60
)
```

```kotlin
// src/main/kotlin/io/boehm/model/RunResult.kt
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
```

```kotlin
// src/main/kotlin/io/boehm/model/ProgressEvent.kt
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
```

```kotlin
// src/main/kotlin/io/boehm/model/ValidationError.kt
package io.boehm.model

data class ValidationError(
    val field: String,
    val message: String
)
```

```kotlin
// src/main/kotlin/io/boehm/model/TestType.kt
package io.boehm.model

enum class TestType(val label: String) {
    HTTP("http"),
    DATABASE("database"),
    CUSTOM("custom")
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.model.RunResultTest" --tests "io.boehm.model.*" 2>&1 | tail -10`
Expected: PASS — all model tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/model/ src/test/kotlin/io/boehm/model/
git add build.gradle.kts settings.gradle.kts
git commit -m "Task 1: gradle scaffold + model data classes"
```

---

### Task 2: SQLite store

**Files:**
- Create: `src/main/kotlin/io/boehm/core/Store.kt`
- Create: `src/test/kotlin/io/boehm/core/StoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/boehm/core/StoreTest.kt
package io.boehm.core

import io.boehm.model.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class StoreTest {
    private lateinit var store: Store
    private val dbPath = "/tmp/boehm-test-${UUID.randomUUID()}.db"

    @BeforeEach
    fun setUp() {
        store = Store(dbPath)
    }

    @AfterEach
    fun tearDown() {
        File(dbPath).delete()
    }

    @Test
    fun `schema initializes and tables exist`() {
        // Should not throw
    }

    @Test
    fun `insert and retrieve adapter`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val adapters = store.listAdapters()
        assertEquals(1, adapters.size)
        assertEquals("tulip", adapters[0].name)
    }

    @Test
    fun `insert and retrieve scenario`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        assertNotNull(scenarioId)

        val scenario = store.getScenario("tulip", "test-1")
        assertNotNull(scenario)
        assertEquals(scenarioId, scenario!!.id)
    }

    @Test
    fun `insert and retrieve run`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        val runId = store.insertRun(scenarioId, "tulip")
        assertNotNull(runId)

        val run = store.getRun(runId!!)
        assertNotNull(run)
        assertEquals("pending", run!!.status)
    }

    @Test
    fun `update run status`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        val runId = store.insertRun(scenarioId!!, "tulip")

        store.updateRunStatus(runId!!, "completed", """{"durationSec":30}""")
        val run = store.getRun(runId)
        assertEquals("completed", run!!.status)
        assertNotNull(run.completedAt)
    }

    @Test
    fun `list runs by scenario`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":30}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)
        store.insertRun(scenarioId!!, "tulip")
        store.insertRun(scenarioId, "tulip")

        val runs = store.listRuns(scenarioId)
        assertEquals(2, runs.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "io.boehm.core.StoreTest" 2>&1 | tail -15`
Expected: FAIL — Store class not found

- [ ] **Step 3: Write Store.kt**

```kotlin
// src/main/kotlin/io/boehm/core/Store.kt
package io.boehm.core

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

data class AdapterRow(val name: String, val supportedTypes: String, val version: String, val toolVersions: String)
data class ScenarioRow(val id: String, val tool: String, val name: String, val testPlan: String, val createdAt: String)
data class RunRow(val id: String, val scenarioId: String, val tool: String, val status: String,
                  val createdAt: String, val startedAt: String?, val completedAt: String?,
                  val error: String?, val summary: String?, val rawOutputPath: String?,
                  val metadata: String?)

class Store(private val dbPath: String) {
    private val conn: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        c.createStatement().execute("PRAGMA journal_mode=WAL")
        initSchema(c)
        c
    }

    private fun initSchema(c: Connection) {
        val stmt = c.createStatement()
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS adapters (
                name TEXT PRIMARY KEY,
                supported_types TEXT NOT NULL,
                adapter_version TEXT NOT NULL,
                tool_versions TEXT NOT NULL
            )
        """)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS test_scenarios (
                id TEXT PRIMARY KEY,
                tool TEXT NOT NULL REFERENCES adapters(name),
                name TEXT NOT NULL,
                test_plan JSON NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                UNIQUE(tool, name)
            )
        """)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY,
                scenario_id TEXT NOT NULL REFERENCES test_scenarios(id),
                tool TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                started_at TEXT,
                completed_at TEXT,
                error TEXT,
                summary JSON,
                raw_output_path TEXT,
                metadata JSON DEFAULT '{}'
            )
        """)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
        """)
        stmt.close()
    }

    fun insertAdapter(name: String, supportedTypes: String, version: String, toolVersions: String) {
        val ps = conn.prepareStatement("INSERT OR IGNORE INTO adapters VALUES (?, ?, ?, ?)")
        ps.setString(1, name)
        ps.setString(2, supportedTypes)
        ps.setString(3, version)
        ps.setString(4, toolVersions)
        ps.execute()
        ps.close()
    }

    fun listAdapters(): List<AdapterRow> {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT name, supported_types, adapter_version, tool_versions FROM adapters")
        val result = mutableListOf<AdapterRow>()
        while (rs.next()) {
            result.add(AdapterRow(rs.getString("name"), rs.getString("supported_types"),
                rs.getString("adapter_version"), rs.getString("tool_versions")))
        }
        rs.close()
        stmt.close()
        return result
    }

    fun insertScenario(tool: String, name: String, testPlan: String): String? {
        val id = UUID.randomUUID().toString()
        val ps = conn.prepareStatement("INSERT OR IGNORE INTO test_scenarios (id, tool, name, test_plan) VALUES (?, ?, ?, ?)")
        ps.setString(1, id)
        ps.setString(2, tool)
        ps.setString(3, name)
        ps.setString(4, testPlan)
        val rows = ps.executeUpdate()
        ps.close()
        return if (rows > 0) id else getScenario(tool, name)?.id
    }

    fun getScenario(tool: String, name: String): ScenarioRow? {
        val ps = conn.prepareStatement("SELECT id, tool, name, test_plan, created_at FROM test_scenarios WHERE tool = ? AND name = ?")
        ps.setString(1, tool)
        ps.setString(2, name)
        val rs = ps.executeQuery()
        val result = if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
            rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
        rs.close()
        ps.close()
        return result
    }

    fun insertRun(scenarioId: String, tool: String): String? {
        val id = UUID.randomUUID().toString()
        val ps = conn.prepareStatement("INSERT INTO runs (id, scenario_id, tool) VALUES (?, ?, ?)")
        ps.setString(1, id)
        ps.setString(2, scenarioId)
        ps.setString(3, tool)
        ps.execute()
        ps.close()
        return id
    }

    fun getRun(runId: String): RunRow? {
        val ps = conn.prepareStatement("SELECT * FROM runs WHERE id = ?")
        ps.setString(1, runId)
        val rs = ps.executeQuery()
        val result = if (rs.next()) RunRow(
            rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
            rs.getString("status"), rs.getString("created_at"),
            rs.getString("started_at"), rs.getString("completed_at"),
            rs.getString("error"), rs.getString("summary"),
            rs.getString("raw_output_path"), rs.getString("metadata")
        ) else null
        rs.close()
        ps.close()
        return result
    }

    fun updateRunStatus(runId: String, status: String, summary: String? = null,
                        error: String? = null, rawOutputPath: String? = null) {
        val now = Instant.now().toString()
        val ps = conn.prepareStatement("""
            UPDATE runs SET status = ?, summary = ?, error = ?, raw_output_path = ?,
                started_at = CASE WHEN ? = 'running' AND started_at IS NULL THEN ? ELSE started_at END,
                completed_at = CASE WHEN ? = 'completed' OR ? = 'failed' THEN ? ELSE completed_at END
            WHERE id = ?
        """)
        ps.setString(1, status)
        ps.setString(2, summary)
        ps.setString(3, error)
        ps.setString(4, rawOutputPath)
        ps.setString(5, status); ps.setString(6, now)
        ps.setString(7, status); ps.setString(8, status); ps.setString(9, now)
        ps.setString(10, runId)
        ps.execute()
        ps.close()
    }

    fun listRuns(scenarioId: String): List<RunRow> {
        val ps = conn.prepareStatement("SELECT * FROM runs WHERE scenario_id = ? ORDER BY created_at DESC")
        ps.setString(1, scenarioId)
        val rs = ps.executeQuery()
        val result = mutableListOf<RunRow>()
        while (rs.next()) {
            result.add(RunRow(rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                rs.getString("status"), rs.getString("created_at"),
                rs.getString("started_at"), rs.getString("completed_at"),
                rs.getString("error"), rs.getString("summary"),
                rs.getString("raw_output_path"), rs.getString("metadata")))
        }
        rs.close()
        ps.close()
        return result
    }

    fun getPendingOrRunningRun(): RunRow? {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("""
            SELECT * FROM runs WHERE status IN ('pending', 'queued', 'running') 
            ORDER BY created_at ASC LIMIT 1
        """)
        val result = if (rs.next()) RunRow(
            rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
            rs.getString("status"), rs.getString("created_at"),
            rs.getString("started_at"), rs.getString("completed_at"),
            rs.getString("error"), rs.getString("summary"),
            rs.getString("raw_output_path"), rs.getString("metadata")
        ) else null
        rs.close()
        stmt.close()
        return result
    }

    fun getQueuedRuns(): List<RunRow> {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT * FROM runs WHERE status = 'queued' ORDER BY created_at ASC")
        val result = mutableListOf<RunRow>()
        while (rs.next()) {
            result.add(RunRow(rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                rs.getString("status"), rs.getString("created_at"),
                rs.getString("started_at"), rs.getString("completed_at"),
                rs.getString("error"), rs.getString("summary"),
                rs.getString("raw_output_path"), rs.getString("metadata")))
        }
        rs.close()
        stmt.close()
        return result
    }

    fun close() {
        if (::conn.isInitialized) conn.close()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.core.StoreTest" 2>&1 | tail -15`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Store.kt src/test/kotlin/io/boehm/core/StoreTest.kt
git commit -m "Task 2: SQLite store with schema, CRUD for adapters/scenarios/runs"
```

---

### Task 3: Auth handler

**Files:**
- Create: `src/main/kotlin/io/boehm/auth/AuthHandler.kt`
- Create: `src/test/kotlin/io/boehm/auth/AuthHandlerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/boehm/auth/AuthHandlerTest.kt
package io.boehm.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthHandlerTest {
    @Test
    fun `valid token returns true`() {
        val handler = AuthHandler()
        val token = handler.createToken("test-token")
        assertTrue(handler.validateToken("test-token"))
    }

    @Test
    fun `invalid token returns false`() {
        val handler = AuthHandler()
        handler.createToken("real-token")
        assertFalse(handler.validateToken("fake-token"))
    }

    @Test
    fun `missing token returns false`() {
        val handler = AuthHandler()
        assertFalse(handler.validateToken(null))
    }

    @Test
    fun `empty token returns false`() {
        val handler = AuthHandler()
        assertFalse(handler.validateToken(""))
    }

    @Test
    fun `multiple tokens can be created and validated`() {
        val handler = AuthHandler()
        handler.createToken("token-1")
        handler.createToken("token-2")
        assertTrue(handler.validateToken("token-1"))
        assertTrue(handler.validateToken("token-2"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "io.boehm.auth.AuthHandlerTest" 2>&1 | tail -10`
Expected: FAIL — AuthHandler not found

- [ ] **Step 3: Write AuthHandler.kt**

```kotlin
// src/main/kotlin/io/boehm/auth/AuthHandler.kt
package io.boehm.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AuthHandler {
    private val tokens = mutableSetOf<String>()

    fun createToken(token: String): String {
        tokens.add(hashToken(token))
        return token
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return tokens.contains(hashToken(token))
    }

    fun loadFromConfig(tokens: List<String>) {
        this.tokens.addAll(tokens.map { hashToken(it) })
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(token.toByteArray()))
    }
}
```

Note: In-memory set with SHA-256 hashing for Phase 1. Tokens are provided via `--token` CLI arg or `BOEHM_TOKEN` env var. Future phases will use bcrypt and persistent storage.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.auth.AuthHandlerTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/auth/ src/test/kotlin/io/boehm/auth/
git commit -m "Task 3: bearer token auth handler with SHA-256 hashing"
```

---

### Task 4: Adapter interface + Tulip JSON parser

**Files:**
- Create: `src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt`
- Create: `src/main/kotlin/io/boehm/adapters/tulip/TulipParser.kt`
- Create: `src/test/fixtures/tulip-sample-output.json`
- Create: `src/test/kotlin/io/boehm/adapter/TulipParserTest.kt`

- [ ] **Step 1: Write the failing test and fixture**

```kotlin
// src/test/kotlin/io/boehm/adapter/TulipParserTest.kt
package io.boehm.adapter

import io.boehm.adapters.tulip.TulipParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipParserTest {
    @Test
    fun `parse valid Tulip JSON output returns correct RunResult`() {
        val json = File("src/test/fixtures/tulip-sample-output.json").readText()
        val result = TulipParser.parse(json)

        assertEquals("tulip", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertEquals(30, result.summary!!.durationSec)
        assertTrue(result.summary!!.totalRequests > 0)
        assertTrue(result.summary!!.throughputReqPerSec > 0)
        assertTrue(result.summary!!.latency.p50Ms > 0)
        assertTrue(result.summary!!.latency.p99Ms > 0)
    }

    @Test
    fun `parse with missing fields throws`() {
        assertThrows<Exception> {
            TulipParser.parse("{}")
        }
    }

    @Test
    fun `parse with invalid JSON throws`() {
        assertThrows<Exception> {
            TulipParser.parse("not json")
        }
    }
}
```

```json
// src/test/fixtures/tulip-sample-output.json
{
  "version": "2.3.4",
  "timestamp": "2026-07-21_10:00:00",
  "java": {
    "jvm.system.properties": {
      "java.vendor": "Eclipse Adoptium",
      "java.version": "21.0.7",
      "os.name": "Linux",
      "os.arch": "amd64"
    },
    "jvm.runtime.options": ["-Xms512m", "-Xmx512m"]
  },
  "config": {
    "actions": {
      "output_filename": "/tmp/boehm-output.json",
      "user_params": {"url": "https://httpbin.org/get"}
    }
  },
  "results": [
    {
      "context_name": "Context-0",
      "context_id": 0,
      "bm_name": "http-get",
      "bm_id": 1,
      "row_id": 0,
      "num_users": 100,
      "num_threads": 8,
      "queue_length": 100,
      "test_begin": "2026-07-21_10:00:00",
      "test_end": "2026-07-21_10:00:30",
      "duration": 30.0,
      "num_actions": 2850,
      "num_failed": 0,
      "avg_aps": 95.0,
      "avg_rt": 8500000.0,
      "sd_rt": 5000000.0,
      "min_rt": 2100000.0,
      "max_rt": 210000000.0,
      "percentiles_rt": {
        "50.0": 8500000.0,
        "75.0": 15000000.0,
        "90.0": 22300000.0,
        "95.0": 35000000.0,
        "99.0": 68100000.0
      },
      "hdr_histogram_rt": "HIST",
      "user_actions": {}
    }
  ]
}
```

Note: This is Tulip's native output format — a top-level JSON object containing a `results[]` array. Latency values (`min_rt`, `max_rt`, `percentiles_rt`) are in **nanoseconds**. The parser divides by 1,000,000 to convert to milliseconds for the normalized `RunResult`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "io.boehm.adapter.TulipParserTest" 2>&1 | tail -10`
Expected: FAIL — TulipParser not found

- [ ] **Step 3: Write the interface and parser**

```kotlin
// src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt
package io.boehm.adapters

import io.boehm.model.*

interface PerfToolAdapter {
    val name: String
    val supportedTestTypes: List<TestType>
    val version: String
    val toolVersions: List<String>

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan): RunResult
}
```

```kotlin
// src/main/kotlin/io/boehm/adapters/tulip/TulipParser.kt
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.adapter.TulipParserTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/adapters/ src/test/kotlin/io/boehm/adapter/ src/test/fixtures/
git commit -m "Task 4: adapter interface + Tulip JSON parser with fixture tests"
```

---

### Task 5: Tulip CLI adapter

**Files:**
- Create: `src/main/kotlin/io/boehm/adapters/tulip/TulipAdapter.kt`
- Create: `src/test/kotlin/io/boehm/adapter/TulipAdapterTest.kt`
- Create: `src/test/fixtures/mock-tulip.sh`

- [ ] **Step 1: Write the test and mock CLI script**

```kotlin
// src/test/kotlin/io/boehm/adapter/TulipAdapterTest.kt
package io.boehm.adapter

import io.boehm.adapters.tulip.TulipAdapter
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipAdapterTest {
    private val mockScript = File("src/test/fixtures/mock-tulip.sh").absolutePath

    @Test
    fun `adapter name and version are set`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        assertEquals("tulip", adapter.name)
        assertTrue(adapter.version.isNotBlank())
    }

    @Test
    fun `run executes CLI and returns RunResult`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 100,
            durationSec = 5,
            warmupSec = 1
        )
        val result = adapter.run(plan)
        assertEquals("tulip", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertTrue(result.summary!!.totalRequests > 0)
    }

    @Test
    fun `validate rejects missing targetUrl`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "",
            ratePerSec = 100,
            durationSec = 30
        )
        val errors = adapter.validate(plan)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `validate rejects zero duration`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://example.com",
            ratePerSec = 100,
            durationSec = 0
        )
        val errors = adapter.validate(plan)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `validate accepts valid plan`() {
        val adapter = TulipAdapter(tulipCommand = mockScript)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 100,
            durationSec = 10,
            warmupSec = 2
        )
        val errors = adapter.validate(plan)
        assertTrue(errors.isEmpty())
    }
}
```

```bash
#!/bin/bash
# src/test/fixtures/mock-tulip.sh
# Mock Tulip CLI: accepts --config <file>, writes sample JSON to output_filename.
# Mirrors real Tulip behavior: reads config, writes results to configured output file.
set -e

CONFIG_FILE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --config) CONFIG_FILE="$2"; shift 2 ;;
        *) shift ;;
    esac
done

if [ -z "$CONFIG_FILE" ]; then
    echo "mock-tulip: missing --config" >&2
    exit 1
fi

exec python3 -c "
import json, os, sys

with open('$CONFIG_FILE') as f:
    cfg = json.load(f)

output_file = cfg['actions']['output_filename']
os.makedirs(os.path.dirname(output_file) or '.', exist_ok=True)

# Write output in real Tulip format: top-level object with results[] array
result = {
    'version': '2.3.4',
    'timestamp': '2026-07-21_10:00:00',
    'java': {
        'jvm.system.properties': {
            'java.vendor': 'Eclipse Adoptium',
            'java.version': '21.0.7',
            'os.name': 'Linux',
            'os.arch': 'amd64'
        },
        'jvm.runtime.options': ['-Xms512m', '-Xmx512m']
    },
    'config': cfg,
    'results': [{
        'context_name': 'default',
        'context_id': 0,
        'bm_name': 'boehm-benchmark',
        'bm_id': 1,
        'row_id': 0,
        'num_users': 10,
        'num_threads': 2,
        'queue_length': 100,
        'workflow_name': '',
        'test_begin': '2026-07-21_10:00:00',
        'test_end': '2026-07-21_10:00:05',
        'duration': 5,
        'num_actions': 500,
        'num_failed': 0,
        'avg_aps': 100.0,
        'avg_rt': 8500000.0,
        'sd_rt': 5000000.0,
        'min_rt': 2100000.0,
        'max_rt': 210000000.0,
        'percentiles_rt': {
            '50.0': 8500000.0,
            '75.0': 15000000.0,
            '90.0': 22300000.0,
            '95.0': 35000000.0,
            '99.0': 68100000.0
        },
        'hdr_histogram_rt': 'HIST',
        'user_actions': {}
    }]
}

with open(output_file, 'w') as f:
    json.dump(result, f, indent=2)

sys.exit(0)
"
```

- [ ] **Step 2: Make mock script executable and verify tests fail**

Run: `chmod +x src/test/fixtures/mock-tulip.sh && ./gradlew test --tests "io.boehm.adapter.TulipAdapterTest" 2>&1 | tail -10`
Expected: FAIL — TulipAdapter not found

- [ ] **Step 3: Write TulipAdapter**

```kotlin
// src/main/kotlin/io/boehm/adapters/tulip/TulipAdapter.kt
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
                        mapOf("id" to 3, "weight" to 0)
                    )
                )
            )
        )

        return JsonParser.parseString(
            GsonBuilder().setPrettyPrinting().create().toJson(config)
        ).asJsonObject.toString()
    }
}
```

Key design notes:
- Tulip CLI only accepts `--config <file>` (no per-benchmark CLI flags like `--type` or `--target`)
- The adapter generates a temporary Tulip config JSON file with the test plan parameters mapped to Tulip's config schema (`actions.output_filename`, `actions.user_params.url`, `benchmarks` with `aps_rate`, `warmup_duration1`, `benchmark_duration`, etc.)
- Post-benchmark HTML report generation may fail (exit code 1) even when the benchmark succeeded, so the adapter reads the output file regardless of exit code

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.adapter.TulipAdapterTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/adapters/tulip/TulipAdapter.kt
git add src/test/kotlin/io/boehm/adapter/TulipAdapterTest.kt
git add src/test/fixtures/mock-tulip.sh
git commit -m "Task 5: Tulip CLI adapter with validation and mock CLI tests"
```

---

### Task 6: MCP handler

**Files:**
- Create: `src/main/kotlin/io/boehm/core/McpHandler.kt`
- Create: `src/test/kotlin/io/boehm/core/McpHandlerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/boehm/core/McpHandlerTest.kt
package io.boehm.core

import io.boehm.auth.AuthHandler
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpHandlerTest {
    private lateinit var handler: McpHandler
    private lateinit var authHandler: AuthHandler
    private lateinit var orchestrator: Orchestrator

    @BeforeEach
    fun setUp() {
        authHandler = AuthHandler()
        authHandler.createToken("test-token")
        val store = Store(":memory:")
        handler = McpHandler(authHandler, store)
    }

    @Test
    fun `initialize with valid token returns success`() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}"""
        )
        assertTrue(response.contains("\"result\""))
    }

    @Test
    fun `initialize without token returns error`() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        )
        assertTrue(response.contains("\"error\""))
        assertTrue(response.contains("-32005"))
    }

    @Test
    fun `tools_called before initialize returns error`() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}"""
        )
        assertTrue(response.contains("\"error\""))
    }

    @Test
    fun `list_adapters returns adapters`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_adapters","arguments":{}}}"""
        )
        assertTrue(response.contains("\"adapters\""))
    }

    @Test
    fun `run_test with invalid plan returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{
                "tool":"nonexistent","test_name":"test","test_plan":{"type":"http"}}
            }}
        """.trimIndent())
        assertTrue(response.contains("\"error\"") || response.contains("-32000"))
    }

    @Test
    fun `unknown tool call returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}"""
        )
        assertTrue(response.contains("\"error\""))
    }

    @Test
    fun `server_status returns status`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        handler.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_adapters","arguments":{}}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"server_status","arguments":{}}}"""
        )
        assertTrue(response.contains("\"status\""))
    }

    @Test
    fun `get_run with missing id returns error`() {
        handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_run","arguments":{}}}"""
        )
        assertTrue(response.contains("\"error\""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "io.boehm.core.McpHandlerTest" 2>&1 | tail -15`
Expected: FAIL — McpHandler not found

- [ ] **Step 3: Write McpHandler**

```kotlin
// src/main/kotlin/io/boehm/core/McpHandler.kt
package io.boehm.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.boehm.auth.AuthHandler
import io.boehm.model.*
import java.time.Instant

data class McpError(val code: Int, val message: String, val data: Map<String, Any>? = null)

class McpHandler(
    private val authHandler: AuthHandler,
    private val store: Store,
    private val serverVersion: String = "0.1.0",
    private val startupTime: Long = System.currentTimeMillis()
) {
    private val gson = Gson()
    private var initialized = false
    private var orchestrator: Orchestrator? = null

    fun handle(request: String): String {
        return try {
            val json = JsonParser.parseString(request).asJsonObject
            val id = json.get("id")
            val method = json.get("method")?.asString ?: return errorResponse(id, -32600, "Invalid request")

            when (method) {
                "initialize" -> handleInitialize(json, id)
                "tools/call" -> handleToolCall(json, id)
                "ping" -> gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "result" to emptyMap<String, Any>()))
                else -> errorResponse(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"""
        }
    }

    private fun handleInitialize(json: JsonObject, id: Any): String {
        val params = json.getAsJsonObject("params") ?: return errorResponse(id, -32602, "Invalid params")
        val token = params.get("auth_token")?.asString

        if (!authHandler.validateToken(token)) {
            return errorResponse(id, -32005, "Authentication failed")
        }

        initialized = true
        if (orchestrator == null) {
            orchestrator = Orchestrator(store)
        }

        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "protocolVersion" to "2024-11-05",
                "serverInfo" to mapOf("name" to "boehm", "version" to serverVersion)
            )
        ))
    }

    private fun handleToolCall(json: JsonObject, id: Any): String {
        if (!initialized) {
            return errorResponse(id, -32006, "Not initialized. Call initialize first.")
        }

        val params = json.getAsJsonObject("params") ?: return errorResponse(id, -32602, "Invalid params")
        val toolName = params.get("name")?.asString ?: return errorResponse(id, -32602, "Missing tool name")
        val args = params.getAsJsonObject("arguments") ?: JsonObject()

        return when (toolName) {
            "list_adapters" -> handleListAdapters(id)
            "run_test" -> handleRunTest(args, id)
            "get_run" -> handleGetRun(args, id)
            "server_status" -> handleServerStatus(id)
            "get_run_progress" -> handleGetRunProgress(args, id)
            else -> errorResponse(id, -32602, "Unknown tool: $toolName")
        }
    }

    private fun handleListAdapters(id: Any): String {
        val adapters = store.listAdapters().map {
            mapOf("name" to it.name, "supported_types" to it.supportedTypes, "version" to it.version)
        }
        return gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "result" to mapOf("adapters" to adapters)))
    }

    private fun handleRunTest(args: JsonObject, id: Any): String {
        val tool = args.get("tool")?.asString ?: return errorResponse(id, -32001, "Missing tool")
        val testName = args.get("test_name")?.asString ?: return errorResponse(id, -32001, "Missing test_name")
        val testPlanJson = args.getAsJsonObject("test_plan") ?: return errorResponse(id, -32001, "Missing test_plan")

        val plan = TestPlan(
            type = testPlanJson.get("type")?.asString ?: "http",
            targetUrl = testPlanJson.get("target_url")?.asString ?: "",
            ratePerSec = testPlanJson.get("rate_per_sec")?.asInt ?: 100,
            durationSec = testPlanJson.get("duration_sec")?.asInt ?: 30,
            warmupSec = testPlanJson.get("warmup_sec")?.asInt ?: 5,
            timeoutSec = testPlanJson.get("timeout_sec")?.asInt ?: 60
        )

        val orchestrator = orchestrator!!
        val runId = orchestrator.submitRun(tool, testName, plan)
        if (runId == null) {
            return errorResponse(id, -32000, "Adapter not found: $tool",
                mapOf("available_adapters" to store.listAdapters().map { it.name }))
        }

        val run = store.getRun(runId)
        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "runId" to runId,
                "tool" to tool,
                "testName" to testName,
                "status" to (run?.status ?: "queued"),
                "summary" to null
            )
        ))
    }

    private fun handleGetRun(args: JsonObject, id: Any): String {
        val runId = args.get("run_id")?.asString ?: return errorResponse(id, -32001, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResponse(id, -32002, "Run not found: $runId")

        val summary = if (run.summary != null) parseJson(run.summary) else null
        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "runId" to run.id,
                "tool" to run.tool,
                "testName" to "",
                "timestamp" to run.createdAt,
                "status" to run.status,
                "summary" to summary,
                "rawOutputPath" to run.rawOutputPath,
                "metadata" to (if (run.metadata != null) parseJson(run.metadata) else emptyMap<String, Any>())
            )
        ))
    }

    private fun handleServerStatus(id: Any): String {
        val runningRun = store.getPendingOrRunningRun()
        val queuedRuns = store.getQueuedRuns()

        val currentlyRunning = if (runningRun != null && runningRun.status == "running") {
            mapOf(
                "runId" to runningRun.id,
                "tool" to runningRun.tool,
                "testName" to "",
                "progressPct" to 0.0,
                "currentStage" to "running",
                "elapsedSec" to 0,
                "estimatedRemainingSec" to 0
            )
        } else null

        val queued = queuedRuns.mapIndexed { idx, r ->
            mapOf("runId" to r.id, "tool" to r.tool, "testName" to "", "position" to (idx + 1))
        }

        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "status" to if (runningRun != null) "running" else "idle",
                "uptimeSec" to ((System.currentTimeMillis() - startupTime) / 1000),
                "queueDepth" to queued.size,
                "currentlyRunning" to currentlyRunning,
                "queuedRuns" to queued,
                "serverVersion" to serverVersion,
                "adapters" to store.listAdapters().map { it.name }
            )
        ))
    }

    private fun handleGetRunProgress(args: JsonObject, id: Any): String {
        val runId = args.get("run_id")?.asString ?: return errorResponse(id, -32001, "Missing run_id")
        val run = store.getRun(runId) ?: return errorResponse(id, -32002, "Run not found: $runId")

        val summary = if (run.summary != null) parseJson(run.summary) else null
        return gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "result" to mapOf(
                "runId" to run.id,
                "status" to run.status,
                "progressPct" to (if (run.status == "completed") 100.0 else if (run.status == "running") 50.0 else 0.0),
                "currentStage" to (if (run.status == "running") "running" else run.status),
                "stageProgress" to mapOf(
                    "warmup" to mapOf("status" to "completed"),
                    "running" to mapOf("status" to (if (run.status == "running") "in_progress" else "completed"))
                ),
                "rollingSummary" to summary
            )
        ))
    }

    private fun parseJson(json: String): Map<String, Any> {
        return try {
            gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun errorResponse(id: Any?, code: Int, message: String, data: Map<String, Any>? = null): String {
        val error = mutableMapOf<String, Any>("code" to code, "message" to message)
        if (data != null) error["data"] = data
        val resp = mutableMapOf<String, Any>("jsonrpc" to "2.0", "error" to error)
        if (id != null) resp["id"] = id
        return gson.toJson(resp)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.core.McpHandlerTest" 2>&1 | tail -15`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/McpHandler.kt
git add src/test/kotlin/io/boehm/core/McpHandlerTest.kt
git commit -m "Task 6: MCP JSON-RPC handler with tool dispatch and auth"
```

---

### Task 7: Orchestrator + Scheduler

**Files:**
- Create: `src/main/kotlin/io/boehm/core/Orchestrator.kt`
- Create: `src/main/kotlin/io/boehm/core/Scheduler.kt`
- Create: `src/test/kotlin/io/boehm/core/OrchestratorTest.kt`
- Create: `src/test/kotlin/io/boehm/core/SchedulerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/boehm/core/OrchestratorTest.kt
package io.boehm.core

import io.boehm.adapters.PerfToolAdapter
import io.boehm.adapters.tulip.TulipParser
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class OrchestratorTest {
    private lateinit var store: Store
    private lateinit var orchestrator: Orchestrator

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        orchestrator = Orchestrator(store)
    }

    @Test
    fun `submitRun with known adapter returns runId`() {
        val plan = TestPlan("http", "https://example.com", 100, 10)
        val runId = orchestrator.submitRun("tulip", "test-1", plan)
        assertNotNull(runId)
    }

    @Test
    fun `submitRun with unknown adapter returns null`() {
        val plan = TestPlan("http", "https://example.com", 100, 10)
        val runId = orchestrator.submitRun("nonexistent", "test-1", plan)
        assertNull(runId)
    }
}
```

```kotlin
// src/test/kotlin/io/boehm/core/SchedulerTest.kt
package io.boehm.core

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SchedulerTest {
    private lateinit var store: Store
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    }

    @Test
    fun `scheduler runs a queued run and completes it`() {
        val adapter = TestAdapter()
        scheduler = Scheduler(store, adapter)
        scheduler.start()

        val plan = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":5}"""
        val scenarioId = store.insertScenario("tulip", "test-1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")

        Thread.sleep(500)

        val run = store.getRun(runId)
        assertEquals("completed", run!!.status)
        scheduler.stop()
    }

    @Test
    fun `scheduler serializes two runs`() {
        val adapter = TestAdapter()
        scheduler = Scheduler(store, adapter)
        scheduler.start()

        val plan1 = """{"type":"http","targetUrl":"https://example.com","ratePerSec":100,"durationSec":1}"""
        val plan2 = """{"type":"http","targetUrl":"https://other.com","ratePerSec":100,"durationSec":1}"""
        val s1 = store.insertScenario("tulip", "test-1", plan1)!!
        val s2 = store.insertScenario("tulip", "test-2", plan2)!!
        val r1 = store.insertRun(s1, "tulip")!!
        val r2 = store.insertRun(s2, "tulip")!!

        store.updateRunStatus(r1, "queued")
        store.updateRunStatus(r2, "queued")

        Thread.sleep(1500)

        val run1 = store.getRun(r1)
        val run2 = store.getRun(r2)
        assertEquals("completed", run1!!.status)
        assertEquals("completed", run2!!.status)
        assertTrue(run1.completedAt!! < run2.createdAt || run1.completedAt!! < run2.startedAt!!)
        scheduler.stop()
    }
}

class TestAdapter : PerfToolAdapter {
    override val name = "tulip"
    override val supportedTestTypes = listOf(TestType.HTTP)
    override val version = "0.1.0"
    override val toolVersions = listOf("0.x")

    override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()

    override fun run(testPlan: TestPlan): RunResult {
        Thread.sleep(100) // simulate work
        return RunResult(
            tool = "tulip", testName = "test",
            timestamp = java.time.Instant.now().toString(),
            runId = java.util.UUID.randomUUID().toString(),
            status = "completed",
            summary = Summary(1, 100, 100.0, 0.0,
                Latency(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)),
            rawOutputPath = null,
            metadata = emptyMap()
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "io.boehm.core.OrchestratorTest" --tests "io.boehm.core.SchedulerTest" 2>&1 | tail -15`
Expected: FAIL — classes not found

- [ ] **Step 3: Write Orchestrator and Scheduler**

```kotlin
// src/main/kotlin/io/boehm/core/Orchestrator.kt
package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.adapters.tulip.TulipAdapter
import io.boehm.model.*

class Orchestrator(private val store: Store) {
    private val gson = Gson()
    private var scheduler: Scheduler? = null
    private val adapters = mutableMapOf<String, PerfToolAdapter>()

    fun registerAdapter(adapter: PerfToolAdapter) {
        adapters[adapter.name] = adapter
        store.insertAdapter(adapter.name,
            gson.toJson(adapter.supportedTestTypes.map { it.label }),
            adapter.version, gson.toJson(adapter.toolVersions))
    }

    fun submitRun(tool: String, testName: String, testPlan: TestPlan): String? {
        val adapter = adapters[tool] ?: return null

        val errors = adapter.validate(testPlan)
        if (errors.isNotEmpty()) return null

        val planJson = gson.toJson(testPlan)
        val scenarioId = store.insertScenario(tool, testName, planJson) ?: return null

        val runId = store.insertRun(scenarioId, tool) ?: return null
        store.updateRunStatus(runId, "queued")

        if (scheduler == null) {
            scheduler = Scheduler(store, adapters.values.toList())
            scheduler!!.start()
        }

        return runId
    }
}
```

```kotlin
// src/main/kotlin/io/boehm/core/Scheduler.kt
package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(
    private val store: Store,
    private val adapters: List<PerfToolAdapter>
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val gson = Gson()
    private val adapterMap = adapters.associateBy { it.name }
    private var running = false

    fun start() {
        if (running) return
        running = true
        executor.scheduleWithFixedDelay({ pollQueue() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        executor.shutdown()
    }

    private fun pollQueue() {
        try {
            val pending = store.getPendingOrRunningRun() ?: return
            if (pending.status == "running") return // already processing

            store.updateRunStatus(pending.id, "running")

            val scenarioId = pending.scenarioId
            val scenario = getScenarioById(scenarioId) ?: return

            val testPlan = parseTestPlan(scenario.testPlan)
            val adapter = adapterMap[pending.tool]

            if (adapter == null) {
                store.updateRunStatus(pending.id, "failed", error = "Adapter not found: ${pending.tool}")
                return
            }

            val outputDir = System.getProperty("user.home") + "/.boehm/outputs"
            val result = adapter.run(testPlan)

            val summaryJson = if (result.summary != null) gson.toJson(result.summary) else null
            store.updateRunStatus(pending.id, result.status, summary = summaryJson,
                error = null, rawOutputPath = result.rawOutputPath)

        } catch (e: Exception) {
            // Log error, continue polling
        }
    }

    private fun getScenarioById(id: String): ScenarioRow? {
        // We need a direct lookup - add to Store if not there
        try {
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${store.dbPath}")
            val ps = conn.prepareStatement("SELECT * FROM test_scenarios WHERE id = ?")
            ps.setString(1, id)
            val rs = ps.executeQuery()
            val result = if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
                rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
            rs.close()
            ps.close()
            conn.close()
            return result
        } catch (_: Exception) { null }
    }

    private fun parseTestPlan(json: String): TestPlan {
        return gson.fromJson(json, TestPlan::class.java)
    }
}
```

Wait — Store doesn't expose `dbPath` publicly. Let me fix that and add `getScenarioById` to Store. Let me check what I need to change.

I need to:
1. Make `dbPath` accessible or add `getScenarioById` to Store
2. Add `getScenarioById` method

Let me adjust the Scheduler to use a `getScenarioById` method on Store, and make Store.dbPath public.

- [ ] **Step 3 (revised): Update Store and write Orchestrator + Scheduler**

First update Store to add `getScenarioById`:

Add to Store.kt:
```kotlin
fun getScenarioById(id: String): ScenarioRow? {
    val ps = conn.prepareStatement("SELECT id, tool, name, test_plan, created_at FROM test_scenarios WHERE id = ?")
    ps.setString(1, id)
    val rs = ps.executeQuery()
    val result = if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
        rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
    rs.close()
    ps.close()
    return result
}
```

Then write Orchestrator and Scheduler as above but using `store.getScenarioById`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "io.boehm.core.OrchestratorTest" --tests "io.boehm.core.SchedulerTest" 2>&1 | tail -15`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Orchestrator.kt src/main/kotlin/io/boehm/core/Scheduler.kt
git add src/test/kotlin/io/boehm/core/OrchestratorTest.kt src/test/kotlin/io/boehm/core/SchedulerTest.kt
git commit -m "Task 7: orchestrator + serial scheduler with run lifecycle"
```

---

### Task 8: Main entry point

**Files:**
- Create: `src/main/kotlin/io/boehm/Main.kt`

- [ ] **Step 1: Write Main.kt**

```kotlin
// src/main/kotlin/io/boehm/Main.kt
package io.boehm

import io.boehm.auth.AuthHandler
import io.boehm.core.McpHandler
import io.boehm.core.Store
import java.io.File

fun main(args: Array<String>) {
    val dbPath = System.getenv("BOEHM_DB_PATH") ?: "${System.getProperty("user.home")}/.boehm/boehm.db"
    val token = args.find { it.startsWith("--token=") }?.substringAfter("--token=")
        ?: System.getenv("BOEHM_TOKEN")
        ?: error("No token provided. Use --token=<token> or set BOEHM_TOKEN env var.")

    File(dbPath).parentFile.mkdirs()

    val authHandler = AuthHandler()
    authHandler.createToken(token)

    val store = Store(dbPath)
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")

    val mcpHandler = McpHandler(authHandler, store)

    println("Boehm MCP server starting on stdio")
    System.err.println("DB: $dbPath")

    val reader = System.`in`.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        val response = mcpHandler.handle(line)
        println(response)
        System.out.flush()
    }
}
```

- [ ] **Step 2: Create build.gradle.kts with application plugin**

Make sure build.gradle.kts has the application plugin so we can run:

```kotlin
plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "io.boehm"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

application {
    mainClass.set("io.boehm.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
```

- [ ] **Step 3: Verify server starts**

Run: `echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test123"}}' | ./gradlew run --args="--token=test123" 2>/dev/null | head -5`
Expected: JSON response with protocolVersion

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/boehm/Main.kt build.gradle.kts settings.gradle.kts
git commit -m "Task 8: main entry point with stdio loop and wiring"
```

---

### Task 9: Integration test

**Files:**
- Create: `src/test/kotlin/io/boehm/integration/TulipIntegrationTest.kt`

- [ ] **Step 1: Write the integration test**

```kotlin
// src/test/kotlin/io/boehm/integration/TulipIntegrationTest.kt
package io.boehm.integration

import io.boehm.adapters.tulip.TulipAdapter
import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.*
import org.junit.jupiter.api.Test
import java.io.File

class TulipIntegrationTest {
    private val tulipRepo = File("/home/bvwyk/git/Tulip")

    @Test
    fun `tulip CLI runs against httpbin and returns valid RunResult`() {
        assumeTrue(tulipRepo.exists(), "Tulip repo not found at $tulipRepo")
        assumeTrue(File(tulipRepo, "gradlew").exists(), "Tulip Gradle wrapper not found")

        val wrapper = File("src/test/fixtures/run-real-tulip.sh")
        assumeTrue(wrapper.exists(), "Tulip wrapper script not found")
        wrapper.setExecutable(true)

        val adapter = TulipAdapter(tulipCommand = wrapper.absolutePath)
        val plan = TestPlan(
            type = "http",
            targetUrl = "https://httpbin.org/get",
            ratePerSec = 50,
            durationSec = 10,
            warmupSec = 2
        )

        val result = adapter.run(plan)
        assertEquals("tulip", result.tool)
        assertEquals("completed", result.status)
        assertNotNull(result.summary)
        assertTrue(result.summary!!.totalRequests > 0)
        assertTrue(result.summary!!.durationSec > 0)

        if (result.rawOutputPath != null) {
            val outputFile = File(result.rawOutputPath)
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0)
        }
    }
}
```

Note: Tulip is a Gradle application (no standalone `tulip` binary), so the integration test uses a wrapper script (`run-real-tulip.sh`) that runs Tulip via `./gradlew :tulip-main:run --args="--config <file>"`.

- [ ] **Step 2: Run to verify test structure works (will skip if no tulip CLI)**

Run: `./gradlew test --tests "io.boehm.integration.TulipIntegrationTest" 2>&1 | tail -15`
Expected: Test passes (either runs against tulip CLI or skips with assumption failure)

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/io/boehm/integration/
git add src/test/fixtures/run-real-tulip.sh
git commit -m "Task 9: integration test against Tulip CLI"
```

---

## Remaining spec items (post-Phase 1)

After all 9 tasks complete and all tests pass, the following spec items are fully covered:

| Acceptance criterion | Task |
|---|---|
| `./gradlew build` succeeds | 1, 8 |
| Auth token validation | 3, 6 |
| `list_adapters` returns adapters | 4, 5, 6 |
| `run_test` queues and returns run_id | 6, 7 |
| `server_status` shows running/idle | 6 |
| `get_run_progress` shows progress | 6 |
| Two runs serialize correctly | 7 |
| Integration test against Tulip CLI | 9 |
| Raw output file saved | 5, 9 |
