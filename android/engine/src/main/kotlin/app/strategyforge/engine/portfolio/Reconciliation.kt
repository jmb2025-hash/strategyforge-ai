package app.strategyforge.engine.portfolio

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.operations.ComponentHealth
import app.strategyforge.engine.operations.DiagnosticsContributor
import app.strategyforge.engine.operations.HealthStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Serializable
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
class ReconciliationService(
    private val db: Db,
    private val ledger: LedgerService,
    private val lots: LotService,
    private val audit: AuditService,
    private val notifications: NotificationService,
    private val clock: Clock,
    private val events: EngineEvents,
) {
    private val log = EngineLog.of(javaClass)

    fun run(portfolioId: UUID): ReconciliationRun = db.tx { runInTx(portfolioId) }

    private fun runInTx(portfolioId: UUID): ReconciliationRun {
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

        // Sums are taken in Kotlin: amounts are exact decimal text.
        fun held(statuses: String) =
            db
                .sql("select reserved_amount, reservation_released from paper_orders where portfolio_id = :p and status in ($statuses)")
                .param("p", portfolioId)
                .list { it.dec("reserved_amount").subtract(it.dec("reservation_released")) }
                .fold(BigDecimal.ZERO, BigDecimal::add)
        val holds = held("'CREATED','VALIDATED','PENDING','PARTIALLY_FILLED'")
        val closedHolds = held("'REJECTED','FILLED','CANCELLED','EXPIRED','FAILED'")
        checks += ReconciliationCheck("RESERVATIONS_MATCH_OPEN_ORDERS", reserved.compareTo(holds) == 0 && closedHolds.signum() == 0, holds.toPlainString(), reserved.toPlainString(), "Reserved cash equals holds of open orders; closed orders hold nothing")

        val start =
            db
                .sql("select starting_balance from portfolios where id = :p")
                .param("p", portfolioId)
                .single { it.dec("starting_balance") }
        val capital = ledger.balance(portfolioId, Account.OWNER_CAPITAL).negate()
        checks += ReconciliationCheck("FUNDING_MATCHES_START", capital.compareTo(start) == 0, start.toPlainString(), capital.toPlainString(), "Owner capital equals starting balance")

        val executed = mutableMapOf<String, BigDecimal>()
        db
            .sql("select order_id, quantity from paper_executions where portfolio_id = :p")
            .param("p", portfolioId)
            .list { it.str("order_id") to it.dec("quantity") }
            .forEach { (o, q) -> executed.merge(o, q, BigDecimal::add) }
        val fillMismatch =
            db
                .sql("select id, filled_quantity from paper_orders where portfolio_id = :p")
                .param("p", portfolioId)
                .list { it.str("id") to it.dec("filled_quantity") }
                .count { (o, filled) -> (executed[o] ?: BigDecimal.ZERO).compareTo(filled) != 0 }
        checks += ReconciliationCheck("FILLS_MATCH_ORDERS", fillMismatch == 0, "0", fillMismatch.toString(), "Execution quantities equal order filled quantities")

        val executionsMatch =
            db
                .sql(
                    """
                    select count(*) n from paper_executions e
                    where e.portfolio_id = :p and not exists (select 1 from ledger_journals j where j.id = e.journal_id and j.journal_type = 'EXECUTION')
                    """.trimIndent(),
                ).param("p", portfolioId)
                .int()
        checks += ReconciliationCheck("EXECUTIONS_JOURNALED", executionsMatch == 0, "0", executionsMatch.toString(), "Every execution has its ledger journal")

        val cash = ledger.balance(portfolioId, Account.CASH)
        checks += ReconciliationCheck("CASH_NON_NEGATIVE", cash.signum() >= 0, ">= 0", cash.toPlainString(), "Available cash is not negative")

        val status = if (checks.all { it.passed }) "OK" else "FAILED"
        val id = UUID.randomUUID()
        val now = clock.instant()
        db
            .sql("insert into reconciliation_runs(id, portfolio_id, run_at, status, checks) values (:id, :p, :t, :s, :c)")
            .param("id", id)
            .param("p", portfolioId)
            .param("t", (now))
            .param("s", status)
            .param("c", EngineJson.encodeToString(ListSerializer(ReconciliationCheck.serializer()), checks))
            .update()
        val previous =
            db
                .sql("select reconciliation_status from portfolios where id = :p")
                .param("p", portfolioId)
                .single { it.str("reconciliation_status") }
        db
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
            events.publish(ReconciliationFailed(portfolioId, id, failed))
        } else if (previous != "OK") {
            audit.record(AuditCategory.LEDGER, "RECONCILIATION_RECOVERED", entityType = "Portfolio", entityId = portfolioId, details = mapOf("runId" to id))
        }
        return ReconciliationRun(id, portfolioId, now, status, checks)
    }

    fun runs(
        portfolioId: UUID,
        limit: Int,
    ): List<ReconciliationRun> =
        db
            .sql("select * from reconciliation_runs where portfolio_id = :p order by run_at desc limit :l")
            .param("p", portfolioId)
            .param("l", limit)
            .list { rs ->
                ReconciliationRun(
                    rs.uuid("id"),
                    rs.uuid("portfolio_id"),
                    rs.instant("run_at"),
                    rs.str("status"),
                    EngineJson.decodeFromString(ListSerializer(ReconciliationCheck.serializer()), rs.str("checks")),
                )
            }

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
        db.sql("select id from portfolios where status = 'ACTIVE'").list { it.uuid("id") }.mapNotNull { id ->
            runCatching { run(id) }.onFailure { log.error("Reconciliation failed to run for {}", id, it) }.getOrNull()
        }

    fun scheduled() {
        run { runAll() }
    }
}

class ReconciliationDiagnostics(
    private val db: Db,
) : DiagnosticsContributor {
    override fun diagnose(now: Instant): List<ComponentHealth> {
        val rows =
            db
                .sql(
                    """
                    select p.id, p.name, p.reconciliation_status, (select max(run_at) from reconciliation_runs r where r.portfolio_id = p.id) as last
                    from portfolios p where p.status = 'ACTIVE'
                    """.trimIndent(),
                ).list { rs -> mapOf("portfolioId" to rs.uuid("id"), "name" to rs.str("name"), "status" to rs.str("reconciliation_status"), "lastRun" to rs.instantOrNull("last")) }
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
