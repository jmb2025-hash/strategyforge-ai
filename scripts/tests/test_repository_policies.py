#!/usr/bin/env python3
"""Repository policy checks (run in CI: python3 -m unittest discover -s scripts/tests).

NFR-011: toolchains, dependencies and container images are pinned for reproducible builds.
NFR-012: core tests never call live provider APIs.
MS-20 / FR-113: no brokerage SDKs or live-trading dependencies exist in any build.
"""
import os
import re
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def files(base, suffixes):
    for dirpath, dirnames, names in os.walk(os.path.join(ROOT, base)):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", "node_modules")]
        for n in names:
            if n.endswith(suffixes):
                yield os.path.join(dirpath, n)


BUILD_FILES = [
    "backend/build.gradle.kts",
    "backend/gradle/libs.versions.toml",
    "android/build.gradle.kts",
    "android/app/build.gradle.kts",
    "android/core/build.gradle.kts",
    "android/engine/build.gradle.kts",
    "android/engine/settings.gradle.kts",
    "android/gradle/libs.versions.toml",
]

LIVE_HOSTS = [
    "api.anthropic.com",
    "api.openai.com",
    "generativelanguage.googleapis.com",
    "openrouter.ai",
    "api.twelvedata.com",
    "fcm.googleapis.com",
    "oauth2.googleapis.com",
]


class PinningTest(unittest.TestCase):
    """NFR-011"""

    def test_nfr011_no_dynamic_dependency_versions(self):
        dynamic = re.compile(r"""["'=]\s*[^"'\s]*(\+|latest\.(release|integration)|-SNAPSHOT)["']""")
        for rel in BUILD_FILES:
            for n, line in enumerate(read(rel).splitlines(), 1):
                self.assertIsNone(dynamic.search(line), f"{rel}:{n} uses a dynamic version: {line.strip()}")

    def test_nfr011_version_catalogs_use_exact_versions(self):
        for rel in ("backend/gradle/libs.versions.toml", "android/gradle/libs.versions.toml"):
            section = None
            for line in read(rel).splitlines():
                s = line.strip()
                if s.startswith("["):
                    section = s
                    continue
                if section == "[versions]" and "=" in s and not s.startswith("#"):
                    value = s.split("=", 1)[1].strip().strip('"')
                    self.assertRegex(value, r"^\d+(\.\d+)*([.-][A-Za-z0-9.-]+)?$", f"{rel}: '{s}' is not an exact version")

    def test_nfr011_gradle_wrappers_pin_one_version(self):
        for rel in ("backend/gradle/wrapper/gradle-wrapper.properties", "android/gradle/wrapper/gradle-wrapper.properties"):
            self.assertIn("gradle-8.14.3-bin.zip", read(rel), rel)

    def test_nfr011_container_images_pinned_by_digest(self):
        for rel in ("docker-compose.yml", "backend/Dockerfile"):
            for line in read(rel).splitlines():
                m = re.match(r"\s*(?:image:|FROM)\s+(\S+)", line)
                if not m or m.group(1).startswith("strategyforge-"):
                    continue
                self.assertIn("@sha256:", m.group(1), f"{rel}: image not pinned by digest: {m.group(1)}")

    def test_nfr011_scan_tools_pinned(self):
        script = read("scripts/security-scan.sh")
        for image in re.findall(r'_IMAGE="([^"]+)"', script):
            self.assertRegex(image, r":v?\d+\.\d+\.\d+$", f"scanner image not pinned: {image}")

    def test_nfr011_ci_uses_pinned_toolchain(self):
        ci = read(".github/workflows/ci.yml")
        self.assertIn("java-version: '21'", ci.replace('"', "'"))
        for action in re.findall(r"uses:\s*(\S+)", ci):
            self.assertRegex(action, r"@v\d+$|@[0-9a-f]{40}$", f"action not pinned: {action}")


