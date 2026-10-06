package app.strategyforge.engine

import app.strategyforge.engine.autonomy.ActivationService
import app.strategyforge.engine.backtest.BacktestService
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.SqlBackend
import app.strategyforge.engine.db.str
import app.strategyforge.engine.execution.ExecutionEngine
import app.strategyforge.engine.execution.OpenOrderVolumeInterest
import app.strategyforge.engine.execution.OrderFilled
import app.strategyforge.engine.execution.OrderService
import app.strategyforge.engine.execution.PortfolioMaintenance
import app.strategyforge.engine.execution.PositionInterest
import app.strategyforge.engine.market.CorporateActionService
import app.strategyforge.engine.market.EquityDataKey
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataIngestion
import app.strategyforge.engine.market.MarketDataProvider
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.market.MarketInterest
import app.strategyforge.engine.market.MarketMode
import app.strategyforge.engine.market.MarketSources
import app.strategyforge.engine.market.PriceAlertService
import app.strategyforge.engine.market.ReplayFixtures
import app.strategyforge.engine.market.ReplayProvider
import app.strategyforge.engine.market.ReplayService
import app.strategyforge.engine.market.VolumeInterest
import app.strategyforge.engine.market.WatchlistInterest
import app.strategyforge.engine.market.WatchlistService
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.operations.DiagnosticsContributor
import app.strategyforge.engine.operations.DiagnosticsService
import app.strategyforge.engine.portfolio.LedgerService
import app.strategyforge.engine.portfolio.LotService
import app.strategyforge.engine.portfolio.PortfolioChanged
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.portfolio.ReconciliationDiagnostics
import app.strategyforge.engine.portfolio.ReconciliationFailed
import app.strategyforge.engine.portfolio.ReconciliationService
import app.strategyforge.engine.reports.ExportService
import app.strategyforge.engine.reports.ReportService
import app.strategyforge.engine.research.AiBudgetService
import app.strategyforge.engine.research.AiClients
import app.strategyforge.engine.research.AiProviderService
import app.strategyforge.engine.research.InMemorySecretStore
import app.strategyforge.engine.research.ResearchService
import app.strategyforge.engine.research.SecretStore
import app.strategyforge.engine.risk.DefaultRiskContextFactory
import app.strategyforge.engine.risk.RiskEngine
import app.strategyforge.engine.risk.RiskEvaluationQueries
import app.strategyforge.engine.risk.RiskProfileChanged
import app.strategyforge.engine.risk.RiskProfileService
import app.strategyforge.engine.risk.StandardRules
import app.strategyforge.engine.settings.SettingsService
import app.strategyforge.engine.signals.EmergencyService
import app.strategyforge.engine.signals.EvaluationBlocked
import app.strategyforge.engine.signals.EvaluationFailed
import app.strategyforge.engine.signals.EvaluationService
import app.strategyforge.engine.signals.ReauthorizationRequired
import app.strategyforge.engine.signals.RecommendationService
import app.strategyforge.engine.signals.SignalDispatcher
import app.strategyforge.engine.signals.SignalQueries
import app.strategyforge.engine.signals.StrategyActivationFacade
import app.strategyforge.engine.signals.StrategyHealthMonitor
import app.strategyforge.engine.signals.StrategyInterest
import app.strategyforge.engine.strategy.StrategyService
import app.strategyforge.engine.strategy.StrategyValidator
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The on-device StrategyForge engine (D-027): one object graph over one SQLite database, used by
 * the app's UI and by its foreground service. Paper trading only; there is no broker code path.
 *
 * All calls are expected on one engine thread (the app serializes them), so no row locking is
 * needed; every multi-step change runs in a single database transaction.
 */
