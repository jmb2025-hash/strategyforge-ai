# Work Package Log

Each work package is stated before implementation (outcome, requirements, files,
migrations, tests, dependencies, risks) and closed with its exit-gate evidence.

## WP0 — Repository and verification harness

- **Outcome:** Monorepo with pinned toolchain, Spring Boot skeleton booting against PostgreSQL via Flyway, Docker Compose, CI, formatting/lint/static analysis, deterministic replay fixtures, traceability matrix with executable checker, decision log.
- **Requirements:** NFR-001, NFR-002, NFR-004 (idempotency base), NFR-010, NFR-011, NFR-012, FR-110 (audit base), FR-113 (startup/route guard).
- **Files:** `backend/` (Gradle build, `common/*`, `safety/*`), `fixtures/replay/`, `scripts/*`, `docker-compose.yml`, `.github/workflows/ci.yml`, `docs/*`.
- **Migrations:** `V1__baseline.sql` (audit_events with append-only triggers and hash chain, idempotency_records, system_health_events).
- **Tests:** `BaselineIT` (migrations, UTC columns, no float columns, append-only audit, hash chain, redaction, concurrent idempotency, brokerage-route rejection), `RealMoneyPolicyTest`, `DecimalsTest` (+ jqwik property), `SecretRedactionTest`, `ReplayFixtureManifestTest`, `ArchitectureTest`.
- **Dependencies:** Docker (Testcontainers), Maven Central.
- **Risks:** Blocked Google Maven/Gradle downloads (D-002, D-003).
- **Exit gate:** clean build and skeleton tests pass — see commit message and `backend/build/test-results`.
- **Result:** Passed — 21 tests, spotless, detekt, bootJar, SBOM, image build (commit "WP0").

## WP1 — Identity, settings and diagnostics

- **Outcome:** Owner bootstrap (singleton, optional bootstrap token), Argon2id login with lockout, opaque revocable sessions, recent-auth step-up, optional TOTP with replay protection, offline recovery codes, owner preferences with ETag concurrency, provider configuration with AES-256-GCM encrypted credentials never returned, device registration, pluggable diagnostics.
- **Requirements:** FR-001, FR-002, FR-003 (partial: risk profile defaults in WP6), FR-004 (partial: market/data freshness in WP2, scheduler/reconciliation later), FR-030 (configuration), FR-031, FR-110, NFR-004, MS-01, MS-02.
- **Files:** `identity/*`, `settings/Settings.kt`, `providers/*`, `operations/Diagnostics.kt`, `common/security/Crypto.kt`, `notifications/NotificationCategory.kt`.
- **Migrations:** `V2__identity_settings_providers.sql` (owners singleton, recovery_codes, devices, sessions, action_tokens, owner_settings, provider_configurations, provider_capabilities).
- **Tests:** `IdentityLifecycleIT` (fresh DB), `SessionsAndSettingsIT`, `ProviderCredentialIT`, `TotpTest` (RFC 6238 vectors).
- **Dependencies:** Spring Security, Bouncy Castle (Argon2).
- **Risks:** Argon2 cost vs test time (acceptable); push access to the Git remote is currently denied (work is committed locally).
- **Result:** Passed — 37 tests green; spotless and detekt clean.

## WP2 — Instrument master and market data

- **Outcome:** Instrument master (46 curated instruments; provider-verified equity additions), provider abstraction with explicit capability states, deterministic replay adapter with persisted replay clock, Twelve Data adapter (fixture-tested only), token-bucket limits, quote/candle/FX ingestion with provider and exchange timestamps, batch validation (out-of-order, malformed, clock skew), freshness verification, candle aggregation with session alignment and gap detection, NYSE calendar 2023–2027, watchlists, price alerts, corporate actions with coverage/MRR, notification inbox (early, needed for alerts and stale data), market diagnostics.
- **Requirements:** FR-004, FR-020–FR-025, MS-09, MS-17 (FR-100 inbox groundwork).
- **Migrations:** `V3__instruments_market_data.sql`, `V4__notifications.sql`.
- **Tests:** `MarketCalendarTest`, `ReplayProviderTest`, `TwelveDataProviderTest` (MockWebServer + recorded responses), `MarketDataIT`, `LiveProviderIT` (fresh DB).
- **Risks:** Twelve Data response shapes are based on published documentation; live verification is an optional owner step (docs/RUNBOOK.md).
- **Result:** Passed — 61 tests green; spotless and detekt clean.

## WP3 — Portfolio, ledger and manual orders

- **Outcome:** Paper portfolios (≤10 active) with create/rename/archive/clone/reset; append-only double-entry ledger with database-enforced balance; FIFO lots; ledger-derived positions and valuation; cash reservations; paper order state machine; simulator with spread, slippage, commissions, liquidity-capped partial fills, execution delay, sessions and crypto 24/7; shorts/covers with borrow fees and forced cover; splits and dividends; reconciliation; restart-safe execution; risk-engine framework with integrity rules.
- **Requirements:** FR-010–FR-015, FR-080–FR-085, NFR-002, MS-03, MS-04, MS-14 (FR-093 groundwork).
- **Migrations:** `V5__portfolio_ledger_orders.sql`.
- **Tests:** `PricingPropertyTest` (jqwik), `PortfolioLifecycleIT`, `OrderExecutionIT`, `EquitySessionIT`, `LedgerInvariantIT` (randomized seeds), `ExecutionRecoveryIT` (DB fault injection).
- **Risks found and fixed:** Kotlin default arguments under CGLIB proxies, audit-lock self-deadlock (D-015), in-flight order counted in holdings, self-invoked `@Transactional` methods.
- **Result:** Passed — 80 tests green; spotless and detekt clean.
