package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationService
import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.execution.OrderFilled
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.portfolio.PortfolioChanged
import app.strategyforge.engine.portfolio.ReconciliationFailed
import app.strategyforge.engine.risk.RiskProfileChanged
import app.strategyforge.engine.risk.RiskProfileService
import app.strategyforge.engine.strategy.StrategyService
import app.strategyforge.engine.strategy.StrategyStatus
import java.math.BigDecimal
import java.util.UUID

/**
 * Automatic strategy safety reactions (FR-073, FR-094): repeated evaluation errors and consecutive
 * losses suspend a strategy; unverifiable state or a material change pauses autonomous execution.
 * Every reaction is audited and raises a critical notification; pending recommendations are closed.
 */
class StrategyHealthMonitor(
    private val db: Db,
    private val activations: ActivationService,
    private val strategies: StrategyService,
    private val recommendations: RecommendationService,
    private val profiles: RiskProfileService,
    private val notifications: NotificationService,
    private val audit: AuditService,
) {
    private val log = EngineLog.of(javaClass)

    fun onEvaluationFailed(e: EvaluationFailed) {
        val limit = profiles.effectiveFor(null, e.strategyId).maxConsecutiveErrors?.value ?: DEFAULT_MAX_ERRORS
        if (e.consecutiveFailures >= limit) {
            stop(e.strategyId, StrategyStatus.SUSPENDED, "Suspended after ${e.consecutiveFailures} consecutive evaluation errors (limit $limit)", "REPEATED_ERRORS")
        }
    }

    /** Autonomous mode fails closed: stale data, provider loss or reconciliation failure pause autonomy. */
    fun onEvaluationBlocked(e: EvaluationBlocked) {
        if (e.mode == ActivationMode.AUTONOMOUS) {
            stop(e.strategyId, StrategyStatus.PAUSED, "Autonomous mode paused: ${e.conditions.sorted().joinToString()}", "AUTONOMY_AUTO_PAUSE")
        }
    }

    fun onReauthorizationRequired(e: ReauthorizationRequired) {
        stop(e.strategyId, StrategyStatus.PAUSED, "Autonomous mode paused until re-authorized: ${e.reason}", "REAUTHORIZATION_REQUIRED")
    }

    /** Any profile change re-checks every autonomous authorization fingerprint (FR-072). */
    fun onRiskProfileChanged(e: RiskProfileChanged) {
        activations.activeAll().filter { it.mode == ActivationMode.AUTONOMOUS }.forEach { a ->
            if (activations.fingerprint(a.strategyId, a.versionId, a.portfolioId, a.allocationPercent) != a.fingerprint) {
                stop(a.strategyId, StrategyStatus.PAUSED, "Autonomous mode paused until re-authorized: ${e.scope.lowercase()} risk profile changed", "REAUTHORIZATION_REQUIRED")
            }
        }
    }

    /** Portfolio changes (cost model, shorting, archive) are material to autonomous authorization (FR-072). */
    fun onPortfolioChanged(e: PortfolioChanged) {
        activations.activeAll().filter { it.portfolioId == e.portfolioId }.forEach { a ->
            if (a.mode == ActivationMode.AUTONOMOUS || e.change == "ARCHIVED") {
                stop(a.strategyId, StrategyStatus.PAUSED, "Paused: portfolio ${e.change.lowercase().replace('_', ' ')} changed; re-authorization required", "REAUTHORIZATION_REQUIRED")
            }
        }
    }

    /** Reconciliation failure pauses every strategy on the portfolio (FR-074, fail closed). */
    fun onReconciliationFailed(e: ReconciliationFailed) {
        activations.activeAll().filter { it.portfolioId == e.portfolioId }.forEach { a ->
            stop(a.strategyId, StrategyStatus.PAUSED, "Paused: portfolio reconciliation failed", "RECONCILIATION_FAILURE")
        }
    }

    /** Runs inside the fill transaction; suspension commits atomically with the losing fill. */
    fun onOrderFilled(e: OrderFilled) {
        if (e.realizedPnl.signum() >= 0) return
        val strategyId =
            db
                .sql("select strategy_id from paper_orders where id = :id")
                .param("id", e.orderId)
                .firstOrNull { it.uuidOrNull("strategy_id") } ?: return
        val active = activations.active(strategyId) ?: return
        val limit = strategies.definition(active.versionId).risk.maximumConsecutiveLosses ?: return
        val pnls =
            db
                .sql(
                    """
                    select e.realized_pnl from paper_executions e join paper_orders o on o.id = e.order_id
                    where o.strategy_id = :s and e.side in ('SELL', 'BUY_TO_COVER') order by e.executed_at desc, e.fill_seq desc limit 100
                    """.trimIndent(),
                ).param("s", strategyId)
                .list { it.dec("realized_pnl") }
        val streak = pnls.takeWhile { it.signum() < 0 }.size
        if (streak >= limit) {
            suspendInCurrentTx(strategyId, StrategyStatus.SUSPENDED, "Suspended after $streak consecutive losing trades (limit $limit)", "CONSECUTIVE_LOSSES")
        }
    }

    private fun stop(
        strategyId: UUID,
        target: StrategyStatus,
        reason: String,
        code: String,
    ) {
        try {
            db.tx { suspendInCurrentTx(strategyId, target, reason, code) }
        } catch (ex: RuntimeException) {
            log.error("Could not stop strategy {} ({})", strategyId, code, ex)
            audit.record(AuditCategory.FAILURE, "STRATEGY_STOP_FAILED", AuditOutcome.FAILURE, "Strategy", strategyId, mapOf("code" to code, "error" to ex.javaClass.simpleName))
            throw ex
        }
    }

    private fun suspendInCurrentTx(
        strategyId: UUID,
        target: StrategyStatus,
        reason: String,
        code: String,
    ) {
        val s = strategies.lock(strategyId)
        if (!s.status.active) return
        activations.deactivate(strategyId, reason, target)
        recommendations.closePendingForStrategy(strategyId, reason)
        audit.record(AuditCategory.AUTONOMY, "STRATEGY_$code", AuditOutcome.BLOCKED, "Strategy", strategyId, mapOf("reason" to reason, "status" to target))
        notifications.notify(
            NotificationCategory.STRATEGY_SUSPENSION,
            Severity.CRITICAL,
            "${s.name} ${target.name.lowercase()}",
            "$reason. No further signals will be generated until you review it.",
            "Strategy",
            strategyId,
            "strategy-stop:$strategyId:$code:${s.version}",
        )
    }

    companion object {
        const val DEFAULT_MAX_ERRORS = 3
    }
}
