# Boehm — Phase 1: MCP Server + Tulip Adapter

## Goal

A working MCP server that accepts `run_test` calls, shells out to Tulip CLI, captures its JSON output, persists results to SQLite, and returns structured data to the agent.

The agent can start a test, poll for progress, get the final result, and check server status. All calls authenticated via bearer token.

## Scope

- Gradle project (Kotlin/JVM) running a JSON-RPC 2.0 server over stdio
- Bearer token auth on every MCP call
- SQLite database (`~/.boehm/boehm.db`) with `adapters`, `test_scenarios`, `runs` tables
- Run scheduler — serial execution, one run at a time
- Tulip adapter — shell exec Tulip CLI, parse native JSON output into normalized `RunResult`
- 5 MCP tools: `list_adapters`, `run_test`, `get_run`, `server_status`, `get_run_progress`
- Unit tests + integration test that runs Tulip CLI against a real target
- Raw output saved to `~/.boehm/outputs/` for debugging

## Tools

| Tool | Input | Output |
|---|---|---|
| `list_adapters` | — | Tool names + supported test types |
| `run_test` | `tool`, `test_name`, `test_plan` | `run_id`, initial status (`queued`) |
| `get_run` | `run_id` | Full `RunResult` |
| `server_status` | — | Queue depth, current run + progress, queued runs |
| `get_run_progress` | `run_id` | Live rolling metrics during an active run |

### Example call: run_test

**Request:**
```json
{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "run_test",
    "arguments": {
      "tool": "tulip",
      "test_name": "http-get",
      "test_plan": {
        "type": "http", "target_url": "https://httpbin.org/get",
        "rate_per_sec": 100, "duration_sec": 30, "warmup_sec": 5
      }
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0", "id": 1, "result": {
    "runId": "uuid", "tool": "tulip", "testName": "http-get",
    "status": "queued", "summary": null
  }
}
```

### Example call: get_run (completed)

```json
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "tool": "tulip", "testName": "http-get",
    "runId": "uuid", "status": "completed",
    "summary": {
      "durationSec": 30, "totalRequests": 2850,
      "throughputReqPerSec": 95.0, "errorRatePct": 0.0,
      "latency": { "minMs": 2.1, "p50Ms": 8.5, "p90Ms": 22.3, "p95Ms": 35.0, "p99Ms": 68.1, "maxMs": 210.0 }
    },
    "rawOutputPath": "/home/user/.boehm/outputs/http-get/2026-07-20T10-00-00.json",
    "metadata": { "tulip_version": "x.y.z" }
  }
}
```

### Example call: server_status

```json
{
  "jsonrpc": "2.0", "id": 3, "result": {
    "status": "running", "uptime_sec": 3600, "queue_depth": 0,
    "currently_running": {
      "run_id": "uuid", "tool": "tulip", "test_name": "http-get",
      "progress_pct": 67, "current_stage": "running",
      "elapsed_sec": 20, "estimated_remaining_sec": 10
    },
    "queued_runs": [],
    "server_version": "0.1.0"
  }
}
```

### Example call: get_run_progress

```json
{
  "jsonrpc": "2.0", "id": 4, "result": {
    "run_id": "uuid", "status": "running", "progress_pct": 67,
    "current_stage": "running",
    "stage_progress": {
      "warmup": { "status": "completed", "duration_sec": 5 },
      "running": { "status": "in_progress", "elapsed_sec": 20, "estimated_remaining_sec": 10 }
    },
    "rolling_summary": {
      "duration_sec": 20, "totalRequests": 1900,
      "throughput_req_per_sec": 95.0, "error_rate_pct": 0.0,
      "latency": { "minMs": 2.1, "p50Ms": 8.5, "p90Ms": 22.3, "p95Ms": 35.0, "p99Ms": null, "maxMs": 210.0 }
    }
  }
}
```

## Authentication

Bearer token passed in the MCP `initialize` handshake:

```json
{
  "jsonrpc": "2.0", "id": 0, "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05", "capabilities": {},
    "clientInfo": { "name": "claude-code", "version": "1.0.0" },
    "auth_token": "boehm_sk_abc123..."
  }
}
```

- If token is missing or invalid, the server responds with MCP error `-32005` ("Authentication failed") and does not process any tools
- Phase 1 has one role: `runner` (all tools). Admin token management is Phase 2+
- Tokens stored as bcrypt hashes, generated via `boehm token create --name ci-token`

## Data Flow

1. Agent sends `run_test` with bearer token
2. Server validates token, persists a `pending` run in SQLite
3. Run moves to `queued` — scheduler picks it up when executor is free
4. Status `running` — server shells out to `tulip <args>`, captures stdout
5. During execution, Tulip's JSON progress lines are parsed into rolling metrics, served via `get_run_progress`
6. On completion, Tulip's final JSON output is parsed into normalized `RunResult`
7. Run completes — status `completed`, full result available via `get_run`

