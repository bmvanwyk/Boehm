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

**Building Phase 1** — MCP server scaffold with Tulip adapter. Bearer token auth, serial run queue, live progress reporting.

See [`docs/superpowers/specs/2026-07-20-boehm-phase-1.md`](docs/superpowers/specs/2026-07-20-boehm-phase-1.md) for the active spec.

Full product vision at [`docs/superpowers/specs/2026-07-20-boehm-vision.md`](docs/superpowers/specs/2026-07-20-boehm-vision.md).
