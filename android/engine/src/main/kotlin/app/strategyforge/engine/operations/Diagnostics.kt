package app.strategyforge.engine.operations

import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.str
import java.time.Clock
import java.time.Instant

enum class HealthStatus { OK, DEGRADED, FAILED, NOT_CONFIGURED, UNKNOWN }

data class ComponentHealth(
    val component: String,
    val status: HealthStatus,
    val detail: String,
    val data: Map<String, Any?> = emptyMap(),
    val checkedAt: Instant,
)

data class DiagnosticsReport(
    val overall: HealthStatus,
    val generatedAt: Instant,
    val components: List<ComponentHealth>,
)

/** Each module contributes its own health view (FR-004, FR-111). */
fun interface DiagnosticsContributor {
    fun diagnose(now: Instant): List<ComponentHealth>
}

class DiagnosticsService(
    private val db: Db,
    private val contributors: () -> List<DiagnosticsContributor>,
    private val clock: Clock,
) {
    /** Appends a health event for the operations history (FR-111). */
    fun recordEvent(
        component: String,
        status: HealthStatus,
        detail: String,
    ) {
        val s = if (status == HealthStatus.NOT_CONFIGURED) HealthStatus.UNKNOWN else status
        db
            .sql("insert into system_health_events(occurred_at, component, status, detail) values (:t, :c, :s, :d)")
            .param("t", clock.instant())
            .param("c", component)
            .param("s", s.name)
            .param("d", detail.take(1000))
            .update()
    }

    fun recentEvents(limit: Int): List<Map<String, Any?>> =
        db
            .sql("select occurred_at, component, status, detail from system_health_events order by id desc limit :l")
            .param("l", limit)
            .list { mapOf("occurredAt" to it.instant("occurred_at"), "component" to it.str("component"), "status" to it.str("status"), "detail" to it.string("detail")) }

    fun report(): DiagnosticsReport {
        val now = clock.instant()
        val components =
            contributors()
                .flatMap { c ->
                    runCatching { c.diagnose(now) }.getOrElse {
                        listOf(ComponentHealth(c.javaClass.simpleName, HealthStatus.FAILED, "Diagnostics failed: ${it.javaClass.simpleName}", checkedAt = now))
                    }
                }.sortedBy { it.component }
        val overall =
            when {
                components.any { it.status == HealthStatus.FAILED } -> HealthStatus.FAILED
                components.any { it.status == HealthStatus.DEGRADED || it.status == HealthStatus.UNKNOWN } -> HealthStatus.DEGRADED
                else -> HealthStatus.OK
            }
        return DiagnosticsReport(overall, now, components)
    }
}
