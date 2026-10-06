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

## D-048 Plan v3: Chart Champions plan tuned on real 2025 BTC data and checked on 2026

- **Date:** 2026-10-03
- **Context:** The owner asked for the Chart Champions plan to be tuned on historical crypto data until its returns line up with the profits Chart Champions published:

  | Period | Net profit | Win rate | Streams |
  |---|---|---|---|
  | 2025 | $96,413 | 66% | 104 |
  | Q1 2026 | $124,817 | 100% | 30 |
  | Q2 2026 | $49,614 | 85% | 33 |

  The starting balances are "Not Publicly Disclosed", so no percentage return can be derived from those figures.
- **Decision:**
  - **Tuning target.** Profitable in each period with a sensible drawdown, compared alongside their win rates. An exact dollar or percentage match was not the target, because one can always be produced by changing the risk per trade without saying anything about the method.
  - **Overfitting guard.** Choices were made on 2025 only; Q1, Q2 and Q3 2026 were run unchanged afterwards.
  - **Sweep.** About 150 variants of plan v2 on Bitstamp BTC/USD 30-minute bars with perpetual-futures costs. Variables: trend filter, confluence count, stop buffer and targets per setup, then setup combinations, conflict policy and open-risk cap.
  - **Findings:**
    - No SFP or CCV variant was profitable in 2025.
    - The best failed-auction variant made +9.9% in 2025, then lost 13–15% in every 2026 quarter. It was rejected as a textbook overfit.
    - The CC Fibonacci setup works with at least 2 confluences, a stop 0.3% beyond the 0.786 level, and targets at 1R (half) and 3R.
    - The 4h EMA swing was positive in every period as published.
    - Letting both setups hold a position (STACK) beat ONE_PER_SYMBOL in 2025.
    - An open-risk cap of exactly 2 × the per-trade risk blocked some second entries, because sizing is measured at the signal close; plan v3 uses 2.5 ×.
  - **Plan v3** (`research/chart_champions_plan_v3.md`, imported in LocalApiAppTest) at 1% risk per trade:

    | Period | Return | Max drawdown | Positions | Win rate (per position) |
    |---|---|---|---|---|
    | 2025 (tuning) | +10.9% | 6.5% | 73 | 56% |
    | Q1 2026 | +10.4% | 4.0% | 25 | 60% |
    | Q2 2026 | +3.6% | 5.7% | 24 | 54% |
    | Q3 2026 | +0.7% | 5.7% | 29 | 48% |

  - **Comparison with the published figures.**
    - Per quarter, their profits rank Q1 2026 ($125k) above Q2 2026 ($50k) and above the 2025 quarterly average ($24k). v3 ranks the same way: +10.4%, +3.6% and +2.7%.
    - Position counts are of the same order as their streams.
    - Their win rates (66–100%) are higher than any mechanical variant reached. Their 100% quarter cannot be checked without the trades.
  - **Harness.** `RealDataResearchTest` gains a sweep over a directory of plans (SF_SWEEP_DIR) and per-position win rates. It is opt-in; CI skips it.
- **Requirements affected:** FR-040 to FR-047.

## D-049 Built-in plans: BTC trend pullback found in real history, Chart Champions v3 and a combined plan

- **Date:** 2026-10-03
- **Context:** The owner asked for:
  - Plan v3 to be saved so it can be loaded into a slot.
  - Patterns in real crypto history from 2025 to now that could become consistently profitable strategies, and when each should be applied.
  - A high-win-rate strategy for a second slot.
  - Results from a $10,000 start.
- **Pattern research (Bitstamp BTC/USD, Jan 2025 – Oct 3 2026).**
  - **Method.** Families were scanned in Python on daily, 4h and 1h bars with the app's default costs: trend following (SMA/EMA, Donchian), RSI(2) mean reversion with and without trend filters, Bollinger reversion, and overbought shorts. Rules were chosen on 2025 and checked unchanged on 2026. The winner was then rebuilt in the strategy language and confirmed with the app's own backtester.
  - **The market.** BTC rose to $126k (Oct 2025), fell 54% to $57.7k (Jul 2026) and recovered to $84.7k.
  - **Findings:**
    - Unfiltered mean reversion works in one year and fails in the other.
    - Daily trend following is positive in both years but wins only about 35% of the time.
    - Dips bought, and spikes sold, only in the direction of the daily trend win often in both years.
  - **Chosen rule (BTC trend pullback, 4h bars).** If yesterday's daily close is above the 50-day SMA, buy when RSI(4) < 10; if it is below, short when RSI(4) > 90. Exit when RSI(4) crosses 50, at a 5% stop, or after 60 bars. Size: 95% of equity.
  - **Robustness.** Every neighbour tested was positive in 2026: 40–50-day SMA, RSI threshold 10–12, and an 8% stop.
  - **When to apply.** The daily-trend switch is the regime rule: the plan buys dips in up-trends and sells rallies in down-trends by itself. On Oct 3 2026 BTC ($84.7k) has been above its 50-day SMA ($78.6k) since Aug 16, so the plan is in buy-the-dip mode.
- **Results at $10,000 with the app's default costs:**

  | Plan | 2025 | 2026 to Oct 3 | Jan 2025 – Oct 2026 |
  |---|---|---|---|
  | BTC trend pullback | +17.2%, 21 trades, 86% won | +6.6%, 14 trades, 79% won | +25.0%, max drawdown 5.2%, 83% won |
  | Chart Champions v3 | +6.1% | +9.8% | +13.1%, max drawdown 12.4% |
  | Combined (v3 50%, pullback 50%) | +7.2% | +3.3% | +9.9%, max drawdown 3.6% |

  - BTC itself fell 13% over the same span.
  - At $10,000 v3 had fewer volume-limited partial fills than at $100,000, which changes its quarterly figures slightly.
- **Decision:**
  - **Library.** Three built-in plans are bundled under `/library`; `PlanLibrary` loads them.
  - **API.** `GET /v1/library` lists them with their backtests and the strategy already added from each. `POST /v1/library/{id}/add` imports one through the normal validator, so it is versioned and slot-able like any other strategy.
  - **App.** The Plans screen has a "Built-in plans" section with "Add to my plans".
