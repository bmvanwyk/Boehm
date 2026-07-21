# Architecture — Phase 1

## System Context

```mermaid
graph TB
    Agent["AI Agent<br/>(Claude Code, Cline, etc.)"]
    Boehm["Boehm MCP Server<br/>Kotlin/JVM"]
    Tulip["Tulip CLI<br/>(shell exec)"]
    Target["Target System<br/>(e.g. httpbin.org)"]
    SQLite[(SQLite<br/>~/.boehm/boehm.db)]
    OutputDir[(Raw Outputs<br/>~/.boehm/outputs/)]

    Agent -- "JSON-RPC 2.0 over stdio<br/>bearer token auth" --> Boehm
    Boehm -- "shell exec" --> Tulip
    Tulip -- "HTTP load" --> Target
    Boehm -- "runs, scenarios, adapters" --> SQLite
    Boehm -- "raw stdout/stderr" --> OutputDir
```

---

## Component Architecture

```mermaid
graph TB
    subgraph "MCP Layer"
        McpHandler["McpHandler.kt<br/>JSON-RPC dispatcher"]
        Auth["AuthHandler.kt<br/>Bearer token validation"]
    end

    subgraph "Core Layer"
        Orchestrator["Orchestrator.kt<br/>Route test plans, persist runs"]
        Scheduler["Scheduler.kt<br/>Serial run queue"]
        Store["Store.kt<br/>SQLite operations"]
    end

    subgraph "Adapter Layer"
        AdapterInterface["PerfToolAdapter<br/>(interface)"]
        TulipAdapter["TulipAdapter.kt<br/>CLI exec + capture"]
        TulipParser["TulipParser.kt<br/>JSON → RunResult"]
    end

    subgraph "Model"
        Models["TestPlan, RunResult,<br/>ProgressEvent"]
    end

    Agent["AI Agent"]

    Agent --> McpHandler
    McpHandler --> Auth
    McpHandler --> Orchestrator
    Orchestrator --> Scheduler
    Orchestrator --> Store
    Scheduler --> TulipAdapter
    TulipAdapter --> TulipParser
    TulipAdapter --> Models
    TulipParser --> Models
    Store --> SQLite[(SQLite)]
    TulipAdapter -- "stdout" --> Tulip
    Tulip -- "load" --> Target
```

---

## Data Flow — run_test

```mermaid
sequenceDiagram
    participant Agent
    participant McpHandler
    participant Auth
    participant Orchestrator
    participant Scheduler
    participant Store
    participant TulipAdapter

    Agent->>McpHandler: tools/call { name: "run_test", arguments }
    McpHandler->>Auth: validate token
    Auth-->>McpHandler: ok
    McpHandler->>Orchestrator: execute(testPlan)
    Orchestrator->>Store: INSERT pending run
    Store-->>Orchestrator: run_id
    Orchestrator->>Scheduler: enqueue(run_id)
    Scheduler-->>Orchestrator: queued
    Orchestrator-->>McpHandler: { runId, status: "queued" }
    McpHandler-->>Agent: result

    Note over Agent: poll for completion

    Agent->>McpHandler: tools/call { name: "get_run", runId }
    McpHandler->>Orchestrator: getRun(runId)
    Orchestrator->>Store: SELECT status
    Store-->>Orchestrator: { status: "running" }
    Orchestrator-->>McpHandler: { status: "running", progress }
    McpHandler-->>Agent: result

    Note over Scheduler: run dequeued

    Scheduler->>TulipAdapter: run(testPlan)
    TulipAdapter->>Tulip: tulip <args>
    Tulip-->>TulipAdapter: JSON stdout (live)
    loop During execution
        Agent->>McpHandler: tools/call { name: "get_run_progress", runId }
        McpHandler->>Orchestrator: getProgress(runId)
        Orchestrator-->>McpHandler: { rolling_summary, progress_pct }
        McpHandler-->>Agent: result
    end
    Tulip->>TulipAdapter: exit 0
    TulipAdapter->>TulipParser: parse(raw JSON)
    TulipParser-->>TulipAdapter: RunResult
    TulipAdapter-->>Scheduler: completed
    Scheduler->>Store: UPDATE status = completed, summary
    Store-->>Scheduler: ok
    Scheduler->>Scheduler: dequeue next run

    Agent->>McpHandler: tools/call { name: "get_run", runId }
    McpHandler->>Orchestrator: getRun(runId)
    Orchestrator-->>McpHandler: { status: "completed", summary }
    McpHandler-->>Agent: result
```

---

## Auth Flow

```mermaid
sequenceDiagram
    participant Agent
    participant McpHandler
    participant Auth

    Agent->>McpHandler: initialize { auth_token }
    McpHandler->>Auth: validate(token)
    Auth->>Auth: bcrypt check
    alt Invalid or missing token
        Auth-->>McpHandler: reject
        McpHandler-->>Agent: error -32005
        Note over Agent: server ignores all subsequent tools/call
    else Valid token
        Auth-->>McpHandler: ok
        McpHandler-->>Agent: initialized
    end

    Agent->>McpHandler: tools/call { name: "run_test", ... }
    McpHandler->>Auth: authenticate(caller)
    Auth-->>McpHandler: ok (cached from initialize)
    McpHandler->>McpHandler: dispatch
    McpHandler-->>Agent: result
```

---

## Scheduler — Run Queue

