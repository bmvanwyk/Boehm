# Phase 1 Bugfixes: Profile Dispatch, Scheduler Resilience, Timeout — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three correctness bugs in the Boehm MCP server: (1) `run_test` silently ignores `test_plan.profile`, (2) any adapter exception permanently deadlocks the run queue, (3) `timeout_sec` is never enforced.

**Architecture:** Adapters are registered per tool+profile and dispatched by a composite `tool:profile` key through `Orchestrator` and `Scheduler`. Run submission returns a sealed `SubmitResult` so the MCP layer can map failure causes to the correct JSON-RPC error codes. The scheduler fails runs explicitly instead of swallowing exceptions, and recovers interrupted runs at startup. `CatalogAdapter` enforces `timeout_sec` by killing the subprocess tree.

**Tech Stack:** Kotlin/JVM 21, Gradle, Gson, SQLite (sqlite-jdbc), SnakeYAML, JUnit 5.

---

## Scope decisions

- **Orphan policy:** On server start, runs stuck in `running` are marked `failed` with `interrupted: server restarted`. Queued runs still execute.
- **Commit per validated task:** Only commit after a task's tests pass. Never commit a broken task.
- **Out of scope:** MCP protocol gaps (`tools/list`, `capabilities`, notifications, content envelopes), real progress metrics, shell escaping, stale-plan-on-scenario-reuse, `testName: ""` in responses.

---

## Context for the Implementing Engineer

Working in `/home/bvwyk/git/Boehm`, a Kotlin/JVM MCP server that runs performance tests via external CLI tools and persists results in SQLite. Read `AGENTS.md` and `docs/architecture.md` first.

Key facts:

- **Build/test:** `./gradlew build` (compiles + runs all unit tests). Unit tests need **no external tools** — Tulip CLI is only needed for the integration test, which skips gracefully when absent.
- **Java 21** required (`.sdkmanrc` exists; Gradle toolchain handles it).
- **Stdio protocol:** The server speaks JSON-RPC 2.0 over **stdout**. Never `println` to stdout from server code — use `System.err`. A stray `println` corrupts the protocol stream.
- **Test fixtures** at `src/test/fixtures/` (note: not under `kotlin/io/boehm/`).
- **Test style:** JUnit 5, backtick test names, `org.junit.jupiter.api.Assertions.*`.
- **Imports:** Full qualified imports inside test files when referencing project types from a different package (tests are in `io.boehm.adapter`, `io.boehm.core` etc.).
- **Work on branch** `fix/phase-1-dispatch-scheduler-timeout` (already checked out).
- **Commit after each task** with a short imperative message matching repo style.

### The bugs being fixed (confirmed, do not re-diagnose)

1. **Profile dispatch ignored.** `Main.kt` builds one `CatalogAdapter` per profile (Tulip has `http-get` and `demo`), but `Orchestrator.registerAdapter` keys adapters by `adapter.name` (the *tool* name), so the second registration overwrites the first and every Tulip run executes the `demo` profile. `TestPlan.profile` is never read.
2. **Scheduler deadlock.** `Scheduler.pollQueue()` wraps everything in `catch (_: Exception) {}`. If the adapter throws, the run stays `running` forever; the scheduler skips `running` runs, so no queued run ever executes again. Orphaned runs survive restarts.
3. **No timeout.** `TestPlan.timeoutSec` is parsed but unused; `CatalogAdapter` calls `executor.waitFor()` with no timeout, so a hung CLI hangs the serial scheduler forever.

---

### Task 1: Fix profile dispatch and submission error reporting

`PerfToolAdapter` gains a `profile` property. `Orchestrator` and `Scheduler` key adapters by `"tool:profile"`. `Orchestrator.submitRun` returns a sealed `SubmitResult`; `McpHandler` maps it to the correct error codes (`-32000` unknown tool, `-32001` unknown profile / invalid plan with validation messages).

