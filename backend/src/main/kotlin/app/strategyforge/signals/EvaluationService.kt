package app.strategyforge.signals

import app.strategyforge.autonomy.Activation
import app.strategyforge.autonomy.ActivationMode
import app.strategyforge.autonomy.ActivationService
import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.execution.OrderLinks
import app.strategyforge.execution.OrderRequest
import app.strategyforge.execution.OrderService
import app.strategyforge.execution.OrderSide
import app.strategyforge.execution.OrderType
import app.strategyforge.execution.TimeInForce
import app.strategyforge.market.ClockMode
import app.strategyforge.market.Instrument
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketClock
import app.strategyforge.market.MarketClockAdvanced
import app.strategyforge.market.MarketInterest
import app.strategyforge.market.data.BarSchedule
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.risk.OrderIntent
import app.strategyforge.risk.OrderSource
import app.strategyforge.risk.RiskEngine
import app.strategyforge.strategy.Condition
import app.strategyforge.strategy.Direction
import app.strategyforge.strategy.Indicators
import app.strategyforge.strategy.RuleEvaluator
import app.strategyforge.strategy.RuleGroup
import app.strategyforge.strategy.RuleNode
import app.strategyforge.strategy.SizingMethod
import app.strategyforge.strategy.StrategyDefinition
import app.strategyforge.strategy.StrategyService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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

