# Agent Guide — Boehm

**Performance Testing AI Engineer.** MCP server + agents + skills with history tracking, baseline comparison, statistical analysis, and git PR investigation. Named after Barry Boehm.

## Implemented Capabilities

- **Run history** (`list_runs`) — recent finished runs, filterable by tool/scenario
- **Per-scenario baselines** (`tag_baseline`) — any completed run can be tagged as its scenario's baseline
- **Baseline comparison** (`compare_runs`) — direction-aware deltas (throughput/latency/error rate) with regression/improvement flags beyond 10%
- **Cancellation** (`cancel_run`) — queued runs are flipped to `cancelled`; running runs have their subprocess tree killed
- **Real progress** (`get_run_progress`) — estimated from `started_at` and the plan's warmup/duration, not hardcoded values
- **Timeout validation** — plans whose `timeout_sec` cannot cover `duration_sec + warmup_sec + slack` are rejected at submit time
- **Measurement integrity** — resubmitting a scenario name updates its stored plan; ISO-8601 timestamps everywhere; Store access serialized behind one lock

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/architecture.md` | System architecture with Mermaid diagrams, component map, data flows |
| `catalog.yaml` | Tool and profile index (the source of truth for adapters) |
| `README.md` | Setup, usage, project layout, and MCP tool reference |

Read `docs/architecture.md` before changing behaviour.

## Implemented Adapters

| Tool | Parser | Profile(s) | Integration Test |
|------|--------|------------|------------------|
| Tulip | `TulipParser` (JSON) | `http-get`, `demo` | `TulipIntegrationTest` |
| JMeter | `JMeterParser` (JTL CSV) | `http-get` | `JMeterIntegrationTest` |
| k6 | `K6Parser` (NDJSON) | `http-get` | `K6IntegrationTest` |

Designed but not yet implemented: Gatling, vegeta, wrk.

## Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin / JVM |
| Build | Gradle (multi-module) |
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
./gradlew build                       # Compile + test
./gradlew test                        # Run tests
./gradlew test jacocoTestReport       # Tests + coverage report (HTML at build/reports/jacoco/)
./gradlew run --args="--token=<your-token>"   # Start MCP server
```

## Quality Standards

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
