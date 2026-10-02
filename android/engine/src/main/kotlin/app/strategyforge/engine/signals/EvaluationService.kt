package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.Activation
import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationService
import app.strategyforge.engine.backtest.sizing
import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.int
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.execution.OrderLinks
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderService
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.Pricing
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.BarSchedule
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.ClockMode
import app.strategyforge.engine.market.Instrument
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.market.MarketInterest
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.risk.OrderIntent
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.risk.RiskEngine
import app.strategyforge.engine.strategy.Condition
import app.strategyforge.engine.strategy.Indicators
import app.strategyforge.engine.strategy.Operand
import app.strategyforge.engine.strategy.RuleEvaluator
import app.strategyforge.engine.strategy.RuleGroup
import app.strategyforge.engine.strategy.RuleNode
import app.strategyforge.engine.strategy.SeriesContext
import app.strategyforge.engine.strategy.StrategyDefinition
import app.strategyforge.engine.strategy.StrategyService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Why an evaluation could not proceed; mapped to the strategy's inactivity conditions. */
data class EvaluationBlocked(
    val strategyId: UUID,
    val activationId: UUID,
    val mode: ActivationMode,
    val conditions: Set<String>,
    val detail: String,
)

/** Published after an evaluation failure so repeated errors can suspend the strategy (FR-094). */
data class EvaluationFailed(
    val strategyId: UUID,
    val consecutiveFailures: Int,
    val error: String,
)

data class EvaluationSummary(
    val strategyId: UUID,
    val bucket: Instant?,
    val status: String,
    val signals: Int,
    val detail: String,
)

data class SignalDraft(
    val instrument: Instrument,
    val action: String,
    val side: OrderSide,
    val quantity: BigDecimal,
    val orderType: OrderType,
    val limitPrice: BigDecimal?,
    val referencePrice: BigDecimal,
    val triggered: List<String>,
    val rationale: String,
)

/**
 * Authoritative on-device evaluation of active strategies (FR-060, FR-061). Each strategy/time
 * bucket is claimed exactly once through a unique row, so repeated ticks and restarts never
 * produce duplicate signals (MS-13, NFR-007). Evaluation fails closed when data,
 * portfolio reconciliation or strategy state cannot be verified.
 */
