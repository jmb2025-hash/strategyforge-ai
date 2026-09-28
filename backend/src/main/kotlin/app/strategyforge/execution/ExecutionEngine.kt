package app.strategyforge.execution

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.market.ClockMode
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketCalendar
import app.strategyforge.market.MarketClock
import app.strategyforge.market.MarketClockAdvanced
import app.strategyforge.market.SessionState
import app.strategyforge.market.Timeframe
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.portfolio.Account
import app.strategyforge.portfolio.JournalType
import app.strategyforge.portfolio.LedgerService
import app.strategyforge.portfolio.LotService
import app.strategyforge.portfolio.LotSide
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.portfolio.Posting
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class Execution(
    val id: UUID,
    val orderId: UUID,
    val portfolioId: UUID,
    val instrumentId: UUID,
    val symbol: String,
    val fillSeq: Int,
    val side: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val referencePrice: BigDecimal,
    val notional: BigDecimal,
    val commission: BigDecimal,
    val spreadCost: BigDecimal,
    val slippageCost: BigDecimal,
    val realizedPnl: BigDecimal,
    val liquidityCap: BigDecimal?,
    val liquidityModel: String,
    val marketSnapshotId: UUID,
    val journalId: UUID,
    val executedAt: Instant,
    val recordedAt: Instant,
)

/** Published after each fill so strategy/autonomy modules can react (e.g. consecutive losses). */
data class OrderFilled(
    val orderId: UUID,
    val executionId: UUID,
    val portfolioId: UUID,
    val realizedPnl: BigDecimal,
    val closedPosition: Boolean,
)

/** Outcome of one processing attempt for one order (used by tests and the scheduler). */
enum class ProcessOutcome { FILLED, PARTIAL, WAITING, EXPIRED, FAILED, SKIPPED }

/**
 * Internal paper-execution simulator (FR-080..FR-085). Each fill, its balanced ledger journal,
 * lot updates and the order update commit in one transaction (section 8). Orders are locked
 * with SKIP LOCKED so concurrent workers, scheduler retries and restarts never double-fill.
 */
