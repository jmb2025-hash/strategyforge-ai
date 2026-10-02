@file:UseSerializers(BigDecimalSerializer::class, InstantSerializer::class, UUIDSerializer::class)

package app.strategyforge.engine.backtest

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.BigDecimalSerializer
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.InstantSerializer
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.UUIDSerializer
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.int
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.BarSchedule
import app.strategyforge.engine.market.CorporateActionService
import app.strategyforge.engine.market.DataStatus
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.market.MarketSources
import app.strategyforge.engine.market.Timeframe
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.risk.EffectiveLimits
import app.strategyforge.engine.risk.RiskLevel
import app.strategyforge.engine.risk.RiskLimits
import app.strategyforge.engine.risk.RiskProfileService
import app.strategyforge.engine.settings.SettingsService
import app.strategyforge.engine.strategy.StrategyService
import app.strategyforge.engine.strategy.StrategyStatus
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class BacktestRequest(
    val strategyId: UUID,
    val versionId: UUID? = null,
    val from: Instant,
    val to: Instant,
    val startingCapital: BigDecimal? = null,
    val costModel: CostModel? = null,
    val executionDelayBars: Int = 1,
    val benchmarkSymbol: String? = null,
    val symbols: List<String>? = null,
    /** Extra risk limits for this run; merged strictest-wins with the global profile when [applyGlobalRiskProfile] (FR-051). */
    val riskProfile: RiskLimits? = null,
    val applyGlobalRiskProfile: Boolean = true,
)

data class IntegrityIssue(
    val severity: String,
    val symbol: String?,
    val code: String,
    val message: String,
)

