# StrategyForge AI — Project State Manifesto

Save point: **2026-10-02T05:24Z** · HEAD `9a44046` · version **1.5.0** (versionCode 8) · branch `claude/strategyforge-v1-delivery-vgf1ah`

```text
=== STRATEGYFORGE AI — PROJECT STATE MANIFESTO ===
SAVE POINT: 2026-10-02 05:24 UTC | HEAD 9a44046 | v1.5.0 (versionCode 8) | CI run 36948398701 GREEN
BRANCH: claude/strategyforge-v1-delivery-vgf1ah (push: git push -u origin <branch>; NO PR unless asked)
APK: https://github.com/jmb2025-hash/strategyforge-ai/releases/tag/phone-latest (StrategyForge.apk, CI-published)

1. SYSTEM ARCHITECTURE & GOAL
- Goal: private single-user Android app, PAPER TRADING ONLY (never real money). User researches an
  investor/method (e.g. Chart Champions) with AI -> compiled to a JSON strategy -> library -> run in
  NOTIFICATIONS or AUTONOMOUS mode. One CRYPTO slot + one STOCK slot active at a time. Scorecards rate
  effectiveness; AI can build a combined "better" strategy.
- Stack: Kotlin, Jetpack Compose, Hilt, MVVM (ViewModels use ResourceViewModel/act pattern).
  NOT Room: raw SQLite with versioned migrations (JDBC in JVM tests). BigDecimal money; Jackson JSON.
- Modules:
  * android/engine  pure Kotlin/JVM: Db (migrations 1..5), in-process LocalApi "/v1" (HTTP-shaped),
                    backtest/BacktestEngine, signals/EvaluationService (live eval), risk engine,
                    autonomy/ActivationService, research (AiClients, ResearchPrompts, imports),
                    strategy (StrategyModel, Indicators, Periods, StrategyValidator, StrategyExplainer),
                    reports (Charts, Scorecards).
  * android/core    models, Repository, ApiClient over LocalApiInterceptor, Formatters.
  * android/app     Compose UI (MainScreens, PortfolioAndActivity, Canvas charts, ViewModels.kt).
                    Builds ONLY on CI (no Android SDK in container).
- Data: Coinbase public (crypto), Twelve Data (stocks, user key), replay/demo fixtures /fixtures/replay.
- AI: Gemini (default, gemini-flash-latest), Anthropic, OpenAI, OpenRouter. Keys entered in-app ->
  Android Keystore. Never in source/APK/logs/chat.
- Strategy pipeline: strategy-schema-1.0.json -> StrategyValidator -> StrategyDefinition.from.
  Indicators are causal (no look-ahead). Crypto days = UTC; US stock days = America/New_York; weeks Monday.

2. VERSION EVOLUTION LOG
- <=1.1  Core: library, validator, backtester, live eval, risk engine, activation gates, AI research/compile.
- 1.2.0 (D-037) Scorecards + "Build me a better strategy".
- 1.3.0 (D-038) New theme; Canvas charts (equity curve, candles w/ trade markers, strategy comparison);
        motion, illustrations, new icon.
- 1.3.1 (D-039) FIX Gemini 404: preset gemini-flash-latest; on 404 list models + auto-switch
        (AiClients.pickGeminiModel); provider message shown; key change clears stale test; auto-test +
        editable model field.
- 1.3.2 (D-040) Research message limit = AI budget input ceiling (was fixed 8,000 chars); live counter;
        GET /v1/research/limits.
- 1.4.0 (D-041) Clearer quota/rate-limit errors w/ provider reason; "Use your own AI" (GET
        /v1/research/authoring-prompt + tolerant paste via StrategyText.extract); "Import research"
        POST /v1/research/imports (chunked digest -> compile, resumable; migration 4 import_chunk).
- 1.5.0 (D-042) Strategy language expansion (fixes AI omitting Chart Champions features):
        * Direction BOTH: shortEntryRules + exitRules.shortConditions; conflicting signals same bar = no trade.
        * Simulated crypto shorts (migration 5 crypto shortable=1); portfolio shorting switch
          POST /v1/portfolios/{id}/shorting (device-lock confirm); activation gate lists failing gates.
        * Indicators: PERIOD_LEVELS (DAY/WEEK/MONTH: open,high,low,prevOpen,prevHigh,prevLow,prevClose,prevEq);
          indicator "timeframe" 1d/1w/1M on COMPLETED HTF bars; VWAP(anchor); ANCHORED_VWAP(period +
          LOWEST_LOW/HIGHEST_HIGH); FIBONACCI(f236..f786 incl f660, high, low, trend);
          VOLUME_PROFILE(period|anchor: poc,vah,val; 50 rows, 70% VA, doubles).
        * exitRules.partialTakeProfit{atPercent,closePercent,moveStopToEntry} + BREAKEVEN_STOP reason.
        * Sizing RISK_PERCENT, shrunk to strictest of strategy maximumPositionPercent and risk-profile
          per-trade % / value. BUG FIXED: positions were rejected by profile 20% cap -> now fitted.
        * riskLimits.maximumDailyLosingTrades.
        * minimumHistoryBars auto-raised (HISTORY_RAISED warning); >5000 bars = HISTORY_TOO_LONG error.
        * Prompts (EXPRESSIBLE, PATTERN_GUIDE, compile/authoring) teach all; "express, don't omit".
        * SECURITY FIX: Jackson -> 2.21.7 (CVE-2026-89407/89425/91776/91777) in
          android/gradle/libs.versions.toml AND backend/build.gradle.kts.
        * Verified: Chart Champions demo backtest 103 trades (55L/48S) w/ partials + breakeven stops.
- 1.6.0 (D-043) "Copy instructions" prompt makes the research AI self-complete: checklist of rule points,
        web-research any gap itself (no guessing, no asking), reply = JSON + RULE READBACK + FURTHER RESEARCH
        + STILL MISSING. Phone sends text outside the JSON as notes; migration 6 strategies.import_notes;
        ImportNotes parser; strategy page "From your research AI" section + missing-points warning banner.

3. CURRENT WORKING STATE & BLOCKERS
- Build: GREEN. No compile errors, no unfinished files, working tree clean at 9a44046.
- Tests: engine 174/174, core 26/26 (incl. LevelIndicatorsTest 9, BacktestFeaturesTest 4,
  ChartChampionsStrategyTest 2, LiveStrategyFeaturesTest 3, LocalApiAppTest D-042, ChartsUiTest ShortingSwitch).
- BLOCKER: futures.kraken.com is DENIED by this cloud env network policy (connect_rejected) -> cannot
  verify live futures responses here. User can allow it: env Network access settings (cloud env -> Edit).
- Open decisions awaiting user:
  a) Approve Kraken Futures public API (no key, available in Canada) for OI / funding / delta-CVD.
  b) Live streaming upgrade (Coinbase websocket + Alpaca IEX) - offered, unanswered.
- Known approximations: volume profile and CVD derived from candles (not tick data).

4. NEXT IMMEDIATE STEPS (ranked)
 1. Get user approval on Kraken Futures (and ideally host allow-listed for verification).
 2. Engine: market/KrakenFuturesClient (public, no key):
      tickers (OI, funding)   futures.kraken.com/derivatives/api/v3/tickers
      funding history         /derivatives/api/v4/historicalfundingrates?symbol=PF_XBTUSD
      OI history              /api/charts/v1/analytics/PF_XBTUSD/open-interest
      delta (aggressor diff)  /api/charts/v1/analytics/PF_XBTUSD/aggressor-differential  -> CVD = cumsum
    Map spot symbols -> PF_* perps (BTC-USD->PF_XBTUSD, ETH-USD->PF_ETHUSD). Add replay fixtures.
 3. Db migration 7: derivatives series cache (symbol, ts, oi, funding, delta) aligned to bar openTime.
 4. Indicators: OPEN_INTEREST, FUNDING_RATE, DELTA/CVD types (crypto only), causal, aligned to bars;
    validator rejects for stocks; lookback/requiredHistory; explainer text.
 5. Wire into BacktestEngine + EvaluationService (missing derivatives data -> MISSING_HISTORY/inactivity).
 6. Schema JSON + ResearchPrompts (EXPRESSIBLE/PATTERN_GUIDE) teach new fields.
 7. Tests: hand-checked values, no-look-ahead test, fixture-based client parse tests, API test.
 8. D-044 in docs/DECISION_LOG.md; bump version 1.7.0 / versionCode 10; spotless; gitleaks; commit; push;
    wait CI (scratchpad/waitci.sh <sha>); confirm phone-latest republished; report to user.

5. CRITICAL CAVEATS & CONSTRAINTS
- Paper trading only. Never real orders. Shorts are simulated.
- Secrets: never in source/APK/logs/chat; Keystore only. gitleaks must pass; fake-key test false
  positives go in .gitleaksignore as sha:file:rule:line.
- Before EVERY commit: engine + core tests, spotless (scratch project scratchpad/fmt: gradle
  spotlessApply then spotlessCheck), gitleaks (scratchpad/gitleaks).
- Commit trailer: "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" +
  "Claude-Session: https://claude.ai/code/session_01V9zLb6Ee2kHxCcxDqzS2ae". No model IDs in code/commits.
- Every user-facing update ships a new APK via CI to release tag phone-latest. CI cancels in-progress runs
  (push once, then wait). Job logs via mcp__github__get_job_logs (no gh CLI).
- Decision log entry per change (next: D-044). Update StrategyExplainer for any new feature.
- Demo replay clock = 2026-06-22T13:30Z: backtests on demo data must end by then.
- Indicators must be causal; HTF values only from completed periods (period complete when a bar closes
  at/after period end or a later period's bar arrives). Keep the "no past value changes" test passing.
- Calendar: crypto UTC days; US stocks New York days; weeks start Monday.
- RISK_PERCENT sizing must fit (not fail) the risk-profile per-trade cap.
- Conflicting long+short signals on one bar => no trade. One partial per position max.
- Test fixture unsupported_indicator.json uses ICHIMOKU (keep an unsupported type there).
- Gemini: handle 404 via model listing; show provider error text; quota errors include provider reason.
- android/app compiles only on CI - be extra careful with Compose/Hilt edits; validate via CI.
=== END MANIFESTO ===
```
