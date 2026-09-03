# Changelog

## Unreleased
- Per-run plan snapshots (`runs.test_plan`); `completed_at` now set for `cancelled`; raw-output retention (newest 50 per tool)
- `adapters` keyed by `(name, profile)`; `list_adapters` returns `profile`; `types`/`tool_versions` catalog-driven
- `warmup_sec` is Tulip-only in timeout/progress math; Gatling `p90` excluded from `compare_runs` verdicts
- SQLite `foreign_keys=ON` + `busy_timeout`; ISO `strftime` DB defaults; Scheduler restarts after `stop()`
- detekt (`dev.detekt` 2.0.0-alpha.6) enforced via `./gradlew build`; named constants, chained causes, extracted helpers

## 0.1.0 — 2026-08-31
- Added Gatling (`gatling-stats`) — `profiles/gatling/http-get.scala`, `GatlingParser`, `GatlingIntegrationTest` (Docker `docker.io/denvazh/gatling:3.9.5` fallback)
- Removed `vegeta`/`wrk` profiles (no parser, never registered) — use `catalog.yaml` history; `run_test` with `tool=vegeta/wrk` now returns `-32000` with `removed: vegeta, wrk — see CHANGELOG.md`
- Docker fallback now test-only; production `catalog.yaml` remains bare-metal (see `src/test/kotlin/io/boehm/integration/*:14`)
- Fixed `CatalogAdapter` `{{output_file}}` handling for Gatling (`outputFile` vs `resolvedOutputPath`)
- Fixed Gatling `baseUrl`/`path` split to avoid double-path

## 0.0.1 — Initial
- Tulip, JMeter, k6 adapters; 9 MCP tools; SQLite store; Scheduler serial queue
