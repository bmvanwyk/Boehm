#!/bin/bash
# Boehm smoke test — validates MCP server responds to all 5 tools
# Uses coproc for persistent bidirectional stdio.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BOEHM_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOKEN="boehm_sk_smoke_test"
PASS=0
FAIL=0
BUILD=true

usage() { echo "Usage: $0 [--skip-build] [--token x]"; exit 1; }
while [[ $# -gt 0 ]]; do
    case "$1" in --skip-build) BUILD=false;; --token) TOKEN="$2"; shift;; *) usage;; esac
    shift
done

if $BUILD; then
    echo "=== Building Boehm ==="
    (cd "$BOEHM_DIR" && ./gradlew installDist -q)
fi

BIN="$BOEHM_DIR/build/install/boehm/bin/boehm"
[ -f "$BIN" ] || { echo "ERROR: Binary not found at $BIN"; exit 1; }

echo "=== Smoke Test: Boehm MCP Server ==="
echo ""

# Start server in background coproc
exec 3>&1  # save stdout
coproc BOEHM { "$BIN" --token="$TOKEN" 2>/dev/null; }

send() {
    echo "$1" >&"${BOEHM[1]}"
}

recv() {
    local timeout="${2:-3}"
    read -r -t "$timeout" line <&"${BOEHM[0]}" && echo "$line" || echo "(timeout)"
}

check() {
    local name="$1" expected="$2" actual="$3"
    if echo "$actual" | grep -qF "$expected"; then
        echo "  PASS: $name"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $name (expected '$expected')"
        FAIL=$((FAIL + 1))
        echo "    got: $actual"
    fi
}

# ── Phase 1: Init ─────────────────────────────────────────────────
echo "--- Phase 1: Initialize ---"
send '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"},"auth_token":"'"$TOKEN"'"}}'
init_resp=$(recv)
check "initialize succeeds" '"result"' "$init_resp"

# ── Phase 2: list_adapters ────────────────────────────────────────
echo ""
echo "--- Phase 2: list_adapters ---"
send '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_adapters","arguments":{}}}'
list_resp=$(recv)
check "list_adapters returns result" '"result"' "$list_resp"
check "list_adapters has tulip" 'tulip' "$list_resp"

# ── Phase 3: run_test ─────────────────────────────────────────────
echo ""
echo "--- Phase 3: run_test ---"
send '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"tulip","test_name":"smoke-demo","test_plan":{"type":"http","profile":"demo","duration_sec":3}}}}'
run_resp=$(recv)
check "run_test returns result" '"result"' "$run_resp"
check "run_test returns runId" 'runId' "$run_resp"

RUN_ID=$(echo "$run_resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['runId'])" 2>/dev/null || echo "")

# ── Phase 4: server_status ────────────────────────────────────────
echo ""
echo "--- Phase 4: server_status ---"
send '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"server_status","arguments":{}}}'
status_resp=$(recv)
check "server_status returns result" '"result"' "$status_resp"
check "server_status has status field" '"status"' "$status_resp"

# ── Phase 5: get_run_progress (existing run) ──────────────────────
echo ""
echo "--- Phase 5: get_run_progress ---"
if [ -n "$RUN_ID" ]; then
    send '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_run_progress","arguments":{"run_id":"'"$RUN_ID"'"}}}'
    progress_resp=$(recv)
    check "get_run_progress returns result" '"result"' "$progress_resp"
    check "get_run_progress has status" '"status"' "$progress_resp"
else
    echo "  SKIP: no run ID available"
fi

# ── Phase 6: get_run (existing run) ───────────────────────────────
echo ""
echo "--- Phase 6: get_run ---"
if [ -n "$RUN_ID" ]; then
    send '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_run","arguments":{"run_id":"'"$RUN_ID"'"}}}'
    get_resp=$(recv)
    check "get_run returns result" '"result"' "$get_resp"
    check "get_run has runId" 'runId' "$get_resp"
else
    echo "  SKIP: no run ID available"
fi

# ── Phase 7: Error paths ──────────────────────────────────────────
echo ""
echo "--- Phase 7: Error paths ---"
send '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_run","arguments":{"run_id":"nonexistent"}}}'
notfound_resp=$(recv)
check "get_run nonexistent returns error" '"error"' "$notfound_resp"

send '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"run_test","arguments":{"tool":"unknown","test_name":"x","test_plan":{"type":"http"}}}}'
unknown_resp=$(recv)
check "run_test unknown tool returns error" '"error"' "$unknown_resp"

send '{"jsonrpc":"2.0","id":9,"method":"ping","params":{}}'
ping_resp=$(recv)
check "ping returns result" '"result"' "$ping_resp"

# ── Phase 8: Poll for completion (wait a bit for scheduler) ───────
echo ""
echo "--- Phase 8: Poll run completion ---"
if [ -n "$RUN_ID" ]; then
    for i in 1 2 3; do
        sleep 3
        send '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"get_run","arguments":{"run_id":"'"$RUN_ID"'"}}}'
        poll_resp=$(recv 3)
        if echo "$poll_resp" | grep -q '"completed"'; then
            check "run completed (iteration $i)" 'completed' "$poll_resp"
            break
        elif echo "$poll_resp" | grep -q '"failed"'; then
            echo "  INFO: run failed (expected if Tulip CLI unavailable)"
            check "run failed gracefully" 'failed' "$poll_resp"
            break
        elif [ "$i" -eq 3 ]; then
            echo "  INFO: run still queued/running after 9s (scheduler may be slow)"
            check "run exists" 'result' "$poll_resp"
        fi
    done
fi

# ── Phase 9: Auth failure ─────────────────────────────────────────
echo ""
echo "--- Phase 9: Auth failure ---"

# Start a second instance with different token
coproc BAD_BOEHM { "$BIN" --token="$TOKEN" 2>/dev/null; }
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"},"auth_token":"wrong-token"}}' >&"${BAD_BOEHM[1]}"
read -t 3 bad_init_resp <&"${BAD_BOEHM[0]}" || bad_init_resp="(timeout)"
check "wrong token returns 32005" '32005' "$bad_init_resp"
check "wrong token fails auth" 'Authentication failed' "$bad_init_resp"

# ── Phase 10: Missing initialize ──────────────────────────────────
echo ""
echo "--- Phase 10: Missing initialize ---"
coproc NOINIT { "$BIN" --token="$TOKEN" 2>/dev/null; }
echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_adapters","arguments":{}}}' >&"${NOINIT[1]}"
read -t 3 noinit_resp <&"${NOINIT[0]}" || noinit_resp="(timeout)"
check "tool before init returns 32006" '32006' "$noinit_resp"

# ── Cleanup ───────────────────────────────────────────────────────
exec 3>&-
kill %1 %2 %3 2>/dev/null || true
wait 2>/dev/null || true

# ── Summary ───────────────────────────────────────────────────────
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then echo "FAIL: Some checks did not pass."; exit 1; fi
echo "OK: Boehm MCP server is working."
echo ""
echo "Next steps:"
echo "  - Run against opencode (configured in .opencode/opencode.jsonc)"
echo "  - For manual usage: scripts/run.sh --token=your-token"
