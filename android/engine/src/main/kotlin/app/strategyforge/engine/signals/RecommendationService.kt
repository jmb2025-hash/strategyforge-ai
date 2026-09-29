package app.strategyforge.engine.signals

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.execution.OrderLinks
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderService
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.risk.OrderSource
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
    val decisions: List<Map<String, Any?>>,
    val disclaimer: String,
)

data class Modification(
    val quantity: BigDecimal? = null,
    val limitPrice: BigDecimal? = null,
)

data class AcceptRequest(
    val modification: Modification? = null,
)

/** A rejection whose record must be kept: it commits first, then the error is raised. */
private class CommittedRejection(
    val problem: EngineException,
) : RuntimeException(problem.message)

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
 * Recommendation lifecycle (section 8, FR-062..FR-066). Acceptance happens only inside the
 * unlocked app (a notification can open it but never accept); it re-checks expiry and price
 * deviation, and creates the paper order, its risk evaluation, cash reservation and audit in one
 * transaction. The unique
 * recommendation_id on paper_orders guarantees at most one order per recommendation (FR-065).
 */
class RecommendationService(
    private val db: Db,
    private val orders: OrderService,
    private val market: MarketDataService,
    private val instruments: InstrumentService,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val clock: Clock,
    private val marketClock: MarketClock,
) {
    private val mapper = JacksonCanonical.mapper

    fun detail(id: UUID): RecommendationDetail = RecommendationDetail(get(id), decisions(id), DISCLAIMER)

    /**
     * Expiry and price-deviation failures are recorded and committed, and the error is raised only
     * after the commit; any other failure rolls everything back.
     */
    fun accept(
        id: UUID,
        req: AcceptRequest,
    ): DecisionResult {
        var rejection: EngineException? = null
        val result =
            db.tx {
                try {
                    acceptLocked(id, req)
                } catch (e: CommittedRejection) {
                    rejection = e.problem
                    null
                }
            }
        rejection?.let { throw it }
        return result!!
    }

    private fun acceptLocked(
        id: UUID,
        req: AcceptRequest,
    ): DecisionResult {
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) {
            throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}", mapOf("status" to r.status, "orderId" to r.orderId))
        }
        val now = marketClock.now()
        if (!now.isBefore(r.expiresAt)) {
            finalize(r, RecommendationStatus.EXPIRED, "EXPIRE", "Expired at ${r.expiresAt} before acceptance", AuditOutcome.FAILURE)
            throw CommittedRejection(Problems.conflict("recommendation-expired", "The recommendation expired at ${r.expiresAt}"))
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
            val reason = "Price moved ${Decimals.percent(deviation).stripTrailingZeros().toPlainString()}% from ${r.referencePrice.toPlainString()} (limit ${r.maxDeviationPercent.toPlainString()}%)"
            finalize(r, RecommendationStatus.FAILED, "FAIL", reason, AuditOutcome.FAILURE)
            throw CommittedRejection(
                Problems.conflict("price-deviation", "The market price moved ${deviation.setScale(2, java.math.RoundingMode.HALF_EVEN)}% since the recommendation; limit ${r.maxDeviationPercent.toPlainString()}%"),
            )
        }
        val (qty, limit, modified) = applyModification(r, req.modification, instrument.minQuantity, instrument.quantityIncrement)
        val signal =
            db
                .sql("select version_id from signals where id = :s")
                .param("s", r.signalId)
                .single { it.uuid("version_id") }
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
        db
            .sql("update recommendations set status = :s, status_reason = :r, order_id = :o, decided_at = :now, updated_at = :now, version = version + 1 where id = :id")
            .param("s", status.name)
            .param("r", if (status == RecommendationStatus.FAILED) order.rejectionReason else null)
            .param("o", order.id)
            .param("now", (clock.instant()))
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

    fun decline(
        id: UUID,
        reason: String?,
    ): Recommendation {
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}")
        finalize(r, RecommendationStatus.DECLINED, "DECLINE", reason ?: "Declined by owner")
        return get(id)
    }

    fun snooze(
        id: UUID,
        minutes: Long,
    ): Recommendation {
        if (minutes !in 1..240) throw Problems.badRequest("invalid-snooze", "minutes must be 1-240")
        val r = lock(id)
        if (r.status != RecommendationStatus.PENDING) throw Problems.conflict("recommendation-not-pending", "Recommendation is ${r.status}")
        // Snoozing hides the item but never extends its expiry.
        val until = minOf(marketClock.now().plusSeconds(minutes * 60), r.expiresAt)
        db
            .sql("update recommendations set snoozed_until = :u, updated_at = :now, version = version + 1 where id = :id")
            .param("u", (until))
            .param("now", (clock.instant()))
            .param("id", id)
            .update()
        record(id, "SNOOZE", mapOf("until" to until))
        return get(id)
    }

    /** Pauses the strategy and declines this recommendation (FR-063). */
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
    fun create(
        signalId: UUID,
        allowed: Boolean,
        riskEvaluationId: UUID,
        blockReasons: List<String>,
        maxDeviation: BigDecimal,
    ): Recommendation {
        val sig =
            db
                .sql("select * from signals where id = :id")
                .param("id", signalId)
                .single { rs ->
                    mapOf(
                        "strategy" to rs.uuid("strategy_id"),
                        "portfolio" to rs.uuid("portfolio_id"),
                        "instrument" to rs.uuid("instrument_id"),
                        "side" to rs.string("side"),
                        "qty" to rs.dec("quantity"),
                        "type" to rs.string("order_type"),
                        "limit" to rs.decOrNull("limit_price"),
                        "ref" to rs.dec("reference_price"),
                        "expires" to rs.instant("expires_at"),
                    )
                }
        val superseded =
            db
                .sql("select id from recommendations where strategy_id = :s and instrument_id = :i and status = 'PENDING'")
                .param("s", sig["strategy"])
                .param("i", sig["instrument"])
                .list { it.uuid("id") }
        superseded.forEach { rid ->
            db
                .sql("update recommendations set status = 'SUPERSEDED', status_reason = :r, decided_at = :now, updated_at = :now, version = version + 1 where id = :id and status = 'PENDING'")
                .param("r", "Superseded by signal $signalId")
                .param("now", clock.instant())
                .param("id", rid)
                .update()
        }
        superseded.forEach { record(it, "SUPERSEDE", mapOf("bySignal" to signalId)) }
        val id = UUID.randomUUID()
        val status = if (allowed) RecommendationStatus.PENDING else RecommendationStatus.BLOCKED
        db
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
            .param("exp", (sig["expires"] as Instant))
            .param("risk", riskEvaluationId)
            .param("now", (clock.instant()))
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
    fun expireDue(): Int {
        val due =
            db
                .sql("select id from recommendations where status = 'PENDING' and expires_at <= :now")
                .param("now", (marketClock.now()))
                .list { it.uuid("id") }
        due.forEach { id ->
            val r = get(id)
            finalize(r, RecommendationStatus.EXPIRED, "EXPIRE", "Expired at ${r.expiresAt}")
            notifications.notify(NotificationCategory.RECOMMENDATION_EXPIRY, Severity.INFO, "Recommendation expired: ${r.symbol}", "${r.side} ${r.symbol} from ${r.strategyName} expired without a decision.", "Recommendation", id, "rec-expired:$id")
        }
        return due.size
    }

    /** Declines all pending recommendations of a strategy (used when it is paused or suspended). */
    fun closePendingForStrategy(
        strategyId: UUID,
        reason: String,
    ) {
        db
            .sql("select id from recommendations where strategy_id = :s and status = 'PENDING'")
            .param("s", strategyId)
            .list { it.uuid("id") }
            .forEach { finalize(get(it), RecommendationStatus.DECLINED, "DECLINE", reason) }
    }

    private fun finalize(
        r: Recommendation,
        status: RecommendationStatus,
        decision: String,
        reason: String,
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
    ) {
        val changed =
            db
                .sql("update recommendations set status = :s, status_reason = :r, decided_at = :now, updated_at = :now, version = version + 1 where id = :id and status = 'PENDING'")
                .param("s", status.name)
                .param("r", reason.take(1000))
                .param("now", (clock.instant()))
                .param("id", r.id)
                .update()
        if (changed == 0) return // already decided (e.g. closed by a strategy pause in the same transaction)
        record(r.id, decision, mapOf("reason" to reason))
        audit.record(AuditCategory.RECOMMENDATION, "RECOMMENDATION_$decision", outcome, "Recommendation", r.id, mapOf("reason" to reason))
    }

    private fun record(
        id: UUID,
        decision: String,
        details: Map<String, Any?>,
    ) {
        val actor = if (decision in OWNER_DECISIONS) AuditService.ACTOR_OWNER else AuditService.ACTOR_SYSTEM
        db
            .sql("insert into recommendation_decisions(id, recommendation_id, decision, actor, details, at, market_time) values (:id, :r, :d, :a, :det, :now, :mt)")
            .param("id", UUID.randomUUID())
            .param("r", id)
            .param("d", decision)
            .param("a", actor)
            .param("det", details.toJsonElement().toString())
            .param("now", (clock.instant()))
            .param("mt", (marketClock.now()))
            .update()
    }

    fun decisions(id: UUID): List<Map<String, Any?>> =
        db
            .sql("select * from recommendation_decisions where recommendation_id = :r order by at")
            .param("r", id)
            .list { rs ->
                mapOf("decision" to rs.string("decision"), "actor" to rs.string("actor"), "details" to mapper.readTree(rs.str("details")), "at" to rs.instant("at"), "marketTime" to rs.instant("market_time"))
            }

    /** Newest first; [before] continues a page from the last item's creation time. */
    fun list(
        status: String?,
        strategyId: UUID?,
        limit: Int,
        before: Instant? = null,
    ): List<Recommendation> =
        db
            .sql(
                """
                $VIEW where (:st is null or r.status = :st) and (:s is null or r.strategy_id = :s)
                  and (:before is null or r.created_at < :before)
                order by r.created_at desc, r.id desc limit :l
                """.trimIndent(),
            ).param("st", status)
            .param("s", strategyId)
            .param("before", before)
            .param("l", limit.coerceIn(1, 500))
            .list { rs -> map(rs) }

    fun get(id: UUID): Recommendation =
        db
            .sql("$VIEW where r.id = :id")
            .param("id", id)
            .firstOrNull { rs -> map(rs) } ?: throw Problems.notFound("Recommendation", id)

    private fun lock(id: UUID): Recommendation {
        db
            .sql("select id from recommendations where id = :id")
            .param("id", id)
            .firstOrNull { it.uuid("id") } ?: throw Problems.notFound("Recommendation", id)
        return get(id)
    }

    private fun map(rs: Row) =
        Recommendation(
            rs.uuid("id"),
            rs.uuid("signal_id"),
            rs.uuid("strategy_id"),
            rs.str("strategy_name"),
            rs.uuid("portfolio_id"),
            rs.uuid("instrument_id"),
            rs.str("symbol"),
            RecommendationStatus.valueOf(rs.str("status")),
            rs.string("status_reason"),
            OrderSide.valueOf(rs.str("side")),
            rs.dec("quantity"),
            OrderType.valueOf(rs.str("order_type")),
            rs.decOrNull("limit_price"),
            rs.dec("reference_price"),
            rs.dec("max_deviation_percent"),
            rs.instant("expires_at"),
            rs.instantOrNull("snoozed_until"),
            rs.uuidOrNull("risk_evaluation_id"),
            rs.uuidOrNull("order_id"),
            rs.str("rationale"),
            mapper.readTree(rs.str("triggered_rules")).map { it.asText() },
            rs.str("content_hash"),
            rs.uuid("market_snapshot_id"),
            rs.instant("created_at"),
            rs.instantOrNull("decided_at"),
            rs.long("version") ?: 0L,
        )

    companion object {
        private val OWNER_DECISIONS = setOf("ACCEPT", "MODIFY", "DECLINE", "SNOOZE")
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
