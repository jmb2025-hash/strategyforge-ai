package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.Activation
import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.execution.OrderLinks
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderService
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.ClockMode
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.risk.OrderIntent
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.risk.RiskEngine
import app.strategyforge.engine.risk.RuleOutcome
import java.util.UUID

/**
 * Turns a persisted signal into its outcome: a risk-evaluated recommendation (Recommendation Mode,
 * FR-062) or, for an authorized autonomous activation, a simulated order that exists only after the
 * authorization fingerprint still matches and deterministic risk checks pass (FR-072, FR-073).
 * Runs inside the caller's transaction.
 */
class SignalDispatcher(
    private val db: Db,
    private val activations: ActivationService,
    private val risk: RiskEngine,
    private val recommendations: RecommendationService,
    private val orders: OrderService,
    private val portfolios: PortfolioService,
    private val notifications: NotificationService,
    private val events: EngineEvents,
) {
    fun dispatch(
        a: Activation,
        signalId: UUID,
        d: SignalDraft,
    ) {
        if (a.mode == ActivationMode.AUTONOMOUS) executeAutonomously(a, signalId, d) else recommend(a, signalId, d)
    }

    private fun recommend(
        a: Activation,
        signalId: UUID,
        d: SignalDraft,
    ) {
        val decision =
            risk.evaluate(
                OrderIntent(a.portfolioId, d.instrument.id, d.side, d.orderType, d.quantity, d.limitPrice, null, TimeInForce.DAY, OrderSource.RECOMMENDATION, a.strategyId, a.versionId),
            )
        disposition(signalId, decision.id, if (decision.allowed) "RECOMMENDED" else "BLOCKED")
        val maxDev = portfolios.get(a.portfolioId).costModel.maxPriceDeviationPercent
        recommendations.create(signalId, decision.allowed, decision.id, decision.reasons, maxDev)
    }

    private fun executeAutonomously(
        a: Activation,
        signalId: UUID,
        d: SignalDraft,
    ) {
        val current = activations.fingerprint(a.strategyId, a.versionId, a.portfolioId, a.allocationPercent)
        if (current != a.fingerprint) {
            db.sql("update signals set disposition = 'BLOCKED' where id = :id").param("id", signalId).update()
            events.publish(ReauthorizationRequired(a.strategyId, a.id, "Material change since autonomy was authorized"))
            return
        }
        val r =
            orders.create(
                OrderRequest(a.portfolioId, d.instrument.symbol, d.side, d.orderType, d.quantity, d.limitPrice, null, TimeInForce.DAY),
                OrderSource.AUTONOMOUS,
                OrderLinks(a.strategyId, a.versionId, null, signalId),
            )
        disposition(signalId, r.risk.id, if (r.risk.allowed) "AUTO_EXECUTED" else "BLOCKED")
        if (r.risk.allowed) return
        // FR-074: loss/drawdown limits and an unavailable risk engine or unverifiable state stop autonomy.
        val unverified =
            r.risk.results
                .filter { it.rule in r.risk.blocking && it.outcome == RuleOutcome.UNVERIFIED }
                .map { it.rule }
        val conditions =
            buildSet {
                if ("LOSS_LIMITS" in r.risk.blocking) add("LOSS_OR_DRAWDOWN_LIMIT")
                if ("RISK_ENGINE_AVAILABLE" in unverified) add("RISK_ENGINE_UNAVAILABLE")
                if (unverified.any { it != "RISK_ENGINE_AVAILABLE" }) add("UNVERIFIED_RISK_STATE")
            }
        if (conditions.isNotEmpty()) events.publish(EvaluationBlocked(a.strategyId, a.id, a.mode, conditions, r.risk.reasons.joinToString("; ")))
        notifications.notify(
            NotificationCategory.RISK_EVENT,
            Severity.WARNING,
            "Autonomous order blocked: ${d.instrument.symbol}",
            "Risk blocked the simulated order: ${r.risk.reasons.joinToString("; ").take(500)}",
            "PaperOrder",
            r.order.id,
            "auto-blocked:${r.order.id}",
        )
    }

    private fun disposition(
        signalId: UUID,
        riskEvaluationId: UUID,
        disposition: String,
    ) {
        db
            .sql("update signals set risk_evaluation_id = :r, disposition = :d where id = :id")
            .param("r", riskEvaluationId)
            .param("d", disposition)
            .param("id", signalId)
            .update()
    }
}
