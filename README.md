# Boehm

**Performance Testing AI Engineer — an MCP server that runs performance tests across multiple tools and analyzes results.**

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm). The server speaks JSON-RPC 2.0 over stdio via the official MCP Kotlin SDK and persists every run in SQLite.

## Prerequisites

- **Java 25** — managed via sdkman (`.sdkmanrc` pins `java=25.0.2-open`, `kotlin=2.4.10`): `sdk env`
- **Tulip CLI** (for Tulip adapter) — cloned to `~/git/Tulip`, built with `./gradlew build`
- **JMeter** (for JMeter adapter) — bare metal: install [Apache JMeter 5.6.3](https://jmeter.apache.org/download_jmeter.cgi), ensure `jmeter` on `PATH` and `JAVA_HOME` set; or Docker: `docker pull justb4/jmeter:5.6.3` (integration tests use Docker fallback if `jmeter` not found)
- **k6** (for k6 adapter) — bare metal: `brew install k6`, the [official deb repo](https://grafana.com/docs/k6/latest/set-up/install-k6/), or `go install go.k6.io/k6@latest`; or Docker: `docker pull grafana/k6:latest`
- **Gatling** (for Gatling adapter) — bare metal: `sdk install gatling` or `brew install gatling`; or Docker: `docker pull denvazh/gatling:3.9.5` (integration tests use Docker fallback)

All four tools have parsers and are registered at runtime. Add new tools in `catalog.yaml`; any profile whose `output.schema` lacks a parser is skipped at startup with a stderr note.

## Quick start

```bash
# Build (compile + test + coverage)
./gradlew build

# Unit tests only (fast, no external tools needed)
./gradlew test

# Coverage report (HTML at build/reports/jacoco/test/html/)
./gradlew test jacocoTestReport

# Integration tests (each skips gracefully if binary/Docker image is absent)
./gradlew test --tests "io.boehm.integration.TulipIntegrationTest"
./gradlew test --tests "io.boehm.integration.JMeterIntegrationTest"  # tries ~/.sdkman/apache-jmeter-5.6.3, then docker justb4/jmeter:5.6.3
./gradlew test --tests "io.boehm.integration.K6IntegrationTest"        # tries k6 on PATH/~/go/bin/k6, then docker grafana/k6:latest
./gradlew test --tests "io.boehm.integration.GatlingIntegrationTest"   # tries gatling on PATH/sdkman, then docker denvazh/gatling:3.9.5

# Start the MCP server (stdio); token required
./gradlew run --args="--token=boehm_sk_$(openssl rand -hex 16)"
# or BOEHM_TOKEN=... ./gradlew run
# DB and catalog paths can be overridden:
# BOEHM_DB_PATH=/tmp/boehm.db BOEHM_CATALOG_PATH=/path/to/catalog.yaml ./gradlew run --args="--token=..."
```

## Project structure

```
Boehm/
├── build.gradle.kts              # Kotlin 2.4.10 / JVM 25, Gson, SQLite, SnakeYAML, MCP SDK 0.15.0
├── settings.gradle.kts
├── catalog.yaml                  # Tool index: profiles, overrides, parsers
├── profiles/
│   ├── tulip/http-get.jsonc, demo.jsonc
│   ├── k6/http-get.js
│   ├── jmeter/http-get.jmx
│   └── gatling/http-get.scala
├── src/main/kotlin/io/boehm/
│   ├── Main.kt                   # stdio entry point, catalog loader, parser registry, 9 tool registrations
│   ├── catalog/
│   │   ├── CatalogModels.kt      # Catalog, ToolDef, ProfileDef, OutputDef, OverrideDef
│   │   ├── CatalogLoader.kt      # SnakeYAML → typed models
│   │   ├── AdapterBuilder.kt     # buildAdapters(): only profiles with a parser
│   │   └── CatalogAdapter.kt     # PerfToolAdapter: template + overrides + bash -c + timeout + parse
│   ├── core/
│   │   ├── BoehmToolHandlers.kt  # 9 MCP tool handlers + real progress estimation
│   │   ├── Orchestrator.kt       # SubmitResult / CancelResult, scenario UPSERT, scheduler lifecycle
│   │   ├── Scheduler.kt          # serial queue, failInterruptedRuns, cancellation
│   │   ├── Comparator.kt         # direction-aware baseline comparison (10% threshold)
│   │   └── Store.kt              # SQLite (adapters, scenarios, runs, baselines), synchronized
│   ├── model/
│   │   ├── TestPlan.kt           # type, profile, targetUrl, rate/duration/warmup/timeout, parameters
│   │   ├── RunResult.kt          # RunResult, Summary, Latency (incl. meanMs/stdevMs)
│   │   ├── ProgressEvent.kt      # progress / server status types
│   │   └── Stats.kt              # mean + population stdev
│   └── adapters/
│       ├── PerfToolAdapter.kt    # interface (name, profile, validate, run)
│       ├── tulip/TulipParser.kt  # Tulip JSON → RunResult
│       ├── jmeter/JMeterParser.kt # JTL CSV → RunResult
│       ├── k6/K6Parser.kt        # NDJSON → RunResult
│       └── gatling/GatlingParser.kt # Gatling global_stats.json → RunResult
└── src/test/kotlin/io/boehm/
    ├── adapter/                  # TulipAdapterTest, TulipParserTest, JMeterParserTest, K6ParserTest, GatlingParserTest
    ├── catalog/                  # AdapterBuilderTest, CatalogLoaderTest, CatalogAdapterValidationTest
    ├── core/                     # BoehmServerTest, ComparatorTest, OrchestratorTest, SchedulerTest, StoreTest
    ├── model/                    # RunResultTest, ProgressEventTest
    ├── fixtures/                 # mock-tulip.sh, tulip-sample-output.json, jmeter-sample-output.csv, k6-sample-output.jsonl, gatling-sample-output.json
    └── integration/              # TulipIntegrationTest, JMeterIntegrationTest, K6IntegrationTest, GatlingIntegrationTest
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
| `list_adapters` | — | Registered adapters (only profiles with parsers) + supported types |
| `run_test` | `tool`, `test_name`, `test_plan` (`type`, `profile`, `target_url`, `rate_per_sec`, `duration_sec`, `warmup_sec`, `timeout_sec` + tool-specific params) | `runId`, `status: queued` (or `-32000`/`-32001` with `validation_errors`) |
| `get_run` | `run_id` | Full `RunResult` with latency (incl. `meanMs`/`stdevMs`), throughput, error rate, `rawOutputPath`, `metadata` |
| `get_run_progress` | `run_id` | `status`, `progressPct`, `currentStage` (`warmup`/`measuring`/`completed`), `elapsedSec`/`estimatedRemainingSec`, `rollingSummary`; derived from `started_at` + `warmupSec`/`durationSec` |
| `server_status` | — | `status` (`idle`/`running`), `queueDepth`, `currentlyRunning` (with real progress), `queuedRuns`, `uptimeSec`, `adapters` |
| `list_runs` | `tool?`, `test_name?`, `limit?` (default 20, max 100) | Recent `completed`/`failed`/`cancelled` runs, newest first, with summaries |
| `tag_baseline` | `run_id` | Tags a `completed` run with a summary as its scenario's baseline (`UPSERT` on `scenario_id`) |
| `compare_runs` | `run_id`, `baseline_run_id?` | Per-metric `deltaPct` + `verdict` (`regression`/`improvement`/`unchanged`, >10% threshold, direction-aware), plus `regressions`/`improvements` lists |
| `cancel_run` | `run_id` | Cancels `pending`/`queued` (flipped to `cancelled`) or `running` (subprocess tree killed, recorded as `cancelled`) |

Profiles whose `output.schema` has no parser are skipped at startup with a stderr note and never appear in `list_adapters`.

## How it works

1. Agent sends `tools/call` (`run_test`) with `tool`, `test_name`, and `test_plan` overrides.
2. `BoehmToolHandlers.runTest` builds a `TestPlan`; `Orchestrator.submitRun` looks up `tool:profile`, validates (including `timeoutSec >= durationSec + warmupSec + 10`), upserts the scenario, inserts a `queued` run, and ensures the `Scheduler` is running.
3. `Scheduler` (single daemon thread, polls every 500 ms) dequeues the oldest `pending`/`queued` run, marks `running` (`started_at` set), and calls `CatalogAdapter.run(plan, onProcessStart)`.
4. `CatalogAdapter` loads the profile template from `profiles/<tool>/`, applies overrides via JSON path (JSON/JSONC) or copies as-is (script templates, overrides via `{{target_url}}`/`{{rate_per_sec}}` etc. in the command), sanitizes values, substitutes `{{config_file}}`/`{{output_file}}`, executes `bash -c` with `timeoutSec` enforcement, and parses the output via the schema's parser (`tulip-results`/`k6-jsonl`/`jmeter-csv`).
5. `Scheduler` persists the normalized `RunResult` (or `failed`/`cancelled`) in SQLite; `Store` access is serialized behind one lock and timestamps are ISO-8601.
6. Agent polls `get_run` or `get_run_progress` (computed from `started_at` + plan) until `completed`/`failed`/`cancelled`.

## Test catalog

`catalog.yaml` declares every profile across all supported tools. `AdapterBuilder` registers only those with a parser:

| Tool | Profiles | Status |
|------|----------|--------|
| Tulip | `http-get`, `demo` | Implemented (`TulipParser`, `tulip-results`) |
| k6 | `http-get` | Implemented (`K6Parser`, `k6-jsonl`) |
| JMeter | `http-get` | Implemented (`JMeterParser`, `jmeter-csv`) |
| Gatling | `http-get` | Implemented (`GatlingParser`, `gatling-stats`) |

Each profile declares a static config template, overridable params, and the output `path`/`format`/`schema` so `CatalogAdapter` knows where to find and how to parse results.

## Defining new tests (`catalog.yaml`)

`catalog.yaml` is the source of truth — no hardcoded adapters. `Main.kt` calls `buildAdapters(catalog, baseDir, parsers)` once at startup (override paths with `BOEHM_CATALOG_PATH` / `BOEHM_DB_PATH`). Every `run_test` must name an existing `tool` + `profile`; `test_plan` fields are mapped to the profile's `overrides`.

### Structure

```yaml
version: 1
tools:
  k6:
    description: "Load testing tool by Grafana (JavaScript)"
    install: "brew install k6"          # display only
    run:
      command: >                        # bash -c template; available vars: {{config_file}}, {{output_file}}, {{target_url}}, {{rate_per_sec}}, ...
        k6 run -e TARGET_URL={{target_url}} -e RATE_PER_SEC={{rate_per_sec}} -e DURATION_SEC={{duration_sec}} --out json={{output_file}} {{config_file}}
    profiles:
      http-get:
        description: "HTTP GET with configurable rate and duration"
        config: profiles/k6/http-get.js # path relative to repo root; null for CLI-only not used
        output:
          path: "{{output_file}}"       # "{{output_file}}" (temp file), "{{config.actions.output_filename}}" (embedded in JSON config), or "stdout"
          format: jsonl                 # json | jsonl | csv | text (for docs)
          schema: k6-jsonl              # must match a parser key in Main.kt (tulip-results, jmeter-csv, k6-jsonl, gatling-stats) or profile is skipped
        overrides:
          target_url: { default: "https://httpbin.org/get" }          # path: null for script templates
          rate_per_sec: { default: 50 }
          duration_sec: { default: 30 }
```

* `CatalogModels.kt` defines `Catalog`/`ToolDef`/`ProfileDef`/`OutputDef`/`OverrideDef`; `CatalogLoader.kt` parses the YAML via SnakeYAML.
* `CatalogAdapter.kt` is the single `PerfToolAdapter` implementation: JSON/JSONC templates get overrides applied via JSON path (`profile.overrides[].path`, e.g. `actions.user_params.url` for Tulip) with type coercion; script templates (`.js`, `.jmx`, `.scala`) are copied as-is and overrides flow only through `{{var}}` substitution in `run.command` (`CatalogAdapter.kt:339`).
* `AdapterBuilder.kt` hides profiles whose `output.schema` has no parser — they appear in `catalog.yaml` but never in `list_adapters` (stderr note).

### Adding a new profile (reusing an existing parser)

1. Create the template, e.g. `profiles/k6/http-post.js` reading `__ENV.TARGET_URL` like `profiles/k6/http-get.js:17`.
2. Add to `catalog.yaml`:

```yaml
k6:
  profiles:
    http-post:
      description: "HTTP POST with JSON body"
      config: profiles/k6/http-post.js
      output: { path: "{{output_file}}", format: jsonl, schema: k6-jsonl }  # reuse k6-jsonl parser
      overrides:
        target_url: { default: "https://httpbin.org/post" }
        rate_per_sec: { default: 20 }
        duration_sec: { default: 30 }
```

3. Restart the server (`./gradlew run --args="--token=..."`). Call with:

```json
{ "tool": "k6", "test_name": "my-post", "test_plan": { "profile": "http-post", "target_url": "https://myhost/api", "rate_per_sec": 50 } }
```

Overrides not listed are warned (`CatalogAdapter.kt:212` — unknown overrides produce `validation_errors`); `target_url` is validated as `http`/`https` URI with query strings allowed and shell-metachar screening (`CatalogAdapter.kt:215,222`); numeric overrides must be integers; `timeout_sec` must satisfy `>= duration_sec + warmup_sec + 10` (`CatalogAdapter.kt:62`). Docker fallback is test-only — production `catalog.yaml` remains bare-metal (see `src/test/kotlin/io/boehm/integration/*:14`).

### Adding a new tool

Add a new top-level entry under `tools:` with `run.command` and at least one profile whose `output.schema` matches an existing parser (to avoid writing a parser) or add a new parser object (`TulipParser.kt`/`K6Parser.kt`/`JMeterParser.kt`/`GatlingParser.kt`) and register it in the `parsers` map in `Main.kt:50`.

## Quality and docs

- Coverage: minimum 80% instructions (excludes `MainKt`). Run `./gradlew test jacocoTestReport` and check `build/reports/jacoco/test/html/`.
- Architecture details, state model, scheduler, and adapter contract: `docs/architecture.md`.
- Catalog and profile conventions: `catalog.yaml` and `profiles/<tool>/`.
- Agent guide: `AGENTS.md`.
