# Decision Log

Format per master package Appendix B. Priority rule for conflicts:
safety boundary > final product decisions > requirements > contracts > architecture > build plan.
When a conflict is unresolved, the safest reversible option is selected.

---

## D-001 Backend toolchain line

- **Date:** 2026-09-27
- **Context:** Spring Boot 4.1, Kotlin 2.4 and Testcontainers 2 are available. Spring Boot 3.5.x is still receiving patch releases on Maven Central (3.5.16).
- **Decision:** Kotlin 2.2.21, Spring Boot 3.5.16 (Jackson 2, Spring Framework 6.2), Java 21, Gradle 8.14.3, Flyway (Boot-managed 11.x), PostgreSQL 16.15, Testcontainers 2.0.5.
- **Alternatives:** Spring Boot 4.x (Jackson 3, new modular starters) — rejected for Version 1 because of higher migration risk with no functional benefit.
- **Consequences:** Stable, well-understood APIs. A move to Boot 4 must occur before 3.5 patch support ends.
- **Requirements affected:** NFR-011, RG-04, RG-06.
- **Reversal plan:** Upgrade Boot/Kotlin in one change set guarded by the full test suite; Jackson 3 migration is isolated to `common/json`.

## D-002 Restricted build network in the delivery environment

- **Date:** 2026-09-27
- **Context:** The container used to build this delivery blocks `downloads.gradle.org` (Gradle distributions) and `dl.google.com` (Android SDK packages and every Google Maven artifact: AGP, AndroidX, Firebase). Maven Central, the Gradle plugin portal and Docker Hub are reachable.
- **Decision:** (a) The Gradle wrapper pins 8.14.3; `scripts/verify.sh` falls back to an installed Gradle of the same version when the wrapper distribution cannot be downloaded. (b) The Android SDK (platforms, build-tools) is taken from CircleCI's public `cimg/android` Docker image. (c) Google Maven artifacts cannot be obtained in this environment; see D-003.
- **Alternatives:** Third-party mirrors of Google Maven — rejected: routing around an organization egress policy is not acceptable.
- **Consequences:** Backend verification is complete here. Android verification depends on D-003.
- **Requirements affected:** NFR-011, RG-03.
- **Reversal plan:** Once `dl.google.com` is allowed, the standard `./gradlew` flow is used unchanged; CI (GitHub Actions) already uses it.

## D-003 Android build verification path

- **Date:** 2026-09-27
- **Context:** AGP and AndroidX resolve only from Google Maven (redirects to the blocked `dl.google.com`).
- **Decision:** Android code that has no Android framework dependency (API client, DTOs, formatting, redaction, UI state reducers, cache policy) lives in a pure Kotlin/JVM module `android/core` that is built and tested here from Maven Central. The Compose application module `android/app` is built and tested by CI (`.github/workflows/ci.yml` android job), and locally once Google Maven is reachable. Its status is recorded in the traceability matrix as not verified until such a run passes.
- **Alternatives:** Declaring Android complete without compiling it — rejected (no claims without verification).
- **Consequences:** WP9 exit gates depend on an environment with Google Maven access.
- **Requirements affected:** RG-03, NFR-003, NFR-005, NFR-009, FR-101, FR-102.
- **Reversal plan:** None needed; the split is a sound architecture regardless.

## D-004 Opaque server-side sessions instead of JWT access tokens

- **Date:** 2026-09-27
- **Context:** FR-002 requires session revocation. Appendix C lists `JWT_SIGNING_KEY`.
- **Decision:** Access tokens are 256-bit random opaque tokens; only their SHA-256 hash is stored. Revocation is immediate. `JWT_SIGNING_KEY` is used as the HMAC key for single-use server-side action tokens (section 14) and other signed values.
- **Alternatives:** Stateless JWT (revocation needs a deny list anyway).
- **Consequences:** One indexed lookup per request; simple, immediate revocation.
- **Requirements affected:** FR-002, NFR-004, section 14.
- **Reversal plan:** Token format is opaque to clients; can be changed server-side.

## D-005 Unknown strategy fields: Manual Review Required vs rejection

