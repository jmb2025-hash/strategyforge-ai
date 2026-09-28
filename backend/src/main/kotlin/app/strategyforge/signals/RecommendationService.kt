package app.strategyforge.signals

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
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
import app.strategyforge.execution.OrderLinks
import app.strategyforge.execution.OrderRequest
import app.strategyforge.execution.OrderService
import app.strategyforge.execution.OrderSide
import app.strategyforge.execution.OrderStatus
import app.strategyforge.execution.OrderType
import app.strategyforge.execution.TimeInForce
import app.strategyforge.identity.ActionToken
import app.strategyforge.identity.ActionTokenService
import app.strategyforge.identity.CurrentOwner
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketClock
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.risk.OrderSource
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class RecommendationStatus(
    val open: Boolean,
) {
    CREATED(true),
    BLOCKED(false),
    PENDING(true),
    ACCEPTED(false),
    MODIFIED(false),
    DECLINED(false),
    EXPIRED(false),
    SUPERSEDED(false),
    FAILED(false),
}

data class Recommendation(
    val id: UUID,
    val signalId: UUID,
    val strategyId: UUID,
    val strategyName: String,
    val portfolioId: UUID,
    val instrumentId: UUID,
    val symbol: String,
    val status: RecommendationStatus,
    val statusReason: String?,
    val side: OrderSide,
    val quantity: BigDecimal,
    val orderType: OrderType,
    val limitPrice: BigDecimal?,
    val referencePrice: BigDecimal,
    val maxDeviationPercent: BigDecimal,
    val expiresAt: Instant,
    val snoozedUntil: Instant?,
    val riskEvaluationId: UUID?,
    val orderId: UUID?,
    val rationale: String,
    val triggeredRules: List<String>,
    val versionHash: String,
    val marketSnapshotId: UUID,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val version: Long,
)

data class RecommendationDetail(
    val recommendation: Recommendation,
    val actionToken: ActionToken?,
    val decisions: List<Map<String, Any?>>,
    val disclaimer: String,
)

data class Modification(
    val quantity: BigDecimal? = null,
    val limitPrice: BigDecimal? = null,
)

data class AcceptRequest(
    val actionToken: String?,
    val modification: Modification? = null,
)

data class DeclineRequest(
    val reason: String? = null,
)

data class SnoozeRequest(
    val minutes: Long,
)

data class DecisionResult(
    val recommendation: Recommendation,
    val orderId: UUID?,
    val orderStatus: String?,
    val detail: String,
)

/**
 * Recommendation lifecycle (section 8, FR-062..FR-066). Acceptance consumes a single-use action
 * token, locks the recommendation, re-checks expiry and price deviation, and creates the paper
 * order, its risk evaluation, cash reservation and audit in one transaction. The unique
 * recommendation_id on paper_orders guarantees at most one order per recommendation (FR-065).
 */
