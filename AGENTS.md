# Agent Guide — Boehm

**Performance Testing AI Engineer.** MCP server + agents + skills with history tracking, baseline comparison, statistical analysis, and git PR investigation. Named after Barry Boehm.

## Implemented Capabilities

- **Run history** (`list_runs`) — recent finished runs, filterable by tool/scenario
- **Per-scenario baselines** (`tag_baseline`) — any completed run can be tagged as its scenario's baseline
- **Baseline comparison** (`compare_runs`) — direction-aware deltas (throughput/latency/error rate) with regression/improvement flags beyond 10%
- **Cancellation** (`cancel_run`) — queued runs are flipped to `cancelled`; running runs have their subprocess tree killed
- **Real progress** (`get_run_progress`) — estimated from `started_at` and the plan's warmup/duration, not hardcoded values
- **Timeout validation** — plans whose `timeout_sec` cannot cover `duration_sec + warmup_sec + slack` are rejected at submit time
- **Measurement integrity** — resubmitting a scenario name updates its stored plan, but each run snapshots the exact plan it executed (`runs.test_plan`); ISO-8601 timestamps everywhere; Store access serialized behind one lock

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/architecture.md` | System architecture with Mermaid diagrams, component map, data flows |
| `catalog.yaml` | Tool and profile index (the source of truth for adapters) |
| `README.md` | Setup, usage, project layout, and MCP tool reference |

Read `docs/architecture.md` before changing behaviour.

## Implemented Adapters

| Tool | Parser | Profile(s) | Integration Test | Docker |
|------|--------|------------|------------------|--------|
| Tulip | `TulipParser` (JSON) | `http-get`, `demo` | `TulipIntegrationTest` | No (bare metal only) |
| JMeter | `JMeterParser` (JTL CSV) | `http-get` | `JMeterIntegrationTest` | Yes (`alpine/jmeter:latest` or `justb4/jmeter:5.6.3` fallback) |
| k6 | `K6Parser` (NDJSON) | `http-get` | `K6IntegrationTest` | Yes (`grafana/k6:latest` fallback) |
| Gatling | `GatlingParser` (JSON) | `http-get` | `GatlingIntegrationTest` | Yes (`denvazh/gatling:3.9.5` fallback) |

## Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin / JVM |
| Build | Gradle (single-module) |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk:0.15.0` |
| Persistence | SQLite (`~/.boehm/boehm.db`) |
| Testing | JUnit 5 |

## Toolchain

- **Java, Kotlin, Gradle** managed via **sdkman**. Use `sdk install <candidate> <version>`, `sdk use`, `sdk default`.
- Versions pinned in `.sdkmanrc` for `sdk env`.
- Gradle wrapper always used (`./gradlew`), not system `gradle`.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Boehm MCP Server                                             │
│                                                               │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  Agent       │  │  JSON-RPC    │  │  Catalog + Profiles  │ │
│  │  (opencode,  │──│  McpHandler  │──│  catalog.yaml        │ │
│  │   etc)       │  │  Handlers    │  │  profiles/<tool>/*   │ │
│  └──────────────┘  └──────┬───────┘  └──────────┬───────────┘ │
│                           │                      │            │
│                    ┌──────▼──────────────────────▼──────────┐  │
│                    │  Orchestrator + Scheduler                │  │
│                    │  → serial run queue                     │  │
│                    │  → CatalogAdapter (generic CLI exec)    │  │
│                    │  → SQLite persist (Store)               │  │
│                    └─────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### Rules

1. **Catalog-driven** — all tools and profiles defined in `catalog.yaml`. No hardcoded adapters. `CatalogAdapter` is the single generic implementation driven by catalog data.
2. **JSON configs vs script configs** — `.json`/`.jsonc` templates get overrides applied via JSON path. `.js`/`.jmx`/`.scala` templates are copied as-is with overrides passed via env vars or CLI flags (`-e`, `-J`, `-D`).
3. **Skill-driven methodology** — engineering knowledge lives in opencode skill files, not in the MCP server.
4. **Everything preserved** — every run stored; any run can be tagged as a baseline.
5. **Run isolation** — scheduler serializes all test execution to prevent overlapping runs from adding noise.
6. **Local-first** — no cloud dependencies; SQLite single-file database.
7. **All adapters equal** — every tool is invoked via CLI shell exec (`bash -c`). No in-process coupling. The CLI output format is the stable contract.

## Build and Run

```bash
./gradlew build                       # Compile + test + detekt
./gradlew test                        # Run tests
./gradlew detekt                      # Static analysis only (config/detekt/detekt.yml)
./gradlew test jacocoTestReport       # Tests + coverage report (HTML at build/reports/jacoco/)
./gradlew run --args="--token=<your-token>"   # Start MCP server
```

## Quality Standards

- **Static analysis: detekt** (`dev.detekt` 2.0.0-alpha.6, matching Kotlin 2.4.10). `./gradlew build` fails on new violations. Tuned norms in `config/detekt/detekt.yml`: 140-col limit, no wildcard imports in `src/main`, early-return style allowed (`ReturnCount` max 6), JDBC indices / test fixtures excluded. Deliberate boundaries (branchy output parsers, catch-all around external processes that must never kill the queue) carry `@Suppress` with a reason comment — prefer that over weakening rules globally.

- **Code coverage: minimum 80%** (instructions). Run `./gradlew test jacocoTestReport` and check the HTML report. Coverage below 80% should be improved before committing.
- All new adapter profiles must include a parser integration test.
- All new MCP tools must include a handler test.

## Design Status

Design finalized. See `docs/architecture.md` for current implementation.

## What Not To Do

- No HTML reports (use Markdown + Mermaid)
- No cloud services or network dependencies for storage
- No cloud database — local SQLite only
- No report rendering in the MCP server itself
