# Critical Fixes + Baseline Comparison Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the measurement-integrity and correctness bugs found in the critical review, and ship the missing core value prop: run history, baseline tagging, and baseline comparison via new MCP tools (`list_runs`, `tag_baseline`, `compare_runs`, `cancel_run`).

**Architecture:** All changes stay inside the existing layering (Store → Orchestrator/Scheduler → CatalogAdapter → parsers → MCP handlers). Store gains a `baselines` table, ISO-8601 timestamps everywhere, thread-safety, and plan-update-on-resubmit. Parsers gain mean/stdev latency so comparisons are meaningful. Scheduler gains cooperative cancellation by killing the active subprocess. Handlers expose four new MCP tools registered in `Main.buildServer`.

**Tech Stack:** Kotlin/JVM 25, Gradle wrapper (`./gradlew`), Gson, SQLite (xerial JDBC), JUnit 5, MCP Kotlin SDK 0.15.0.

**Out of scope (deferred):** environment/tool-version capture per run, offered-vs-achieved rate recording, JMeter URL component parsing, warmup support in k6/JMeter profiles, per-label breakdowns, error classification.

**Verification command for every task:** `./gradlew test` from repo root.

---

## Task 1: Fix silent scenario plan drift

When a scenario name is resubmitted with a different test plan, `Store.insertScenario` silently ignores the new plan (`INSERT OR IGNORE`), so the run executes the *old* config while the agent believes it queued the new one.

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/Store.kt` (`insertScenario`)
- Test: `src/test/kotlin/io/boehm/core/StoreTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `StoreTest.kt`:

```kotlin
@Test
fun `insertScenario updates stored plan when resubmitted with different config`() {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val p1 = """{"type":"http","ratePerSec":100}"""
    val p2 = """{"type":"http","ratePerSec":500}"""
    val id1 = store.insertScenario("tulip", "test-1", p1)!!
    val id2 = store.insertScenario("tulip", "test-1", p2)!!

    // Same scenario identity (name-keyed), but the stored plan must reflect the latest submission
    assertEquals(id1, id2)
    assertTrue(
        store.getScenarioById(id1)!!.testPlan.contains("500"),
        "expected updated plan persisted, got: ${store.getScenarioById(id1)!!.testPlan}"
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.boehm.core.StoreTest"`
Expected: FAIL — persisted plan still contains `100`, not `500`.

- [ ] **Step 3: Implement UPSERT in `insertScenario`**

Replace the body of `insertScenario` in `Store.kt`:

```kotlin
fun insertScenario(tool: String, name: String, testPlan: String): String? {
    val id = UUID.randomUUID().toString()
    conn.prepareStatement("""
        INSERT INTO test_scenarios (id, tool, name, test_plan) VALUES (?, ?, ?, ?)
        ON CONFLICT(tool, name) DO UPDATE SET test_plan = excluded.test_plan
    """).use { ps ->
        ps.setString(1, id)
        ps.setString(2, tool)
        ps.setString(3, name)
        ps.setString(4, testPlan)
        ps.executeUpdate()
    }
    return getScenario(tool, name)?.id
}
```

(SQLite bundled with sqlite-jdbc 3.45.1.0 supports UPSERT. Re-read returns the existing scenario id on resubmit.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.boehm.core.StoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Store.kt src/test/kotlin/io/boehm/core/StoreTest.kt
git commit -m "fix: resubmitting a scenario name now updates its stored test plan"
```

---

## Task 2: Consistent ISO-8601 timestamps

Schema defaults use SQLite `datetime('now')` (`2026-08-24 12:00:00`) while updates write ISO-8601 (`2026-08-24T12:00:00Z`). Mixed formats break lexicographic `ORDER BY created_at`. Fix: application code always writes explicit ISO timestamps; DB defaults remain as fallback only.

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/Store.kt` (`insertScenario`, `insertRun`)
- Test: `src/test/kotlin/io/boehm/core/StoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `StoreTest.kt`:

```kotlin
@Test
fun `scenario and run timestamps are ISO-8601 with Z suffix`() {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "ts-test", """{"type":"http"}""")!!
    val rid = store.insertRun(sid, "tulip")!!

    val scenario = store.getScenarioById(sid)!!
    val run = store.getRun(rid)!!
    assertTrue(scenario.createdAt.endsWith("Z") && scenario.createdAt.contains('T'),
        "scenario created_at not ISO-8601: ${scenario.createdAt}")
    assertTrue(run.createdAt.endsWith("Z") && run.createdAt.contains('T'),
        "run created_at not ISO-8601: ${run.createdAt}")
}

@Test
fun `runs created later sort before earlier runs in listRuns DESC order`() {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "sort-test", """{"type":"http"}""")!!
    val r1 = store.insertRun(sid, "tulip")!!
    Thread.sleep(50)
    val r2 = store.insertRun(sid, "tulip")!!
    val runs = store.listRuns(sid)  // ORDER BY created_at DESC
    assertEquals(r2, runs[0].id)
    assertEquals(r1, runs[1].id)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.core.StoreTest"`
Expected: FAIL — `created_at` from DB default is `2026-08-24 12:34:56` (no `T`/`Z`).

- [ ] **Step 3: Write timestamps explicitly**

In `Store.kt`, change both inserts to supply `created_at` explicitly (`java.time.Instant` already imported):

```kotlin
fun insertScenario(tool: String, name: String, testPlan: String): String? {
    val id = UUID.randomUUID().toString()
    conn.prepareStatement("""
        INSERT INTO test_scenarios (id, tool, name, test_plan, created_at) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(tool, name) DO UPDATE SET test_plan = excluded.test_plan
    """).use { ps ->
        ps.setString(1, id)
        ps.setString(2, tool)
        ps.setString(3, name)
        ps.setString(4, testPlan)
        ps.setString(5, Instant.now().toString())
        ps.executeUpdate()
    }
    return getScenario(tool, name)?.id
}

fun insertRun(scenarioId: String, tool: String): String? {
    val id = UUID.randomUUID().toString()
    conn.prepareStatement("INSERT INTO runs (id, scenario_id, tool, created_at) VALUES (?, ?, ?, ?)").use { ps ->
        ps.setString(1, id)
        ps.setString(2, scenarioId)
        ps.setString(3, tool)
        ps.setString(4, Instant.now().toString())
        ps.execute()
    }
    return id
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.boehm.core.StoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Store.kt src/test/kotlin/io/boehm/core/StoreTest.kt
git commit -m "fix: always write ISO-8601 created_at so run ordering is consistent"
```

---

## Task 3: Make Store thread-safe

One shared SQLite `Connection` is used concurrently by the scheduler thread and MCP coroutine handlers. SQLite connections are not thread-safe. Guard all public methods with a single lock.

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/Store.kt` (all public methods)

- [ ] **Step 1: Add lock field and wrap public methods**

Add next to `_conn`:

```kotlin
private val lock = Any()
```

Wrap the body of every public method (`insertAdapter`, `listAdapters`, `insertScenario`, `getScenario`, `getScenarioById`, `insertRun`, `getRun`, `updateRunStatus`, `listRuns`, `getPendingOrRunningRun`, `getQueuedRuns`, `failInterruptedRuns`, `close`) in `synchronized(lock) { ... }`. Example for two methods (apply identically to all):

```kotlin
fun insertAdapter(name: String, supportedTypes: String, version: String, toolVersions: String) {
    synchronized(lock) {
        conn.prepareStatement("INSERT OR IGNORE INTO adapters VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, name)
            ps.setString(2, supportedTypes)
            ps.setString(3, version)
            ps.setString(4, toolVersions)
            ps.execute()
        }
    }
}

