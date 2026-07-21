# Architecture — Phase 1 (revised)

## Review Summary

The original proposal is directionally correct, but it is still too optimistic for a first MCP server release. For Phase 1, the design should favor simplicity, durability, and predictable behavior over extensibility. The server must be reliable under the MCP stdio transport, survive process restarts, and avoid turning a small tool into a general-purpose execution platform.

The biggest design corrections are:

- Make execution asynchronous from the start. `run_test` should return quickly with a persisted run record, not block until the load test completes.
- Treat progress as durable state, not as a transient in-memory object. A stdio server can restart, and the agent may poll after a delay.
- Keep the adapter boundary narrow. The adapter should own process execution, output capture, and parsing; the rest of the system should only consume normalized state.
- Fail fast on invalid input and tool absence. A Phase 1 server should be explicit and predictable, not attempt to recover from every failure mode.

---

## System Context

```mermaid
graph TB
    Agent["AI Agent<br/>(Claude Code, Cline, etc.)"]
    Boehm["Boehm MCP Server<br/>Kotlin/JVM"]
    Tulip["Tulip CLI"]
    Target["Target System<br/>(e.g. httpbin.org)"]
    SQLite[(SQLite<br/>~/.boehm/boehm.db)]
    OutputDir[(Raw Outputs<br/>~/.boehm/outputs/)]

    Agent -- "JSON-RPC 2.0 over stdio<br/>bearer token auth" --> Boehm
    Boehm -- "spawn + capture" --> Tulip
    Tulip -- "HTTP load" --> Target
    Boehm -- "persist runs, progress, metadata" --> SQLite
    Boehm -- "write raw stdout/stderr" --> OutputDir
```

---

## Core Design Principles

1. The MCP server is a request/response entrypoint, not the execution engine itself.
   - The stdio loop should accept requests, validate them, and hand off work to a background executor.
   - Tool calls should not block the event loop for long-running tests.

2. The authoritative source of truth is SQLite.
   - Runs, queue state, progress snapshots, and errors should be persisted.
   - In-memory state is only a cache.

3. The adapter boundary is intentionally small.
   - `PerfToolAdapter` should expose `validate`, `run`, and optionally a progress callback.
   - No other layer should need to know about Tulip-specific stdout formats.

4. Phase 1 should be opinionated and boring.
   - One adapter: Tulip.
   - One execution policy: serial execution.
   - One auth model: connection-scoped bearer token.

---

## Component Architecture

```mermaid
graph TB
    subgraph "Protocol Layer"
        McpServer["McpServer.kt<br/>stdio JSON-RPC loop"]
        RequestRouter["RequestRouter.kt<br/>dispatch tools"]
        AuthGuard["AuthGuard.kt<br/>validate token per session"]
    end

    subgraph "Application Layer"
        RunService["RunService.kt<br/>create/read/update runs"]
        Scheduler["Scheduler.kt<br/>serial queue + worker"]
        RunStore["RunStore.kt<br/>SQLite + migrations"]
        ProgressStore["ProgressStore.kt<br/>latest progress snapshot"]
    end

    subgraph "Adapter Layer"
        AdapterInterface["PerfToolAdapter"]
        TulipAdapter["TulipAdapter.kt"]
        TulipParser["TulipParser.kt"]
        ProcessRunner["ProcessRunner.kt<br/>timeout + output capture"]
    end

    subgraph "Model"
        Models["TestPlan, RunResult, ProgressSnapshot"]
    end

    Agent["AI Agent"]

    Agent --> McpServer
    McpServer --> AuthGuard
    McpServer --> RequestRouter
    RequestRouter --> RunService
    RunService --> Scheduler
    RunService --> RunStore
    RunService --> ProgressStore
    Scheduler --> TulipAdapter
    TulipAdapter --> ProcessRunner
    TulipAdapter --> TulipParser
    TulipAdapter --> Models
    TulipParser --> Models
    RunStore --> SQLite[(SQLite)]
```

---

## Request Flow

### Initialize

```mermaid
sequenceDiagram
    participant Agent
    participant McpServer
    participant AuthGuard

    Agent->>McpServer: initialize { auth_token }
    McpServer->>AuthGuard: validate(token)
    alt invalid or missing
        AuthGuard-->>McpServer: reject
        McpServer-->>Agent: error -32005
    else valid
        AuthGuard-->>McpServer: allow session
        McpServer-->>Agent: initialized
    end
```

### run_test

```mermaid
sequenceDiagram
    participant Agent
    participant McpServer
    participant RunService
    participant Scheduler
    participant Store
    participant TulipAdapter

    Agent->>McpServer: tools/call run_test
    McpServer->>RunService: createRun(testPlan)
    RunService->>Store: INSERT pending run
    Store-->>RunService: run_id
    RunService-->>McpServer: { runId, status: "queued" }
    McpServer-->>Agent: result

    RunService->>Scheduler: enqueue(run_id)
    Scheduler->>TulipAdapter: execute(run_id, testPlan)
    TulipAdapter->>TulipAdapter: spawn process, capture output, write raw file
    TulipAdapter->>Store: update status/progress/final result
```

