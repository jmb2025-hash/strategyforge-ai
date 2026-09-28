# StrategyForge AI 1.0.0: release candidate

**Paper trading only.** Version 1 contains no real-money trading, brokerage connection or
order routing, and none can be enabled (FR-113; verified by MS-20/RG-09).

| Item | Value |
|---|---|
| Release candidate code | branch `claude/strategyforge-v1-delivery-vgf1ah`, commit `fd9ef1f` (later commits change documentation only) |
| Backend | `strategyforge-backend` 1.0.0: Kotlin 2.2.21, Spring Boot 3.5.16, Java 21, PostgreSQL 16 |
| Android | `app.strategyforge.android` 1.0.0: minSdk 29, targetSdk 35 |
| Traceability | 114/114 requirements mapped (RG-01); status in [TRACEABILITY.md](TRACEABILITY.md) |

## 1. Artifacts

| Artifact | Where | Checksum / identity |
|---|---|---|
| Release APK | CI artifact `android-outputs` → `app/build/outputs/apk/release/app-release.apk` | SHA-256 in `app/build/outputs/apk/SHA256SUMS` (same artifact) and in the CI log (§6) |
| Debug APK | `android-outputs` → `app/build/outputs/apk/debug/app-debug.apk` | `SHA256SUMS` |
| APK signing certificate | `android-outputs` → `app/build/outputs/apk/release/SIGNING.txt` (`apksigner verify --print-certs`) | CI ephemeral key unless owner secrets are set (D-023) |
| Backend jar | `backend/build/libs/strategyforge-backend.jar` from `./gradlew bootJar` (CI builds it and the container image on every push) | reproducible from the pinned toolchain |
| Backend image | `docker compose build` → `strategyforge-backend:1.0.0` | base image pinned by digest |
| SBOMs (CycloneDX) | `backend/build/reports/sbom/strategyforge-backend-sbom.json`, `android/app/build/reports/sbom/strategyforge-android-sbom.json` (CI artifacts) | scanned by Trivy on every run |
| OpenAPI 3.1 contract | `contracts/openapi.json` (committed; CI fails on drift) | validated by `ContractIT` (RG-05) |
| Security reports | CI artifact `security-reports` (`gitleaks.json`, `trivy-*.json`) | [SECURITY.md](SECURITY.md) |
| Test reports | CI artifacts `backend-reports` (`reports/tests`, `test-results`) and `android-outputs` (`app/build/reports`, `core/build/reports`) | §4 |

To install the APK, download `android-outputs` from the CI run for the release commit, check
`sha256sum -c SHA256SUMS` (run in `app/build/outputs/apk/`), and sideload it ([ANDROID.md](ANDROID.md)). For in-place updates, configure
a permanent signing key first; see the ANDROID.md "Release signing" section.

## 2. Backend build and deploy

```bash
cp .env.example .env && scripts/generate-secrets.sh    # fill .env, chmod 600
(cd backend && ./gradlew bootJar)
docker compose up -d --build
curl -s http://127.0.0.1:8080/actuator/health            # {"status":"UP"}
```

For the full procedure (private network and TLS, owner bootstrap, providers, replay mode,
monitoring, incidents, upgrades), see [RUNBOOK.md](RUNBOOK.md). For backup and restore, see
[BACKUP_RESTORE.md](BACKUP_RESTORE.md).

## 3. Migration status

Flyway applies these at startup. The same set is applied by every integration test and by the restore command.

| Version | Content |
|---|---|
| V1 | Baseline: hash-chained append-only audit, idempotency records, health events |
| V2 | Owner, recovery codes, devices, sessions, action tokens, settings, providers and capabilities |
| V3 | Instruments, candles, quotes, snapshots, market data status, FX, corporate actions, watchlists, alerts, replay state |
| V4 | Notification inbox and push deliveries |
| V5 | Paper portfolios, double-entry ledger, lots, orders, executions, reconciliation, risk evaluations |
| V6 | Strategies, versions, validations, imports, status history |
| V7 | Backtests and trades |
| V8 | Risk profiles, emergency state, activations, evaluations, signals, recommendations and decisions |
| V9 | AI budget, research sessions, runs, sources, edits and compilations |

