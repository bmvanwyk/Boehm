# Boehm: Performance Testing AI Engineer

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm), known for the COCOMO model and spiral model of software development.

## Product Overview

Boehm is an **AI engineer specialized in performance testing**. It gives AI coding agents (Claude Code, Cline, etc.) the ability to design performance experiments, execute them across multiple tools, analyze results statistically, track baselines over time, and investigate regressions back to the code change that caused them.

Three things that make it an "engineer" rather than a test runner:
- **Institutional memory** — every run is preserved in SQLite; any run can be tagged as a baseline for comparison
- **Clean measurements** — run scheduler serializes execution so overlapping tests never add noise
- **Investigation skills** — when a regression is detected, Boehm can bisect commits, correlate with code changes, and explain what happened
- **Tool fluency** — speaks Tulip, k6, JMeter, Gatling, and any other performance tool through a unified adapter interface

### User Personas

| Persona | Context | Problem Boehm solves |
|---|---|---|
| **Performance engineer** | Reviewing a PR that touches a critical endpoint | "Did this PR introduce a latency regression? Which commit caused it? Should I block merge?" |
| **SRE / platform engineer** | CI pipeline for a service with SLOs | "I need deterministic pass/fail on perf tests in CI. False positives waste my team's time." |
| **Developer** | Sanity-checking a local change before pushing | "Does this refactor affect throughput? Let me run the same test before and after with one command." |
| **QA / test lead** | Evaluating perf tooling across teams | "Our team uses k6, another uses JMeter. I want one way to define and track perf tests." |

### Example Workflows

**PR regression check:**
1. Agent loads `boehm-pr-check` skill
2. Agent calls `boehm validate_pr(repo, pr_number, test_suite="http-api")`
3. Boehm checks out base branch, runs test suite → baseline
4. Checks out PR head, runs same suite → candidate
5. Compares, identifies regressions, bisects commits if needed
6. Agent posts PR comment with a Mermaid chart showing before/after latency

**Baseline drift alert:**
1. Agent schedules weekly run of `http-api` test suite
2. New result is compared against the tagged baseline
3. p95 latency has drifted +8% over 4 weeks (still under +10% threshold, but trending)
4. Agent flags the trend in a report: "Gradual degradation detected — recommend investigation"

**Tool consistency validation:**
1. Agent defines the same HTTP endpoint test scenario
2. Runs it via Tulip, then k6, then Gatling
3. Compares results: k6 and Gatling agree, Tulip shows lower p99
4. Agent investigates: Tulip runs in-process vs k6/Gatling as shell exec — overhead difference explains it
5. Documents the systematic offset in the baseline metadata

## Architecture

```
AI Agent (Claude Code, Cline, etc.)
  │  Loads skills (reusable agent workflows)
  │  Uses MCP protocol (JSON-RPC 2.0 over stdio)
  ▼
perf-mcp-server (Kotlin/JVM)
  ├── Core Layer
  │   ├── MCP Handler        — exposes MCP tools/resources
  │   ├── Orchestrator       — routes test plans to adapters, manages runs
  │   ├── SQLite Store       — runs, baselines, scenarios, orchestration state
  │   └── Run Scheduler      — serializes execution by default; opt-in parallelism
  └── Adapter Layer
      ├── tool adapters      — translates test plans to tool-specific invocation
      └── adapter registry   — discoverable by agents via list_adapters
```

### Data Flow

1. Agent calls `run_test(tool, test_name, test_plan)` via MCP
2. Core layer validates the test plan, persists a pending run record in SQLite
3. Run is enqueued — status `pending` → `queued`
4. Scheduler picks up the run when executor is free — status `queued` → `running`
5. Adapter translates test plan to tool-specific invocation (in-process JVM call, shell exec, etc.)
6. Adapter parses tool output into normalized `RunResult`
7. Core layer persists result in SQLite — status `running` → `completed` (or `failed`)
8. Agent polls `get_run(run_id)` or uses notification to get the result

## Concrete API Examples

### run_test

**Request (agent → MCP):**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "run_test",
    "arguments": {
      "tool": "k6",
      "test_name": "http-api-load",
      "test_plan": {
        "type": "http",
        "target_url": "https://httpbin.org/get",
        "method": "GET",
        "rate_per_sec": 100,
        "duration_sec": 30,
        "warmup_sec": 5,
        "timeout_sec": 60,
        "headers": {}
      }
    }
  }
}
```

**Response (MCP → agent):**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tool": "k6",
    "testName": "http-api-load",
    "timestamp": "2026-07-20T10:00:00Z",
    "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "status": "queued",
    "summary": null
  }
}
```

**Polling get_run after completion:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "get_run",
    "arguments": { "run_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" }
  }
}
```

**Response (completed):**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tool": "k6",
    "testName": "http-api-load",
    "timestamp": "2026-07-20T10:00:00Z",
    "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "status": "completed",
    "summary": {
      "durationSec": 30,
      "totalRequests": 2850,
      "throughputReqPerSec": 95.0,
      "errorRatePct": 0.0,
      "latency": {
        "minMs": 2.1,
        "p50Ms": 8.5,
        "p90Ms": 22.3,
        "p95Ms": 35.0,
        "p99Ms": 68.1,
        "maxMs": 210.0
      }
    },
    "rawOutputPath": "/home/user/.boehm/outputs/k6/http-api-load/2026-07-20T10-00-00.log",
    "metadata": {
      "k6_version": "0.54.0",
      "iterations": 2850,
      "target_host": "httpbin.org"
    }
  }
}
```