@Service
class ExecutionEngine(
    private val jdbc: JdbcClient,
    private val orders: OrderService,
    private val portfolios: PortfolioService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val ledger: LedgerService,
    private val lots: LotService,
    private val audit: AuditService,
    private val notifications: NotificationService,
    private val clock: Clock,
    private val marketClock: MarketClock,
    private val props: StrategyForgeProperties,
    private val events: ApplicationEventPublisher,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun processAll(): Map<UUID, ProcessOutcome> =
        orders.openOrderIds().associateWith { id ->
            try {
                tx.execute { processLocked(id) } ?: ProcessOutcome.SKIPPED
            } catch (e: Exception) {
                log.error("Execution processing failed for order {}", id, e)
                audit.recordIndependently(AuditCategory.EXECUTION, "EXECUTION_ERROR", AuditOutcome.FAILURE, "PaperOrder", id, mapOf("error" to e.javaClass.simpleName))
                ProcessOutcome.SKIPPED
            }
        }

    /** Processes one order inside the caller's transaction; returns SKIPPED if another worker holds it. */
    fun processLocked(orderId: UUID): ProcessOutcome {
        val locked =
            jdbc
                .sql("select id from paper_orders where id = :id and status in ('PENDING','PARTIALLY_FILLED') for update skip locked")
                .param("id", orderId)
                .query(UUID::class.java)
                .optional()
                .orElse(null) ?: return ProcessOutcome.SKIPPED
        val o = orders.get(locked)
        val now = marketClock.now()
        val portfolio = orders.lockPortfolio(o.portfolioId, requireActive = false)
        if (!now.isBefore(o.expiresAt)) {
            orders.releaseRemainingReservation(o, "Reservation released on expiry")
            orders.transition(o.id, o.status, OrderStatus.EXPIRED, "Time in force ${o.timeInForce} ended at ${o.expiresAt}")
            audit.record(AuditCategory.ORDER, "ORDER_EXPIRED", entityType = "PaperOrder", entityId = o.id)
            return ProcessOutcome.EXPIRED
        }
        if (now.isBefore(o.eligibleAt)) return ProcessOutcome.WAITING
        val instrument = instruments.byId(o.instrumentId)
        if (MarketCalendar.state(instrument.assetClass, now) != SessionState.OPEN) return ProcessOutcome.WAITING
        val m = portfolio.costModel
        val v = market.verifyQuote(instrument, m.executionMaxQuoteAgeSeconds, now)
        val q = v.quote
        if (!v.verified || q == null) return ProcessOutcome.WAITING // fail closed: never fill on unverified data
        if (o.lastFillQuoteTs != null && !q.exchangeTs.isAfter(o.lastFillQuoteTs)) return ProcessOutcome.WAITING

        if ((o.orderType == OrderType.STOP || o.orderType == OrderType.STOP_LIMIT) && !o.triggered) {
            if (!Pricing.stopTriggered(o.side, o.stopPrice!!, q.last)) return ProcessOutcome.WAITING
            jdbc
                .sql("update paper_orders set triggered = true, updated_at = :now where id = :id")
                .param("now", ts(clock.instant()))
                .param("id", o.id)
                .update()
            audit.record(AuditCategory.ORDER, "STOP_TRIGGERED", entityType = "PaperOrder", entityId = o.id, details = mapOf("stop" to o.stopPrice, "last" to q.last))
        }
        val limit = if (o.orderType == OrderType.LIMIT || o.orderType == OrderType.STOP_LIMIT) o.limitPrice else null
        val fp = Pricing.fillPrice(QuoteInput(q.bid, q.ask, q.last), o.side.buys, instrument.assetClass, m, limit, instrument.priceIncrement) ?: return ProcessOutcome.WAITING

        val volume = latestBarVolume(instrument.id, now)
        val cap = Pricing.liquidityCap(m, volume, instrument.quantityIncrement)
        var fillQty = Decimals.floorToStep(o.remaining.min(cap ?: o.remaining), instrument.quantityIncrement)
        val position = ledger.position(o.portfolioId, o.instrumentId).quantity
        fillQty =
            when (o.side) {
                OrderSide.SELL -> fillQty.min(position.max(BigDecimal.ZERO))
                OrderSide.BUY_TO_COVER -> fillQty.min(position.negate().max(BigDecimal.ZERO))
                else -> fillQty
            }
        if (fillQty.signum() <= 0) {
            if ((o.side == OrderSide.SELL || o.side == OrderSide.BUY_TO_COVER) && cap?.signum() != 0) return fail(o, "Position no longer holds enough quantity to fill")
            return ProcessOutcome.WAITING
        }
        val firstFill = o.filledQuantity.signum() == 0
        val isLast = fillQty.compareTo(o.remaining) == 0
        val reservedLeft = o.reservedAmount.subtract(o.reservationReleased)
        var release = if (isLast) reservedLeft else Decimals.money(o.reservedAmount.multiply(fillQty).divide(o.quantity, Decimals.MC)).min(reservedLeft)

        // Buy-side fills must be funded; shrink to what is affordable, or fail the order.
        if (o.side.buys) {
            val cash = ledger.balance(o.portfolioId, Account.CASH).add(release)
            val cost = { qty: BigDecimal -> qty.multiply(fp.price).let { it.add(Pricing.commission(m, qty, it, firstFill)) } }
            if (cost(fillQty) > cash) {
                val unit = fp.price.multiply(BigDecimal.ONE.add(m.commissionPercent.divide(Decimals.HUNDRED))).add(m.commissionPerShare)
                val affordable = Decimals.floorToStep(cash.subtract(if (firstFill) m.commissionPerOrder else BigDecimal.ZERO).max(BigDecimal.ZERO).divide(unit, 18, RoundingMode.FLOOR), instrument.quantityIncrement)
                if (affordable.signum() <= 0 || affordable < instrument.minQuantity) return fail(o, "Insufficient cash to fund the fill at ${fp.price.toPlainString()}")
                fillQty = affordable
                release = if (fillQty.compareTo(o.remaining) == 0) reservedLeft else Decimals.money(o.reservedAmount.multiply(fillQty).divide(o.quantity, Decimals.MC)).min(reservedLeft)
            }
        }
        return fill(o, instrument.symbol, fillQty, fp, release, firstFill, cap, volume != null, now)
    }

    private fun fill(
        o: PaperOrder,
        symbol: String,
        qty: BigDecimal,
        fp: FillPrice,
        release: BigDecimal,
        firstFill: Boolean,
        cap: BigDecimal?,
        liquidityKnown: Boolean,
        now: Instant,
    ): ProcessOutcome {
        val portfolio = portfolios.get(o.portfolioId)
        val m = portfolio.costModel
        val instrument = instruments.byId(o.instrumentId)
        val notional = Decimals.money(qty.multiply(fp.price))
        val commission = Pricing.commission(m, qty, notional, firstFill)
        val postings = mutableListOf<Posting>()
        if (release.signum() > 0) {
            postings += Posting(Account.CASH, release)
            postings += Posting(Account.CASH_RESERVED, release.negate())
        }
        var realized = BigDecimal.ZERO
        val executionId = UUID.randomUUID()
        when (o.side) {
            OrderSide.BUY -> {
                postings += Posting(Account.POSITION, notional, o.instrumentId, qty)
                postings += Posting(Account.CASH, notional.negate())
                lots.open(o.portfolioId, o.instrumentId, LotSide.LONG, qty, notional, now, executionId)
            }
            OrderSide.SELL -> {
                val relief = lots.relieveFifo(o.portfolioId, o.instrumentId, LotSide.LONG, qty, now)
                val x = relief.cost.subtract(notional)
                postings += Posting(Account.POSITION, relief.cost.negate(), o.instrumentId, qty.negate())
                postings += Posting(Account.CASH, notional)
                postings += Posting(Account.REALIZED_PNL, x)
                realized = x.negate()
            }
            OrderSide.SELL_SHORT -> {
                postings += Posting(Account.POSITION, notional.negate(), o.instrumentId, qty.negate())
                postings += Posting(Account.CASH, notional)
                lots.open(o.portfolioId, o.instrumentId, LotSide.SHORT, qty, notional.negate(), now, executionId)
            }
            OrderSide.BUY_TO_COVER -> {
                val relief = lots.relieveFifo(o.portfolioId, o.instrumentId, LotSide.SHORT, qty, now)
                val x = notional.add(relief.cost)
                postings += Posting(Account.POSITION, relief.cost.negate(), o.instrumentId, qty)
                postings += Posting(Account.CASH, notional.negate())
                postings += Posting(Account.REALIZED_PNL, x)
                realized = x.negate()
            }
        }
        if (commission.signum() > 0) {
            postings += Posting(Account.FEES, commission)
            postings += Posting(Account.CASH, commission.negate())
        }
        val journalId =
            ledger.post(o.portfolioId, JournalType.EXECUTION, now, "${o.side} ${qty.stripTrailingZeros().toPlainString()} $symbol @ ${fp.price.stripTrailingZeros().toPlainString()} (paper)", postings, "PaperOrder", o.id)
        val (snapshot, _) = market.captureSnapshot(instrument, m.executionMaxQuoteAgeSeconds)
        val seq =
            jdbc
                .sql("select coalesce(max(fill_seq), 0) + 1 from paper_executions where order_id = :o")
                .param("o", o.id)
                .query(Int::class.java)
                .single()
        jdbc
            .sql(
                """
                insert into paper_executions(id, order_id, portfolio_id, instrument_id, fill_seq, side, quantity, price, reference_price, notional, commission,
                  spread_cost, slippage_cost, realized_pnl, liquidity_cap, liquidity_model, market_snapshot_id, journal_id, executed_at, recorded_at)
                values (:id, :o, :p, :i, :seq, :side, :q, :price, :ref, :n, :c, :sc, :slc, :r, :cap, :lm, :snap, :j, :now, :rec)
                """.trimIndent(),
            ).param("id", executionId)
            .param("o", o.id)
            .param("p", o.portfolioId)
            .param("i", o.instrumentId)
            .param("seq", seq)
            .param("side", o.side.name)
            .param("q", Decimals.quantity(qty))
            .param("price", fp.price)
            .param("ref", fp.referencePrice)
            .param("n", notional)
            .param("c", commission)
            .param("sc", Decimals.money(fp.spreadCostPerUnit.multiply(qty)))
            .param("slc", Decimals.money(fp.slippagePerUnit.multiply(qty)))
            .param("r", Decimals.money(realized))
            .param("cap", cap)
            .param("lm", if (liquidityKnown) "PARTICIPATION_${m.participationRatePercent.toPlainString()}%" else "UNAVAILABLE_NO_VOLUME")
            .param("snap", snapshot.id)
            .param("j", journalId)
            .param("now", ts(now))
            .param("rec", ts(clock.instant()))
            .update()

        val newFilled = o.filledQuantity.add(qty)
        val avg = (o.averageFillPrice ?: BigDecimal.ZERO).multiply(o.filledQuantity).add(fp.price.multiply(qty)).divide(newFilled, Decimals.PRICE_SCALE, RoundingMode.HALF_EVEN)
        jdbc
            .sql(
                "update paper_orders set filled_quantity = :f, average_fill_price = :a, reservation_released = reservation_released + :r, last_fill_quote_ts = :qts where id = :id",
            ).param("f", Decimals.quantity(newFilled))
            .param("a", avg)
            .param("r", release)
            .param("qts", ts(market.latestQuote(o.instrumentId)?.exchangeTs))
            .param("id", o.id)
            .update()
        val full = newFilled.compareTo(o.quantity) == 0
        orders.transition(o.id, o.status, if (full) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED, "Fill #$seq ${qty.stripTrailingZeros().toPlainString()} @ ${fp.price.stripTrailingZeros().toPlainString()}")
        if (full) orders.releaseRemainingReservation(orders.get(o.id), "Residual reservation released after final fill")
        val closedPosition = !o.side.increasesRisk && ledger.position(o.portfolioId, o.instrumentId).quantity.signum() == 0
        portfolios.recordEquity(o.portfolioId, "EXECUTION")
        audit.record(
            AuditCategory.EXECUTION,
            "EXECUTION_FILLED",
            entityType = "PaperExecution",
            entityId = executionId,
            details =
                mapOf(
                    "orderId" to o.id,
                    "portfolioId" to o.portfolioId,
                    "symbol" to symbol,
                    "side" to o.side,
                    "quantity" to qty,
                    "price" to fp.price,
                    "reference" to fp.referencePrice,
                    "commission" to commission,
                    "spreadSource" to fp.spreadSource,
                    "journalId" to journalId,
                    "marketSnapshotId" to snapshot.id,
                    "strategyVersionId" to o.strategyVersionId,
                    "riskEvaluationId" to o.riskEvaluationId,
                    "venue" to "PAPER_SIMULATOR",
                ),
        )
        notifications.notify(
            // A filled stop order is a stop event (FR-101), announced on the same channel.
            if (o.orderType == OrderType.STOP || o.orderType == OrderType.STOP_LIMIT) NotificationCategory.STOP_TARGET else NotificationCategory.ORDER_FILL,
            Severity.INFO,
            "Paper ${if (full) "fill" else "partial fill"}: ${o.side} $symbol",
            "${o.side} ${qty.stripTrailingZeros().toPlainString()} $symbol at ${fp.price.stripTrailingZeros().toPlainString()} (simulated). Filled ${newFilled.stripTrailingZeros().toPlainString()} of ${o.quantity.stripTrailingZeros().toPlainString()}.",
            "PaperOrder",
            o.id,
            "fill:$executionId",
        )
        events.publishEvent(OrderFilled(o.id, executionId, o.portfolioId, Decimals.money(realized), closedPosition))
        return if (full) ProcessOutcome.FILLED else ProcessOutcome.PARTIAL
    }

    private fun fail(
        o: PaperOrder,
        reason: String,
    ): ProcessOutcome {
        orders.releaseRemainingReservation(o, "Reservation released: $reason")
        orders.transition(o.id, o.status, OrderStatus.FAILED, reason)
        audit.record(AuditCategory.ORDER, "ORDER_FAILED", AuditOutcome.FAILURE, "PaperOrder", o.id, mapOf("reason" to reason))
        notifications.notify(NotificationCategory.ORDER_REJECTION, Severity.WARNING, "Paper order failed: ${o.side} ${o.symbol}", reason, "PaperOrder", o.id, "order-failed:${o.id}")
        return ProcessOutcome.FAILED
    }

    /** Volume of the most recent closed 1-minute bar (point in time), used for the liquidity cap. */
    private fun latestBarVolume(
        instrumentId: UUID,
        now: Instant,
    ): BigDecimal? {
        val i = instruments.byId(instrumentId)
        // Replay hydrates from fixtures; live mode relies on ingestion so executions never spend provider quota.
        val s = market.candles(i, Timeframe.M1, now.minus(Duration.ofMinutes(10)), now, now, hydrate = marketClock.mode() == ClockMode.REPLAY)
        return s.bars.lastOrNull()?.volume
    }

    fun executions(
        portfolioId: UUID?,
        orderId: UUID?,
        limit: Int,
    ): List<Execution> =
        jdbc
            .sql(
                """
                select e.*, i.symbol from paper_executions e join instruments i on i.id = e.instrument_id
                where (cast(:p as uuid) is null or e.portfolio_id = :p) and (cast(:o as uuid) is null or e.order_id = :o)
                order by e.executed_at desc, e.fill_seq desc limit :l
                """.trimIndent(),
            ).param("p", portfolioId)
            .param("o", orderId)
            .param("l", limit)
            .query { rs, _ ->
                Execution(
                    rs.uuid("id"),
                    rs.uuid("order_id"),
                    rs.uuid("portfolio_id"),
                    rs.uuid("instrument_id"),
                    rs.getString("symbol"),
                    rs.getInt("fill_seq"),
                    rs.getString("side"),
                    rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("reference_price"),
                    rs.getBigDecimal("notional"),
                    rs.getBigDecimal("commission"),
                    rs.getBigDecimal("spread_cost"),
                    rs.getBigDecimal("slippage_cost"),
                    rs.getBigDecimal("realized_pnl"),
                    rs.getBigDecimal("liquidity_cap"),
                    rs.getString("liquidity_model"),
                    rs.uuid("market_snapshot_id"),
                    rs.uuid("journal_id"),
                    rs.instant("executed_at"),
                    rs.instant("recorded_at"),
                )
            }.list()

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.execution-interval-ms:2000}", initialDelay = 5000)
    fun scheduled() {
        if (!props.scheduler.enabled || marketClock.mode() != ClockMode.LIVE) return
        CorrelationIdFilter.withCorrelation("exec") { processAll() }
    }

    /** Replay mode: executions run after ingestion on every clock step. */
    @EventListener
    @Order(20)
    fun onReplayStep(e: MarketClockAdvanced) {
        processAll()
    }
}
