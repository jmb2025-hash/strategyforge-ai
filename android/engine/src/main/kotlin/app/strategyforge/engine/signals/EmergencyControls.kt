package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationService
import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.execution.OrderLinks
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderService
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.strategy.StrategyStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class EmergencyState(
    val pauseAll: Boolean,
    val preventNewPositions: Boolean,
    val reason: String?,
    val updatedAt: Instant,
    val updatedBy: String?,
    val version: Long,
)

data class ToggleRequest(
    val enabled: Boolean,
    val reason: String? = null,
)

data class CloseAllRequest(
    val confirmation: String?,
    val portfolioId: UUID? = null,
)

data class EmergencyResult(
    val action: String,
    val state: EmergencyState,
    val affected: List<UUID>,
    val detail: String,
)

/**
 * Emergency controls (FR-075, MS-15). Engaging a control is always allowed immediately; releasing
 * one loosens safety and requires recent authentication. Close All Simulated Positions is separate,
 * requires recent authentication and an explicit typed confirmation.
 */
class EmergencyService(
    private val db: Db,
    private val orders: OrderService,
    private val portfolios: PortfolioService,
    private val activations: ActivationService,
    private val facade: StrategyActivationFacade,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val clock: Clock,
    private val auth: RecentAuth,
) {
    fun state(): EmergencyState =
        db
            .sql("select * from emergency_state")
            .single { rs ->
                EmergencyState(rs.bool("pause_all"), rs.bool("prevent_new_positions"), rs.string("reason"), rs.instant("updated_at"), rs.string("updated_by"), rs.long("version") ?: 0L)
            }

    fun pauseAll(req: ToggleRequest): EmergencyResult = toggle("pause_all", "PAUSE_ALL", req)

    fun preventNewPositions(req: ToggleRequest): EmergencyResult = toggle("prevent_new_positions", "PREVENT_NEW_POSITIONS", req)

    private fun toggle(
        column: String,
        action: String,
        req: ToggleRequest,
    ): EmergencyResult {
        if (!req.enabled) auth.require("release ${action.replace('_', ' ').lowercase()}")
        return db.tx { applyToggle(column, action, req) }
    }

    private fun applyToggle(
        column: String,
        action: String,
        req: ToggleRequest,
    ): EmergencyResult {
        val reason = req.reason?.takeIf { it.isNotBlank() }?.take(300) ?: if (req.enabled) "$action engaged by owner" else "$action released by owner"
        db
            .sql("update emergency_state set $column = :v, reason = :r, updated_at = :now, updated_by = :by, version = version + 1")
            .param("v", req.enabled)
            .param("r", reason)
            .param("now", (clock.instant()))
            .param("by", AuditService.ACTOR_OWNER)
            .update()
        val paused = mutableListOf<UUID>()
        if (req.enabled && column == "pause_all") {
            // Pause All also pauses every active strategy so nothing resumes silently when released.
            activations.activeAll().forEach { a ->
                facade.deactivate(a.strategyId, "Pause All: $reason")
                paused += a.strategyId
            }
        }
        audit.record(AuditCategory.EMERGENCY, "${action}_${if (req.enabled) "ENGAGED" else "RELEASED"}", details = mapOf("reason" to reason, "pausedStrategies" to paused))
        notifications.notify(
            NotificationCategory.SYSTEM_HEALTH,
            Severity.CRITICAL,
            "${action.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} ${if (req.enabled) "engaged" else "released"}",
            reason,
            "Emergency",
            null,
            "emergency:$action:${state().version}",
        )
        return EmergencyResult(action, state(), paused, reason)
    }

    fun cancelPending(reason: String?): EmergencyResult {
        val text = reason?.takeIf { it.isNotBlank() }?.take(300) ?: "Cancel Pending Orders emergency control"
        val ids = orders.cancelAllOpen(text)
        audit.record(AuditCategory.EMERGENCY, "CANCEL_PENDING_ORDERS", details = mapOf("orders" to ids, "reason" to text))
        notifications.notify(NotificationCategory.SYSTEM_HEALTH, Severity.CRITICAL, "Pending paper orders cancelled", "${ids.size} open simulated order(s) cancelled.", "Emergency", null, "emergency:cancel:${clock.millis()}")
        return EmergencyResult("CANCEL_PENDING_ORDERS", state(), ids, "${ids.size} order(s) cancelled")
    }

    /** Stops every autonomous strategy; Recommendation Mode strategies continue. */
    fun disableAutonomous(reason: String?): EmergencyResult {
        val text = reason?.takeIf { it.isNotBlank() }?.take(300) ?: "Disable Autonomous Mode emergency control"
        val affected = activations.activeAll().filter { it.mode == ActivationMode.AUTONOMOUS }.map { it.strategyId }
        affected.forEach { activations.deactivate(it, text, StrategyStatus.PAUSED) }
        audit.record(AuditCategory.EMERGENCY, "DISABLE_AUTONOMOUS_MODE", details = mapOf("strategies" to affected, "reason" to text))
        notifications.notify(NotificationCategory.SYSTEM_HEALTH, Severity.CRITICAL, "Autonomous mode disabled", "${affected.size} autonomous strateg(ies) paused.", "Emergency", null, "emergency:disable-auto:${clock.millis()}")
        return EmergencyResult("DISABLE_AUTONOMOUS_MODE", state(), affected, "${affected.size} strateg(ies) paused")
    }

    /** Cancels open orders then submits simulated market orders flattening every position. */
    fun closeAll(req: CloseAllRequest): EmergencyResult {
        auth.require("close all simulated positions")
        if (req.confirmation != CONFIRMATION) {
            throw Problems.unprocessable("confirmation-required", "Type \"$CONFIRMATION\" to close all simulated positions", mapOf("confirmation" to CONFIRMATION))
        }
        val targets = if (req.portfolioId != null) listOf(portfolios.get(req.portfolioId)) else portfolios.list(false)
        val created = mutableListOf<UUID>()
        targets.forEach { p ->
            orders.cancelAllOpen("Close All Simulated Positions", p.id)
            portfolios.positionViews(p).filter { it.quantity.signum() != 0 }.forEach { pos ->
                val side = if (pos.quantity.signum() > 0) OrderSide.SELL else OrderSide.BUY_TO_COVER
                val r = orders.create(OrderRequest(p.id, pos.symbol, side, OrderType.MARKET, pos.quantity.abs(), timeInForce = TimeInForce.GTC), OrderSource.EMERGENCY_CLOSE, OrderLinks())
                created += r.order.id
            }
        }
        audit.record(AuditCategory.EMERGENCY, "CLOSE_ALL_SIMULATED_POSITIONS", details = mapOf("orders" to created, "portfolios" to targets.map { it.id }))
        notifications.notify(
            NotificationCategory.SYSTEM_HEALTH,
            Severity.CRITICAL,
            "Closing all simulated positions",
            "${created.size} simulated closing order(s) submitted. Equity positions fill when their market is open.",
            "Emergency",
            null,
            "emergency:close-all:${clock.millis()}",
        )
        return EmergencyResult("CLOSE_ALL_SIMULATED_POSITIONS", state(), created, "${created.size} closing order(s) submitted")
    }

    companion object {
        const val CONFIRMATION = "CLOSE ALL SIMULATED POSITIONS"
    }
}
