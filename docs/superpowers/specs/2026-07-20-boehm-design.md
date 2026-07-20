# Boehm: Performance Testing AI Engineer

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm), known for the COCOMO model and spiral model of software development.

## Product Overview

Boehm is an **AI engineer specialized in performance testing**. It gives AI coding agents (Claude Code, Cline, etc.) the ability to design performance experiments, execute them across multiple tools, analyze results statistically, track baselines over time, and investigate regressions back to the code change that caused them.

Three things that make it an "engineer" rather than a test runner:
- **Institutional memory** — every run is preserved; any run can be tagged as a baseline for comparison
- **Investigation skills** — when a regression is detected, Boehm can bisect commits, correlate with code changes, and explain what happened
- **Tool fluency** — it speaks Tulip, k6, JMeter, Gatling, and any other performance tool through a unified adapter interface

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

## Success Criteria

### Product outcomes

| Phase | What success looks like |
|---|---|
| Phase 1 | A developer can open Claude Code, say "run a load test against httpbin.org at 100 RPS for 30s using Tulip", and get a structured result back |
| Phase 2 | Same developer can say "mark that as baseline, run it again, compare" and get a regression report. CI can call `compare_with_baseline` and exit non-zero on threshold breach |
| Phase 3 | Boehm has its own eval suite proving correctness. Adapter output is verified against known fixtures |
| Phase 4-5 | Two adapters (Tulip + k6) work. A bundled agent skill can guide a new user through their first PR check in <5 minutes |
| Phase 6-7 | `check_pr` runs in CI, posts a comment with before/after Mermaid charts, and bisects regressions to individual commits. Analysts trust the results enough to block merges |

### What "autonomous performance engineer" means in practice

- **Not** fully unsupervised — a human should review and approve regression thresholds, baseline tags, and CI gates
- **Is** able to execute a multi-step investigation (checkout → run → compare → bisect → report) without hand-holding
- **Is** able to explain its findings in natural language with supporting data (tables, charts, deltas)
- **Is not** a replacement for a senior perf engineer's deep analysis — it's a force multiplier that handles the mechanical work and highlights what needs human attention

### Metrics

- **Manual perf investigation time reduction** — a PR regression check that takes a human 30-60min should take Boehm <5min
- **Regressions caught before merge** — the comparison tool should make it easier to block regressions than to merge them
- **False positive rate** — threshold defaults and statistical methods should keep false alarms under 10% of runs; users can tighten per-test
- **CI decision determinism** — same commit + same baseline = same pass/fail result (modulo inherent load test noise)

## Architecture

```
AI Agent (Claude Code, Cline, etc.)
  │  Loads skills (reusable agent workflows)
  │  Uses MCP protocol
  ▼
perf-mcp-server (Kotlin/JVM)
  ├── Core Layer
  │   ├── MCP Handler        — exposes MCP tools/resources
  │   ├── Orchestrator       — routes test plans to adapters, manages runs
  │   └── Baseline Store     — persists runs as JSON, supports baseline lifecycle
  └── Adapter Layer
      ├── tool adapters      — translates test plans to tool-specific invocation
      └── adapter registry   — discoverable by agents via list_adapters
```

### Data Flow

1. Agent calls `run_test(tool, test_name, test_plan)` via MCP
2. Core layer validates, persists pending run, dispatches to adapter
3. Adapter translates test plan to tool-specific invocation (in-process JVM call, shell exec, etc.)
4. Adapter parses tool output into normalized `RunResult`
5. Core layer persists result, returns `run_id` to agent

## Adapter Interface

Every tool adapter implements:

```
interface PerfToolAdapter {
    val name: String
    val supportedTestTypes: List<TestType>
    fun run(testPlan: TestPlan): RunResult
    fun validate(testPlan: TestPlan): List<ValidationError>
}
```

Adapters are discoverable — an agent can call `list_adapters` to see what tools are available. Each adapter declares its supported test types (HTTP, database, custom, etc.) so the agent can route correctly.

## MCP Tools (API Surface)

### Core tools

| Tool | Input | Output | Phase |
|---|---|---|---|
| `list_adapters` | — | Tool names + supported test types | 1 |
| `run_test` | `tool`, `test_name`, `test_plan` | `run_id`, `RunResult` | 1 |
| `get_run` | `run_id` | Full `RunResult` | 1 |
| `mark_baseline` | `tool`, `test_name`, `run_id` | Confirmation | 2 |
| `get_baseline` | `tool`, `test_name` | Baseline `RunResult` or null | 2 |
| `list_baselines` | `tool?`, `test_name?` | List of baselines | 2 |
| `compare_with_baseline` | `tool`, `test_name`, `run_id` | Diff report with deltas + verdict | 2 |
| `list_runs` | `tool?`, `test_name?`, `limit?` | Recent runs metadata | 2 |
| `validate_pr` | `repo`, `pr_number`, `test_suite`, `thresholds?` | PR comparison report | 6 |

### Error handling semantics

