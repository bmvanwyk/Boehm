# Boehm

**Performance Testing AI Engineer — an MCP server that runs performance tests across multiple tools and analyzes results.**

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm).

## Prerequisites

- **Java 21** — install via SDKMAN: `sdk install java 21.0.7-tem`
- **Tulip CLI** (for Tulip adapter) — cloned to `~/git/Tulip`, built with `./gradlew build`
- **JMeter** (for JMeter adapter) — install [Apache JMeter 5.6.3](https://jmeter.apache.org/download_jmeter.cgi), ensure `jmeter` is on PATH and `JAVA_HOME` is set
- **k6** (for k6 adapter) — install via `brew install k6`, the [official deb repo](https://grafana.com/docs/k6/latest/set-up/install-k6/), or `go install go.k6.io/k6@latest`; ensure `k6` is on PATH

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

# Run k6 integration test (requires k6 on PATH; skips gracefully if absent)
./gradlew test --tests "io.boehm.integration.K6IntegrationTest"

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
│   │   ├── catalog/
│   │   │   ├── CatalogModels.kt   # Data classes for catalog.yaml
│   │   │   ├── CatalogLoader.kt   # Parse catalog.yaml → typed models
│   │   │   └── CatalogAdapter.kt  # Generic PerfToolAdapter (catalog-driven)
│   │   ├── core/
│   │   │   ├── BoehmToolHandlers.kt # 9 MCP tool handlers (suspend funs)
│   │   │   ├── Orchestrator.kt    # Route test plans → adapters
│   │   │   ├── Scheduler.kt       # Serial run queue
│   │   │   └── Store.kt           # SQLite (adapters, runs, schemas)
│   │   ├── model/                 # TestPlan, RunResult, Summary, Latency
│   │   ├── adapters/tulip/
│   │   │   └── TulipParser.kt     # Native Tulip JSON → RunResult
│   │   ├── adapters/jmeter/
│   │   │   └── JMeterParser.kt   # JMeter JTL CSV → RunResult
│   │   └── adapters/k6/
│   │       └── K6Parser.kt       # k6 NDJSON → RunResult
│   └── test/
│       ├── adapter/               # TulipAdapterTest, JMeterParserTest, K6ParserTest, TulipParserTest
│   │   ├── catalog/               # AdapterBuilder, CatalogLoader tests
│   │   ├── core/                  # BoehmToolHandlers (BoehmServerTest), Comparator, Orchestrator, Scheduler, Store
│       ├── fixtures/
│       │   ├── mock-tulip.sh      # Mock CLI for unit tests
│       │   ├── run-real-tulip.sh  # Wrapper for integration test
│       │   ├── tulip-sample-output.json
│       │   ├── jmeter-sample-output.csv
│       │   └── k6-sample-output.jsonl
│       └── integration/
│           ├── TulipIntegrationTest.kt
│           ├── JMeterIntegrationTest.kt
│           └── K6IntegrationTest.kt
```

## Using with opencode

The `.opencode/opencode.jsonc` config registers Boehm as an MCP server. Restart opencode to pick it up, then:

```
use boehm to list adapters
use boehm to queue a test named demo with tool tulip profile http-get against https://httpbin.org/get at 50 req/s for 30s
use boehm to queue a test named http-get with tool jmeter profile http-get against httpbin.org with 10 threads for 30s
use boehm to check server status
use boehm to get the result for run <runId>
use boehm to list recent runs
use boehm to tag run <runId> as the baseline
use boehm to compare run <runId> against the baseline
use boehm to cancel run <runId>
```

### MCP tools

| Tool | Input | Output |
|------|-------|--------|
| `list_adapters` | — | Available tools + supported profiles |
| `run_test` | `tool`, `test_name`, `test_plan` (contains `type`, `profile`, `target_url`, `rate_per_sec`, `duration_sec`, `warmup_sec`, plus tool-specific params) | `runId`, status `queued` |
| `get_run` | `run_id` | Full result with latency (incl. mean/stdev), throughput, error rate |
| `get_run_progress` | `run_id` | Status, real progress %, current stage, elapsed/remaining estimate |
| `server_status` | — | Queue depth, currently running (+ progress), uptime, adapters |
| `list_runs` | `tool?`, `test_name?`, `limit?` | Recent completed/failed/cancelled runs, newest first |
| `tag_baseline` | `run_id` | Tags a completed run as its scenario's baseline |
| `compare_runs` | `run_id`, `baseline_run_id?` | Per-metric deltas vs baseline with regression/improvement flags |
| `cancel_run` | `run_id` | Cancels a queued/pending run or kills a running one |

Adapters whose output schema has no parser yet (Gatling, vegeta, wrk) are not
registered — they appear in `catalog.yaml` but not in `list_adapters` until a
parser is implemented.

## How it works

1. An agent sends `tools/call` (run_test) with a tool name, profile, and parameter overrides via the MCP protocol
2. The official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) `Server` dispatches the call to `BoehmToolHandlers.runTest`, which constructs a `TestPlan`
3. `Orchestrator` validates the plan and queues the run in SQLite (serial execution — one at a time for clean measurements)
4. `CatalogAdapter` loads the profile template from `profiles/<tool>/`, applies overrides via JSON path (JSON-based tools) or env vars (script-based tools), shell-execs the CLI command, and captures output
5. Results are parsed into a normalized `RunResult` and persisted in SQLite
6. The agent polls `get_run` or `get_run_progress` until complete

The MCP SDK handles the protocol layer: `initialize` handshake, `capabilities`, `tools/list` discovery, `notifications/initialized`, content envelopes, and JSON-RPC parsing — so Boehm is fully discoverable and standards-compliant for any MCP client (Claude Code, opencode, etc.).

## Test catalog

`catalog.yaml` defines every test profile across all supported tools:

| Tool | Profiles | Status |
|------|----------|--------|
| Tulip | `http-get`, `demo` | Implemented |
| k6 | `http-get` | Implemented |
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
