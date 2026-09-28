#!/usr/bin/env bash
# Runs selected backend test classes and prints a compact summary per class.
# Usage: scripts/run-tests.sh ClassName [ClassName...]
set -uo pipefail
cd "$(dirname "$0")/../backend"
args=()
for t in "$@"; do args+=(--tests "*$t"); done
timeout 1800 gradle test "${args[@]}" -q 2>&1 | grep -E "^e:|BUILD" || true
for t in "$@"; do
  python3 ../scripts/test-summary.py build/test-results/test/*"$t".xml | cut -c1-700
  grep -oE "$t.kt:[0-9]+" build/test-results/test/*"$t".xml | sort -u | head -4
done
