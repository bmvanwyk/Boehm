#!/bin/bash
# Run Boehm MCP server (builds first if needed)
set -e

BOEHM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$BOEHM_DIR/build/install/boehm/bin/boehm"

if [ ! -f "$BIN" ]; then
    echo "Building Boehm..."
    (cd "$BOEHM_DIR" && ./gradlew installDist -q)
fi

exec "$BIN" "$@"
