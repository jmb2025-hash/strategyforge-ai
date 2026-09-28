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

## WP4 — Strategy framework

- **Outcome:** Restricted JSON strategy format (schema 1.0), import pipeline (size, UTF-8, no BOM/control characters, strict JSON without duplicates/comments, prohibited-content and prompt-injection scan, unknown-field classification, JSON Schema 2020-12, semantic rules, data availability), immutable SHA-256-addressed versions, lifecycle state machine with history, explanations, clone/export, rejected-import records.
- **Requirements:** FR-040–FR-046, FR-047 (instrument cap), MS-05, MS-07, MS-06 (strategy side).
- **Migrations:** `V6__strategies.sql`.
- **Tests:** `StrategyIT` (27 fixtures through the real import API), `StrategyScannerTest`.
- **Result:** Passed — 89 tests green; spotless and detekt clean.

## WP5 — Backtesting

- **Outcome:** BigDecimal indicator library and rule evaluator; deterministic point-in-time backtester (next-bar fills, intrabar stops/targets/trailing stops, spread, slippage, commissions, participation-capped fills, daily-loss halts, open-position and trade limits, splits/dividends); integrity and dataset provenance; full metrics; equity/drawdown series; benchmark; async jobs with restart recovery; promotion to Paper Eligible only for clean results.
- **Requirements:** FR-050, FR-052–FR-055, MS-08 (FR-051 risk-profile parameter completes in WP6).
- **Migrations:** `V7__backtests.sql`.
- **Tests:** `IndicatorsTest` (hand values + jqwik causality), `BacktestEngineTest` (look-ahead perturbation, determinism, stop-first), `BacktestIT` (promotion, missing data, future range, corporate-action MRR).
- **Result:** Passed — 101 tests green; spotless and detekt clean.

## WP6 — Risk limits, signals and recommendations

- **Outcome:** Global/portfolio/strategy risk profiles merged strictest-wins with the supplying level reported; limit rules for emergency controls, strategy status, clock drift, symbol lists, trade value, instrument/asset-class/strategy allocation, open positions, trade frequency and cooldown, daily loss, drawdown and loss streaks, short exposure, quote age, spread, liquidity and abnormal moves (UNVERIFIED fails closed). Loosening a profile requires recent authentication. Backend evaluation of active strategies once per closed bar, claimed through a unique row. Fail-closed verification of strategy state, reconciliation, quotes and bars. Signals with full provenance. Recommendations created only after a recorded risk evaluation, with accept (single-use token), risk-reducing modify, decline, snooze, pause, expiry, supersession, price-deviation rejection and an append-only decision log. Consecutive-loss and error-rate suspension. Backtest risk-profile parameter (FR-051).
- **Requirements:** FR-051, FR-060–FR-066, FR-090, FR-091, FR-093, FR-094, NFR-006, NFR-007, MS-10, MS-12, MS-13.
- **Migrations:** `V8__risk_signals_recommendations.sql`.
- **Tests:** `LimitRulesTest` (every rule), `RecommendationLifecycleIT`, `RecommendationExpiryIT`, `StrategySafetyIT` (concurrent scheduler workers, loss and error suspension, 25-active cap), `RiskDecisionLatencyIT`, `BacktestIT` (risk profile).
- **Risks found and fixed:** a self-deadlock when a rejection was recorded in a second transaction while the recommendation row was locked. Rejections now commit with the idempotency record (`CommittedRejection`).
- **Decisions:** D-019, D-020, D-021.

## WP7 — Autonomous mode and emergency controls

- **Outcome:** Recommendation Mode by default. Autonomous activation gated on a validated immutable version, an eligible backtest for the exact hash, an active reconciled portfolio, allocation within 100%, a loadable risk profile, the versioned disclosure and recent authentication. An authorization fingerprint is re-checked before every autonomous order and whenever a risk profile or portfolio changes. Autonomous orders are created only after risk passes. Automatic pause on stale data, unverifiable risk state, loss/drawdown blocks and reconciliation failure. Emergency controls: Pause All, Prevent New Positions, Cancel Pending Orders, Disable Autonomous Mode, and a separate Close All Simulated Positions (recent authentication plus a typed confirmation). Releasing a control requires recent authentication. Everything is audited and raises critical notifications.
- **Requirements:** FR-047 (active cap), FR-070–FR-075, MS-11, MS-15.
- **Tests:** `AutonomyIT`, `EmergencyControlsIT`.
- **Result (WP6 + WP7):** Passed. 124 backend tests green; spotless and detekt clean; traceability 71/114 Passed.

## WP8 — AI research and controlled compilation

