# StrategyForge AI

Private, single-owner Android application for researching, validating, backtesting and
operating stock and crypto strategies with **simulated money only**. Everything runs on the
phone (D-027): live crypto data from Coinbase, optional US stock data with a free Twelve Data
key, AI research with the owner's own provider key (Google Gemini free tier by default), and
paper trading that keeps running in the background.

**Install the app:** see [`docs/ANDROID.md`](docs/ANDROID.md). The latest signed APK is published
as the `phone-latest` pre-release on the Releases page.

> **Paper trading only.** Real-money trading does not exist in Version 1 and cannot be enabled
> through the UI, API, configuration or strategy files.

The authoritative specification is [`docs/StrategyForge_AI_Master_Build_Package_v1.0.docx`](docs/StrategyForge_AI_Master_Build_Package_v1.0.docx).
Read it first.

## Repository layout

| Path | Contents |
|---|---|
| `android/` | Android app (`app`, Jetpack Compose), the on-device trading `engine` and the pure-Kotlin `core` data layer |
| `backend/` | Version 1 server (Kotlin + Spring Boot). Kept for reference; the phone app does not use it (D-027) |
| `contracts/` | OpenAPI 3.1 contract generated from and verified against the backend |
| `fixtures/replay/` | Deterministic synthetic replay market data (generated, checksummed) |
| `deploy/` | Optional TLS proxy configuration |
| `scripts/` | Verification, security scans, fixtures, traceability, secrets, backup helpers |
| `docs/` | Traceability, decisions, architecture, runbook, security, acceptance |

## Start here

- Release notes, artifacts, limitations and owner acceptance: [`docs/RELEASE.md`](docs/RELEASE.md)
- Deploy and operate: [`docs/RUNBOOK.md`](docs/RUNBOOK.md) · Backup and restore: [`docs/BACKUP_RESTORE.md`](docs/BACKUP_RESTORE.md)
- Architecture: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · Security: [`docs/SECURITY.md`](docs/SECURITY.md) · Android: [`docs/ANDROID.md`](docs/ANDROID.md)

- Toolchain and verification commands: [`docs/TOOLCHAIN.md`](docs/TOOLCHAIN.md)
- Requirement traceability: [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md)
- Decisions and resolved conflicts: [`docs/DECISION_LOG.md`](docs/DECISION_LOG.md)
- Work package log: [`docs/WORK_PACKAGES.md`](docs/WORK_PACKAGES.md)