class EvaluationService(
    private val db: Db,
    private val activations: ActivationService,
    private val strategies: StrategyService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val portfolios: PortfolioService,
    private val dispatcher: SignalDispatcher,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val clock: Clock,
    private val marketClock: MarketClock,
    private val events: EngineEvents,
    /** Futures context for strategies that use it (D-044). */
    private val derivatives: app.strategyforge.engine.market.DerivativesService? = null,
    /** Effective risk limits for a portfolio and strategy, used to fit risk-based position sizes (D-042). */
    private val riskLimits: (UUID, UUID) -> app.strategyforge.engine.risk.RiskLimits? = { _, _ -> null },
) {
    private val log = EngineLog.of(javaClass)

    fun evaluateAll(): List<EvaluationSummary> {
        val paused = db.sql("select pause_all from emergency_state").single { it.bool("pause_all") }
        if (paused) return emptyList() // Pause All stops new evaluations and recommendations.
        return activations.activeAll().map { a ->
            try {
                evaluate(a)
            } catch (e: Exception) {
                log.error("Evaluation of strategy {} failed", a.strategyId, e)
                onFailure(a, e)
                EvaluationSummary(a.strategyId, null, "FAILED", 0, e.javaClass.simpleName)
            }
        }
    }

    fun evaluate(a: Activation): EvaluationSummary {
        val s = strategies.get(a.strategyId)
        if (!s.status.active) return EvaluationSummary(a.strategyId, null, "SKIPPED", 0, "Strategy is ${s.status}")
        val def = strategies.definition(a.versionId)
        val now = marketClock.now()
        val bucket =
            def.symbols.firstNotNullOfOrNull { sym -> instruments.findBySymbol(sym)?.let { BarSchedule.lastClosedBarStart(it.assetClass, def.timeframe, now) } }
                ?: return EvaluationSummary(a.strategyId, null, "SKIPPED", 0, "No closed bar is available for the evaluation bucket")
        val evaluationId = claim(a, bucket) ?: return EvaluationSummary(a.strategyId, bucket, "DUPLICATE", 0, "Bucket $bucket already evaluated")

        val blocks = mutableSetOf<String>()
        val details = mutableListOf<String>()
        // Strategy state must be verifiable: the active version must still be the authorized hash.
        if (s.currentVersionId != a.versionId || s.contentHash != a.contentHash) {
            blocks += "STRATEGY_STATE_UNVERIFIED"
            details += "Active version changed since activation"
        }
        val portfolio = portfolios.get(a.portfolioId)
        if (portfolio.reconciliationStatus != "OK") {
            blocks += "RECONCILIATION_FAILURE"
            details += "Portfolio reconciliation is ${portfolio.reconciliationStatus}"
        }
        val drafts = mutableListOf<SignalDraft>()
        if (blocks.isEmpty()) {
            def.symbols.forEach { sym ->
                val i = instruments.bySymbol(sym)
                val quote = market.verifyQuote(i, def.maximumQuoteAgeSeconds, now)
                if (!quote.verified) {
                    blocks += if (quote.status.name in setOf("PROVIDER_ERROR", "UNSUPPORTED")) "PROVIDER_UNAVAILABLE" else "STALE_MARKET_DATA"
                    details += "$sym quote ${quote.status}: ${quote.detail}"
                    return@forEach
                }
                val series = market.verifyCandles(i, def.timeframe, def.minimumHistoryBars, now)
                if (series.status.name != "VERIFIED") {
                    blocks += if (series.status.name == "INSUFFICIENT_HISTORY") "MISSING_HISTORY" else "STALE_MARKET_DATA"
                    details += "$sym bars ${series.status}: ${series.detail}"
                    return@forEach
                }
                var aligned: app.strategyforge.engine.market.AlignedDerivatives? = null
                if (def.usesDerivatives) {
                    val r = derivatives?.forBars(sym, def.timeframe, series.bars, now)
                    when (r) {
                        is app.strategyforge.engine.market.ProviderResult.Ok -> aligned = r.value.second
                        is app.strategyforge.engine.market.ProviderResult.Failed -> {
                            blocks += "PROVIDER_UNAVAILABLE"
                            details += "$sym futures data: ${r.detail}"
                            return@forEach
                        }
                        else -> {
                            blocks += "PROVIDER_UNAVAILABLE"
                            details += "$sym futures data: ${(r as? app.strategyforge.engine.market.ProviderResult.Unsupported)?.detail ?: "no futures data source"}"
                            return@forEach
                        }
                    }
                    val missing = derivativesMissingOnLastBar(def, aligned)
                    if (missing.isNotEmpty()) {
                        blocks += "MISSING_HISTORY"
                        details += "$sym futures data has no recent ${missing.joinToString()}"
                        return@forEach
                    }
                }
                draft(a, def, i, series.bars, quote.quote!!.last, aligned)?.let { drafts += it }
            }
        }
        if (blocks.isNotEmpty()) {
            finishEvaluation(evaluationId, "BLOCKED", mapOf("blocks" to blocks, "details" to details))
            notifications.notify(
                NotificationCategory.STALE_DATA.takeIf { "STALE_MARKET_DATA" in blocks || "PROVIDER_UNAVAILABLE" in blocks } ?: NotificationCategory.STRATEGY_HEALTH,
                Severity.CRITICAL,
                "Strategy ${s.name} could not be evaluated",
                "Blocked: ${blocks.joinToString()}. ${details.joinToString("; ").take(800)}. No new positions were opened.",
                "Strategy",
                s.id,
                "eval-blocked:${s.id}:$bucket",
            )
            audit.record(AuditCategory.STRATEGY, "EVALUATION_BLOCKED", AuditOutcome.BLOCKED, "Strategy", s.id, mapOf("bucket" to bucket.toString(), "blocks" to blocks, "details" to details))
            events.publish(EvaluationBlocked(s.id, a.id, a.mode, blocks, details.joinToString("; ")))
            // Symbols with verified data are not evaluated either: no verified state, no new trade.
            return EvaluationSummary(s.id, bucket, "BLOCKED", 0, details.joinToString("; "))
        }
        var created = 0
        drafts.forEach { d -> if (emit(a, def, evaluationId, bucket, d, now)) created++ }
        finishEvaluation(evaluationId, "COMPLETED", mapOf("signals" to created, "symbols" to def.symbols.size))
        return EvaluationSummary(s.id, bucket, "COMPLETED", created, "$created signal(s)")
    }

    private fun claim(
        a: Activation,
        bucket: Instant,
    ): UUID? {
        val id = UUID.randomUUID()
        val n =
            db
                .sql(
                    "insert or ignore into strategy_evaluations(id, strategy_id, activation_id, bucket_start, status, started_at) values (:id, :s, :a, :b, 'CLAIMED', :now)",
                ).param("id", id)
                .param("s", a.strategyId)
                .param("a", a.id)
                .param("b", (bucket))
                .param("now", (clock.instant()))
                .update()
        return if (n == 1) id else null
    }

    private fun finishEvaluation(
        id: UUID,
        status: String,
        detail: Map<String, Any?>,
    ) {
        db
            .sql("update strategy_evaluations set status = :s, detail = :d, completed_at = :now where id = :id")
            .param("s", status)
            .param("d", detail.toJsonElement().toString())
            .param("now", (clock.instant()))
            .param("id", id)
            .update()
    }

    private fun onFailure(
        a: Activation,
        e: Exception,
    ) {
        db
            .sql(
                """
                update strategy_evaluations set status = 'FAILED', detail = :d, completed_at = :now
                where strategy_id = :s and status = 'CLAIMED'
                """.trimIndent(),
            ).param("d", mapOf("error" to e.javaClass.simpleName).toJsonElement().toString())
            .param("now", (clock.instant()))
            .param("s", a.strategyId)
            .update()
        val recent =
            db
                .sql("select status from strategy_evaluations where strategy_id = :s order by started_at desc limit 20")
                .param("s", a.strategyId)
                .list { it.str("status") }
        val streak = recent.takeWhile { it == "FAILED" }.size
        audit.record(AuditCategory.FAILURE, "EVALUATION_FAILED", AuditOutcome.FAILURE, "Strategy", a.strategyId, mapOf("error" to e.javaClass.simpleName, "consecutive" to streak))
        events.publish(EvaluationFailed(a.strategyId, streak, e.javaClass.simpleName))
    }

    /**
     * The futures series a strategy reads that have no value for any of the last three closed bars
     * (D-044): the feed has stopped. A single late interval only leaves that bar's value empty.
     */
    private fun derivativesMissingOnLastBar(
        def: StrategyDefinition,
        d: app.strategyforge.engine.market.AlignedDerivatives,
    ): List<String> {
        val types = def.indicators.map { it.type }.toSet()
        return listOfNotNull(
            "open interest".takeIf { app.strategyforge.engine.strategy.IndicatorType.OPEN_INTEREST in types && d.openInterest.takeLast(3).all { it == null } },
            "funding rate".takeIf { app.strategyforge.engine.strategy.IndicatorType.FUNDING_RATE in types && d.fundingRate.takeLast(3).all { it == null } },
            "delta".takeIf { app.strategyforge.engine.strategy.IndicatorType.CVD in types && d.delta.takeLast(3).all { it == null } },
        )
    }

    /** Builds an entry or exit signal for one symbol from verified closed bars (point in time). */
    private fun draft(
        a: Activation,
        def: StrategyDefinition,
        i: Instrument,
        bars: List<CandleData>,
        last: BigDecimal,
        derivatives: app.strategyforge.engine.market.AlignedDerivatives? = null,
    ): SignalDraft? {
        val ev = RuleEvaluator(bars, Indicators.compute(def.indicators, bars, SeriesContext.of(def.timeframe, def.assetClass).copy(derivatives = derivatives)))
        val idx = bars.lastIndex
        val held = strategyHolding(a, i.id)
        val openOrder =
            db
                .sql("select count(*) n from paper_orders where strategy_id = :s and instrument_id = :i and status in ('VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("s", a.strategyId)
                .param("i", i.id)
                .int() > 0
        // A newer signal supersedes any pending recommendation for this instrument (FR-066).
        if (openOrder) return null
        val limitFor = { price: BigDecimal, buy: Boolean ->
            def.order.limitOffsetPercent?.takeIf { def.order.orderType == "LIMIT" }?.let { off ->
                val f = off.divide(Decimals.HUNDRED, Decimals.MC)
                Pricing.roundToIncrement(if (buy) price.multiply(BigDecimal.ONE.subtract(f)) else price.multiply(BigDecimal.ONE.add(f)), i.priceIncrement, RoundingMode.HALF_EVEN)
            }
        }
        if (held != null) return exitDraft(def, i, bars, ev, idx, held, last, limitFor)
        val position = portfolios.positionViews(portfolios.get(a.portfolioId)).firstOrNull { it.instrumentId == i.id }
        if (position != null) return null // the instrument is held outside this strategy; do not mix
        // No new entries once today's losing trades reach the strategy's cap (D-042).
        def.risk.maximumDailyLosingTrades?.let { cap -> if (losingTradesToday(a) >= cap) return null }
        val long = def.entryFor(false)?.let { ev.evaluate(it, idx) } ?: false
        val shortSignal = def.entryFor(true)?.let { ev.evaluate(it, idx) } ?: false
        // Conflicting long and short signals on the same bar: no trade.
        if (long == shortSignal) return null
        val short = shortSignal
        if (short && !portfolios.get(a.portfolioId).shortingEnabled) return null
        val rules = def.entryFor(short)!!
        val equity = portfolios.summary(a.portfolioId).equity
        val limits = runCatching { riskLimits(a.portfolioId, a.strategyId) }.getOrNull()
        val tradeCap = listOfNotNull(limits?.maxTradePercentOfEquity, limits?.maxInstrumentAllocationPercent).minOrNull()
        val qty = Decimals.floorToStep(sizing(def, equity, last, tradeCap, limits?.maxTradeValue), i.quantityIncrement)
        if (qty < i.minQuantity) return null
        val side = if (short) OrderSide.SELL_SHORT else OrderSide.BUY
        val triggered = triggeredConditions(rules, ev, idx)
        val bar = bars.last()
        val rationale =
            "${if (short) "Short entry" else "Entry"}: ${triggered.joinToString("; ")} on the ${def.timeframe.code} bar that closed at ${BarSchedule.closeTime(i.assetClass, def.timeframe, bar.openTime)}. " +
                "Last price ${last.stripTrailingZeros().toPlainString()}; size ${def.sizing.method.name.lowercase().replace('_', ' ')} ${def.sizing.value.stripTrailingZeros().toPlainString()}."
        val limit = limitFor(last, side.buys)
        return SignalDraft(i, if (short) "ENTER_SHORT" else "ENTER_LONG", side, qty, if (limit != null) OrderType.LIMIT else OrderType.MARKET, limit, last, triggered, rationale)
    }

    /** Stop, partial target, target, trailing stop, holding time and exit rules for a held position. */
    private fun exitDraft(
        def: StrategyDefinition,
        i: Instrument,
        bars: List<CandleData>,
        ev: RuleEvaluator,
        idx: Int,
        held: Holding,
        last: BigDecimal,
        limitFor: (BigDecimal, Boolean) -> BigDecimal?,
    ): SignalDraft? {
        val short = held.short
        val avgCost = held.averageCost
        val pct = { x: BigDecimal -> x.divide(Decimals.HUNDRED, Decimals.MC) }
        val against = { f: BigDecimal -> if (short) avgCost.multiply(BigDecimal.ONE.add(f)) else avgCost.multiply(BigDecimal.ONE.subtract(f)) }
        val favour = { f: BigDecimal -> if (short) avgCost.multiply(BigDecimal.ONE.subtract(f)) else avgCost.multiply(BigDecimal.ONE.add(f)) }
        val reached = { level: BigDecimal -> if (short) last <= level else last >= level }
        val breached = { level: BigDecimal -> if (short) last >= level else last <= level }
        val partial = def.exit.partialTakeProfit
        val breakeven = held.partialTaken && partial?.moveStopToEntry == true
        val stop = if (breakeven) avgCost else against(pct(def.exit.stopLossPercent))
        val barsSince = bars.count { !it.openTime.isBefore(held.entryAt) }
        val extreme = bars.filter { !it.openTime.isBefore(held.entryAt) }.let { b -> if (short) b.minOfOrNull { it.low } else b.maxOfOrNull { it.high } } ?: avgCost
        val trail = def.exit.trailingStopPercent?.let { pct(it) }
        var qty = held.quantity
        val reason =
            when {
                breached(stop) -> if (breakeven) "BREAKEVEN_STOP" else "STOP_LOSS"
                reached(favour(pct(def.exit.takeProfitPercent))) -> "TAKE_PROFIT"
                partial != null && !held.partialTaken && reached(favour(pct(partial.atPercent))) -> {
                    // Take part of the position off at the first target (D-042).
                    val part = Decimals.floorToStep(held.quantity.multiply(partial.closePercent).divide(Decimals.HUNDRED, Decimals.MC), i.quantityIncrement)
                    if (part < i.minQuantity || part >= held.quantity) null else "PARTIAL_TAKE_PROFIT".also { qty = part }
                }
                trail != null && breached(if (short) extreme.multiply(BigDecimal.ONE.add(trail)) else extreme.multiply(BigDecimal.ONE.subtract(trail))) -> "TRAILING_STOP"
                barsSince >= def.exit.maximumHoldingBars -> "MAX_HOLDING_BARS"
                def.exit.conditionsFor(short, def.direction)?.let { ev.evaluate(it, idx) } == true -> "EXIT_RULE"
                else -> null
            } ?: return null
        val side = if (short) OrderSide.BUY_TO_COVER else OrderSide.SELL
        val portion = if (qty < held.quantity) " (${qty.stripTrailingZeros().toPlainString()} of ${held.quantity.stripTrailingZeros().toPlainString()})" else ""
        val rationale = "Exit ($reason)$portion: last ${last.stripTrailingZeros().toPlainString()} vs average cost ${avgCost.setScale(4, RoundingMode.HALF_EVEN).toPlainString()} after $barsSince bar(s)."
        val limit = limitFor(last, side.buys)
        return SignalDraft(i, if (short) "EXIT_SHORT" else "EXIT_LONG", side, qty, if (limit != null) OrderType.LIMIT else OrderType.MARKET, limit, last, listOf(reason), rationale)
    }

    /** Distinct closing orders of this strategy that lost money since the start of today (New York). */
    private fun losingTradesToday(a: Activation): Int {
        val dayStart =
            marketClock
                .now()
                .atZone(app.strategyforge.engine.market.MarketCalendar.NEW_YORK)
                .toLocalDate()
                .atStartOfDay(app.strategyforge.engine.market.MarketCalendar.NEW_YORK)
                .toInstant()
        val pnl =
            db
                .sql(
                    """
                    select e.order_id, e.realized_pnl from paper_executions e join paper_orders o on o.id = e.order_id
                    where o.strategy_id = :s and e.side in ('SELL', 'BUY_TO_COVER') and e.executed_at >= :d
                    """.trimIndent(),
                ).param("s", a.strategyId)
                .param("d", dayStart)
                .list { it.str("order_id") to it.dec("realized_pnl") }
        // Summed per order in Kotlin: amounts are exact decimal text.
        return pnl.groupBy({ it.first }, { it.second }).count { (_, v) -> v.fold(BigDecimal.ZERO, BigDecimal::add).signum() < 0 }
    }

    private fun triggeredConditions(
        g: RuleGroup,
        ev: RuleEvaluator,
        i: Int,
    ): List<String> =
        g.conditions.flatMap { n: RuleNode ->
            when (n) {
                is RuleGroup -> triggeredConditions(n, ev, i)
                is Condition -> if (ev.evaluate(n, i)) listOf(describe(n)) else emptyList()
            }
        }

    private fun describe(c: Condition): String {
        fun op(o: Operand) =
            when (o) {
                is Operand.Constant -> o.value.stripTrailingZeros().toPlainString()
                is Operand.Price -> o.field.name
                is Operand.IndicatorRef -> if (o.component == "value") o.id else "${o.id}.${o.component}"
            }
        return "${op(c.left)} ${c.comparison} ${op(c.right)}" + if (c.offsetBars > 0) " (${c.offsetBars} bars ago)" else ""
    }

    /** The lots this strategy manages in one instrument, summed (D-035, D-042). */
    private data class Holding(
        val quantity: BigDecimal,
        val averageCost: BigDecimal,
        val entryAt: Instant,
        val short: Boolean,
        /** Some of the position was already closed, so a partial target was taken. */
        val partialTaken: Boolean,
    )

    /**
     * Quantity, average cost, first entry time and side of the lots this strategy manages: those its
     * orders opened, plus those handed to it when it replaced another strategy (D-035).
     */
    private fun strategyHolding(
        a: Activation,
        instrumentId: UUID,
    ): Holding? {
        // Summed in Kotlin: quantities and costs are exact decimal text.
        data class Lot(
            val remaining: BigDecimal,
            val open: BigDecimal,
            val cost: BigDecimal,
            val at: Instant,
            val short: Boolean,
        )
        val lots =
            db
                .sql(
                    """
                    select l.quantity_remaining, l.quantity_open, l.cost_remaining, l.opened_at, l.side from position_lots l
                    join paper_executions e on e.id = l.open_execution_id join paper_orders o on o.id = e.order_id
                    where l.portfolio_id = :p and l.instrument_id = :i and coalesce(l.managed_by_strategy_id, o.strategy_id) = :s and l.closed_at is null
                    """.trimIndent(),
                ).param("p", a.portfolioId)
                .param("i", instrumentId)
                .param("s", a.strategyId)
                .list { rs -> Lot(rs.dec("quantity_remaining"), rs.dec("quantity_open"), rs.dec("cost_remaining").abs(), rs.instant("opened_at"), rs.str("side") == "SHORT") }
        val q = lots.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.remaining) }
        if (lots.isEmpty() || q.signum() == 0) return null
        val opened = lots.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.open) }
        val c = lots.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.cost) }
        return Holding(q, c.divide(q, Decimals.MC), lots.minOf { it.at }, lots.first().short, q < opened)
    }

    /** Persists the signal, evaluates risk, then either recommends (default) or executes autonomously. */
    private fun emit(
        a: Activation,
        def: StrategyDefinition,
        evaluationId: UUID,
        bucket: Instant,
        d: SignalDraft,
        now: Instant,
    ): Boolean =
        db.tx {
            val (snapshot, _) = market.captureSnapshot(d.instrument, def.maximumQuoteAgeSeconds)
            val expires =
                now.plus(
                    Duration.ofSeconds(
                        def.timeframe.duration.seconds
                            .coerceIn(MIN_EXPIRY_SECONDS, MAX_EXPIRY_SECONDS),
                    ),
                )
            val id = UUID.randomUUID()
            val inserted =
                db
                    .sql(
                        """
                        insert or ignore into signals(id, strategy_id, version_id, content_hash, activation_id, evaluation_id, portfolio_id, instrument_id, bucket_start, action, side, quantity,
                          order_type, limit_price, reference_price, market_snapshot_id, triggered_rules, rationale, expires_at, disposition, created_at)
                        values (:id, :s, :v, :h, :a, :e, :p, :i, :b, :act, :side, :q, :t, :l, :ref, :snap, :tr, :rat, :exp, 'RECOMMENDED', :now)
                        """.trimIndent(),
                    ).param("id", id)
                    .param("s", a.strategyId)
                    .param("v", a.versionId)
                    .param("h", a.contentHash)
                    .param("a", a.id)
                    .param("e", evaluationId)
                    .param("p", a.portfolioId)
                    .param("i", d.instrument.id)
                    .param("b", (bucket))
                    .param("act", d.action)
                    .param("side", d.side.name)
                    .param("q", d.quantity)
                    .param("t", d.orderType.name)
                    .param("l", d.limitPrice)
                    .param("ref", d.referencePrice)
                    .param("snap", snapshot.id)
                    .param("tr", d.triggered.toJsonElement().toString())
                    .param("rat", d.rationale.take(2000))
                    .param("exp", (expires))
                    .param("now", (clock.instant()))
                    .update()
            if (inserted == 0) return@tx false
            dispatcher.dispatch(a, id, d)
            // FR-101: protective exits are announced separately from the order they produce.
            d.triggered.firstOrNull { it in PROTECTIVE_EXITS }?.let { reason ->
                val label = reason.lowercase().replace('_', ' ')
                notifications.notify(NotificationCategory.STOP_TARGET, Severity.WARNING, "Exit triggered ($label): ${d.instrument.symbol}", d.rationale.take(500), "Signal", id, "stop-target:$id")
            }
            audit.record(AuditCategory.STRATEGY, "SIGNAL_CREATED", entityType = "Signal", entityId = id, details = mapOf("strategyId" to a.strategyId, "hash" to a.contentHash, "action" to d.action, "symbol" to d.instrument.symbol, "mode" to a.mode))
            true
        }

    companion object {
        const val MIN_EXPIRY_SECONDS = 300L
        const val MAX_EXPIRY_SECONDS = 1800L
        val PROTECTIVE_EXITS = setOf("STOP_LOSS", "TAKE_PROFIT", "TRAILING_STOP")
    }
}

/** Published when autonomy must be re-authorized after a material change (FR-072). */
data class ReauthorizationRequired(
    val strategyId: UUID,
    val activationId: UUID,
    val reason: String,
)

/** Keeps quotes fresh for every instrument in an active strategy's universe. */
class StrategyInterest(
    private val db: Db,
) : MarketInterest {
    override fun instrumentIds(): Set<UUID> =
        db
            .sql("select v.canonical_content from strategy_activations a join strategy_versions v on v.id = a.version_id where a.status = 'ACTIVE'")
            .list { rs ->
                JacksonCanonical.mapper
                    .readTree(rs.str("canonical_content"))
                    .path("universe")
                    .path("symbols")
                    .map { it.asText() }
            }.flatten()
            .toSet()
            .let { symbols ->
                if (symbols.isEmpty()) {
                    emptySet()
                } else {
                    db
                        .sql("select id from instruments where symbol in (:s)")
                        .param("s", symbols)
                        .list { it.uuid("id") }
                        .toSet()
                }
            }
}
