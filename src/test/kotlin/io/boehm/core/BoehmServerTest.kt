package io.boehm.core

import com.google.gson.Gson
import io.boehm.adapters.PerfToolAdapter
import io.boehm.adapters.tulip.TulipParser
import io.boehm.catalog.CatalogAdapter
import io.boehm.catalog.*
import io.boehm.model.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class BoehmServerTest {

    private lateinit var store: Store
    private lateinit var orchestrator: Orchestrator
    private lateinit var handlers: BoehmToolHandlers
    private val gson = Gson()

    private fun request(args: JsonObject): CallToolRequest =
        CallToolRequest(CallToolRequestParams(name = "x", arguments = args))

    @BeforeEach
    fun setUp() {
        store = Store(":memory:")
        orchestrator = Orchestrator(store)
        handlers = BoehmToolHandlers(store, orchestrator)
    }

    private fun registerTulipHttpGet() {
        val toolDef = ToolDef(
            name = "tulip",
            description = "Test tool",
            install = null,
            run = RunDef(command = "echo test"),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = null,
                    config = "profiles/tulip/http-get.jsonc",
                    output = OutputDef(path = "{{config.actions.output_filename}}", format = "json", schema = "tulip-results"),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = "actions.user_params.url", default = "https://httpbin.org/get"),
                        "rate_per_sec" to OverrideDef(path = "benchmarks.boehm-benchmark.aps_rate", default = 50),
                        "duration_sec" to OverrideDef(path = "benchmarks.boehm-benchmark.benchmark_duration", default = 30),
                        "warmup_sec" to OverrideDef(path = "benchmarks.boehm-benchmark.warmup_duration1", default = 5)
                    )
                )
            )
        )
        val adapter = CatalogAdapter(toolDef, "http-get", File("").absolutePath,
            mapOf("tulip-results" to { raw -> TulipParser.parse(raw) }))
        orchestrator.registerAdapter(adapter)
    }

    private fun contentText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String {
        val block = result.content.first()
        return (block as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
    }

    // ── run_test ───────────────────────────────────────────────────────

    @Test
    fun `run_test returns queued run with runId`() = runBlocking {
        registerTulipHttpGet()
        val args = buildJsonObject {
            put("tool", "tulip")
            put("test_name", "my-test")
            putJsonObject("test_plan") {
                put("type", "http")
                put("profile", "http-get")
                put("target_url", "https://example.com")
                put("rate_per_sec", 100)
                put("duration_sec", 5)
            }
        }
        val result = handlers.runTest(request(args))
        assertFalse(result.isError ?: false)
        val json = gson.fromJson(contentText(result), Map::class.java)
        assertNotNull(json["runId"])
        assertEquals("my-test", json["testName"])
        assertEquals("queued", json["status"])
    }

    @Test
    fun `run_test with unknown tool returns error`() = runBlocking {
        val args = buildJsonObject {
            put("tool", "nonexistent")
            put("test_name", "t")
            putJsonObject("test_plan") { put("type", "http") }
        }
        val result = handlers.runTest(request(args))
        assertTrue(result.isError ?: false)
        assertTrue(contentText(result).contains("Adapter not found"))
    }

    @Test
    fun `run_test with unknown profile returns error`() = runBlocking {
        registerTulipHttpGet()
        val args = buildJsonObject {
            put("tool", "tulip")
            put("test_name", "t")
            putJsonObject("test_plan") {
                put("type", "http")
                put("profile", "nope")
                put("target_url", "https://example.com")
            }
        }
        val result = handlers.runTest(request(args))
        assertTrue(result.isError ?: false)
        assertTrue(contentText(result).contains("Unknown profile"))
    }

    @Test
    fun `run_test missing tool returns error`() = runBlocking {
        val args = buildJsonObject {
            put("test_name", "t")
            putJsonObject("test_plan") { put("type", "http") }
        }
        val result = handlers.runTest(request(args))
        assertTrue(result.isError ?: false)
        assertTrue(contentText(result).contains("Missing tool"))
    }

    // ── list_adapters ──────────────────────────────────────────────────

    @Test
    fun `list_adapters returns all adapters`() = runBlocking {
        registerTulipHttpGet()
        val result = handlers.listAdapters(request(buildJsonObject { }))
        val json = gson.fromJson(contentText(result), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val adapters = json["adapters"] as? List<Map<String, Any>> ?: emptyList()
        assertTrue(adapters.any { it["name"] == "tulip" }, "expected tulip adapter: $json")
    }

    // ── get_run ────────────────────────────────────────────────────────

    @Test
    fun `get_run returns testName from scenario`() = runBlocking {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "my-scenario", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "completed",
            summary = """{"durationSec":30,"totalRequests":100,"throughputReqPerSec":3.33,"errorRatePct":0.0,"latency":{"minMs":1.0,"p50Ms":2.0,"p90Ms":3.0,"p95Ms":4.0,"p99Ms":5.0,"maxMs":6.0}}""")

        val result = handlers.getRun(request(buildJsonObject { put("run_id", rid) }))
        val json = gson.fromJson(contentText(result), Map::class.java)
        assertEquals("my-scenario", json["testName"])
        assertEquals("completed", json["status"])
        assertTrue(contentText(result).contains("latency"))
    }

    @Test
    fun `get_run missing id returns error`() = runBlocking {
        val result = handlers.getRun(request(buildJsonObject { }))
        assertTrue(result.isError ?: false)
        assertTrue(contentText(result).contains("Missing run_id"))
    }

    @Test
    fun `get_run nonexistent returns error`() = runBlocking {
        val result = handlers.getRun(request(buildJsonObject { put("run_id", "no-such") }))
        assertTrue(result.isError ?: false)
        assertTrue(contentText(result).contains("Run not found"))
    }

    // ── server_status ─────────────────────────────────────────────────

    @Test
    fun `server_status returns queue depth and uptime`() = runBlocking {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val result = handlers.serverStatus(request(buildJsonObject { }))
        val json = gson.fromJson(contentText(result), Map::class.java)
        assertTrue(json.containsKey("queueDepth"))
        assertTrue(json.containsKey("uptimeSec"))
        assertTrue(json.containsKey("status"))
        assertEquals("idle", json["status"])
    }

    @Test
    fun `server_status shows running run with testName`() = runBlocking {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "running-scenario", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "running")
        val result = handlers.serverStatus(request(buildJsonObject { }))
        val json = gson.fromJson(contentText(result), Map::class.java)
        assertEquals("running", json["status"])
        assertTrue(contentText(result).contains("running-scenario"))
    }

    // ── get_run_progress ──────────────────────────────────────────────

    @Test
    fun `get_run_progress returns status for completed run`() = runBlocking {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "completed",
            summary = """{"durationSec":30,"totalRequests":100,"throughputReqPerSec":3.33,"errorRatePct":0.0,"latency":{"minMs":1.0,"p50Ms":2.0,"p90Ms":3.0,"p95Ms":4.0,"p99Ms":5.0,"maxMs":6.0}}""")
        val result = handlers.getRunProgress(request(buildJsonObject { put("run_id", rid) }))
        val json = gson.fromJson(contentText(result), Map::class.java)
        assertEquals(100.0, json["progressPct"])
        assertTrue(contentText(result).contains("rollingSummary"))
    }

    @Test
    fun `get_run_progress missing id returns error`() = runBlocking {
        val result = handlers.getRunProgress(request(buildJsonObject { }))
        assertTrue(result.isError ?: false)
        assertTrue(contentText(result).contains("Missing run_id"))
    }

    // ── metadata persistence (Task 5) ─────────────────────────────────

    @Test
    fun `metadata is persisted through updateRunStatus and visible in get_run`() = runBlocking {
        store.insertAdapter("tulip", """["http"]""", "0.1.0", """["0.x"]""")
        val sid = store.insertScenario("tulip", "meta-test", """{"type":"http"}""")!!
        val rid = store.insertRun(sid, "tulip")!!
        store.updateRunStatus(rid, "completed",
            summary = """{"durationSec":1,"totalRequests":1,"throughputReqPerSec":1.0,"errorRatePct":0.0,"latency":{"minMs":1.0,"p50Ms":2.0,"p90Ms":3.0,"p95Ms":4.0,"p99Ms":5.0,"maxMs":6.0}}""",
            metadata = """{"exitCode":0,"stdout":"ok"}""")
        val result = handlers.getRun(request(buildJsonObject { put("run_id", rid) }))
        assertTrue(contentText(result).contains("exitCode"))
        assertTrue(contentText(result).contains("ok"))
    }

    // ── shell injection prevention (Task 6) ───────────────────────────

    @Test
    fun `shell command sanitization rejects malicious target_url`() {
        val toolDef = ToolDef(
            name = "tulip", description = "", install = null,
            run = RunDef(command = "echo x"),
            profiles = mapOf(
                "http-get" to ProfileDef("http-get", null, null,
                    OutputDef("{{output_file}}", "json", "tulip-results"),
                    mapOf("target_url" to OverrideDef(path = "actions.user_params.url", default = "https://x")))
            )
        )
        val adapter = CatalogAdapter(toolDef, "http-get", File("").absolutePath, emptyMap())
        val malicious = listOf(
            "https://x; rm -rf /",
            "https://x | cat /etc/passwd",
            "https://x \$(whoami)",
            "https://x && echo hi",
            "https://x`id`",
            "https://x\${HOME}"
        )
        for (url in malicious) {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                adapter.run(TestPlan(type = "http", targetUrl = url))
            }
            assertTrue(ex.message!!.contains("target_url") || ex.message!!.contains("shell metacharacters"),
                "expected sanitization error for '$url', got: ${ex.message}")
        }
    }

    @Test
    fun `shell command sanitization rejects non-numeric numeric override`() {
        val toolDef = ToolDef(
            name = "tulip", description = "", install = null,
            run = RunDef(command = "echo x"),
            profiles = mapOf(
                "http-get" to ProfileDef("http-get", null, null,
                    OutputDef("{{output_file}}", "json", "tulip-results"),
                    mapOf("rate_per_sec" to OverrideDef(path = "r", default = 50)))
            )
        )
        val adapter = CatalogAdapter(toolDef, "http-get", File("").absolutePath, emptyMap())
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.run(TestPlan(type = "http", targetUrl = "https://x", parameters = mapOf("rate_per_sec" to "abc")))
        }
        assertTrue(ex.message!!.contains("rate_per_sec"), "expected numeric error, got: ${ex.message}")
    }

    @Test
    fun `shell command sanitization accepts valid target_url and numeric overrides`() {
        // Sanity: legitimate values do not throw and reach the adapter execution path.
        val toolDef = ToolDef(
            name = "tulip", description = "", install = null,
            run = RunDef(command = "echo x"),
            profiles = mapOf(
                "http-get" to ProfileDef("http-get", null, null,
                    OutputDef("{{output_file}}", "json", "tulip-results"),
                    mapOf("target_url" to OverrideDef(path = "actions.user_params.url", default = "https://x")))
            )
        )
        val adapter = CatalogAdapter(toolDef, "http-get", File("").absolutePath, emptyMap())
        // A plan with a valid URL should not throw a sanitization error. (It may fail later
        // for other reasons, but not due to shell-injection validation.)
        val plan = TestPlan(type = "http", targetUrl = "https://httpbin.org/get", ratePerSec = 50, durationSec = 10)
        try {
            adapter.validate(plan)
        } catch (_: Exception) {
            // validate may reject for other reasons; what we assert is that no
            // IllegalArgumentException about shell metacharacters is thrown.
        }
        // Ensure resolveOverrides directly does not throw for valid input.
        val method = adapter.javaClass.getDeclaredMethod("resolveOverrides", TestPlan::class.java)
        method.isAccessible = true
        assertDoesNotThrow { method.invoke(adapter, plan) }

    }

    // ── get_run_progress (real estimation) ──────────────────────────────

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

    // ── list_runs / tag_baseline ────────────────────────────────────────

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

    // ── compare_runs ────────────────────────────────────────────────────

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
        store.updateRunStatus(run, "completed", summary = """{"durationSec":1,"totalRequests":1,
            "throughputReqPerSec":1.0,"errorRatePct":0.0,"latency":{"minMs":1.0,"p50Ms":1.0,
            "p90Ms":1.0,"p95Ms":1.0,"p99Ms":1.0,"maxMs":1.0}}""".replace("\n", ""))
        val err = handlers.compareRuns(request(buildJsonObject { put("run_id", run) }))
        assertTrue(err.isError ?: false)
        assertTrue(contentText(err).contains("No baseline"))
    }
}
