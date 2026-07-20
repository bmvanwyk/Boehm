# Boehm

**Performance Testing AI Engineer — an AI specialist that designs, runs, analyzes, and investigates performance tests across any tool.**

Named after [Barry Boehm](https://en.wikipedia.org/wiki/Barry_Boehm), known for the COCOMO model and spiral model of software development.

Boehm is not just a test runner. It's an engineer with:

- **Institutional memory** — every run preserved, any run taggable as a baseline
- **Investigation skills** — detects regressions, bisects commits, traces problems to their source
- **Tool fluency** — speaks Tulip, k6, JMeter, Gatling, and more through a unified MCP interface

## Who it's for

- **Performance engineers** reviewing PRs — "Did this change introduce a regression? Which commit?"
- **SREs** running CI gates — deterministic pass/fail on perf tests, no false alarms
- **Developers** sanity-checking local changes — "Does this refactor affect throughput?"
- **QA leads** standardizing perf testing across teams — one way to define and track tests

## What it does

- **Runs tests** against any supported tool through a single MCP interface
- **Tracks baselines** — mark a run as baseline, compare any future run against it
- **Investigates PRs** — checks out base vs head, runs tests, compares, bisects commits
- **Analyzes deeply** — distribution comparison, trend detection, anomaly flagging (Phases 6-7)
- **Reports via skills** — reusable agent workflows produce Markdown + Mermaid reports

## Phases

| Phase | What |
|---|---|
| 1 | MCP server scaffold + first tool adapter |
| 2 | Baseline store, comparison, regression thresholds |
| 3 | Evals and correctness testing |
| 4 | More tool adapters |
| 5 | Skills and agent integration |
| 6 | Git PR integration + commit bisect |
| 7 | Advanced statistical analysis |

## Status

Design finalized. Ready for implementation planning.

See `docs/superpowers/specs/2026-07-20-boehm-design.md` for the full spec.