- **Date:** 2026-09-27
- **Context:** Section 9 lists "Unknown schema fields" under prohibited content and says unknown fields are not silently ignored; FR-043 requires unrecognized but potentially meaningful content to be labelled Manual Review Required; FR-042 requires rejecting executable content, unsupported operators, unsafe values and schema violations.
- **Decision:** Unknown fields are never ignored and never activatable. (1) Unknown fields whose name or content matches executable, network, file, database, shell, reflection or real-money patterns → **rejected** (Validation Failed, security). (2) Unknown enum values (operators, indicators, comparisons, timeframes, sizing methods) → **rejected** as unsupported operators. (3) Any other unknown field → **Manual Review Required**: the version cannot be validated, backtested or activated; the owner must remove or resolve it by creating a new version.
- **Alternatives:** Reject all unknown fields (loses FR-043); accept and ignore (violates section 9).
- **Consequences:** Satisfies both statements with the most restrictive outcome for anything executable.
- **Requirements affected:** FR-041, FR-042, FR-043, section 9.
- **Reversal plan:** Classification rules are data in `StrategyContentScanner`; moving a field class between MRR and rejection is a one-line change with tests.

## D-006 Replay data is synthetic and labelled

- **Date:** 2026-09-27
- **Context:** Deterministic replay is mandatory; real historical data cannot be redistributed in the repository.
- **Decision:** `scripts/generate_replay_fixtures.py` produces deterministic synthetic OHLCV (integer arithmetic, fixed seed) for 8 US equities/ETFs and 3 crypto pairs, plus synthetic splits, dividends and USD/CAD rates. Every artifact and API response in replay mode is labelled `REPLAY_SYNTHETIC`; nothing is presented as real market data.
- **Consequences:** No silent placeholder data; realistic enough for end-to-end verification (calendar-aligned sessions, split, dividends, documented gap, stress regime).
- **Requirements affected:** NFR-012, FR-112, FR-023.
- **Reversal plan:** Replace fixtures with licensed data by pointing `SF_REPLAY_FIXTURES_DIR` at another directory with the same format.

## D-007 In-application encrypted logical backup instead of pg_dump

- **Date:** 2026-09-27
- **Decision:** Backups are produced by the backend using PostgreSQL `COPY` over JDBC, packaged with a manifest (schema version, row counts, ledger reconciliation totals, SHA-256), and encrypted with AES-256-GCM using a key derived from `MASTER_ENCRYPTION_KEY`. Restore runs as an offline command mode, loads a clean database, applies Flyway upgrades and verifies reconciliation.
- **Alternatives:** `pg_dump` in the backend image (extra native dependency, separate encryption tooling).
- **Consequences:** Backup/restore is testable in CI with Testcontainers; no superuser is required.
- **Requirements affected:** FR-112, NFR-008, MS-21, RG-08.
- **Reversal plan:** `pg_dump` remains usable as an operator-level extra (documented in docs/BACKUP_RESTORE.md).

## D-008 US equity trading calendar

- **Date:** 2026-09-27
- **Decision:** NYSE regular sessions, full-day holidays and 13:00 ET early closes are encoded for 2023–2027. Outside that range the calendar reports `UNVERIFIED` and equity trading is blocked (fail closed) until the table is extended.
- **Requirements affected:** FR-082, FR-090, section 11 (trading schedule).
- **Reversal plan:** Extend the table annually; a test fails when the current year is not covered.

## D-009 Critical notifications cannot be disabled

- **Date:** 2026-09-27
- **Context:** Section 3: "critical safety events cannot be disabled"; FR-101 makes push optional.
- **Decision:** Categories RISK_EVENT, STRATEGY_SUSPENSION, STALE_DATA, SYSTEM_HEALTH and SECURITY are always written to the inbox and, when a push channel is configured, always pushed regardless of per-category preferences. Settings updates that disable them are rejected with 422 `critical-notification-required`.
- **Requirements affected:** FR-100, FR-101, section 3.
- **Reversal plan:** The critical flag lives on `NotificationCategory`.

## D-010 AI provider pricing is mandatory configuration

- **Date:** 2026-09-27
- **Context:** FR-037 requires cost ceilings; FR-032 records cost "when available". Provider APIs return token usage but not price.
- **Decision:** Every AI provider configuration must declare input/output USD price per million tokens. Costs are reported as estimates computed from recorded usage. Requests whose worst-case estimated cost would exceed the remaining budget are refused before any network call (fail closed).
- **Requirements affected:** FR-032, FR-037.
- **Reversal plan:** If a provider starts returning authoritative cost, prefer it and keep the estimate as fallback.

