# StrategyForge AI — Project State Manifesto

Save point: **2026-10-03T11:30Z** · version **1.13.0** (versionCode 18) · branch `claude/strategyforge-v1-delivery-vgf1ah`

```text
=== STRATEGYFORGE AI — PROJECT STATE MANIFESTO ===
SAVE POINT: 2026-10-03T11:30Z | v1.13.0 (versionCode 18) | engine 225 (6 opt-in real-data skipped), core 27/27
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
- 1.9.0 (D-047) FIDELITY: exitRules stop/shortStop {at SIGNAL_WICK|level, bufferPercent} (stopLossPercent = max),
        targets/shortTargets (<=3, at level | rMultiple, closePercent), breakevenAfterTarget, trailing {swingPeriod,
        afterTarget}, minimumRewardRisk; ExitPlan fixed at signal (signals.exit_plan, migration 8); RISK sizing from
        real stop. Timeframe 30m; Anchor M30/H1/H4 (HTF candle via PERIOD_LEVELS, indicator timeframe); setup
        decisionTimeframe. AT_LEAST/count; withinBars/minimumBars. LEVEL/ROUND_NUMBER/NAKED_POC. Compact schema in prompts.
        Real BTC (Jul25-Oct26): v2 default costs -16.7% PF .73; no costs +4.3%; futures costs -12.6%; EMA swing strong,
        mechanical SFP/failed auction no edge. Fixture research/chart_champions_plan_v2_reply.md.
- (D-048) PLAN v3 TUNED: sweep of ~150 v2 variants (SF_SWEEP_DIR), chosen on 2025 only, futures costs, 1% risk.
        v3 = CC_FIB (>=2 confluences, stop f786+0.3%, 1R half/3R) + EMA_SWING as published, STACK, open risk 2.5%.
        2025 +10.9% (dd 6.5), Q1-26 +10.4%, Q2-26 +3.6%, Q3-26 +0.7%; win 48-60% per position. SFP/CCV/FA dropped
        (FA best 2025 variant +9.9% then -13..-15% each 2026 quarter = overfit). CC public figures have no balances,
        so no % match is possible; per-quarter ranking Q1-26 > Q2-26 > 2025 avg matches theirs. Fixture plan_v3.md.
- 1.10.0 (D-049) BUILT-IN PLANS: resources /library/*.json + research/PlanLibrary; GET /v1/library, POST
        /v1/library/{id}/add; app Plans screen "Built-in plans". Plans: btc-trend-pullback (4h; prev daily close vs
        1d SMA50 picks side; RSI4 <10 long / >90 short; exit RSI4 x 50, 5% stop, 60 bars; 95% equity),
        chart-champions-v3, chart-champions-v3-plus-trend-pullback (ALLOCATED 25/25/50, STACK).
        $10k app costs Jan25-Oct26: pullback +25.0% dd 5.2 83% win (2025 +17.2, 2026 +6.6); v3 +13.1% dd 12.4;
        combined +9.9% dd 3.6. One crypto slot only -> combined plan is the side-by-side option; a 2nd crypto slot
        (separate portfolios) awaits the owner's decision. Sweep: SF_START, SF_COSTS=app, timeframe-aware bars.
- 1.10.1 (D-050) HISTORY 2018-2026: Bitstamp 1m merged (scratch btc1m_2018.csv from data/historical
        btcusd_bitstamp_1min_2012-2025.csv.gz + updates). 4h pullback WITHDRAWN (lost 5 of 7 earlier years); combined
        plan removed. Library = btc-daily-trend-dip-rip (1d; close vs SMA40 picks side; RSI2<15 long / >85 short;
        exit close x SMA10, 8% stop, 30 bars; 95% equity; $10k app costs: +270% 2018-Oct26, dd 21.6, 73% win,
        7/9 years up; chosen on 2018-22, 2023-26 +26%) + chart-champions-v3 (4/9 years up). History write-up:
        docs/research/BTC_HISTORY_2018_2026.md. Harness SF_PERIODS=years.
- 1.11.0 (D-051) SLOTS: 10 crypto + 10 stock numbered slots (migration 9 strategy_activations.slot); activate(slot?)
        -> keep slot / first free / slots-full; occupied -> slot-occupied keep/close; symbol-shared gate (one running
        strategy per symbol per portfolio); portfolio cap 25; app slot picker + "New portfolio" per slot.
- 1.12.0 (D-052) STOCK RESEARCH: stock hosts BLOCKED by env network policy (owner told to allow
        query1/query2.finance.yahoo.com, fc.yahoo.com, stooq.com). Used QuantConnect/Lean sample daily data on
        raw.githubusercontent (Data/equity/usa/daily/<t>.zip + factor_files; 1998-2021-03; spy qqq iwm aapl ibm bac aig
        goog). Harness: stock sweep SF_STOCK_DIR (+SF_FIRST/LAST/SPLIT_YEAR). Library now: btc-trend-core (close>SMA100
        hold; +1985% 2018-Oct26, dd 37), btc-daily-trend-dip-rip, chart-champions-v3, index-dip-score (SPY/QQQ/IWM,
        >=2 of 4 oversold signals above SMA50, exit >SMA5; +33% 1998-2021, 73% win), spy-trend-core (SMA200; +224%).
        Report + slot plan: docs/research/STRATEGY_RESEARCH_2026-10.md.
- 1.13.0 (D-053) YAHOO OPEN: query1.finance.yahoo.com chart API works (no key); 36 master symbols 2015-01-02..2026-10-02,
        adjusted via adjclose (scratch stocks/yahoo, daily2). Unseen 2021-26: spy-trend-core +87% dd 18.7; index-dip +14%
        71% win. New large-cap-dip-score (31 stocks, k2, 5x19%): 2015-26 +80% dd 13.5, 64% win. SPY B&H +355% dd 34.
- (D-054) TSX RESEARCH (docs only): Yahoo .TO data 175 listings 2014-10..2026-10; python sim docs/research/tsx/scripts.
        P1 div growth+momentum 24 (17.0%/yr dd38.5), P2 high-yield trend 12 (11.1%, dd11.4), P3 core-satellite (14.2%, dd24),
        P4 momentum rotation 50/50 (17.8%, dd32.6). Report artifact https://claude.ai/artifact/MBGgKnTkLGtMDb7sUnVZWq.
        App cannot run them yet (no TSX/CAD instruments, no allocation/rebalance plan type) - awaiting owner decision.
- 1.14.0 (D-055) TSX PLANS IN APP: engine/tsx subsystem (TsxService, Book, PlanRules, PlanSimulator, YahooTsxProvider,
        resources/tsx universe.json 173 listings + plans.json 4 plans w/ research), migration 10 (tsx_history, tsx_runs,
        tsx_run_values, tsx_run_events, tsx_backtests), 10 TSX slots, NOTIFY (approve/decline) or AUTONOMOUS, DRIP or
        paid out, scheduler task "tsx" (auto refresh 6h while a run is active). API /v1/tsx/*. App: Strategies ->
        "TSX plans" screen + tsx-run/{id}; deep link strategyforge://tsxrun/{id}. Doubles for plan math (documented).
        Kotlin/Python parity P1 47,908/47,930 P2 28,729 P3 37,748/37,750 P4 51,497.
- 1.15.0 (D-056) LIVE STREAMING: market/QuoteStreams.kt (CoinbaseQuoteStream advanced-trade-ws ticker+heartbeats, no key;
        AlpacaQuoteStream IEX, owner key pair via StreamKey/Keystore, 30 symbols), StreamHub; scheduler task "stream" every
        tick stores fresh ticks (crypto <=30s, stocks <=5min) under the class's provider name, else 15s polling; polled
        quotes older than streamed ones are skipped (no OUT_OF_ORDER). TSX intraday display (Yahoo regularMarketPrice,
        1/min 09:30-16:15 Toronto, liveValue/livePrice), TSX daily refresh ends at last completed session. Screens
        auto-refresh (AutoRefresh). API /v1/market-data/streams, PUT /v1/market-data/stocks/stream-key. Owner unblocked
        stream.data.alpaca.markets + advanced-trade-ws.coinbase.com here; LiveStreamsTest (SF_LIVE_STREAMS=1) passes live.
- 1.16.0 (D-057) YAHOO STREAM: owner cannot use Alpaca -> removed (AlpacaQuoteStream, StreamKey, stream-key API/UI).
        YahooQuoteStream wss://streamer.finance.yahoo.com/?version=2, no key, {"subscribe":[..]} repeated every 15 s,
        base64 protobuf PricingData decoded by hand (YahooPricing), regular session only, BRK.B<->BRK-B. Streams watched
        US stocks (LIVE) + TSX run holdings as SYM.TO during the TSX session (both modes); TSX live value = newer of
        streamed/polled. Owner unblocked streamer.finance.yahoo.com; live test passes (SF_LIVE_STREAMS=1
        SF_LIVE_YAHOO=1): BTC-USD 85,440.24 vs Coinbase 85,440.25 on 2026-10-04. Market-open check 2026-10-05
        (SF_LIVE_YAHOO_STOCKS=1): AAPL 334.68 vs chart 334.655, RY.TO 277.20 vs 277.16, REALTIME, <1 s. 1.17.2: bid/ask
        kept only as a pair (Yahoo sends none or one side).
- 1.17.0 (D-058) PLANS IN PORTFOLIO MENU: Portfolio screen "Running plans" chips (slot strategies + active TSX runs).
        Slot plan -> its portfolio + SlotPlanCard (scorecard realized, own-position unrealized, trades) and positions/orders
        filtered to the plan; TSX plan -> TsxRunDetail inline. App-only (RunningPlan in ViewModels.kt).
- 1.18.0 (D-060) PLANS ON HOME: HomeViewModel.plans (PlanSnapshot Slot/Tsx, every 15 s) -> PlanSnapshotCard under the
        portfolio card; tap opens portfolio?plan=<key> (PortfolioViewModel preselects it).
- 1.18.1 (D-061) Plans screen "Running now" lists TSX slots (TsxSlotCards: Open -> tsx-run/id, Stop with confirm).
- 1.18.2 (D-062) TSX Book.rebalance sets costs aside (bisection on invested fraction, cash >= 0); coverNegativeCash
        fixes older runs next trading day. Parity now P1 47,834 P2 28,719 P3 37,742 P4 51,458 (research 47,930/...).
- 1.19.0 (D-063) PLAN RISK LIMITS: global 20%/25%/crypto 50% blocked BTC trend core (95%) forever. RiskProfileService
        .baseFor/planBase: a running plan's own orders in its portfolio use the plan's sizing/loss limits in place of
        global ones (backtests too). Sizing capped by activation allocation + buying power, margin 0.995. More -> Risk
        limits screen (GET /v1/risk/limits, PUT /v1/risk/limits/global, loosening needs device lock).

3. CURRENT WORKING STATE & BLOCKERS
- Build: GREEN. No compile errors, no unfinished files, working tree clean after the D-055 commit.
- Tests: engine 250 (248 + 2 opt-in live; incl. market QuoteStreamTest, LiveStreamsTest, tsx BookTest, TsxServiceTest, YahooTsxProviderTest; LocalApiAppTest TSX + stream key), core 27/27 (incl. LevelIndicatorsTest 9, BacktestFeaturesTest 4,
  ChartChampionsStrategyTest 2, LiveStrategyFeaturesTest 3, LocalApiAppTest D-042, ChartsUiTest ShortingSwitch).
- futures.kraken.com and docs.kraken.com are DENIED by this cloud env network policy -> Kraken formats were
  taken from ccxt (raw.githubusercontent.com works) and doc summaries; live verification is on the phone.
- Open decisions awaiting user:
  a) (done in 1.7.0) Kraken Futures for OI / funding / delta-CVD.
  b) (done in 1.15.0/1.16.0) Live streaming: Coinbase (crypto) + Yahoo Finance (US stocks, TSX holdings), no keys.
- Known approximations: volume profile and CVD derived from candles (not tick data).

4. NEXT IMMEDIATE STEPS (ranked)
 1. Owner imports research with Copy instructions on 1.9.0; review readback/approximations; check portfolio cost model
    (default crypto fallback spread 0.20%/side is very conservative; real-data runs show costs dominate tight stops).
 2. Agreed next: "Copy analysis prompt" export (plan + per-setup results + trades for any AI), plan comparison charts,
    "build a better plan". Consider exposing realistic cost presets (spot vs perpetual) in the app.
 3. Owner verifies Kraken futures and the live streams (Engine screen: Live price streaming) on the phone.
 4. Known limits: one signal per symbol per bar; one plan timeframe (setups decide on longer ones via decisionTimeframe);
    no cross-symbol (alt vs BTC) rules; no laddered entries (CCW).

5. CRITICAL CAVEATS & CONSTRAINTS
- Paper trading only. Never real orders. Shorts are simulated.
- Secrets: never in source/APK/logs/chat; Keystore only. gitleaks must pass; fake-key test false
  positives go in .gitleaksignore as sha:file:rule:line.
- Before EVERY commit: engine + core tests, spotless (scratch project scratchpad/fmt: gradle
  spotlessApply then spotlessCheck), gitleaks (scratchpad/gitleaks).
- Commit trailer: "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" +
  "Claude-Session: https://claude.ai/code/session_01V9zLb6Ee2kHxCcxDqzS2ae". No model IDs in code/commits.
- Every user-facing update ships a new APK via CI to release tag phone-latest, signed with the owner's PERMANENT key
  (D-059: phone-release refuses any other certificate; updates install over the old app and keep all data). CI cancels in-progress runs
  (push once, then wait). Job logs via mcp__github__get_job_logs (no gh CLI).
- Decision log entry per change (next: D-064). Update StrategyExplainer for any new feature.
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
