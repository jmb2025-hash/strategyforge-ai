package app.strategyforge.operations

import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * FR-111 operational health: strategy scheduler progress, push outbox queue depth and recent
 * error events. Each view is derived from durable state so it survives restarts.
 */
@Component
class OperationalDiagnostics(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
) : DiagnosticsContributor {
    override fun diagnose(now: Instant): List<ComponentHealth> = listOf(scheduler(now), pushQueue(now), recentErrors(now), auditChain(now))

    /** FR-110: the hash chain proves the append-only audit trail is complete and unmodified. */
    private fun auditChain(now: Instant): ComponentHealth {
        val broken = audit.verifyChain()
        val count = jdbc.sql("select count(*) from audit_events").query(Long::class.javaObjectType).single()
        return ComponentHealth(
            "audit-chain",
            if (broken == null) HealthStatus.OK else HealthStatus.FAILED,
            if (broken == null) "Audit hash chain intact ($count events)" else "Audit hash chain broken at event $broken",
            mapOf("events" to count, "firstBrokenEventId" to broken),
            now,
        )
    }

    private fun scheduler(now: Instant): ComponentHealth {
        val active = jdbc.sql("select count(*) from strategy_activations where status = 'ACTIVE'").query(Int::class.java).single()
        val last =
            jdbc
                .sql("select max(started_at) t from strategy_evaluations")
                .query { rs, _ -> rs.instantOrNull("t") }
                .list()
                .firstOrNull()
        val since = ts(now.minus(Duration.ofHours(24)))
        val counts =
            jdbc
                .sql("select status, count(*) n from strategy_evaluations where started_at >= :t group by status")
                .param("t", since)
                .query { rs, _ -> rs.getString("status") to rs.getInt("n") }
                .list()
                .toMap()
        val stuck =
            jdbc
                .sql("select count(*) from strategy_evaluations where status = 'CLAIMED' and started_at < :t")
                .param("t", ts(now.minus(STUCK_AFTER)))
                .query(Int::class.java)
                .single()
        val status =
            when {
                stuck > 0 -> HealthStatus.DEGRADED
                (counts["FAILED"] ?: 0) > 0 -> HealthStatus.DEGRADED
                else -> HealthStatus.OK
            }
        val detail =
            when {
                active == 0 -> "No active strategies; the evaluation scheduler is idle"
                stuck > 0 -> "$stuck evaluation(s) claimed but not completed"
                else -> "$active active strategy(ies)"
            }
        return ComponentHealth(
            "strategy-scheduler",
            status,
            detail,
            mapOf("activeStrategies" to active, "lastEvaluationAt" to last, "last24h" to counts, "stuckClaims" to stuck),
            now,
        )
    }

    private fun pushQueue(now: Instant): ComponentHealth {
        val pending = jdbc.sql("select count(*) from push_deliveries where status = 'PENDING'").query(Int::class.java).single()
        val oldest =
            jdbc
                .sql("select min(created_at) t from push_deliveries where status = 'PENDING'")
                .query { rs, _ -> rs.instantOrNull("t") }
                .list()
                .firstOrNull()
        val failed24h =
            jdbc
                .sql("select count(*) from push_deliveries where status = 'FAILED' and updated_at >= :t")
                .param("t", ts(now.minus(Duration.ofHours(24))))
                .query(Int::class.java)
                .single()
        val lagging = oldest != null && Duration.between(oldest, now) > PUSH_LAG
        return ComponentHealth(
            "push-queue",
            if (lagging) HealthStatus.DEGRADED else HealthStatus.OK,
            if (pending == 0) "Push outbox empty" else "$pending push delivery(ies) queued",
            mapOf("queueDepth" to pending, "oldestQueuedAt" to oldest, "failedLast24h" to failed24h),
            now,
        )
    }

    private fun recentErrors(now: Instant): ComponentHealth {
        val errors =
            jdbc
                .sql("select occurred_at, component, status, detail from system_health_events where status in ('FAILED', 'DEGRADED') and occurred_at >= :t order by id desc limit 20")
                .param("t", ts(now.minus(Duration.ofHours(24))))
                .query { rs, _ -> mapOf("occurredAt" to rs.instant("occurred_at"), "component" to rs.getString("component"), "status" to rs.getString("status"), "detail" to rs.getString("detail")) }
                .list()
        return ComponentHealth(
            "recent-errors",
            if (errors.any { it["status"] == "FAILED" }) HealthStatus.DEGRADED else HealthStatus.OK,
            if (errors.isEmpty()) "No errors in the last 24 hours" else "${errors.size} health event(s) in the last 24 hours",
            mapOf("events" to errors),
            now,
        )
    }

    companion object {
        val STUCK_AFTER: Duration = Duration.ofMinutes(10)
        val PUSH_LAG: Duration = Duration.ofMinutes(30)
    }
}
