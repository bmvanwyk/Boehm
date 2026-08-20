# Boehm

**Performance Testing AI Engineer — an MCP server that runs performance tests across multiple tools and analyzes results.**

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm).

## Prerequisites

- **Java 21** — install via SDKMAN: `sdk install java 21.0.7-tem`
- **Tulip CLI** (for Tulip adapter) — cloned to `~/git/Tulip`, built with `./gradlew build`
- **JMeter** (for JMeter adapter) — install [Apache JMeter 5.6.3](https://jmeter.apache.org/download_jmeter.cgi), ensure `jmeter` is on PATH and `JAVA_HOME` is set

## Quick start

```bash
# Build (compile + test)
./gradlew build

# Run unit tests only (fast, no Tulip needed)
./gradlew test

# Run integration test (requires ~/git/Tulip with Tulip built)
./gradlew test --tests "io.boehm.integration.TulipIntegrationTest"

# Run JMeter integration test (requires JMeter on PATH + JAVA_HOME set)
./gradlew test --tests "io.boehm.integration.JMeterIntegrationTest"

# Start the MCP server (stdio)
./gradlew run --args="--token=boehm_sk_$(openssl rand -hex 16)"
```

## Project structure

```
Boehm/
├── build.gradle.kts              # Kotlin/JVM, Gson, SQLite, SnakeYAML
├── catalog.yaml                   # Tool index: profiles, overrides, parsers
├── profiles/
│   ├── tulip/
│   │   ├── http-get.jsonc         # Tulip config template (JSON with overrides)
│   │   └── demo.jsonc
│   ├── k6/http-get.js             # k6 script template (env vars)
│   ├── jmeter/http-get.jmx        # JMeter plan template (properties)
│   └── gatling/http-get.scala     # Gatling simulation template (system props)
├── src/
│   ├── main/kotlin/io/boehm/
│   │   ├── Main.kt                # stdio entry point, catalog loader
│   │   ├── auth/AuthHandler.kt    # SHA-256 bearer token
│   │   ├── catalog/
│   │   │   ├── CatalogModels.kt   # Data classes for catalog.yaml
│   │   │   ├── CatalogLoader.kt   # Parse catalog.yaml → typed models
│   │   │   └── CatalogAdapter.kt  # Generic PerfToolAdapter (catalog-driven)
│   │   ├── core/
│   │   │   ├── McpHandler.kt      # JSON-RPC dispatcher (5 tools)
│   │   │   ├── Orchestrator.kt    # Route test plans → adapters
│   │   │   ├── Scheduler.kt       # Serial run queue
│   │   │   └── Store.kt           # SQLite (adapters, runs, schemas)
│   │   ├── model/                 # TestPlan, RunResult, Summary, Latency
│   │   ├── adapters/tulip/
│   │   │   └── TulipParser.kt     # Native Tulip JSON → RunResult
│   │   └── adapters/jmeter/
│   │       └── JMeterParser.kt   # JMeter JTL CSV → RunResult
│   └── test/
│       ├── adapter/               # TulipAdapterTest, JMeterParserTest
│       ├── parser/                # TulipParserTest
│       ├── auth/                  # AuthHandlerTest
│       ├── core/                  # McpHandler, Orchestrator, Scheduler, Store
│       ├── fixtures/
│       │   ├── mock-tulip.sh      # Mock CLI for unit tests
│       │   ├── run-real-tulip.sh  # Wrapper for integration test
│       │   ├── tulip-sample-output.json
│       │   └── jmeter-sample-output.csv
│       └── integration/
│           ├── TulipIntegrationTest.kt
│           └── JMeterIntegrationTest.kt
```

## Using with opencode

The `.opencode/opencode.jsonc` config registers Boehm as an MCP server. Restart opencode to pick it up, then:

```
use boehm to list adapters
use boehm to queue a test named demo with tool tulip profile http-get against https://httpbin.org/get at 50 req/s for 30s
use boehm to queue a test named http-get with tool jmeter profile http-get against httpbin.org with 10 threads for 30s
use boehm to check server status
use boehm to get the result for run <runId>
```

### MCP tools

| Tool | Input | Output |
|------|-------|--------|
| `list_adapters` | — | Available tools + supported profiles |
| `run_test` | `tool`, `test_name`, `test_plan` (contains `type`, `profile`, `target_url`, `rate_per_sec`, `duration_sec`, `warmup_sec`, plus tool-specific params) | `runId`, status `queued` |
| `get_run` | `run_id` | Full `RunResult` with latency, throughput, error rate |
| `server_status` | — | Queue depth, currently running, uptime, registered adapters |
| `get_run_progress` | `run_id` | Status, `progress_pct`, `current_stage`, `rolling_summary` |

## How it works

1. An agent sends `run_test` with a tool name, profile, and parameter overrides
2. `McpHandler` parses the request and constructs a `TestPlan` with common fields + tool-specific `parameters`
3. `Orchestrator` validates the plan and queues the run in SQLite (serial execution — one at a time for clean measurements)
4. `CatalogAdapter` loads the profile template from `profiles/<tool>/`, applies overrides via JSON path (JSON-based tools) or env vars (script-based tools), shell-execs the CLI command, and captures output
5. Results are parsed into a normalized `RunResult` and persisted in SQLite
6. The agent polls `get_run` or `get_run_progress` until complete

## Test catalog

`catalog.yaml` defines every test profile across all supported tools:

| Tool | Profiles | Status |
|------|----------|--------|
| Tulip | `http-get`, `demo` | Implemented |
| k6 | `http-get` | Designed (parser needed) |
| JMeter | `http-get` | Implemented |
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
