#!/bin/bash
# Self-assessment gate check for itch.io integration
# Usage: bash tools/gate-check.sh
# Run before every commit on feat/itchio branch

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

PASS=0
WARN=0
FAIL=0

gate_pass() { echo "✓ PASS: $1"; PASS=$((PASS + 1)); }
gate_warn() { echo "⚠ WARN: $1"; WARN=$((WARN + 1)); }
gate_fail() { echo "✗ FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== GATE 1: Compile ==="
if ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5 | grep -q 'BUILD SUCCESSFUL'; then
    gate_pass "Compile"
else
    gate_fail "Compile — build did not succeed"
fi

echo ""
echo "=== GATE 2: GameSource exhaustiveness ==="
MISSING=""
for f in $(grep -rl 'when.*gameSource\|when.*GameSource' app/src/main/java --include='*.kt' 2>/dev/null || true); do
    if ! grep -q 'GameSource.ITCH\|\.ITCH' "$f"; then
        MISSING="$MISSING $f"
    fi
done
if [ -z "$MISSING" ]; then
    gate_pass "All GameSource when-blocks include ITCH"
else
    gate_fail "Missing ITCH in:$MISSING"
fi

echo ""
echo "=== GATE 3: Security (API key not in URL) ==="
if grep -rn 'api_key=' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' 2>/dev/null | grep -v '^\s*//' | grep -q .; then
    gate_warn "API key found in URL query string"
    grep -rn 'api_key=' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' | grep -v '^\s*//'
else
    gate_pass "No API key in URL"
fi

echo ""
echo "=== GATE 4: No runBlocking in itch service ==="
if grep -rn 'runBlocking' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' 2>/dev/null | grep -q .; then
    gate_warn "runBlocking found (acceptable pre-Phase 3)"
    grep -rn 'runBlocking' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'
else
    gate_pass "No runBlocking"
fi

echo ""
echo "=== GATE 5: No new OkHttpClient instantiation ==="
if grep -rn 'OkHttpClient()' app/src/main/java/app/gamenative/service/itch/ --include='*.kt' 2>/dev/null | grep -q .; then
    gate_warn "New OkHttpClient() found (acceptable pre-Phase 3)"
    grep -rn 'OkHttpClient()' app/src/main/java/app/gamenative/service/itch/ --include='*.kt'
else
    gate_pass "No new OkHttpClient()"
fi

echo ""
echo "=== GATE 6: No debug/temp artifacts in staged changes ==="
if git diff --cached 2>/dev/null | grep -iE 'TODO.*TEMP|HACK|println\(|System\.out\.print' | grep -q .; then
    gate_warn "Debug artifacts in staged changes"
else
    gate_pass "No debug artifacts"
fi

echo ""
echo "========================================"
echo "Results: $PASS passed, $WARN warnings, $FAIL failures"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    echo "BLOCKED: Fix failures before committing."
    exit 1
else
    echo "READY: Safe to commit (review any warnings)."
    exit 0
fi
