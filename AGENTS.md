# Agent Guide — Boehm

**Performance Testing AI Engineer.** MCP server + agents + skills with history tracking, baseline comparison, statistical analysis, and git PR investigation. Named after Barry Boehm.

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/architecture.md` | System architecture with Mermaid diagrams, component map, data flows, ADRs |
| `docs/superpowers/specs/2026-07-20-boehm-phase-1.md` | Active Phase 1 spec: MCP tools, adapter interface, schema, acceptance criteria |
| `docs/superpowers/specs/2026-07-20-boehm-vision.md` | Full product vision (Phases 2-7 reference) |

Read the design spec before changing behaviour.

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

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Boehm MCP Server                                             │
│                                                               │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  Agent       │  │  JSON-RPC    │  │  Catalog + Profiles  │ │
│  │  (opencode,  │──│  McpHandler  │──│  catalog.yaml        │ │
│  │   etc)       │  │  AuthHandler │  │  profiles/<tool>/*   │ │
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

Design finalized. See implementation plans for current phase.

## What Not To Do

- No HTML reports (use Markdown + Mermaid)
- No cloud services or network dependencies for storage
- No cloud database — local SQLite only
- No report rendering in the MCP server itself
