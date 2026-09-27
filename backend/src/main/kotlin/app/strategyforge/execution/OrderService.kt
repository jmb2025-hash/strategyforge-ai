package app.strategyforge.execution

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.decimalOrNull
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.Cursor
import app.strategyforge.common.web.PageResponse
import app.strategyforge.common.web.Paging
import app.strategyforge.common.web.Problems
import app.strategyforge.market.AssetClass
import app.strategyforge.market.Instrument
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketCalendar
import app.strategyforge.market.MarketClock
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.portfolio.Account
import app.strategyforge.portfolio.JournalType
import app.strategyforge.portfolio.LedgerService
import app.strategyforge.portfolio.Portfolio
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.portfolio.Posting
import app.strategyforge.risk.OrderIntent
import app.strategyforge.risk.OrderSource
import app.strategyforge.risk.RiskDecision
import app.strategyforge.risk.RiskEngine
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class OrderStatus(
    val open: Boolean,
    val terminal: Boolean,
) {
    CREATED(true, false),
    VALIDATED(true, false),
    REJECTED(false, true),
    PENDING(true, false),
    PARTIALLY_FILLED(true, false),
    FILLED(false, true),
    CANCELLED(false, true),
    EXPIRED(false, true),
    FAILED(false, true),
}

/** Paper order lifecycle (section 8). Any transition not listed is refused. */
object OrderStateMachine {
    private val allowed =
        mapOf(
            OrderStatus.CREATED to setOf(OrderStatus.VALIDATED, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
            OrderStatus.VALIDATED to setOf(OrderStatus.PENDING, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
            OrderStatus.PENDING to setOf(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
            OrderStatus.PARTIALLY_FILLED to setOf(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
        )

    fun canTransition(
        from: OrderStatus,
        to: OrderStatus,
    ): Boolean = allowed[from]?.contains(to) == true

    fun check(
        from: OrderStatus,
        to: OrderStatus,
    ) {
        check(canTransition(from, to)) { "Illegal order transition $from -> $to" }
    }
}

data class PaperOrder(
    val id: UUID,
    val portfolioId: UUID,
    val instrumentId: UUID,
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal?,
    val stopPrice: BigDecimal?,
    val status: OrderStatus,
    val source: OrderSource,
    val venue: String,
    val strategyId: UUID?,
    val strategyVersionId: UUID?,
    val recommendationId: UUID?,
    val signalId: UUID?,
    val marketSnapshotId: UUID?,
    val riskEvaluationId: UUID?,
    val filledQuantity: BigDecimal,
    val averageFillPrice: BigDecimal?,
    val reservedAmount: BigDecimal,
    val reservationReleased: BigDecimal,
    val triggered: Boolean,
    val lastFillQuoteTs: Instant?,
    val rejectionReason: String?,
    val createdAt: Instant,
    val eligibleAt: Instant,
    val expiresAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    val remaining: BigDecimal get() = quantity.subtract(filledQuantity)
}

data class OrderRequest(
    val portfolioId: UUID,
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    val timeInForce: TimeInForce = TimeInForce.DAY,
)

/** Links recorded on strategy-originated orders (FR-085). */
data class OrderLinks(
    val strategyId: UUID? = null,
    val strategyVersionId: UUID? = null,
    val recommendationId: UUID? = null,
    val signalId: UUID? = null,
)

data class StatusChange(
    val from: String?,
    val to: String,
    val at: Instant,
    val marketTime: Instant,
    val reason: String?,
)

data class OrderResult(
    val order: PaperOrder,
    val risk: RiskDecision,
)

/**
 * Creates, validates, reserves and cancels paper orders. Order creation, risk evaluation, cash
 * reservation and audit commit atomically (section 8). The simulator is the only venue.
 */
@Service
class OrderService(
    private val jdbc: JdbcClient,
    private val portfolios: PortfolioService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val risk: RiskEngine,
    private val ledger: LedgerService,
    private val audit: AuditService,
    private val notifications: NotificationService,
    private val clock: Clock,
    private val marketClock: MarketClock,
) {
    @Transactional
    fun create(
        req: OrderRequest,
        source: OrderSource,
        links: OrderLinks = OrderLinks(),
    ): OrderResult {
        validateShape(req)
        val portfolio = lockPortfolio(req.portfolioId)
        val instrument = instruments.bySymbol(req.symbol)
        val now = marketClock.now()
        // Refresh on demand so the decision uses the freshest verifiable quote; failures simply leave it unverified.
        runCatching { market.refreshQuote(instrument) }
        val (snapshot, _) = market.captureSnapshot(instrument, portfolio.costModel.executionMaxQuoteAgeSeconds)
        val id = UUID.randomUUID()
        val wall = clock.instant()
        val expires = expiry(instrument, req.timeInForce, now)
        jdbc
            .sql(
                """
                insert into paper_orders(id, portfolio_id, instrument_id, side, order_type, time_in_force, quantity, limit_price, stop_price, status, source,
                  strategy_id, strategy_version_id, recommendation_id, signal_id, market_snapshot_id, created_at, eligible_at, expires_at, updated_at)
                values (:id, :p, :i, :side, :type, :tif, :q, :lp, :sp, 'CREATED', :src, :sid, :svid, :rid, :sig, :snap, :now, :elig, :exp, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("p", portfolio.id)
            .param("i", instrument.id)
            .param("side", req.side.name)
            .param("type", req.orderType.name)
            .param("tif", req.timeInForce.name)
            .param("q", Decimals.quantity(req.quantity))
            .param("lp", req.limitPrice)
            .param("sp", req.stopPrice)
            .param("src", source.name)
            .param("sid", links.strategyId)
            .param("svid", links.strategyVersionId)
            .param("rid", links.recommendationId)
            .param("sig", links.signalId)
            .param("snap", snapshot.id)
            .param("now", ts(wall))
            .param("elig", ts(now.plusSeconds(portfolio.costModel.executionDelaySeconds.toLong())))
            .param("exp", ts(expires))
            .update()
        history(id, null, OrderStatus.CREATED, "Order received from $source")

        val decision =
            risk.evaluate(
                OrderIntent(portfolio.id, instrument.id, req.side, req.orderType, req.quantity, req.limitPrice, req.stopPrice, req.timeInForce, source, links.strategyId, links.strategyVersionId),
            )
        jdbc
            .sql("update paper_orders set risk_evaluation_id = :r where id = :id")
            .param("r", decision.id)
            .param("id", id)
            .update()
        if (!decision.allowed) {
            val reason = decision.reasons.joinToString("; ").take(1000)
            transition(id, OrderStatus.CREATED, OrderStatus.REJECTED, reason)
            jdbc
                .sql("update paper_orders set rejection_reason = :r where id = :id")
                .param("r", reason)
                .param("id", id)
                .update()
            audit.record(AuditCategory.ORDER, "ORDER_REJECTED", AuditOutcome.BLOCKED, "PaperOrder", id, mapOf("portfolioId" to portfolio.id, "symbol" to instrument.symbol, "side" to req.side, "source" to source, "reasons" to decision.reasons))
            notifications.notify(NotificationCategory.ORDER_REJECTION, Severity.WARNING, "Paper order rejected: ${req.side} ${instrument.symbol}", reason, "PaperOrder", id, "order-rejected:$id")
            return OrderResult(get(id), decision)
        }
        transition(id, OrderStatus.CREATED, OrderStatus.VALIDATED, "Risk evaluation ${decision.id} passed")
        val reservation = reservationFor(portfolio, req, decision)
        if (reservation.signum() > 0) {
            ledger.post(
                portfolio.id,
                JournalType.RESERVATION,
                now,
                "Cash reserved for ${req.side} ${req.quantity.stripTrailingZeros().toPlainString()} ${instrument.symbol}",
                listOf(Posting(Account.CASH_RESERVED, reservation), Posting(Account.CASH, reservation.negate())),
                "PaperOrder",
                id,
            )
            jdbc
                .sql("update paper_orders set reserved_amount = :r where id = :id")
                .param("r", reservation)
                .param("id", id)
                .update()
        }
        transition(id, OrderStatus.VALIDATED, OrderStatus.PENDING, "Awaiting simulated execution")
        audit.record(
            AuditCategory.ORDER,
            "ORDER_CREATED",
            entityType = "PaperOrder",
            entityId = id,
            details =
                mapOf(
                    "portfolioId" to portfolio.id,
                    "symbol" to instrument.symbol,
                    "side" to req.side,
                    "type" to req.orderType,
                    "quantity" to req.quantity,
                    "limitPrice" to req.limitPrice,
                    "stopPrice" to req.stopPrice,
                    "source" to source,
                    "riskEvaluationId" to decision.id,
                    "reserved" to reservation,
                    "marketSnapshotId" to snapshot.id,
                    "strategyVersionId" to links.strategyVersionId,
                    "recommendationId" to links.recommendationId,
                    "venue" to "PAPER_SIMULATOR",
                ),
        )
        return OrderResult(get(id), decision)
    }

    private fun reservationFor(
        p: Portfolio,
        req: OrderRequest,
        decision: RiskDecision,
    ): BigDecimal {
        val estimated =
            decision.results
                .firstOrNull { it.rule == "BUYING_POWER" }
                ?.actual
                ?.let(::BigDecimal)
        return when (req.side) {
            OrderSide.BUY, OrderSide.SELL_SHORT, OrderSide.BUY_TO_COVER -> estimated ?: BigDecimal.ZERO
            OrderSide.SELL -> BigDecimal.ZERO
        }.let { Decimals.money(it).min(ledger.balance(p.id, Account.CASH).max(BigDecimal.ZERO)) }
    }

    @Transactional
    fun cancel(
        id: UUID,
        reason: String,
    ): PaperOrder {
        val o = lock(id)
        if (!o.status.open) throw Problems.conflict("order-not-cancellable", "Order is ${o.status}")
        lockPortfolio(o.portfolioId, requireActive = false)
        releaseRemainingReservation(o, "Reservation released on cancel")
        transition(id, o.status, OrderStatus.CANCELLED, reason)
        audit.record(AuditCategory.ORDER, "ORDER_CANCELLED", entityType = "PaperOrder", entityId = id, details = mapOf("reason" to reason, "filled" to o.filledQuantity))
        return get(id)
    }

    /** Cancels every cancellable order (emergency control); returns the cancelled ids. */
    @Transactional
    fun cancelAllOpen(
        reason: String,
        portfolioId: UUID? = null,
    ): List<UUID> {
        val ids =
            jdbc
                .sql("select id from paper_orders where status in ('CREATED','VALIDATED','PENDING','PARTIALLY_FILLED') and (cast(:p as uuid) is null or portfolio_id = :p) order by created_at")
                .param("p", portfolioId)
                .query(UUID::class.java)
                .list()
        ids.forEach { cancel(it, reason) }
        return ids
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun releaseRemainingReservation(
        o: PaperOrder,
        description: String,
    ) {
        val remaining = o.reservedAmount.subtract(o.reservationReleased)
        if (remaining.signum() <= 0) return
        ledger.post(
            o.portfolioId,
            JournalType.RESERVATION_RELEASE,
            marketClock.now(),
            description,
            listOf(Posting(Account.CASH, remaining), Posting(Account.CASH_RESERVED, remaining.negate())),
            "PaperOrder",
            o.id,
        )
        jdbc.sql("update paper_orders set reservation_released = reserved_amount where id = :id").param("id", o.id).update()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun transition(
        id: UUID,
        from: OrderStatus,
        to: OrderStatus,
        reason: String?,
    ) {
        OrderStateMachine.check(from, to)
        val n =
            jdbc
                .sql("update paper_orders set status = :to, updated_at = :now, version = version + 1 where id = :id and status = :from")
                .param("to", to.name)
                .param("now", ts(clock.instant()))
                .param("id", id)
                .param("from", from.name)
                .update()
        check(n == 1) { "Order $id was not in status $from" }
        history(id, from, to, reason)
    }

    private fun history(
        id: UUID,
        from: OrderStatus?,
        to: OrderStatus,
        reason: String?,
    ) {
        jdbc
            .sql("insert into order_status_history(order_id, from_status, to_status, at, market_time, reason) values (:id, :f, :t, :at, :mt, :r)")
            .param("id", id)
            .param("f", from?.name)
            .param("t", to.name)
            .param("at", ts(clock.instant()))
            .param("mt", ts(marketClock.now()))
            .param("r", reason?.take(1000))
            .update()
    }

    fun statusHistory(id: UUID): List<StatusChange> =
        jdbc
            .sql("select * from order_status_history where order_id = :id order by id")
            .param("id", id)
            .query { rs, _ -> StatusChange(rs.getString("from_status"), rs.getString("to_status"), rs.instant("at"), rs.instant("market_time"), rs.getString("reason")) }
            .list()

    /** DAY orders expire at the session close (equities) or after 24h (crypto); GTC after 90 days. */
    fun expiry(
        instrument: Instrument,
        tif: TimeInForce,
        now: Instant,
    ): Instant =
        when {
            tif == TimeInForce.GTC -> now.plus(Duration.ofDays(GTC_DAYS))
            instrument.assetClass == AssetClass.CRYPTO -> now.plus(Duration.ofHours(24))
            else -> MarketCalendar.nextSession(now)?.close ?: now.plus(Duration.ofHours(24))
        }

    private fun validateShape(req: OrderRequest) {
        if (req.quantity.signum() <= 0) throw Problems.badRequest("invalid-quantity", "quantity must be positive")
        when (req.orderType) {
            OrderType.MARKET -> if (req.limitPrice != null || req.stopPrice != null) throw Problems.badRequest("invalid-order", "MARKET orders take no limit or stop price")
            OrderType.LIMIT -> if (req.limitPrice == null || req.stopPrice != null) throw Problems.badRequest("invalid-order", "LIMIT orders require limitPrice only")
            OrderType.STOP -> if (req.stopPrice == null || req.limitPrice != null) throw Problems.badRequest("invalid-order", "STOP orders require stopPrice only")
            OrderType.STOP_LIMIT -> if (req.stopPrice == null || req.limitPrice == null) throw Problems.badRequest("invalid-order", "STOP_LIMIT orders require stopPrice and limitPrice")
        }
        listOfNotNull(req.limitPrice, req.stopPrice).forEach { if (it.signum() <= 0) throw Problems.badRequest("invalid-price", "Prices must be positive") }
    }

    fun lockPortfolio(
        id: UUID,
        requireActive: Boolean = true,
    ): Portfolio {
        jdbc
            .sql("select id from portfolios where id = :id for update")
            .param("id", id)
            .query(UUID::class.java)
            .optional()
            .orElseThrow { Problems.notFound("Portfolio", id) }
        return if (requireActive) portfolios.requireActive(id) else portfolios.get(id)
    }

    fun lock(id: UUID): PaperOrder =
        jdbc
            .sql("select o.*, i.symbol from paper_orders o join instruments i on i.id = o.instrument_id where o.id = :id for update of o")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Order", id) }

    fun get(id: UUID): PaperOrder =
        jdbc
            .sql("select o.*, i.symbol from paper_orders o join instruments i on i.id = o.instrument_id where o.id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Order", id) }

    fun byRecommendation(recommendationId: UUID): PaperOrder? =
        jdbc
            .sql("select o.*, i.symbol from paper_orders o join instruments i on i.id = o.instrument_id where o.recommendation_id = :r")
            .param("r", recommendationId)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun list(
        portfolioId: UUID?,
        status: String?,
        cursor: String?,
        limit: Int?,
    ): PageResponse<PaperOrder> {
        val c = Cursor.decode(cursor)
        val l = Paging.limit(limit)
        val statuses = status?.split(',')?.map { it.trim().uppercase() }?.filter { s -> OrderStatus.entries.any { it.name == s } }
        val rows =
            jdbc
                .sql(
                    """
                    select o.*, i.symbol from paper_orders o join instruments i on i.id = o.instrument_id
                    where (cast(:p as uuid) is null or o.portfolio_id = :p)
                      and (cast(:st as text[]) is null or o.status = any(cast(:st as text[])))
                      and (cast(:ca as timestamptz) is null or (o.created_at, o.id) < (cast(:ca as timestamptz), cast(:cid as uuid)))
                    order by o.created_at desc, o.id desc limit :l
                    """.trimIndent(),
                ).param("p", portfolioId)
                .param("st", statuses?.let { "{" + it.joinToString(",") + "}" })
                .param("ca", ts(c?.at))
                .param("cid", c?.id)
                .param("l", l + 1)
                .query { rs, _ -> map(rs) }
                .list()
        return Paging.page(rows, l) { Cursor(it.createdAt, it.id.toString()) }
    }

    fun openOrderIds(): List<UUID> = jdbc.sql("select id from paper_orders where status in ('PENDING','PARTIALLY_FILLED') order by created_at").query(UUID::class.java).list()

    fun map(rs: java.sql.ResultSet) =
        PaperOrder(
            rs.uuid("id"),
            rs.uuid("portfolio_id"),
            rs.uuid("instrument_id"),
            rs.getString("symbol"),
            OrderSide.valueOf(rs.getString("side")),
            OrderType.valueOf(rs.getString("order_type")),
            TimeInForce.valueOf(rs.getString("time_in_force")),
            rs.getBigDecimal("quantity"),
            rs.decimalOrNull("limit_price"),
            rs.decimalOrNull("stop_price"),
            OrderStatus.valueOf(rs.getString("status")),
            OrderSource.valueOf(rs.getString("source")),
            rs.getString("venue"),
            rs.uuidOrNull("strategy_id"),
            rs.uuidOrNull("strategy_version_id"),
            rs.uuidOrNull("recommendation_id"),
            rs.uuidOrNull("signal_id"),
            rs.uuidOrNull("market_snapshot_id"),
            rs.uuidOrNull("risk_evaluation_id"),
            rs.getBigDecimal("filled_quantity"),
            rs.decimalOrNull("average_fill_price"),
            rs.getBigDecimal("reserved_amount"),
            rs.getBigDecimal("reservation_released"),
            rs.getBoolean("triggered"),
            rs.instantOrNull("last_fill_quote_ts"),
            rs.getString("rejection_reason"),
            rs.instant("created_at"),
            rs.instant("eligible_at"),
            rs.instant("expires_at"),
            rs.instant("updated_at"),
            rs.getLong("version"),
        )

    companion object {
        const val GTC_DAYS = 90L
    }
}
