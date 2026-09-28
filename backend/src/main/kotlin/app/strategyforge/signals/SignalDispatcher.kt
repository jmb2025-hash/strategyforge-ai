package app.strategyforge.signals

import app.strategyforge.autonomy.Activation
import app.strategyforge.autonomy.ActivationMode
import app.strategyforge.autonomy.ActivationService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.execution.OrderLinks
import app.strategyforge.execution.OrderRequest
import app.strategyforge.execution.OrderService
import app.strategyforge.execution.TimeInForce
import app.strategyforge.market.ClockMode
import app.strategyforge.market.MarketClock
import app.strategyforge.market.MarketClockAdvanced
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.risk.OrderIntent
import app.strategyforge.risk.OrderSource
import app.strategyforge.risk.RiskEngine
import app.strategyforge.risk.RuleOutcome
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns a persisted signal into its outcome: a risk-evaluated recommendation (Recommendation Mode,
 * FR-062) or, for an authorized autonomous activation, a simulated order that exists only after the
 * authorization fingerprint still matches and deterministic risk checks pass (FR-072, FR-073).
 * Runs inside the caller's transaction.
 */
@Component
class SignalDispatcher(
    private val jdbc: JdbcClient,
    private val activations: ActivationService,
    private val risk: RiskEngine,
    private val recommendations: RecommendationService,
    private val orders: OrderService,
    private val portfolios: PortfolioService,
    private val notifications: NotificationService,
    private val events: ApplicationEventPublisher,
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
        if (conditions.isNotEmpty()) events.publishEvent(EvaluationBlocked(a.strategyId, a.id, a.mode, conditions, r.risk.reasons.joinToString("; ")))
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
        jdbc
            .sql("update signals set risk_evaluation_id = :r, disposition = :d where id = :id")
            .param("r", riskEvaluationId)
            .param("d", disposition)
            .param("id", signalId)
            .update()
    }
}

/** Live scheduling and replay-step wiring for evaluation (@Order 30) and recommendation expiry (@Order 40). */
@Component
class EvaluationScheduler(
    private val evaluation: EvaluationService,
    private val recommendations: RecommendationService,
    private val marketClock: MarketClock,
    private val props: StrategyForgeProperties,
) {
    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.evaluation-interval-ms:20000}", initialDelay = 15000)
    fun scheduled() {
        if (!props.scheduler.enabled || marketClock.mode() != ClockMode.LIVE) return
        CorrelationIdFilter.withCorrelation("eval") { evaluation.evaluateAll() }
    }

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.expiry-interval-ms:15000}", initialDelay = 15000)
    fun scheduledExpiry() {
        if (!props.scheduler.enabled || marketClock.mode() != ClockMode.LIVE) return
        CorrelationIdFilter.withCorrelation("expiry") { recommendations.expireDue() }
    }

    /** Replay mode: evaluation runs after ingestion, execution and maintenance. */
    @EventListener
    @Order(30)
    fun onReplayStep(
        @Suppress("UNUSED_PARAMETER") e: MarketClockAdvanced,
    ) {
        evaluation.evaluateAll()
    }

    @EventListener
    @Order(40)
    fun expireOnReplayStep(
        @Suppress("UNUSED_PARAMETER") e: MarketClockAdvanced,
    ) {
        recommendations.expireDue()
    }
}
