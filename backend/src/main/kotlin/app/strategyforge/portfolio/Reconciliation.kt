package app.strategyforge.portfolio

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.operations.ComponentHealth
import app.strategyforge.operations.DiagnosticsContributor
import app.strategyforge.operations.HealthStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ReconciliationCheck(
    val name: String,
    val passed: Boolean,
    val expected: String?,
    val actual: String?,
    val detail: String,
)

data class ReconciliationRun(
    val id: UUID,
    val portfolioId: UUID,
    val runAt: Instant,
    val status: String,
    val checks: List<ReconciliationCheck>,
)

/** Published when reconciliation fails so autonomy pauses (FR-074) and increases are blocked. */
data class ReconciliationFailed(
    val portfolioId: UUID,
    val runId: UUID,
    val failed: List<String>,
)

/**
 * Proves the mutable projections agree with the append-only ledger (FR-012, NFR-008):
 * journals balanced, lots equal ledger positions, reservations equal open-order holds,
 * funding equals the starting balance, fills equal order quantities, no negative cash.
 */
@Service
class ReconciliationService(
    private val jdbc: JdbcClient,
    private val ledger: LedgerService,
    private val lots: LotService,
    private val audit: AuditService,
    private val notifications: NotificationService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun run(portfolioId: UUID): ReconciliationRun {
        val checks = mutableListOf<ReconciliationCheck>()
        val unbalanced = ledger.unbalancedJournals(portfolioId)
        checks += ReconciliationCheck("JOURNALS_BALANCED", unbalanced.isEmpty(), "0", unbalanced.size.toString(), if (unbalanced.isEmpty()) "Every journal sums to zero" else "Unbalanced journals: ${unbalanced.take(5)}")

        val ledgerPositions = ledger.positions(portfolioId).associateBy { it.instrumentId }
        val lotTotals = lots.totals(portfolioId)
        val ids = ledgerPositions.keys + lotTotals.keys
        val mismatches =
            ids.filter { id ->
                val lp = ledgerPositions[id]
                val lt = lotTotals[id]
                (lp?.quantity ?: BigDecimal.ZERO).compareTo(lt?.first ?: BigDecimal.ZERO) != 0 || (lp?.cost ?: BigDecimal.ZERO).compareTo(lt?.second ?: BigDecimal.ZERO) != 0
            }
        checks += ReconciliationCheck("LOTS_MATCH_LEDGER", mismatches.isEmpty(), "0", mismatches.size.toString(), if (mismatches.isEmpty()) "Lots equal ledger positions (${ids.size} instrument(s))" else "Mismatched instruments: $mismatches")

        val reserved = ledger.balance(portfolioId, Account.CASH_RESERVED)
        val holds =
            jdbc
                .sql("select coalesce(sum(reserved_amount - reservation_released), 0) from paper_orders where portfolio_id = :p and status in ('CREATED','VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("p", portfolioId)
                .query(BigDecimal::class.java)
                .single()
        val closedHolds =
            jdbc
                .sql("select coalesce(sum(reserved_amount - reservation_released), 0) from paper_orders where portfolio_id = :p and status in ('REJECTED','FILLED','CANCELLED','EXPIRED','FAILED')")
                .param("p", portfolioId)
                .query(BigDecimal::class.java)
                .single()
        checks += ReconciliationCheck("RESERVATIONS_MATCH_OPEN_ORDERS", reserved.compareTo(holds) == 0 && closedHolds.signum() == 0, holds.toPlainString(), reserved.toPlainString(), "Reserved cash equals holds of open orders; closed orders hold nothing")

        val start =
            jdbc
                .sql("select starting_balance from portfolios where id = :p")
                .param("p", portfolioId)
                .query(BigDecimal::class.java)
                .single()
        val capital = ledger.balance(portfolioId, Account.OWNER_CAPITAL).negate()
        checks += ReconciliationCheck("FUNDING_MATCHES_START", capital.compareTo(start) == 0, start.toPlainString(), capital.toPlainString(), "Owner capital equals starting balance")

        val fillMismatch =
            jdbc
                .sql(
                    """
                    select count(*) from paper_orders o left join (select order_id, sum(quantity) q from paper_executions group by order_id) e on e.order_id = o.id
                    where o.portfolio_id = :p and coalesce(e.q, 0) <> o.filled_quantity
                    """.trimIndent(),
                ).param("p", portfolioId)
                .query(Int::class.java)
                .single()
        checks += ReconciliationCheck("FILLS_MATCH_ORDERS", fillMismatch == 0, "0", fillMismatch.toString(), "Execution quantities equal order filled quantities")

        val executionsMatch =
            jdbc
                .sql(
                    """
                    select count(*) from paper_executions e
                    where e.portfolio_id = :p and not exists (select 1 from ledger_journals j where j.id = e.journal_id and j.journal_type = 'EXECUTION')
                    """.trimIndent(),
                ).param("p", portfolioId)
                .query(Int::class.java)
                .single()
        checks += ReconciliationCheck("EXECUTIONS_JOURNALED", executionsMatch == 0, "0", executionsMatch.toString(), "Every execution has its ledger journal")

        val cash = ledger.balance(portfolioId, Account.CASH)
        checks += ReconciliationCheck("CASH_NON_NEGATIVE", cash.signum() >= 0, ">= 0", cash.toPlainString(), "Available cash is not negative")

        val status = if (checks.all { it.passed }) "OK" else "FAILED"
        val id = UUID.randomUUID()
        val now = clock.instant()
        jdbc
            .sql("insert into reconciliation_runs(id, portfolio_id, run_at, status, checks) values (:id, :p, :t, :s, cast(:c as jsonb))")
            .param("id", id)
            .param("p", portfolioId)
            .param("t", ts(now))
            .param("s", status)
            .param("c", mapper.writeValueAsString(checks))
            .update()
        val previous =
            jdbc
                .sql("select reconciliation_status from portfolios where id = :p")
                .param("p", portfolioId)
                .query(String::class.java)
                .single()
        jdbc
            .sql("update portfolios set reconciliation_status = :s where id = :p")
            .param("s", status)
            .param("p", portfolioId)
            .update()
        if (status == "FAILED") {
            val failed = checks.filter { !it.passed }.map { it.name }
            audit.record(AuditCategory.LEDGER, "RECONCILIATION_FAILED", AuditOutcome.FAILURE, "Portfolio", portfolioId, mapOf("failed" to failed, "runId" to id))
            notifications.notify(
                NotificationCategory.RISK_EVENT,
                Severity.CRITICAL,
                "Ledger reconciliation failed",
                "Checks failed: ${failed.joinToString()}. New or larger positions are blocked until reconciliation passes.",
                "Portfolio",
                portfolioId,
                "recon:$portfolioId:${failed.sorted()}",
            )
            events.publishEvent(ReconciliationFailed(portfolioId, id, failed))
        } else if (previous != "OK") {
            audit.record(AuditCategory.LEDGER, "RECONCILIATION_RECOVERED", entityType = "Portfolio", entityId = portfolioId, details = mapOf("runId" to id))
        }
        return ReconciliationRun(id, portfolioId, now, status, checks)
    }

    fun runs(
        portfolioId: UUID,
        limit: Int,
    ): List<ReconciliationRun> =
        jdbc
            .sql("select * from reconciliation_runs where portfolio_id = :p order by run_at desc limit :l")
            .param("p", portfolioId)
            .param("l", limit)
            .query { rs, _ ->
                ReconciliationRun(
                    rs.uuid("id"),
                    rs.uuid("portfolio_id"),
                    rs.instant("run_at"),
                    rs.getString("status"),
                    mapper.readValue(rs.getString("checks"), mapper.typeFactory.constructCollectionType(List::class.java, ReconciliationCheck::class.java)),
                )
            }.list()

    private val dirty =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<UUID>()

    /** Ledger-changing activity marks a portfolio for reconciliation on the next replay step. */
    fun markDirty(portfolioId: UUID) {
        dirty.add(portfolioId)
    }

    fun runDirty(): List<ReconciliationRun> {
        val ids = dirty.toList()
        dirty.removeAll(ids.toSet())
        return ids.mapNotNull { id -> runCatching { run(id) }.onFailure { log.error("Reconciliation failed to run for {}", id, it) }.getOrNull() }
    }

    fun runAll(): List<ReconciliationRun> =
        jdbc.sql("select id from portfolios where status = 'ACTIVE'").query(UUID::class.java).list().mapNotNull { id ->
            runCatching { run(id) }.onFailure { log.error("Reconciliation failed to run for {}", id, it) }.getOrNull()
        }

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.reconciliation-interval-ms:300000}", initialDelay = 30000)
    fun scheduled() {
        CorrelationIdFilter.withCorrelation("recon") { runAll() }
    }
}