**Error response (tool not found):**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32000,
    "message": "Adapter not found",
    "data": { "tool": "nonexistent", "available_adapters": ["k6", "tulip"] }
  }
}
```

### compare_with_baseline

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "compare_with_baseline",
    "arguments": {
      "tool": "k6",
      "test_name": "http-api-load",
      "run_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "thresholds": {
        "p95_pct": 10.0,
        "p99_pct": 15.0,
        "error_rate_pp": 2.0,
        "throughput_drop_pct": 15.0
      }
    }
  }
}
```

**Response (regression detected):**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "verdict": "REGRESSION",
    "baseline_run_id": "z9y8x7w6-v5u4-3210-fedc-ba9876543210",
    "candidate_run_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "baseline_timestamp": "2026-07-15T09:00:00Z",
    "candidate_timestamp": "2026-07-20T10:00:00Z",
    "deltas": {
      "p95_ms": { "baseline": 28.0, "candidate": 35.0, "delta_pct": 25.0, "threshold_pct": 10.0, "breached": true },
      "p99_ms": { "baseline": 55.0, "candidate": 68.1, "delta_pct": 23.8, "threshold_pct": 15.0, "breached": true },
      "error_rate_pp": { "baseline": 0.0, "candidate": 0.0, "delta_pp": 0.0, "threshold_pp": 2.0, "breached": false },
      "throughput_req_per_sec": { "baseline": 100.0, "candidate": 95.0, "delta_pct": -5.0, "threshold_drop_pct": 15.0, "breached": false }
    },
    "summary": "p95 exceeded threshold (+25% vs +10% limit), p99 exceeded threshold (+23.8% vs +15% limit)"
  }
}
```

### validate_pr (Phase 6)

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "validate_pr",
    "arguments": {
      "repo": "/home/user/project",
      "pr_number": 42,
      "test_suite": "http-api",
      "bisect": true,
      "timeout_min": 30
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "verdict": "REGRESSION",
    "base_run_id": "...",
    "head_run_id": "...",
    "bisect": {
      "total_commits": 5,
      "commits_tested": 3,
      "first_bad_commit": "abc123def",
      "first_bad_commit_message": "Increase connection pool timeout",
      "first_bad_commit_author": "dev@example.com",
      "runs_performed": 3
    },
    "deltas": { "...": "..." },
    "summary": "p95 regression traced to commit abc123def (author: dev@example.com)"
  }
}
```

## MVP Acceptance Criteria

### Phase 1 — MCP Server Scaffold + First Adapter

1. **Project compiles and starts:** `./gradlew build` succeeds. Running `perf-mcp-server` starts a process that accepts stdio JSON-RPC 2.0.
2. **list_adapters returns tools:** Calling `list_adapters` returns a non-empty list with at least one adapter name and its supported test types.
3. **run_test executes and returns a result:** Given a valid test plan against httpbin.org, `run_test` returns a `run_id` with status `queued`. Polling `get_run` eventually returns status `completed` with `summary.latency.p50Ms`, `p90Ms`, `p95Ms`, `p99Ms` populated as positive numbers.
4. **Invalid test plan is rejected:** Calling `run_test` with missing required fields returns a validation error (MCP error code -32002) without executing.
5. **Integration test passes:** A CI job runs `run_test` against httpbin.org and asserts the result status is `completed` with throughput > 0.
6. **Scheduler serializes runs:** Issuing two `run_test` calls in rapid succession results in the second being `queued` until the first completes. Both eventually complete.

### Phase 2 — Baseline & Comparison

1. **mark_baseline persists and retrieves:** Calling `mark_baseline` then `get_baseline` returns the same run.
2. **compare_with_baseline produces correct verdict:** A known-good baseline compared against itself returns `PASS` with zero deltas. A comparison with a known-regressed candidate returns `REGRESSION`.
3. **Baseline staleness warning:** Comparing against a baseline older than the configured stale threshold (default 30 days) returns a warning message alongside the comparison.
4. **Threshold override works:** Passing custom thresholds to `compare_with_baseline` changes the verdict boundary as expected.
5. **False positive rate ≤10%:** Over 100 CI runs against a stable target, `compare_with_baseline` returns `REGRESSION` no more than 10 times (configurable thresholds, warmup applied).
6. **list_runs returns paginated results:** `list_runs` with `limit=5` returns at most 5 results. Results are ordered by `created_at` descending.

### Phase 3 — Evals

1. **Adapter fixture tests pass:** Each adapter has a set of stored input/output fixtures. Running the adapter against a fixture's input produces a `RunResult` matching the fixture's expected output within tolerance.
2. **Determinism:** Running the same test plan twice against the same target produces throughput and latency values within 5% of each other.
3. **Crash recovery:** If the MCP server is killed while a run is `running`, on restart the run is marked `failed` and the queue processes the next item.
4. **Schema migration:** Adding a column to the SQLite schema and running the migration script preserves all existing data.