**Files:**
- Modify: `src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt`
- Modify: `src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt`
- Modify: `src/main/kotlin/io/boehm/core/Orchestrator.kt` (full rewrite)
- Modify: `src/main/kotlin/io/boehm/core/McpHandler.kt` (`handleRunTest` only)
- Modify: `src/main/kotlin/io/boehm/core/Scheduler.kt` (two lines only — full rewrite in Task 2)
- Modify: `src/test/kotlin/io/boehm/core/OrchestratorTest.kt` (full rewrite)
- Modify: `src/test/kotlin/io/boehm/core/SchedulerTest.kt` (mock + plan JSON updates, one new test)
- Modify: `src/test/kotlin/io/boehm/core/McpHandlerTest.kt` (two new tests)
- Modify: `docs/architecture.md` and `docs/superpowers/specs/2026-07-20-boehm-phase-1.md` (interface snippets)

- [ ] **Step 1: Rewrite `OrchestratorTest.kt` to express the new contract (will not compile — that is the red state)**

Replace or overwrite `src/test/kotlin/io/boehm/core/OrchestratorTest.kt` with:

```kotlin
package io.boehm.core

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrchestratorTest {
    private lateinit var store: Store
    private lateinit var orchestrator: Orchestrator

    private fun mockAdapter(
        toolName: String,
        profileName: String,
        failValidation: Boolean = false,
        validatedBy: MutableList<String>? = null
    ): PerfToolAdapter {
        return object : PerfToolAdapter {
            override val name = toolName
            override val profile = profileName
            override val supportedTestTypes = listOf(TestType.HTTP)
            override val version = "0.1.0"
            override val toolVersions = listOf("0.x")
            override fun validate(testPlan: TestPlan): List<ValidationError> {
                validatedBy?.add(profileName)
                return if (failValidation) listOf(ValidationError("targetUrl", "must not be empty"))
                else emptyList()
            }
            override fun run(testPlan: TestPlan): RunResult = throw UnsupportedOperationException()
        }
    }

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        orchestrator = Orchestrator(store)
    }

    @Test
    fun `submitRun with known adapter and profile returns Queued`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get"))
        val plan = TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.Queued)
    }

    @Test
    fun `submitRun with unknown adapter returns UnknownAdapter`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get"))
        val plan = TestPlan(type = "http", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("nonexistent", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.UnknownAdapter)
    }

    @Test
    fun `submitRun with unknown profile returns UnknownProfile`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get"))
        val plan = TestPlan(type = "http", profile = "no-such-profile", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.UnknownProfile)
    }

    @Test
    fun `submitRun with invalid plan returns Invalid with errors`() {
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get", failValidation = true))
        val plan = TestPlan(type = "http", profile = "http-get", targetUrl = "")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.Invalid)
        assertEquals("targetUrl", (result as Orchestrator.SubmitResult.Invalid).errors[0].field)
    }

    @Test
    fun `submitRun dispatches to adapter matching plan profile`() {
        val validatedBy = mutableListOf<String>()
        orchestrator.registerAdapter(mockAdapter("tulip", "http-get", validatedBy = validatedBy))
        orchestrator.registerAdapter(mockAdapter("tulip", "demo", validatedBy = validatedBy))
        val plan = TestPlan(type = "http", profile = "demo", targetUrl = "https://example.com")
        val result = orchestrator.submitRun("tulip", "test-1", plan)
        assertTrue(result is Orchestrator.SubmitResult.Queued)
        assertEquals(listOf("demo"), validatedBy)
    }
}
```

Run: `./gradlew test --tests "io.boehm.core.OrchestratorTest" 2>&1 | tail -5`
Expected: **compilation failure** (`profile`/`SubmitResult` unresolved). This is the red state.

- [ ] **Step 2: Add `profile` to the adapter interface**

Replace the entire contents of `src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt` with:

```kotlin
package io.boehm.adapters

import io.boehm.model.*

interface PerfToolAdapter {
    val name: String
    val profile: String
    val supportedTestTypes: List<TestType>
    val version: String
    val toolVersions: List<String>

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan): RunResult
}
```

- [ ] **Step 3: Update `CatalogAdapter` — expose `profile`, rename internal `ProfileDef` getter**

