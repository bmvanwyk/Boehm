# Boehm

**Performance Testing AI Engineer — an MCP server that runs performance tests across multiple tools and analyzes results.**

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm).

## Prerequisites

- **Java 21** — install via SDKMAN: `sdk install java 21.0.7-tem`
- **Tulip CLI** (for Tulip adapter) — cloned to `~/git/Tulip`, built with `./gradlew build`

## Quick start

```bash
# Build (compile + test)
./gradlew build

# Run unit tests only (fast, no Tulip needed)
./gradlew test

# Run integration test (requires ~/git/Tulip with Tulip built)
./gradlew test --tests "io.boehm.integration.TulipIntegrationTest"

# Start the MCP server (stdio)
./gradlew run --args="--token=boehm_sk_$(openssl rand -hex 16)"
```

## Project structure

```
Boehm/
├── build.gradle.kts              # Kotlin/JVM, Gson, SQLite, JUnit 5
├── catalog.yaml                   # Test profiles for every tool
├── profiles/
│   └── tulip/
│       ├── http-get.jsonc         # Static config template (checked in)
│       └── demo.jsonc
├── src/
│   ├── main/kotlin/io/boehm/
│   │   ├── Main.kt                # stdio entry point
│   │   ├── auth/AuthHandler.kt    # SHA-256 bearer token
│   │   ├── core/
│   │   │   ├── McpHandler.kt      # JSON-RPC dispatcher (5 tools)
│   │   │   ├── Orchestrator.kt    # Route test plans → adapters
│   │   │   ├── Scheduler.kt       # Serial run queue
│   │   │   └── Store.kt           # SQLite (adapters, runs, schemas)
│   │   ├── model/                 # TestPlan, RunResult, Summary, Latency
│   │   └── adapters/tulip/
│   │       ├── TulipAdapter.kt    # Config template → overrides → exec → parse
│   │       └── TulipParser.kt     # Native Tulip JSON → RunResult
│   └── test/
│       ├── adapter/               # TulipAdapterTest, TulipParserTest
│       ├── auth/                  # AuthHandlerTest
│       ├── core/                  # McpHandler, Orchestrator, Scheduler, Store
│       ├── fixtures/
│       │   ├── mock-tulip.sh      # Mock CLI for unit tests
│       │   ├── run-real-tulip.sh  # Wrapper for integration test
│       │   └── tulip-sample-output.json
│       └── integration/
│           └── TulipIntegrationTest.kt
```

## Using with opencode

The `.opencode/opencode.jsonc` config registers Boehm as an MCP server. Restart opencode to pick it up, then:

```
use boehm to list adapters
use boehm to queue a test named demo with tool tulip against https://httpbin.org/get at 50 req/s for 30s
use boehm to check server status
use boehm to get the result for run <runId>
```

### MCP tools

| Tool | Input | Output |
|------|-------|--------|
| `list_adapters` | — | Available tools + supported test types |
| `run_test` | `tool`, `test_name`, `test_plan` | `runId`, status `queued` |
| `get_run` | `run_id` | Full `RunResult` with latency, throughput, error rate |
| `server_status` | — | Queue depth, current run, uptime |
| `get_run_progress` | `run_id` | Live `progress_pct`, `current_stage`, `rolling_summary` |

## How it works

1. An agent sends `run_test` with a tool name, profile, and parameter overrides
2. Boehm loads the profile template from `profiles/<tool>/`, applies overrides
3. The scheduler queues the run (serial execution — one at a time, clean measurements)
4. The adapter shell-execs the tool CLI, captures output
5. Results are parsed into a normalized `RunResult` and persisted in SQLite
6. The agent polls `get_run` or `get_run_progress` for the result

## Test catalog

`catalog.yaml` defines every test profile across all supported tools:

| Tool | Profiles | Status |
|------|----------|--------|
| Tulip | `http-get`, `demo` | Implemented |
| k6 | `http-get` | Designed (parser needed) |
| JMeter | `http-get` | Designed (parser needed) |
| Gatling | `http-get` | Designed (parser needed) |
| vegeta | `http-get` | Designed (parser needed) |
| wrk | `http-get` | Designed (parser needed) |

Each profile declares a static config template, overridable params, and the output path/format so the adapter knows where to find and how to parse results.

## Specs

- [Phase 1 spec](docs/superpowers/specs/2026-07-20-boehm-phase-1.md)
- [Architecture](docs/architecture.md)
- [Full vision](docs/superpowers/specs/2026-07-20-boehm-vision.md)
- [`catalog.yaml`](catalog.yaml) — test profile index
- [`AGENTS.md`](AGENTS.md) — agent guide
