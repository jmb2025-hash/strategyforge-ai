# Architecture

StrategyForge AI Version 1 is a private, single-owner system for researching, validating,
backtesting and operating trading strategies with **simulated money only**. It has two parts:

- **Backend.** A Kotlin/Spring Boot modular monolith backed by PostgreSQL. It is the only source of truth.
- **Android app.** A Kotlin/Compose client that displays and commands the backend. Its local data is a cache.

The master build package (`docs/StrategyForge_AI_Master_Build_Package_v1.0.docx`) is
authoritative. Deviations and resolved ambiguities are recorded in [DECISION_LOG.md](DECISION_LOG.md).

```
 Android app (Compose)                       Owner's machine (Docker Compose)
 ┌──────────────────────────┐   HTTPS over   ┌───────────────────────────────────────────────┐
 │ ui ── ViewModels          │  private net   │ backend (Spring Boot, Java 21)                │
 │  │    android/core:       │ ─────────────▶ │  web/API ─ identity ─ settings ─ providers    │
 │  │    ApiClient, presenters│  JSON, idem-   │  market ─ portfolio/ledger ─ execution         │
 │  │    cache policy, format │  potency keys  │  strategy ─ backtest ─ risk ─ signals/autonomy │
 │  Room cache, Keystore     │ ◀───────────── │  research(AI) ─ notifications ─ reports        │
 │  WorkManager sync, FCM    │  FCM (data-only│  operations(diagnostics, backup) ─ safety      │
 └──────────────────────────┘   redacted)    │               │                                │
                                              │         PostgreSQL 16 (Flyway V1..V9)          │
                                              └───────────────────────────────────────────────┘
            Optional external providers (owner-configured, all behind adapters):
            Twelve Data (market data), Anthropic / OpenAI / Gemini / OpenRouter (AI), FCM (push)
```

## 1. Safety boundary (FR-113)

The safety boundary takes priority over every other design concern.

- **Startup.** `RealMoneyPolicy` refuses to start the process unless `REAL_MONEY_TRADING_ENABLED` is exactly `false`. This check runs in `main` and again inside the Spring context.
- **HTTP.** `RealMoneyRouteFilter` rejects route shapes such as broker, live, withdraw or wallet with `403 real-money-prohibited`, before authentication, and audits each rejection.
- **Fields.** Any request, setting, provider setting or strategy field matching `PROHIBITED_FIELD_PATTERN` is rejected. In a strategy file the security scan reports it as a `REAL_MONEY_FIELD` error, so the strategy can never be validated.
- **Database.** CHECK constraints fix `portfolios.account_type = 'PAPER'`, `paper_orders.venue = 'PAPER_SIMULATOR'` and the allowed `provider_type` values. No provider kind can place orders.
- **Code.** ArchUnit rules forbid broker or live-trading classes and packages, and forbid the research module from reaching execution, risk, signals, autonomy or portfolio code. A repository policy test forbids brokerage SDK dependencies.
- **Evidence.** `RealMoneyProhibitionIT` (MS-20/RG-09), `RealMoneyPolicyTest`, `BaselineIT`, `ArchitectureTest` and `scripts/tests/test_repository_policies.py`.

## 2. Backend modules

The packages are under `backend/src/main/kotlin/app/strategyforge/`. `common` depends on no
feature module; this is enforced by an ArchUnit rule.

| Module | Responsibility | Key types |
|---|---|---|
| `common` | Audit (append-only, SHA-256 hash chain), idempotency, money/decimals, JSON, time, web errors (RFC 7807), secret encryption and redaction | `AuditService`, `IdempotencyService`, `Decimals`, `SecretCipher`, `ProblemHandler` |
| `safety` | Real-money prohibition policy and guards | `RealMoneyPolicy`, `RealMoneyRouteFilter` |
| `identity` | Single owner, Argon2id passwords, TOTP, recovery codes, opaque server sessions, recent authentication, single-use action tokens, devices and push tokens | `IdentityService`, `RecentAuth`, `ActionTokenService`, `DeviceService` |
| `settings` | Timezone, display currency, theme, notification preferences, portfolio defaults (ETag/If-Match) | `SettingsService` |
| `providers` | Provider configurations, encrypted credentials, capability detection (never assumed) | `ProviderService`, `ProviderTester` |
| `market` | Instrument master, calendars, replay clock, market data ingestion and freshness, snapshots, FX, corporate actions, watchlists and alerts | `MarketDataIngestion`, `MarketClock`, `ReplayProvider`, `TwelveDataProvider` |
| `portfolio` | Paper portfolios, double-entry ledger (DB-enforced balanced journals), lots, reconciliation | `PortfolioService`, `Ledger`, `Reconciliation` |
| `execution` | Paper orders, the fill simulator (spread, slippage, participation, delays, stops), shorting and borrow fees | `OrderService`, `ExecutionEngine`, `Pricing` |
| `strategy` | Strategy JSON schema 1.0, security scan, validator, indicators, explanations, versions | `StrategyValidator`, `StrategyService`, `Indicators` |
| `backtest` | Deterministic backtests with integrity checks and benchmark | `BacktestEngine`, `BacktestService` |
| `risk` | Pre-trade risk engine and limit rules (strictest-wins global, portfolio and strategy profiles) | `RiskEngine`, `LimitRules`, `RiskProfiles` |
| `signals` | Scheduled evaluation, signals, recommendations (accept/modify/decline/snooze), strategy health, emergency controls | `EvaluationService`, `SignalDispatcher`, `RecommendationService`, `EmergencyControls` |
| `autonomy` | Autonomous-mode activation gates, disclosure, authorization fingerprint | `ActivationService` |
| `research` | AI adapters, budgets, research runs with provenance, controlled compilation into the schema | `ResearchService`, `AiClients`, `AnthropicAiClient`, `AiBudgetService` |
| `notifications` | Authoritative inbox, redaction, push outbox, FCM delivery | `NotificationService`, `PushSender` |
| `reports` | Reports, outcome comparisons, exports with reconciliation, daily summary | `ReportService`, `ExportService`, `DailySummaryService` |
| `operations` | Diagnostics contributors, health events, encrypted backup and offline restore | `DiagnosticsService`, `OperationalDiagnostics`, `BackupArchive`, `BackupCommand` |