class LiveApiIsolationTest(unittest.TestCase):
    """NFR-012"""

    def test_nfr012_tests_never_target_live_provider_hosts(self):
        for base in ("backend/src/test", "android/core/src/test", "android/app/src/test"):
            for path in files(base, (".kt", ".json", ".yml", ".properties")):
                with open(path, encoding="utf-8", errors="replace") as f:
                    text = f.read()
                for host in LIVE_HOSTS:
                    self.assertNotIn(host, text, f"{os.path.relpath(path, ROOT)} references live host {host}")

    def test_nfr012_test_profile_has_no_provider_credentials(self):
        cfg = read("backend/src/test/resources/application-test.yml")
        for key in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY", "MARKET_API_KEY", "FCM_CREDENTIALS_PATH"):
            self.assertNotIn(key, cfg)
        self.assertNotRegex(cfg, r"(?i)market-provider:\s*(?!REPLAY)\w+")

    def test_nfr012_ci_sets_no_provider_secrets_for_tests(self):
        ci = read(".github/workflows/ci.yml")
        for key in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY", "MARKET_API_KEY"):
            self.assertNotIn(key, ci)


class NoBrokerageDependencyTest(unittest.TestCase):
    """MS-20 / FR-113"""

    def test_ms20_no_brokerage_or_exchange_trading_sdks(self):
        banned = re.compile(r"(?i)(alpaca|interactive.?brokers|ibkr|tradier|ccxt|binance|coinbase-advanced|robinhood|questrade|oanda|etrade|schwab)")
        for rel in BUILD_FILES:
            for n, line in enumerate(read(rel).splitlines(), 1):
                self.assertIsNone(banned.search(line), f"{rel}:{n} references a trading SDK: {line.strip()}")


class ReleaseGateTest(unittest.TestCase):
    """RG-01, RG-02, RG-04, RG-06 structural gates (execution evidence comes from CI)."""

    def _rows(self, name):
        import csv

        with open(os.path.join(ROOT, "docs", name), newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def test_rg01_every_requirement_is_mapped(self):
        reqs = {r["id"] for r in self._rows("requirements.csv")}
        trace = {r["id"] for r in self._rows("traceability.csv")}
        self.assertEqual(reqs, trace)
        self.assertEqual(len(reqs), 114)

    def test_rg02_every_mandatory_scenario_has_passing_test_evidence(self):
        rows = [r for r in self._rows("traceability.csv") if r["id"].startswith("MS-")]
        self.assertEqual(len(rows), 21)
        for r in rows:
            self.assertEqual(r["status"], "Passed", r["id"])
            self.assertTrue(r["test_ref"].strip(), r["id"])

    def test_rg04_container_runs_unprivileged_and_is_not_exposed(self):
        docker = read("backend/Dockerfile")
        self.assertRegex(docker, r"(?m)^USER strategyforge$")
        self.assertIn("HEALTHCHECK", docker)
        compose = read("docker-compose.yml")
        self.assertIn('"127.0.0.1:8080:8080"', compose)
        self.assertIn("read_only: true", compose)
        self.assertIn("no-new-privileges:true", compose)
        self.assertIn('REAL_MONEY_TRADING_ENABLED: "false"', compose)

    def test_rg06_security_findings_are_not_suppressed_without_justification(self):
        entries = [l.strip() for l in read(".trivyignore").splitlines() if l.strip() and not l.strip().startswith("#")]
        for e in entries:
            self.assertRegex(e, r"^CVE-\d{4}-\d+\s+#\s*.+review\s+\d{4}-\d{2}-\d{2}", f"unjustified suppression: {e}")
        self.assertIn("--exit-code 1", read("scripts/security-scan.sh"))

    def test_rg06_no_secrets_in_env_template(self):
        for line in read(".env.example").splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                if re.search(r"(?i)(KEY|SECRET|PASSWORD|TOKEN)", key):
                    self.assertEqual(value.strip(), "", f".env.example must not carry a value for {key}")


if __name__ == "__main__":
    unittest.main()