In `src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt`:

(a) Replace the block at lines 23–25:

```kotlin
    private val profile: ProfileDef
        get() = toolDef.profiles[profileName]
            ?: error("Profile '$profileName' not found for tool '${toolDef.name}'")
```

with:

```kotlin
    override val profile: String get() = profileName

    private val profileDef: ProfileDef
        get() = toolDef.profiles[profileName]
            ?: error("Profile '$profileName' not found for tool '${toolDef.name}'")
```

(b) Replace all occurrences of `profile.overrides` → `profileDef.overrides` (6 occurrences: three in `validate()`, three in `resolveOverrides()`).
(c) Replace all occurrences of `profile.output` → `profileDef.output` (5 occurrences in `run()`).
(d) Replace the single occurrence of `profile.config` → `profileDef.config`.
(e) In `run()`, delete the line `val profile = profile`. Change the call `applyJsonOverrides(cleanTemplate, profile, overrides)` to `applyJsonOverrides(cleanTemplate, profileDef, overrides)`.

- [ ] **Step 4: Rewrite `Orchestrator.kt` with `SubmitResult` and `tool:profile` keying**

Replace `src/main/kotlin/io/boehm/core/Orchestrator.kt` with:

```kotlin
package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan
import io.boehm.model.ValidationError

class Orchestrator(private val store: Store) {

    sealed class SubmitResult {
        data class Queued(val runId: String) : SubmitResult()
        data class UnknownAdapter(val tool: String) : SubmitResult()
        data class UnknownProfile(val tool: String, val profile: String) : SubmitResult()
        data class Invalid(val errors: List<ValidationError>) : SubmitResult()
    }

    private val gson = Gson()
    private var scheduler: Scheduler? = null
    private val adapters = mutableMapOf<String, PerfToolAdapter>() // key: "tool:profile"

    fun registerAdapter(adapter: PerfToolAdapter) {
        adapters["${adapter.name}:${adapter.profile}"] = adapter
        store.insertAdapter(adapter.name,
            gson.toJson(adapter.supportedTestTypes.map { it.label }),
            adapter.version, gson.toJson(adapter.toolVersions))
    }

    fun submitRun(tool: String, testName: String, testPlan: TestPlan): SubmitResult {
        if (adapters.values.none { it.name == tool }) {
            return SubmitResult.UnknownAdapter(tool)
        }
        val adapter = adapters["$tool:${testPlan.profile}"]
            ?: return SubmitResult.UnknownProfile(tool, testPlan.profile)

        val errors = adapter.validate(testPlan)
        if (errors.isNotEmpty()) return SubmitResult.Invalid(errors)

        val planJson = gson.toJson(testPlan)
        val scenarioId = store.insertScenario(tool, testName, planJson)
            ?: return SubmitResult.Invalid(listOf(ValidationError("testName", "could not persist scenario")))
        val runId = store.insertRun(scenarioId, tool)
            ?: return SubmitResult.Invalid(listOf(ValidationError("run", "could not persist run")))
        store.updateRunStatus(runId, "queued")

        if (scheduler == null) {
            scheduler = Scheduler(store, adapters.values.toList())
            scheduler!!.start()
        }

        return SubmitResult.Queued(runId)
    }
}
```

- [ ] **Step 5: Update `McpHandler.handleRunTest` to map `SubmitResult` to error codes**

In `src/main/kotlin/io/boehm/core/McpHandler.kt`, replace the entire `handleRunTest` function with:

