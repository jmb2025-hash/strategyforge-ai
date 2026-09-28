package app.strategyforge.signals

import app.strategyforge.autonomy.ActivationMode
import app.strategyforge.autonomy.ActivationService
import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.Problems
import app.strategyforge.execution.OrderLinks
import app.strategyforge.execution.OrderRequest
import app.strategyforge.execution.OrderService
import app.strategyforge.execution.OrderSide
import app.strategyforge.execution.OrderType
import app.strategyforge.execution.TimeInForce
import app.strategyforge.identity.CurrentOwner
import app.strategyforge.identity.RecentAuth
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.risk.OrderSource
import app.strategyforge.strategy.StrategyStatus
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
@Service
class EmergencyService(
    private val jdbc: JdbcClient,
    private val orders: OrderService,
    private val portfolios: PortfolioService,
    private val activations: ActivationService,
    private val facade: StrategyActivationFacade,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun state(): EmergencyState =
        jdbc
            .sql("select * from emergency_state")
            .query { rs, _ ->
                EmergencyState(rs.getBoolean("pause_all"), rs.getBoolean("prevent_new_positions"), rs.getString("reason"), rs.instant("updated_at"), rs.getString("updated_by"), rs.getLong("version"))
            }.single()

    @Transactional
    fun pauseAll(req: ToggleRequest): EmergencyResult = toggle("pause_all", "PAUSE_ALL", req)

    @Transactional
    fun preventNewPositions(req: ToggleRequest): EmergencyResult = toggle("prevent_new_positions", "PREVENT_NEW_POSITIONS", req)

    private fun toggle(
        column: String,
        action: String,
        req: ToggleRequest,
    ): EmergencyResult {
        jdbc.sql("select * from emergency_state for update").query { _, _ -> 1 }.single()
        if (!req.enabled) RecentAuth.require(clock.instant(), "release-${action.lowercase()}")
        val reason = req.reason?.takeIf { it.isNotBlank() }?.take(300) ?: if (req.enabled) "$action engaged by owner" else "$action released by owner"
        jdbc
            .sql("update emergency_state set $column = :v, reason = :r, updated_at = :now, updated_by = :by, version = version + 1")
            .param("v", req.enabled)
            .param("r", reason)
            .param("now", ts(clock.instant()))
            .param("by", runCatching { CurrentOwner.get().currentActor() }.getOrDefault("system"))
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

    @Transactional
    fun cancelPending(reason: String?): EmergencyResult {
        val text = reason?.takeIf { it.isNotBlank() }?.take(300) ?: "Cancel Pending Orders emergency control"
        val ids = orders.cancelAllOpen(text)
        audit.record(AuditCategory.EMERGENCY, "CANCEL_PENDING_ORDERS", details = mapOf("orders" to ids, "reason" to text))
        notifications.notify(NotificationCategory.SYSTEM_HEALTH, Severity.CRITICAL, "Pending paper orders cancelled", "${ids.size} open simulated order(s) cancelled.", "Emergency", null, "emergency:cancel:${clock.millis()}")
        return EmergencyResult("CANCEL_PENDING_ORDERS", state(), ids, "${ids.size} order(s) cancelled")
    }

    /** Stops every autonomous strategy; Recommendation Mode strategies continue. */
    @Transactional
    fun disableAutonomous(reason: String?): EmergencyResult {
        val text = reason?.takeIf { it.isNotBlank() }?.take(300) ?: "Disable Autonomous Mode emergency control"
        val affected = activations.activeAll().filter { it.mode == ActivationMode.AUTONOMOUS }.map { it.strategyId }
        affected.forEach { activations.deactivate(it, text, StrategyStatus.PAUSED) }
        audit.record(AuditCategory.EMERGENCY, "DISABLE_AUTONOMOUS_MODE", details = mapOf("strategies" to affected, "reason" to text))
        notifications.notify(NotificationCategory.SYSTEM_HEALTH, Severity.CRITICAL, "Autonomous mode disabled", "${affected.size} autonomous strateg(ies) paused.", "Emergency", null, "emergency:disable-auto:${clock.millis()}")
        return EmergencyResult("DISABLE_AUTONOMOUS_MODE", state(), affected, "${affected.size} strateg(ies) paused")
    }

    /** Cancels open orders then submits simulated market orders flattening every position. */
    @Transactional
    fun closeAll(req: CloseAllRequest): EmergencyResult {
        RecentAuth.require(clock.instant(), "close-all-simulated-positions")
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

@RestController
@RequestMapping("/v1/emergency")
@Tag(name = "Emergency")
class EmergencyController(
    private val emergency: EmergencyService,
    private val idempotency: IdempotencyService,
) {
    @GetMapping
    fun state() = emergency.state()

    @PostMapping("/pause-all")
    fun pauseAll(
        @RequestBody req: ToggleRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("emergency-pause-all", key, req) { emergency.pauseAll(req) }

    @PostMapping("/prevent-new-positions")
    fun preventNew(
        @RequestBody req: ToggleRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("emergency-prevent-new", key, req) { emergency.preventNewPositions(req) }

    @PostMapping("/cancel-pending-orders")
    fun cancelPending(
        @RequestBody(required = false) req: DeclineRequest?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("emergency-cancel-pending", key, req) { emergency.cancelPending(req?.reason) }

    @PostMapping("/disable-autonomous")
    fun disableAutonomous(
        @RequestBody(required = false) req: DeclineRequest?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("emergency-disable-autonomous", key, req) { emergency.disableAutonomous(req?.reason) }

    @PostMapping("/close-all-simulated-positions")
    fun closeAll(
        @RequestBody req: CloseAllRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("emergency-close-all", key, req) { emergency.closeAll(req) }
}
