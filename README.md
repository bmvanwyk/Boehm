# Boehm

**Performance Testing AI Engineer — an AI specialist that designs, runs, analyzes, and investigates performance tests across any tool.**

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm), known for the COCOMO model and spiral model of software development.

Boehm is not just a test runner. It's an engineer with:

- **Institutional memory** — every run preserved in SQLite, any run taggable as a baseline
- **Clean measurements** — run scheduler serializes execution to prevent overlapping test noise
- **Investigation skills** — detects regressions, bisects commits, traces problems to their source
- **Tool fluency** — speaks Tulip, k6, JMeter, Gatling, and more through a unified MCP interface

## Who it's for

- **Performance engineers** reviewing PRs
- **SREs** running CI gates
- **Developers** sanity-checking local changes
- **QA leads** standardizing perf testing across teams

## Status

**Phase 1 complete** — MCP server with Tulip adapter. Bearer token auth, serial run queue, SQLite persistence, 5 MCP tools.

| Tool | Description |
|------|-------------|
| `list_adapters` | List available performance testing tools |
| `run_test` | Queue a new performance test |
| `get_run` | Retrieve a completed run with full results |
| `server_status` | Get queue depth, current run, uptime |
| `get_run_progress` | Poll for live progress during execution |

## Quick Start

```bash
# Build and test
./gradlew build

# Run unit tests only
./gradlew test --tests "io.boehm.*" --exclude-task "test" "*Integration*"

# Run integration test (requires Tulip repo at ~/git/Tulip)
./gradlew test --tests "io.boehm.integration.TulipIntegrationTest"

# Start the MCP server
./gradlew run --args="--token=boehm_sk_$(openssl rand -hex 16)"
```

## Architecture

```
MCP Client (Claude Code, etc.)
       │ JSON-RPC 2.0 over stdio
       ▼
┌─────────────────────────────┐
│  McpHandler                 │
│  └─ route tools, auth check │
├─────────────────────────────┤
│  Orchestrator + Scheduler   │
│  └─ serial run queue        │
├─────────────────────────────┤
│  TulipAdapter               │
│  ├─ generate temp config    │
│  ├─ exec tulip --config     │
│  └─ parse output → RunResult│
├─────────────────────────────┤
│  SQLite Store               │
│  └─ adapters, runs, schemas │
└─────────────────────────────┘
```

Each tool adapter communicates with its CLI via shell exec. The Tulip adapter generates a temporary JSON config file from the `TestPlan` and passes it via `--config <file>`. Results are parsed from Tulip's native output format (JSON object with `results[]` array, nanosecond-precision latency).

## Specs

- [Phase 1 spec](docs/superpowers/specs/2026-07-20-boehm-phase-1.md) — active implementation target
- [Architecture](docs/architecture.md) — component design, data flows, ADRs
- [Full vision](docs/superpowers/specs/2026-07-20-boehm-vision.md) — Phases 2-7

## See also

- [`AGENTS.md`](AGENTS.md) — agent guide for AI workers
- [`docs/superpowers/plans/2026-07-21-boehm-phase-1.md`](docs/superpowers/plans/2026-07-21-boehm-phase-1.md) — detailed implementation plan
