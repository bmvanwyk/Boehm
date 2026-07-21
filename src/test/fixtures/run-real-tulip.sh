#!/bin/bash
# Wrapper: runs real Tulip CLI from Tulip repo.
# Adapter calls: <this> --config <configfile>
set -e

TULIP_DIR="/home/bvwyk/git/Tulip"

if [ ! -d "$TULIP_DIR" ]; then
    echo "Tulip repo not found at $TULIP_DIR" >&2
    exit 1
fi

cd "$TULIP_DIR"

# Build if needed
if [ ! -f "tulip-main/build/classes/kotlin/main/org/example/app/AppKt.class" ]; then
    source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null
    sdk env 2>/dev/null
    ./gradlew -q :tulip-main:classes 2>&1
fi

./gradlew -q --stop 2>/dev/null || true
exec ./gradlew -q :tulip-main:run --args="$*"
