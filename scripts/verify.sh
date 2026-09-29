#!/usr/bin/env bash
# Local verification harness. Mirrors .github/workflows/ci.yml.
# Usage: scripts/verify.sh [backend|android|security|all]   (default: all)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-all}"

gradle_cmd() {
  # Prefer the wrapper; fall back to an installed Gradle of the same pinned version
  # when the wrapper distribution cannot be downloaded (restricted networks).
  local dir="$1"
  if [[ -n "${SF_GRADLE:-}" ]]; then echo "$SF_GRADLE"; return; fi
  if [[ -x "$dir/gradlew" ]] && "$dir/gradlew" -p "$dir" --version >/dev/null 2>&1; then echo "$dir/gradlew"; return; fi
  echo "gradle"
}

step() { printf '\n==> %s\n' "$*"; }

common() {
  step "Replay fixtures are reproducible"
  python3 "$ROOT/scripts/generate_replay_fixtures.py" --check
  step "Requirement traceability"
  python3 "$ROOT/scripts/traceability.py"
  step "Repository policies"
  python3 -m unittest discover -s "$ROOT/scripts/tests"
  step "Docker Compose configuration"
  (cd "$ROOT" && DATABASE_USER=x DATABASE_PASSWORD=x docker compose --env-file /dev/null config -q)
}

backend() {
  local G; G="$(gradle_cmd "$ROOT/backend")"
  step "Backend: format, static analysis, tests, build, SBOM"
  (cd "$ROOT/backend" && $G --no-daemon spotlessCheck detekt test bootJar cyclonedxBom)
  step "Backend: container image"
  (cd "$ROOT/backend" && docker build -q -t strategyforge-backend:1.0.0 . >/dev/null && echo "image built")
}

android() {
  if [[ ! -d "$ROOT/android" ]]; then echo "android/ not present"; return; fi
  local G; G="$(gradle_cmd "$ROOT/android")"
  step "Android: lint, unit/Compose tests, debug and release builds, SBOM"
  (cd "$ROOT/android" && $G --no-daemon spotlessCheck :core:test :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:cyclonedxBom)
}

security() {
  step "Security scans"
  "$ROOT/scripts/security-scan.sh"
}

case "$TARGET" in
  backend) common; backend ;;
  android) android ;;
  security) security ;;
  all) common; backend; android; security ;;
  *) echo "unknown target $TARGET"; exit 2 ;;
esac
step "Verification complete: $TARGET"
