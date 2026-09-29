package app.strategyforge.engine.risk

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.market.MarketClock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Clock
import java.util.UUID

/** Builds a [RiskContext]; separated so failures to verify state are themselves a blocking outcome. */
fun interface RiskContextFactory {
    fun build(intent: OrderIntent): RiskContext
}

/**
 * Deterministic risk engine (section 11). AI output never reaches this engine except as an
 * order intent; confidence is not an input (FR-092). No verified state means no new trade:
 * UNVERIFIED blocks exactly like FAIL for risk-increasing orders, and any engine failure blocks.
 */
class RiskEngine(
    /** Rules in evaluation order. */
    private val rules: () -> List<RiskRule>,
    private val contextFactory: RiskContextFactory,
    private val db: Db,
    private val audit: AuditService,
    private val clock: Clock,
    private val marketClock: MarketClock,
) {
    private val log = EngineLog.of(javaClass)

    fun evaluate(intent: OrderIntent): RiskDecision {
        val started = System.nanoTime()
        val id = UUID.randomUUID()
        val increasing = intent.side.increasesRisk
        var inputs: Map<String, Any?> = emptyMap()
        val results =
            try {
                val ctx = contextFactory.build(intent)
                inputs = inputsOf(ctx)
                rules().map { rule ->
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
        val ruleIndex = rules().associateBy { it.name }
        val blocking =
            results
                .filter { r ->
                    val bad = r.outcome == RuleOutcome.FAIL || r.outcome == RuleOutcome.UNVERIFIED
                    bad && (increasing || r.rule == "RISK_ENGINE_AVAILABLE" || ruleIndex[r.rule]?.blocksReducing == true)
                }.map { it.rule }
                .distinct()
        val allowed = blocking.isEmpty()
        val durationMs = (System.nanoTime() - started) / 1_000_000
        db
            .sql(
                """
                insert into risk_evaluations(id, portfolio_id, instrument_id, strategy_id, source, intent, inputs, rule_results, decision, blocking_rules,
                  market_time, created_at, duration_ms)
                values (:id, :p, :i, :s, :src, :intent, :inputs, :rules, :d, :b, :mt, :now, :ms)
                """.trimIndent(),
            ).param("id", id)
            .param("p", intent.portfolioId)
            .param("i", intent.instrumentId)
            .param("s", intent.strategyId)
            .param("src", intent.source.name)
            .param("intent", EngineJson.encodeToString(OrderIntent.serializer(), intent))
            .param("inputs", inputs.toJsonElement().toString())
            .param("rules", EngineJson.encodeToString(ListSerializer(RuleResult.serializer()), results))
            .param("d", if (allowed) "ALLOW" else "BLOCK")
            .param("b", EngineJson.encodeToString(ListSerializer(String.serializer()), blocking))
            .param("mt", (marketClock.now()))
            .param("now", (clock.instant()))
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