fun close() {
    synchronized(lock) {
        _conn?.close()
        _conn = null
    }
}
```

After this change the lazy `conn` getter is only ever reached inside `synchronized(lock)`; no separate guard needed there.

- [ ] **Step 2: Run full suite**

Run: `./gradlew test`
Expected: PASS — no behaviour change; SchedulerTest concurrency stays green.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Store.kt
git commit -m "fix: serialize Store access behind a single lock (shared SQLite connection)"
```

---

## Task 4: Reject plans whose timeout cannot cover the test

Default `timeout_sec=60` vs default `duration_sec=30`: any long run without an explicit timeout is killed mid-run and marked failed. Validate instead of failing at runtime.

**Files:**
- Modify: `src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt` (`validate`)
- Test: create `src/test/kotlin/io/boehm/catalog/CatalogAdapterValidationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/io/boehm/catalog/CatalogAdapterValidationTest.kt`:

```kotlin
package io.boehm.catalog

import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogAdapterValidationTest {

    private fun adapter() = CatalogAdapter(
        toolDef = ToolDef(
            name = "tulip",
            description = "test",
            install = null,
            run = RunDef(command = "echo"),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = null,
                    config = null,
                    output = OutputDef(path = "stdout", format = "text", schema = "none"),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = null, default = "https://example.com"),
                        "duration_sec" to OverrideDef(path = null, default = 30),
                        "warmup_sec" to OverrideDef(path = null, default = 5)
                    )
                )
            )
        ),
        profileName = "http-get"
    )

    @Test
    fun `validate rejects timeout shorter than duration plus warmup plus slack`() {
        val errors = adapter().validate(
            TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com",
                durationSec = 120, warmupSec = 10, timeoutSec = 60)
        )
        assertTrue(errors.any { it.field == "timeoutSec" }, "expected timeoutSec error, got: $errors")
    }

    @Test
    fun `validate accepts adequate timeout`() {
        val errors = adapter().validate(
            TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com",
                durationSec = 30, warmupSec = 5, timeoutSec = 60)
        )
        assertTrue(errors.isEmpty())
    }
}
```

Note: with slack 10, `durationSec=30, warmupSec=5` needs `timeoutSec >= 45`; 60 passes, 60 < 130+10 fails the first case.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.boehm.catalog.CatalogAdapterValidationTest"`
Expected: FAIL — first test finds no `timeoutSec` error.

- [ ] **Step 3: Implement validation**

In `CatalogAdapter.kt`, add to the companion object:

```kotlin
// Process timeout must cover the whole test plus teardown slack.
private const val TIMEOUT_SLACK_SEC = 10
```

Append to `validate(testPlan)` before `return errors`:

```kotlin
if (profileDef.overrides.containsKey("duration_sec")) {
    val minTimeout = testPlan.durationSec + testPlan.warmupSec + TIMEOUT_SLACK_SEC
    if (testPlan.timeoutSec < minTimeout) {
        errors.add(
            ValidationError(
                "timeoutSec",
                "must be >= duration_sec + warmup_sec + $TIMEOUT_SLACK_SEC " +
                    "(got ${testPlan.timeoutSec}, need >= $minTimeout); otherwise the run would be killed mid-test"
            )
        )
    }
}
```

- [ ] **Step 4: Run full suite**

