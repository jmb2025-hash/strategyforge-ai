package app.strategyforge.risk

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.ts
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.execution.OrderSide
import app.strategyforge.execution.OrderType
import app.strategyforge.execution.TimeInForce
import app.strategyforge.market.Instrument
import app.strategyforge.market.MarketClock
import app.strategyforge.market.data.QuoteVerification
import app.strategyforge.portfolio.Portfolio
import app.strategyforge.portfolio.PortfolioSummary
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class OrderSource { MANUAL, RECOMMENDATION, AUTONOMOUS, EMERGENCY_CLOSE, FORCED_COVER, SYSTEM }

data class OrderIntent(
    val portfolioId: UUID,
    val instrumentId: UUID,
    val side: OrderSide,
    val orderType: OrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal?,
    val stopPrice: BigDecimal?,
    val timeInForce: TimeInForce,
    val source: OrderSource,
    val strategyId: UUID? = null,
    val strategyVersionId: UUID? = null,
)

enum class RuleOutcome { PASS, FAIL, UNVERIFIED, NOT_APPLICABLE }

/** Level at which a limit was defined; the strictest applicable level wins (FR-091). */
enum class RiskLevel { SYSTEM, GLOBAL, PORTFOLIO, STRATEGY, ORDER }

data class RuleResult(
    val rule: String,
    val family: String,
    val outcome: RuleOutcome,
    val level: RiskLevel,
    val limit: String? = null,
    val actual: String? = null,
    val detail: String,
)

data class OpenOrderInfo(
    val id: UUID,
    val instrumentId: UUID,
    val side: OrderSide,
    val remaining: BigDecimal,
    val reservedRemaining: BigDecimal,
)

/** Everything a rule may consult. Built once per evaluation from verified state only. */
class RiskContext(
    val intent: OrderIntent,
    val portfolio: Portfolio,
    val summary: PortfolioSummary,
    val instrument: Instrument,
    val quote: QuoteVerification,
    val estimatedPrice: BigDecimal?,
    val openOrders: List<OpenOrderInfo>,
    val now: Instant,
    val attributes: MutableMap<String, Any?> = mutableMapOf(),
)

/**
 * A deterministic risk rule. [blocksReducing] marks rules that also apply to position-reducing
 * orders (integrity rules such as holdings). Any other rule only guards risk-increasing orders.
 */
interface RiskRule {
    val name: String
    val family: String
    val blocksReducing: Boolean get() = false

    fun evaluate(ctx: RiskContext): RuleResult

    fun pass(
        detail: String,
        level: RiskLevel = RiskLevel.PORTFOLIO,
        limit: Any? = null,
        actual: Any? = null,
    ) = RuleResult(name, family, RuleOutcome.PASS, level, limit?.toString(), actual?.toString(), detail)

    fun fail(
        detail: String,
        level: RiskLevel = RiskLevel.PORTFOLIO,
        limit: Any? = null,
        actual: Any? = null,
    ) = RuleResult(name, family, RuleOutcome.FAIL, level, limit?.toString(), actual?.toString(), detail)

    fun unverified(
        detail: String,
        level: RiskLevel = RiskLevel.SYSTEM,
    ) = RuleResult(name, family, RuleOutcome.UNVERIFIED, level, null, null, detail)

    fun na(detail: String = "Not applicable") = RuleResult(name, family, RuleOutcome.NOT_APPLICABLE, RiskLevel.ORDER, null, null, detail)
}

data class RiskDecision(
    val id: UUID,
    val allowed: Boolean,
    val results: List<RuleResult>,
    val blocking: List<String>,
) {
    val reasons: List<String> get() = results.filter { it.rule in blocking }.map { "${it.rule}: ${it.detail}" }
}

/** Builds a [RiskContext]; separated so failures to verify state are themselves a blocking outcome. */
fun interface RiskContextFactory {
    fun build(intent: OrderIntent): RiskContext
}

/**
 * Deterministic risk engine (section 11). AI output never reaches this engine except as an
 * order intent; confidence is not an input (FR-092). No verified state means no new trade:
 * UNVERIFIED blocks exactly like FAIL for risk-increasing orders, and any engine failure blocks.
 */
