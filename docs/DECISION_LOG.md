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

## D-018 Backtest conventions

- **Decision:** Annualized return is reported only for windows of at least one year (otherwise null with a note). Volatility is annualized from the observed bar frequency. Critical integrity: no bars in window, more than 5% missing bars, or a data-quality flag. Corporate-action data unavailable for an equity makes the result Manual Review Required. Only OK/WARNINGS results for the exact current version promote a strategy to Paper Eligible. Limit entries are valid for one bar; unfilled remainders are dropped. Backtests cannot request data after the current market time. Running backtests interrupted by a restart are marked failed.
- **Requirements affected:** FR-050..FR-055, FR-025.

## D-019 Default risk profile values

- **Context:** The master document requires a global risk profile but does not give numeric defaults for every limit.
- **Decision:** A seeded GLOBAL profile uses conservative values (20% of equity per trade, 25% per instrument, 100% equities / 50% crypto per asset class, 25 open positions, 5/60/200 orders per minute/hour/day, 5% daily loss, 25% drawdown, 6 consecutive losses, 50% short exposure, 60 s quote age, 1% spread, 10% participation, 15% move from the previous close, 3 consecutive evaluation errors). Tightening any level is immediate; loosening requires recent authentication and is audited. Portfolio and strategy profiles are optional overlays; the strictest applicable value wins and each block names the level that supplied it.
- **Requirements affected:** FR-090, FR-091, FR-093.

## D-020 Signal evaluation, expiry and supersession

- **Context:** The master document requires scheduled evaluation, signal expiry and supersession without fixing the cadence or expiry length.
- **Decision:** Each active strategy is evaluated once per closed bar of its timeframe. The claim is a unique `(strategy, bucket)` row, so concurrent workers, retries and restarts cannot duplicate work. Signals expire after the bar length clamped to 5–30 minutes. A newer signal for the same strategy and instrument supersedes a pending recommendation. A strategy never trades an instrument that is held outside the strategy. A symbol that fails verification blocks the whole evaluation and nothing is traded.
- **Requirements affected:** FR-060, FR-061, FR-066, NFR-007, MS-13.

## D-021 Automatic pauses, suspensions and emergency controls

- **Context:** The master document lists the pause triggers and emergency controls but not the resulting states.
- **Decision:** Stale or unavailable data, reconciliation failure, an unavailable risk engine or unverifiable risk state, a loss or drawdown limit, and any material change to the authorization fingerprint (strategy version, portfolio cost model or shorting, allocation, or any applicable risk profile) move an autonomous strategy to Paused. Repeated evaluation errors (the effective `maxConsecutiveErrors`, default 3) and the strategy's consecutive-loss limit move it to Suspended, which the owner must review before re-activating. Pending recommendations are closed on every pause or suspension. Engaging an emergency control is immediate. Releasing Pause All or Prevent New Positions requires recent authentication. Pause All also pauses every active strategy so nothing resumes on release, and blocks every order except Close All Simulated Positions, which requires recent authentication plus the typed phrase `CLOSE ALL SIMULATED POSITIONS`.
- **Requirements affected:** FR-072, FR-074, FR-075, FR-094, MS-11, MS-15.

## D-022 AI adapters, research workflow and controlled compilation