```kotlin
    private fun handleRunTest(args: JsonObject, id: Any): String {
        val tool = args.get("tool")?.asString ?: return errorResponse(id, -32001, "Missing tool")
        val testName = args.get("test_name")?.asString ?: return errorResponse(id, -32001, "Missing test_name")
        val testPlanJson = args.getAsJsonObject("test_plan") ?: return errorResponse(id, -32001, "Missing test_plan")

        val knownFields = setOf("type", "profile", "target_url", "rate_per_sec", "duration_sec", "warmup_sec", "timeout_sec")
        val parameters = testPlanJson.entrySet()
            .filterNot { knownFields.contains(it.key) }
            .associate { it.key to (it.value?.asString ?: "") }

        val plan = TestPlan(
            type = testPlanJson.get("type")?.asString ?: "http",
            profile = testPlanJson.get("profile")?.asString ?: "http-get",
            targetUrl = testPlanJson.get("target_url")?.asString ?: "",
            ratePerSec = testPlanJson.get("rate_per_sec")?.asInt ?: 50,
            durationSec = testPlanJson.get("duration_sec")?.asInt ?: 30,
            warmupSec = testPlanJson.get("warmup_sec")?.asInt ?: 5,
            timeoutSec = testPlanJson.get("timeout_sec")?.asInt ?: 60,
            parameters = parameters
        )

        return when (val result = orchestrator!!.submitRun(tool, testName, plan)) {
            is Orchestrator.SubmitResult.Queued -> {
                val run = store.getRun(result.runId)
                gson.toJson(mapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "result" to mapOf(
                        "runId" to result.runId,
                        "tool" to tool,
                        "testName" to testName,
                        "status" to (run?.status ?: "queued"),
                        "summary" to null
                    )
                ))
            }
            is Orchestrator.SubmitResult.UnknownAdapter ->
                errorResponse(id, -32000, "Adapter not found: ${result.tool}",
                    mapOf("available_adapters" to store.listAdapters().map { it.name }))
            is Orchestrator.SubmitResult.UnknownProfile ->
                errorResponse(id, -32001, "Unknown profile '${result.profile}' for tool '${result.tool}'")
            is Orchestrator.SubmitResult.Invalid ->
                errorResponse(id, -32001, "Invalid test plan",
                    mapOf("validation_errors" to result.errors.map { "${it.field}: ${it.message}" }))
        }
    }
```

- [ ] **Step 6: Update `Scheduler` dispatch key (two lines)**

In `src/main/kotlin/io/boehm/core/Scheduler.kt`:
- Change `private val adapterMap = adapters.associateBy { it.name }` → `adapters.associateBy { "${it.name}:${it.profile}" }`
- Change `val adapter = adapterMap[pending.tool]` → `val adapter = adapterMap["${pending.tool}:${testPlan.profile}"]`

- [ ] **Step 7: Fix `SchedulerTest` mock and plan JSONs**

In `src/test/kotlin/io/boehm/core/SchedulerTest.kt`:

(a) Add `override val profile = "http-get"` to `TestAdapter` (after `override val name = "tulip"`).

(b) Every plan JSON string must include `"profile":"http-get"`:
```kotlin
val plan = """{"type":"http","profile":"http-get","targetUrl":"https://example.com","ratePerSec":100,"durationSec":5}"""
```
Same for `plan1` and `plan2` in the serialization test.

(c) Add this test:

```kotlin
    @Test
    fun `scheduler executes adapter matching plan profile`() {
        val httpAdapter = TestAdapter()
        val demoAdapter = object : PerfToolAdapter {
            override val name = "tulip"
            override val profile = "demo"
            override val supportedTestTypes = listOf(TestType.HTTP)
            override val version = "0.1.0"
            override val toolVersions = listOf("0.x")
            override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()
            override fun run(testPlan: TestPlan) = RunResult(
                tool = "tulip", testName = "test",
                timestamp = java.time.Instant.now().toString(),
                runId = java.util.UUID.randomUUID().toString(),
                status = "failed", summary = null, rawOutputPath = null,
                metadata = mapOf("error" to "demo adapter ran")
            )
        }
        scheduler = Scheduler(store, listOf(httpAdapter, demoAdapter))
        scheduler.start()

        val plan = """{"type":"http","profile":"demo","targetUrl":"https://example.com"}"""
        val scenarioId = store.insertScenario("tulip", "test-demo", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")

        val run = waitForStatus(runId, "failed", 5)
        assertEquals("failed", run!!.status)
        scheduler.stop()
    }
```