```mermaid
stateDiagram-v2
    [*] --> Idle: server start
    Idle --> Running: run_test received
    Running --> Running: progress events (adapter output)
    Running --> Completed: adapter returns RunResult
    Running --> Failed: adapter exit non-zero / timeout
    Completed --> Idle: no queued runs
    Completed --> Running: queued run dequeued
    Failed --> Idle: no queued runs
    Failed --> Running: queued run dequeued
    Running --> Running: adapter emits progress (rolling summary)

    state Idle {
        [*] --> WaitForRequest
    }
    state Running {
        AdapterExec --> AdapterExec: progress polling via get_run_progress
    }
```

---

## Adapter Interface

```mermaid
classDiagram
    class PerfToolAdapter {
        <<interface>>
        +String name
        +List~TestType~ supportedTestTypes
        +String version
        +List~String~ toolVersions
        +validate(TestPlan) List~ValidationError~
        +run(TestPlan) RunResult
    }

    class TulipAdapter {
        +run(testPlan)
        -captureOutput()
        -buildArgs()
    }

    class TulipParser {
        +parse(rawJson) RunResult
        -extractPercentiles()
        -extractThroughput()
    }

    class TestPlan {
        +String type
        +String targetUrl
        +int ratePerSec
        +int durationSec
        +int warmupSec
    }

    class RunResult {
        +String tool
        +String testName
        +String runId
        +String status
        +Summary summary
        +String rawOutputPath
        +Map metadata
    }

    class Summary {
        +int durationSec
        +int totalRequests
        +double throughputReqPerSec
        +double errorRatePct
        +Latency latency
    }

    class Latency {
        +double minMs
        +double p50Ms
        +double p90Ms
        +double p95Ms
        +double p99Ms
        +double maxMs
    }

    PerfToolAdapter <|.. TulipAdapter
    TulipAdapter --> TulipParser
    TulipAdapter --> TestPlan
    TulipAdapter --> RunResult
    TulipParser --> RunResult
    RunResult --> Summary
    Summary --> Latency
```

---

## Module Layout

```
Boehm/
├── build.gradle.kts              # Gradle config + dependencies
├── settings.gradle.kts
├── src/
│   └── main/kotlin/io/boehm/
│       ├── Main.kt               # Entry point: stdio JSON-RPC loop
│       ├── auth/
│       │   └── AuthHandler.kt    # Bearer token validation (bcrypt)
│       ├── core/
│       │   ├── McpHandler.kt     # JSON-RPC dispatcher → tools
│       │   ├── Orchestrator.kt   # Route test plans, persist runs
│       │   ├── Scheduler.kt      # Run queue, serial execution
│       │   └── Store.kt          # SQLite operations
│       ├── model/
│       │   ├── TestPlan.kt       # Test plan data class
│       │   ├── RunResult.kt      # Normalized result + summary + latency
│       │   └── ProgressEvent.kt  # Live progress event
│       └── adapters/
│           ├── PerfToolAdapter.kt  # Interface
│           └── tulip/
│               ├── TulipAdapter.kt  # CLI exec, output capture
│               └── TulipParser.kt   # Parse native JSON → RunResult
```

---

## Architectural Decision Records

### ADR-001: SQLite over JSON files

**Status:** Accepted
**Context:** Need persistence for runs, scenarios, and orchestration state. JSON files are simple but lack schema enforcement, concurrent writer support, and querying.
**Decision:** Use SQLite single-file database. Atomic writes, concurrent readers, schema enforcement, rich queries via SQL.
**Consequences:** Adequate for thousands of runs. If scale exceeds 100 runs/hour sustained, migrate to PostgreSQL via abstracted `Store` interface.

### ADR-002: All adapters are CLI shell exec

**Status:** Accepted
**Context:** Tulip could be called in-process (same JVM) for performance. This creates tight coupling to Tulip's internal APIs and JVM dependency conflicts.
**Decision:** All tool adapters shell out to the tool's CLI. The CLI output format is the stable contract. No in-process tool coupling.
**Consequences:** Consistent adapter pattern across all tools. Slight overhead from process spawn. Tulip may need a `--json` flag for machine-readable output.

### ADR-003: Serial execution by default

**Status:** Accepted
**Context:** Overlapping performance tests add noise to measurements (resource contention, JVM warmup, network interference).
**Decision:** Single global run queue. One test executes at a time. Additional runs are enqueued with status `queued`.
**Consequences:** Clean measurements. CI bottleneck for orgs with many concurrent PRs — mitigated by `isolation_group` opt-in parallelism in Phase 2.

### ADR-004: Bearer token auth in initialize handshake

**Status:** Accepted
**Context:** MCP protocol has no built-in auth. Need to prevent unauthorized access to the server.
**Decision:** Pass bearer token in `initialize` params. Validate on every `tools/call`. Server returns `-32005` on invalid/missing token.
**Consequences:** Simple, stateless auth. Token is sent once per connection. No session management needed in Phase 1.

### ADR-005: Normalized RunResult over raw tool output for analysis

**Status:** Accepted
**Context:** Each tool produces different output (JSON, CSV, HTML). Cross-tool comparison and historical tracking need a consistent schema.
**Decision:** Every adapter parses tool output into the normalized `RunResult` schema. Raw output preserved at `rawOutputPath` for debugging.
**Consequences:** Cross-tool comparison and trend analysis work on normalized fields. Tool-specific data goes in `metadata`. Raw output is available but not required for analytics.