Run: `./gradlew test`
Expected: PASS. If any existing handler/scheduler test plans violate the new rule, raise those plans' `timeoutSec` in the tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt src/test/kotlin/io/boehm/catalog/CatalogAdapterValidationTest.kt
git commit -m "fix: reject test plans whose timeout_sec is shorter than the test itself"
```

---

## Task 5: Gate adapters on having a parser; extract builder for testability

`Main` registers adapters for every catalog profile, including gatling/vegeta/wrk whose schemas have no parser. Running them yields `"completed"` results with `summary: null`.

**Files:**
- Create: `src/main/kotlin/io/boehm/catalog/AdapterBuilder.kt`
- Modify: `src/main/kotlin/io/boehm/Main.kt`
- Test: create `src/test/kotlin/io/boehm/catalog/AdapterBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/io/boehm/catalog/AdapterBuilderTest.kt`:

```kotlin
package io.boehm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdapterBuilderTest {

    private fun catalogWithSchemas(vararg schemas: String): Catalog {
        val profiles = schemas.associate { schema ->
            "$schema-profile" to ProfileDef(
                name = "$schema-profile",
                description = null,
                config = null,
                output = OutputDef(path = "stdout", format = "json", schema = schema),
                overrides = emptyMap()
            )
        }
        return Catalog(
            version = 1,
            tools = mapOf("tooly" to ToolDef("tooly", "d", null, RunDef("echo"), profiles))
        )
    }

    @Test
    fun `buildAdapters skips profiles whose schema has no parser`() {
        val parsers = mapOf<String, (String) -> io.boehm.model.RunResult>(
            "known-schema" to { _ -> throw UnsupportedOperationException() }
        )
        val adapters = buildAdapters(catalogWithSchemas("known-schema", "wrk-text"), System.getProperty("java.io.tmpdir"), parsers)

        assertEquals(listOf("tooly:known-schema-profile"), adapters.map { "${it.name}:${it.profile}" })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.boehm.catalog.AdapterBuilderTest"`
Expected: COMPILE ERROR — `buildAdapters` unresolved.

- [ ] **Step 3: Implement `buildAdapters` and wire into Main**

Create `src/main/kotlin/io/boehm/catalog/AdapterBuilder.kt`:

```kotlin
package io.boehm.catalog

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.RunResult

/**
 * Builds one CatalogAdapter per catalog profile that has a parser for its output
 * schema. Profiles without a parser are skipped with a stderr note — they appear
 * nowhere in the server's tool surface rather than failing confusingly at run time.
 */
fun buildAdapters(
    catalog: Catalog,
    baseDir: String,
    parsers: Map<String, (String) -> RunResult>
): List<PerfToolAdapter> =
    catalog.tools.flatMap { (_, toolDef) ->
        toolDef.profiles.values.mapNotNull { profileDef ->
            if (profileDef.output.schema !in parsers) {
                System.err.println(
                    "boehm: skipping ${toolDef.name}:${profileDef.name} — no parser for schema '${profileDef.output.schema}'"
                )
                null
            } else {
                CatalogAdapter(toolDef, profileDef.name, baseDir, parsers)
            }
        }
    }
```

In `Main.kt` replace the inline adapter construction block:

```kotlin
// Load catalog and create adapters
val adapters = buildAdapters(catalog, baseDir, parsers)
```

(add `import io.boehm.catalog.buildAdapters`; delete the old flatMap block).

- [ ] **Step 4: Run full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/catalog/AdapterBuilder.kt src/main/kotlin/io/boehm/Main.kt \
        src/test/kotlin/io/boehm/catalog/AdapterBuilderTest.kt
git commit -m "fix: only register adapters whose output schema has a parser"
```

---

## Task 6: Add mean/stdev to Latency across all parsers

Comparisons need central tendency and dispersion, not just percentiles. Add `meanMs`/`stdevMs` to `Latency` (defaulted so positional constructions keep compiling) and populate in all three parsers via a shared stats helper. Also remove the dead `avg_connect_ms` block.

**Files:**
- Create: `src/main/kotlin/io/boehm/model/Stats.kt`
- Modify: `src/main/kotlin/io/boehm/model/RunResult.kt`
- Modify: `src/main/kotlin/io/boehm/adapters/jmeter/JMeterParser.kt`
- Modify: `src/main/kotlin/io/boehm/adapters/k6/K6Parser.kt`
- Modify: `src/main/kotlin/io/boehm/adapters/tulip/TulipParser.kt`
- Test: modify `JMeterParserTest.kt`, `K6ParserTest.kt`, `TulipParserTest.kt`

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/io/boehm/adapter/JMeterParserTest.kt`:

```kotlin
@Test
fun `latency includes mean and stdev`() {
    val csv = """
        timeStamp,elapsed,success,label
        1700000000000,100,true,mean-test
        1700000000100,200,true,mean-test
        1700000000200,300,true,mean-test
    """.trimIndent()
    val result = JMeterParser.parse(csv)
    assertEquals(200.0, result.summary!!.latency.meanMs, 0.001)
    // population stdev of {100,200,300} ≈ 81.65
    assertEquals(81.6497, result.summary!!.latency.stdevMs, 0.01)
}
```

In `TulipParserTest.kt`:

```kotlin
@Test
fun `latency includes mean and stdev from avg_rt and sd_rt`() {
    val json = """
        {"results":[{"bm_name":"bm","duration":10,"num_actions":100,"num_failed":0,"avg_aps":10.0,
          "min_rt":1000000.0,"max_rt":5000000.0,"avg_rt":2500000.0,"sd_rt":500000.0,
          "percentiles_rt":{"50.0":2400000.0}}]}
    """.trimIndent()
    val result = TulipParser.parse(json)
    assertEquals(2.5, result.summary!!.latency.meanMs, 0.0001)
    assertEquals(0.5, result.summary!!.latency.stdevMs, 0.0001)
}
```

In `K6ParserTest.kt`:

```kotlin
@Test
fun `latency includes mean and stdev`() {
    val ndjson = listOf(
        """{"metric":"http_req_duration","data":{"value":100.0,"time":"2026-01-01T00:00:00Z"}}""",
        """{"metric":"http_req_duration","data":{"value":200.0,"time":"2026-01-01T00:00:01Z"}}""",
        """{"metric":"http_req_duration","data":{"value":300.0,"time":"2026-01-01T00:00:02Z"}}"""
    ).joinToString("\n")
    val result = K6Parser.parse(ndjson)
    assertEquals(200.0, result.summary!!.latency.meanMs, 0.001)
    assertEquals(81.6497, result.summary!!.latency.stdevMs, 0.01)
}
```

(Check each test file's existing imports; they should already import the parser under test.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.adapter.JMeterParserTest" --tests "io.boehm.adapter.K6ParserTest" --tests "io.boehm.adapter.TulipParserTest"`
Expected: COMPILE ERROR — `meanMs` unresolved on Latency.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/io/boehm/model/Stats.kt`:

```kotlin
package io.boehm.model

import kotlin.math.sqrt

/** Descriptive statistics shared by all output parsers. */
object Stats {
    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.average()

    /** Population standard deviation. */
    fun stdev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }
}
```

Extend `Latency` in `RunResult.kt` (defaults keep positional call sites compiling):

```kotlin
data class Latency(
    val minMs: Double,
    val p50Ms: Double,
    val p90Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val meanMs: Double = 0.0,
    val stdevMs: Double = 0.0
)
```

In `JMeterParser.computeLatency`, return with samples (import `io.boehm.model.Stats`):

```kotlin
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
```

Apply identical named args in `K6Parser.parse` where `Latency(...)` is built (samples are `durationValues`).

In `TulipParser.parseLatency` (aggregates, not samples):

```kotlin
val meanRt = if (result.has("avg_rt")) result.get("avg_rt").asDouble / NS_TO_MS else 0.0
val sdRt = if (result.has("sd_rt")) result.get("sd_rt").asDouble / NS_TO_MS else 0.0
```

and add `meanMs = meanRt, stdevMs = sdRt` to the returned `Latency`.

While in `JMeterParser.parse`, delete the dead block computing constant 0.0:

```kotlin
// DELETE these lines:
if (connectCol != null) {
    val avgConnect = timestamps.zip(elapsedValues).mapNotNull { (_, _) -> 0.0 }.let { 0.0 }
    metadata["avg_connect_ms"] = avgConnect
}
```

and the now-unused `connectCol` local. Keep `latencyValues` (it feeds `avg_latency_ms` metadata).

Also simplify `CatalogAdapter.supportedTestTypes` (both branches identical):

```kotlin
override val supportedTestTypes: List<TestType>
    get() = listOf(TestType.HTTP)
```

- [ ] **Step 4: Run full suite**

Run: `./gradlew test jacocoTestReport`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/model/Stats.kt src/main/kotlin/io/boehm/model/RunResult.kt \
        src/main/kotlin/io/boehm/adapters/jmeter/JMeterParser.kt src/main/kotlin/io/boehm/adapters/k6/K6Parser.kt \
        src/main/kotlin/io/boehm/adapters/tulip/TulipParser.kt src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt \
        src/test/kotlin/io/boehm/adapter/
git commit -m "feat: report mean/stdev latency in all parsers; drop dead avg_connect_ms code"
```

---

## Task 7: Store additions for history, baselines, cancellation

New persistence primitives used by Tasks 9–11: a `baselines` table, filtered run listing, and queued-run cancellation.

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/Store.kt`
- Test: `src/test/kotlin/io/boehm/core/StoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `StoreTest.kt`:

```kotlin
@Test
fun `setBaseline replaces previous baseline for scenario`() {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "b-test", """{"type":"http"}""")!!
    val r1 = store.insertRun(sid, "tulip")!!
    val r2 = store.insertRun(sid, "tulip")!!

    store.setBaseline(sid, r1)
    assertEquals(r1, store.getBaselineRunId(sid))
    store.setBaseline(sid, r2)
    assertEquals(r2, store.getBaselineRunId(sid))
}

@Test
fun `getBaselineRunId returns null when unset`() {
    assertNull(store.getBaselineRunId("no-such-scenario"))
}

@Test
fun `listRecentRuns filters by tool testName and limits`() {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    store.insertAdapter("k6", """["http"]""", "0.1.0", """["0.x"]""")
    val s1 = store.insertScenario("tulip", "alpha", """{"type":"http"}""")!!
    val s2 = store.insertScenario("k6", "beta", """{"type":"http"}""")!!
    store.updateRunStatus(store.insertRun(s1, "tulip")!!, "completed")
    store.updateRunStatus(store.insertRun(s2, "k6")!!, "completed")
    store.updateRunStatus(store.insertRun(s2, "k6")!!, "completed")

    assertEquals(3, store.listRecentRuns(null, null, 10).size)
    assertEquals(1, store.listRecentRuns("tulip", null, 10).size)
    assertEquals(2, store.listRecentRuns(null, "beta", 10).size)
    assertEquals(1, store.listRecentRuns(null, null, 1).size)
}

@Test
fun `cancelQueuedRun cancels only non-started runs`() {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "c-test", """{"type":"http"}""")!!
    val queued = store.insertRun(sid, "tulip")!!
    store.updateRunStatus(queued, "queued")
    val running = store.insertRun(sid, "tulip")!!
    store.updateRunStatus(running, "running")

    assertTrue(store.cancelQueuedRun(queued))
    assertEquals("cancelled", store.getRun(queued)!!.status)
    assertFalse(store.cancelQueuedRun(running))   // already started — scheduler handles it
    assertFalse(store.cancelQueuedRun("no-such-run"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.core.StoreTest"`
Expected: COMPILE ERROR — new methods unresolved.

- [ ] **Step 3: Implement**

In `initSchema`, add after the other CREATE TABLE statements:

```kotlin
stmt.execute("""
    CREATE TABLE IF NOT EXISTS baselines (
        scenario_id TEXT PRIMARY KEY REFERENCES test_scenarios(id),
        run_id TEXT NOT NULL REFERENCES runs(id),
        tagged_at TEXT NOT NULL
    )
""")
```

Add methods (all wrapped in `synchronized(lock)` like Task 3):

```kotlin
fun setBaseline(scenarioId: String, runId: String) {
    synchronized(lock) {
        conn.prepareStatement("""
            INSERT INTO baselines (scenario_id, run_id, tagged_at) VALUES (?, ?, ?)
            ON CONFLICT(scenario_id) DO UPDATE SET run_id = excluded.run_id, tagged_at = excluded.tagged_at
        """).use { ps ->
            ps.setString(1, scenarioId)
            ps.setString(2, runId)
            ps.setString(3, Instant.now().toString())
            ps.execute()
        }
    }
}

fun getBaselineRunId(scenarioId: String): String? {
    synchronized(lock) {
        conn.prepareStatement("SELECT run_id FROM baselines WHERE scenario_id = ?").use { ps ->
            ps.setString(1, scenarioId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString("run_id") else null
            }
        }
    }
}

/** Recently finished runs, newest first. In-flight runs are excluded. */
fun listRecentRuns(tool: String?, testName: String?, limit: Int): List<RunRow> {
    synchronized(lock) {
        conn.prepareStatement("""
            SELECT r.* FROM runs r JOIN test_scenarios s ON r.scenario_id = s.id
            WHERE (? IS NULL OR r.tool = ?) AND (? IS NULL OR s.name = ?)
              AND r.status IN ('completed', 'failed', 'cancelled')
            ORDER BY r.created_at DESC LIMIT ?
        """).use { ps ->
            ps.setString(1, tool); ps.setString(2, tool)
            ps.setString(3, testName); ps.setString(4, testName)
            ps.setInt(5, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<RunRow>()
                while (rs.next()) {
                    result.add(RunRow(
                        rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                        rs.getString("status"), rs.getString("created_at"),
                        rs.getString("started_at"), rs.getString("completed_at"),
                        rs.getString("error"), rs.getString("summary"),
                        rs.getString("raw_output_path"), rs.getString("metadata")))
                }
                return result
            }
        }
    }
}

/** Cancels a run that has not started yet. Returns true if the status changed. */
fun cancelQueuedRun(runId: String): Boolean {
    synchronized(lock) {
        conn.prepareStatement("""
            UPDATE runs SET status = 'cancelled', completed_at = ?
            WHERE id = ? AND status IN ('pending', 'queued')
        """).use { ps ->
            ps.setString(1, Instant.now().toString())
            ps.setString(2, runId)
            return ps.executeUpdate() > 0
        }
    }
}
```

Note on queue pickup: `getPendingOrRunningRun` filters `status IN ('pending','queued','running')`, which already excludes `'cancelled'` — no change needed there.

- [ ] **Step 4: Run full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Store.kt src/test/kotlin/io/boehm/core/StoreTest.kt
git commit -m "feat: baselines table, filtered run history, queued-run cancellation in Store"
```

---

## Task 8: Real progress estimation

`get_run_progress` and `server_status` currently report hardcoded percentages and zero elapsed time. Compute real progress from `started_at` and the scenario plan's warmup/duration.

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt`
- Test: `src/test/kotlin/io/boehm/core/BoehmServerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `BoehmServerTest.kt`:

```kotlin
@Test
fun `get_run_progress reports estimated progress for running run`() = runBlocking {
    registerTulipHttpGet()
    val args = buildJsonObject {
        put("tool", "tulip"); put("test_name", "prog-test")
        putJsonObject("test_plan") {
            put("type", "http"); put("profile", "http-get")
            put("target_url", "https://example.com")
            put("duration_sec", 100); put("warmup_sec", 50); put("timeout_sec", 200)
        }
    }
    val queued = gson.fromJson(contentText(handlers.runTest(request(args))), Map::class.java)
    val runId = queued["runId"] as String
    store.updateRunStatus(runId, "running")  // sets started_at = now

    val json = gson.fromJson(contentText(handlers.getRunProgress(request(
        buildJsonObject { put("run_id", runId) }))), Map::class.java)

    assertEquals("running", json["status"])
    @Suppress("UNCHECKED_CAST")
    val pct = json["progressPct"] as Double
    assertTrue(pct in 0.0..1.0, "just-started run should be ~0%, got $pct")
    assertEquals("warmup", json["currentStage"])  // warmup=50s, elapsed≈0
}

@Test
fun `get_run_progress reports completed at 100 pct`() = runBlocking {
    registerTulipHttpGet()
    val args = buildJsonObject {
        put("tool", "tulip"); put("test_name", "prog-done")
        putJsonObject("test_plan") {
            put("type", "http"); put("profile", "http-get"); put("target_url", "https://example.com")
        }
    }
    val queued = gson.fromJson(contentText(handlers.runTest(request(args))), Map::class.java)
    val runId = queued["runId"] as String
    store.updateRunStatus(runId, "completed")

    val json = gson.fromJson(contentText(handlers.getRunProgress(request(
        buildJsonObject { put("run_id", runId) }))), Map::class.java)
    assertEquals(100.0, json["progressPct"])
}
```

(Gson serializes `Double` map values as JSON numbers, so the `as Double` cast works.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.core.BoehmServerTest"`
Expected: FAIL — current code returns 50.0 for running runs and stage `"running"`.

- [ ] **Step 3: Implement progress estimation**

In `BoehmToolHandlers.kt` add a private helper (needs imports `io.boehm.core.RunRow` is same package; add `java.time.Instant`, `java.time.Duration`):

```kotlin
private data class ProgressEstimate(val pct: Double, val stage: String, val elapsedSec: Long, val remainingSec: Long)

private fun estimateProgress(run: RunRow): ProgressEstimate {
    if (run.status == "completed") return ProgressEstimate(100.0, "completed", 0, 0)
    if (run.status != "running") return ProgressEstimate(0.0, run.status, 0, 0)

    val startedAt = run.startedAt?.let {
        try { Instant.parse(it) } catch (_: Exception) { null }
    } ?: return ProgressEstimate(0.0, "running", 0, 0)

    val elapsed = Duration.between(startedAt, Instant.now()).seconds
    val scenario = store.getScenarioById(run.scenarioId)
    val plan = scenario?.let {
        try { gson.fromJson(it.testPlan, JsonObject::class.java) } catch (_: Exception) { null }
    }
    // TestPlan is serialized with Gson using its Kotlin property names
    val warmup = plan?.optInt("warmupSec") ?: 0
    val duration = plan?.optInt("durationSec") ?: 0
    val total = (warmup + duration).coerceAtLeast(1)

    val pct = (elapsed.toDouble() / total * 100.0).coerceIn(0.0, 99.0)
    val stage = if (elapsed < warmup) "warmup" else "measuring"
    return ProgressEstimate(pct, stage, elapsed, (total - elapsed).coerceAtLeast(0))
}
```

Rewrite `getRunProgress`:

```kotlin
suspend fun getRunProgress(request: CallToolRequest): CallToolResult {
    val runId = argString(request, "run_id") ?: return errorResult(-32001, "Missing run_id")
    val run = store.getRun(runId) ?: return errorResult(-32002, "Run not found: $runId")

    val est = estimateProgress(run)
    val summary = if (run.summary != null) parseJson(run.summary) else null
    return textResult(mapOf(
        "runId" to run.id,
        "status" to run.status,
        "progressPct" to est.pct,
        "currentStage" to est.stage,
        "elapsedSec" to est.elapsedSec,
        "estimatedRemainingSec" to est.remainingSec,
        "rollingSummary" to summary
    ))
}
```

In `serverStatus`, replace the hardcoded `currentlyRunning` map body:

```kotlin
val currentlyRunning = if (runningRun != null && runningRun.status == "running") {
    val est = estimateProgress(runningRun)
    mapOf(
        "runId" to runningRun.id,
        "tool" to runningRun.tool,
        "testName" to (store.getScenarioById(runningRun.scenarioId)?.name ?: ""),
        "progressPct" to est.pct,
        "currentStage" to est.stage,
        "elapsedSec" to est.elapsedSec,
        "estimatedRemainingSec" to est.remainingSec
    )
} else null
```

- [ ] **Step 4: Run full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt src/test/kotlin/io/boehm/core/BoehmServerTest.kt
git commit -m "feat: derive real progress/stage from started_at and plan duration instead of hardcoded values"
```

---

## Task 9: `list_runs` and `tag_baseline` MCP tools

**Files:**
- Modify: `src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt`
- Modify: `src/main/kotlin/io/boehm/Main.kt` (`buildServer`)
- Test: `src/test/kotlin/io/boehm/core/BoehmServerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `BoehmServerTest.kt`:

```kotlin
@Test
fun `list_runs returns completed runs with summary briefs`() = runBlocking {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "hist", """{"type":"http"}""")!!
    val rid = store.insertRun(sid, "tulip")!!
    store.updateRunStatus(rid, "completed",
        summary = """{"totalRequests":100,"throughputReqPerSec":33.5,"errorRatePct":0.5}""")

    val json = gson.fromJson(contentText(handlers.listRuns(request(
        buildJsonObject { put("limit", 10) }))), Map::class.java)
    @Suppress("UNCHECKED_CAST")
    val runs = json["runs"] as List<Map<String, Any?>>
    assertEquals(1, runs.size)
    assertEquals(rid, runs[0]["runId"])
    assertEquals("completed", runs[0]["status"])
    assertEquals("hist", runs[0]["testName"])
    assertNotNull(runs[0]["summary"])
}

@Test
fun `tag_baseline tags completed run and rejects failed run`() = runBlocking {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "base", """{"type":"http"}""")!!
    val ok = store.insertRun(sid, "tulip")!!
    val bad = store.insertRun(sid, "tulip")!!
    store.updateRunStatus(ok, "completed", summary = "{}")
    store.updateRunStatus(bad, "failed", error = "boom")

    val good = gson.fromJson(contentText(handlers.tagBaseline(request(
        buildJsonObject { put("run_id", ok) }))), Map::class.java)
    assertEquals(true, good["taggedAsBaseline"])

    val err = handlers.tagBaseline(request(buildJsonObject { put("run_id", bad) }))
    assertTrue(err.isError ?: false)
    assertTrue(contentText(err).contains("not taggable"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.core.BoehmServerTest"`
Expected: COMPILE ERROR — `listRuns`/`tagBaseline` handlers unresolved.

- [ ] **Step 3: Implement handlers**

In `BoehmToolHandlers.kt`:

```kotlin
// ── list_runs ────────────────────────────────────────────────────────

suspend fun listRuns(request: CallToolRequest): CallToolResult {
    val tool = argString(request, "tool")
    val testName = argString(request, "test_name")
    val limit = request.arguments?.get("limit")?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    } ?: 20

    val runs = store.listRecentRuns(tool, testName, limit.coerceIn(1, 100)).map { run ->
        mapOf(
            "runId" to run.id,
            "tool" to run.tool,
            "testName" to (store.getScenarioById(run.scenarioId)?.name ?: ""),
            "status" to run.status,
            "createdAt" to run.createdAt,
            "summary" to (if (run.summary != null) parseJson(run.summary) else null)
        )
    }
    return textResult(mapOf("runs" to runs))
}

// ── tag_baseline ─────────────────────────────────────────────────────

suspend fun tagBaseline(request: CallToolRequest): CallToolResult {
    val runId = argString(request, "run_id") ?: return errorResult(-32001, "Missing run_id")
    val run = store.getRun(runId) ?: return errorResult(-32002, "Run not found: $runId")
    if (run.status != "completed" || run.summary == null) {
        return errorResult(
            -32003,
            "Run $runId is not taggable as baseline (status=${run.status}; needs a completed run with a summary)"
        )
    }
    store.setBaseline(run.scenarioId, run.id)
    return textResult(mapOf(
        "runId" to run.id,
        "scenarioId" to run.scenarioId,
        "taggedAsBaseline" to true
    ))
}
```

- [ ] **Step 4: Register tools in `Main.buildServer`**

Add alongside the existing `addTool` calls:

```kotlin
server.addTool(
    name = "list_runs",
    description = "List recent performance test runs (optionally filtered by tool and test name), newest first, with summaries",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("tool") { put("type", "string"); put("description", "Filter by tool name") }
            putJsonObject("test_name") { put("type", "string"); put("description", "Filter by scenario name") }
            putJsonObject("limit") { put("type", "integer"); put("description", "Max runs to return (default 20)") }
        }
    )
) { request: CallToolRequest -> handlers.listRuns(request) }

server.addTool(
    name = "tag_baseline",
    description = "Tag a completed run as the comparison baseline for its scenario (replaces any previous baseline)",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("run_id") { put("type", "string") }
        },
        required = listOf("run_id")
    )
) { request: CallToolRequest -> handlers.tagBaseline(request) }
```

- [ ] **Step 5: Run full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt src/main/kotlin/io/boehm/Main.kt \
        src/test/kotlin/io/boehm/core/BoehmServerTest.kt
git commit -m "feat: list_runs and tag_baseline MCP tools"
```

---

## Task 10: `compare_runs` MCP tool

Compares a run against an explicit `baseline_run_id` or the scenario's tagged baseline. Direction-aware deltas (higher throughput better; lower latency/error-rate better), regressions/improvements flagged beyond 10%.

**Files:**
- Create: `src/main/kotlin/io/boehm/core/Comparator.kt`
- Modify: `src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt`
- Modify: `src/main/kotlin/io/boehm/Main.kt`
- Test: create `src/test/kotlin/io/boehm/core/ComparatorTest.kt`; additions to `BoehmServerTest.kt`

- [ ] **Step 1: Write the failing unit tests**

Create `src/test/kotlin/io/boehm/core/ComparatorTest.kt`:

```kotlin
package io.boehm.core

import io.boehm.model.Latency
import io.boehm.model.Summary
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComparatorTest {
    private fun summary(throughput: Double, errorPct: Double, p95: Double) = Summary(
        durationSec = 30, totalRequests = 1000,
        throughputReqPerSec = throughput, errorRatePct = errorPct,
        latency = Latency(1.0, 10.0, 20.0, p95, 40.0, 50.0, meanMs = 15.0, stdevMs = 4.0)
    )

    @Test
    fun `flags throughput drop above threshold as regression`() {
        val verdicts = Comparator.compare(
            runSummary = summary(80.0, 0.5, 20.0),
            baselineSummary = summary(100.0, 0.5, 20.0)
        )
        val throughput = verdicts.metrics["throughputReqPerSec"]!!
        assertEquals("regression", throughput.verdict)
        assertEquals(-20.0, throughput.deltaPct!!, 0.001)
    }

    @Test
    fun `flags latency rise as regression and error rate drop as improvement`() {
        val verdicts = Comparator.compare(
            runSummary = summary(100.0, 0.1, 25.0),
            baselineSummary = summary(100.0, 1.0, 20.0)
        )
        assertEquals("regression", verdicts.metrics["p95Ms"]!!.verdict)
        assertEquals("improvement", verdicts.metrics["errorRatePct"]!!.verdict)
        assertEquals(listOf("p95Ms"), verdicts.regressions)
        assertEquals(listOf("errorRatePct"), verdicts.improvements)
    }

    @Test
    fun `within-threshold deltas are unchanged`() {
        val verdicts = Comparator.compare(
            runSummary = summary(105.0, 0.5, 21.0),
            baselineSummary = summary(100.0, 0.5, 20.0)
        )
        assertEquals("unchanged", verdicts.metrics["throughputReqPerSec"]!!.verdict)
        assertTrue(verdicts.regressions.isEmpty())
    }

    @Test
    fun `zero baseline yields null delta`() {
        val verdicts = Comparator.compare(
            runSummary = summary(100.0, 0.0, 20.0),
            baselineSummary = summary(0.0, 0.0, 0.0)
        )
        assertNull(verdicts.metrics["throughputReqPerSec"]!!.deltaPct)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.core.ComparatorTest"`
Expected: COMPILE ERROR — `Comparator` unresolved.

- [ ] **Step 3: Implement Comparator**

Create `src/main/kotlin/io/boehm/core/Comparator.kt`:

```kotlin
package io.boehm.core

import io.boehm.model.Summary
import kotlin.math.abs

/**
 * Compares two run summaries metric-by-metric. Direction-aware: higher
 * throughput is better, lower latency/error-rate is better. Deltas beyond
 * [REGRESSION_THRESHOLD_PCT] are flagged.
 */
object Comparator {

    private const val REGRESSION_THRESHOLD_PCT = 10.0

    data class MetricDelta(
        val baseline: Double,
        val run: Double,
        val deltaPct: Double?,
        /** One of "regression", "improvement", "unchanged". */
        val verdict: String
    )

    data class Comparison(
        val metrics: Map<String, MetricDelta>,
        val regressions: List<String>,
        val improvements: List<String>
    )

    fun compare(runSummary: Summary, baselineSummary: Summary): Comparison {
        // name -> Triple(baselineValue, runValue, higherIsBetter)
        val raw = linkedMapOf<String, Triple<Double, Double, Boolean>>(
            "throughputReqPerSec" to Triple(
                baselineSummary.throughputReqPerSec, runSummary.throughputReqPerSec, true),
            "errorRatePct" to Triple(
                baselineSummary.errorRatePct, runSummary.errorRatePct, false),
            "p50Ms" to Triple(baselineSummary.latency.p50Ms, runSummary.latency.p50Ms, false),
            "p90Ms" to Triple(baselineSummary.latency.p90Ms, runSummary.latency.p90Ms, false),
            "p95Ms" to Triple(baselineSummary.latency.p95Ms, runSummary.latency.p95Ms, false),
            "p99Ms" to Triple(baselineSummary.latency.p99Ms, runSummary.latency.p99Ms, false),
            "meanMs" to Triple(baselineSummary.latency.meanMs, runSummary.latency.meanMs, false),
            "maxMs" to Triple(baselineSummary.latency.maxMs, runSummary.latency.maxMs, false)
        )

        val metrics = raw.mapValues { (_, t) ->
            val (baselineVal, runVal, higherIsBetter) = t
            val deltaPct = if (baselineVal == 0.0) null else (runVal - baselineVal) / baselineVal * 100.0
            val verdict = if (deltaPct == null || abs(deltaPct) <= REGRESSION_THRESHOLD_PCT) {
                "unchanged"
            } else {
                val improved = if (higherIsBetter) deltaPct > 0 else deltaPct < 0
                if (improved) "improvement" else "regression"
            }
            MetricDelta(baselineVal, runVal, deltaPct, verdict)
        }

        return Comparison(
            metrics = metrics,
            regressions = metrics.filterValues { it.verdict == "regression" }.keys.toList(),
            improvements = metrics.filterValues { it.verdict == "improvement" }.keys.toList()
        )
    }
}
```

- [ ] **Step 4: Run unit tests to verify they pass**

Run: `./gradlew test --tests "io.boehm.core.ComparatorTest"`
Expected: PASS.

- [ ] **Step 5: Add failing handler test**

Add to `BoehmServerTest.kt`:

```kotlin
@Test
fun `compare_runs uses tagged baseline and reports deltas`() = runBlocking {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "cmp", """{"type":"http"}""")!!
    val baseRun = store.insertRun(sid, "tulip")!!
    val newRun = store.insertRun(sid, "tulip")!!
    val baseSummary = ("{\"durationSec\":30,\"totalRequests\":1500,\"throughputReqPerSec\":50.0," +
        "\"errorRatePct\":0.0,\"latency\":{\"minMs\":1.0,\"p50Ms\":10.0,\"p90Ms\":20.0," +
        "\"p95Ms\":20.0,\"p99Ms\":40.0,\"maxMs\":50.0,\"meanMs\":15.0,\"stdevMs\":4.0}}")
    val newSummary = baseSummary.replace("\"throughputReqPerSec\":50.0", "\"throughputReqPerSec\":25.0")
    store.updateRunStatus(baseRun, "completed", summary = baseSummary)
    store.updateRunStatus(newRun, "completed", summary = newSummary)
    store.setBaseline(sid, baseRun)

    val json = gson.fromJson(contentText(handlers.compareRuns(request(
        buildJsonObject { put("run_id", newRun) }))), Map::class.java)

    assertEquals(newRun, json["runId"])
    assertEquals(baseRun, json["baselineRunId"])
    @Suppress("UNCHECKED_CAST")
    val metrics = json["metrics"] as Map<String, Map<String, Any?>>
    assertEquals(-50.0, metrics["throughputReqPerSec"]!!["deltaPct"] as Double, 0.001)
    assertTrue((json["regressions"] as List<*>).contains("throughputReqPerSec"))
}

@Test
fun `compare_runs without baseline returns actionable error`() = runBlocking {
    store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
    val sid = store.insertScenario("tulip", "cmp2", """{"type":"http"}""")!!
    val run = store.insertRun(sid, "tulip")!!
    val err = handlers.compareRuns(request(buildJsonObject { put("run_id", run) }))
    assertTrue(err.isError ?: false)
    assertTrue(contentText(err).contains("No baseline"))
}
```

- [ ] **Step 6: Implement handler and register tool**

In `BoehmToolHandlers.kt` (strict typed parse helper first):

```kotlin
private fun parseSummary(json: String): io.boehm.model.Summary? = try {
    gson.fromJson(json, io.boehm.model.Summary::class.java)
} catch (_: Exception) { null }

// ── compare_runs ─────────────────────────────────────────────────────

suspend fun compareRuns(request: CallToolRequest): CallToolResult {
    val runId = argString(request, "run_id") ?: return errorResult(-32001, "Missing run_id")
    val run = store.getRun(runId) ?: return errorResult(-32002, "Run not found: $runId")
    val runSummary = run.summary?.let { parseSummary(it) }
        ?: return errorResult(-32003, "Run $runId has no summary (status=${run.status})")

    val baselineRunId = argString(request, "baseline_run_id")
        ?: store.getBaselineRunId(run.scenarioId)
        ?: return errorResult(
            -32004,
            "No baseline tagged for this scenario; pass baseline_run_id or call tag_baseline first"
        )
    val baseline = store.getRun(baselineRunId)
        ?: return errorResult(-32002, "Baseline run not found: $baselineRunId")
    val baselineSummary = baseline.summary?.let { parseSummary(it) }
        ?: return errorResult(-32003, "Baseline run $baselineRunId has no summary (status=${baseline.status})")

    val comparison = Comparator.compare(runSummary, baselineSummary)
    return textResult(mapOf(
        "runId" to run.id,
        "baselineRunId" to baseline.id,
        "metrics" to comparison.metrics.mapValues { (_, d) ->
            mapOf(
                "baseline" to d.baseline,
                "run" to d.run,
                "deltaPct" to d.deltaPct,
                "verdict" to d.verdict.name.lowercase()
            )
        },
        "regressions" to comparison.regressions,
        "improvements" to comparison.improvements
    ))
}
```

Register in `Main.buildServer` (`Comparator` is in the same package as handlers — no import needed there; Main needs none either since it only references `handlers.compareRuns`):

```kotlin
server.addTool(
    name = "compare_runs",
    description = "Compare a run against the scenario's tagged baseline (or an explicit baseline_run_id). Reports per-metric deltas with regression/improvement flags",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("run_id") { put("type", "string") }
            putJsonObject("baseline_run_id") { put("type", "string"); put("description", "Override the tagged baseline") }
        },
        required = listOf("run_id")
    )
) { request: CallToolRequest -> handlers.compareRuns(request) }
```

- [ ] **Step 7: Run full suite**

Run: `./gradlew test jacocoTestReport`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/boehm/core/Comparator.kt src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt \
        src/main/kotlin/io/boehm/Main.kt src/test/kotlin/io/boehm/core/ComparatorTest.kt \
        src/test/kotlin/io/boehm/core/BoehmServerTest.kt
git commit -m "feat: compare_runs MCP tool with direction-aware regression detection"
```

---

## Task 11: `cancel_run` MCP tool

Cooperative cancellation: queued/pending runs flip straight to `cancelled` in the Store; a running run's subprocess tree is destroyed by the Scheduler and recorded as `cancelled`.

**Files:**
- Modify: `src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt`
- Modify: `src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt`
- Modify: `src/main/kotlin/io/boehm/core/Scheduler.kt`
- Modify: `src/main/kotlin/io/boehm/core/Orchestrator.kt`
- Modify: `src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt`
- Modify: `src/main/kotlin/io/boehm/Main.kt`
- Test: `src/test/kotlin/io/boehm/core/SchedulerTest.kt`, `BoehmServerTest.kt`

- [ ] **Step 1: Read PerfToolAdapter before editing**

Read `src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt` to confirm exact members.

- [ ] **Step 2: Write the failing scheduler tests**

Extend `TestAdapter` at the bottom of `SchedulerTest.kt` with a `sleepMs` parameter (default keeps existing tests unchanged) and implement the two-arg `run` so it reports its process handle:

```kotlin
class TestAdapter(
    override val profile: String = "http-get",
    private val resultStatus: String = "completed",
    private val resultError: String? = null,
    private val shouldThrow: Boolean = false,
    private val sleepMs: Long = 100
) : PerfToolAdapter {
    override val name = "tulip"
    override val supportedTestTypes = listOf(TestType.HTTP)
    override val version = "0.1.0"
    override val toolVersions = listOf("0.x")

    override fun validate(testPlan: TestPlan) = emptyList<ValidationError>()

    override fun run(testPlan: TestPlan): RunResult =
        run(testPlan) { /* no process to report in mock */ }

    override fun run(testPlan: TestPlan, onProcessStart: (Process) -> Unit): RunResult {
        if (shouldThrow) throw RuntimeException("boom")
        Thread.sleep(sleepMs)
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

(needs `import java.lang.Process`.)

Add the tests:

```kotlin
@Test
fun `cancel queued run prevents execution`() {
    val slow = TestAdapter(sleepMs = 400)
    scheduler = Scheduler(store, listOf(slow))
    scheduler.start()

    val s1 = store.insertScenario("tulip", "q1", """{"type":"http","profile":"http-get"}""")!!
    val s2 = store.insertScenario("tulip", "q2", """{"type":"http","profile":"demo"}""")!!
    val r1 = store.insertRun(s1, "tulip")!!
    val r2 = store.insertRun(s2, "tulip")!!
    store.updateRunStatus(r1, "queued")
    store.updateRunStatus(r2, "queued")

    assertTrue(scheduler.requestCancel(r2))

    waitForStatus(r1, "completed", 5)
    Thread.sleep(600)  // give the scheduler a poll cycle to prove it never ran r2
    assertEquals("cancelled", store.getRun(r2)!!.status)
    scheduler.stop()
}

@Test
fun `cancel running run kills work and records cancelled`() {
    val slow = TestAdapter(sleepMs = 3000)
    scheduler = Scheduler(store, listOf(slow))
    scheduler.start()

    val s1 = store.insertScenario("tulip", "r1", """{"type":"http","profile":"http-get"}""")!!
    val r1 = store.insertRun(s1, "tulip")!!
    store.updateRunStatus(r1, "queued")

    waitForStatus(r1, "running", 5)
    assertTrue(scheduler.requestCancel(r1))
    val run = waitForStatus(r1, "cancelled", 10)
    assertEquals("cancelled", run!!.status)
    scheduler.stop()
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.boehm.core.SchedulerTest"`
Expected: COMPILE ERROR — `requestCancel` / two-arg `run` unresolved.

- [ ] **Step 4: Extend adapter interface with process listener**

In `PerfToolAdapter.kt`, add (keep all existing members; add `import java.lang.Process`):

```kotlin
/**
 * Runs the plan. Implementations that spawn a subprocess must invoke
 * [onProcessStart] once the process exists so the caller can cancel it.
 * Default implementation ignores the listener (in-memory adapters).
 */
fun run(testPlan: TestPlan, onProcessStart: (Process) -> Unit): RunResult = run(testPlan)
```

In `CatalogAdapter.kt`, rename the existing `override fun run(testPlan: TestPlan): RunResult` to the two-arg form and register the process right after starting it:

```kotlin
override fun run(testPlan: TestPlan, onProcessStart: (Process) -> Unit): RunResult {
    // ... existing body up to process start ...
    val process = ProcessBuilder("bash", "-c", command)
        .redirectErrorStream(true)
        .start()
    onProcessStart(process)
    // ... rest of existing body unchanged ...
}

override fun run(testPlan: TestPlan): RunResult = run(testPlan) { /* no listener */ }
```

- [ ] **Step 5: Add cancellation to Scheduler**

In `Scheduler.kt`, add fields and methods (imports: `java.util.concurrent.atomic.AtomicReference`) :

```kotlin
// Cancellation state. activeRunId/activeProcess are written by the scheduler
// thread and read/written by MCP threads via requestCancel — hence atomics.
private val activeRunId = AtomicReference<String?>(null)
private val activeProcess = AtomicReference<Process?>(null)
private val cancelRequested = java.util.Collections.newKeySet<String>()
```

Change the invocation inside `pollQueue` from

```kotlin
store.updateRunStatus(pending.id, "running")
val result = adapter.run(testPlan)
```

to

```kotlin
store.updateRunStatus(pending.id, "running")
activeRunId.set(pending.id)
val result = try {
    adapter.run(testPlan) { p -> activeProcess.set(p) }
} finally {
    activeProcess.set(null)
    activeRunId.set(null)
}
val finalStatus = if (cancelRequested.remove(pending.id)) "cancelled" else result.status
store.updateRunStatus(pending.id, finalStatus, summary = summaryJson,
    error = error, rawOutputPath = result.rawOutputPath, metadata = metadataJson)
```

(`summaryJson`/`error`/`metadataJson` computation stays between the two blocks as today.)

Add the public method:

```kotlin
/**
 * Cancel a run. Queued/pending runs are flipped to 'cancelled' directly;
 * the currently-running run has its subprocess killed and will be recorded
 * as 'cancelled' when the adapter call returns.
 */
fun requestCancel(runId: String): Boolean {
    val run = store.getRun(runId) ?: return false
    return when (run.status) {
        "pending", "queued" -> store.cancelQueuedRun(runId)
        "running" -> {
            if (runId != activeRunId.get()) return false
            cancelRequested.add(runId)
            activeProcess.get()?.destroyForcibly()
            true
        }
        else -> false
    }
}
```

Note: when the process is destroyed, `adapter.run` returns a failed/parse-error result, but `finalStatus` overrides it to `cancelled`. If the adapter already returned normally before the kill landed, the run completes normally and `requestCancel`'s flag is removed harmlessly — acceptable v1 semantics.

- [ ] **Step 6: Expose via Orchestrator and handler**

In `Orchestrator.kt` add a sealed result and delegate (the scheduler is lazily created — create it here too so cancellation works before any submit):

```kotlin
sealed class CancelResult {
    data class Cancelled(val status: String) : CancelResult()
    data class NotFound(val runId: String) : CancelResult()
    data class NotCancellable(val runId: String, val status: String) : CancelResult()
}

fun cancelRun(runId: String): CancelResult {
    val run = store.getRun(runId) ?: return CancelResult.NotFound(runId)
    if (run.status !in listOf("pending", "queued", "running")) {
        return CancelResult.NotCancellable(runId, run.status)
    }
    ensureScheduler()
    val ok = scheduler!!.requestCancel(runId)
    return if (ok) CancelResult.Cancelled(runId) else CancelResult.NotCancellable(runId, run.status)
}

private fun ensureScheduler() {
    if (scheduler == null) {
        scheduler = Scheduler(store, adapters.values.toList())
        scheduler!!.start()
    }
}
```

and replace the lazy-init block inside `submitRun` with `ensureScheduler()`.

In `BoehmToolHandlers.kt` (constructor gains the orchestrator already — it has it):

```kotlin
// ── cancel_run ───────────────────────────────────────────────────────

suspend fun cancelRun(request: CallToolRequest): CallToolResult {
    val runId = argString(request, "run_id") ?: return errorResult(-32001, "Missing run_id")
    return when (val result = orchestrator.cancelRun(runId)) {
        is Orchestrator.CancelResult.Cancelled ->
            textResult(mapOf("runId" to runId, "status" to "cancelling"))
        is Orchestrator.CancelResult.NotFound ->
            errorResult(-32002, "Run not found: ${result.runId}")
        is Orchestrator.CancelResult.NotCancellable ->
            errorResult(-32003, "Run ${result.runId} cannot be cancelled (status=${result.status})")
    }
}
```

Register in `Main.buildServer`:

```kotlin
server.addTool(
    name = "cancel_run",
    description = "Cancel a pending or running performance test run",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("run_id") { put("type", "string") }
        },
        required = listOf("run_id")
    )
) { request: CallToolRequest -> handlers.cancelRun(request) }
```

- [ ] **Step 7: Run full suite**

Run: `./gradlew test jacocoTestReport`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/boehm/adapters/PerfToolAdapter.kt src/main/kotlin/io/boehm/catalog/CatalogAdapter.kt \
        src/main/kotlin/io/boehm/core/Scheduler.kt src/main/kotlin/io/boehm/core/Orchestrator.kt \
        src/main/kotlin/io/boehm/core/BoehmToolHandlers.kt src/main/kotlin/io/boehm/Main.kt \
        src/test/kotlin/io/boehm/core/
git commit -m "feat: cancel_run MCP tool with subprocess-tree kill for active runs"
```

---

## Task 12: Remove dead AuthHandler; update docs; final verification

**Files:**
- Delete: `src/main/kotlin/io/boehm/auth/AuthHandler.kt`, `src/test/kotlin/io/boehm/auth/AuthHandlerTest.kt`
- Modify: `README.md`, `AGENTS.md`

- [ ] **Step 1: Verify AuthHandler is truly unused**

Run: `grep -rn "AuthHandler" src/ --include="*.kt" | grep -v "auth/AuthHandler\|AuthHandlerTest"`
Expected: no matches outside those files. If anything references it, stop and reassess instead of deleting.

- [ ] **Step 2: Delete the dead code**

```bash
rm src/main/kotlin/io/boehm/auth/AuthHandler.kt src/test/kotlin/io/boehm/auth/AuthHandlerTest.kt
rmdir src/main/kotlin/io/boehm/auth src/test/kotlin/io/boehm/auth
```

(stdio transport relies on the spawning process for auth per the SPEC comment in `Main.kt`; git history preserves the class if a remote transport needs it later.)

- [ ] **Step 3: Update README.md**

Replace the MCP tools table with:

```markdown
### MCP tools

| Tool | Input | Output |
|------|-------|--------|
| `list_adapters` | — | Available tools + supported profiles |
| `run_test` | `tool`, `test_name`, `test_plan` | `runId`, status `queued` |
| `get_run` | `run_id` | Full result with latency (incl. mean/stdev), throughput, error rate |
| `get_run_progress` | `run_id` | Status, real progress %, current stage, elapsed/remaining estimate |
| `server_status` | — | Queue depth, currently running (+ progress), uptime, adapters |
| `list_runs` | `tool?`, `test_name?`, `limit?` | Recent completed/failed/cancelled runs, newest first |
| `tag_baseline` | `run_id` | Tags a completed run as its scenario's baseline |
| `compare_runs` | `run_id`, `baseline_run_id?` | Per-metric deltas vs baseline with regression/improvement flags |
| `cancel_run` | `run_id` | Cancels a queued/pending run or kills a running one |
```

And update the "Using with opencode" examples to include:

```
use boehm to list recent runs
use boehm to tag run <runId> as the baseline
use boehm to compare run <runId> against the baseline
use boehm to cancel run <runId>
```

Also note under "How it works": adapters whose output schema has no parser (gatling, vegeta, wrk) are not registered until parsers exist.

- [ ] **Step 4: Update AGENTS.md**

Under a new "Implemented Capabilities" bullet list near the top, add: run history (`list_runs`), per-scenario baselines (`tag_baseline`), direction-aware comparison with regression flags (`compare_runs`), cancellation (`cancel_run`), timeout validation, ISO-8601 timestamps throughout, thread-safe Store.

- [ ] **Step 5: Full verification**

Run: `./gradlew build jacocoTestReport`
Expected: BUILD SUCCESSFUL, all tests pass. Open `build/reports/jacoco/test/html/index.html` and confirm instruction coverage ≥ 80% per AGENTS.md quality standards. If below, add tests for uncovered new branches (especially Scheduler cancellation paths) before finishing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: remove dead AuthHandler, document history/baseline/comparison tools"
```

---

## Self-review checklist (executor: do at the end)

- [ ] All nine MCP tools registered and discoverable (`tools/list`)
- [ ] Resubmitting a scenario name with a changed plan updates the stored plan (Task 1)
- [ ] All persisted timestamps sort lexicographically (ISO-8601 everywhere)
- [ ] A plan with `timeout_sec < duration_sec + warmup_sec + 10` is rejected at submit time with a clear message
- [ ] gatling/vegeta/wrk profiles are absent from `list_adapters`
- [ ] Every parser populates `latency.meanMs`/`latency.stdevMs`
- [ ] `compare_runs` works both with a tagged baseline and explicit `baseline_run_id`
- [ ] Cancelling a queued run never executes it; cancelling a running one records status `cancelled`
- [ ] `./gradlew build` green; coverage ≥ 80%