### get_run / get_run_progress / server_status

```mermaid
sequenceDiagram
    participant Agent
    participant McpServer
    participant RunService
    participant Store

    Agent->>McpServer: tools/call get_run|get_run_progress|server_status
    McpServer->>RunService: read state
    RunService->>Store: SELECT latest snapshot
    Store-->>RunService: persisted state
    RunService-->>McpServer: response
    McpServer-->>Agent: result
```

---

## State Model

The run lifecycle should be explicit and durable:

```text
pending -> queued -> running -> completed
                  \-> failed
```

Persisted fields for each run should include:

- `id`
- `scenario_id` or equivalent name
- `tool`
- `status`
- `created_at`, `started_at`, `completed_at`
- `current_stage`
- `progress_pct`
- `progress_json` (latest snapshot)
- `summary`
- `error`
- `raw_output_path`
- `metadata`

This is important because the server is stdio-based and may be restarted. A run should not depend on in-memory objects alone.

---

## Scheduler and Execution Model

A Phase 1 scheduler should be intentionally simple:

- One worker thread executes at a time.
- New runs are appended to a queue and marked `queued` immediately.
- The worker dequeues the next run and moves it to `running`.
- On completion, the worker updates the run record to `completed` or `failed`.

This meets the acceptance criteria without introducing premature parallelism or cross-run interference. A future phase can add isolation groups or concurrent workers, but Phase 1 should not try to optimize beyond serial execution.

---

## Adapter Contract

The adapter interface should be narrow and deterministic:

```kotlin
interface PerfToolAdapter {
    val name: String
    val supportedTestTypes: List<TestType>
    val version: String
    val toolVersions: List<String>

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan, progressSink: ProgressSink): RunResult
}
```

Where `ProgressSink` is a minimal callback for emitting progress snapshots while the tool is running. The adapter is responsible for:

- constructing the subprocess command
- capturing stdout/stderr
- writing raw output to disk atomically
- parsing the tool output into a normalized `RunResult`
- emitting best-effort progress updates while the process is running

The rest of the system should not care whether the underlying tool is Tulip, k6, or something else.

---

## Output and File Handling

The raw output path should be handled as a first-class concern:

- Write to a temporary file first.
- Rename to the final path only after the process completes successfully or when a failure is final.
- Store the final path in the run record.
- Preserve both stdout and stderr in the file for debugging.

This avoids partially written outputs and makes the integration test predictable.

---

## Authentication and Security

The Phase 1 auth model should stay intentionally simple:

- A bearer token is passed in the `initialize` request.
- The server validates it once per session and rejects invalid or missing tokens with `-32005`.
- Every tool call is checked against the authenticated session state.
- No role model, token rotation workflow, or admin UI is required in Phase 1.

The important design choice is not the algorithm itself but that the server never executes tools for an unauthenticated session.

---

## Error Handling

Phase 1 should handle errors explicitly and consistently:

- Invalid input: return a validation error and do not create a run.
- Tool binary missing: mark the run as `failed` with a clear error.
- Timeout: kill the subprocess and record the failure with partial progress if available.
- Non-zero tool exit: record stderr and mark the run as `failed`.
- Internal failure: return MCP error `-32603` and log server-side details.

The server should avoid silently swallowing failures and should preserve enough context to debug the run later.

---

## Recommended Module Layout

```text
Boehm/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/kotlin/io/boehm/
│   │   ├── Main.kt                  # stdio JSON-RPC loop
│   │   ├── auth/
│   │   │   └── AuthGuard.kt         # bearer token validation
│   │   ├── core/
│   │   │   ├── McpServer.kt         # request dispatch
│   │   │   ├── RunService.kt        # run lifecycle and state updates
│   │   │   ├── Scheduler.kt         # serial queue + worker
│   │   │   └── RunStore.kt          # SQLite access and migrations
│   │   ├── model/
│   │   │   ├── TestPlan.kt
│   │   │   ├── RunResult.kt
│   │   │   └── ProgressSnapshot.kt
│   │   └── adapters/
│   │       ├── PerfToolAdapter.kt
│   │       └── tulip/
│   │           ├── TulipAdapter.kt
│   │           ├── TulipParser.kt
│   │           └── ProcessRunner.kt
│   └── test/kotlin/io/boehm/
│       ├── adapter/
│       │   └── TulipAdapterTest.kt
│       └── integration/
│           └── TulipIntegrationTest.kt
```

---

## What This Design Improves Over the First Draft

- It avoids making the MCP server itself the place where long-running execution and polling logic become too coupled.
- It makes progress observable even if the process or server restarts.
- It keeps the adapter interface stable while still allowing Tulip-specific parsing to remain isolated.
- It aligns the architecture with the Phase 1 acceptance criteria rather than overreaching into later phases.

## Risks to Keep in Mind

- Tulip may not provide a perfectly stable progress stream. For Phase 1, progress should be treated as best-effort and degraded gracefully.
- The server must never block the stdio loop for a long-running test. That is a hard requirement for a usable MCP implementation.
- The design should stay small. The temptation to add multi-user auth, generic plugin discovery, or parallel runners should be resisted until Phase 2.