## SQLite Schema (Phase 1)

```sql
CREATE TABLE adapters (
    name TEXT PRIMARY KEY,
    supported_types TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    tool_versions TEXT NOT NULL
);

CREATE TABLE test_scenarios (
    id TEXT PRIMARY KEY,
    tool TEXT NOT NULL REFERENCES adapters(name),
    name TEXT NOT NULL,
    test_plan JSON NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(tool, name)
);

CREATE TABLE runs (
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
);

CREATE TABLE schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

## Adapter Interface

```kotlin
interface PerfToolAdapter {
    val name: String
    val supportedTestTypes: List<TestType>
    val version: String
    val toolVersions: List<String>

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan): RunResult
}
```

The Tulip adapter shells out to `tulip` CLI, captures JSON stdout, parses into `RunResult`. Progress events are extracted from Tulip's live JSON output lines during execution.

## RunResult Schema (Phase 1)

```json
{
  "tool": "tulip",
  "testName": "http-get",
  "timestamp": "2026-07-20T10:00:00Z",
  "runId": "uuid",
  "status": "completed",
  "summary": {
    "durationSec": 30,
    "totalRequests": 2850,
    "throughputReqPerSec": 95.0,
    "errorRatePct": 0.02,
    "latency": { "minMs": 1.2, "p50Ms": 8.5, "p90Ms": 22.1, "p95Ms": 35.0, "p99Ms": 68.1, "maxMs": 250.0 }
  },
  "rawOutputPath": "/path/to/output.json",
  "metadata": {}
}
```

## Error Handling

| Scenario | Behavior |
|---|---|
| Invalid or missing token | Error code `-32005`, message "Authentication failed" |
| Tool binary not found | Status `failed`, error "tulip: command not found" |
| Test exceeds timeout | Kill subprocess, status `failed`, partial metrics if available |
| Invalid test plan | Error code `-32001`, validation messages |
| Tulip CLI returns non-zero | Status `failed`, error from stderr |
| Internal server error | Error code `-32603`, message logged server-side |

## Acceptance Criteria

1. `./gradlew build` succeeds. Server starts and accepts stdio JSON-RPC 2.0
2. Calling `initialize` without auth token returns error `-32005` and refuses all tools
3. Calling `initialize` with valid token returns success and enables all tools
4. `list_adapters` returns at least one adapter (`tulip`)
5. `run_test` with a valid test plan returns `run_id` with status `queued`. Polling `get_run` eventually returns `completed` with all latency percentiles populated
6. `server_status` returns accurate queue depth and current run info when a test is running, and `"status": "idle"` when nothing is running
7. `get_run_progress` returns rolling metrics with `progress_pct` during an active run, and transitions to completed result after the run finishes
8. Issuing two `run_test` calls in succession results in the second being `queued` until the first completes. Both eventually complete successfully
9. An integration test shells out to `tulip` CLI against httpbin.org and asserts a valid `RunResult` with positive throughput
10. Raw output file exists at the path specified in `rawOutputPath` and contains Tulip's original JSON output

## File Layout

```
Boehm/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/kotlin/io/boehm/
│   │   ├── Main.kt                    # Server entry point, MCP stdio loop
│   │   ├── auth/
│   │   │   └── AuthHandler.kt         # Bearer token validation
│   │   ├── core/
│   │   │   ├── McpHandler.kt          # JSON-RPC dispatcher
│   │   │   ├── Orchestrator.kt        # Route test plans, manage runs
│   │   │   ├── Scheduler.kt           # Run queue, serial execution
│   │   │   └── Store.kt              # SQLite operations
│   │   ├── model/
│   │   │   ├── TestPlan.kt
│   │   │   ├── RunResult.kt
│   │   │   └── ProgressEvent.kt
│   │   └── adapters/
│   │       ├── PerfToolAdapter.kt     # Interface
│   │       └── tulip/
│   │           ├── TulipAdapter.kt    # CLI exec, output capture
│   │           └── TulipParser.kt     # Parse native JSON → RunResult
│   └── test/kotlin/io/boehm/
│       ├── adapter/
│       │   └── TulipAdapterTest.kt
│       └── integration/
│           └── TulipIntegrationTest.kt
├── AGENTS.md
├── README.md
├── .gitignore
└── docs/superpowers/specs/
    ├── 2026-07-20-boehm-phase-1.md
    └── 2026-07-20-boehm-vision.md
```

## Dependencies

| Dependency | Purpose |
|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | Language runtime |
| `org.xerial:sqlite-jdbc` | SQLite driver |
| `com.google.code.gson:gson` | JSON parsing (Tulip output, test plans) |
| `org.junit.jupiter:junit-jupiter` | Testing |
| Tulip CLI | Installed separately, discovered via `PATH` |
