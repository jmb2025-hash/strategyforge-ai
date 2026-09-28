package app.strategyforge.backtest

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import app.strategyforge.market.AssetClass
import app.strategyforge.market.CorporateActionService
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketCalendar
import app.strategyforge.market.MarketClock
import app.strategyforge.market.Timeframe
import app.strategyforge.market.data.BarSchedule
import app.strategyforge.market.data.DataStatus
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.market.provider.MarketProviderRegistry
import app.strategyforge.portfolio.CostModel
import app.strategyforge.risk.EffectiveLimits
import app.strategyforge.risk.RiskLevel
import app.strategyforge.risk.RiskLimits
import app.strategyforge.risk.RiskProfileService
import app.strategyforge.settings.SettingsService
import app.strategyforge.strategy.StrategyService
import app.strategyforge.strategy.StrategyStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
@Service
class BacktestService(
    private val jdbc: JdbcClient,
    private val strategies: StrategyService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val corporate: CorporateActionService,
    private val registry: MarketProviderRegistry,
    private val settings: SettingsService,
    private val marketClock: MarketClock,
    private val clock: Clock,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val events: org.springframework.context.ApplicationEventPublisher,
    private val profiles: RiskProfileService,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 2
            queueCapacity = 50
            setThreadNamePrefix("backtest-")
            initialize()
        }

    fun submit(req: BacktestRequest): BacktestView {
        val id =
            tx.execute {
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
                val params = req.copy(versionId = versionId, startingCapital = capital, costModel = req.costModel ?: CostModel.from(settings.get().portfolioDefaults), riskProfile = risk)
                val id = UUID.randomUUID()
                jdbc
                    .sql("insert into backtests(id, strategy_id, version_id, content_hash, status, params, created_at) values (:id, :s, :v, :h, 'QUEUED', cast(:p as jsonb), :now)")
                    .param("id", id)
                    .param("s", s.id)
                    .param("v", versionId)
                    .param("h", v.contentHash)
                    .param("p", mapper.writeValueAsString(params))
                    .param("now", ts(clock.instant()))
                    .update()
                audit.record(AuditCategory.BACKTEST, "BACKTEST_QUEUED", entityType = "Backtest", entityId = id, details = mapOf("strategyId" to s.id, "hash" to v.contentHash, "from" to req.from.toString(), "to" to req.to.toString()))
                id
            }!!
        val correlation = CorrelationIdFilter.current()
        val task =
            Runnable {
                org.slf4j.MDC.put(CorrelationIdFilter.MDC_KEY, correlation)
                try {
                    execute(id)
                } finally {
                    org.slf4j.MDC.remove(CorrelationIdFilter.MDC_KEY)
                }
            }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = executor.execute(task)
                },
            )
        } else {
            executor.execute(task)
        }
        return get(id)
    }

    fun execute(id: UUID) {
        val started =
            jdbc
                .sql("update backtests set status = 'RUNNING', started_at = :now where id = :id and status = 'QUEUED'")
                .param("now", ts(clock.instant()))
                .param("id", id)
                .update()
        if (started == 0) return
        try {
            val result = runBacktest(id)
            tx.executeWithoutResult { complete(id, result) }
        } catch (e: Exception) {
            log.error("Backtest {} failed", id, e)
            jdbc
                .sql("update backtests set status = 'FAILED', error = :e, completed_at = :now where id = :id")
                .param("e", "${e.javaClass.simpleName}: ${e.message}".take(500))
                .param("now", ts(clock.instant()))
                .param("id", id)
                .update()
            audit.recordIndependently(AuditCategory.BACKTEST, "BACKTEST_FAILED", AuditOutcome.FAILURE, "Backtest", id, mapOf("error" to e.javaClass.simpleName))
            val s = get(id)
            events.publishEvent(BacktestCompleted(id, s.strategyId, null))
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
        val req = mapper.treeToValue(bt.params, BacktestRequest::class.java)
        val def = strategies.definition(bt.versionId)
        val symbols = req.symbols?.filter { it in def.symbols }?.takeIf { it.isNotEmpty() } ?: def.symbols
        val asOf = minOf(req.to, marketClock.now())
        val issues = mutableListOf<IntegrityIssue>()
        val datasetSymbols = mutableListOf<Map<String, Any?>>()
        val series = mutableListOf<SymbolSeries>()
        var manualReview = false
        val provider = registry.active().type.name
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
            series += SymbolSeries(sym, i.assetClass, cs.bars, i.priceIncrement, i.quantityIncrement, i.minQuantity, actions)
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
            jdbc
                .sql(
                    """
                    insert into backtest_trades(backtest_id, symbol, side, entry_time, entry_price, exit_time, exit_price, quantity, gross_pnl, fees, spread_cost, slippage_cost,
                      borrow_cost, dividends, net_pnl, holding_bars, exit_reason, partial_fill)
                    values (:b, :s, :side, :et, :ep, :xt, :xp, :q, :g, :f, :sc, :sl, :bc, :d, :n, :h, :r, :pf)
                    """.trimIndent(),
                ).param("b", id)
                .param("s", t.symbol)
                .param("side", t.side)
                .param("et", ts(t.entryTime))
                .param("ep", t.entryPrice)
                .param("xt", ts(t.exitTime))
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

        fun downsample(xs: List<SeriesPoint>) = if (xs.size <= MAX_POINTS) xs else xs.filterIndexed { i, _ -> i % (xs.size / MAX_POINTS + 1) == 0 } + xs.last()
        jdbc
            .sql(
                """
                update backtests set status = 'COMPLETED', result_status = :rs, dataset = cast(:d as jsonb), integrity = cast(:i as jsonb), metrics = cast(:m as jsonb),
                  equity_series = cast(:e as jsonb), drawdown_series = cast(:dd as jsonb), benchmark = cast(:b as jsonb), completed_at = :now where id = :id
                """.trimIndent(),
            ).param("rs", r.resultStatus)
            .param("d", mapper.writeValueAsString(r.dataset))
            .param("i", mapper.writeValueAsString(mapOf("status" to r.resultStatus, "issues" to r.integrity)))
            .param("m", mapper.writeValueAsString(r.metrics))
            .param("e", mapper.writeValueAsString(downsample(r.output.equity)))
            .param("dd", mapper.writeValueAsString(downsample(r.output.drawdown)))
            .param("b", mapper.writeValueAsString(r.benchmark))
            .param("now", ts(clock.instant()))
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
        events.publishEvent(BacktestCompleted(id, bt.strategyId, r.resultStatus))
    }

    /** Latest completed backtest usable for activation: same content hash and no critical/MRR integrity (FR-055). */
    fun eligibleBacktest(versionId: UUID): BacktestView? =
        jdbc
            .sql("select * from backtests where version_id = :v and status = 'COMPLETED' and result_status in ('OK', 'WARNINGS') order by completed_at desc limit 1")
            .param("v", versionId)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun get(id: UUID): BacktestView =
        jdbc
            .sql("select * from backtests where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Backtest", id) }

    fun list(strategyId: UUID?): List<BacktestView> =
        jdbc
            .sql("select * from backtests where (cast(:s as uuid) is null or strategy_id = :s) order by created_at desc limit 200")
            .param("s", strategyId)
            .query { rs, _ -> map(rs) }
            .list()

    fun trades(id: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("select * from backtest_trades where backtest_id = :b order by entry_time, id")
            .param("b", id)
            .query { rs, _ ->
                mapOf(
                    "symbol" to rs.getString("symbol"),
                    "side" to rs.getString("side"),
                    "entryTime" to rs.instant("entry_time"),
                    "entryPrice" to rs.getBigDecimal("entry_price"),
                    "exitTime" to rs.instantOrNull("exit_time"),
                    "exitPrice" to rs.getBigDecimal("exit_price"),
                    "quantity" to rs.getBigDecimal("quantity"),
                    "grossPnl" to rs.getBigDecimal("gross_pnl"),
                    "fees" to rs.getBigDecimal("fees"),
                    "spreadCost" to rs.getBigDecimal("spread_cost"),
                    "slippageCost" to rs.getBigDecimal("slippage_cost"),
                    "borrowCost" to rs.getBigDecimal("borrow_cost"),
                    "dividends" to rs.getBigDecimal("dividends"),
                    "netPnl" to rs.getBigDecimal("net_pnl"),
                    "holdingBars" to rs.getInt("holding_bars"),
                    "exitReason" to rs.getString("exit_reason"),
                    "partialFill" to rs.getBoolean("partial_fill"),
                )
            }.list()

    fun series(id: UUID): Map<String, Any?> =
        jdbc
            .sql("select equity_series, drawdown_series, benchmark from backtests where id = :id")
            .param("id", id)
            .query { rs, _ ->
                mapOf("equity" to rs.getString(1)?.let { mapper.readTree(it) }, "drawdown" to rs.getString(2)?.let { mapper.readTree(it) }, "benchmark" to rs.getString(3)?.let { mapper.readTree(it) })
            }.single()

    /** Backtests interrupted by a restart are failed explicitly, never left running. */
    @EventListener(ApplicationReadyEvent::class)
    fun recoverInterrupted() {
        val n =
            jdbc
                .sql("update backtests set status = 'FAILED', error = 'Interrupted by backend restart; rerun the backtest', completed_at = :now where status in ('QUEUED', 'RUNNING')")
                .param("now", ts(clock.instant()))
                .update()
        if (n > 0) log.warn("Marked {} interrupted backtest(s) as failed", n)
    }

    private fun map(rs: java.sql.ResultSet): BacktestView {
        fun json(c: String) = rs.getString(c)?.let { mapper.readTree(it) }
        return BacktestView(
            rs.uuid("id"),
            rs.uuid("strategy_id"),
            rs.uuid("version_id"),
            rs.getString("content_hash"),
            rs.getString("status"),
            rs.getString("result_status"),
            json("params")!!,
            json("dataset"),
            json("integrity"),
            json("metrics"),
            json("benchmark"),
            rs.getString("error"),
            rs.instant("created_at"),
            rs.instantOrNull("started_at"),
            rs.instantOrNull("completed_at"),
        )
    }

    companion object {
        const val MAX_DAYS = 3650L
        const val MAX_POINTS = 2000
        val MAX_GAP_RATIO = BigDecimal("0.05")
    }
}

@RestController
@RequestMapping("/v1/backtests")
@Tag(name = "Strategies")
class BacktestController(
    private val backtests: BacktestService,
    private val idempotency: app.strategyforge.common.idempotency.IdempotencyService,
) {
    @PostMapping
    fun submit(
        @RequestBody req: BacktestRequest,
        @RequestHeader(app.strategyforge.common.idempotency.IdempotencyService.HEADER, required = false) key: String?,
    ): ResponseEntity<Any> = idempotency.execute("backtest-submit", key, req, HttpStatus.ACCEPTED) { backtests.submit(req) }

    @GetMapping
    fun list(
        @RequestParam(required = false) strategyId: String?,
    ) = backtests.list(strategyId?.let { parseUuid(it) })

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ) = backtests.get(parseUuid(id))

    @GetMapping("/{id}/trades")
    fun trades(
        @PathVariable id: String,
    ) = backtests.trades(parseUuid(id))

    @GetMapping("/{id}/series")
    fun series(
        @PathVariable id: String,
    ) = backtests.series(parseUuid(id))
}