- **Slots.** There is one crypto slot (D-035), so v3 and the trend pullback cannot run side by side as separate strategies. The combined plan runs both as setups with ALLOCATED capital, and the slot reports each setup's results separately. A second crypto slot would need separate portfolios for each slot and was left for the owner to decide.
- **Harness.** The sweep takes SF_START (starting capital) and SF_COSTS=app (the app's default costs), picks the bar size from the plan's timeframe, prints validator warnings, and adds 2026-YTD and whole-span periods.
- **Version.** 1.10.0 (versionCode 14).
- **Requirements affected:** FR-040 to FR-047, FR-060 to FR-066.

## D-050 Research extended to 2018–2026: the 4-hour pullback is withdrawn and replaced by the daily trend dip and rip

- **Date:** 2026-10-03
- **Context:** The owner asked for the BTC history to be expanded, not summarized. Bitstamp 1-minute BTC/USD data back to 2012 was available, so the research now covers January 2018 to October 3 2026: 4.6 million rows with no gaps, spanning two full bull/bear cycles plus the 2025–2026 cycle. The full history is written up in `docs/research/BTC_HISTORY_2018_2026.md`.
- **Findings:**
  - **The D-049 4-hour trend pullback** was chosen on 2025 alone. It lost money in 2018, 2019, 2022, 2023 and 2024, and halted at its 35% drawdown limit over 2018–2026. Its 2025–2026 results came from a range-bound market. It is **withdrawn**.
  - **Chart Champions v3** was profitable in 4 of 9 calendar years. Over 2018–2022 and 2023–2026 it reached its 20% drawdown limit. It stays in the library, at the owner's request, with that record shown.
  - **Search method.** Selection used 2018–2022 only and testing used 2023 – Oct 2026; the search covered about 2,160 dip-buy and spike-short rules on daily and 4h bars.
  - **What failed and what held.** Most rules that ranked best on 2018–2022 lost money after 2023; long-term filters (100- and 200-day averages) failed in particular. The daily-bar family with a 40–60-day trend filter held in both halves.
  - **Plan chosen: BTC daily trend dip and rip** (the 40-day version ranked best on 2018–2022):
    - **Long:** close above its 40-day SMA and RSI(2) < 15.
    - **Short:** close below its 40-day SMA and RSI(2) > 85.
    - **Exit:** close back across the 10-day SMA, an 8% stop, or 30 days.
    - **Size:** 95% of equity.
  - **App backtester, $10,000, default costs:**
    - **By year:** 2018 −15.3%, 2019 +27.0%, 2020 +48.5%, 2021 +53.6%, 2022 +19.5%, 2023 +5.7%, 2024 −3.0%, 2025 +20.2%, 2026 +2.4%.
    - **2018–2022:** +193%. **2023–2026:** +26%.
    - **Whole span:** +270%, max drawdown 21.6%, 160 trades, 73% won. BTC over the same span: +530%, with an 81% drawdown.
  - **Neighbours** (50- and 60-day SMA) were also profitable in 7 of 9 years.
- **Decision:**
  - The library is now `btc-daily-trend-dip-rip` and `chart-champions-v3`. Each shows every calendar year plus the selection, unseen and whole spans.
  - The combined plan is removed. A daily setup inside a 30-minute plan would check its exits on every 30-minute bar, which would change the rule, so it was not rebuilt.
  - Running two crypto plans side by side needs a second crypto slot. That is left for the owner to decide.
- **Harness.** SF_PERIODS=years adds each calendar year and the 2018–2022, 2023–2026 and whole spans.
- **Version.** 1.10.1 (versionCode 15).
- **Requirements affected:** FR-040 to FR-047, FR-060 to FR-066.

## D-051 Ten slots per asset class, each able to use its own paper portfolio

- **Date:** 2026-10-03
- **Context:** The owner wants several plans running side by side, for example Chart Champions v3 and the daily dip and rip on BTC, and asked for up to 10 crypto and 10 stock slots.
- **Decision:**
  - **Slots.** Each asset class has slots 1–10.
    - Migration 9 adds `strategy_activations.slot` and numbers any active activation per asset class.
    - Activation takes an optional slot. Without one, a strategy keeps its current slot or takes the first free one.
    - When all ten are in use the owner must choose a slot (`slots-full`). Choosing an occupied slot replaces its strategy, with the D-035 keep-or-close question (`slot-occupied`, now including the slot number).
    - On KEEP, only positions in the portfolio the new strategy trades are handed to it; the rest stay open and are reported.
  - **One owner per position.** Two running strategies may not trade the same symbol in the same portfolio (`symbol-shared`), so lots never mix between strategies. Strategies that share a symbol each use their own portfolio.
  - **Portfolio cap.** Active paper portfolios rise from 10 to 25 (a portfolio per slot plus manual ones).
  - **API.** `/v1/slots` returns all 20 slots with `number`. Activations carry `slot`, and `POST /v1/strategies/{id}/activate` accepts `slot`.
  - **App.**
    - "Running now" lists occupied slots per asset class ("Crypto · 2 of 10 slots in use").
    - The activation panel has a slot picker (• marks slots in use) and a "New portfolio" button. It creates "Crypto slot N" with the chosen starting cash (default $10,000) and turns on simulated shorting when the strategy can short, then selects it with allocation 100%.
- **Tests:** StrategySlotsTest (next free slot, shared-symbol refusal, slots full, invalid slot, explicit replacement); LocalApiAppTest D-051 through the app's calls; the portfolio cap test uses the new limit.
- **Version:** 1.11.0 (versionCode 16).
- **Requirements affected:** FR-070 to FR-075.

## D-052 Stock research on 1998–2021 data, the dip score, trend cores and a slot plan

- **Date:** 2026-10-03
- **Context:**
  - The owner asked for 10 years of stock data, more simulations to find winning and novel strategies, and a plan built from the results.
  - This environment's network policy blocks every stock-data provider tried: Yahoo, Stooq, Twelve Data, Nasdaq Data Link, Alpha Vantage and Tiingo. The owner was told how to allow one.
  - QuantConnect's public sample data on GitHub was reachable: daily SPY, QQQ, IWM, AAPL, IBM, BAC, AIG and GOOG, 1998 to March 2021, adjusted with its factor files.
- **Method.**
  - Rules were scanned in Python, selected on 1998–2012 and run unchanged on 2013 – Mar 2021, then confirmed in the app's backtester through a new opt-in stock sweep (`SF_STOCK_DIR`).
  - BTC rules used the 2018–2026 data (D-050): selection on 2018–2022, unseen 2023–2026.
- **Findings:**
  - **Dip score (new composite).** Count of 4 oversold signals: RSI(2) < 10, close in the bottom 20% of the day's range (a LEVEL from LOW to HIGH at 0.2), close below the lower Bollinger band, and a 10-day closing low.
    - Above the 50-day average, at least 3 of 4: 1.25% per trade and 73% won in 1998–2012; 0.69% per trade and 74% won in 2013–2021; positive on all 8 symbols in both spans.
    - It beats each single signal and does not transfer to BTC.
  - **Rejected stock rules:**
    - Weekly or monthly failed breakdowns and reversal days were not consistent.
    - Narrow-range and 55-day breakouts decayed after 2012.
    - Unfiltered mean reversion lost in bear markets.
  - **SPY trend core** (hold above the 200-day average): 1998–2021 +224%, max drawdown 26.7%.
  - **BTC trend cores:**
    - Long-only above the 100–150-day average held in both halves.
    - Long-and-short versions that led in 2018–2022 collapsed afterwards.
    - The 100-day core was chosen by return per unit of drawdown on 2018–2022. Results: 2018–2022 +531%, 2023–2026 +231%, whole span +1,985%, max drawdown 37.2%, 23% of trades won.
- **Engine findings while testing:**
  - Daily equity orders need GTC, because DAY orders signalled at the close expire before the next open.
  - The market calendar covers 2023–2027 only. Backtests before 2023 still run, but live sessions and holidays outside that range are unknown.
- **Decision:**
  - **Library:**
    - `btc-trend-core`, `btc-daily-trend-dip-rip`, `chart-champions-v3`, `index-dip-score` (SPY, QQQ, IWM; 2 of 4 signals, up to 3 positions) and `spy-trend-core`.
    - Each shows its selection, unseen and whole spans plus every calendar year.
    - The stock plans say their data ends in March 2021.
  - **Slot plan** (`docs/research/STRATEGY_RESEARCH_2026-10.md`), each slot in its own portfolio:
    - Crypto 1: BTC trend core. Crypto 2: dip and rip. Crypto 3: Chart Champions v3.
    - Stock 1: S&P 500 trend core. Stock 2: Index dip score.
  - **Pending.** Re-test the stock plans on April 2021 – October 2026 once a stock-data host is allowed.
- **Version:** 1.12.0 (versionCode 17).
- **Requirements affected:** FR-040 to FR-047, FR-060 to FR-066.

## D-053 Stock data 2015–2026 from Yahoo Finance: second unseen test and the large-cap dip score

- **Date:** 2026-10-04
- **Context:** The owner allowed query1/query2.finance.yahoo.com, fc.yahoo.com and stooq.com. Yahoo's chart endpoint works without a key; Stooq now needs a browser.
- **Data:**
  - Daily bars from January 2 2015 to October 2 2026 for all 36 stock symbols in the instrument master (BRK-B is stored as BRK.B).
  - Split- and dividend-adjusted with Yahoo's adjusted close.
  - Kept in the research scratch area, not committed.
- **Findings:** the plans of D-052 were run unchanged on 2021 – Oct 2026, which played no part in choosing them. Results at $10,000 with the app's costs:

  | Plan | 2021 – Oct 2026 | 2015 – Oct 2026 |
  |---|---|---|
  | S&P 500 trend core | +87.1%, max drawdown 18.7% | +166%, max drawdown 19.1% |
  | Index dip score | +14.0%, max drawdown 3.5%, 71% won | +18.5% |
  | SPY buy and hold | +125%, max drawdown 24% | +355%, max drawdown 34% |

  - **Large-cap dip score (new).** The same rule (2 of 4 signals) across the 31 large stocks, up to 5 positions at 19%. Chosen among four variants (2 or 3 signals; 5 or 10 positions) on 2015–2020 at +45%. Then 2021–2026 +25.1%; 2015–2026 +80.3%, max drawdown 13.5%, 1,045 trades, 64% won.
  - The patterns held out of sample but did not beat buy and hold in this bull decade. The trend core trades return for smaller drawdowns.
- **Decision:**
  - The library adds `large-cap-dip-score`.
  - The stock entries now show 2015 – Oct 2026 results by year, with the 2021–2026 span marked as never used to choose the rule, plus a buy-and-hold comparison.
  - The slot plan adds Stock 2: Large-cap dip score.
- **Version:** 1.13.0 (versionCode 18).
- **Requirements affected:** FR-040 to FR-047, FR-060 to FR-066.

## D-054 TSX research: four rule-based portfolio plans (research only, not yet in the app)

- **Date:** 2026-10-04
- **Context:** The owner asked for 10 years of TSX data and four plans:
  1. long-term high dividend, shown both with dividends reinvested (DRIP) and as monthly dividends plus monthly value;
  2. short-term high dividend;
  3. long-term diversified, any vehicle;
  4. short-term diversified, any vehicle.
- **Data:**
  - Yahoo Finance `.TO` chart API: daily prices, dividends and splits for 175 TSX listings from October 2014 to October 2 2026, all quoted in CAD.
  - Coverage: about 120 stocks across 12 sectors and 53 ETFs (equity, sector, bond and gold).
  - Companies taken over that Yahoo no longer serves are a known survivorship bias.
- **Method:**
  - Python simulator (`docs/research/tsx/scripts`), C$10,000 start.
  - Daily share tracking, ex-date dividends (DRIP or paid out), 0.07–0.10% trading costs, and conversion to cash at the last price for delisted names.
  - Rules selected on October 2016 – December 2021, unseen on January 2022 – October 2026.
  - Searched: about 1,000 rule variants, about 190,000 ETF mixes, random-portfolio baselines, trend overlays, and cross-asset lead-lag tests (BTC, oil, gold and USD/CAD against TSX sectors).
- **Results** (per year / worst drop over October 2016 – October 2026):
  - **Plan 1, Dividend Growth & Momentum 24:** 17.0% / 38.5%; unseen 15.2%; C$47,930 with DRIP; paid out: C$31,474 plus C$7,478 of dividends.
  - **Plan 2, High-Yield Trend 12:** 11.1% / 11.4%; 95% of 12-month windows positive.
  - **Plan 3, All-Weather Core-Satellite:** 14.2% / 24.2%.
  - **Plan 4, Momentum Rotation 50/50:** 17.8% / 32.6%; unseen 15.1%.
  - **Benchmarks:** XIC 12.3% / 37.2%; VDY 14.3% / 39.2%; VFV 16.1% / 27.5%.
- **Rejected:**
  - Raw highest-yield ranking (best on the selection years, weak afterwards).
  - Trend overlays for long-term holders.
  - Market-level timing.
  - Cross-asset leads that faded after 2022.
- **Deliverables:**
  - Interactive report (Artifact).
  - `docs/research/TSX_PLANS_2026-10.md`.
  - Monthly value and dividend CSVs and current holdings for each plan.
- **Not done:** the app cannot run these plans yet. It has no TSX instruments, CAD pricing or Canadian market calendar, and its strategy language is signal-based per symbol, with no target-weight rebalancing or DRIP. This is left for the owner to decide.

## D-055 TSX portfolio plans in the app

- **Date:** 2026-10-04
- **Context:** The owner approved building the four D-054 TSX plans into the app: TSX listings in C$ with current TSX prices, and a portfolio-style plan type with target weights, scheduled rebalancing and DRIP.
- **Decision:**
  - **Separate subsystem.** TSX plans run as their own subsystem (`engine/tsx`), not through the order, ledger and strategy-language engine. That engine trades per-symbol signals in USD; these plans hold a basket at target weights, re-weight on a schedule and pay dividends. Keeping them apart leaves the existing paper engine and its tests untouched.
  - **Ten TSX slots,** in addition to the 10 crypto and 10 stock slots. Each run has its own C$ starting cash (C$100 to C$10M, default C$10,000), a DRIP or paid-out choice, and a mode:
    - **Notify:** a rebalance is proposed, raises a notification and waits for Approve or Decline.
    - **Autonomous:** the rebalance is applied on schedule.
  - **Data.**
    - Source: Yahoo Finance's public `.TO` chart endpoint (no key), downloaded on demand from the TSX plans screen.
    - Updates: automatic every 6 hours while a TSX plan is running. The scheduler task `tsx` checks every 10 minutes.
    - Storage: daily close, adjusted close and dividends from October 2014 for the 173 bundled listings (`resources/tsx/universe.json`), in table `tsx_history` (migration 10).
    - Plans refuse to start on data more than 10 days old.
  - **Execution.** Trades happen at the day's close, with the plan's cost (0.10%, or 0.07% for the momentum rotation plan). Ex-date dividends are moved to the next trading day if the ex-date is not one. With DRIP they buy the payer at the close; otherwise they are recorded as income. A listing with no prices for more than 10 trading days becomes cash at its last price, recorded as "taken over or delisted".
  - **Plans and research.** The four plans, their rules and their research series ship in `resources/tsx/plans.json`. The research series are monthly values with DRIP and paid out, monthly dividends, and years, compared with XIC and VDY. The app shows them as three views: DRIP growth against the index funds, an income view (monthly value with dividends paid out plus monthly dividend bars and dividends per year), and returns by year.
  - **Backtests on the phone.** A plan can be backtested on the phone's own downloaded data, and the result shows both dividend views.
  - **Plain numbers.** Plan math uses doubles, not the BigDecimal money type. These are research-grade portfolio simulations with fractional shares and no ledger. Values are rounded to cents when stored.
  - **Parity with the research.** The Kotlin engine reproduces the Python research on the research data (opt-in `TsxResearchParityTest`, `SF_TSX_RAW`):

    | Plan | Kotlin | Python |
    |---|---|---|
    | Plan 1 | 47,908 | 47,930 |
    | Plan 2 | 28,729 | 28,729 |
    | Plan 3 | 37,748 | 37,750 |
    | Plan 4 | 51,497 | 51,497 |

  - **API:** `/v1/tsx/plans`, `/v1/tsx/data` (+`/refresh`), `/v1/tsx/plans/{id}/backtest`, `/v1/tsx/backtests[/{id}]`, `/v1/tsx/runs[/{id}]`, and `/v1/tsx/runs/{id}/approve|decline|stop`. Notifications deep-link to `strategyforge://tsxrun/{id}`.
  - **App:** Strategies has a "TSX plans (Canada, C$)" button. That screen shows the data status and download, the running TSX slots ("n of 10"), and a card per plan with its research views, rules and holdings, plus Run in a TSX slot and Backtest on my data. The run screen shows value, holdings, the rebalance waiting for approval, value and monthly dividend charts, activity, and Stop (confirmation required).
- **Tests:**
  - Book: target weights and costs, DRIP compared with paid out, and delisting.
  - The Yahoo parser.
  - The service on synthetic data: refresh, stale data, notify then approve, autonomous runs over 60 days with both dividend modes, the slot limits and the backtest.
  - The app's own Repository end to end through the local API.
  - A Robolectric UI test for the run screen and the data card.
- **Caveats:**
  - Yahoo's endpoint is unofficial and may be delayed or change; failures are shown and retried.
  - The research has survivorship bias: companies taken over that Yahoo no longer serves are missing.
  - StrategyExplainer is unchanged: TSX plans are not written in the strategy language, so each plan card states its rules instead.
- **Version:** 1.14.0 (versionCode 19).

## D-056 Live price streaming and intraday TSX values

- **Date:** 2026-10-04
- **Context:** The owner asked for live profit/loss while plans run: real-time streams for crypto and US stocks, and intraday values for TSX plans. Before this, the app polled for prices:
  - crypto from Coinbase every 15 seconds;
  - US stocks from Twelve Data, about 15 minutes delayed on the free plan;
  - TSX plans updated only at the daily close.
- **Decision:**
  - **Streams.** A new `QuoteStream` interface, with WebSocket plumbing shared between the two streams (`market/QuoteStreams.kt`). The plumbing handles subscriptions as the watched symbols change, reconnects with backoff (2 s doubling to 60 s), detects dead links with 20-second pings, and runs a silence watchdog where the feed has heartbeats. Socket callbacks never touch the database; they only update an in-memory map of the latest tick per symbol.
    - **Crypto:** Coinbase Advanced Trade's public feed (`wss://advanced-trade-ws.coinbase.com`, `ticker` and `heartbeats` channels, no key). Ticks are timed by the message `timestamp`, capped at the moment the phone received them, so a phone clock a few seconds behind never makes a price look future-dated.
    - **US stocks:** Alpaca's free IEX feed (`wss://stream.data.alpaca.markets/v2/iex`), real-time trades and quotes for up to 30 symbols. It needs the owner's Alpaca key ID and secret, kept in the phone's key store like the other keys (new `StreamKey`). Changing the key needs the device lock, and only a 12-character fingerprint of the key ID is ever shown or logged.
  - **Use.** In Live mode a new scheduler task `stream` runs on every 5-second engine tick:
    - it subscribes the streams to the watched instruments;
    - it stores each fresh tick as the instrument's quote, under the asset class's existing provider name so verification is unchanged;
    - it evaluates price alerts.

    Holdings' value, profit/loss, stops and fills therefore follow prices within about 5 seconds.
  - **Fallback.** A stream is used only while it is fresh: up to 30 s old for crypto and 5 min for stocks. Otherwise the 15-second polling runs as before. A polled (possibly delayed) quote older than the last streamed one is not stored and does not raise an out-of-order error. Demo mode disconnects the streams.
  - **Strategy candles are unchanged.** Signals still come from candles: Coinbase candles for crypto, which are real-time, and Twelve Data candles for stocks, which may be delayed. Streaming changes how positions are valued and filled, not when a stock strategy signals.
  - **TSX intraday (display only).**
    - While a TSX plan runs, the TSX tick (now every minute) fetches current prices for the held listings from Yahoo's chart endpoint (`regularMarketPrice`). This happens at most once a minute, from 09:30 to 16:15 Toronto on weekdays, in the background.
    - A run shows "Now (intraday)", today's change and the price time, plus each holding's current price.
    - Dividends, rebalancing and the stored values still use closing prices, so runs keep matching the research.
  - **TSX daily data fix.** The daily data download now stops at the last completed session (before 16:30 Toronto, yesterday). This stops a refresh during trading hours from storing today's unfinished bar and trading at a mid-day price.
  - **Screens refresh themselves** while in the foreground: home and portfolio every 5 s, slots every 10 s, TSX screens every 30 s, and the engine screen's stream status every 5 s.
  - **API and app.**
    - New endpoints: `GET /v1/market-data/streams` and `PUT /v1/market-data/stocks/stream-key` (`keyId` and `secret`; null for both removes the key).
    - TSX run responses add `liveValue`, `liveAt` and each holding's `livePrice`.
    - The Engine screen has a "Live price streaming" section with each stream's state and errors, and Alpaca key entry.
- **Tests:**
  - Both stream protocols against a local WebSocket server: subscribe and unsubscribe deltas, Coinbase string prices, Alpaca's auth handshake, trades and quotes (pricing at the mid before any trade), a rejected key backing off for 10 minutes, and no key meaning no connection.
  - The engine storing streamed quotes on each tick and falling back to polling without an out-of-order flag.
  - TSX intraday values and the completed-session download.
  - The Yahoo meta parser.
  - The API keeps the Alpaca key out of every response.
  - UI tests for the run screen's intraday value and the streaming section.
- **Verified live:** `LiveStreamsTest` (opt-in, `SF_LIVE_STREAMS=1`) ran against the real hosts on 2026-10-04. Coinbase streamed BTC-USD and ETH-USD ticks with bid and ask within a second of connecting. Alpaca accepted the connection and answered a made-up key with `402 auth failed`, exactly as handled. A real Alpaca key is needed to see stock ticks, which happens on the phone.
- **Version:** 1.15.0 (versionCode 20).

## D-057 Yahoo Finance stream replaces Alpaca for stocks and streams TSX holdings

- **Date:** 2026-10-04
- **Context:** The owner cannot use Alpaca. They asked for Yahoo Finance's stream, which yfinance and other community libraries use, instead.
- **Decision:**
  - **Yahoo stream.** `YahooQuoteStream` connects to `wss://streamer.finance.yahoo.com/?version=2` with no key.
    - It subscribes with `{"subscribe":[...]}` and repeats the subscription every 15 s, as yfinance does, because the server drops subscriptions that are not repeated.
    - Each price arrives as `{"type":"pricing","message":<base64>}`. The message is yfinance's `PricingData` protobuf, read by a small hand-written decoder (no protobuf dependency) that keeps only the symbol, price, time, market hours, bid, ask and price hint.
    - Engine symbols map to Yahoo's (`BRK.B` becomes `BRK-B`, TSX listings keep `.TO`), and ticks map back.
  - **Regular session only.** Pre- and post-market prices are ignored, so stops never trigger on thin extended-hours trading. A price more than 10 minutes old is labelled delayed.
  - **US stocks** stream from Yahoo in Live mode, up to 60 watched symbols. Twelve Data stays the polled fallback and the source of strategy candles.
  - **TSX plans.** The same socket streams the listings that running TSX plans hold, during the TSX session (09:30–16:15 Toronto), in Demo and Live mode. A run's intraday value uses the newer of the streamed and polled prices. Listings the stream prices are no longer polled. Polling (D-056) remains the fallback.
  - **Alpaca removed:** `AlpacaQuoteStream`, the `StreamKey` key store, `PUT /v1/market-data/stocks/stream-key` and the key fields on the Engine screen. The Engine screen now only shows each stream's state. No stored Alpaca keys existed.
- **Risk:** the stream is unofficial and may change or stop without notice. When it is silent or fails, the app falls back to the earlier polling: Twelve Data for stocks, Yahoo's chart endpoint once a minute for TSX holdings. The Engine screen shows the stream's error.
- **Tests:**
  - Against a local WebSocket server, using protobuf messages built in the test: subscribe and unsubscribe JSON, the `BRK-B` mapping, bid and ask decoding, ignored pre-market ticks, delayed labelling and the 15-second resubscription.
  - The decoder skips unknown fields and rejects malformed bytes.
  - TSX: held listings are subscribed during the session in Demo mode, stay unsubscribed when the market is closed, and the streamed price drives the live value and replaces polling.
  - `LiveStreamsTest` gains an opt-in Yahoo check (`SF_LIVE_YAHOO=1`). The owner unblocked `streamer.finance.yahoo.com` here. On 2026-10-04 (a Sunday) the real stream connected with no key, and BTC-USD arrived within a second: Yahoo's price was 85,440.24 and Coinbase's was 85,440.25 in the same run, which confirms the protobuf field numbers and the time unit. On 2026-10-05 at the US open (13:42 UTC) AAPL and RY.TO streamed within a second of subscribing, labelled real-time. AAPL came in at $334.68 at 13:42:39 against Yahoo's chart quote of $334.655 at 13:42:51. RY.TO came in at C$277.20 at 13:42:39 against C$277.16 at 13:42:41. Yahoo sent AAPL with no bid or ask, and RY.TO with an ask but no bid. Bid and ask are therefore now kept only as a pair, so a one-sided quote cannot skew simulated fills (1.17.2). `LiveStreamsTest` with `SF_LIVE_YAHOO_STOCKS=1` asserts stock ticks during a session.
- **Version:** 1.16.0 (versionCode 21).

## D-058 Running plans listed in the Portfolio menu

- **Date:** 2026-10-04
- **Context:** The owner wants each running strategy plan in the Portfolio menu, viewable as if it were one of their portfolios.
- **Decision:** The Portfolio screen keeps its "Paper portfolios" chips and adds a "Running plans" row, refreshed every 30 s. It lists every strategy in a crypto or stock slot (for example "Crypto 1 · Chart Champions v3") and every active TSX plan (for example "TSX 2 · …").
  - **Selecting a crypto or stock plan** opens the portfolio it trades in, with:
    - a plan card: the slot, the mode, the share of the portfolio, realized profit/loss from closed trades (the strategy's scorecard), unrealized profit/loss of its own open positions, the total, closed trades with wins and losses, win rate and days running;
    - the positions and orders filtered to that plan (its slot holdings in that portfolio, and orders tagged with its strategy);
    - the portfolio's equity, cash and chart, labelled as the whole portfolio's because two plans can share one.
  - **Selecting a TSX plan** shows the TSX run view (D-055 and D-056): value at the last close, the intraday value now, today's change, holdings, a rebalance waiting for approval with Approve and Decline, value and dividend charts, activity, and Stop.
  - **Updates:** both views refresh every 5 s while open, so they follow the streamed prices. The create-portfolio form is hidden while a plan is selected. A plan that stops drops off the list.
- **Scope:** app only; it reuses the existing `/v1/slots`, `/v1/strategies/{id}/scorecard`, `/v1/orders` and `/v1/tsx/runs` endpoints.
- **Tests:** `PlanPortfolioUiTest` checks the plan label and portfolio, and that the plan card counts only the plan's own position (+$30.00 unrealized, +$150.50 total with $120.50 realized) and shows "3 (2 won, 1 lost)".
- **Version:** 1.17.0 (versionCode 22).

## D-059 Permanent signing key: updates keep the app's data

- **Date:** 2026-10-05
- **Context:** After installing 1.17.0 the owner's running plans were gone. The repository had no signing secrets, so CI signed every build with a new temporary key. Android refuses to update an app signed with a different key, and the uninstall it requires deletes the database (portfolios, plans, TSX runs, settings) and the Keystore entries (API keys). App updates themselves never reset data: database migrations run on every start. This was a signing problem, and D-031 and `docs/ANDROID.md` described it without it ever being raised with the owner.
- **Decision:**
  - **The key.** A permanent release key was generated: RSA 4096, PKCS12, alias `strategyforge`, valid 30 years. It went to the owner as a private file of the four repository-secret values (`SF_RELEASE_KEYSTORE_B64`, `SF_RELEASE_KEYSTORE_PASSWORD`, `SF_RELEASE_KEY_ALIAS`, `SF_RELEASE_KEY_PASSWORD`), and the session's copy was deleted. This session cannot write Actions secrets, so the owner adds them.
  - **The guard.** The `phone-release` job publishes only when `apksigner` reports the permanent certificate's SHA-256 (`f2f721efecdb46b8055a3f267f6e7b69986586064e2b1d032046853ae72ee3ef`). A build that could not update the installed app is never published again.
  - **Policy.** Every update installs over the previous version and keeps all data. A data-resetting release would be announced as such in advance. None is planned: schema changes ship as database migrations.
- **One-time switch:** moving from the temporary key to the permanent one needs one last uninstall.
  1. Save a backup with **More → Backups → Back up now → Save a copy**. Backups include every table, including TSX runs, but no keys.
  2. Uninstall, then install the first permanently signed build.
  3. Restore the backup from the file, then re-enter the API keys.
- **Version:** 1.17.1 (versionCode 23), the first permanently signed build. It was published after the owner added the secrets on 2026-10-05. Verified on 2026-10-05: the owner installed 1.17.2 over 1.17.1 and all data stayed.

## D-060 Running plans on the Home screen

- **Date:** 2026-10-05
- **Context:** The owner wants the active plans' portfolios visible on Home, not only in the Portfolio menu (D-058).
- **Decision:**
  - **Placement.** Home shows a "Running plans" section below the portfolio card, with one card per running plan.
  - **Crypto and stock plans.** Each card shows the slot label, the mode and portfolio, the total profit/loss (realized from the strategy's scorecard plus unrealized from its own open positions), and open positions against closed trades.
  - **TSX plans.** Each card shows the value now (intraday when available, otherwise at the last close), the change since start, today's change, and whether a rebalance is waiting for approval.
  - **Navigation.** Tapping a card opens that plan in the Portfolio screen: the route `portfolio?plan=<key>` preselects it.
  - **Refresh.** Home re-reads the plans every 15 s, because each needs a few requests. The portfolio card still refreshes every 5 s.
- **Scope:** app plus one core call (`Repository.portfolioSummaryNow`); no engine change.
- **Tests:** `PlanPortfolioUiTest` checks the Home card's label, portfolio, profit/loss, positions and trades, and that tapping it opens the plan.
- **Version:** 1.18.0 (versionCode 25). It installs over 1.17.x and keeps the app's data (D-059).

## D-061 TSX plans listed with the running slots on the Plans screen

- **Date:** 2026-10-05
- **Context:** The owner noticed that running TSX plans did not appear in the "Running now" slots at the top of the Plans screen. Only crypto and stock slots were listed there.
- **Decision:**
  - **TSX row.** "Running now" gains a "TSX · n of 10 slots in use" row, below the crypto and stock rows. Each active TSX plan gets a card showing its slot and plan name, its mode, its value now (intraday when available) with the change since start and the number of holdings, and any rebalance waiting for approval.
  - **Actions.** Each card has **Open**, which opens the plan's page, and **Stop**, which asks for confirmation.
  - **Wording.** The intro now says that 10 TSX plans can run alongside the 10 crypto and 10 stock strategies.
- **Tests:** `StrategySlotsUiTest` checks the TSX row, the card text and the Stop confirmation.
- **Version:** 1.18.1 (versionCode 26). It installs over 1.18.0 and keeps the app's data (D-059).

## D-062 TSX trading costs set aside before investing; cash never negative

- **Date:** 2026-10-05
- **Context:** A TSX run showed "Cash C$-1.00". The plan invested the whole value, and the 0.1% trading cost was then taken from cash. The research simulator counted costs the same way, but a negative cash line confused the owner, who asked for the fee to be set aside instead.
- **Decision:**
  - **Set the fee aside.** `Book.rebalance` scales the targets to the largest fraction of the portfolio's value whose purchases plus costs the cash can cover, found by bisection; the result ends at zero cash or above. From all cash, C$1,000 buys C$999.00 of stock and pays C$1.00 of costs. Switching every holding (fees on both the sale and the purchase) also stays at zero or above.
  - **Existing runs.** `Book.coverNegativeCash` brings a run that already has negative cash back to zero on its next trading day. It sells the same small fraction of every holding at the close, net of costs, and records the sales as SELL events.
- **Effect on the research:** on the research data over 10 years:

  | Plan | Before | Now |
  |---|---|---|
  | Plan 1 | 47,930 | 47,834 |
  | Plan 2 | 28,729 | 28,719 |
  | Plan 3 | 37,750 | 37,742 |
  | Plan 4 | 51,497 | 51,458 |

  The per-year returns and drawdowns are unchanged to one decimal.
- **Tests:** `BookTest` covers cash ending between 0 and 1 cent for the first purchase, a full switch, and covering existing negative cash.
- **Version:** 1.18.2 (versionCode 27). It installs over 1.18.1 and keeps the app's data (D-059).

## D-063 Running plans trade under their own risk limits; sizing fits limits and buying power

- **Date:** 2026-10-06
- **Context:** The owner's BTC trend core (95% of equity in BTC) never traded. Every nightly entry was rejected by the global profile: 20% per trade, 25% per instrument, 50% crypto, 5% daily loss and 25% drawdown. The app had no way to change these limits. Every backtest on the phone ended with zero trades for the same reason, while the library's research results were made without these limits, and nothing warned about the mismatch. The owner's backup (`.sfbk`) confirmed one rejected signal (TRADE_VALUE 95% against 20%, ALLOCATION 95% against 25%) and four zero-trade backtests. The other alerts were transient: a missing first quote at activation, the re-authorization after turning shorting on, and a phone DNS error. The owner chose per-plan limits over raising the global ones or shrinking the plans.
- **Decision:**
  - **Plan limits.** `RiskProfileService.baseFor` gives a running plan's own orders, in the portfolio its activation runs in, `planBase`: the global profile with the plan's declared limits in place of the global sizing and loss limits. Those are its largest position (trade and single instrument), 100% of its own asset class, daily loss, drawdown, open positions and losing streak. Data-quality, rate and emergency limits stay global.
  - **Tightening still works.** Portfolio and strategy profiles still apply strictest-wins on top of the plan limits.
  - **Other orders.** Manual orders, and strategies outside a slot, keep the global profile.
  - **Backtests.** They use the same plan base, so their results match what the plan will do in a slot.
  - **Sizing fixes found on the way.**
    - Entry size is also capped at the activation's share of the portfolio.
    - It is capped so the cash reservation (price buffer and costs) fits within buying power; before, a 99.5% buy needed about 101.5% of the cash.
    - The margin under a percentage cap is now 0.5%, up from 0.1%, so spread and slippage cannot push a fill over the limit.
  - **Risk limits screen** (More → Risk limits; `GET /v1/risk/limits`, `PUT /v1/risk/limits/global`).
    - It shows each running plan's effective limits, with a warning when they would block the plan's normal entry size.
    - It lets the owner edit the default limits for everything else: per trade, per symbol, crypto, stocks, daily loss, drawdown and open positions. Raising a limit needs the device lock.
- **Tests:**
  - A 95% plan fills, while the same size entered by hand in another portfolio is rejected (TRADE_VALUE).
  - The backtest applies the plan's own open-position cap of 3 instead of the global 25.
  - Risk-percent sizing is still capped by a portfolio's own 20% profile.
  - The live wick-stop plan now fills and exits; it had been hiding the buying-power gap.
  - Through the Repository, tightening needs no device lock and loosening does.
  - `RiskLimitsUiTest`.
- **Version:** 1.19.0 (versionCode 28). It installs over 1.18.2 and keeps the app's data (D-059).

## D-064 A started or resumed plan checks the current bar straight away

- **Date:** 2026-10-06
- **Context:** The owner started the BTC trend core at 11:51 and expected it to buy. Its rule (hold while the daily close is above the 100-day average) was true, but nothing happened for about nine hours. The first check ran before any BTC quote had arrived, so it was blocked as STALE_MARKET_DATA and autonomy paused the plan. After re-authorization the plan's check of the same daily bar was refused as a duplicate, so it waited for the next daily close at 00:00 UTC. Crypto trades all day, but daily-bar plans decide once per completed day.
- **Decision:**
  - **Missing first quote.** When a plan is checked and no quote has been received yet for a symbol, the engine fetches one on demand before deciding. A quote that has gone stale still blocks and pauses autonomy, as before (FR-074).
  - **Re-check on restart.** A new activation (a plan started again, or resumed after a pause) may re-check the current bar when an earlier activation's check of it produced no signal. Checks that produced a signal are kept, so a bar never trades twice.
- **Tests:**
  - A plan activated before any quote exists is not blocked and produces exactly one ENTER_LONG on its first check.
  - The autonomy test still pauses on a feed that stops refreshing.
- **Version:** 1.19.1 (versionCode 29). It installs over 1.19.0 and keeps the app's data (D-059).

## D-065 Symbol search with an information screen; errors shown at their field

- **Date:** 2026-10-06
- **Context:** The owner asked for two things. First, errors from an action should appear next to the field they are about, and the screen should move there; before, they appeared as a banner at the bottom of a long screen. Second, the order screen's free-text symbol field accepted anything. The owner wanted typing to suggest matching symbols, and a way to see a stock's or coin's price, chart and details before buying, as Wealthsimple and Questrade do. A risk rejection was also reported as "Paper order submitted".
- **Decision:**
  - **Search as you type.** `GET /v1/instruments?q=` matches the app's instruments by ticker or name. Exact tickers rank first, and "btc" finds BTC-USD. Then come US stocks and ETFs from Yahoo Finance's public search; those are added (after Twelve Data confirms them, FR-020) when first traded (`POST /v1/instruments`). Crypto stays limited to the allowlisted pairs. Each suggestion shows the name, exchange, sector and last price, plus an Info button.
  - **Information screen** (`GET /v1/instruments/{symbol}?range=`). It shows:
    - the price and today's change;
    - a price chart for 1D, 5D, 1M, 6M, 1Y or 5Y;
    - previous close, day and 52-week ranges, volume, exchange and industry;
    - the owner's position in the portfolio;
    - the trading price paper orders fill from.

    Buy and Sell fill in the order form. Display data comes from Yahoo Finance (`YahooSymbolDirectory`, no key). Orders still fill from the trading sources (Coinbase, Twelve Data), and the screen says so. Company descriptions are not shown, because Yahoo's profile endpoint needs a session cookie.
  - **Errors at their field.**
    - Engine problems may name a `field`: `unknown-symbol`, `invalid-quantity` and `invalid-price` now do. `ActionState.Failed.field` carries it.
    - The order form checks its fields before sending (`OrderForm`). It shows each problem under its field, then scrolls to the first one and focuses it (`FieldTarget`).
    - A risk rejection is shown by the Submit button with its reason.
    - On every screen, an action's error banner now scrolls into view.
- **Tests:**
  - Engine: search ranking and filtering, the information data, the field on order errors, and adding stocks. Yahoo parsing, including BRK.B↔BRK-B and yesterday's close from today's change.
  - An opt-in live Yahoo check (`SF_LIVE_YAHOO=1`), run once against the real service.
  - Core: `OrderForm` and field mapping.
  - App: `InstrumentUiTest`.
- **Version:** 1.20.0 (versionCode 30). It installs over 1.19.1 and keeps the app's data (D-059).

## D-066 Orders by dollar amount, with fractional quantities

- **Date:** 2026-10-06
- **Context:** The owner wanted to put $500 into bitcoin, but the order screen only took a quantity. They asked to enter an amount and have the app buy or sell that value, in fractions, for crypto and stocks alike, the way consumer trading apps do.
- **Decision:**
  - **Amount orders.** An order may carry an `amount` (USD) instead of a quantity (`OrderRequest.notional`). The engine converts it after refreshing the quote, at the same price the risk checks use: the limit price, or the current ask for buys and bid for sells (with the fallback spread when there is no bid/ask).
    - A buy is sized so the amount also covers the price buffer and costs, so it never needs more than the amount.
    - A sell is capped at the position held, so "sell $X" for at least the position's value sells all of it.
    - The quantity is rounded down to the instrument's step (crypto 0.00000001, stocks 0.0001).
  - **Errors at the field.** An amount below one step fails as `amount-too-small`, and a missing price as `no-price`, both on the amount field (D-065).
  - **Order screen.** It offers **Amount ($)** (the default) or **Quantity**. Under the amount, an estimate shows roughly how much of the symbol it buys at the last known price.
  - **Limits still apply.** Manual orders still follow the default limits (20% of equity per trade, 25% per symbol, 50% crypto). A larger buy is rejected with that reason by the Submit button; the owner can raise the limits on More → Risk limits (D-063).
- **Tests:**
  - Engine: $500 in a $500 portfolio buys a fractional BTC quantity and the cash stays non-negative; selling more dollars than held sells the whole position; amounts too small or zero fail at the amount field; a limit order uses the limit price.
  - Core: amount form checks.
  - App: the amount hint.
- **Version:** 1.21.0 (versionCode 31).

## D-067 A plan with an old stored price fetches a fresh one instead of pausing

- **Date:** 2026-10-06
- **Context:** The owner uninstalled the app before installing 1.20.0, which deletes all of an app's data on Android, and then restored the backup from 00:02 UTC Oct 6. The BTC trend core plan was running in that backup, but did not run after the restore. The backup's stored BTC quote was hours old. The plan's first check found it STALE and blocked with STALE_MARKET_DATA, which pauses autonomy. D-064 fetched a quote on demand only when none existed (MISSING). The same happens whenever the phone has been off or the app stopped long enough for the stored price to age.
- **Decision:**
  - **Refresh on any unverified quote.** When a plan is checked and its quote is not verified for any reason (missing, stale, from an inactive source), the engine fetches one before deciding, as long as the instrument is active.
  - **A real feed failure still pauses.** If the fetch fails, the quote stays unverified and blocks, so autonomy still pauses (FR-074). This is tested by deactivating the instrument.
  - **Plans started after a backup are not in it.** They have to be started again after a restore.
- **Tests:** a plan whose stored quote is three hours old keeps running and is not blocked. The FR-074 autonomy test still pauses.
- **Version:** 1.21.1 (versionCode 32).

## D-068 Accept CVE-2026-47884 in the legacy backend for 30 days

- **Date:** 2026-10-06
- **Context:** The security scan began failing on CVE-2026-47884 (CRITICAL, spring-webmvc 6.2.19: remote code execution through XsltView path handling), which blocked phone releases. The fix exists only in Spring 7.0.9 (Spring Boot 4), and no 6.2.x release fixes it. Only the legacy `backend/` server depends on Spring. The phone app runs its engine on the device and does not include Spring, and `backend/` uses no XsltView or XSLT views.
- **Decision:** The owner chose to accept the finding for 30 days rather than upgrade or retire `backend/` now. `.trivyignore` lists it with this justification and an expiry (`exp:2026-11-05`), after which the scan fails again.
- **Follow-up:** by 2026-11-05, upgrade `backend/` to Spring Boot 4, or remove it if it is no longer needed.

## D-069 Daily backup to Downloads

- **Date:** 2026-10-06
- **Context:** The owner uninstalled the app before installing an update. That deleted its data and its backups, because backups were kept in app-private storage. They asked for a daily backup that would survive an uninstall.
- **Decision:**
  - **Daily backup.** `AutoBackups` runs once a day, in both modes, when there is something to keep (at least one portfolio or strategy). It writes a backup tagged `auto` (`strategyforge-...-autoXXXX.sfbk`) and hands it to the app's `exportBackup`. On the phone that is `DownloadsBackups`, which saves it with MediaStore to `Download/StrategyForge/` (no storage permission needed).
  - **Kept to seven.** The newest 7 daily backups are kept, both in the app and in Downloads. Manual backups, and files from an earlier install, are never deleted. (Changed to three in total by D-070.)
  - **Failures.** A failure is recorded, shown on the Backups screen and retried after an hour. It never affects trading.
  - **Settings.** The daily backup is on by default and can be turned off on More → Backups (`GET/PUT /v1/backups/auto`). "Back up to Downloads now" runs it at once (`POST /v1/backups/auto/run`).
  - **Restoring after a reinstall.** Home, when empty, offers "Restore from a backup". Backups → "Restore from a file" picks the newest file in Downloads/StrategyForge. API keys are not in backups and are entered again.
- **Tests:**
  - Engine: nothing is backed up before there is data, then once a day with a copy outside the app; seven are kept and manual backups survive; a failed copy is retried after an hour; turning it off stops it; the scheduler runs it in demo mode; with no export target, backups stay in the app; the Repository round trip works.
  - App: the backup card and the failure banner.
- **Version:** 1.22.0 (versionCode 33).

## D-070 Keep only the last three backups

- **Date:** 2026-10-06
- **Context:** The owner asked the daily backup job to delete old backups so that there are only ever three in total, covering the last three days.
- **Decision:**
  - **Three in total.** After each daily backup, only the newest three backups are kept, both in the app and in Downloads.
  - **In the app,** that count covers every backup: manual ones and the safety copy made before a restore count too. This replaces D-069's seven daily backups with manual ones never deleted.
  - **In Downloads,** the job deletes only daily copies it made itself. Files saved by hand, or left by an earlier install, are left alone, because Android does not let the app see them.
- **Tests:** after ten days only three backups remain, the newest three, and an older manual backup is gone.
- **Version:** 1.22.1 (versionCode 34).

## D-071 Editing default limits no longer pauses plans that don't use them; clock tolerance; pause reason shown

- **Date:** 2026-10-06
- **Context:** The owner's newest backup (2026-10-06 04:06 UTC) showed why BTC trend core kept pausing: "Autonomous mode paused until re-authorized: global risk profile changed".
  - **The limit edits.** The owner raised the default limits for manual orders at 03:23 and 03:28 UTC, as D-066 suggested, and each edit paused the running plan. The autonomy fingerprint (FR-072) hashed the whole global profile. Since D-063, however, a plan's own orders replace the global per-trade, per-symbol, asset-class, daily-loss, drawdown, open-positions and losing-streak limits with the plan's (`planBase`), so those edits did not change what the plan may do.
  - **Clock skew.** The backup also showed Coinbase quotes rejected as dated about 10 s ahead of the phone's clock, because the tolerance was 5 s.
  - **Missing reason.** The plan's results card showed "Paused" without saying why.
  - **The 1969 date.** The Emergency screen showed "Last change: Dec 31, 1969", because the never-changed state is stored as 1970-01-01T00:00:00Z.
- **Decision:**
  - **Fingerprint uses the plan's limits.** The fingerprint now hashes `planBase(global, plan limits, asset class)` (the limits the plan runs under) instead of the raw global profile. Global limits the plan still uses, such as data quality, rates and quote age, still pause it when changed.
  - **Running plans carried over.** At start, the engine re-stamps a running autonomous plan when its stored fingerprint matches the earlier formula, so the new formula alone does not pause it.
  - **Clock tolerance.** It is now 60 s for quotes and bars. A quote two minutes in the future is still rejected.
  - **Pause reason shown.** The results card shows the reason whenever a plan is not running.
  - **Never-changed dates.** A time of 1970-01-01T00:00:00Z shows as "—", or "Never" for the emergency last change.
- **Tests:**
  - Engine: raising per-trade, per-symbol and crypto limits leaves a running plan active, while tightening the quote-age limit pauses it.
  - Core: dates stored as "never".
- **Version:** 1.22.2 (versionCode 35).

## D-072 Resume a paused plan with one tap

- **Date:** 2026-10-06
- **Context:** The owner saw BTC trend core marked Paused on Home and on its results card, but could not find a way to unpause it. The only path was the activation form at the bottom of the strategy's page, where they had to choose the portfolio, share and mode again and accept the disclosure.
- **Decision:**
  - **Resume.** `StrategySlots.resume` (`POST /v1/strategies/{id}/resume`) restarts a PAUSED strategy exactly as it last ran: the same portfolio, share, mode and slot. It goes through the usual activation gates.
  - **Autonomous mode** still needs the device lock. The disclosure the owner accepted earlier counts only while its version is still current; if it has changed, Resume says so and the owner accepts it on the page.
  - **Strategy page.** A paused strategy shows its reason and a **Resume** button at the top.
  - **Home.** Each paused or suspended strategy gets an "Open … to resume it ›" link.
- **Tests:** a paused autonomous plan resumes with the same portfolio, share and mode, and needs the device lock. A strategy that is not paused, or never ran, cannot be resumed.
- **Version:** 1.23.0 (versionCode 36).
