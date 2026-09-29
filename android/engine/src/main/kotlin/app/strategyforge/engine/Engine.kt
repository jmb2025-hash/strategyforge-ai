package app.strategyforge.engine

import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.Problems
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
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.portfolio.ReconciliationDiagnostics
import app.strategyforge.engine.portfolio.ReconciliationService
import app.strategyforge.engine.risk.DefaultRiskContextFactory
import app.strategyforge.engine.risk.RiskEngine
import app.strategyforge.engine.risk.RiskEvaluationQueries
import app.strategyforge.engine.risk.RiskProfileService
import app.strategyforge.engine.risk.StandardRules
import app.strategyforge.engine.risk.StrategyDefinitionLookup
import app.strategyforge.engine.settings.SettingsService
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
    /** Live US equity data source; null until the owner adds a key. */
    equityProvider: () -> MarketDataProvider? = { null },
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
    val sources = MarketSources(marketClock, replayProvider, { fixtures.dataset.replayStart }, cryptoProvider, equityProvider)
    val settings = SettingsService(db, audit, auth, wall)
    val notifications = NotificationService(db, wall, settings)
    val instruments = InstrumentService(db, sources, audit)
    val market = MarketDataService(db, sources, marketClock, wall, audit)
    val corporateActions = CorporateActionService(db, sources, audit, wall)
    val watchlists = WatchlistService(db, instruments, audit, wall)
    val alerts = PriceAlertService(db, instruments, notifications, audit, wall)

    // ------------------------------------------------------------------ portfolios, risk, orders
    val ledger = LedgerService(db, wall)
    val lots = LotService(db)
    val portfolios = PortfolioService(db, ledger, settings, audit, wall, marketClock, market, instruments, events, auth)
    val reconciliation = ReconciliationService(db, ledger, lots, audit, notifications, wall, events)
    val riskProfiles = RiskProfileService(db, audit, wall, events, auth)
    val riskEvaluations = RiskEvaluationQueries(db)

    /** Replaced by the strategy service once it is wired (strategy-originated orders need definitions). */
    @Volatile
    var strategyDefinitions: StrategyDefinitionLookup = StrategyDefinitionLookup { throw Problems.notFound("Strategy version", it) }

    val risk =
        RiskEngine(
            { StandardRules.all() },
            DefaultRiskContextFactory(portfolios, instruments, market, marketClock, wall, db, riskProfiles) { strategyDefinitions.definition(it) },
            db,
            audit,
            wall,
            marketClock,
        )
    val orders = OrderService(db, portfolios, instruments, market, risk, ledger, audit, notifications, wall, marketClock)
    val execution = ExecutionEngine(db, orders, portfolios, instruments, market, ledger, lots, audit, notifications, wall, marketClock, events)
    val maintenance = PortfolioMaintenance(db, portfolios, instruments, corporateActions, ledger, lots, orders, audit, notifications, marketClock, reconciliation)

    // ------------------------------------------------------------------ operations
    val diagnosticsContributors = CopyOnWriteArrayList<DiagnosticsContributor>(listOf(ReconciliationDiagnostics(db)))
    val diagnostics = DiagnosticsService(db, { diagnosticsContributors.toList() }, wall)
    val marketInterests = CopyOnWriteArrayList<MarketInterest>(listOf(WatchlistInterest(db), PositionInterest(db)))
    val volumeInterests = CopyOnWriteArrayList<VolumeInterest>(listOf(OpenOrderVolumeInterest(db)))
    val ingestion = MarketDataIngestion({ marketInterests.toList() }, { volumeInterests.toList() }, instruments, market, alerts, notifications, marketClock, diagnostics)

    /** Later pipeline stages (strategy evaluation, recommendation expiry) register here, in order. */
    val stepStages = CopyOnWriteArrayList<Pair<String, (Instant, Instant) -> Unit>>()
    val replay = ReplayService(marketClock, fixtures, audit, db) { from, to -> step(from, to) }

    init {
        events.on<OrderFilled> { maintenance.onFilled(it) }
        sources.use(storedMarketMode())
    }

    /**
     * One pass of the trading pipeline, in the same order as the Version 1 server: ingest quotes,
     * simulate executions, portfolio upkeep and reconciliation, then the registered later stages.
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