@Service
class RecommendationService(
    private val jdbc: JdbcClient,
    private val orders: OrderService,
    private val tokens: ActionTokenService,
    private val market: MarketDataService,
    private val instruments: InstrumentService,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val marketClock: MarketClock,
    txManager: PlatformTransactionManager,
) {
    private val independent = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun detail(id: UUID): RecommendationDetail {
        val r = get(id)
        // A fresh single-use token is issued only to an authenticated session viewing a pending recommendation.
        val token = if (r.status == RecommendationStatus.PENDING) tokens.issue(PURPOSE, id.toString()) else null
        return RecommendationDetail(r, token, decisions(id), DISCLAIMER)
    }

    @Transactional
    fun accept(
        id: UUID,
        req: AcceptRequest,
    ): DecisionResult {
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) {
            throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}", mapOf("status" to r.status, "orderId" to r.orderId))
        }
        tokens.consume(req.actionToken, PURPOSE, id.toString())
        val now = marketClock.now()
        if (!now.isBefore(r.expiresAt)) {
            finalizeIndependently(r, RecommendationStatus.EXPIRED, "EXPIRE", "Expired at ${r.expiresAt} before acceptance")
            throw Problems.conflict("recommendation-expired", "The recommendation expired at ${r.expiresAt}")
        }
        val instrument = instruments.byId(r.instrumentId)
        market.refreshQuote(instrument)
        val q = market.verifyQuote(instrument, DEVIATION_QUOTE_AGE, now)
        val price = q.quote?.last
        if (!q.verified || price == null) {
            throw Problems.unavailable("market-data-unverified", "Current market data cannot be verified (${q.status}); acceptance is not possible now")
        }
        val deviation =
            price
                .subtract(r.referencePrice)
                .abs()
                .multiply(Decimals.HUNDRED)
                .divide(r.referencePrice, Decimals.MC)
        if (deviation > r.maxDeviationPercent) {
            finalizeIndependently(r, RecommendationStatus.FAILED, "FAIL", "Price moved ${Decimals.percent(deviation).stripTrailingZeros().toPlainString()}% from ${r.referencePrice.toPlainString()} (limit ${r.maxDeviationPercent.toPlainString()}%)")
            throw Problems.conflict("price-deviation", "The market price moved ${deviation.setScale(2, java.math.RoundingMode.HALF_EVEN)}% since the recommendation; limit ${r.maxDeviationPercent.toPlainString()}%")
        }
        val (qty, limit, modified) = applyModification(r, req.modification, instrument.minQuantity, instrument.quantityIncrement)
        val signal =
            jdbc
                .sql("select version_id from signals where id = :s")
                .param("s", r.signalId)
                .query(UUID::class.java)
                .single()
        val result =
            orders.create(
                OrderRequest(r.portfolioId, instrument.symbol, r.side, if (limit != null) OrderType.LIMIT else r.orderType, qty, limit, null, TimeInForce.DAY),
                OrderSource.RECOMMENDATION,
                OrderLinks(r.strategyId, signal, r.id, r.signalId),
            )
        val order = result.order
        val (status, decision) =
            if (order.status == OrderStatus.REJECTED) {
                RecommendationStatus.FAILED to "FAIL"
            } else if (modified) {
                RecommendationStatus.MODIFIED to "MODIFY"
            } else {
                RecommendationStatus.ACCEPTED to "ACCEPT"
            }
        jdbc
            .sql("update recommendations set status = :s, status_reason = :r, order_id = :o, decided_at = :now, updated_at = :now, version = version + 1 where id = :id")
            .param("s", status.name)
            .param("r", if (status == RecommendationStatus.FAILED) order.rejectionReason else null)
            .param("o", order.id)
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        record(id, decision, mapOf("orderId" to order.id, "orderStatus" to order.status, "quantity" to qty, "limitPrice" to limit, "marketPrice" to price, "deviationPercent" to Decimals.percent(deviation)))
        audit.record(
            AuditCategory.RECOMMENDATION,
            "RECOMMENDATION_$decision",
            if (status == RecommendationStatus.FAILED) AuditOutcome.BLOCKED else AuditOutcome.SUCCESS,
            "Recommendation",
            id,
            mapOf("orderId" to order.id, "riskEvaluationId" to result.risk.id, "modified" to modified),
        )
        return DecisionResult(get(id), order.id, order.status.name, if (status == RecommendationStatus.FAILED) "Order rejected by risk: ${order.rejectionReason}" else "Paper order ${order.id} created")
    }

    /** Only risk-reducing modifications are permitted: smaller quantity, or a more conservative limit price. */
    private fun applyModification(
        r: Recommendation,
        m: Modification?,
        minQty: BigDecimal,
        increment: BigDecimal,
    ): Triple<BigDecimal, BigDecimal?, Boolean> {
        if (m == null || (m.quantity == null && m.limitPrice == null)) return Triple(r.quantity, r.limitPrice, false)
        val qty = m.quantity ?: r.quantity
        if (qty > r.quantity) throw Problems.unprocessable("modification-not-permitted", "Quantity can only be reduced (max ${r.quantity.stripTrailingZeros().toPlainString()})")
        if (qty < minQty || qty.remainder(increment).signum() != 0) throw Problems.unprocessable("modification-not-permitted", "Quantity must be at least ${minQty.toPlainString()} in steps of ${increment.toPlainString()}")
        val limit = m.limitPrice ?: r.limitPrice
        if (m.limitPrice != null) {
            val buys = r.side.buys
            val bound = r.limitPrice ?: r.referencePrice
            val conservative = if (buys) m.limitPrice <= bound else m.limitPrice >= bound
            val dev =
                m.limitPrice
                    .subtract(r.referencePrice)
                    .abs()
                    .multiply(Decimals.HUNDRED)
                    .divide(r.referencePrice, Decimals.MC)
            if (!conservative || dev > MAX_LIMIT_OFFSET_PERCENT) {
                throw Problems.unprocessable("modification-not-permitted", "Limit price must be at or ${if (buys) "below" else "above"} ${bound.toPlainString()} and within ${MAX_LIMIT_OFFSET_PERCENT}% of the reference price")
            }
        }
        return Triple(qty, limit, true)
    }

    @Transactional
    fun decline(
        id: UUID,
        reason: String?,
    ): Recommendation {
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}")
        finalize(r, RecommendationStatus.DECLINED, "DECLINE", reason ?: "Declined by owner")
        return get(id)
    }

    @Transactional
    fun snooze(
        id: UUID,
        minutes: Long,
    ): Recommendation {
        if (minutes !in 1..240) throw Problems.badRequest("invalid-snooze", "minutes must be 1-240")
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}")
        // Snoozing hides the item but never extends its expiry.
        val until = minOf(marketClock.now().plusSeconds(minutes * 60), r.expiresAt)
        jdbc
            .sql("update recommendations set snoozed_until = :u, updated_at = :now, version = version + 1 where id = :id")
            .param("u", ts(until))
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        record(id, "SNOOZE", mapOf("until" to until))
        return get(id)
    }

    /** Pauses the strategy and declines this recommendation (FR-063). */
    @Transactional
    fun pauseStrategy(
        id: UUID,
        deactivate: (UUID, String) -> Unit,
    ): Recommendation {
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}")
        deactivate(r.strategyId, "Paused from recommendation ${r.id}")
        finalize(r, RecommendationStatus.DECLINED, "PAUSE_STRATEGY", "Strategy paused by owner")
        return get(id)
    }

    /** Creates a recommendation from a signal; supersedes older pending ones for the same strategy and instrument. */
    @Transactional
    fun create(
        signalId: UUID,
        allowed: Boolean,
        riskEvaluationId: UUID,
        blockReasons: List<String>,
        maxDeviation: BigDecimal,
    ): Recommendation {
        val sig =
            jdbc
                .sql("select * from signals where id = :id")
                .param("id", signalId)
                .query { rs, _ ->
                    mapOf(
                        "strategy" to rs.uuid("strategy_id"),
                        "portfolio" to rs.uuid("portfolio_id"),
                        "instrument" to rs.uuid("instrument_id"),
                        "side" to rs.getString("side"),
                        "qty" to rs.getBigDecimal("quantity"),
                        "type" to rs.getString("order_type"),
                        "limit" to rs.getBigDecimal("limit_price"),
                        "ref" to rs.getBigDecimal("reference_price"),
                        "expires" to rs.instant("expires_at"),
                    )
                }.single()
        val superseded =
            jdbc
                .sql("update recommendations set status = 'SUPERSEDED', status_reason = :r, decided_at = :now, updated_at = :now, version = version + 1 where strategy_id = :s and instrument_id = :i and status = 'PENDING' returning id")
                .param("r", "Superseded by signal $signalId")
                .param("now", ts(clock.instant()))
                .param("s", sig["strategy"])
                .param("i", sig["instrument"])
                .query(UUID::class.java)
                .list()
        superseded.forEach { record(it, "SUPERSEDE", mapOf("bySignal" to signalId)) }
        val id = UUID.randomUUID()
        val status = if (allowed) RecommendationStatus.PENDING else RecommendationStatus.BLOCKED
        jdbc
            .sql(
                """
                insert into recommendations(id, signal_id, strategy_id, portfolio_id, instrument_id, status, status_reason, side, quantity, order_type, limit_price, reference_price,
                  max_deviation_percent, expires_at, risk_evaluation_id, created_at, updated_at)
                values (:id, :sig, :s, :p, :i, :st, :r, :side, :q, :t, :l, :ref, :dev, :exp, :risk, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("sig", signalId)
            .param("s", sig["strategy"])
            .param("p", sig["portfolio"])
            .param("i", sig["instrument"])
            .param("st", status.name)
            .param("r", if (allowed) null else blockReasons.joinToString("; ").take(1000))
            .param("side", sig["side"])
            .param("q", sig["qty"])
            .param("t", sig["type"])
            .param("l", sig["limit"])
            .param("ref", sig["ref"])
            .param("dev", maxDeviation)
            .param("exp", ts(sig["expires"] as Instant))
            .param("risk", riskEvaluationId)
            .param("now", ts(clock.instant()))
            .update()
        record(id, if (allowed) "CREATE" else "BLOCK", mapOf("signalId" to signalId, "riskEvaluationId" to riskEvaluationId, "reasons" to blockReasons))
        val r = get(id)
        audit.record(AuditCategory.RECOMMENDATION, if (allowed) "RECOMMENDATION_CREATED" else "RECOMMENDATION_BLOCKED", if (allowed) AuditOutcome.SUCCESS else AuditOutcome.BLOCKED, "Recommendation", id, mapOf("signalId" to signalId, "reasons" to blockReasons))
        if (allowed) {
            notifications.notify(
                NotificationCategory.RECOMMENDATION,
                Severity.INFO,
                "Recommendation: ${r.side} ${r.quantity.stripTrailingZeros().toPlainString()} ${r.symbol}",
                "${r.strategyName}: ${r.rationale} Expires ${r.expiresAt}. Open the recommendation to accept, modify, decline or snooze.",
                "Recommendation",
                id,
                "rec:$id",
            )
        } else {
            notifications.notify(
                NotificationCategory.RISK_EVENT,
                Severity.WARNING,
                "Recommendation blocked by risk: ${r.symbol}",
                "Blocked by: ${blockReasons.joinToString("; ").take(500)}",
                "Recommendation",
                id,
                "rec-blocked:$id",
            )
        }
        return r
    }

    /** Expires pending recommendations whose expiry has passed (FR-066). */
    @Transactional
    fun expireDue(): Int {
        val due =
            jdbc
                .sql("select id from recommendations where status = 'PENDING' and expires_at <= :now for update skip locked")
                .param("now", ts(marketClock.now()))
                .query(UUID::class.java)
                .list()
        due.forEach { id ->
            val r = get(id)
            finalize(r, RecommendationStatus.EXPIRED, "EXPIRE", "Expired at ${r.expiresAt}")
            notifications.notify(NotificationCategory.RECOMMENDATION_EXPIRY, Severity.INFO, "Recommendation expired: ${r.symbol}", "${r.side} ${r.symbol} from ${r.strategyName} expired without a decision.", "Recommendation", id, "rec-expired:$id")
        }
        return due.size
    }

    /** Declines all pending recommendations of a strategy (used when it is paused or suspended). */
    @Transactional
    fun closePendingForStrategy(
        strategyId: UUID,
        reason: String,
    ) {
        jdbc
            .sql("select id from recommendations where strategy_id = :s and status = 'PENDING' for update")
            .param("s", strategyId)
            .query(UUID::class.java)
            .list()
            .forEach { finalize(get(it), RecommendationStatus.DECLINED, "DECLINE", reason) }
    }

    private fun finalize(
        r: Recommendation,
        status: RecommendationStatus,
        decision: String,
        reason: String,
    ) {
        jdbc
            .sql("update recommendations set status = :s, status_reason = :r, decided_at = :now, updated_at = :now, version = version + 1 where id = :id and status = 'PENDING'")
            .param("s", status.name)
            .param("r", reason.take(1000))
            .param("now", ts(clock.instant()))
            .param("id", r.id)
            .update()
        record(r.id, decision, mapOf("reason" to reason))
        audit.record(AuditCategory.RECOMMENDATION, "RECOMMENDATION_$decision", entityType = "Recommendation", entityId = r.id, details = mapOf("reason" to reason))
    }

    private fun finalizeIndependently(
        r: Recommendation,
        status: RecommendationStatus,
        decision: String,
        reason: String,
    ) {
        independent.executeWithoutResult {
            jdbc
                .sql("update recommendations set status = :s, status_reason = :r, decided_at = :now, updated_at = :now, version = version + 1 where id = :id and status = 'PENDING'")
                .param("s", status.name)
                .param("r", reason.take(1000))
                .param("now", ts(clock.instant()))
                .param("id", r.id)
                .update()
            record(r.id, decision, mapOf("reason" to reason))
        }
        audit.recordIndependently(AuditCategory.RECOMMENDATION, "RECOMMENDATION_$decision", AuditOutcome.FAILURE, "Recommendation", r.id, mapOf("reason" to reason))
    }

    private fun record(
        id: UUID,
        decision: String,
        details: Map<String, Any?>,
    ) {
        val actor = CurrentOwner.orNull()?.currentActor() ?: "SYSTEM"
        jdbc
            .sql("insert into recommendation_decisions(id, recommendation_id, decision, actor, details, at, market_time) values (:id, :r, :d, :a, cast(:det as jsonb), :now, :mt)")
            .param("id", UUID.randomUUID())
            .param("r", id)
            .param("d", decision)
            .param("a", actor)
            .param("det", mapper.writeValueAsString(details))
            .param("now", ts(clock.instant()))
            .param("mt", ts(marketClock.now()))
            .update()
    }

    fun decisions(id: UUID): List<Map<String, Any?>> =
        jdbc
            .sql("select * from recommendation_decisions where recommendation_id = :r order by at")
            .param("r", id)
            .query { rs, _ ->
                mapOf("decision" to rs.getString("decision"), "actor" to rs.getString("actor"), "details" to mapper.readTree(rs.getString("details")), "at" to rs.instant("at"), "marketTime" to rs.instant("market_time"))
            }.list()

    fun list(
        status: String?,
        strategyId: UUID?,
        cursor: String?,
        limit: Int?,
    ): PageResponse<Recommendation> {
        val c = Cursor.decode(cursor)
        val l = Paging.limit(limit)
        val rows =
            jdbc
                .sql(
                    """
                    $VIEW where (cast(:st as text) is null or r.status = :st) and (cast(:s as uuid) is null or r.strategy_id = :s)
                      and (cast(:ca as timestamptz) is null or (r.created_at, r.id) < (cast(:ca as timestamptz), cast(:cid as uuid)))
                    order by r.created_at desc, r.id desc limit :l
                    """.trimIndent(),
                ).param("st", status)
                .param("s", strategyId)
                .param("ca", ts(c?.at))
                .param("cid", c?.id)
                .param("l", l + 1)
                .query { rs, _ -> map(rs) }
                .list()
        return Paging.page(rows, l) { Cursor(it.createdAt, it.id.toString()) }
    }

    fun get(id: UUID): Recommendation =
        jdbc
            .sql("$VIEW where r.id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Recommendation", id) }

    private fun lock(id: UUID): Recommendation {
        jdbc
            .sql("select id from recommendations where id = :id for update")
            .param("id", id)
            .query(UUID::class.java)
            .optional()
            .orElseThrow { Problems.notFound("Recommendation", id) }
        return get(id)
    }

    private fun map(rs: java.sql.ResultSet) =
        Recommendation(
            rs.uuid("id"),
            rs.uuid("signal_id"),
            rs.uuid("strategy_id"),
            rs.getString("strategy_name"),
            rs.uuid("portfolio_id"),
            rs.uuid("instrument_id"),
            rs.getString("symbol"),
            RecommendationStatus.valueOf(rs.getString("status")),
            rs.getString("status_reason"),
            OrderSide.valueOf(rs.getString("side")),
            rs.getBigDecimal("quantity"),
            OrderType.valueOf(rs.getString("order_type")),
            rs.getBigDecimal("limit_price"),
            rs.getBigDecimal("reference_price"),
            rs.getBigDecimal("max_deviation_percent"),
            rs.instant("expires_at"),
            rs.instantOrNull("snoozed_until"),
            rs.uuidOrNull("risk_evaluation_id"),
            rs.uuidOrNull("order_id"),
            rs.getString("rationale"),
            mapper.readValue(rs.getString("triggered_rules"), mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
            rs.getString("content_hash"),
            rs.uuid("market_snapshot_id"),
            rs.instant("created_at"),
            rs.instantOrNull("decided_at"),
            rs.getLong("version"),
        )

    companion object {
        const val PURPOSE = "recommendation-accept"
        const val DEVIATION_QUOTE_AGE = 120L
        val MAX_LIMIT_OFFSET_PERCENT = BigDecimal("10")
        const val DISCLAIMER =
            "Paper trading only: accepting creates a SIMULATED order. AI or strategy confidence is informational; deterministic risk rules decide whether an order may be created."
        private const val VIEW =
            """
            select r.*, s.name as strategy_name, i.symbol, g.rationale, g.triggered_rules, g.content_hash, g.market_snapshot_id
            from recommendations r join strategies s on s.id = r.strategy_id join instruments i on i.id = r.instrument_id join signals g on g.id = r.signal_id
            """
    }
}
