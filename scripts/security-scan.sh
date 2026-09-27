#!/usr/bin/env bash
# Security scans (section 15): secrets (gitleaks), dependency/container vulnerabilities
# and misconfiguration (Trivy). Reports go to build/security/. Fails on any
# unresolved CRITICAL or HIGH finding (release gate RG-06).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/security"
mkdir -p "$OUT"
GITLEAKS_IMAGE="zricethezav/gitleaks:v8.28.0"
TRIVY_IMAGE="aquasec/trivy:0.67.2"
fail=0

echo "==> gitleaks (secrets in working tree and git history)"
if docker run --rm -v "$ROOT:/repo" "$GITLEAKS_IMAGE" git /repo --redact \
     --config /repo/.gitleaks.toml --report-format json --report-path /repo/build/security/gitleaks.json; then
  echo "gitleaks: no secrets found"
else
  echo "gitleaks: findings present (see build/security/gitleaks.json)"; fail=1
fi

TRIVY_CACHE="${TRIVY_CACHE_DIR:-$HOME/.cache/trivy}"
mkdir -p "$TRIVY_CACHE"
trivy() { docker run --rm -v "$ROOT:/repo" -v "$TRIVY_CACHE:/root/.cache/trivy" -v /var/run/docker.sock:/var/run/docker.sock "$TRIVY_IMAGE" "$@"; }

echo "==> trivy filesystem: dependency vulnerabilities (Gradle lockfiles/SBOMs) and IaC misconfiguration"
for sbom in backend/build/reports/sbom/strategyforge-backend-sbom.json android/app/build/reports/sbom/strategyforge-android-sbom.json; do
  if [[ -f "$ROOT/$sbom" ]]; then
    name="$(basename "$sbom" .json)"
    trivy sbom --quiet --severity HIGH,CRITICAL --ignorefile /repo/.trivyignore --format json --output "/repo/build/security/trivy-$name.json" "/repo/$sbom"
    trivy sbom --quiet --severity HIGH,CRITICAL --ignorefile /repo/.trivyignore --exit-code 1 "/repo/$sbom" || fail=1
  else
    echo "SBOM $sbom not found; build it first (scripts/verify.sh)"; fail=1
  fi
done
trivy config --quiet --severity HIGH,CRITICAL --format json --output /repo/build/security/trivy-config.json /repo
trivy config --quiet --severity HIGH,CRITICAL --exit-code 1 /repo || fail=1

if docker image inspect strategyforge-backend:1.0.0 >/dev/null 2>&1; then
  echo "==> trivy container image"
  trivy image --quiet --severity HIGH,CRITICAL --ignorefile /repo/.trivyignore --format json --output /repo/build/security/trivy-image.json strategyforge-backend:1.0.0
  trivy image --quiet --severity HIGH,CRITICAL --ignorefile /repo/.trivyignore --exit-code 1 strategyforge-backend:1.0.0 || fail=1
fi

if [[ $fail -ne 0 ]]; then echo "Security scan FAILED"; exit 1; fi
echo "Security scan passed: no unresolved HIGH/CRITICAL findings."