(d) Add import for `PerfToolAdapter` at top (possibly already present): `import io.boehm.adapters.PerfToolAdapter`

- [ ] **Step 8: Add `McpHandlerTest` coverage for the new error paths**

Append inside `src/test/kotlin/io/boehm/core/McpHandlerTest.kt`:

```kotlin
    private fun tulipMock() = object : io.boehm.adapters.PerfToolAdapter {
        override val name = "tulip"
        override val profile = "http-get"
        override val supportedTestTypes = listOf(io.boehm.model.TestType.HTTP)
        override val version = "0.1.0"
        override val toolVersions = listOf("0.x")
        override fun validate(testPlan: io.boehm.model.TestPlan): List<io.boehm.model.ValidationError> =
            if (testPlan.targetUrl.isBlank()) listOf(io.boehm.model.ValidationError("targetUrl", "must not be empty"))
            else emptyList()
        override fun run(testPlan: io.boehm.model.TestPlan): io.boehm.model.RunResult =
            throw UnsupportedOperationException()
    }

    @Test
    fun `run_test with unknown profile returns -32001`() {
        val store = Store(":memory:")
        val h = McpHandler(authHandler, store, listOf(tulipMock()))
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"t","test_plan":{"type":"http","profile":"nope","target_url":"https://example.com"}}}}""")
        assertTrue(response.contains("-32001"), response)
        assertTrue(response.contains("Unknown profile"), response)
    }

    @Test
    fun `run_test with invalid plan returns -32001 with validation errors`() {
        val store = Store(":memory:")
        val h = McpHandler(authHandler, store, listOf(tulipMock()))
        h.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"test-token"}}""")
        val response = h.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"t","test_plan":{"type":"http","profile":"http-get","target_url":""}}}}""")
        assertTrue(response.contains("-32001"), response)
        assertTrue(response.contains("validation_errors"), response)
    }
```

- [ ] **Step 9: Run the full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: all tests PASS.

- [ ] **Step 10: Update interface snippets in docs**

In `docs/architecture.md` (line 217) and `docs/superpowers/specs/2026-07-20-boehm-phase-1.md` (line 187), add `val profile: String` after `val name: String` in each `PerfToolAdapter` Kotlin code block.

- [ ] **Step 11: Commit (only if Step 9 passes)**

```bash
git add src/main src/test docs/architecture.md docs/superpowers/specs/2026-07-20-boehm-phase-1.md
git commit -m "Fix profile dispatch: key adapters by tool:profile, structured SubmitResult errors"
```

---

### Task 2: Scheduler failure handling and orphan recovery