data class BacktestView(
    val id: UUID,
    val strategyId: UUID,
    val versionId: UUID,
    val contentHash: String,
    val status: String,
    val resultStatus: String?,
    val params: JsonNode,
    val dataset: JsonNode?,
    val integrity: JsonNode?,
    val metrics: JsonNode?,
    val benchmark: JsonNode?,
    val error: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

/** Published when a backtest completes (success or failure). */
data class BacktestCompleted(
    val backtestId: UUID,
    val strategyId: UUID,
    val resultStatus: String?,
)

/**
 * Backtest orchestration (section 12, FR-050..FR-055): loads point-in-time data as of the market
 * clock, records dataset provenance and integrity, applies corporate actions or marks the result
 * Manual Review Required, runs the engine, stores metrics/series/trades, and promotes the strategy
 * to Paper Eligible only when integrity has no critical errors.
 */
class BacktestService(
    private val db: Db,
    private val strategies: StrategyService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val corporate: CorporateActionService,
    private val registry: MarketSources,
    private val settings: SettingsService,
    private val marketClock: MarketClock,
    private val clock: Clock,
    private val audit: AuditService,
    private val events: EngineEvents,
    private val profiles: RiskProfileService,
    private val derivatives: app.strategyforge.engine.market.DerivativesService? = null,
) {
    private val log = EngineLog.of(javaClass)
    private val mapper = JacksonCanonical.mapper

    /**
     * Queues and runs a backtest. On the phone the caller is already on the engine's background
     * thread, so the run happens inline; the stored QUEUED/RUNNING states still make an
     * interrupted run (process killed) visible and recoverable.
     */
    fun submit(req: BacktestRequest): BacktestView {
        val id = queueId(req)
        execute(id)
        return get(id)
    }

    private fun queueId(req: BacktestRequest): UUID =
        db.tx {
            val s = strategies.get(req.strategyId)
            val versionId = req.versionId ?: s.currentVersionId ?: throw Problems.conflict("no-version", "Strategy has no version")
            val v = strategies.version(versionId)
            if (v.strategyId != s.id) throw Problems.badRequest("version-mismatch", "Version does not belong to the strategy")
            if (v.validationStatus != "VALIDATED") throw Problems.conflict("strategy-not-validated", "Only validated versions can be backtested (status ${v.validationStatus})")
            val now = marketClock.now()
            if (!req.from.isBefore(req.to)) throw Problems.badRequest("invalid-range", "from must be before to")
            if (req.to.isAfter(now)) throw Problems.badRequest("future-range", "Backtests cannot use data after the current market time $now (point-in-time)")
            if (Duration.between(req.from, req.to) > Duration.ofDays(MAX_DAYS)) throw Problems.badRequest("range-too-large", "Backtest ranges are limited to $MAX_DAYS days")
            if (req.executionDelayBars !in 1..10) throw Problems.badRequest("invalid-delay", "executionDelayBars must be 1-10")
            val capital = req.startingCapital ?: settings.get().portfolioDefaults.startingBalance
            if (capital < BigDecimal(100) || capital > BigDecimal(100_000_000)) throw Problems.badRequest("invalid-capital", "startingCapital must be 100 - 100,000,000")
            req.riskProfile?.let { profiles.validate(it) }
            val levels =
                listOfNotNull(
                    profiles
                        .global()
                        .limits
                        .takeIf { req.applyGlobalRiskProfile }
                        ?.let { RiskLevel.GLOBAL to it },
                    req.riskProfile?.let { RiskLevel.PORTFOLIO to it },
                )
            // The stored parameters record the exact merged limits the run used.
            val risk = if (levels.isEmpty()) RiskLimits() else RiskProfileService.toLimits(EffectiveLimits.merge(levels))
            val params = req.copy(versionId = versionId, startingCapital = capital, costModel = req.costModel ?: settings.get().portfolioDefaults.costModel(), riskProfile = risk)
            val id = UUID.randomUUID()
            db
                .sql("insert into backtests(id, strategy_id, version_id, content_hash, status, params, created_at) values (:id, :s, :v, :h, 'QUEUED', :p, :now)")
                .param("id", id)
                .param("s", s.id)
                .param("v", versionId)
                .param("h", v.contentHash)
                .param("p", EngineJson.encodeToString(BacktestRequest.serializer(), params))
                .param("now", (clock.instant()))
                .update()
            audit.record(AuditCategory.BACKTEST, "BACKTEST_QUEUED", entityType = "Backtest", entityId = id, details = mapOf("strategyId" to s.id, "hash" to v.contentHash, "from" to req.from.toString(), "to" to req.to.toString()))
            id
        }

    fun execute(id: UUID) {
        val started =
            db
                .sql("update backtests set status = 'RUNNING', started_at = :now where id = :id and status = 'QUEUED'")
                .param("now", (clock.instant()))
                .param("id", id)
                .update()
        if (started == 0) return
        try {
            val result = runBacktest(id)
            db.tx { complete(id, result) }
        } catch (e: Exception) {
            log.error("Backtest {} failed", id, e)
            db
                .sql("update backtests set status = 'FAILED', error = :e, completed_at = :now where id = :id")
                .param("e", "${e.javaClass.simpleName}: ${e.message}".take(500))
                .param("now", (clock.instant()))
                .param("id", id)
                .update()
            audit.record(AuditCategory.BACKTEST, "BACKTEST_FAILED", AuditOutcome.FAILURE, "Backtest", id, mapOf("error" to e.javaClass.simpleName))
            val s = get(id)
            events.publish(BacktestCompleted(id, s.strategyId, null))
        }
    }

    private data class RunResult(
        val output: BacktestOutput,
        val metrics: Map<String, Any?>,
        val dataset: Map<String, Any?>,
        val integrity: List<IntegrityIssue>,
        val resultStatus: String,
        val benchmark: Map<String, Any?>?,
    )

    private fun runBacktest(id: UUID): RunResult {
        val bt = get(id)
        val req = EngineJson.decodeFromString(BacktestRequest.serializer(), bt.params.toString())
        val def = strategies.definition(bt.versionId)
        val symbols = req.symbols?.filter { it in def.symbols }?.takeIf { it.isNotEmpty() } ?: def.symbols
        val asOf = minOf(req.to, marketClock.now())
        val issues = mutableListOf<IntegrityIssue>()
        val datasetSymbols = mutableListOf<Map<String, Any?>>()
        val series = mutableListOf<SymbolSeries>()
        var manualReview = false
        val provider = registry.nameFor(def.assetClass)
        symbols.forEach { sym ->
            val i = instruments.bySymbol(sym)
            val warmup = BarSchedule.lookback(i.assetClass, def.timeframe, def.minimumHistoryBars + 5)
            val cs = market.candles(i, def.timeframe, req.from.minus(warmup), req.to, asOf)
            val inWindow = cs.bars.filter { !it.openTime.isBefore(req.from) }
            val before = cs.bars.size - inWindow.size
            val gapsInWindow = cs.gaps.filter { !it.isBefore(req.from) }
            val gapRatio = if (inWindow.isEmpty()) BigDecimal.ONE else BigDecimal(gapsInWindow.size).divide(BigDecimal(inWindow.size + gapsInWindow.size), MathContext.DECIMAL64)
            when {
                cs.status in setOf(DataStatus.UNSUPPORTED, DataStatus.OUT_OF_ORDER, DataStatus.MALFORMED, DataStatus.CLOCK_SKEW, DataStatus.PROVIDER_ERROR) ->
                    issues += IntegrityIssue("CRITICAL", sym, "DATA_${cs.status}", cs.detail)
                inWindow.isEmpty() -> issues += IntegrityIssue("CRITICAL", sym, "MISSING_DATA", "No bars for $sym in the test window")
                gapRatio > MAX_GAP_RATIO -> issues += IntegrityIssue("CRITICAL", sym, "EXCESSIVE_GAPS", "${gapsInWindow.size} missing bars (${Decimals.percent(gapRatio.multiply(Decimals.HUNDRED))}%)")
                gapsInWindow.isNotEmpty() -> issues += IntegrityIssue("WARNING", sym, "DATA_GAPS", "${gapsInWindow.size} missing bar(s): ${gapsInWindow.take(5)}")
            }
            if (before < def.minimumHistoryBars && inWindow.isNotEmpty()) {
                issues += IntegrityIssue("WARNING", sym, "SHORT_WARMUP", "Only $before warm-up bars before the window (strategy needs ${def.minimumHistoryBars}); early signals are suppressed")
            }
            val actions =
                if (i.assetClass == AssetClass.US_EQUITY) {
                    val fromDate = req.from.atZone(MarketCalendar.NEW_YORK).toLocalDate()
                    val toDate = asOf.atZone(MarketCalendar.NEW_YORK).toLocalDate()
                    if (!corporate.covered(i, fromDate, toDate)) {
                        manualReview = true
                        issues += IntegrityIssue("REVIEW", sym, "CORPORATE_ACTIONS_UNAVAILABLE", "Corporate-action data unavailable; results for $sym require manual review")
                        emptyList()
                    } else {
                        corporate.actions(i.id, fromDate, toDate).map { SimpleCorporateAction(it.type, it.exDate, it.ratioNew, it.ratioOld, it.cashAmount) }
                    }
                } else {
                    emptyList()
                }
            val futures =
                if (def.usesDerivatives && cs.bars.isNotEmpty()) {
                    derivativesFor(def, sym, cs.bars, inWindow, asOf, issues)
                } else {
                    null
                }
            series += SymbolSeries(sym, i.assetClass, cs.bars, i.priceIncrement, i.quantityIncrement, i.minQuantity, actions, futures)
            datasetSymbols +=
                mapOf(
                    "symbol" to sym,
                    "provider" to cs.provider,
                    "timeframe" to def.timeframe.code,
                    "sourceTimeframe" to cs.sourceTimeframe?.code,
                    "barsInWindow" to inWindow.size,
                    "warmupBars" to before,
                    "firstBar" to inWindow.firstOrNull()?.openTime,
                    "lastBar" to inWindow.lastOrNull()?.openTime,
                    "gaps" to gapsInWindow.size,
                    "corporateActions" to actions.size,
                    "futuresData" to futures?.source,
                    "status" to cs.status,
                )
        }
        val benchSymbol = req.benchmarkSymbol ?: if (def.assetClass == AssetClass.CRYPTO) "BTC-USD" else "SPY"
        val benchmark = benchmark(benchSymbol, req.from, asOf, issues)
        val out = BacktestEngine(def, BacktestParams(req.from, asOf, req.startingCapital!!, req.costModel!!, req.executionDelayBars, req.riskProfile ?: RiskLimits())).run(series)
        val metrics = BacktestMetrics.compute(out, req.startingCapital, req.from, asOf, benchmark?.get("returnFraction") as BigDecimal?)
        val status =
            when {
                issues.any { it.severity == "CRITICAL" } -> "CRITICAL"
                manualReview -> "MANUAL_REVIEW_REQUIRED"
                issues.isNotEmpty() -> "WARNINGS"
                else -> "OK"
            }
        val dataset =
            mapOf(
                "provider" to provider,
                "synthetic" to (provider == "REPLAY"),
                "asOf" to asOf,
                "from" to req.from,
                "to" to req.to,
                "timeframe" to def.timeframe.code,
                "symbols" to datasetSymbols,
                "adjustments" to "Unadjusted prices; splits and cash dividends applied as events on ex-dates",
                "assumptions" to
                    listOf(
                        "Signals at bar close fill at the open of bar +${req.executionDelayBars}",
                        "Stops and targets rest intrabar; the stop is assumed first when both are touched",
                        "Fallback spread and configured slippage apply to market fills; limit and target fills take no slippage",
                        "Fills capped at ${req.costModel.participationRatePercent}% of bar volume; unfilled remainder is dropped",
                        "No look-ahead: indicators are causal and only bars closed by each decision time are used",
                    ),
            )
        return RunResult(out, metrics, dataset, issues, status, benchmark?.minus("returnFraction"))
    }

    private fun benchmark(
        symbol: String,
        from: Instant,
        to: Instant,
        issues: MutableList<IntegrityIssue>,
    ): Map<String, Any?>? {
        val i = instruments.findBySymbol(symbol) ?: return null.also { issues += IntegrityIssue("WARNING", symbol, "BENCHMARK_UNKNOWN", "Benchmark $symbol is not in the instrument master") }
        val s = market.candles(i, Timeframe.D1, from.minus(Duration.ofDays(5)), to, to)
        val bars = s.bars.filter { !it.openTime.isBefore(from.minus(Duration.ofDays(1))) }
        if (bars.size < 2) {
            issues += IntegrityIssue("WARNING", symbol, "BENCHMARK_UNAVAILABLE", "Insufficient benchmark data for $symbol")
            return null
        }
        val first = bars.first().open
        val last = bars.last().close
        val ret = last.subtract(first).divide(first, MathContext.DECIMAL128)
        return mapOf(
            "symbol" to symbol,
            "start" to first,
            "end" to last,
            "startTime" to bars.first().openTime,
            "endTime" to bars.last().openTime,
            "returnPercent" to Decimals.percent(ret.multiply(Decimals.HUNDRED)),
            "returnFraction" to ret,
            "method" to "Buy and hold on daily bars",
        )
    }

    private fun complete(
        id: UUID,
        r: RunResult,
    ) {
        val bt = get(id)
        r.output.trades.forEach { t ->
            db
                .sql(
                    """
                    insert into backtest_trades(backtest_id, symbol, side, entry_time, entry_price, exit_time, exit_price, quantity, gross_pnl, fees, spread_cost, slippage_cost,
                      borrow_cost, dividends, net_pnl, holding_bars, exit_reason, partial_fill)
                    values (:b, :s, :side, :et, :ep, :xt, :xp, :q, :g, :f, :sc, :sl, :bc, :d, :n, :h, :r, :pf)
                    """.trimIndent(),
                ).param("b", id)
                .param("s", t.symbol)
                .param("side", t.side)
                .param("et", (t.entryTime))
                .param("ep", t.entryPrice)
                .param("xt", (t.exitTime))
                .param("xp", t.exitPrice)
                .param("q", t.quantity)
                .param("g", t.grossPnl)
                .param("f", t.fees)
                .param("sc", t.spreadCost)
                .param("sl", t.slippageCost)
                .param("bc", t.borrowCost)
                .param("d", t.dividends)
                .param("n", t.netPnl)
                .param("h", t.holdingBars)
                .param("r", t.exitReason)
                .param("pf", t.partialFill)
                .update()
        }

        fun points(xs: List<SeriesPoint>) = xs.map { mapOf("t" to it.t, "v" to it.v) }.toJsonElement().toString()

        fun downsample(xs: List<SeriesPoint>) = if (xs.size <= MAX_POINTS) xs else xs.filterIndexed { i, _ -> i % (xs.size / MAX_POINTS + 1) == 0 } + xs.last()
        db
            .sql(
                """
                update backtests set status = 'COMPLETED', result_status = :rs, dataset = :d, integrity = :i, metrics = :m,
                  equity_series = :e, drawdown_series = :dd, benchmark = :b, completed_at = :now where id = :id
                """.trimIndent(),
            ).param("rs", r.resultStatus)
            .param("d", r.dataset.toJsonElement().toString())
            .param("i", mapOf("status" to r.resultStatus, "issues" to r.integrity.map { mapOf("severity" to it.severity, "symbol" to it.symbol, "code" to it.code, "message" to it.message) }).toJsonElement().toString())
            .param("m", r.metrics.toJsonElement().toString())
            .param("e", points(downsample(r.output.equity)))
            .param("dd", points(downsample(r.output.drawdown)))
            .param("b", r.benchmark?.toJsonElement()?.toString())
            .param("now", (clock.instant()))
            .param("id", id)
            .update()
        audit.record(
            AuditCategory.BACKTEST,
            "BACKTEST_COMPLETED",
            if (r.resultStatus == "CRITICAL") AuditOutcome.BLOCKED else AuditOutcome.SUCCESS,
            "Backtest",
            id,
            mapOf("resultStatus" to r.resultStatus, "trades" to r.output.trades.size, "hash" to bt.contentHash, "critical" to r.integrity.filter { it.severity == "CRITICAL" }.map { it.code }),
        )
        // Promotion to Paper Eligible requires a clean backtest of the exact current version (FR-055).
        val s = strategies.lock(bt.strategyId)
        if (s.currentVersionId == bt.versionId && s.status == StrategyStatus.VALIDATED) {
            when (r.resultStatus) {
                "OK", "WARNINGS" -> {
                    strategies.transition(s.id, StrategyStatus.VALIDATED, StrategyStatus.BACKTESTED, "Backtest $id completed (${r.resultStatus})")
                    strategies.transition(s.id, StrategyStatus.BACKTESTED, StrategyStatus.PAPER_ELIGIBLE, "Backtest integrity has no critical errors")
                }
                else -> audit.record(AuditCategory.STRATEGY, "PAPER_ELIGIBILITY_DENIED", AuditOutcome.BLOCKED, "Strategy", s.id, mapOf("backtestId" to id, "resultStatus" to r.resultStatus))
            }
        }
        events.publish(BacktestCompleted(id, bt.strategyId, r.resultStatus))
    }

    /** Latest completed backtest usable for activation: same content hash and no critical/MRR integrity (FR-055). */
    fun eligibleBacktest(versionId: UUID): BacktestView? =
        db
            .sql("select * from backtests where version_id = :v and status = 'COMPLETED' and result_status in ('OK', 'WARNINGS') order by completed_at desc limit 1")
            .param("v", versionId)
            .firstOrNull { rs -> map(rs) }

    fun get(id: UUID): BacktestView =
        db
            .sql("select * from backtests where id = :id")
            .param("id", id)
            .firstOrNull { rs -> map(rs) } ?: throw Problems.notFound("Backtest", id)

    fun list(strategyId: UUID?): List<BacktestView> =
        db
            .sql("select * from backtests where (:s is null or strategy_id = :s) order by created_at desc limit 200")
            .param("s", strategyId)
            .list { rs -> map(rs) }

    fun trades(id: UUID): List<Map<String, Any?>> =
        db
            .sql("select * from backtest_trades where backtest_id = :b order by entry_time, id")
            .param("b", id)
            .list { rs ->
                mapOf(
                    "symbol" to rs.str("symbol"),
                    "side" to rs.str("side"),
                    "entryTime" to rs.instant("entry_time"),
                    "entryPrice" to rs.dec("entry_price"),
                    "exitTime" to rs.instantOrNull("exit_time"),
                    "exitPrice" to rs.decOrNull("exit_price"),
                    "quantity" to rs.dec("quantity"),
                    "grossPnl" to rs.dec("gross_pnl"),
                    "fees" to rs.dec("fees"),
                    "spreadCost" to rs.dec("spread_cost"),
                    "slippageCost" to rs.dec("slippage_cost"),
                    "borrowCost" to rs.dec("borrow_cost"),
                    "dividends" to rs.dec("dividends"),
                    "netPnl" to rs.dec("net_pnl"),
                    "holdingBars" to rs.int("holding_bars"),
                    "exitReason" to rs.string("exit_reason"),
                    "partialFill" to rs.bool("partial_fill"),
                )
            }

    fun series(id: UUID): Map<String, Any?> =
        db
            .sql("select equity_series, drawdown_series, benchmark from backtests where id = :id")
            .param("id", id)
            .single { rs ->
                mapOf("equity" to rs.string("equity_series")?.let { mapper.readTree(it) }, "drawdown" to rs.string("drawdown_series")?.let { mapper.readTree(it) }, "benchmark" to rs.string("benchmark")?.let { mapper.readTree(it) })
            }

    /** Backtests interrupted by a restart are failed explicitly, never left running. */
    fun recoverInterrupted() {
        val n =
            db
                .sql("update backtests set status = 'FAILED', error = 'Interrupted before it finished (app closed); rerun the backtest', completed_at = :now where status in ('QUEUED', 'RUNNING')")
                .param("now", (clock.instant()))
                .update()
        if (n > 0) log.warn("Marked {} interrupted backtest(s) as failed", n)
    }

    private fun map(rs: Row): BacktestView {
        fun json(c: String) = rs.string(c)?.let { mapper.readTree(it) }
        return BacktestView(
            rs.uuid("id"),
            rs.uuid("strategy_id"),
            rs.uuid("version_id"),
            rs.str("content_hash"),
            rs.str("status"),
            rs.string("result_status"),
            json("params")!!,
            json("dataset"),
            json("integrity"),
            json("metrics"),
            json("benchmark"),
            rs.string("error"),
            rs.instant("created_at"),
            rs.instantOrNull("started_at"),
            rs.instantOrNull("completed_at"),
        )
    }

    /**
     * Futures context for a crypto backtest (D-044). Missing data fails the backtest; partial coverage
     * of the test window is a warning naming how much is covered.
     */
    private fun derivativesFor(
        def: app.strategyforge.engine.strategy.StrategyDefinition,
        sym: String,
        bars: List<app.strategyforge.engine.market.CandleData>,
        inWindow: List<app.strategyforge.engine.market.CandleData>,
        asOf: Instant,
        issues: MutableList<IntegrityIssue>,
    ): app.strategyforge.engine.market.DerivativesData? {
        val svc = derivatives ?: return null.also { issues += IntegrityIssue("CRITICAL", sym, "FUTURES_DATA_UNAVAILABLE", "No futures data source is available") }
        return when (val r = svc.forBars(sym, def.timeframe, bars, asOf)) {
            is app.strategyforge.engine.market.ProviderResult.Ok -> {
                val (data, aligned) = r.value
                val first = bars.size - inWindow.size
                val needed = def.indicators.map { it.type }.toSet()
                listOfNotNull(
                    aligned.openInterest.takeIf { app.strategyforge.engine.strategy.IndicatorType.OPEN_INTEREST in needed }?.let { "open interest" to it },
                    aligned.fundingRate.takeIf { app.strategyforge.engine.strategy.IndicatorType.FUNDING_RATE in needed }?.let { "funding" to it },
                    aligned.delta.takeIf { app.strategyforge.engine.strategy.IndicatorType.CVD in needed }?.let { "delta" to it },
                ).forEach { (label, values) ->
                    val window = values.drop(first)
                    val covered = window.count { it != null }
                    when {
                        window.isEmpty() -> Unit
                        covered == 0 -> issues += IntegrityIssue("CRITICAL", sym, "FUTURES_DATA_MISSING", "${data.source} has no $label data for $sym in the test window")
                        covered < window.size -> {
                            val firstCovered = inWindow.getOrNull(window.indexOfFirst { it != null })?.openTime
                            issues += IntegrityIssue("WARNING", sym, "FUTURES_DATA_PARTIAL", "${data.source} $label covers $covered of ${window.size} bars (from $firstCovered); rules using it cannot trigger on the rest")
                        }
                    }
                }
                data
            }
            is app.strategyforge.engine.market.ProviderResult.Failed -> null.also { issues += IntegrityIssue("CRITICAL", sym, "FUTURES_DATA_UNAVAILABLE", "Futures data for $sym: ${r.detail}") }
            is app.strategyforge.engine.market.ProviderResult.Unsupported -> null.also { issues += IntegrityIssue("CRITICAL", sym, "FUTURES_DATA_UNAVAILABLE", r.detail) }
        }
    }

    companion object {
        const val MAX_DAYS = 3650L
        const val MAX_POINTS = 2000
        val MAX_GAP_RATIO = BigDecimal("0.05")
    }
}