@Service
class RiskEngine(
    private val rules: ObjectProvider<RiskRule>,
    private val contextFactory: RiskContextFactory,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val audit: AuditService,
    private val clock: Clock,
    private val marketClock: MarketClock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun evaluate(intent: OrderIntent): RiskDecision {
        val started = System.nanoTime()
        val id = UUID.randomUUID()
        val increasing = intent.side.increasesRisk
        var inputs: Map<String, Any?> = emptyMap()
        val results =
            try {
                val ctx = contextFactory.build(intent)
                inputs = inputsOf(ctx)
                rules.orderedStream().toList().map { rule ->
                    try {
                        rule.evaluate(ctx)
                    } catch (e: Exception) {
                        log.error("Risk rule {} failed", rule.name, e)
                        RuleResult(rule.name, rule.family, RuleOutcome.UNVERIFIED, RiskLevel.SYSTEM, null, null, "Rule could not be evaluated (${e.javaClass.simpleName}); failing closed")
                    }
                }
            } catch (e: Exception) {
                log.error("Risk context could not be built", e)
                listOf(RuleResult("RISK_ENGINE_AVAILABLE", "SYSTEM_SAFETY", RuleOutcome.UNVERIFIED, RiskLevel.SYSTEM, null, null, "Risk state could not be verified (${e.javaClass.simpleName})"))
            }
        val ruleIndex = rules.orderedStream().toList().associateBy { it.name }
        val blocking =
            results
                .filter { r ->
                    val bad = r.outcome == RuleOutcome.FAIL || r.outcome == RuleOutcome.UNVERIFIED
                    bad && (increasing || r.rule == "RISK_ENGINE_AVAILABLE" || ruleIndex[r.rule]?.blocksReducing == true)
                }.map { it.rule }
                .distinct()
        val allowed = blocking.isEmpty()
        val durationMs = (System.nanoTime() - started) / 1_000_000
        jdbc
            .sql(
                """
                insert into risk_evaluations(id, portfolio_id, instrument_id, strategy_id, source, intent, inputs, rule_results, decision, blocking_rules,
                  market_time, created_at, correlation_id, duration_ms)
                values (:id, :p, :i, :s, :src, cast(:intent as jsonb), cast(:inputs as jsonb), cast(:rules as jsonb), :d, cast(:b as text[]), :mt, :now, :c, :ms)
                """.trimIndent(),
            ).param("id", id)
            .param("p", intent.portfolioId)
            .param("i", intent.instrumentId)
            .param("s", intent.strategyId)
            .param("src", intent.source.name)
            .param("intent", mapper.writeValueAsString(intent))
            .param("inputs", mapper.writeValueAsString(inputs))
            .param("rules", mapper.writeValueAsString(results))
            .param("d", if (allowed) "ALLOW" else "BLOCK")
            .param("b", "{" + blocking.joinToString(",") + "}")
            .param("mt", ts(marketClock.now()))
            .param("now", ts(clock.instant()))
            .param("c", CorrelationIdFilter.current())
            .param("ms", durationMs)
            .update()
        audit.record(
            AuditCategory.RISK,
            "RISK_EVALUATED",
            if (allowed) AuditOutcome.SUCCESS else AuditOutcome.BLOCKED,
            "RiskEvaluation",
            id,
            mapOf("portfolioId" to intent.portfolioId, "side" to intent.side, "source" to intent.source, "blocking" to blocking),
        )
        return RiskDecision(id, allowed, results, blocking)
    }

    private fun inputsOf(ctx: RiskContext): Map<String, Any?> =
        mapOf(
            "cash" to ctx.summary.cash,
            "buyingPower" to ctx.summary.buyingPower,
            "equity" to ctx.summary.equity,
            "quoteStatus" to ctx.quote.status,
            "quoteAgeSeconds" to ctx.quote.ageSeconds,
            "quoteTs" to ctx.quote.quote?.exchangeTs,
            "bid" to ctx.quote.quote?.bid,
            "ask" to ctx.quote.quote?.ask,
            "last" to ctx.quote.quote?.last,
            "estimatedPrice" to ctx.estimatedPrice,
            "openOrders" to ctx.openOrders.size,
            "reconciliation" to ctx.portfolio.reconciliationStatus,
            "marketTime" to ctx.now,
            "symbol" to ctx.instrument.symbol,
        ) + ctx.attributes
}