class Engine(
    backend: SqlBackend,
    val wall: Clock = Clock.systemUTC(),
    /** Reads replay fixture files by relative path (the app reads its assets). */
    fixtureReader: (String) -> String,
    /** Live crypto data source; null until configured. */
    cryptoProvider: () -> MarketDataProvider? = { null },
    /** Builds the live US equity source around the owner's stored key (Twelve Data, D-032). */
    equityProvider: ((apiKey: () -> String?) -> MarketDataProvider)? = null,
    /** API keys (the Android Keystore in the app). */
    val secrets: SecretStore = InMemorySecretStore(),
    aiClients: AiClients = AiClients.default(),
    /** Runs slow network work (AI calls) off the engine thread; inline by default. */
    background: (() -> Unit) -> Unit = { it() },
    /** Posts work back onto the engine thread; inline by default. */
    engineThread: (() -> Unit) -> Unit = { it() },
    /** Tests only: lets AI providers point at a local recorded-response server over http. */
    allowLocalProviderHttp: Boolean = false,
    backupDir: () -> java.io.File? = { null },
    /** Live perpetual-futures context for crypto strategies (Kraken Futures in the app, D-044). */
    derivativesProvider: () -> app.strategyforge.engine.market.DerivativesProvider? = { null },
    /** Daily TSX history with dividends for the portfolio plans (Yahoo Finance in the app, D-055). */
    tsxProvider: () -> app.strategyforge.engine.tsx.TsxHistoryProvider? = { null },
    /** Live crypto price stream (Coinbase WebSocket in the app, D-056). */
    cryptoStream: () -> app.strategyforge.engine.market.QuoteStream? = { null },
    /** Live US stock and TSX price stream (Yahoo Finance's streamer in the app, D-056). */
    stockStream: () -> app.strategyforge.engine.market.QuoteStream? = { null },
) {
    private val log = EngineLog.of(javaClass)

    val db = Db(backend).also { it.migrate() }
    val events = EngineEvents()
    val auth = RecentAuth(wall)
    val audit = AuditService(db, wall)

    // ------------------------------------------------------------------ market data
    val fixtures = ReplayFixtures(fixtureReader)
    val replayProvider = ReplayProvider(fixtures)
    val marketClock = MarketClock(db, wall)
    val equityKey = EquityDataKey(secrets, auth, audit)
    private val equities: MarketDataProvider? by lazy { equityProvider?.invoke(equityKey::get) }
    val sources = MarketSources(marketClock, replayProvider, { fixtures.dataset.replayStart }, cryptoProvider, { equities?.takeIf { equityKey.configured() } })

    val streams =
        app.strategyforge.engine.market
            .StreamHub(cryptoStream, stockStream)

    /** The US equity source even before a key is stored (for the key test screen). */
    fun equitySource(): MarketDataProvider? = equities

    val settings = SettingsService(db, audit, auth, wall)
    val notifications = NotificationService(db, wall, settings)
    val instruments = InstrumentService(db, sources, audit)
    val market = MarketDataService(db, sources, marketClock, wall, audit, { streams.quote(it) })
    val corporateActions = CorporateActionService(db, sources, audit, wall)
    val derivatives =
        app.strategyforge.engine.market
            .DerivativesService(
                sources,
                app.strategyforge.engine.market
                    .ReplayDerivatives(replayProvider),
                derivativesProvider,
            )
    val watchlists = WatchlistService(db, instruments, audit, wall)
    val alerts = PriceAlertService(db, instruments, notifications, audit, wall)

    // ------------------------------------------------------------------ portfolios, risk, orders
    val ledger = LedgerService(db, wall)
    val lots = LotService(db)
    val portfolios = PortfolioService(db, ledger, settings, audit, wall, marketClock, market, instruments, events, auth)
    val reconciliation = ReconciliationService(db, ledger, lots, audit, notifications, wall, events)
    val riskProfiles = RiskProfileService(db, audit, wall, events, auth)
    val riskEvaluations = RiskEvaluationQueries(db)

    // ------------------------------------------------------------------ strategies and backtests
    val validator = StrategyValidator({ instruments.findBySymbol(it) }, { sources.active() })
    val strategies = StrategyService(db, validator, audit, wall, sources, events)
    val backtests = BacktestService(db, strategies, instruments, market, corporateActions, sources, settings, marketClock, wall, audit, events, riskProfiles, derivatives)

    val risk =
        RiskEngine(
            { StandardRules.all() },
            DefaultRiskContextFactory(portfolios, instruments, market, marketClock, wall, db, riskProfiles, strategies),
            db,
            audit,
            wall,
            marketClock,
        )
    val orders = OrderService(db, portfolios, instruments, market, risk, ledger, audit, notifications, wall, marketClock)
    val execution = ExecutionEngine(db, orders, portfolios, instruments, market, ledger, lots, audit, notifications, wall, marketClock, events)
    val maintenance = PortfolioMaintenance(db, portfolios, instruments, corporateActions, ledger, lots, orders, audit, notifications, marketClock, reconciliation)

    // ------------------------------------------------------------------ signals, recommendations, autonomy
    init {
        riskProfiles.definitions = strategies
    }

    val activations = ActivationService(db, strategies, backtests, portfolios, riskProfiles, audit, wall, auth)
    val recommendations = RecommendationService(db, orders, market, instruments, notifications, audit, wall, marketClock)
    val dispatcher = SignalDispatcher(db, activations, risk, recommendations, orders, portfolios, notifications, events)
    val signals = SignalQueries(db)
    val evaluation =
        EvaluationService(db, activations, strategies, instruments, market, portfolios, dispatcher, notifications, audit, wall, marketClock, events, derivatives) { p, s ->
            RiskProfileService.toLimits(riskProfiles.effectiveFor(p, s))
        }
    val strategyControl = StrategyActivationFacade(db, activations) { recommendations }

    /** One crypto and one stock strategy at a time (D-035). */
    val slots =
        app.strategyforge.engine.autonomy
            .StrategySlots(db, strategies, strategyControl, activations, orders, audit)
    val emergency = EmergencyService(db, orders, portfolios, activations, strategyControl, notifications, audit, wall, auth)
    val healthMonitor = StrategyHealthMonitor(db, activations, strategies, recommendations, riskProfiles, notifications, audit)

    // ------------------------------------------------------------------ reports and exports
    val reports = ReportService(db, portfolios)

    /** Candles with trade markers and equity curves for the phone's charts (D-038). */
    val charts =
        app.strategyforge.engine.reports
            .ChartService(db, instruments, market, marketClock)

    /** Per-strategy results and the "build me a better strategy" brief (D-037). */
    val scorecards =
        app.strategyforge.engine.reports
            .ScorecardService(db, strategies, backtests, slots, wall)
    val exports = ExportService(db, portfolios, audit, wall)
    val backups =
        app.strategyforge.engine.operations
            .BackupService(db, backupDir, audit, auth, wall)

    // ------------------------------------------------------------------ AI research
    val aiProviders = AiProviderService(db, secrets, aiClients, audit, auth, wall, allowLocalProviderHttp)
    val aiBudget = AiBudgetService(db, audit, auth, wall)
    val research = ResearchService(db, aiProviders, aiClients, aiBudget, instruments, strategies, audit, wall, background, engineThread)

    // ------------------------------------------------------------------ TSX portfolio plans (D-055)
    val tsx =
        app.strategyforge.engine.tsx
            .TsxService(db, notifications, audit, wall, tsxProvider, background, engineThread, { s -> streams.tsxTick(s) })

    // ------------------------------------------------------------------ operations
    val diagnosticsContributors = CopyOnWriteArrayList<DiagnosticsContributor>(listOf(ReconciliationDiagnostics(db)))
    val diagnostics = DiagnosticsService(db, { diagnosticsContributors.toList() }, wall)
    val marketInterests = CopyOnWriteArrayList<MarketInterest>(listOf(WatchlistInterest(db), PositionInterest(db), StrategyInterest(db)))
    val volumeInterests = CopyOnWriteArrayList<VolumeInterest>(listOf(OpenOrderVolumeInterest(db)))
    val ingestion = MarketDataIngestion({ marketInterests.toList() }, { volumeInterests.toList() }, instruments, market, alerts, notifications, marketClock, diagnostics)

    /** Extra pipeline stages run after the built-in ones, in order. */
    val stepStages = CopyOnWriteArrayList<Pair<String, (Instant, Instant) -> Unit>>()
    val replay = ReplayService(marketClock, fixtures, audit, db) { from, to -> step(from, to) }

    init {
        events.on<OrderFilled> { maintenance.onFilled(it) }
        events.on<OrderFilled> { healthMonitor.onOrderFilled(it) }
        events.on<EvaluationFailed> { healthMonitor.onEvaluationFailed(it) }
        events.on<EvaluationBlocked> { healthMonitor.onEvaluationBlocked(it) }
        events.on<ReauthorizationRequired> { healthMonitor.onReauthorizationRequired(it) }
        events.on<RiskProfileChanged> { healthMonitor.onRiskProfileChanged(it) }
        events.on<PortfolioChanged> { healthMonitor.onPortfolioChanged(it) }
        events.on<ReconciliationFailed> { healthMonitor.onReconciliationFailed(it) }
        backtests.recoverInterrupted()
        research.recoverInterrupted()
        sources.use(storedMarketMode())
    }

    /**
     * One pass of the trading pipeline, in the same order as the Version 1 server: ingest quotes,
     * simulate executions, portfolio upkeep and reconciliation, strategy evaluation (signals,
     * recommendations, autonomous paper orders) and recommendation expiry.
     * A failing stage is logged and never stops the stages after it.
     */
    fun step(
        from: Instant,
        to: Instant,
    ) {
        stage("ingestion") { ingestion.refreshAll() }
        stage("execution") { execution.processAll() }
        stage("maintenance") { maintenance.runAll() }
        stage("reconciliation") { reconciliation.runDirty() }
        stage("evaluation") { evaluation.evaluateAll() }
        stage("expiry") { recommendations.expireDue() }
        stage("equity") { portfolios.recordPeriodicEquity() }
        stepStages.forEach { (name, f) -> stage(name) { f(from, to) } }
    }

    private fun stage(
        name: String,
        block: () -> Unit,
    ) {
        runCatching(block).onFailure { log.error("Pipeline stage {} failed", name, it) }
    }

    // ------------------------------------------------------------------ market mode

    fun marketMode(): MarketMode = sources.mode

    /** Switches between demo (replay) and live data; persisted across restarts. */
    fun setMarketMode(mode: MarketMode) {
        db
            .sql("insert or replace into settings(key, value) values (:k, :v)")
            .param("k", MARKET_MODE_KEY)
            .param("v", mode.name)
            .update()
        sources.use(mode)
    }

    private fun storedMarketMode(): MarketMode =
        db
            .sql("select value from settings where key = :k")
            .param("k", MARKET_MODE_KEY)
            .firstOrNull { r -> MarketMode.entries.firstOrNull { it.name == r.str("value") } }
            ?: MarketMode.DEMO

    companion object {
        const val MARKET_MODE_KEY = "market_mode"
    }
}