## Success Metrics

| Metric | Target | Measurement | Phase |
|---|---|---|---|
| PR investigation time | < 5 min for 90th percentile | Wall-clock from agent invocation to report delivery | 6 |
| False positive rate | ≤ 10% over 100 CI runs | `REGRESSION` verdicts on a stable target ÷ total runs | 2 |
| CI decision determinism | 100% | Same commit + same baseline + same target = same verdict | 3 |
| Regressions caught pre-merge | Tracked (baseline) | Number of PRs where Boehm flagged a regression that would have merged | 6 |
| MCP server overhead | < 50 ms p99 (excl. test exec) | Internal timing of orchestration + persistence per call | 2 |
| Adapter coverage | ≥ 2 tools | Number of supported adapters with CI-tested fixtures | 4 |

## Acceptance & Rollout Policy

### Process for blocking merges

| Stage | Gate | Who decides | Mechanism |
|---|---|---|---|
| **Warning only** (Phase 2 default) | Boehm reports `REGRESSION` but exits 0 | Perf engineer reads the report | `compare_with_baseline` returns verdict + summary, but CLI exits 0 |
| **Advisory** (Phase 4+) | CI pipeline shows perf check as "required" with yellow status | Team lead configures per-repo | GitHub Action uses `continue-on-error: false` |
| **Hard block** (Phase 6+) | CI fails if regression exceeds thresholds | SRE/platform team configures thresholds | Non-zero exit code, PR cannot merge without bypass |

### Baseline override policy