Exceptions during execution now mark the run `failed` (queue keeps moving), adapter-reported failures persist their error message, and runs orphaned in `running` state by a server restart are marked `failed` at scheduler start.

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/Scheduler.kt` (full rewrite)
- Modify: `src/main/kotlin/io/boehm/core/Store.kt` (add `failInterruptedRuns`)
- Modify: `src/test/kotlin/io/boehm/core/SchedulerTest.kt` (richer `TestAdapter` + 5 new tests)
- Modify: `src/test/kotlin/io/boehm/core/StoreTest.kt` (1 new test)

- [ ] **Step 1: Write the failing scheduler tests**

In `src/test/kotlin/io/boehm/core/SchedulerTest.kt`, replace the `TestAdapter` class with:

```kotlin
class TestAdapter(
    override val profile: String = "http-get",
    private val resultStatus: String = "completed",
    private val resultError: String? = null,
    private val shouldThrow: Boolean = false
) : PerfToolAdapter {
    override val name = "tulip"
    override val supportedTestTypes = listOf(TestType.HTTP)
    override val version = "0.1.0"
    override val toolVersions = listOf("0.x")

    override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()

    override fun run(testPlan: TestPlan): RunResult {
        if (shouldThrow) throw RuntimeException("boom")
        Thread.sleep(100)
        return RunResult(
            tool = "tulip", testName = "test",
            timestamp = java.time.Instant.now().toString(),
            runId = java.util.UUID.randomUUID().toString(),
            status = resultStatus,
            summary = if (resultStatus == "completed") Summary(1, 100, 100.0, 0.0,
                Latency(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)) else null,
            rawOutputPath = null,
            metadata = if (resultError != null) mapOf("error" to resultError) else emptyMap()
        )
    }
}
```

Then add these tests to `SchedulerTest`:

```kotlin
    @Test
    fun `scheduler marks run failed when adapter throws and continues queue`() {
        val bad = TestAdapter(shouldThrow = true)
        val good = TestAdapter(profile = "demo")
        scheduler = Scheduler(store, listOf(bad, good))
        scheduler.start()

        val p1 = """{"type":"http","profile":"http-get"}"""
        val p2 = """{"type":"http","profile":"demo"}"""
        val s1 = store.insertScenario("tulip", "t1", p1)!!
        val s2 = store.insertScenario("tulip", "t2", p2)!!
        val r1 = store.insertRun(s1, "tulip")!!
        val r2 = store.insertRun(s2, "tulip")!!
        store.updateRunStatus(r1, "queued")
        store.updateRunStatus(r2, "queued")

        val run1 = waitForStatus(r1, "failed", 5)
        assertTrue(run1!!.error!!.contains("boom"), "expected boom in: ${run1.error}")
        val run2 = waitForStatus(r2, "completed", 5)
        assertEquals("completed", run2!!.status)
        scheduler.stop()
    }

    @Test
    fun `scheduler marks run failed when scenario is missing`() {
        scheduler = Scheduler(store, listOf(TestAdapter()))
        scheduler.start()
        val runId = store.insertRun("no-such-scenario", "tulip")!!
        store.updateRunStatus(runId, "queued")
        val run = waitForStatus(runId, "failed", 5)
        assertTrue(run!!.error!!.contains("Scenario not found"), "got: ${run.error}")
        scheduler.stop()
    }

    @Test
    fun `scheduler marks run failed when no adapter matches`() {
        scheduler = Scheduler(store, emptyList())
        scheduler.start()
        val plan = """{"type":"http","profile":"http-get"}"""
        val scenarioId = store.insertScenario("tulip", "t1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")
        val run = waitForStatus(runId, "failed", 5)
        assertTrue(run!!.error!!.contains("Adapter not found"), "got: ${run.error}")
        scheduler.stop()
    }

    @Test
    fun `start marks interrupted running runs as failed`() {
        val plan = """{"type":"http","profile":"http-get"}"""
        val scenarioId = store.insertScenario("tulip", "t1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "running")

        scheduler = Scheduler(store, listOf(TestAdapter()))
        scheduler.start()

        val run = waitForStatus(runId, "failed", 5)
        assertEquals("failed", run!!.status)
        assertTrue(run.error!!.contains("interrupted"), "got: ${run.error}")
        scheduler.stop()
    }

    @Test
    fun `scheduler persists adapter-reported failure error`() {
        scheduler = Scheduler(store, listOf(TestAdapter(resultStatus = "failed", resultError = "kaboom")))
        scheduler.start()
        val plan = """{"type":"http","profile":"http-get"}"""
        val scenarioId = store.insertScenario("tulip", "t1", plan)!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "queued")
        val run = waitForStatus(runId, "failed", 5)
        assertEquals("kaboom", run!!.error)
        scheduler.stop()
    }
```

Run: `./gradlew test --tests "io.boehm.core.SchedulerTest" 2>&1 | tail -20`
Expected: the 5 new tests **FAIL** (old code swallows exceptions and never persists errors).

- [ ] **Step 2: Add `failInterruptedRuns` to `Store`**

In `src/main/kotlin/io/boehm/core/Store.kt`, add immediately before `fun close()`:

```kotlin
    fun failInterruptedRuns(): Int {
        val now = Instant.now().toString()
        return conn.prepareStatement("""
            UPDATE runs SET status = 'failed', error = 'interrupted: server restarted', completed_at = ?
            WHERE status = 'running'
        """).use { ps ->
            ps.setString(1, now)
            ps.executeUpdate()
        }
    }