@Component
class ReconciliationDiagnostics(
    private val jdbc: JdbcClient,
) : DiagnosticsContributor {
    override fun diagnose(now: Instant): List<ComponentHealth> {
        val rows =
            jdbc
                .sql(
                    """
                    select p.id, p.name, p.reconciliation_status, (select max(run_at) from reconciliation_runs r where r.portfolio_id = p.id) as last
                    from portfolios p where p.status = 'ACTIVE'
                    """.trimIndent(),
                ).query { rs, _ -> mapOf("portfolioId" to rs.uuid("id"), "name" to rs.getString("name"), "status" to rs.getString("reconciliation_status"), "lastRun" to rs.getObject("last", java.time.OffsetDateTime::class.java)?.toInstant()) }
                .list()
        val failed = rows.count { it["status"] == "FAILED" }
        return listOf(
            ComponentHealth(
                "reconciliation",
                when {
                    failed > 0 -> HealthStatus.FAILED
                    rows.any { it["status"] != "OK" } -> HealthStatus.DEGRADED
                    else -> HealthStatus.OK
                },
                if (rows.isEmpty()) "No active portfolios" else "$failed of ${rows.size} portfolio(s) failing reconciliation",
                mapOf("portfolios" to rows),
                now,
            ),
        )
    }
}