- **Context:** The master document requires Anthropic, OpenAI, Gemini and OpenRouter adapters, provenance, citations when retrieval is supported, budgets and schema-only compilation. It leaves the adapter mechanics open.
- **Decision:**
  - **Adapters.** The Anthropic adapter uses the official Anthropic Java SDK (pinned, `anthropic-java` 2.34.0) and its web search server tool. Cited sources come from `web_search_result_location` citations. For `claude-opus-5` and `claude-fable-5-1` the server-side refusal fallback (`fallbacks: "default"`) is requested; a provider setting can turn it off. OpenAI (Chat Completions), OpenRouter (OpenAI-compatible) and Gemini (`generateContent`) use plain HTTPS adapters without retrieval, so no sources are ever claimed for their output.
  - **Failures, not partial answers.** Refusals, truncation (`max_tokens`/`length`/`MAX_TOKENS`), paused turns and exceeded context windows fail the run. The whole response, headers and body, has a single deadline.
  - **Asynchronous runs.** Provider calls run in the background after the budget reservation commits. The client polls the session. A run interrupted by a restart is recorded as failed with `INTERRUPTED`.
  - **Budgets.** Budgets are global (monthly cost, daily requests, output tokens, input characters) and per session (requests, cost). Each call reserves its worst case: input characters / 2 tokens, the full output ceiling, and every allowed search. The call is refused before any network request if the reservation would exceed a ceiling. Raising a global ceiling requires recent authentication.
  - **Review and compilation.** Output is labelled "UNVERIFIED AI OUTPUT" until the owner approves it, and any edit returns it to unverified. Research that required retrieval but returned no citations fails (`MISSING_SOURCES`) and cannot be approved. Compilation sends the reviewed text (the latest owner edit, else the model's response) with the JSON Schema. It accepts exactly one JSON object (a single Markdown fence is tolerated). The server sets `metadata.createdBy = AI_COMPILED`, and the result then goes through the regular strategy import validator (security scan, schema, semantics, data availability). The resulting version is linked to its compilation (`strategy_versions.source_ref`), and the full chain is served by `/v1/research/provenance/versions/{id}`.
  - **Boundary.** An architecture test forbids the research module from depending on execution, risk, signals, autonomy or portfolio code (FR-092).
- **Requirements affected:** FR-030–FR-037, FR-092, MS-06, MS-18.
- **Reversal plan:** Retrieval for other providers can be added behind `AiClients.retrievalAvailable` once their citation formats are implemented and tested. A paused Anthropic turn could be continued rather than failed.

## D-023 Android architecture, CI-only app build and release signing

- **Date:** 2026-09-28
- **Context:** D-003 left the Compose app module buildable only where Google Maven is reachable. The master document requires a signed release APK. It also requires the backend to be authoritative, with Room as a cache.
- **Decision:**
  - **Split.** `android/core` (pure Kotlin) holds the API client, DTOs, cache policy, formatting, redaction, deep links and presenters; it is tested here and on CI. `android/app` (Compose, Hilt, Room, WorkManager) holds only platform glue and screens.
  - **Cache.** Room stores serialized API responses keyed by endpoint. It is read first and then replaced by the network copy. Offline data is labelled with its age. Every write goes to the backend (NFR-003). Signing out clears the cache.
  - **Session.** The session token is kept in an AES-GCM blob whose key lives in the Android Keystore. A 401 response clears it.
  - **Push.** Firebase is initialised at runtime from `/v1/devices/push-config` (public identifiers only), so there is no `google-services.json` in the repository. Push is optional, and the inbox stays authoritative.
  - **Screens.** `FLAG_SECURE` is set on all screens. Backup is disabled. Release builds accept only HTTPS server addresses.
  - **Release signing.** The key comes from repository secrets (`SF_RELEASE_KEYSTORE_B64` and passwords). Without them, CI generates an **ephemeral** key for each run, so the APK is signed and installable. Updating the app later requires the same key, so the owner must configure a permanent key before relying on updates. The CI log records the SHA-256 of every APK and the signing certificate digest.
- **Evidence:** CI run 36393920240 (commit d92c8a5), android job green: spotless, 14 core tests, 6 Robolectric Compose UI tests, lint, assembleDebug, assembleRelease and CycloneDX SBOM. `app-release.apk` SHA-256 `5816af7ce5085f6f5460e2834ff61d6bb24223e35fc479d2da74c789917119e1`, signed by an ephemeral CI certificate (SHA-256 `f60ecfe4…c41d9b`).
- **Requirements affected:** RG-03, NFR-003, NFR-005, NFR-009, FR-101, FR-102.
- **Reversal plan:** Moving the app build to local machines needs only Google Maven access. The build files are unchanged.

## D-024 Backup restore mechanics

- **Date:** 2026-09-28
- **Context:** D-007 chose in-application COPY backups. Implementing it raised three problems. The schema contains foreign-key cycles (`strategies` ↔ `strategy_versions`). Migrations seed singleton rows. Startup hooks such as provider initialisation would write to a restore target.
- **Decision:**
  - **Offline mode.** Restore and verification run as offline commands (`restore <file>`, `verify-backup <file>`) that never start the Spring context. They share `BackupArchive` with the service.
  - **Restore order.** Flyway migrates the target to the backup's schema version. Then, in one transaction, foreign keys are dropped, seeded rows are cleared (`strategyforge.allow_purge`), the tables are loaded with `COPY … HEADER MATCH`, the deferred double-entry checks run, the foreign keys are re-added (re-validating every row) and the sequences are reset. The fingerprint is then compared, and only afterwards does Flyway upgrade to the latest version.
  - **Encryption container.** Chunked AES-256-GCM. The chunk index and a final flag are authenticated, so truncation is detectable.
  - **Target.** Restore only into an empty database. The documented procedure renames the old database, which makes it reversible.
- **Requirements affected:** FR-112, NFR-008, MS-21, RG-08.
- **Reversal plan:** Streaming table loads, instead of loading each table into memory, can replace the in-memory load if data grows beyond single-owner scale.

## D-025 Dependency patch overrides

- **Date:** 2026-09-28
- **Context:** The CI Trivy scan of the backend SBOM reported HIGH and CRITICAL CVEs in versions managed by the Spring Boot 3.5.16 BOM: Tomcat 10.1.55, pgjdbc 42.7.11 and httpcore5 5.3.6, the last one via the Anthropic SDK. RG-06 forbids unresolved HIGH or CRITICAL findings.
- **Decision:** Override the BOM properties to the fixed releases: `tomcat.version` 10.1.60, `postgresql.version` 42.7.13, `httpcore5.version` 5.4.3. Accepting the findings in `.trivyignore` was rejected because fixed versions exist.
- **Consequences:** The full backend suite passes on the patched versions. Remove each override when a Spring Boot release manages an equal or newer version.
- **Amendment (2026-09-29):** CVE-2026-68497 (HIGH) in jackson-databind 2.21.4. Now overridden to 2.21.6: `jackson-bom.version` in the backend, and the `jackson` catalog version for the on-device engine.
- **Requirements affected:** RG-06, NFR-011.

## D-026 Push delivery, stop/target events and daily summary

- **Date:** 2026-09-28
- **Context:** FR-101 lists the push categories, including stop/target events and a daily summary. FR-102 requires lock-screen redaction.
- **Decision:**
  - **Delivery.** Push goes through FCM HTTP v1, authenticated by the service-account credential stored encrypted in the FCM provider. Messages are data-only and carry only the redacted title and body, the channel, the notification id and the deep link. Transient failures (429, 5xx, network) retry with exponential backoff up to 6 attempts. An unregistered token disables push for that device.
  - **Stop/target events.** A STOP_TARGET notification is raised when a strategy's stop-loss, take-profit or trailing stop fires, and when a STOP or STOP_LIMIT paper order fills.
  - **Daily summary.** One summary per local day at the owner's `dailySummaryLocalTime` (default 17:00) in the owner's timezone. The local date is the dedupe key, so restarts cannot duplicate it.
- **Requirements affected:** FR-101, FR-102, MS-16.

## D-027 Phone-only architecture (owner decision, supersedes the backend-authoritative design)

- **Date:** 2026-09-29
- **Context:** The master document specifies a backend-authoritative system: a Spring Boot and PostgreSQL server, with Android data as cache only and provider keys never on the device (FR-031, NFR-003). The owner does not want to operate a server. They want the phone app to fetch market data, call AI providers and run strategies itself, staying active in the background.
- **Decision (owner):**
  - **Engine on the phone.** Version 2 runs the engine on the device. A new plain-Kotlin module, `android/engine`, holds the domain logic ported from the backend. Storage is SQLite on the phone behind a small SQL interface (D-028), and the app calls the engine directly.
  - **Background running.** A foreground service with a persistent notification keeps strategies evaluating while the screen is off. The owner is asked to set the battery mode to "Unrestricted", and the service restarts after a reboot.
  - **Unchanged.** Simulated money only; fail closed; the risk engine, audit trail and double-entry ledger; AI output untrusted and compiled only to the schema.
  - **Rollout (owner choices).**
    - Crypto first, using free public real-time exchange data with no key.
    - Stocks second, using delayed data from a free Twelve Data key.
    - Gemini free tier is the default AI provider; OpenRouter, OpenAI and Anthropic are optional.
    - The backend code stays in the repository, unused, as an option.
- **Consequences:**
  - FR-031 changes from "server-side" to "Android Keystore on the device".
  - NFR-003 changes: the phone database is authoritative.
  - Features that only make sense with a server are replaced: multi-device sessions, the server-side push sender, and server backups (which become an encrypted backup file).
  - Nothing runs while the phone is off, offline or force-stopped. Stale data is never traded, and strategies resume on the next fresh bar.
  - The traceability matrix is re-mapped as each stage lands. The Version 1 release candidate (RELEASE.md) remains the verified server-based build.
- **Reversal plan:** The engine module is independent of Android, so it could run behind the existing backend API again if the owner later wants a server.

## D-028 On-device storage layer

- **Date:** 2026-09-29
- **Context:** D-027 first planned SQLDelight. Its generated queries read columns by position, which made porting about 60 server queries error-prone. Android 10 (minSdk 29) ships SQLite 3.22, which lacks RETURNING, upserts and window functions.
- **Decision:**
  - The engine uses its own small SQL layer: named parameters, rows read by column name, and a `SqlBackend` interface. JDBC SQLite backs it in tests; Android's built-in SQLite backs it in the app.
  - Money, prices and quantities are stored as exact decimal text and are only summed or compared in Kotlin with BigDecimal (NFR-002).
  - Timestamps are epoch milliseconds (UTC). Ids are UUID text.
  - SQL syntax newer than SQLite 3.22 is refused at run time, so it cannot ship by accident.
  - The server's append-only and immutability guarantees are kept as triggers.
- **Requirements affected:** NFR-002, FR-011, FR-110.

## D-029 Engine threading and recommendation acceptance on the phone

- **Date:** 2026-09-29
- **Context:** The server used row locks, worker pools and single-use action tokens (for accepting recommendations from push notifications).
- **Decision:**
  - **One engine thread.** All engine calls run on one thread, and each multi-step change is one transaction. Duplicate protection still comes from unique rows (evaluation buckets, one order per recommendation), so repeated ticks and restarts never duplicate work.
  - **Network off the engine thread.** Backtests run inline on the engine thread. AI provider calls run on a background thread, and their results are recorded back on the engine thread.
  - **No action tokens.** Recommendations are accepted only inside the unlocked app. A notification can open the app but can never accept anything. Single-use tokens protected a network path that no longer exists, so they are dropped. Expiry and price-deviation checks are unchanged (FR-063, FR-064).
  - **Device lock replaces the server's step-up check.** Operations that needed recent authentication now need a device-lock confirmation (biometric or PIN) within the last 5 minutes.
- **Requirements affected:** FR-063, FR-064, FR-065, NFR-007, section 15.

## D-030 AI providers on the device

- **Date:** 2026-09-29
- **Context:** Under D-027 the phone calls AI providers directly.
- **Decision:**
  - **Keys.** Keys are stored through a `SecretStore` backed by the Android Keystore. The database keeps only an alias and a 12-character fingerprint. Changing or removing a key needs a device-lock confirmation.
  - **Gemini is the default provider.** Its free tier needs only a key, so its preset prices are 0 and only the request and token ceilings apply.
  - **Other providers.** OpenRouter, OpenAI and Anthropic use the same adapters as Version 1. Anthropic uses the official SDK. Presets are shown to the owner, who confirms or changes the model and prices; for paid providers, prices remain required.
  - **Unchanged from Version 1:** budget reservation before any network call, provenance, owner review before compilation, and compilation only through the regular validator.
- **Requirements affected:** FR-030 to FR-037 (FR-031 now means on-device secure storage).

## D-031 The app's API served in-process on the phone; on-device backups

- **Date:** 2026-09-29
- **Context:** Under D-027 there is no server. The app's screens, models, offline cache and API client, with its tests, were built against the Version 1 REST API.
- **Decision:**
  - **In-process API.** An OkHttp interceptor (`LocalApiInterceptor`) answers the app's `/v1/...` requests from the engine on the phone. A router (`LocalApi`) maps each request to engine calls, runs it on the engine thread (`EngineHost`), and returns the same JSON and problem documents as Version 1. Nothing listens on a network port. The screens, cache, idempotency keys, If-Match versions and "confirm it's you" handling stay as they are.
  - **Accept button.** The app enables Accept only when a detail carries an action token. The local API therefore returns the id of a pending recommendation in that field. The engine re-checks status, expiry and price deviation on accept, as D-029 describes.
  - **Contract test.** A JVM test runs the app's own `Repository`, `ApiClient`, cache and models against a real engine through the interceptor, so response-shape mismatches fail in CI rather than on the phone.
  - **Backups.** `BackupService` writes a gzip'd JSON copy of every table with a SHA-256 checksum to app-private storage.
    - Backups contain no credentials, because keys are held in the Keystore.
    - Verify checks the format, the checksum and that every table is present.
    - Restore requires a recent device unlock and first writes a safety backup of the current state. It then replaces every table in one transaction, lifting the append-only triggers only inside that transaction, and the audit chain stays verifiable.
    - A backup made under a different schema version is refused.
- **Requirements affected:** FR-105, FR-112, NFR-003, NFR-005, NFR-007.

## D-032 US stock data on the phone: Twelve Data with the owner's free key

- **Date:** 2026-09-29
- **Context:** The owner chose crypto first (Coinbase, no key) and delayed US stock data from a free Twelve Data key second. The free plan allows about 8 requests a minute and 800 a day. The engine checks quote freshness against 60 seconds before it fills an order or passes a risk check.
- **Decision:**
  - **Provider.** The Version 1 Twelve Data adapter is ported to the engine over OkHttp. It is used for US stocks and ETFs only; crypto and FX stay on Coinbase.
  - **Key.** The key is kept like AI keys (D-030): only in the phone's key store, with a 12-character fingerprint shown. Setting or removing it needs a recent device unlock. Without a key, stock data is explicitly unsupported, never replayed.
  - **Budget.** Quotes are cached for 5 minutes and candles for 15. No requests are made while the US market is closed once a quote is cached. The minute and day budgets are enforced on the phone.
  - **Freshness with a polled or delayed feed.** Each provider declares how far behind its quotes normally run (`expectedLagSeconds`): 0 for Coinbase; for Twelve Data the polling interval, plus 20 minutes when the feed is measured as delayed. Quote verification allows that lag on top of its limit, so paper trades on free stock data can fill while a real outage is still caught. Quotes remain labelled real-time, delayed or unknown from measured timestamps.
- **Consequences:** On the free plan, stock paper trades fill on quotes that can be minutes old. The app says so on the market-data screen. Crypto behaviour is unchanged.
- **Requirements affected:** FR-020 to FR-025, NFR-004.

## D-033 Background running and installation on the phone

- **Date:** 2026-09-29
- **Context:** The owner wants trading to continue with the app closed and the screen off, and is willing to use the recent-apps "keep open" setting.
- **Decision:**
  - **Background engine.** A foreground service (type `specialUse`) ticks the engine every 5 seconds and shows a low-importance persistent notification. It optionally holds a partial wake lock (on by default, switchable) and restarts after a reboot or an app update.
  - **Battery.** The app asks for an exemption from battery optimization only when the owner taps the button on the market-data screen. The screen explains the recent-apps "keep open" or "lock" option for phones that close background apps anyway.
  - **App lock.** The app opens behind the phone's own screen lock (fingerprint, face or PIN) and locks again after 5 minutes in the background. Unlocking also counts as the recent confirmation for protected actions (D-029).
  - **Distribution.** CI publishes the signed release APK as the `phone-latest` pre-release. Without the owner's signing secrets each build uses a new CI key, so updating means uninstalling first. The owner saves a backup copy first ("Save a copy") and restores it afterwards ("Restore from a file").
- **Requirements affected:** FR-101, FR-102, FR-112, NFR-001, section 14.

## D-034 Research as a conversation; Gemini web search; schema migrations

- **Date:** 2026-10-01
- **Context:** The owner found the research form confusing. It asked for symbols, timeframe, horizon, approach, a research question, a request cap and a cost cap before anything happened. The owner wants to name an investor, group or strategy, work with the AI on it, and then turn the result into a strategy.
- **Decision:**
  - **Research as a conversation.** Research starts from one message plus a crypto or stocks choice. The AI proposes the symbols (from the app's tradable list), the timeframe and the rules. The owner replies, and every turn sends the earlier turns with it; when the conversation is too long for the input ceiling, the oldest turns are dropped first.
  - **Budget.** The monthly AI budget and the daily request limit still apply to every call. The per-session request and cost caps are no longer asked; a conversation allows up to 100 requests within the monthly budget.
  - **Compile.** "Compile research into a strategy" records the owner's review and then compiles the whole conversation (most recent turns first if it is long). The AI chooses the timeframe and names the strategy. It summarizes the strategy in `metadata.description` and lists there what could not be expressed. The regular validator still decides whether the result is accepted.
  - **Gemini web search.** Gemini uses Google Search grounding by default, and sources come from `groundingMetadata`. On free-tier prices, searches are treated as free. It can be turned off with `webSearchEnabled=false`.
  - **Sources.** A conversation turn without web results is recorded and shown as such, not failed. Form-based research that required retrieval still needs citations before approval (MS-18 unchanged for it).
  - **Schema migrations.** Versioned, forward-only migrations run on every start. Fresh installs apply the base schema and then every migration, so new and existing phones follow one path. Migration 2 adds `research_runs.owner_message`. Backups record `engine-N`, and restore accepts backups from the same or older versions.
- **Requirements affected:** FR-032 to FR-037, FR-112.

## D-035 One crypto strategy and one stock strategy at a time

- **Date:** 2026-10-01
- **Context:** The owner wants one active crypto strategy and one active stock strategy, swapped for another saved strategy when one proves ineffective. The owner chose to be asked about open positions every time, with "keep" preselected.
- **Decision:**
  - **Slots.** Each asset class has one slot. Activating a strategy while another of the same asset class is active returns `slot-occupied`, naming that strategy and its open positions. The app asks "Replace X?" with keep preselected.
  - **Keep.** The replaced strategy's open lots in symbols the new strategy trades are marked as managed by the new strategy (migration 3: `position_lots.managed_by_strategy_id`). The new strategy then applies its own exits to them. Lots in symbols it does not trade stay open and unmanaged, and the app lists them.
  - **Close.** Market orders (source SYSTEM, linked to the replaced strategy) close the replaced strategy's positions.
  - **One transaction.** The switch runs as a single transaction, and the new activation's gates still apply. If the new activation is refused, nothing changes: the old strategy keeps running and no closing orders remain.
  - **Mode changes.** An active strategy can be re-activated to change its mode or allocation. The disclosure, recent-unlock and backtest gates apply again.
  - **Screen and wording.** The Strategies screen shows the two slots ("Running now"). "Recommendation Mode" is shown as "Notifications mode: you approve each trade".
- **Requirements affected:** FR-071, FR-072, FR-047.

## D-036 Candlestick patterns, breakouts, support/resistance and relative volume

- **Date:** 2026-10-01
- **Context:** Strategies researched from trading groups (for example crypto chart traders) rely on candlestick patterns, breakouts, support and resistance, and volume confirmation. The rules could only use moving averages, RSI, MACD, ATR and Bollinger Bands.
- **Decision:**
  - **New indicator types.** `HIGHEST` and `LOWEST` (period N) are the highest high and lowest low of the previous N bars. They exclude the current bar, so "close crosses above HIGHEST" is a breakout.
  - **Swing points.** `SWING_HIGH` and `SWING_LOW` (period k) are the latest swing points, used as resistance and support. A swing point becomes known only after k further bars have closed.
  - **Relative volume.** `RELATIVE_VOLUME` (period N) is the bar's volume divided by the average of the previous N bars. SMA and EMA accept `source: VOLUME`.
  - **Candlestick patterns.** Bullish and bearish engulfing, hammer, shooting star, doji, morning star and evening star take no parameters. Each is 1 on the bar that completes the pattern and 0 otherwise.
  - **No look-ahead.** Every new value depends only on bars up to the current one. A test checks that appending bars never changes earlier values.
  - **Research and compile prompts.** Both describe what can be expressed. The compiler gets a short guide to using these indicators.
  - **Schema version.** The schema stays at 1.0: the change only adds enum values, so existing strategy files remain valid.
- **Requirements affected:** FR-040 to FR-046.

## D-037 Strategy scorecards and "build me a better strategy"

- **Date:** 2026-10-01
- **Context:** The owner wants to see how effective each strategy is, swap out ineffective ones, and later have the app build a strategy from the historical results.
- **Decision:**
  - **Scorecards.** Each tested strategy has a scorecard with two parts:
    - Paper results come from executions of orders the strategy created that closed a position: closed trades, wins and losses, win rate, realized P&L, average/best/worst trade, drawdown of cumulative realized P&L, open positions and days active.
    - The latest completed backtest adds net return, max drawdown, trades, win rate and profit factor.
  - **Exact money.** Money is summed from exact decimal text.
  - **Sample-size warning.** Fewer than 20 closed trades triggers a plain warning that the results cannot yet tell skill from luck.
  - **Which strategies appear.** Drafts and strategies that failed validation are left out. The list is ordered by realized paper P&L, then backtest return.
  - **Build me a better strategy.** The app composes the opening message of a normal research conversation:
    - each tested strategy of the asset class, best first, with its plain-English rules and results;
    - at most six strategies, within the message limit.

    This needs at least two tested strategies.
  - **Normal research path.** The AI proposes a combination and the owner refines it. It is then compiled, validated and backtested like any research, so the AI never activates anything and the budget limits apply as usual.
  - **Why not an automatic optimizer.** A search that tunes rules to past results would overfit the owner's small trade history. An AI proposal that can be explained and then backtested keeps the owner in control.
- **Requirements affected:** FR-046, FR-060 to FR-064, FR-032 to FR-037.

## D-038 Modern look and charts

- **Date:** 2026-10-01
- **Context:** The owner asked for a more modern interface with graphs. They picked three: the portfolio equity curve, candlesticks with trade markers, and a strategy comparison. They had no preference on the overall look.
- **Decision:**
  - **Design system.** A dark "trading terminal" theme with a matching light theme, following the phone setting.
    - Tabular figures, rounded cards and pill-shaped chips, with gain and loss colours as tokens.
    - A header card for the headline number, custom icons, and a "PAPER" badge in the top bar.
    - Gains and losses still carry an arrow and a sign, not colour alone (NFR-009).
  - **Charts on Compose Canvas.** The charts are drawn with Compose Canvas, not a chart library: no new dependency, a small APK, and a themed, exact candlestick and trade-marker rendering.
    - Every chart has a spoken summary.
    - Dragging or tapping reads values. Horizontal drags scrub the chart; vertical drags still scroll the screen.
  - **Charts shown.**
    - Home: an equity sparkline.
    - Portfolio: an equity curve (1D, 1W, 1M, 3M, All) with the change over the range, and an allocation ring.
    - Positions open a full-screen candlestick chart with that portfolio's fills.
    - Strategy page: candlesticks for its symbols at its timeframe, with its own trades.
    - Scorecards: comparison bars (paper P&L, win rate, backtest return, drawdown, profit factor), cumulative paper P&L lines, and a sparkline per strategy.
  - **Motion and imagery.**
    - Screens slide and fade in; tabs cross-fade.
    - Charts animate in: lines draw left to right, candles grow from their midpoints before trade markers fade in, bars grow, and the allocation ring sweeps.
    - The headline number rolls up or down when it changes, and pills fade between states.
    - Shimmer placeholders replace spinners, and cards animate size changes.
    - Empty screens show small illustrations, and hero cards have a faint candlestick backdrop.
    - The app icon and splash screen are redrawn: candlesticks under a trend line on the terminal background.
    - All art is vector or drawn in code (no bitmaps) and is hidden from screen readers.
    - Animations follow the phone's "remove animations" setting.
  - **Engine endpoints.**
    - `GET /v1/charts/candles` returns recent bars plus markers from simulated executions. It can be filtered by portfolio or strategy. Stock look-back is widened for closed hours.
    - `GET /v1/charts/equity/{id}` returns the equity curve thinned to at most 400 points, keeping the first and latest.
    - Scorecards gain a cumulative realized-P&L series.
  - **Equity snapshots.** Equity snapshots used to be written only on fills and funding, too sparse for a curve. The engine now also writes one every 5 minutes of market time per active portfolio.
    - These are tagged `PERIODIC`.
    - Risk rules (daily-loss start equity and peak equity for drawdown) ignore them, so risk limits behave exactly as before.
- **Requirements affected:** FR-100 to FR-104, NFR-009.

## D-039 Gemini model changes no longer break the provider

- **Date:** 2026-10-01
- **Context:** The owner's Gemini key returned HTTP 404. Google no longer offered the preset model `gemini-2.5-flash` to that key. The app had no way to change a provider's model, and replacing the key left the old failed result on screen.
- **Decision:**
  - **New preset.** The Gemini preset is now the `gemini-flash-latest` alias.
  - **Automatic model switch.** When a Gemini test gets a 404, the app lists the models the key can use with `generateContent`. It picks the latest-Flash alias if offered, else the newest stable Flash, and avoids Lite, image, speech, live, embedding and experimental models. It saves that model and tests again, and the result says what changed.
  - **Clearer errors.** Error messages include the provider's own short explanation (never the key).
  - **Stale results cleared.** Replacing or removing a key clears the previous test result.
  - **Automatic testing.** The app tests a provider right after it is added, after a key is replaced, and after its model is edited.
  - **Editable model.** A provider's model can be edited on its card.
  - **Test size.** The connectivity test allows 1,024 output tokens, so models that think before answering still reply.
- **Requirements affected:** FR-004, FR-030.

## D-040 Research messages are limited by the AI budget, not a fixed 8,000 characters

- **Date:** 2026-10-01
- **Context:** The owner wants to paste longer material, such as articles, transcripts or trading rules, into research. The message box was capped at 8,000 characters, a figure left over from the old one-line research question.
- **Decision:**
  - **Budget-based limit.** A conversation message may be as long as fits the AI budget's input ceiling (maximum input characters) together with the app's instructions and the tradable-symbol context. The default ceiling is 60,000 characters, which leaves about 50,000 for the message; the ceiling can be raised up to 400,000 in More → AI budget.
  - **Oldest turns dropped first.** Earlier turns are still dropped, oldest first, to make room; the new message is never cut.
  - **Clear refusal.** A message over the limit is refused with `message-too-long`, which gives its length, the limit and how to raise the ceiling.
  - **Live counter.** The app shows a character counter against the limit (`GET /v1/research/limits`) and disables sending when a message is over it.
  - **Spending limits unchanged.** Monthly cost and daily request limits still apply to every call.
- **Requirements affected:** FR-032, FR-037.

## D-041 Research done elsewhere: import it, or have your own AI write the strategy

- **Date:** 2026-10-01
- **Context:** The owner researches strategies in other AI tools, for example Claude.ai on their Pro plan, and wants to bring that research in. Sending a long message in a Gemini free-tier conversation hit Google's quota: HTTP 429, which the app reported without Google's reason.
- **Decision:**
  - **Clearer quota errors.** Research failures now show the provider's own explanation, such as which quota ran out and when to retry, under a plain summary.
  - **Use your own AI.** On Create / import, **Copy instructions** puts a prompt on the clipboard. It contains the strategy schema, the pattern guide and the tradable symbols, and asks for one JSON object with `createdBy` set to IMPORTED.
    - The owner pastes it with their research into their own AI chat, then pastes the reply back.
    - The importer takes the JSON from a fenced block, or from the first `{` to the last `}`, so surrounding chat is ignored.
    - The regular validator decides, exactly as for any import. No app AI request or cost is involved.
  - **Import research.** On the research screen, the owner can paste research of any length, up to 2,000,000 characters. The owner supplied the text, so it counts as reviewed.
    - If it fits one compile request, it is recorded as the research (no AI call) and compiled directly.
    - Otherwise it is split at paragraph, line or sentence breaks into parts that fit the budget's input ceiling. The AI extracts the trading rules from each part, one request at a time, without web search. The extracts are then compiled together.
    - A failure (for example a quota error) pauses the import; **Try again** resumes at the failed part.
    - Budget ceilings apply to every request. Migration 4 adds `research_sessions.import_chunk`.
- **Requirements affected:** FR-032 to FR-037.

## D-042 The rest of the Chart Champions method: both directions, levels, VWAP, Fibonacci, volume profile, trade management

- **Date:** 2026-10-02
- **Context:** A strategy compiled from the owner's Chart Champions research left out most of the method: monthly/weekly/daily levels, VWAP, Fibonacci, volume profile, short setups at range highs, partial profits, 1% risk sizing and the 3-losing-trades rule. The owner asked for these to work fully, with real data, in backtests and in live paper trading.
- **Decision:**
  - **Both directions.**
    - `metadata.direction` can be `BOTH`: `entryRules` enter longs, `shortEntryRules` enter shorts, and `exitRules.conditions` and `exitRules.shortConditions` exit each side.
    - If the long and short rules both fire on the same bar, no trade is taken.
    - The backtester tracks long or short per position.
    - Live evaluation reads the side from the strategy's lots.
  - **Simulated crypto shorts.** Crypto instruments are now shortable in simulation, perpetual-futures style, with the daily borrow fee standing in for funding (migration 5). They still need the portfolio's shorting switch, which is new on the Portfolio screen and needs the device lock. Activating a strategy that can short is refused, with that reason, until the switch is on. Activation refusals now list their reasons.
  - **Calendar periods.** Crypto days are UTC days; US stock days are New York days; weeks start on Monday.
    - `PERIOD_LEVELS` with `anchor` DAY/WEEK/MONTH gives the current period's open/high/low so far and the previous period's open/high/low/close/midpoint (EQ).
    - Most indicators accept `timeframe` 1d/1w/1M and are then computed on completed daily/weekly/monthly bars built from the strategy's bars.
    - A period counts as complete only when a bar closes at its end or a later period's bar arrives, so nothing uses future data. A test checks every new indicator against appended bars.
  - **VWAP.**
    - `VWAP` with `anchor` is the session VWAP for that period, including the current bar.
    - `ANCHORED_VWAP` starts at the lowest low or highest high of the previous N bars.
  - **Fibonacci.** `FIBONACCI` gives 0.236/0.382/0.5/0.618/0.66/0.786 retracements of the previous N bars' range, measured from its most recent extreme, plus the high, the low and the move's direction.
  - **Volume profile.** `VOLUME_PROFILE` gives the point of control and 70% value area of the previous N bars or the previous complete period.
    - It is estimated from candle volume spread evenly over each bar's range in 50 rows.
    - It approximates tick-level tools, and the app says so.
  - **Trade management.**
    - `exitRules.partialTakeProfit` closes a percentage of the position at a first target, and can move the remaining stop to the entry price (exit reason BREAKEVEN_STOP).
    - `positionSizing.method` RISK_PERCENT sizes each trade so the stop loss costs that percent of equity.
    - The risk-sized position is reduced to fit the strictest limit (the strategy's `maximumPositionPercent` and the risk profile's per-trade percent and value) instead of being refused.
    - `riskLimits.maximumDailyLosingTrades` stops new entries after that many losing trades in a New York day.
  - **History.** `minimumHistoryBars` is raised automatically to what the indicators need, with a warning instead of a failure. More than 5,000 bars is refused with advice.
  - **AI instructions.** The research, compile and copy-instructions prompts describe all of the above and ask the AI to express both directions instead of omitting them.
  - **Not covered.** Open interest, funding, delta/CVD and Elliott Wave remain outside the strategy language. The first three need a futures data source (next step).
- **Requirements affected:** FR-040 to FR-047, FR-050 to FR-053, FR-060 to FR-066, FR-091.

## D-043 The owner's research AI completes the strategy itself and reports what it looked up

- **Date:** 2026-10-02
- **Context:** The owner runs research in an AI with web search, then pastes "Copy instructions" into the same chat. The research often lacked the detail to pin down every rule, so the AI guessed or left parts out, and the imported strategy was not faithful to the method.
- **Decision:**
  - **The prompt makes the AI self-complete.** It now works in four steps:
    1. Express the researched strategy in the schema.
    2. Go through a checklist (markets and timeframe, long entry, short entry, no-trade conditions, stop, targets and partials, holding time, sizing, risk limits). Any point without an exact condition or number is researched with the AI's own web search, preferring the method author's material. The AI must not guess or ask the owner.
    3. Write the JSON. It uses the method's own risk and sizing rules; a conservative value is allowed only when nothing is found, and it must be declared. If the entry rules cannot be established, no JSON is written.
    4. After the JSON, add three fixed sections: RULE READBACK (every rule with its numbers), FURTHER RESEARCH (point, what was lacking, what was found, source) and STILL MISSING (point, what is missing, why, the value used). The AI must say if it has no web search.
  - **The notes are kept and shown.**
    - The phone sends everything in the pasted reply outside the JSON as `notes` (`POST /v1/strategies`).
    - The engine stores the notes on the strategy (migration 6 `strategies.import_notes`), cleaned of control characters and capped at 30,000 characters.
    - The notes are split into the three sections, whatever heading style the AI used, and returned as `importNotes` with the strategy.
    - The strategy page shows a "From your research AI" section next to the app's own "How it works", so the readback can be checked against what will actually run.
    - Points still missing raise a warning banner at the top of the page.
  - **Not trusted.** The notes are display-only text. They are never run, validated as rules or sent to another AI. The JSON alone decides the strategy, through the same validator.
- **Requirements affected:** FR-030 to FR-036.

## D-044 Perpetual-futures context for crypto strategies: open interest, funding and CVD from Kraken Futures

- **Date:** 2026-10-02
- **Context:** The Chart Champions method reads open interest, funding and delta/CVD, which spot candles cannot provide (D-042 left them out). The owner approved Kraken Futures' public data, which needs no account or key and serves Canada.
- **Decision:**
  - **Source.** `KrakenFuturesProvider` reads three public endpoints:
    - open interest from `/api/charts/v1/analytics/{contract}/open-interest`;
    - taker buy-minus-sell volume (delta) from `/api/charts/v1/analytics/{contract}/aggressor-differential`, paged with `since`, `to` and `interval` set to the strategy's bar length;
    - hourly funding from `/derivatives/api/v4/historicalfundingrates`, as `relativeFundingRate`, shown in percent per hour.

    Spot symbols map to perpetuals: BTC-USD becomes PF_XBTUSD, other XXX-USD pairs become PF_XXXUSD. Analytics values are read tolerantly (plain numbers, OHLC objects with close, buy/sell columns). Anything unrecognised is reported with its field names, never guessed.
  - **Not verified against live responses.** This build environment's network policy blocked `futures.kraken.com`. The formats come from Kraken's documentation summaries and the ccxt library's recorded responses. The owner checks the real feed with **More → Engine → Test futures data**.
  - **Lined up with the bars without look-ahead.**
    - Open interest uses the latest value stamped at or before the bar's open. It is treated as missing after three bars (at least an hour) without data.
    - Funding uses the latest rate stamped at or before the bar's open. It is treated as missing after 25 hours.
    - Delta sums every interval that starts inside the bar.
    - A test checks that adding later data never changes earlier values.
  - **Indicators (crypto only).** Using them in a stock strategy is refused with DERIVATIVES_CRYPTO_ONLY.
    - `OPEN_INTEREST` (period N): `value` and `change` (% over N bars).
    - `FUNDING_RATE`: `value` (% per hour) and `annualized`.
    - `CVD` (period N): `value` (delta summed over N bars) and `delta` (this bar).

    They are not available on higher timeframes.
  - **Backtests.** A strategy using these indicators loads futures data for its symbols. Unavailable or entirely missing data is a critical integrity issue (FUTURES_DATA_UNAVAILABLE / FUTURES_DATA_MISSING). Partial coverage is a warning naming how many bars are covered. The dataset records the source.
  - **Live paper trading.** Each evaluation loads the data, cached for 50 seconds. If the source fails, evaluation is blocked as PROVIDER_UNAVAILABLE; if a series has nothing for the last three bars, it is blocked as MISSING_HISTORY. Both notify the owner like other data failures.
  - **Demo mode.** `ReplayDerivatives` derives synthetic values from the replay candles: delta from each candle's body, open interest as slow waves that build with one-sided bars, and funding from the premium over the one-day average. Demo strategies can exercise the rules; the values say nothing about real markets.
  - **AI instructions.** The research, compile and copy-instructions prompts describe the three indicators with examples.
- **Requirements affected:** FR-040 to FR-047, FR-050 to FR-053, FR-060 to FR-066.

## D-045 Trading plans replace single strategies

- **Date:** 2026-10-02
- **Context:** Traders such as Chart Champions use a method made of several setups, each valid in certain market conditions and each with its own trade management, under overall risk rules. One strategy object either merged those setups (with shared exits and sizing) or dropped them. The owner chose a container of setups ("trading plan"), with configurable conflict and capital policies (defaults: one position per symbol and shared capital), and asked to start fresh rather than migrate existing strategies.
- **Decision:**
  - **Format.** A trading plan is a strategy document with `schemaVersion` "2.0" (`trading-plan-schema-2.0.json`) containing:
    - plan metadata, universe and indicators (declared once, up to 40);
    - `context.longWhen` / `context.shortWhen`: the market bias that allows each side;
    - `planRules`: `conflictPolicy` ONE_PER_SYMBOL (default) or STACK, `capitalPolicy` SHARED (default) or ALLOCATED, and `maximumOpenRiskPercent`;
    - 1 to 8 `setups`, each with id, name, description, priority, direction, optional `appliesWhen`, `allocationPercent` and `maximumOpenPositions`, and its own entries, exits and sizing;
    - plan-wide orders, risk limits and inactivity conditions.
  - **One engine for both.** Each setup is turned into a complete 1.0 strategy document carrying the plan's metadata, universe, the indicators it uses, orders and risk. Every setup therefore goes through the same validation, safety scan and rule evaluation as a single strategy. Context and applies-when groups are checked as entry rules. Plan-level checks cover unique setup ids, allocations (required and at most 100% in total when ALLOCATED) and unknown fields. Issues point at the setup or context they belong to. A 1.0 strategy is treated as a plan with one setup ("MAIN").
  - **Backtests.**
    - Each position belongs to the setup that opened it and is managed by that setup's stops, targets, partials, holding time and exit rules.
    - Each bar, held positions are managed first. Then setups are tried in priority order.
    - ONE_PER_SYMBOL: the first setup to fire takes the symbol.
    - STACK: each setup may hold its own position in the symbol, same direction only.
    - ALLOCATED: a setup sizes from, and is capped at, its share of equity. The open-risk cap blocks entries that would put more than the cap at risk to the stops.
    - Trades record their setup. Backtest metrics include per-setup trades, win rate, net P&L and profit factor.
  - **Live paper trading.**
    - Signals record their setup (migration 7 `signals.setup_id`, `backtest_trades.setup_id`). Orders reach it through their signal, and lots through their opening order.
    - Holdings are computed per setup. A closing order relieves its own setup's lots first, so stacked setups keep separate positions.
    - At most one signal per symbol per bar, exits first. A second stacked entry therefore comes on a later bar.
  - **Explanations and results.** "How it works" describes the context, policies, each setup in priority order and plan-wide risk. Scorecards and the strategy page show paper and backtest results per setup, and the "build a better plan" brief includes them.
  - **Editing rules.** The strategy page shows the setups and lets the owner change the conflict policy, capital policy and open-risk cap. Saving creates a new version, which needs a new backtest.
  - **AI.** Copy instructions, the research compile and the memo compile all ask for a trading plan, using the plan schema and a plan-writing guide. The readback is per setup.
  - **Starting fresh.** Migration 7 ends every active strategy and archives every existing strategy with the reason "Retired: replaced by trading plans (1.8.0)". Strategy versions are immutable and orders reference them, so they are retired, not deleted. Portfolios, orders, trades and open positions are kept; positions those strategies opened stay in the portfolio as ordinary holdings.
- **Requirements affected:** FR-040 to FR-047, FR-050 to FR-053, FR-060 to FR-066, FR-091.

## D-046 Copy instructions for evidence-graded research; the owner's Chart Champions research as a test case

- **Date:** 2026-10-03
- **Context:** The owner's detailed Chart Champions research labels each rule Published, Legacy, Observed, Proposed or Unknown. It finds that many setups have unpublished triggers, that statistics such as "80%" are not rules, and that the opening range breakout is a stock-session setup. Under the 1.8.0 prompt, an unknown trigger in any one setup meant "no JSON at all", and the AI could silently swap in a different timeframe, level or percentage stop.
- **Decision:**
  - **Copy instructions now ask the AI to:**
    - include a setup only when the method defines its trigger, and list the rest under STILL MISSING with the missing field;
    - write no JSON only when no setup qualifies;
    - never turn statistics into thresholds;
    - list setups for another market as not applicable;
    - never substitute silently: an approximation is marked and says what it replaces;
    - start every readback line with [Published], [Legacy], [Observed], [Proposed] or [Approximation].

    The plan guide shows how to write "at least two of A, B, C" (ANY of the ALL pairs). The in-app compile prompt gets the same rules, with approximations named in the description. The strategy page counts the [Approximation] lines and shows a warning.
  - **Test case.** The owner's research, turned into a plan reply as an AI following these instructions would write it (`research/chart_champions_plan_v1_reply.md`), now runs through the app's own paste path. It covers the readback labels, validation, explanation, plan setups and a demo backtest per setup.
  - **Real-data research harness.** `RealDataResearchTest` replays a plan on real BTC/USD 1-minute history when SF_REAL_BTC_CSV is set; CI skips it. Results are printed for design decisions, never asserted. On Bitstamp data from July 2025 to October 2026, plan v1 (1h bars, percentage stops):
    - lost 14.6%, and the 15% drawdown limit then stopped it;
    - made 57 trades with a 42% win rate and a profit factor of 0.47;
    - SFP had 50 trades, half of them stopped out at the 1.2% stop.

    Real 1-hour SFP wicks at the prior-day level are a median 0.4% from the close (90% within 1.2%). These numbers guide the fidelity work: stops at the wick, targets at levels, and 30-minute confirmation.
  - **Two validator bugs found by this research and fixed.**
    - Conditions on different bars ("close above now, below two bars ago") were reported as contradictory. The contradiction check now accounts for offsetBars.
    - A setup that needed more history than can be loaded (HISTORY_TOO_LONG) passed validation inside a plan, and its entries were then silently blocked. The issue is now reported for that setup.
- **Requirements affected:** FR-030 to FR-036, FR-041 to FR-043.

## D-047 Fidelity release: chart-level trade management, multiple timeframes, richer rules and levels

- **Date:** 2026-10-03
- **Context:** The owner's Chart Champions research (D-046) describes stops beyond the wick, targets at structural levels, size from the stop distance, higher-timeframe levels with lower-timeframe triggers, 30-minute acceptance closes, "two or more confluences", naked POCs and round numbers. In 1.8.1 all of these had to be approximated. Plan v1, built from that research, lost 14.6% on real BTC data.
- **Decision:**
  - **Chart-level trade management** (exitRules, single strategies and plan setups):
    - **Stops.** `stop` / `shortStop` sit at `SIGNAL_WICK` (the signal candle's low or high) or at any level, plus `bufferPercent`. `stopLossPercent` becomes the farthest stop allowed; a farther stop means no trade.
    - **Targets.** `targets` / `shortTargets` allow up to 3, each at a level or an `rMultiple`, with `closePercent`; the last target closes the rest. `takeProfitPercent` stays as an outer cap.
    - **Management.** `breakevenAfterTarget` moves the stop to entry after that target. `trailing` {swingPeriod, afterTarget} ratchets the stop to each new confirmed swing. `minimumRewardRisk` filters entries.
    - **When no trade.** A stop or level target on the wrong side, or a stop beyond the limit, means no trade.
    - **Sizing.** RISK_PERCENT sizes from the actual stop distance, and the plan's open-risk cap uses it too.
    - **Fixed at the signal.** Stop and targets are fixed when the signal fires (`ExitPlan`). Backtests manage them inside each bar, assuming the stop fills first. Live trading stores them on the entry signal (migration 8 `signals.exit_plan`) and works out targets already taken from the quantity closed; the stop never loosens.
  - **Timeframes.**
    - 30-minute bars: aggregated from 15-minute or 1-minute data; Twelve Data `30min`; Kraken 1800 s.
    - 30m, 1h and 4h periods: fixed buckets (crypto from midnight UTC, US stocks from the 09:30 open). They serve as `anchor` (PERIOD_LEVELS is then the higher-timeframe candle, plus VWAP and profiles) and as indicator `timeframe`. The period must be longer than the strategy's bars.
    - A setup's `decisionTimeframe` ("30m" … "1w") makes it decide only on bars that complete that period.
  - **Rules.**
    - Group operator `AT_LEAST` with `count`, for confluence.
    - Condition `withinBars` / `minimumBars`: held on at least m of the last n closed bars.
    - Windowed conditions never count as contradictions, and they extend the history requirement.
  - **Levels.**
    - `LEVEL` {from, to, ratio}: quartiles, any Fibonacci ratio or extension, range multiples. It is computed after the levels it uses; plan setups bring those levels along.
    - `ROUND_NUMBER` {step}: the round numbers just below and above the close.
    - `NAKED_POC` {anchor, period}: the nearest untested POCs below and above the close, from the last N complete periods. A POC counts as tested only by bars before the current one.
  - **AI instructions** describe all of the above. The plan schema is sent compact, halving its share of the AI budget.
  - **Bug found by the research.** Indicators used only by a stop or target were dropped from a setup's own strategy.
  - **Real-data research (Bitstamp BTC/USD, July 2025 to October 2026, opt-in harness).** Plan v2 is the owner's research expressed with these features: SFP, failed auction, CCV, CC Fibonacci and the 4h EMA swing, on 30m bars, with no approximations left.

    | Run | Result | Trades | Profit factor |
    |---|---|---|---|
    | v2, default app costs (0.2% spread) | −16.7% | 227 | 0.73 (v1: 0.47) |
    | v2, no costs | +4.3% | n/a | 1.05 |
    | v2, perpetual-futures costs (0.02% spread, 0.05% fee) | −12.6% | n/a | n/a |

    - With futures costs, the 4h EMA swing won 4 of 5 trades (+$6,640), while the mechanical SFP lost on 244 trades.
    - Neither published filter (2R minimum, SFP judged on the 4h candle) made the level-reaction setups profitable; it only moved trades between setups.
    - Conclusion recorded for the owner: the discretionary level-reaction judgement cannot be recovered from public rules, and trading costs dominate tight-stop setups. The trend setup is promising on a small sample. These are research findings, not trading advice.
- **Requirements affected:** FR-040 to FR-047, FR-050 to FR-053, FR-060 to FR-066.