private data class SignalDraft(
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
 * Authoritative backend evaluation of active strategies (FR-060, FR-061). Each strategy/time
 * bucket is claimed exactly once through a unique row, so scheduler retries, concurrent workers
 * and restarts never produce duplicate signals (MS-13, NFR-007). Evaluation fails closed when data,
 * portfolio reconciliation or strategy state cannot be verified.
 */
@Service
class EvaluationService(
    private val jdbc: JdbcClient,
    private val activations: ActivationService,
    private val strategies: StrategyService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val portfolios: PortfolioService,
    private val risk: RiskEngine,
    private val recommendations: RecommendationService,
    private val orders: OrderService,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val marketClock: MarketClock,
    private val props: StrategyForgeProperties,
    private val events: ApplicationEventPublisher,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    fun evaluateAll(): List<EvaluationSummary> {
        val paused = jdbc.sql("select pause_all from emergency_state").query(Boolean::class.java).single()
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
                draft(a, def, i, series.bars, quote.quote!!.last, now)?.let { drafts += it }
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
            audit.recordIndependently(AuditCategory.STRATEGY, "EVALUATION_BLOCKED", AuditOutcome.BLOCKED, "Strategy", s.id, mapOf("bucket" to bucket.toString(), "blocks" to blocks, "details" to details))
            events.publishEvent(EvaluationBlocked(s.id, a.id, a.mode, blocks, details.joinToString("; ")))
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
            jdbc
                .sql(
                    "insert into strategy_evaluations(id, strategy_id, activation_id, bucket_start, status, started_at) values (:id, :s, :a, :b, 'CLAIMED', :now) on conflict (strategy_id, bucket_start) do nothing",
                ).param("id", id)
                .param("s", a.strategyId)
                .param("a", a.id)
                .param("b", ts(bucket))
                .param("now", ts(clock.instant()))
                .update()
        return if (n == 1) id else null
    }

    private fun finishEvaluation(
        id: UUID,
        status: String,
        detail: Map<String, Any?>,
    ) {
        jdbc
            .sql("update strategy_evaluations set status = :s, detail = cast(:d as jsonb), completed_at = :now where id = :id")
            .param("s", status)
            .param("d", mapper.writeValueAsString(detail))
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
    }

    private fun onFailure(
        a: Activation,
        e: Exception,
    ) {
        jdbc
            .sql(
                """
                update strategy_evaluations set status = 'FAILED', detail = cast(:d as jsonb), completed_at = :now
                where strategy_id = :s and status = 'CLAIMED'
                """.trimIndent(),
            ).param("d", mapper.writeValueAsString(mapOf("error" to e.javaClass.simpleName)))
            .param("now", ts(clock.instant()))
            .param("s", a.strategyId)
            .update()
        val recent =
            jdbc
                .sql("select status from strategy_evaluations where strategy_id = :s order by started_at desc limit 20")
                .param("s", a.strategyId)
                .query(String::class.java)
                .list()
        val streak = recent.takeWhile { it == "FAILED" }.size
        audit.recordIndependently(AuditCategory.FAILURE, "EVALUATION_FAILED", AuditOutcome.FAILURE, "Strategy", a.strategyId, mapOf("error" to e.javaClass.simpleName, "consecutive" to streak))
        events.publishEvent(EvaluationFailed(a.strategyId, streak, e.javaClass.simpleName))
    }

    /** Builds an entry or exit signal for one symbol from verified closed bars (point in time). */
    private fun draft(
        a: Activation,
        def: StrategyDefinition,
        i: Instrument,
        bars: List<app.strategyforge.market.CandleData>,
        last: BigDecimal,
        now: Instant,
    ): SignalDraft? {
        val ev = RuleEvaluator(bars, Indicators.compute(def.indicators, bars))
        val idx = bars.lastIndex
        val short = def.direction == Direction.SHORT_ONLY
        val held = strategyHolding(a, i.id)
        val openOrder =
            jdbc
                .sql("select count(*) from paper_orders where strategy_id = :s and instrument_id = :i and status in ('VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("s", a.strategyId)
                .param("i", i.id)
                .query(Int::class.java)
                .single() > 0
        val pendingRec =
            jdbc
                .sql("select count(*) from recommendations where strategy_id = :s and instrument_id = :i and status = 'PENDING'")
                .param("s", a.strategyId)
                .param("i", i.id)
                .query(Int::class.java)
                .single() > 0
        if (openOrder) return null
        val limitFor = { price: BigDecimal, buy: Boolean ->
            def.order.limitOffsetPercent?.takeIf { def.order.orderType == "LIMIT" }?.let { off ->
                val f = off.divide(Decimals.HUNDRED, Decimals.MC)
                app.strategyforge.execution.Pricing
                    .roundToIncrement(if (buy) price.multiply(BigDecimal.ONE.subtract(f)) else price.multiply(BigDecimal.ONE.add(f)), i.priceIncrement, RoundingMode.HALF_EVEN)
            }
        }
        if (held != null) {
            val (qty, avgCost, entryAt) = held
            val stopPct = def.exit.stopLossPercent.divide(Decimals.HUNDRED, Decimals.MC)
            val tpPct = def.exit.takeProfitPercent.divide(Decimals.HUNDRED, Decimals.MC)
            val barsSince = bars.count { !it.openTime.isBefore(entryAt) }
            val extreme = bars.filter { !it.openTime.isBefore(entryAt) }.let { b -> if (short) b.minOfOrNull { it.low } else b.maxOfOrNull { it.high } } ?: avgCost
            val trail = def.exit.trailingStopPercent?.divide(Decimals.HUNDRED, Decimals.MC)
            val reason =
                when {
                    if (short) last >= avgCost.multiply(BigDecimal.ONE.add(stopPct)) else last <= avgCost.multiply(BigDecimal.ONE.subtract(stopPct)) -> "STOP_LOSS"
                    if (short) last <= avgCost.multiply(BigDecimal.ONE.subtract(tpPct)) else last >= avgCost.multiply(BigDecimal.ONE.add(tpPct)) -> "TAKE_PROFIT"
                    trail != null && (if (short) last >= extreme.multiply(BigDecimal.ONE.add(trail)) else last <= extreme.multiply(BigDecimal.ONE.subtract(trail))) -> "TRAILING_STOP"
                    barsSince >= def.exit.maximumHoldingBars -> "MAX_HOLDING_BARS"
                    def.exit.conditions?.let { ev.evaluate(it, idx) } == true -> "EXIT_RULE"
                    else -> null
                } ?: return null
            if (pendingRec) return null
            val side = if (short) OrderSide.BUY_TO_COVER else OrderSide.SELL
            val rationale = "Exit ($reason): last ${last.stripTrailingZeros().toPlainString()} vs average cost ${avgCost.setScale(4, RoundingMode.HALF_EVEN).toPlainString()} after $barsSince bar(s)."
            return SignalDraft(i, if (short) "EXIT_SHORT" else "EXIT_LONG", side, qty, if (limitFor(last, side.buys) != null) OrderType.LIMIT else OrderType.MARKET, limitFor(last, side.buys), last, listOf(reason), rationale)
        }
        if (pendingRec) return null
        val position = portfolios.positionViews(portfolios.get(a.portfolioId)).firstOrNull { it.instrumentId == i.id }
        if (position != null) return null // the instrument is held outside this strategy; do not mix
        if (!ev.evaluate(def.entry, idx)) return null
        val equity = portfolios.summary(a.portfolioId).equity
        val rawQty =
            when (def.sizing.method) {
                SizingMethod.FIXED_QUANTITY -> def.sizing.value
                SizingMethod.FIXED_CASH -> def.sizing.value.divide(last, 18, RoundingMode.FLOOR)
                SizingMethod.PERCENT_OF_EQUITY -> equity.multiply(def.sizing.value).divide(Decimals.HUNDRED, Decimals.MC).divide(last, 18, RoundingMode.FLOOR)
            }
        val qty = Decimals.floorToStep(rawQty, i.quantityIncrement)
        if (qty < i.minQuantity) return null
        val side = if (short) OrderSide.SELL_SHORT else OrderSide.BUY
        val triggered = triggeredConditions(def.entry, ev, idx)
        val bar = bars.last()
        val rationale =
            "Entry: ${triggered.joinToString("; ")} on the ${def.timeframe.code} bar that closed at ${BarSchedule.closeTime(i.assetClass, def.timeframe, bar.openTime)}. " +
                "Last price ${last.stripTrailingZeros().toPlainString()}; size ${def.sizing.method.name.lowercase().replace('_', ' ')} ${def.sizing.value.stripTrailingZeros().toPlainString()}."
        val limit = limitFor(last, side.buys)
        return SignalDraft(i, if (short) "ENTER_SHORT" else "ENTER_LONG", side, qty, if (limit != null) OrderType.LIMIT else OrderType.MARKET, limit, last, triggered, rationale)
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
        fun op(o: app.strategyforge.strategy.Operand) =
            when (o) {
                is app.strategyforge.strategy.Operand.Constant -> o.value.stripTrailingZeros().toPlainString()
                is app.strategyforge.strategy.Operand.Price -> o.field.name
                is app.strategyforge.strategy.Operand.IndicatorRef -> if (o.component == "value") o.id else "${o.id}.${o.component}"
            }
        return "${op(c.left)} ${c.comparison} ${op(c.right)}" + if (c.offsetBars > 0) " (${c.offsetBars} bars ago)" else ""
    }

    /** Quantity, average cost and first entry time of lots opened by this strategy's orders. */
    private fun strategyHolding(
        a: Activation,
        instrumentId: UUID,
    ): Triple<BigDecimal, BigDecimal, Instant>? =
        jdbc
            .sql(
                """
                select sum(l.quantity_remaining) q, sum(abs(l.cost_remaining)) c, min(l.opened_at) t from position_lots l
                join paper_executions e on e.id = l.open_execution_id join paper_orders o on o.id = e.order_id
                where l.portfolio_id = :p and l.instrument_id = :i and o.strategy_id = :s and l.quantity_remaining > 0
                """.trimIndent(),
            ).param("p", a.portfolioId)
            .param("i", instrumentId)
            .param("s", a.strategyId)
            .query { rs, _ ->
                val q = rs.getBigDecimal("q")
                if (q == null || q.signum() == 0) null else Triple(q, rs.getBigDecimal("c").divide(q, Decimals.MC), rs.getObject("t", java.time.OffsetDateTime::class.java).toInstant())
            }.single()

    /** Persists the signal, evaluates risk, then either recommends (default) or executes autonomously. */
    private fun emit(
        a: Activation,
        def: StrategyDefinition,
        evaluationId: UUID,
        bucket: Instant,
        d: SignalDraft,
        now: Instant,
    ): Boolean =
        tx.execute {
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
                jdbc
                    .sql(
                        """
                        insert into signals(id, strategy_id, version_id, content_hash, activation_id, evaluation_id, portfolio_id, instrument_id, bucket_start, action, side, quantity,
                          order_type, limit_price, reference_price, market_snapshot_id, triggered_rules, rationale, expires_at, disposition, created_at)
                        values (:id, :s, :v, :h, :a, :e, :p, :i, :b, :act, :side, :q, :t, :l, :ref, :snap, cast(:tr as jsonb), :rat, :exp, 'RECOMMENDED', :now)
                        on conflict (strategy_id, instrument_id, bucket_start, action) do nothing
                        """.trimIndent(),
                    ).param("id", id)
                    .param("s", a.strategyId)
                    .param("v", a.versionId)
                    .param("h", a.contentHash)
                    .param("a", a.id)
                    .param("e", evaluationId)
                    .param("p", a.portfolioId)
                    .param("i", d.instrument.id)
                    .param("b", ts(bucket))
                    .param("act", d.action)
                    .param("side", d.side.name)
                    .param("q", d.quantity)
                    .param("t", d.orderType.name)
                    .param("l", d.limitPrice)
                    .param("ref", d.referencePrice)
                    .param("snap", snapshot.id)
                    .param("tr", mapper.writeValueAsString(d.triggered))
                    .param("rat", d.rationale.take(2000))
                    .param("exp", ts(expires))
                    .param("now", ts(clock.instant()))
                    .update()
            if (inserted == 0) return@execute false
            if (a.mode == ActivationMode.AUTONOMOUS) {
                executeAutonomously(a, id, d)
            } else {
                val decision =
                    risk.evaluate(
                        OrderIntent(a.portfolioId, d.instrument.id, d.side, d.orderType, d.quantity, d.limitPrice, null, TimeInForce.DAY, OrderSource.RECOMMENDATION, a.strategyId, a.versionId),
                    )
                jdbc
                    .sql("update signals set risk_evaluation_id = :r, disposition = :d where id = :id")
                    .param("r", decision.id)
                    .param("d", if (decision.allowed) "RECOMMENDED" else "BLOCKED")
                    .param("id", id)
                    .update()
                val maxDev = portfolios.get(a.portfolioId).costModel.maxPriceDeviationPercent
                recommendations.create(id, decision.allowed, decision.id, decision.reasons, maxDev)
            }
            audit.record(AuditCategory.STRATEGY, "SIGNAL_CREATED", entityType = "Signal", entityId = id, details = mapOf("strategyId" to a.strategyId, "hash" to a.contentHash, "action" to d.action, "symbol" to d.instrument.symbol, "mode" to a.mode))
            true
        } ?: false

    /** Autonomous execution only after the authorization fingerprint still matches and risk passes (FR-072, FR-073). */
    private fun executeAutonomously(
        a: Activation,
        signalId: UUID,
        d: SignalDraft,
    ) {
        val current = activations.fingerprint(a.strategyId, a.versionId, a.portfolioId, a.allocationPercent)
        if (current != a.fingerprint) {
            jdbc.sql("update signals set disposition = 'BLOCKED' where id = :id").param("id", signalId).update()
            events.publishEvent(ReauthorizationRequired(a.strategyId, a.id, "Material change since autonomy was authorized"))
            return
        }
        val r =
            orders.create(
                OrderRequest(a.portfolioId, d.instrument.symbol, d.side, d.orderType, d.quantity, d.limitPrice, null, TimeInForce.DAY),
                OrderSource.AUTONOMOUS,
                OrderLinks(a.strategyId, a.versionId, null, signalId),
            )
        jdbc
            .sql("update signals set risk_evaluation_id = :r, disposition = :d where id = :id")
            .param("r", r.risk.id)
            .param("d", if (r.risk.allowed) "AUTO_EXECUTED" else "BLOCKED")
            .param("id", signalId)
            .update()
        if (!r.risk.allowed) {
            // FR-074: loss/drawdown limits and an unavailable risk engine or unverifiable data stop autonomy.
            val unverified =
                r.risk.results
                    .filter { it.rule in r.risk.blocking && it.outcome == app.strategyforge.risk.RuleOutcome.UNVERIFIED }
                    .map { it.rule }
            val conditions =
                buildSet {
                    if ("LOSS_LIMITS" in r.risk.blocking) add("LOSS_OR_DRAWDOWN_LIMIT")
                    if ("RISK_ENGINE_AVAILABLE" in unverified) add("RISK_ENGINE_UNAVAILABLE")
                    if (unverified.any { it != "RISK_ENGINE_AVAILABLE" }) add("UNVERIFIED_RISK_STATE")
                }
            if (conditions.isNotEmpty()) events.publishEvent(EvaluationBlocked(a.strategyId, a.id, a.mode, conditions, r.risk.reasons.joinToString("; ")))
            notifications.notify(NotificationCategory.RISK_EVENT, Severity.WARNING, "Autonomous order blocked: ${d.instrument.symbol}", "Risk blocked the simulated order: ${r.risk.reasons.joinToString("; ").take(500)}", "PaperOrder", r.order.id, "auto-blocked:${r.order.id}")
        }
    }

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.evaluation-interval-ms:20000}", initialDelay = 15000)
    fun scheduled() {
        if (!props.scheduler.enabled || marketClock.mode() != ClockMode.LIVE) return
        CorrelationIdFilter.withCorrelation("eval") { evaluateAll() }
    }

    /** Replay mode: evaluation after ingestion, execution and maintenance; then recommendation expiry. */
    @EventListener
    @Order(30)
    fun onReplayStep(e: MarketClockAdvanced) {
        evaluateAll()
    }

    @EventListener
    @Order(40)
    fun expireOnReplayStep(e: MarketClockAdvanced) {
        recommendations.expireDue()
    }

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.expiry-interval-ms:15000}", initialDelay = 15000)
    fun scheduledExpiry() {
        if (!props.scheduler.enabled || marketClock.mode() != ClockMode.LIVE) return
        CorrelationIdFilter.withCorrelation("expiry") { recommendations.expireDue() }
    }

    companion object {
        const val MIN_EXPIRY_SECONDS = 300L
        const val MAX_EXPIRY_SECONDS = 1800L
    }
}

/** Published when autonomy must be re-authorized after a material change (FR-072). */
data class ReauthorizationRequired(
    val strategyId: UUID,
    val activationId: UUID,
    val reason: String,
)

/** Keeps quotes fresh for every instrument in an active strategy's universe. */
@Component
class StrategyInterest(
    private val jdbc: JdbcClient,
) : MarketInterest {
    override fun instrumentIds(): Set<UUID> =
        jdbc
            .sql(
                """
                select i.id from strategy_activations a join strategy_versions v on v.id = a.version_id
                cross join lateral jsonb_array_elements_text(v.content -> 'universe' -> 'symbols') sym join instruments i on i.symbol = sym
                where a.status = 'ACTIVE'
                """.trimIndent(),
            ).query { rs, _ -> rs.uuid("id") }
            .list()
            .toSet()
}
