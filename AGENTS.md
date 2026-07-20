# Agent Guide — Boehm

**Performance Testing AI Engineer.** MCP server + agents + skills with history tracking, baseline comparison, statistical analysis, and git PR investigation. Named after Barry Boehm.

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/superpowers/specs/2026-07-20-boehm-design.md` | Architecture, MCP tools, adapter interface, result schema, future vision |

Read the design spec before changing behaviour.

## Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin / JVM |
| Build | Gradle (multi-module) |
| MCP SDK | TBD |
| Persistence | Local JSON files for baseline store |
| Testing | JUnit 5 |

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  Performance Testing AI Engineer                         │
│                                                          │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────┐ │
│  │  Agent       │  │  Skills      │  │  MCP Server     │ │
│  │  (Claude     │  │  (method-    │  │  (instruments)  │ │
│  │   Code, etc) │  │   ology)     │  │                 │ │
│  └──────┬───────┘  └──────────────┘  └────────┬────────┘ │
│         │                                      │          │
│  ┌──────▼──────────────────────────────────────▼────────┐ │
│  │  Baseline Store (lab notebook)                       │ │
│  │  → every run preserved, baselines tagged             │ │
│  │  → git integration for PR investigation              │ │
│  │  → statistical analysis engine                       │ │
│  └─────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### Rules

1. **Adapter contract** — every tool adapter implements `PerfToolAdapter` interface producing normalized `RunResult`.
2. **Skill-driven methodology** — engineering knowledge lives in opencode skill files, not in the MCP server.
3. **Everything preserved** — every run stored; any run can be tagged as a baseline.
4. **Investigation-first** — git integration identifies which commit and code change caused a regression.
5. **Local-first** — no cloud dependencies, no database; JSON file persistence.
6. **Extensibility first** — Tulip adapter first, but adapter interface designed for any CLI tool.

## Build and Run

```bash
./gradlew build          # Compile + test
./gradlew test           # Run tests
```

## Design Status

Design finalized. See implementation plans for current phase.

## What Not To Do

- No HTML reports (use Markdown + Mermaid)
- No cloud services or network dependencies for storage
- No embedded database — JSON files only
- No report rendering in the MCP server itself