Schema version after startup: **9**. Migrations are forward-only. Downgrading is done by restoring a backup with the previous build.

## 4. Test results (release candidate)

| Suite | Tests | Result | Where |
|---|---|---|---|
| Backend unit, integration, contract, security and E2E (JUnit 5, Testcontainers PostgreSQL 16) | 148 | all pass | CI `backend` job; `backend/build/reports/tests/test` |
| Replay end-to-end, twice from clean databases (RG-07) | included above (`ReplayEndToEndIT`) | identical digests | |
| Android core (JVM) | 16 | all pass | CI `android` job; `android/core/build/reports/tests` |
| Android app: Robolectric Compose UI | 7 | all pass | CI `android` job; `android/app/build/reports/tests` |
| Android lint | debug variant | no errors | `android/app/build/reports/lint-results-debug.html` |
| Repository policies (pinning, isolation, release gates) | 15 | all pass | CI `repository` job |
| Static analysis | spotless (ktlint), detekt | clean | CI |

All 21 mandatory scenarios (MS-01 to MS-21) and all release gates have passing, referenced tests; see the traceability matrix.

## 5. Release gates

| Gate | Evidence |
|---|---|
| RG-01: 100% traceability | `scripts/traceability.py --release` and a policy test |
| RG-02: all mandatory scenarios pass | MS rows Passed with test references; CI runs them on every push |
| RG-03: Android debug and release builds | CI `android` job: `assembleDebug`, `assembleRelease` (signed) |
| RG-04: backend build, migrations, containers | CI `backend` job: tests on migrated DBs, `bootJar`, `docker build`; container policy test |
| RG-05: OpenAPI validation | `ContractIT` |
| RG-06: no unresolved critical or high finding | CI `security` job: gitleaks and Trivy (SBOMs and IaC) |
| RG-07: replay E2E twice from clean databases | `ReplayEndToEndIT` |
| RG-08: backup/restore drill | `BackupRestoreIT`, manual drill in BACKUP_RESTORE.md |
| RG-09: real-money prohibition | `RealMoneyProhibitionIT`, `RealMoneyPolicyTest`, `ArchitectureTest`, policy tests |

## 6. CI evidence for the release commit

CI run **36425386927** (`fd9ef1f`, 2026-09-28): the `repository`, `backend`, `android` and `security` jobs all succeeded.

| Item | Value |
|---|---|
| `app-release.apk` SHA-256 | `ef52152b0d4e8d204857cf68af05f26c6dce6c3f2ea8943f531eb6bc39610a14` |
| `app-debug.apk` SHA-256 | `ddb1929fc1bc912467fc3bed347063e8ef4424361f1ea472b195f2d14e555c7d` |
| Release signing certificate | `CN=StrategyForge CI ephemeral`, SHA-256 `4a0733092bd324fd9685f6e80f79d10b7bc74944a8e966bd749ce74be4f7300b` (APK Signature Scheme v2) |
| `android-outputs` artifact | id 10971259212, zip SHA-256 `bd79474b330138f596afe95283104a90174ea6bf21ff837520c331fcf97e99b9` |
| Security | gitleaks: no leaks (history). Trivy: 0 HIGH/CRITICAL in both SBOMs and in IaC |
| Contract | `contracts/openapi.json` unchanged by the build (`git diff --exit-code`) |
| Container | `docker build` of the backend image succeeded |

CI artifacts expire under GitHub's retention policy (90 days by default). To keep the
APK, download it now, or rebuild from the tag. A rebuild signed with a permanent owner key
produces a different checksum; record it in the same way.

## 7. Known limitations

