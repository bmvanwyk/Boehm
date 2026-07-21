#!/bin/bash
# Mock Tulip CLI that returns sample JSON output for testing
cat << 'EOF'
{
  "tool": "tulip",
  "testName": "http-get",
  "status": "completed",
  "duration": 5,
  "totalRequests": 500,
  "throughputPerSec": 100.0,
  "errorRatePct": 0.0,
  "latencyMs": {
    "min": 1.5,
    "p50": 7.2,
    "p90": 20.1,
    "p95": 32.0,
    "p99": 60.5,
    "max": 180.0
  },
  "rawOutputPath": "/tmp/tulip-mock-output.json",
  "metadata": {}
}
EOF
