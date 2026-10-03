# StrategyForge AI — Project State Manifesto

Save point: **2026-10-03T07:48Z** · version **1.8.1** (versionCode 12) · branch `claude/strategyforge-v1-delivery-vgf1ah`

```text
=== STRATEGYFORGE AI — PROJECT STATE MANIFESTO ===
SAVE POINT: 2026-10-03T07:48Z | v1.8.1 (versionCode 12) | engine 208/208 (+1 opt-in), core 27/27
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
- 1.7.0 (D-044) Crypto futures context from Kraken Futures public API (no key): indicators OPEN_INTEREST(period:
        value,change%), FUNDING_RATE(value %/h, annualized), CVD(period: value=sum delta, delta). Causal alignment
        (OI/funding latest at-or-before bar open, delta summed within bar). Backtest integrity FUTURES_DATA_*; live
        blocks PROVIDER_UNAVAILABLE / MISSING_HISTORY. Demo = ReplayDerivatives synthetic. More > Engine > "Test futures data".
- 1.8.0 (D-045) TRADING PLANS replace single strategies. Schema 2.0 (trading-plan-schema-2.0.json): context.longWhen/
        shortWhen, planRules{conflictPolicy ONE_PER_SYMBOL|STACK, capitalPolicy SHARED|ALLOCATED, maximumOpenRiskPercent},
        setups[1..8]{id,name,priority,direction,appliesWhen,allocationPercent,maximumOpenPositions,entry/exit/sizing}.
        Each setup -> synthesized 1.0 doc (TradingPlans.setupDocument) so validator/evaluator are reused. StrategyDefinition
        .setups/.plan/.setupsOrSelf(); 1.0 = one setup "MAIN". Backtester positions keyed symbol|setup; per-setup metrics.
        Live: signals.setup_id (migration 7), holdings per setup via signal->order->lot, setup-first lot relief.
        Migration 7 archives all old strategies (retired, history kept). App: Plans tab, setups + per-setup results,
        plan-rules editor (POST /v1/strategies/{id}/plan-rules -> new version). Prompts ask for plans.
- 1.8.1 (D-046) Copy prompt: per-setup inclusion, evidence labels [Published|Legacy|Observed|Proposed|Approximation],
        no silent substitution, statistics never rules, other-market setups excluded, confluence-pairs technique.
        Owner's Chart Champions research -> fixture research/chart_champions_plan_v1_reply.md (app paste-path test).
        RealDataResearchTest (opt-in, SF_REAL_BTC_CSV = Bitstamp 1m BTC 2025-01..2026-10 from
        raw.githubusercontent.com/ff137/bitstamp-btcusd-minute-data/main/data/updates/btcusd_bitstamp_1min_latest.csv).
        v1 real result Jul25-Oct26: -14.6%, 57 trades, PF 0.47 (SFP 50 trades, half stopped at 1.2%).
        Fixed: offset-aware contradiction check; HISTORY_TOO_LONG kept inside plans.

3. CURRENT WORKING STATE & BLOCKERS
- Build: GREEN. No compile errors, no unfinished files, working tree clean at 9a44046.
- Tests: engine 174/174, core 26/26 (incl. LevelIndicatorsTest 9, BacktestFeaturesTest 4,
  ChartChampionsStrategyTest 2, LiveStrategyFeaturesTest 3, LocalApiAppTest D-042, ChartsUiTest ShortingSwitch).
- futures.kraken.com and docs.kraken.com are DENIED by this cloud env network policy -> Kraken formats were
  taken from ccxt (raw.githubusercontent.com works) and doc summaries; live verification is on the phone.
- Open decisions awaiting user:
  a) (done in 1.7.0) Kraken Futures for OI / funding / delta-CVD.
  b) Live streaming upgrade (Coinbase websocket + Alpaca IEX) - offered, unanswered.
- Known approximations: volume profile and CVD derived from candles (not tick data).

4. NEXT IMMEDIATE STEPS (ranked)
 1. FIDELITY RELEASE (agreed, task 35), driven by the owner's research; compare CC plan v2 vs v1 on real data:
    structural stops (signal wick + buffer, or indicator level) and level targets (POC/VAH/VAL/range/prev H-L, R-multiples),
    up to 3 TPs, structure trail; sizing from actual stop distance; 30m bars + HTF indicator timeframes 30m/1h/4h + HTF
    candle + per-setup decision timeframe; rule ops AT_LEAST k, WITHIN n bars, FOR k of n bars; level calculator
    (A + r*(B-A)); nPOC + round-number levels; setup expiry. Then CC plan v2 fixture + real-data comparison.
 2. Then (was 1.9.0): "Copy analysis prompt" export, plan comparison charts, "build a better plan".
 3. Owner verifies Kraken futures on phone; open offer: live streaming.

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
- Decision log entry per change (next: D-047). Update StrategyExplainer for any new feature.
- Demo replay clock = 2026-06-22T13:30Z: backtests on demo data must end by then.
- Plans: every setup must stay expressible as a 1.0 doc (TradingPlans.setupDocument); keep single strategies
  (schema 1.0) working as one-setup plans (tests rely on them).
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