1. **Market data.** Replay data is synthetic and labelled as such (D-006). The Twelve Data adapter is tested against recorded responses only; live use requires the owner's key and a provider test (MS-09 covers explicit capability states).
2. **Equity calendar.** It covers 2023–2027 (D-008). Outside that range equities are blocked (fail closed) until the table is extended.
3. **AI retrieval.** Web retrieval with citations is implemented for Anthropic only. Other providers never claim sources (D-022). Provider pricing must be configured by the owner (D-010).
4. **Crypto shorting.** It is not available; the strategy validator rejects `allowShort` for crypto. Equity shorting is simulated with borrow fees and margin (D-017).
5. **Android build.** The app module is built on CI only in the delivery environment (D-002, D-003).
6. **APK signing.** Without owner secrets, CI signs with an ephemeral key, so a later APK cannot update an earlier one in place (D-023).
7. **Restore is host-only.** Backups can be created and verified from the app (More → Backups), but restoring is deliberately an offline command on the backend host (BACKUP_RESTORE.md).
8. **Master key.** `MASTER_ENCRYPTION_KEY` has no in-place rotation (RUNBOOK §9).
9. **Restore memory.** Restore loads each table into memory, which is sized for single-owner data (D-024).
10. **Pinning.** GitHub Actions are pinned to major tags. The Gradle wrapper checksum is not pinned (SECURITY.md).
11. **Rate limiting.** There is no request rate limiting beyond login lockout. Deploy on a private network only.
12. **Nature of results.** Reports and comparisons describe simulated results. They make no claim of causation or of future performance (FR-104).

## 8. Owner acceptance checklist

Complete these on your own host and phone. Tick each item and keep the notes with the release.

**Install and access**
- [ ] `.env` created with generated secrets, `chmod 600`, and the master key stored in a password manager.
- [ ] `docker compose up -d --build` completed; `docker compose ps` shows the backend healthy.
- [ ] The phone reaches the backend over Tailscale or the TLS profile. The app rejects a plain `http://` address in the release build.
- [ ] The APK checksum matches `SHA256SUMS`, and the APK is installed.
- [ ] Owner created with the bootstrap token, recovery codes stored offline, and a second bootstrap attempt refused (MS-01).
- [ ] Sign out and sign in again. A wrong password five times locks the account temporarily.
- [ ] More → Security: turn on two-factor authentication with your authenticator app, create new recovery codes and store them offline, and check that the sessions and devices lists show this phone.
- [ ] More → AI budget: lower a limit (applies immediately), then raise it (asks for your password).

**Paper trading on replay data**
- [ ] The Home dashboard shows "Replay/demo mode" and loads in under 2 seconds on reopen (NFR-005). Offline, it shows labelled cached data.
- [ ] Create a portfolio. Place a market buy and a limit order, advance replay (RUNBOOK §5), see the fill, fees and P/L with arrows and spoken descriptions (TalkBack) (MS-03, MS-04, NFR-009).
- [ ] Import a valid strategy and an invalid one. See validation errors and Manual Review Required for unknown fields (MS-05, MS-07).
- [ ] Run a backtest and make the strategy Paper Eligible. Activate it in Recommendation Mode. Accept one recommendation, decline one, and let one expire (MS-10).
- [ ] Activate Autonomous Mode (disclosure plus re-authentication), confirm autonomous fills, change a risk setting, and confirm it pauses for re-authorization (MS-11).
- [ ] Emergency controls: Pause all, Prevent new positions, and Close all simulated positions (typed confirmation) (MS-15).
- [ ] More → Reports shows costs, attribution, benchmarks and the no-causation disclaimer (FR-103, FR-104).

**Optional providers**
- [ ] AI provider configured and tested. A research run shows provenance and "UNVERIFIED AI OUTPUT" until approved. The budget refuses a run over the limit (MS-18).
- [ ] FCM configured. A push arrives with generic lock-screen text, and the inbox has the full item (FR-101, FR-102, MS-16).
- [ ] Twelve Data key configured and tested. Capabilities are shown as detected, and a missing capability is shown as unsupported (MS-09).

**Operations**
- [ ] More → Diagnostics shows all components. Audit chain OK, reconciliation OK, backup present.
- [ ] More → Backups → Back up now creates a backup (asks for your password), Verify reports every table hash matching, and `verify-backup` on the host also reports OK.
- [ ] Restore drill into a new database per BACKUP_RESTORE.md succeeds, then roll back to the original (MS-21, RG-08).
- [ ] More → Exports: save the ledger as CSV, open it in a spreadsheet, and check that the TOTAL rows match the report (MS-19).
- [ ] Setting `REAL_MONEY_TRADING_ENABLED=true` in `.env` prevents the backend from starting. Restore it to `false` afterwards (MS-20).
- [ ] The limitations in §7 are read and accepted.

Accepted by: ____________________  Date: ____________
