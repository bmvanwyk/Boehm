# Agent Guide — Boehm

MCP server + agents + skills for performance testing automation across multiple tools (Tulip, k6, JMeter, Gatling, etc.). Named after Barry Boehm.

## Key Documents

| Document | Purpose |
|----------|---------|
| `docs/superpowers/specs/YYYY-MM-DD-boehm-design.md` | Architecture, MCP tools, adapter interface, result schema |

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
AI Agent  →  MCP protocol  →  perf-mcp-server (Kotlin)
                                ├── Core Layer (orchestrator, baseline store, MCP handler)
                                └── Adapter Layer
                                    ├── Tulip adapter (in-process JVM)
                                    ├── k6 adapter (shell exec)
                                    └── ...
```

### Rules

1. **Adapter contract** — every tool adapter implements `PerfToolAdapter` interface producing normalized `RunResult`.
2. **Skill-driven reporting** — all report generation lives in opencode skill files (Markdown + Mermaid), not in the MCP server.
3. **Baseline tagging** — any run can be marked as a baseline; comparison always against the tagged baseline.
4. **Local-first** — no cloud dependencies, no database; JSON file persistence.
5. **Extensibility first** — Tulip adapter first, but adapter interface designed for any CLI tool.

## Build and Run

```bash
./gradlew build          # Compile + test
./gradlew test           # Run tests
```

## Design Status

Design in progress — see brainstorming session with project owner. Do not implement until design doc is finalized and approved.

## What Not To Do

- No HTML reports (use Markdown + Mermaid)
- No cloud services or network dependencies for storage
- No embedded database — JSON files only
- No report rendering in the MCP server itself