- `mark_baseline` requires an authorization check (either the agent calling from CI has a configured token, or a human confirms in the agent's output)
- All baseline changes are logged in `baseline_history` table with `tagged_at` and `superseded_at` timestamps
- If a baseline is overridden, the previous baseline remains in `baseline_history` for audit

## CI Integration

### Exit code semantics

| Verdict | CLI exit code | CI behavior |
|---|---|---|
| `PASS` | 0 | Green check, proceed |
| `REGRESSION` (advisory mode) | 0 | Yellow/warning, non-blocking |
| `REGRESSION` (hard block mode) | 1 | Red X, blocks merge |
| `ERROR` (tool failure, infra issue) | 2 | Red X, infrastructure failure |
| `TIMEOUT` | 3 | Red X, test exceeded time budget |

### GitHub Action (Phase 2+)

```yaml
# .github/actions/boehm-perf-check/action.yml
name: 'Boehm Performance Check'
description: 'Run perf tests and compare against baseline'
inputs:
  boehm-command:
    description: 'Boehm command to execute'
    required: true
  test-suite:
    description: 'Test suite name'
    required: true
  tool:
    description: 'Performance testing tool'
    default: 'k6'
  thresholds:
    description: 'JSON thresholds override'
    required: false
outputs:
  verdict:
    description: 'PASS, REGRESSION, or ERROR'
  run-id:
    description: 'Run ID for reference'
runs:
  using: 'composite'
  steps:
    - run: |
        perf-mcp-server --daemon
        sleep 2
        boehm run-test --tool ${{ inputs.tool }} \
                       --test-name ${{ inputs.test-suite }} \
                       --test-plan test-suites/${{ inputs.test-suite }}.json
      shell: bash
```

### CI workflow

```yaml
jobs:
  perf-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: grafana/k6-action@v0.3
      - run: boehm run-test --tool k6 --test-name http-api --test-plan tests/perf/http-api.json
        id: perf-run
      - run: boehm compare --tool k6 --test-name http-api --run-id ${{ steps.perf-run.outputs.run-id }}
        id: perf-compare
      - if: steps.perf-compare.outputs.verdict == 'REGRESSION'
        run: |
          echo "## Performance regression detected" >> $GITHUB_STEP_SUMMARY
          echo "${{ steps.perf-compare.outputs.report }}" >> $GITHUB_STEP_SUMMARY
          exit 1
```

### Continuous run recipe (end-to-end)

```
1. Developer pushes PR
2. GitHub Actions triggers boehm-perf-check workflow
3. Action starts perf-mcp-server as daemon
4. boehm run-test executes the test suite, produces run_id
5. boehm compare fetches baseline from SQLite, compares, produces verdict
6. If REGRESSION: exit 1, post PR comment with Mermaid chart
7. If PASS: exit 0, optionally update baseline if configured
8. Raw output is saved to .boehm/outputs/ with run_id for debugging
9. Artifacts: summary.json + raw output.log (retained 90 days)
```

## MCP Tools (API Surface)

### Core tools

| Tool | Input | Output | Phase |
|---|---|---|---|
| `list_adapters` | — | Tool names + supported test types | 1 |
| `run_test` | `tool`, `test_name`, `test_plan` | `run_id`, initial status | 1 |
| `get_run` | `run_id` | Full `RunResult` | 1 |
| `mark_baseline` | `tool`, `test_name`, `run_id`, `note?`, `env?` | Confirmation | 2 |
| `get_baseline` | `tool`, `test_name`, `env?` | Baseline `RunResult` or null | 2 |
| `list_baselines` | `tool?`, `test_name?`, `env?` | List of baselines with env tags | 2 |
| `compare_with_baseline` | `tool`, `test_name`, `run_id`, `thresholds?`, `env?` | Diff report + verdict | 2 |
| `list_runs` | `tool?`, `test_name?`, `limit?`, `status?` | Recent runs metadata | 2 |
| `validate_pr` | `repo`, `pr_number`, `test_suite`, `thresholds?`, `bisect?` | PR comparison report + commit attribution | 6 |

### MCP error codes

| Code | Meaning | When |
|---|---|---|
| -32000 | Adapter not found | Requested tool has no registered adapter |
| -32001 | Test plan validation failed | Missing/invalid parameters |
| -32002 | Baseline not found | No baseline for the given (tool, test_name, env) |
| -32003 | Execution timeout | Test exceeded its time budget |
| -32004 | Git operation failed | Checkout/stash/restore error in validate_pr |
| -32700 | Parse error | Invalid JSON-RPC |
| -32600 | Invalid request | Malformed method/params |
| -32603 | Internal error | Unexpected server failure |

### How agents invoke Boehm

- **From an AI coding assistant** — agent loads a skill file and makes MCP tool calls over stdio
- **From CI** — via GitHub Action or shell wrapper that runs pre-configured comparisons and exits non-zero on regression
- **From CLI** — direct `boehm run_test ...` or `boehm compare ...` for human-driven sessions

## Data Model

### RunResult schema (required vs optional)

```json
{
  "tool": "k6",
  "testName": "http-api-load",
  "timestamp": "2026-07-20T10:00:00Z",
  "runId": "uuid",
  "status": "completed",
  "summary": {
    "durationSec": 60,
    "totalRequests": 50000,
    "throughputReqPerSec": 833.3,
    "errorRatePct": 0.02,
    "latency": {
      "minMs": 1.2,
      "p50Ms": 8.5,
      "p90Ms": 22.1,
      "p95Ms": 35.0,
      "p99Ms": 68.3,
      "maxMs": 250.0
    }
  },
  "rawOutputPath": "/path/to/output.log",
  "metadata": {}
}
```

**Field requirements:**

| Field | Required | Units | Notes |
|---|---|---|---|
| `tool` | yes | — | Adapter name |
| `testName` | yes | — | Logical name for the test scenario |
| `timestamp` | yes | ISO-8601 UTC | When the run completed |
| `runId` | yes | UUID v4 | Generated by the server |
| `status` | yes | — | `completed`, `failed`, `cancelled`, `timed_out` |
| `summary.durationSec` | yes | seconds | Wall-clock duration of active test (excludes warmup, cooldown) |
| `summary.totalRequests` | yes | count | Total requests sent |
| `summary.throughputReqPerSec` | yes | req/s | `totalRequests / durationSec` |
| `summary.errorRatePct` | yes | percentage (0-100) | `(failedRequests / totalRequests) * 100` |
| `summary.latency.minMs` | yes | milliseconds | Fastest observed request |
| `summary.latency.p50Ms` | yes | milliseconds | Median latency |
| `summary.latency.p90Ms` | yes | milliseconds | 90th percentile |
| `summary.latency.p95Ms` | no | milliseconds | Optional if tool provides it |
| `summary.latency.p99Ms` | no | milliseconds | Optional if tool provides it |
| `summary.latency.maxMs` | yes | milliseconds | Slowest observed request |
| `rawOutputPath` | no | — | Absolute path to raw tool output; may contain sensitive data |
| `metadata` | no | — | Free-form map; use for tool-specific fields, environment tags, correlation IDs |

### Baseline lifecycle

| Action | Behavior |
|---|---|
| **Create** | `mark_baseline(tool, test_name, run_id, env?)` — tags a run as the baseline. One active baseline per `(tool, test_name, env)` tuple |
| **Replace** | Calling `mark_baseline` again on the same tuple replaces the pointer (old one moves to `baseline_history`) |
| **Compare** | `compare_with_baseline` reads the baseline for the matching env, or the untagged env if none specified |
| **List** | `list_baselines` shows all current baselines with env tags |
| **History** | `baseline_history` table records every baseline change with `tagged_at` and `superseded_at` |
| **Stale detection** | If baseline timestamp > configured days (default 30), comparison returns a warning but still executes |
| **Environment tags** | `env` parameter in Phase 2 supports `staging`, `production`, `ci`, etc. Baselines are scoped by env |

### Regression decision logic

```
compare_with_baseline runs → shortfall = candidate - baseline

thresholds (per-test config):
  p95_pct            default: 10.0   (percent)
  p99_pct            default: 15.0   (percent)
  error_rate_pp      default: 2.0    (percentage points)
  throughput_drop_pct default: 15.0  (percent drop)

if shortfall.p95_pct > threshold.p95_pct:
    verdict = "REGRESSION"
elif shortfall.p99_pct > threshold.p99_pct:
    verdict = "REGRESSION"
elif shortfall.error_rate_pp > threshold.error_rate_pp:
    verdict = "REGRESSION"
elif shortfall.throughput_pct < -threshold.throughput_drop_pct:
    verdict = "REGRESSION"
else:
    verdict = "PASS"
```

Thresholds are configurable per test scenario in the SQLite `test_scenarios.metadata` field. The defaults above serve as fallback. Threshold values are reference defaults — teams should tune per-service based on historical variance.

### Flakiness mitigation

- **Warmup period** — configurable per adapter, excluded from metrics (default 5s)
- **N-runs median** — option to run N times and compare the median run (opt-in, adds N× duration)
- **Noise annotation** — `RunResult.metadata` can include `noise_level` from the adapter (e.g., "gc_pause_during_test")
- **Statistical confidence** — Phase 7 adds p-value/confidence intervals to comparison results
- **False positive budget** — tests that flake are flagged; repeated flaky tests (>10% false positive rate over 30 days) are quarantined to a separate report

## Adapter Interface

```kotlin
interface PerfToolAdapter {
    val name: String
    val supportedTestTypes: List<TestType>
    val version: String                  // adapter version, not tool version
    val toolVersions: List<String>       // tested tool versions (e.g., ["k6 0.54.x"])

    fun validate(testPlan: TestPlan): List<ValidationError>
    fun run(testPlan: TestPlan): RunResult
}
```

Adapters are discoverable — an agent can call `list_adapters` to see available tools with their supported test types and tested versions.

### Reproducibility contract

Each adapter must document:
- Which tool versions it has been tested against
- Known sources of variance (JIT warmup, GC pauses, network jitter)
- Its container image or pinned binary version for CI reproducibility

## SQLite Database

A single SQLite database at `~/.boehm/boehm.db` stores all persistent state.

### Schema

```sql
-- Tools and adapters
CREATE TABLE adapters (
    name TEXT PRIMARY KEY,
    supported_types TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    tool_versions TEXT NOT NULL
);

-- Test scenarios
CREATE TABLE test_scenarios (
    id TEXT PRIMARY KEY,
    tool TEXT NOT NULL REFERENCES adapters(name),
    name TEXT NOT NULL,
    test_plan JSON NOT NULL,
    env TEXT DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(tool, name, env)
);

-- Run orchestration
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
    raw_output_size_bytes INTEGER DEFAULT 0,
    metadata JSON DEFAULT '{}'
);

-- Active baseline pointers (one per scenario+env)
CREATE TABLE baselines (
    scenario_id TEXT NOT NULL REFERENCES test_scenarios(id),
    run_id TEXT NOT NULL REFERENCES runs(id),
    tagged_at TEXT NOT NULL DEFAULT (datetime('now')),
    tagged_by TEXT DEFAULT 'agent',
    note TEXT,
    PRIMARY KEY (scenario_id)
);

-- Historical baselines (audit trail)
CREATE TABLE baseline_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scenario_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    tagged_at TEXT NOT NULL,
    superseded_at TEXT,
    superseded_by TEXT,
    note TEXT
);

-- Schema version for migration tracking
CREATE TABLE schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

### Why SQLite?

- **Single file, zero infrastructure** — works in CI, local dev, any environment
- **Atomic writes, concurrent readers** — multiple agents can query simultaneously
- **Schema enforcement** — foreign keys, unique constraints, type checking that JSON files lack
- **Rich queries** — joins across scenarios, runs, baselines; filtering by status, date, tool, env
- **Adequate scale** — handles thousands to tens of thousands of runs (SQLite proven at millions of rows)
- **Portable** — single file is easy to back up, inspect, and copy between environments

### Migration to PostgreSQL (when needed)

SQLite is the default. If multi-team, high-volume, or concurrent-writer usage exceeds SQLite's capabilities:

1. Abstract the store behind an interface from day one (`StoreRepository`)
2. Implement a `PostgresStoreRepository` using the same interface
3. Migration path: export SQLite → `pg_restore` → point config at `postgres://...`
4. Trigger condition: sustained > 100 runs/hour or > 5 concurrent writers

### Run Scheduler & Isolation

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Agent calls │     │  Run Queue   │     │  Scheduler   │
│  run_test()  │ ──▶ │  (SQLite)    │ ──▶ │  (serial)    │ ──▶ Adapter
└──────────────┘     └──────────────┘     └──────────────┘
```

**Default: serial execution.**
- Only one run executes at a time globally (single consumer from the queue)
- Additional runs are enqueued with status `queued` and start when the active run completes
- Agents poll `get_run` to see status transitions: `pending` → `queued` → `running` → `completed` / `failed`
- If the MCP server restarts while a run is `running`, it's marked `failed` on startup (crash recovery)
- `validate_pr` runs base then head sequentially in the same queue

**Opt-in parallelism (Phase 2+):**
- Test scenarios can declare an `isolation_group` in their test plan
- Runs from different isolation groups execute in parallel
- Runs within the same isolation group remain serialized
- Example: `isolation_group: "service-a"` and `isolation_group: "service-b"` can run concurrently
- Resource quotas per group (max 2 concurrent groups by default, configurable)

## Observability & Monitoring

### Instrumentation

| Signal | What | Output |
|---|---|---|
| **Metrics** | Run count, pass/fail per test, queue depth, queue wait time, adapter execution time | Prometheus endpoint or periodic log line |
| **Traces** | Full request flow: MCP call → validation → enqueue → scheduler → adapter → persist | Structured logs with trace_id |
| **Logs** | All MCP requests, status transitions, adapter output (first/last 10 lines), errors | JSON lines to stdout/stderr |

### Key metrics to expose

- `boehm_runs_total{tool, status}` — count of runs by tool and final status
- `boehm_run_duration_seconds{tool}` — test execution time histogram
- `boehm_queue_depth` — current number of queued runs
- `boehm_queue_wait_seconds` — time from `queued` → `running` (histogram)
- `boehm_adapter_execution_seconds{adapter}` — adapter execution time (histogram)
- `boehm_verdicts_total{verdict}` — count of PASS/REGRESSION/ERROR verdicts

### Alerting suggestions (external, not built-in)

- `avg queue wait > 5m` — scheduler is backlogged
- `failed runs per day > 5` — adapter or target infrastructure issues
- `boehm_runs_total == 0 for 7 days` — nobody is using it, consider review

## Operational Runbook

### Crash recovery

| Scenario | Recovery |
|---|---|
| Server killed during `running` | On restart, query `runs WHERE status='running'`. Mark them `failed` with error "server restarted". Process next queued run |
| Server killed during git checkout | `validate_pr` uses `git stash` before checkout and restores on completion. If interrupted, manual `git stash pop` in the repo may be needed |
| SQLite WAL corruption | SQLite WAL is auto-recovered on open. If corruption persists, restore from last backup |

### Backup & restore

- **Location:** `~/.boehm/boehm.db` (single file)
- **Backup command:** `sqlite3 ~/.boehm/boehm.db ".backup ~/.boehm/backups/boehm-$(date -I).db"`
- **Frequency:** Recommend daily for CI environments
- **Restore:** Stop server, replace file, restart
- **Retention:** Keep 30 daily backups, then monthly

### Data retention & purging

| Data | Retention | Purge mechanism |
|---|---|---|
| Run results (summary) | Indefinite | Manual DELETE from `runs` table |
| Raw output files | 90 days | Cron job: `find ~/.boehm/outputs -mtime +90 -delete` |
| Baseline history | Indefinite | Audit requirement — never auto-deleted |
| Failed runs | 30 days | Auto-purged after 30 days unless baseline-tagged |

### Storage limits & governance

- Raw output files limited to 10 MB per run (truncated at adapter level if exceeded)
- SQLite database size: warn at 500 MB, hard cap at 2 GB (configurable)
- Purge raw outputs monthly to stay under storage budget

### PII and sensitive data

- Raw output may contain request/response bodies, headers, and tokens
- Adapter SHOULD strip known sensitive headers (Authorization, Cookie, Set-Cookie) before persisting
- Raw outputs stored with restricted file permissions (0600)
- `metadata` field MUST NOT contain secrets (API keys, tokens, passwords)
- The agent (not Boehm) is responsible for redacting sensitive data from reports

## Security & Access Control

### ACL model

| Operation | Who can invoke | Phase |
|---|---|---|
| `list_adapters`, `get_run`, `list_runs`, `list_baselines` | Any connected client | 1 |
| `run_test` | Any connected client (but must have access to the target) | 1 |
| `mark_baseline`, `get_baseline`, `compare_with_baseline` | Any connected client (baseline changes are audited) | 2 |
| `mark_baseline` override (replace existing) | Requires `BOEHM_BASELINE_TOKEN` env var or explicit agent approval | 2 |
| `validate_pr` | Any connected client (operates on local repo) | 6 |

### Shell adapter threat model

| Threat | Mitigation |
|---|---|
| Command injection via test plan | Test plans define parameters (rate, duration, URL), not shell commands. Adapters build CLI args using parameterized exec |
| Arbitrary file read via tool | Each adapter validates the target URL/command before execution. k6 adapter rejects non-HTTP targets |
| Resource exhaustion | Max run duration enforced by timeout (configurable, default 10 min). Queue depth limits incoming runs |
| Sensitive data in raw output | Raw output files stored with 0600 permissions. Adapter strips known sensitive headers before persisting |
| Git repo manipulation | `validate_pr` rejects dirty working trees. Uses `git stash` push/pop, never `git reset --hard` |

### Credential handling

- Tool credentials (API keys, tokens) are passed via environment variables, not in test plans
- Example: `K6_CLOUD_TOKEN` for k6 cloud, `TULIP_API_KEY` for Tulip
- Agents should inject credentials into the MCP server's environment at startup
- No credentials stored in SQLite

## Environment Reproducibility

### CI environment contract

Boehm assumes the following are pre-installed in CI:
- Java 21+ JRE (for the MCP server)
- Tool binaries: k6, JMeter, Tulip (or their Docker equivalents)
- `git` (for `validate_pr`)

### Docker reproducibility

```yaml
# docker-compose.yml (for local dev and CI)
services:
  boehm:
    build: .
    image: boehm/perf-mcp-server:latest
    volumes:
      - ~/.boehm:/root/.boehm
      - /var/run/docker.sock:/var/run/docker.sock  # for tool containers
    environment:
      - BOEHM_LOG_LEVEL=info

  k6:
    image: grafana/k6:0.54.0
    entrypoint: ["sh", "-c", "tail -f /dev/null"]  # kept alive for Boehm to exec

  tulip:
    build: ../tulip
    image: tulip/perf-sdk:latest
    entrypoint: ["sh", "-c", "tail -f /dev/null"]
```

The docker-compose pins tool versions for reproducibility. The evals suite runs against these pinned images.

## Testing Strategy & Fixtures

### Adapter evaluation fixtures

Each adapter ships with a set of stored fixtures in `src/test/fixtures/adapters/<tool>/`:

```
fixtures/
└── adapters/
    └── k6/
        ├── input-basic-http.json           # TestPlan input
        ├── expected-output-basic-http.json  # Expected RunResult
        ├── raw-output-basic-http.txt        # Stored k6 stdout
        ├── input-error-timeout.json
        ├── expected-output-error-timeout.json
        └── raw-output-error-timeout.txt
```

These fixtures are used in unit tests: feed the raw output through the parser, assert the result matches the expected output. This decouples adapter tests from needing a live target.

### Test types

| Test type | What it verifies | Frequency | Phase |
|---|---|---|---|
| **Unit tests** | Adapter parsing, validation logic, regression engine | Every commit | 1 |
| **Fixture tests** | Adapter correctness using stored inputs/outputs | Every commit | 1 |
| **Integration tests** | Adapter against a real target (httpbin.org) | Daily CI | 1 |
| **Eval suite** | End-to-end: run_test → compare → verify | Per release | 3 |
| **Schema migration tests** | SQLite migrations preserve data | Every schema change | 2 |
| **CI matrix** | All supported tool versions × OS | Per release | 4 |

### CI test matrix

```yaml
strategy:
  matrix:
    os: [ubuntu-latest, macos-latest]
    tool: [k6-0.54, k6-0.53, jmeter-5.6]
```

## Costs & Resource Governance

### Run cost model

| Resource | Estimate | Notes |
|---|---|---|
| k6 30s HTTP test | ~30s wall clock, ~50 MB RAM | Single VU, 100 RPS |
| JMeter 60s test | ~60s wall clock, ~200 MB RAM | JVM overhead |
| Tulip in-process | Varies by test plan | Same JVM as Boehm, minimal overhead |
| Raw output storage | ~100 KB per run | Truncated at 10 MB |
| SQLite DB growth | ~5 KB per run | Summary-only, excluding raw output |
| Bisect per commit | 1 extra run per commit tested | Log(N) runs for N commits |

### Bisect guardrails

| Guardrail | Default | Configurable? |
|---|---|---|
| Max commits to bisect | 10 (covers up to 1024 commits in log2) | Yes |
| Max time budget per PR | 30 minutes | Yes |
| Max runs per PR | 20 | Yes |
| Bisect enabled by default | No (opt-in via `bisect: true`) | Per-call |

If bisect would exceed these limits, `validate_pr` returns the comparison without bisect and suggests manual investigation.

### Quota model (shared CI)

For teams sharing a Boehm instance:
- Max queue depth: 20 runs (new runs rejected with error when full)
- Max concurrent isolation groups: 2 by default, configurable
- Rate limit for `run_test`: 10 calls per minute per agent

## Versioning & Compatibility

| Concern | Policy |
|---|---|
| **Tool adapter versions** | Each adapter declares its `toolVersions` (e.g., `["k6 0.53.x", "k6 0.54.x"]`). Running against an untested version logs a warning. Adapters follow semver independently |
| **MCP tool contracts** | Tools follow semver on their parameter schemas. Adding an optional parameter is a minor bump. Removing/renaming a required parameter is a major bump |
| **SQLite schema** | `schema_version` table tracks migrations. Schema changes are additive for one major version (old columns remain). Breaking migrations require a server version bump with migration script |
| **Schema migration** | On startup, the server checks `schema_version` and applies any pending migrations. Migrations are tested against a copy of the DB before running on the real one |
| **Baseline store format** | The `runs.summary` JSON schema has no formal version field — adapters MUST only add fields to `metadata`. If core summary fields change, it's a major server version bump |

Baseline history retention: entries older than 1 year are archived to a separate `baseline_history_archive` table (or exported to a file) to keep query performance on `baseline_history` predictable.

## Skills (Reusable Agent Workflows)

Skills are markdown files that agents load. They encode Boehm's engineering methodology — how to design a performance experiment, how to interpret results, what to look for in a regression. They do NOT live in the MCP server; they are consumed by the agent.

| Skill | What it teaches the agent | Deliverable type |
|---|---|---|
| `boehm-test-http-api.md` | How to define and run HTTP load tests with appropriate rates, durations, and warmup | Documentation + agent workflow |
| `boehm-compare-baseline.md` | How to compare results, interpret thresholds, and decide pass/fail | Documentation + agent workflow |
| `boehm-report-regression.md` | How to format a before/after comparison as a Markdown table + Mermaid bar chart | Documentation + agent workflow |
| `boehm-pr-check.md` | Full workflow: validate a PR end-to-end, bisect regressions, post a review comment | Documentation + agent workflow |
| `boehm-trend-analysis.md` | How to detect gradual degradation across N runs using statistical methods | Documentation + agent workflow |

Skills are packaged as markdown files in `skills/` directory of the Boehm repo. They are loaded by the agent's skill system, not by Boehm itself. Skills are added after the MCP server works (Phase 5+).

## Future Vision

### Git Integration & PR Troubleshooting (Phase 6)

```
Tool: validate_pr
Input:  repo, pr_number, test_suite, thresholds?, bisect?, timeout_min?
Output: comparison report with commit attribution
```

**How it works:**
1. Agent calls `validate_pr(repo="...", pr_number=42, test_suite="http-api")`
2. Boehm checks for dirty working tree — rejects if uncommitted changes exist
3. Stashes current state, checks out base branch, runs test suite → baseline
4. Checks out PR head, runs same suite → candidate
5. Generates diff comparing head vs base
6. **Commit-level bisect** (opt-in): binary search across commits in the branch, each requiring one test run
7. Returns report linking regressions to specific commits, files, or code changes

**Cost estimate for bisect:**
- 1 commit in PR: 0 extra runs (only base + head needed)
- 8 commits: 3 extra runs (log2 8 = 3)
- 32 commits: 5 extra runs
- 100 commits: 7 extra runs
- Max by default: 10 bisect runs (handles up to 1024 commits)

### Advanced Statistical Analysis (Phase 7)

| Capability | Description |
|---|---|
| **Distribution comparison** | Compare full latency histograms using KS-test or Mann-Whitney U test |
| **Trend detection** | Aggregate last N runs into a trend line; detect gradual degradation before thresholds are breached |
| **Anomaly detection** | Flag runs that deviate statistically from the historical distribution (3σ or IQR-based) |
| **Resource correlation** | Correlate perf results with CPU/memory/GC metrics when available |
| **Multi-variable regression** | Identify which test parameters (concurrency, payload size, method) most affect latency |

### Cross-Tool Comparison (Phase 7)

Compare results from different tools running the same test scenario to validate tool consistency and identify tool-specific overhead.

## Risks & Tradeoffs

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Serial execution is a bottleneck** | Medium | High for orgs with many concurrent PRs | Default is serial for measurement integrity. Opt-in `isolation_group` parallelism in Phase 2. Resource quotas per group |
| **Shell exec adapters are brittle** | Medium | Medium | Each adapter captures and parses stderr. Validation runs before execution. Version pinning per adapter. Container-based execution for CI |
| **Performance bisect is expensive** | Low | Medium for large PRs | Bisect is opt-in. Binary search = log(N) runs. Hard cap at 10 bisect runs. Time budget of 30 min per PR check |
| **Noisy load test results** | High | Medium | Warmup period, statistical confidence flags, configurable N-runs median. Skills teach agents to interpret noise, not just raw numbers. False positive budget with auto-quarantine |
| **Git checkout races with other work** | Low | High | `validate_pr` checks for dirty working tree and rejects if uncommitted changes exist. Stashes/restores cleanly. Lock file in TMPDIR prevents concurrent git operations |
| **MCP ecosystem is evolving** | Medium | Medium | Kotlin MCP SDK may not exist yet — fallback is implementing JSON-RPC 2.0 protocol directly over stdio, which is ~500 lines. Protocol is well-documented |

## Implementation Phases

### Phase 1 — MCP Server Scaffold + First Adapter
- Gradle project setup with MCP protocol support (direct JSON-RPC or SDK)
- Core layer: MCP handler, orchestrator, run recording, SQLite schema
- Run scheduler with serial execution
- First adapter (Tulip or k6)
- `list_adapters`, `run_test`, `get_run` tools
- Unit tests + integration test against httpbin.org
- Fixed: tool-timeout handling, test plan validation

### Phase 2 — Baseline & Comparison
- `mark_baseline`, `get_baseline`, `list_baselines`, `compare_with_baseline`, `list_runs` tools
- Baseline lifecycle with env tags
- Threshold configuration and regression decision logic
- Baseline staleness detection
- `isolation_group` support for opt-in parallelism
- Unit tests + integration tests
- Fixed: false positive rate ≤10%

### Phase 3 — Evals
- Adapter fixture test suite (stored inputs/outputs)
- Determinism verification
- Crash recovery testing
- Schema migration tests
- Protocol compliance tests

### Phase 4 — Additional Adapters
- Second adapter
- Adapter developer guide (with fixture template)
- CI test matrix (multiple tool versions × OS)

### Phase 5 — Skills & Agent Integration
- Bundled skill files in `skills/` directory
- Example agent configurations for Claude Code, Cline
- Quickstart: "first PR check in <5 minutes"

### Phase 6 — Git PR Integration
- `validate_pr` tool with checkout/run/compare/bisect
- Commmit attribution in bisect results
- CI integration: GitHub Action + GitLab CI template
- PR comment posting with Mermaid charts
- Guardrails: max bisect runs, time budget, dirty-tree detection

### Phase 7 — Advanced Analysis
- Statistical distribution comparison (KS-test, Mann-Whitney)
- Trend detection and anomaly flagging
- Cross-tool comparison
- Resource correlation

## Non-Goals (Phase 1-3)

- HTML report rendering — use Markdown + Mermaid
- GUI or dashboard — out of scope for Phases 1-3; may be reconsidered later
- Cloud-hosted database — local SQLite always; PostgreSQL migration path documented
- Fully unsupervised PR blocking — human-in-the-loop for threshold changes and baseline management
- Support for non-CLI tools (e.g., cloud-hosted load generators) — future consideration
- Concurrent test execution by default — serial by design; opt-in via `isolation_group`
- Built-in alerting — metrics are exposed; alerts are the consumer's responsibility