| Scenario | Behavior |
|---|---|
| Tool CLI not found | Adapter returns `RunResult` with status `"error"`, error details, no summary |
| Test exceeds timeout | Adapter kills the subprocess, returns partial results if available |
| Baseline not found | `compare_with_baseline` returns error with message: "no baseline for (tool, test_name)" |
| Invalid test plan | `run_test` returns validation errors without executing |
| Network failure (target unreachable) | Adapter returns error, raw output captures CLI stderr |

### How agents invoke Boehm

- **From an AI coding assistant** — agent loads a skill file and makes MCP tool calls directly
- **From CI** — via a thin shell wrapper or GitHub Action that runs pre-configured comparisons and exits non-zero on regression
- **From CLI** — direct `boehm run_test ...` or `boehm compare ...` for human-driven sessions

## Data Model

### RunResult

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

**Key design decisions:**
- `summary` is intentionally minimal — required fields that every adapter must populate. Adapters can extend `metadata` with tool-specific fields (e.g., Tulip's GC pause times, k6's iteration counts)
- `rawOutputPath` is optional but recommended for debugging. If absent, debug requires re-running
- `metadata` is a free-form map for adapter-specific data, environment tags, or correlation IDs

### Baseline lifecycle

| Action | Behavior |
|---|---|
| **Create** | `mark_baseline(tool, test_name, run_id)` — tags a run as the baseline. Only one active baseline per `(tool, test_name)` pair |
| **Replace** | Calling `mark_baseline` again on the same pair replaces the baseline pointer |
| **Compare** | `compare_with_baseline` always reads the current baseline pointer |
| **List** | `list_baselines` shows every run that was ever tagged, not just the active pointer |
| **Stale detection** | If baseline timestamp > N days old (configurable, default 30), comparison returns a warning but still executes. The agent can then prompt: "The baseline for http-api-load is 45 days old — environments may have changed. Consider updating." |
| **Multiple baselines** | Phase 2 supports one active baseline per `(tool, test_name)`. Future phases may add environment tags (e.g., baseline per `staging`/`production` environment) |

### Regression decision logic

```
compare_with_baseline runs → shortfall = candidate - baseline
if shortfall.p95_pct > threshold.p95_pct:
    verdict = "REGRESSION"
elif shortfall.p99_pct > threshold.p99_pct:
    verdict = "REGRESSION"
elif shortfall.error_rate_pp > threshold.error_rate_pp:
    verdict = "REGRESSION"
elif shortfall.throughput_pct < threshold.throughput_drop_pct:
    verdict = "REGRESSION"
else:
    verdict = "PASS"
```

**Threshold defaults** (configurable per test):
- p95: +10%
- p99: +15%
- error rate: +2 percentage points
- throughput drop: -15%

**Flakiness mitigation:**
- Option to run N times and compare median run, or run once with non-determinism tolerance
- Warmup period excluded from metrics (configurable per adapter)
- `compare_with_baseline` returns statistical confidence flags when distribution comparison is available (Phase 7+)

## Baseline Store

Persistence under `~/.boehm/runs/`:

```
~/.boehm/runs/
├── {test_name}/
│   ├── {timestamp}-{run_id}.json
│   └── BASELINE -> {timestamp}-{run_id}.json
```

**Why JSON files?**
- Zero infrastructure — works immediately in CI, local dev, any environment
- Simple to inspect, debug, and back up
- Adequate for single-team usage (thousands of runs, not millions)
- **Limitation:** JSON store does not support concurrent writers, complex queries, or TB-scale data
- **Evolution path:** If multi-team or high-volume usage emerges, the store can be swapped for SQLite (same file-based simplicity, better querying) or PostgreSQL. The baseline store interface should be abstracted from day one

## Non-Functional Requirements

| Concern | Requirement |
|---|---|
| **Reliability (git)** | `validate_pr` must safely stash/restore working tree state. If interrupted, it must not leave the repo in a detached HEAD state |
| **Reproducibility** | Same `TestPlan` + same tool version + same target = same `RunResult` within statistical noise. The adapter must document known sources of variance |
| **Performance** | MCP server overhead should be < 50ms per request (excluding test execution time). Adapter operations are async — the agent polls `get_run` for completion |
| **Security** | Shell exec adapters MUST NOT allow arbitrary command injection. Test plans define parameters, not shell commands. Raw output may contain sensitive data; agents control access |
| **Concurrency** | JSON store uses file-level locking. Multiple runs against the same test name are serialized. Independent test names can run in parallel |

## Versioning & Compatibility

| Concern | Policy |
|---|---|
| **Tool adapter versions** | Each adapter declares its tested tool version range. The Tulip adapter targets Tulip X.Y; running against an untested version produces a warning |
| **MCP tool contracts** | Tools follow semver on their parameter schemas. Breaking changes (removing/renaming a required parameter) bump the MCP server major version |
| **Baseline store format** | The JSON schema is versioned (`schemaVersion` field). Old formats are read-compatible for one major version, then migrated |

## Skills (Reusable Agent Workflows)

Skills are markdown files that agents load. They encode Boehm's engineering methodology — how to design a performance experiment, how to interpret results, what to look for in a regression. They do NOT live in the MCP server; they are consumed by the agent.