```

- [ ] **Step 3: Add the `StoreTest` for it**

Append inside `src/test/kotlin/io/boehm/core/StoreTest.kt`:

```kotlin
    @Test
    fun `failInterruptedRuns marks running runs as failed`() {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val scenarioId = store.insertScenario("tulip", "t1", """{"type":"http"}""")!!
        val runId = store.insertRun(scenarioId, "tulip")!!
        store.updateRunStatus(runId, "running")

        val count = store.failInterruptedRuns()

        assertEquals(1, count)
        val run = store.getRun(runId)!!
        assertEquals("failed", run.status)
        assertTrue(run.error!!.contains("interrupted"))
        assertNotNull(run.completedAt)
    }
```

- [ ] **Step 4: Rewrite `Scheduler.kt`**

Replace `src/main/kotlin/io/boehm/core/Scheduler.kt` with:

```kotlin
package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.TestPlan
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(
    private val store: Store,
    private val adapters: List<PerfToolAdapter>
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "boehm-scheduler").also { it.isDaemon = true }
    }
    private val gson = Gson()
    private val adapterMap = adapters.associateBy { "${it.name}:${it.profile}" }
    private var running = false

    fun start() {
        if (running) return
        running = true
        val interrupted = store.failInterruptedRuns()
        if (interrupted > 0) {
            System.err.println("boehm: marked $interrupted interrupted run(s) as failed")
        }
        executor.scheduleWithFixedDelay({ pollQueue() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        executor.shutdown()
    }

    private fun pollQueue() {
        val pending = store.getPendingOrRunningRun() ?: return
        if (pending.status == "running") return

        try {
            val scenario = store.getScenarioById(pending.scenarioId)
            if (scenario == null) {
                store.updateRunStatus(pending.id, "failed",
                    error = "Scenario not found: ${pending.scenarioId}")
                return
            }

            val testPlan = gson.fromJson(scenario.testPlan, TestPlan::class.java)
            val adapter = adapterMap["${pending.tool}:${testPlan.profile}"]
            if (adapter == null) {
                store.updateRunStatus(pending.id, "failed",
                    error = "Adapter not found: ${pending.tool}:${testPlan.profile}")
                return
            }

            store.updateRunStatus(pending.id, "running")
            val result = adapter.run(testPlan)

            val summaryJson = if (result.summary != null) gson.toJson(result.summary) else null
            val error = if (result.status == "failed") result.metadata["error"]?.toString() else null
            store.updateRunStatus(pending.id, result.status, summary = summaryJson,
                error = error, rawOutputPath = result.rawOutputPath)
        } catch (e: Exception) {
            try {
                store.updateRunStatus(pending.id, "failed",
                    error = "${e.javaClass.simpleName}: ${e.message}")
            } catch (_: Exception) {
                // Store unavailable; nothing more we can do.
            }
        }
    }
}
```

Critical: recovery log goes to `System.err`, never stdout.

- [ ] **Step 5: Run the tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: all tests PASS. Commit only if they pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/boehm/core/ src/test/kotlin/io/boehm/core/
git commit -m "Harden scheduler: fail runs on adapter errors, recover interrupted runs on start"
```

---

### Task 3: Enforce `timeout_sec` in `CatalogAdapter`

**Files:**
- Modify: `src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt` (process execution block + imports)
- Modify: `src/test/kotlin/io/boehm/adapter/TulipAdapterTest.kt` (one new test)

- [ ] **Step 1: Write the failing test**

Append inside `src/test/kotlin/io/boehm/adapter/TulipAdapterTest.kt`:

```kotlin
    @Test
    fun `run exceeding timeout returns failed with timeout error`() {
        val toolDef = ToolDef(
            name = "tulip", description = "", install = null,
            run = RunDef(command = "sleep 30"),
            profiles = mapOf("http-get" to ProfileDef("http-get", null, null,
                OutputDef("{{output_file}}", "json", "tulip-results"), emptyMap()))
        )
        val adapter = CatalogAdapter(toolDef, "http-get", baseDir)
        val plan = TestPlan(type = "http", timeoutSec = 1)
        val result = adapter.run(plan)
        assertEquals("failed", result.status)
        assertTrue(result.metadata["error"].toString().contains("timeout"),
            "expected timeout error, got: ${result.metadata}")
    }
```

Run: `./gradlew test --tests "io.boehm.adapter.TulipAdapterTest" 2>&1 | tail -10`
Expected: the new test **FAILS** (current code waits ~30 seconds).

- [ ] **Step 2: Implement the timeout**

In `src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt`:

(a) Add imports at the top of the file:

```kotlin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
```

(b) Replace the process execution block (the `// Execute command` block) with:

```kotlin
        // Execute command, enforcing the test plan timeout
        val rawCommand = commandOverride ?: toolDef.run.command
        val command = substituteCommand(rawCommand, subs)
        val process = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val finished = process.waitFor(testPlan.timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            val partial = try { stdoutFuture.get(2, TimeUnit.SECONDS) } catch (e: Exception) { "" }
            configFile?.delete()
            configFile?.parentFile?.delete()
            return failedResult(testPlan, "timeout after ${testPlan.timeoutSec}s",
                metadata = mapOf("stdout" to partial.take(500)))
        }
        val stdout = try { stdoutFuture.get(2, TimeUnit.SECONDS) } catch (e: Exception) { "" }
        val exitCode = process.exitValue()

        configFile?.delete()
        configFile?.parentFile?.delete()
```

- [ ] **Step 3: Run the tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: all tests PASS; timeout test completes in ~1–2 seconds.

- [ ] **Step 4: Commit (only if Step 3 passes)**

```bash
git add src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt src/test/kotlin/io/boehm/adapter/TulipAdapterTest.kt
git commit -m "Enforce test plan timeout: kill subprocess tree and fail run on timeout"
```

---

### Task 4: Full validation

- [ ] **Step 1: Clean build**

Run: `./gradlew clean build 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify coverage ≥ 80%**

```bash
python3 -c "
import xml.etree.ElementTree as ET
t = ET.parse('build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for c in t.findall('counter'):
    if c.get('type') == 'INSTRUCTION':
        covered, missed = int(c.get('covered')), int(c.get('missed'))
        print(f'instruction coverage: {covered/(covered+missed)*100:.1f}%')
"
```

Expected: `instruction coverage: 82%` or higher.

- [ ] **Step 3: Smoke test**

Run: `./scripts/smoke-test.sh 2>&1 | tail -15`
Expected: `OK: Boehm MCP server is working.`

- [ ] **Step 4: Manual dispatch verification**

```bash
./gradlew installDist -q
BOEHM_DB_PATH=/tmp/boehm-verify.db build/install/boehm/bin/boehm --token=verify-token &
{ echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"auth_token":"verify-token"}}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"v1","test_plan":{"type":"http","profile":"demo","duration_sec":3}}}}'
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"v2","test_plan":{"type":"http","profile":"http-get","target_url":"https://httpbin.org/get","duration_sec":3}}}}'
  sleep 2
} | BOEHM_DB_PATH=/tmp/boehm-verify.db build/install/boehm/bin/boehm --token=verify-token
rm -f /tmp/boehm-verify.db*
```

Expected: both calls return `"runId"` with `"status":"queued"`. A call with `"profile":"bogus"` must return `-32001`.

- [ ] **Step 5: Review the diff**

```bash
git log --oneline main..HEAD && git diff main --stat
```

Expected: 3 commits; changes confined to the listed files. `.opencode/package.json` must not appear.

---

## Explicitly out of scope (do not touch)

- MCP protocol compliance (`tools/list`, `capabilities`, notification handling, content envelopes)
- Real progress metrics in `get_run_progress` / `server_status`
- Shell escaping of override values in `CatalogAdapter.substituteCommand`
- Scenario reuse returning stale plans for a changed `test_name` (`INSERT OR IGNORE` behavior)
- `testName: ""` in `get_run` / `server_status` responses