## D-011 Recovery flow disables TOTP

- **Date:** 2026-09-27
- **Context:** The offline recovery code exists for lost devices, which usually means a lost authenticator.
- **Decision:** A successful recovery resets the password, revokes every session, disables TOTP (re-enrolment required) and raises a SECURITY notification. Recovery codes are HMAC-SHA256 hashed with the server signing key and single use.
- **Requirements affected:** FR-002.
- **Reversal plan:** Require TOTP during recovery if the owner prefers; one condition in `IdentityService.recover`.

## D-012 Fractional equity quantities

- **Date:** 2026-09-27
- **Context:** Percent-of-equity sizing on high-priced equities can round to zero shares; the simulator is paper-only.
- **Decision:** Equities trade in increments of 0.0001 shares, crypto in 1e-8 (1e-6 for low-priced coins). Quantities are floored to the increment (never rounded up into more exposure).
- **Requirements affected:** FR-045, FR-080, section 7 quantity rules.
- **Reversal plan:** Per-instrument `quantity_increment`; set to 1 for whole shares.

## D-013 Replay clock and market time

- **Context:** Deterministic replay needs a controllable notion of "now" for freshness, sessions, expiry and evaluation buckets.
- **Decision:** A persisted replay clock (`replay_state`) is the market clock while the REPLAY provider is active; wall-clock time remains used for sessions, audit timestamps and security. The replay clock only moves forward once paper orders exist. Each replay step synchronously runs ingestion, execution, evaluation and expiry in that order.
- **Requirements affected:** FR-112, NFR-012, RG-07.
- **Reversal plan:** Activating a live provider switches the market clock to wall time.

## D-014 Data-quality flags block until newer valid data arrives

- **Decision:** A rejected quote or candle batch (out-of-order, malformed, clock skew, provider error) sets a per-instrument flag that makes verification fail. The flag clears only when a newer batch passes validation, or when the owner resets it after review (audited endpoint).
- **Requirements affected:** FR-024, MS-17.

## D-015 Audit hash-chain lock and nested transactions

- **Context:** The audit chain serializes writers with a transaction-scoped advisory lock. A thread that holds it and then opens a REQUIRES_NEW transaction which also audits would wait on itself (observed during replay stepping).
- **Decision:** Pipelines that coordinate independent units of work (replay steps, scheduler runs, engines) run with `PROPAGATION_NOT_SUPPORTED` so every unit commits its own work and releases the lock. Services never call REQUIRES_NEW audit after auditing in the same transaction.
- **Requirements affected:** FR-110, NFR-007.

## D-016 Risk-engine framework in WP3

- **Context:** An order must not exist without a risk decision (fail closed), but the full limit hierarchy is WP6.
- **Decision:** The risk engine (pluggable rules, append-only `risk_evaluations`, block on FAIL/UNVERIFIED/exception) ships in WP3 with execution-integrity rules (portfolio state, reconciliation, instrument, lot rules, price sanity, verified data, schedule, holdings, shorting, buying power). WP6 adds the global/portfolio/strategy/order limit hierarchy as further rules.
- **Requirements affected:** FR-084, FR-090..FR-093.

## D-017 Execution and valuation conventions

- **Decision:** Fills require a verified quote newer than the previous fill; buys fill at ask (or last + half fallback spread) plus adverse slippage, sells at bid minus slippage, rounded to the price increment against the owner; partial fills are capped at the configured participation of the last closed 1-minute bar volume (if no volume exists the fill is uncapped and the execution is labelled `UNAVAILABLE_NO_VOLUME`); buy-side cash is reserved at creation with a 2% buffer. DAY orders expire at the session close (equities) or after 24 h (crypto); GTC orders after 90 days. Positions are valued long-at-bid and short-at-ask; unpriced positions are carried at cost and flagged. Dividends are entitled on the position held when the ex-date is processed and paid on the pay date; shorts pay dividends in lieu. Borrow fees accrue daily on |short value| x rate / 360. Short sales hold initial margin (default 50%) and are force-covered when equity falls below the maintenance percentage (default 30%) of short value.
- **Requirements affected:** FR-013, FR-014, FR-082, FR-083.
