# SPEC: k6 Adapter Parser + Integration Test

## Goal
Add a k6 output parser that reads k6 NDJSON output (`--out json`) and produces a normalized `RunResult`. Register it in `Main.kt`. Write unit tests with a fixture and an integration test that runs real k6 against httpbin.org.

## Context
- Project: `~/hobbies/Boehm` (Kotlin/JVM MCP server for performance testing)
- Java 21 is installed via SDKMAN: `source ~/.sdkman/bin/sdkman-init.sh`
- Build: `./gradlew build` (compile + test)
- Existing pattern: see `src/main/kotlin/io/boehm/adapters/tulip/TulipParser.kt` and `src/main/kotlin/io/boehm/adapters/jmeter/JMeterParser.kt`
- Parser registry: `src/main/kotlin/io/boehm/Main.kt` — parsers map keyed by `output.schema` from `catalog.yaml`
- The catalog entry for k6 already exists in `catalog.yaml` with `schema: k6-jsonl`, `format: jsonl`

## k6 Output Format
k6 with `--out json=<file>` produces NDJSON (one JSON object per line). Each line has a `type` field:
- `Metric` lines contain metric data: `{"type":"Metric","data":{"value":...,"tags":{...}},"metric":"http_req_duration"}`
- At the end, k6 outputs summary lines with aggregated metrics including percentiles
- Key metrics: `http_req_duration` (latency), `http_reqs` (total count), `iterations`, `vus`, `data_received`, `data_sent`
- `http_req_duration` percentiles appear in the summary output

**IMPORTANT:** k6's NDJSON output is a stream of individual metric data points, NOT a pre-aggregated summary. You must:
1. Collect all `http_req_duration` data points from `Metric` lines
2. Compute percentiles yourself (nearest-rank method, same as JMeterParser)
3. Count total requests from `http_reqs` metric lines
4. Compute error rate from HTTP status codes (check for `status >= 400` in tags) or from `http_req_failed` metric
5. Determine duration from timestamps or the `iterations` metric

Alternatively, k6 also outputs a summary at the end of the run to stdout (not the JSON file). The JSON file contains raw metrics. Focus on parsing the JSON file.

## Tasks

### 1. Create `src/main/kotlin/io/boehm/adapters/k6/K6Parser.kt`
- Object with `fun parse(rawJsonl: String): RunResult` method
- Parse NDJSON line by line
- Extract `http_req_duration` values from `Metric` lines where `metric == "http_req_duration"`
- Compute p50, p90, p95, p99 via nearest-rank percentile (see `JMeterParser.percentile()`)
- Count total requests from `http_reqs` metric lines (each line value=1, sum them) OR from `iterations`
- Compute error rate: count `http_req_failed` metric lines where value > 0, or count HTTP status codes >= 400 in tags
- Compute throughput: totalRequests / durationSec
- Duration: derive from `iteration_duration` sum or from timestamps in the data
- Return `RunResult(tool="k6", testName=..., status="completed", summary=Summary(...), metadata=...)`

### 2. Create test fixture `src/test/fixtures/k6-sample-output.jsonl`
- Realistic k6 NDJSON output with ~20-30 lines of metric data
- Include `http_req_duration` data points with varying values
- Include `http_reqs` lines (each value=1)
- Include at least one failed request (status >= 400)
- Include `iterations`, `vus` metrics
- Include timestamps for duration calculation

### 3. Create `src/test/kotlin/io/boehm/adapter/K6ParserTest.kt`
- Test: parse valid k6 NDJSON returns correct RunResult
- Test: latency percentiles are correct and ordered (p99 >= p95 >= p90 >= p50)
- Test: error rate computed correctly
- Test: throughput is positive
- Test: missing http_req_duration throws
- Test: empty input throws
- Test: handles malformed lines gracefully (skip non-Metric lines)
- Test: metadata contains sample counts

### 4. Register parser in `src/main/kotlin/io/boehm/Main.kt`
- Add import: `import io.boehm.adapters.k6.K6Parser`
- Add to parsers map: `"k6-jsonl" to { raw -> K6Parser.parse(raw) }`

### 5. Create `src/test/kotlin/io/boehm/integration/K6IntegrationTest.kt`
- Follow the pattern in `JMeterIntegrationTest.kt`
- Use `assumeTrue` to skip if k6 binary not found
- Run k6 against httpbin.org with the existing `profiles/k6/http-get.js` template
- Assert: completed status, >0 requests, error rate reasonable, p50 > 0

### 6. Install k6 if needed
- Check: `which k6` — if not found, install via: `sudo gpg -k && sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive.gpg --keyserver hkp://keyserver.ubuntu.com --recv-keys C5AD4A3A && echo "deb [signed-by=/usr/share/keyrings/k6-archive.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt update && sudo apt install k6`
- Or try: `go install go.k6.io/k6@latest` (if Go is available)

### 7. Update docs
- `README.md`: change k6 status from "Designed (parser needed)" to "Implemented", update project structure, fixtures, integration tests, prerequisites, quick start
- `AGENTS.md`: add k6 to the Implemented Adapters table
- `docs/architecture.md`: add K6Parser to Mermaid diagram, data flow, and module layout

## Constraints
- Do NOT modify the existing `catalog.yaml` k6 entry (it's already correct)
- Do NOT modify the existing `profiles/k6/http-get.js` template
- Follow the code style of existing parsers (object, `parse()` method, private helpers)
- Keep coverage above 80% (currently 84%)
- All existing tests must still pass

## Verification
- `./gradlew build` must succeed
- `./gradlew test --tests "io.boehm.adapter.K6ParserTest"` must pass
- `./gradlew test --tests "io.boehm.integration.K6IntegrationTest"` must pass (if k6 installed)
- `./gradlew test` (full suite) must pass
- Commit with: `git add -A && git commit -m "Add k6 adapter parser: NDJSON → RunResult"`