## 3. Core flows

**Manual order.**
1. `POST /v1/orders` requires an idempotency key.
2. The request is validated and the instrument and trading session are checked.
3. The risk engine evaluates the order, and the decision is persisted.
4. Cash is reserved through a ledger journal, and the order is stored as PENDING.
5. On the next execution tick or replay step, `ExecutionEngine` fills from a captured market snapshot and posts a balanced journal with an execution record, then notifies.
6. Reconciliation periodically recomputes positions and cash from the ledger.

**Strategy lifecycle.**
1. The strategy is imported or compiled, then validated (security scan, schema, semantics, data availability).
2. A backtest with enough data makes it PAPER_ELIGIBLE.
3. It is activated in Recommendation Mode or, with the disclosure and recent authentication, in Autonomous Mode.
4. The evaluation scheduler claims one `(strategy, bar)` bucket at a time. A signal is created with a snapshot and a risk evaluation, then dispatched as a recommendation or an autonomous order.
5. Health monitors pause or suspend the strategy on stale data, reconciliation failure, limit breaches, errors or material changes (D-020, D-021).

**AI research.**
1. A research session reserves its worst-case budget.
2. A background provider call records immutable provenance.
3. The output stays "UNVERIFIED" until the owner reviews it.
4. Compilation accepts only a single JSON object in the strategy schema, which goes through the same validator as an import.

AI output never reaches execution directly (FR-092).

**Time.** Everything is stored in UTC (`timestamptz`). With the Replay provider, the persisted
replay clock is the market clock, and each replay step runs ingestion, execution, evaluation
and expiry in that order, deterministically (D-013). The replay end-to-end suite runs the same
scenario on two clean databases and requires identical results (RG-07).

## 4. Cross-cutting guarantees

- **Money.** `BigDecimal` only. There are no floating-point columns or fields; a DB test and an ArchUnit rule enforce this (NFR-002).
- **Idempotency.** Every retryable mutation requires `Idempotency-Key`. Replays return the recorded response. A key reused with a different payload is rejected (NFR-004, NFR-007).
- **Concurrency.** Scheduler claims are unique rows and batches are claimed with `FOR UPDATE SKIP LOCKED`. Order state transitions are guarded, so duplicate execution is impossible across retries, devices and restarts (NFR-007).
- **Audit.** Every consequential action appends a hash-chained audit event in the same transaction. Diagnostics verifies the chain (FR-110).
- **Errors.** Errors are RFC 7807 `application/problem+json` documents with stable codes. The contract lives in `contracts/openapi.json` (OpenAPI 3.1, verified by `ContractIT`).
- **Logs.** JSON logs carry correlation ids, and secret values are masked (NFR-010).
- **Fail closed.** Unknown strategy content goes to Manual Review Required. Unverifiable data or risk state blocks trading. An unavailable provider marks data stale and pauses strategies.

## 5. Android app

- **`android/core`.** Pure Kotlin: `ApiClient` (idempotency keys reused on retry; 401 clears the session), DTOs, `CachedResource` (cache first, then network; offline data labelled with its age), formatting (decimals, the owner's timezone, P/L cues that do not rely on colour alone), notification redaction, strict deep links, and presenters. It is tested on the JVM.
- **`android/app`.** Compose UI, Hilt, a Room cache of API responses (NFR-003), Keystore-encrypted session token, WorkManager sync, and runtime FCM initialisation from `/v1/devices/push-config`. `FLAG_SECURE` is set, backups are disabled, and release builds accept HTTPS server addresses only (D-023).

## 6. Persistence

PostgreSQL 16. Migrations `V1`–`V9` are applied by Flyway at startup. Append-only tables
(audit, ledger, executions, risk evaluations, research runs) reject UPDATE and DELETE through triggers.
Backups are encrypted logical snapshots with a consistency manifest, and restore is an offline command that
verifies reconciliation (D-007, D-024, [BACKUP_RESTORE.md](BACKUP_RESTORE.md)).

## 7. Deployment

Docker Compose runs `db`, `backend` (read-only root filesystem, no new privileges, bound to
127.0.0.1) and an optional Caddy TLS proxy. The phone connects over a private network (Tailscale
recommended). See [RUNBOOK.md](RUNBOOK.md).