- **Outcome:** A common AI adapter: Anthropic (official Java SDK, web search with citations, server-side refusal fallback for supported models), OpenAI, OpenRouter and Gemini, with capability diagnostics. Research sessions carry asset class, universe, horizon, timeframe, approach, prompt and request/cost ceilings. Background provider calls with immutable provenance: prompts, prompt version, parameters, raw response, usage, cost, sources and timestamps. Global and session budgets are reserved at worst case before any network call. Output is labelled unverified until owner review, and owner edits are append-only. Compilation turns reviewed research into the strategy schema through the regular validator, and provenance is linked to the resulting strategy version. Restarts mark interrupted runs as failed.
- **Requirements:** FR-030–FR-037, FR-092, MS-18 (MS-06 on the AI path).
- **Migrations:** `V9__research.sql`.
- **Tests:** `AiClientsTest` (MockWebServer recordings for all four adapters: citations, refusal, truncation, pause, HTTP classes, timeouts, malformed JSON), `ResearchIT` (end-to-end workflow, missing sources, malformed/declined/injected/real-money/risk-override compiler output, request and cost ceilings, timeout), `ArchitectureTest` (research cannot reach execution/risk).
- **Risks found and fixed:** Java's HTTP client request timeout does not cover the response body, so a whole-response deadline was added. The session was marked idle before its compilation record existed, which was a visible race; it is now completed in the same transaction.
- **Decisions:** D-022.
- **Result:** Passed. 138 backend tests green; spotless and detekt clean; traceability 80/114 Passed.

## WP9 — Android application

- **Outcome:** A sideloadable Android app (Kotlin, Compose, Hilt, Room, WorkManager). Screens: first run and sign-in, Home dashboard, strategies, backtests, research, portfolio and orders, recommendations with accept/reject/modify, inbox, emergency controls, diagnostics, and settings. The app uses cache-then-network with labelled offline data, keeps the session in Keystore-encrypted storage, sets `FLAG_SECURE`, uses generic lock-screen text and strict deep links, and initialises FCM at runtime.
- **Requirements:** NFR-003, NFR-005, NFR-009, FR-101/FR-102 (client), RG-03.
- **Files:** `android/core` (JVM), `android/app`, `backend/.../identity/Devices.kt` (`/v1/devices/push-config`), `.github/workflows/ci.yml` (android job, signing, checksums), `docs/ANDROID.md`.
- **Tests:** Core: `ApiClientTest`, `CacheAndFormattingTest`, `PresenterTest`, `PerformanceBudgetTest` (15 tests). App: `ComposeUiTest` (6 Robolectric tests). Backend: `DevicesIT`, `ContractIT` (OpenAPI 3.1 export and coverage).
- **Risks found and fixed:** `setup-android` failed on the runner, so the job now uses the preinstalled SDK. Compose assertions needed the unmerged semantics tree and scrolling. The formatter configuration was aligned with the backend.
- **Decisions:** D-023.
- **Result:** Passed. CI run 36393920240 (commit d92c8a5): spotless, core tests, Compose UI tests, lint, debug and release APKs, and the Android SBOM all passed. `app-release.apk` SHA-256 `5816af7ce5085f6f5460e2834ff61d6bb24223e35fc479d2da74c789917119e1` was signed with the ephemeral CI key; configure owner signing secrets for a permanent key.

## WP10 — Notifications delivery, reports, exports and operations

- **Outcome:** FCM push delivery with redacted data-only payloads, retry and backoff, and unregistered-token handling. Stop/target and daily-summary notifications. Portfolio, outcome, risk, strategy and AI-provenance reports. CSV/JSON exports with stable schemas, totals and reconciliation. Operational diagnostics: scheduler, push queue, recent errors, audit chain and backup age. Encrypted backup and restore with an offline command mode. Runbook and backup/restore documentation.
- **Requirements:** FR-003, FR-100–FR-105, FR-110–FR-112, NFR-008, MS-16, MS-19, MS-21, RG-08.
- **Files:** `notifications/PushSender.kt`, `reports/{Reports,Exports,DailySummaryService}.kt`, `operations/OperationalDiagnostics.kt`, `operations/backup/{BackupArchive,BackupService,BackupCommand}.kt`, `docs/RUNBOOK.md`, `docs/BACKUP_RESTORE.md`.
- **Migrations:** none (existing tables).
- **Tests:** `PushDeliveryIT`, `ReportsIT` (reports, exports, CSV escaping, daily summary), `BackupRestoreIT` (backup, verify, restore into a clean database, fingerprint, tamper/truncation/wrong-key, diagnostics), `AutonomyIT` (protective-exit notification), `OrderExecutionIT` (stop-fill notification).
- **Risks found and fixed:** Foreign-key cycles and deferred journal triggers blocked a naive ordered restore (D-024). Primitive `Long` mapping failed in diagnostics. CI Trivy reported HIGH/CRITICAL CVEs in BOM-managed Tomcat, pgjdbc and httpcore5; they are patched through overrides (D-025).
- **Decisions:** D-024, D-025, D-026.
- **Result:** Passed. 146 backend tests green; spotless, detekt, bootJar and SBOM clean; traceability 100/114 Passed, with the remainder assigned to WP11.