| Skill | What it teaches the agent |
|---|---|
| `boehm-test-http-api.md` | How to define and run HTTP load tests with appropriate rates, durations, and warmup |
| `boehm-compare-baseline.md` | How to compare results, interpret thresholds, and decide pass/fail |
| `boehm-report-regression.md` | How to format a before/after comparison as a Markdown table + Mermaid bar chart |
| `boehm-pr-check.md` | Full workflow: validate a PR end-to-end, bisect regressions, post a review comment |
| `boehm-trend-analysis.md` | How to detect gradual degradation across N runs using statistical methods |

Skills are added after the MCP server works (Phase 5+).

## Future Vision

### Git Integration & PR Troubleshooting

```
Tool: validate_pr
Input:  repo, pr_number, test_suite, thresholds?
Output: comparison report with commit attribution
```

**How it works:**
1. Agent calls `validate_pr(repo="...", pr_number=42, test_suite="http-api")`
2. Boehm stashes current state, checks out base branch, runs test suite → baseline
3. Checks out PR head, runs same suite → candidate
4. Generates diff comparing head vs base
5. **Commit-level bisect** — if multiple commits in the branch, Boehm can bisect-run to identify which commit introduced each regression
6. Returns report linking regressions to specific commits, files, or code changes

**CI integration:** Designed to run in GitHub Actions / GitLab CI — non-zero exit when regressions exceed threshold, with a PR comment posted containing the comparison report with Mermaid charts.

### Advanced Statistical Analysis

| Capability | Description | Phase |
|---|---|---|
| **Distribution comparison** | Compare full latency histograms using KS-test or Mann-Whitney | 7 |
| **Trend analysis** | Aggregate last N runs into a trend line; detect gradual degradation | 7 |
| **Anomaly detection** | Flag runs that deviate statistically from the historical distribution | 7 |
| **Resource correlation** | Correlate perf results with CPU/memory/GC metrics from the target system | 7 |
| **Multi-variable regression** | Identify which test parameters most affect latency | 7 |

### Cross-Tool Comparison

Compare results from different tools running the same test scenario to validate tool consistency and identify tool-specific overhead.

## Risks & Tradeoffs

| Risk | Mitigation |
|---|---|
| **JSON store doesn't scale** | Abstract the store interface from day one. If needed, swap for SQLite or PostgreSQL without changing the rest of the server |
| **Shell exec adapters are brittle** | Each adapter captures and parses stderr. Adapter validation runs before execution. Version pinning per adapter |
| **Performance bisect is expensive** | `validate_pr` bisects only on request (opt-in flag). Bisect uses binary search — log(N) runs for N commits |
| **Noisy load test results** | Warmup period, statistical confidence flags, configurable N-runs median. The agent is trained (via skills) to interpret noise, not just raw numbers |
| **Git checkout races with other work** | `validate_pr` checks for dirty working tree and rejects if uncommitted changes exist. It stashes/restores cleanly |
| **MCP ecosystem is evolving** | Kotlin MCP SDK may not exist yet — fallback is implementing the JSON-RPC protocol directly over stdio, which is < 500 lines |

## Implementation Phases

### Phase 1 — MCP Server Scaffold + First Adapter
- Gradle project setup with MCP protocol support (direct JSON-RPC or SDK)
- Core layer: MCP handler, orchestrator, run recording
- First adapter (Tulip or k6 — decided by implementation priority)
- `list_adapters`, `run_test`, `get_run` tools
- Unit tests + integration test against a real target (e.g., httpbin)

### Phase 2 — Baseline & Comparison
- `mark_baseline`, `get_baseline`, `list_baselines`, `compare_with_baseline`, `list_runs` tools
- Baseline store implementation with lifecycle management
- Threshold configuration and regression decision logic
- Unit tests + integration tests

### Phase 3 — Evals
- Test framework for Boehm itself — verify adapter correctness against known fixtures
- Regression test: same test plan + same target = same result
- Protocol compliance tests

### Phase 4 — Additional Adapters
- Second adapter (whichever wasn't chosen in Phase 1)
- Adapter developer guide

### Phase 5 — Skills & Agent Integration
- Bundled skill files (`boehm-test-http-api.md`, `boehm-compare-baseline.md`, `boehm-report-regression.md`)
- Example agent configurations for Claude Code, Cline
- Quickstart: "first PR check in <5 minutes"

### Phase 6 — Git PR Integration
- `validate_pr` tool with checkout/run/compare/bisect
- CI integration: GitHub Action, GitLab CI template
- PR comment posting with Mermaid charts

### Phase 7 — Advanced Analysis
- Statistical distribution comparison
- Trend detection and anomaly flagging
- Cross-tool comparison
- Resource correlation

## Non-Goals (Phase 1-3)

- HTML report rendering — use Markdown + Mermaid
- GUI or dashboard — out of scope for Phases 1-3; may be reconsidered later
- Cloud-hosted baseline store — local-first always, but remote store may be added
- Fully unsupervised PR blocking — human-in-the-loop for threshold changes and baseline management
- Support for non-CLI tools (e.g., cloud-hosted load generators) — future consideration
