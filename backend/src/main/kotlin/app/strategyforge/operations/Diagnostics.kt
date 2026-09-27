package app.strategyforge.operations

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.providers.ProviderService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
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
interface DiagnosticsContributor {
    fun diagnose(now: Instant): List<ComponentHealth>
}

@Service
class DiagnosticsService(
    private val contributors: ObjectProvider<DiagnosticsContributor>,
    private val clock: Clock,
    private val jdbc: JdbcClient,
) {
    fun report(): DiagnosticsReport {
        val now = clock.instant()
        val components =
            contributors
                .orderedStream()
                .toList()
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

    /** Appends a health event for the operations history (FR-111). */
    fun recordEvent(
        component: String,
        status: HealthStatus,
        detail: String,
    ) {
        val s = if (status == HealthStatus.NOT_CONFIGURED) HealthStatus.UNKNOWN else status
        jdbc
            .sql("insert into system_health_events(occurred_at, component, status, detail) values (:t, :c, :s, :d)")
            .param("t", ts(clock.instant()))
            .param("c", component)
            .param("s", s.name)
            .param("d", detail.take(1000))
            .update()
    }

    fun recentEvents(limit: Int): List<Map<String, Any?>> =
        jdbc
            .sql("select occurred_at, component, status, detail from system_health_events order by id desc limit :l")
            .param("l", limit)
            .query { rs, _ -> mapOf("occurredAt" to rs.instant("occurred_at"), "component" to rs.getString("component"), "status" to rs.getString("status"), "detail" to rs.getString("detail")) }
            .list()
}

@Component
class CoreDiagnostics(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val providers: ProviderService,
) : DiagnosticsContributor {
    override fun diagnose(now: Instant): List<ComponentHealth> {
        val out = mutableListOf<ComponentHealth>()
        val uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().uptime)
        out += ComponentHealth("backend", HealthStatus.OK, "Backend running", mapOf("version" to VERSION, "uptimeSeconds" to uptime.seconds, "javaVersion" to Runtime.version().toString()), now)

        val started = System.nanoTime()
        val dbNow =
            jdbc
                .sql("select now()")
                .query(java.time.OffsetDateTime::class.java)
                .single()
                .toInstant()
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val migration =
            jdbc
                .sql("select max(version) from flyway_schema_history where success")
                .query(String::class.java)
                .optional()
                .orElse(null)
        out += ComponentHealth("database", if (latencyMs < 1000) HealthStatus.OK else HealthStatus.DEGRADED, "PostgreSQL reachable", mapOf("latencyMs" to latencyMs, "schemaVersion" to migration), now)

        val appNow = clock.instant()
        val driftMs = Duration.between(dbNow, appNow).toMillis()
        val driftStatus =
            when {
                kotlin.math.abs(driftMs) > CLOCK_FAIL_MS -> HealthStatus.FAILED
                kotlin.math.abs(driftMs) > CLOCK_WARN_MS -> HealthStatus.DEGRADED
                else -> HealthStatus.OK
            }
        out += ComponentHealth("clock", driftStatus, "Application vs database clock drift ${driftMs}ms", mapOf("driftMs" to driftMs, "thresholdMs" to CLOCK_FAIL_MS), now)

        val ai = providers.list().filter { it.kind == "AI" }
        out +=
            ComponentHealth(
                "ai-providers",
                when {
                    ai.isEmpty() -> HealthStatus.NOT_CONFIGURED
                    ai.any { it.lastTestStatus == "OK" } -> HealthStatus.OK
                    ai.all { it.lastTestStatus == null } -> HealthStatus.UNKNOWN
                    else -> HealthStatus.DEGRADED
                },
                if (ai.isEmpty()) "No AI provider configured" else "${ai.size} AI provider(s) configured",
                mapOf("providers" to ai.map { mapOf("id" to it.id, "type" to it.providerType, "credential" to it.credential.configured, "lastTestStatus" to it.lastTestStatus, "lastTestAt" to it.lastTestAt) }),
                now,
            )
        val push = providers.list().filter { it.kind == "PUSH" }
        out +=
            ComponentHealth(
                "push-fcm",
                when {
                    push.isEmpty() -> HealthStatus.NOT_CONFIGURED
                    push.any { it.active && it.lastTestStatus == "OK" } -> HealthStatus.OK
                    else -> HealthStatus.DEGRADED
                },
                if (push.isEmpty()) "FCM push not configured; the in-app inbox remains authoritative" else "FCM configured",
                mapOf("configured" to push.isNotEmpty(), "active" to push.any { it.active }),
                now,
            )
        return out
    }

    companion object {
        const val VERSION = "1.0.0"
        const val CLOCK_WARN_MS = 2_000L
        const val CLOCK_FAIL_MS = 10_000L
    }
}

@RestController
@RequestMapping("/v1/diagnostics")
@Tag(name = "Configuration")
class DiagnosticsController(
    private val diagnostics: DiagnosticsService,
) {
    @GetMapping
    fun report(): DiagnosticsReport = diagnostics.report()

    @GetMapping("/events")
    fun events() = diagnostics.recentEvents(100)
}
