package app.strategyforge.reports

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import app.strategyforge.portfolio.PortfolioService
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ReconciliationCheck(
    val name: String,
    val expected: BigDecimal,
    val actual: BigDecimal,
    val ok: Boolean,
)

data class ExportDocument(
    val dataset: String,
    val schemaVersion: Int,
    val generatedAt: Instant,
    val portfolioId: UUID?,
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val totals: Map<String, BigDecimal>,
    val reconciliation: List<ReconciliationCheck>,
    val disclaimer: String,
) {
    val reconciled: Boolean get() = reconciliation.all { it.ok }
}

/**
 * CSV and JSON exports with stable, versioned column schemas and reconciliation totals (FR-105,
 * MS-19). Ledger-derived totals are checked against independent sources (account balances,
 * executions, portfolio summary) so an export that does not reconcile says so explicitly.
 */
@Service
class ExportService(
    private val jdbc: JdbcClient,
    private val portfolios: PortfolioService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun export(
        dataset: String,
        portfolioId: UUID?,
    ): ExportDocument {
        val doc =
            when (dataset) {
                "ledger" -> ledger(requirePortfolio(portfolioId))
                "executions" -> executions(requirePortfolio(portfolioId))
                "orders" -> orders(requirePortfolio(portfolioId))
                "recommendations" -> recommendations(requirePortfolio(portfolioId))
                "risk-evaluations" -> riskEvaluations(requirePortfolio(portfolioId))
                "audit" -> auditTrail()
                else -> throw Problems.notFound("Export dataset", dataset)
            }
        return doc
    }

    private fun requirePortfolio(id: UUID?): UUID {
        val p = id ?: throw Problems.badRequest("portfolio-required", "portfolioId is required for this dataset")
        portfolios.get(p)
        return p
    }

    private fun ledger(p: UUID): ExportDocument {
        val cols = listOf("seq", "journalId", "journalType", "occurredAt", "account", "symbol", "amount", "quantity", "description")
        val rows =
            jdbc
                .sql(
                    """
                    select j.seq, j.id, j.journal_type, j.occurred_at, e.account, i.symbol, e.amount, e.quantity, j.description
                    from ledger_entries e join ledger_journals j on j.id = e.journal_id left join instruments i on i.id = e.instrument_id
                    where e.portfolio_id = :p order by j.seq, e.id
                    """.trimIndent(),
                ).param("p", p)
                .query { rs, _ ->
                    listOf<Any?>(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, java.time.OffsetDateTime::class.java).toInstant(),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getBigDecimal(7),
                        rs.getBigDecimal(8),
                        rs.getString(9),
                    )
                }.list()
        val byAccount = rows.groupBy { it[4] as String }.mapValues { (_, r) -> r.fold(BigDecimal.ZERO) { a, x -> a.add(x[6] as BigDecimal) } }
        val totals = byAccount.mapKeys { "account.${it.key}" } + ("allEntries" to rows.fold(BigDecimal.ZERO) { a, x -> a.add(x[6] as BigDecimal) })
        val summary = portfolios.summary(p)
        val cash = byAccount["CASH"] ?: BigDecimal.ZERO
        val checks =
            listOf(
                check("Double entry: all entries sum to zero", BigDecimal.ZERO, totals.getValue("allEntries")),
                check("CASH account equals portfolio cash", summary.cash, cash),
                check("Owner capital equals starting balance", summary.portfolio.startingBalance.negate(), byAccount["OWNER_CAPITAL"] ?: BigDecimal.ZERO),
            ) + balanceChecks(p, byAccount)
        return doc("ledger", p, cols, rows, totals, checks)
    }

    /** Independent re-aggregation from the database per account. */
    private fun balanceChecks(
        p: UUID,
        exported: Map<String, BigDecimal>,
    ): List<ReconciliationCheck> =
        jdbc
            .sql("select account, sum(amount) from ledger_entries where portfolio_id = :p group by account order by account")
            .param("p", p)
            .query { rs, _ -> check("Account ${rs.getString(1)} matches ledger balance", rs.getBigDecimal(2), exported[rs.getString(1)] ?: BigDecimal.ZERO) }
            .list()

    private fun executions(p: UUID): ExportDocument {
        val cols = listOf("executionId", "orderId", "symbol", "side", "quantity", "price", "notional", "commission", "spreadCost", "slippageCost", "realizedPnl", "executedAt")
        val rows =
            jdbc
                .sql(
                    """
                    select e.id, e.order_id, i.symbol, e.side, e.quantity, e.price, e.notional, e.commission, e.spread_cost, e.slippage_cost, e.realized_pnl, e.executed_at
                    from paper_executions e join instruments i on i.id = e.instrument_id where e.portfolio_id = :p order by e.executed_at, e.id
                    """.trimIndent(),
                ).param("p", p)
                .query { rs, _ ->
                    listOf<Any?>(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getBigDecimal(5),
                        rs.getBigDecimal(6),
                        rs.getBigDecimal(7),
                        rs.getBigDecimal(8),
                        rs.getBigDecimal(9),
                        rs.getBigDecimal(10),
                        rs.getBigDecimal(11),
                        rs.getObject(12, java.time.OffsetDateTime::class.java).toInstant(),
                    )
                }.list()

        fun sum(i: Int) = rows.fold(BigDecimal.ZERO) { a, x -> a.add(x[i] as BigDecimal) }
        val totals = mapOf("notional" to sum(6), "commission" to sum(7), "spreadCost" to sum(8), "slippageCost" to sum(9), "realizedPnl" to sum(10))

        fun account(a: String) =
            jdbc
                .sql("select coalesce(sum(amount),0) from ledger_entries where portfolio_id = :p and account = :a")
                .param("p", p)
                .param("a", a)
                .query(BigDecimal::class.java)
                .single()
        val checks =
            listOf(
                check("Commissions equal the ledger FEES account", account("FEES"), totals.getValue("commission")),
                check("Realized P/L equals the ledger REALIZED_PNL account (sign reversed)", account("REALIZED_PNL").negate(), totals.getValue("realizedPnl")),
            )
        return doc("executions", p, cols, rows, totals, checks)
    }

    private fun orders(p: UUID): ExportDocument {
        val cols = listOf("orderId", "symbol", "side", "orderType", "timeInForce", "quantity", "limitPrice", "stopPrice", "status", "source", "strategyId", "recommendationId", "filledQuantity", "averageFillPrice", "rejectionReason", "createdAt")
        val rows =
            jdbc
                .sql(
                    """
                    select o.id, i.symbol, o.side, o.order_type, o.time_in_force, o.quantity, o.limit_price, o.stop_price, o.status, o.source, o.strategy_id, o.recommendation_id,
                      o.filled_quantity, o.average_fill_price, o.rejection_reason, o.created_at
                    from paper_orders o join instruments i on i.id = o.instrument_id where o.portfolio_id = :p order by o.created_at, o.id
                    """.trimIndent(),
                ).param("p", p)
                .query { rs, _ -> (1..16).map { c -> value(rs, c) } }
                .list()
        val filled = rows.fold(BigDecimal.ZERO) { a, x -> a.add(x[12] as BigDecimal) }
        val executed =
            jdbc
                .sql("select coalesce(sum(quantity),0) from paper_executions where portfolio_id = :p")
                .param("p", p)
                .query(BigDecimal::class.java)
                .single()
        return doc("orders", p, cols, rows, mapOf("filledQuantity" to filled, "orders" to BigDecimal(rows.size)), listOf(check("Filled quantity equals executed quantity", executed, filled)))
    }

    private fun recommendations(p: UUID): ExportDocument {
        val cols = listOf("recommendationId", "strategyId", "symbol", "status", "side", "quantity", "orderType", "limitPrice", "referencePrice", "expiresAt", "orderId", "createdAt", "decidedAt")
        val rows =
            jdbc
                .sql(
                    """
                    select r.id, r.strategy_id, i.symbol, r.status, r.side, r.quantity, r.order_type, r.limit_price, r.reference_price, r.expires_at, r.order_id, r.created_at, r.decided_at
                    from recommendations r join instruments i on i.id = r.instrument_id where r.portfolio_id = :p order by r.created_at, r.id
                    """.trimIndent(),
                ).param("p", p)
                .query { rs, _ -> (1..13).map { c -> value(rs, c) } }
                .list()
        val withOrders = rows.count { it[10] != null }
        val linked =
            jdbc
                .sql("select count(*) from paper_orders where portfolio_id = :p and recommendation_id is not null")
                .param("p", p)
                .query(Int::class.java)
                .single()
        return doc("recommendations", p, cols, rows, mapOf("recommendations" to BigDecimal(rows.size), "withOrders" to BigDecimal(withOrders)), listOf(check("One order per accepted recommendation", BigDecimal(linked), BigDecimal(withOrders))))
    }

    private fun riskEvaluations(p: UUID): ExportDocument {
        val cols = listOf("evaluationId", "source", "decision", "blockingRules", "marketTime", "createdAt", "durationMs")
        val rows =
            jdbc
                .sql("select id, source, decision, array_to_string(blocking_rules, ';'), market_time, created_at, duration_ms from risk_evaluations where portfolio_id = :p order by created_at, id")
                .param("p", p)
                .query { rs, _ -> (1..7).map { c -> value(rs, c) } }
                .list()
        val blocked = rows.count { it[2] == "BLOCK" }
        return doc("risk-evaluations", p, cols, rows, mapOf("evaluations" to BigDecimal(rows.size), "blocked" to BigDecimal(blocked)), emptyList())
    }

    private fun auditTrail(): ExportDocument {
        val cols = listOf("seq", "eventId", "occurredAt", "actor", "category", "action", "outcome", "entityType", "entityId", "correlationId", "hash")
        val rows =
            jdbc
                .sql("select id, event_id, occurred_at, actor, category, action, outcome, entity_type, entity_id, correlation_id, hash from audit_events order by id")
                .query { rs, _ -> (1..11).map { c -> value(rs, c) } }
                .list()
        return doc("audit", null, cols, rows, mapOf("events" to BigDecimal(rows.size)), emptyList())
    }

    private fun value(
        rs: java.sql.ResultSet,
        c: Int,
    ): Any? =
        when (val v = rs.getObject(c)) {
            is java.time.OffsetDateTime -> v.toInstant()
            is java.sql.Timestamp -> v.toInstant()
            is UUID -> v.toString()
            else -> v
        }

    private fun check(
        name: String,
        expected: BigDecimal,
        actual: BigDecimal,
    ) = ReconciliationCheck(name, expected, actual, expected.compareTo(actual) == 0)

    private fun doc(
        dataset: String,
        p: UUID?,
        cols: List<String>,
        rows: List<List<Any?>>,
        totals: Map<String, BigDecimal>,
        checks: List<ReconciliationCheck>,
    ) = ExportDocument(dataset, SCHEMA_VERSION, clock.instant(), p, cols, rows, totals.mapValues { Decimals.money(it.value) }, checks, ReportService.DISCLAIMER)

    /** Records who exported what, with a SHA-256 of the produced file (FR-110). */
    fun recordExport(
        doc: ExportDocument,
        format: String,
        bytes: ByteArray,
    ) {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        audit.recordIndependently(
            AuditCategory.EXPORT,
            "EXPORT_CREATED",
            if (doc.reconciled) app.strategyforge.common.audit.AuditOutcome.SUCCESS else app.strategyforge.common.audit.AuditOutcome.FAILURE,
            "Export",
            doc.dataset,
            mapOf("format" to format, "portfolioId" to doc.portfolioId, "rows" to doc.rows.size, "sha256" to sha, "reconciled" to doc.reconciled),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** RFC 4180 CSV with formula-injection protection for text cells. */
        fun csv(doc: ExportDocument): String {
            val sb = StringBuilder()
            sb.append(doc.columns.joinToString(",") { cell(it) }).append("\r\n")
            doc.rows.forEach { r -> sb.append(r.joinToString(",") { cell(it) }).append("\r\n") }
            doc.totals.forEach { (k, v) ->
                sb
                    .append(cell("TOTAL"))
                    .append(',')
                    .append(cell(k))
                    .append(',')
                    .append(cell(v))
                    .append("\r\n")
            }
            return sb.toString()
        }

        private fun cell(v: Any?): String {
            val s =
                when (v) {
                    null -> ""
                    is BigDecimal -> v.toPlainString()
                    else -> v.toString()
                }
            val safe = if (v !is Number && s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}

@RestController
@RequestMapping("/v1/exports")
@Tag(name = "Reports")
class ExportController(
    private val exports: ExportService,
    private val mapper: ObjectMapper,
) {
    /** `format` is csv or json. Totals and reconciliation status are also returned as headers. */
    @GetMapping("/{dataset}")
    fun export(
        @PathVariable dataset: String,
        @RequestParam(defaultValue = "json") format: String,
        @RequestParam(required = false) portfolioId: String?,
    ): ResponseEntity<ByteArray> {
        val doc = exports.export(dataset, portfolioId?.let { parseUuid(it) })
        val (bytes, type) =
            when (format) {
                "csv" -> ExportService.csv(doc).toByteArray(Charsets.UTF_8) to MediaType("text", "csv", Charsets.UTF_8)
                "json" -> mapper.writeValueAsBytes(doc) to MediaType.APPLICATION_JSON
                else -> throw Problems.badRequest("invalid-format", "format must be csv or json")
            }
        exports.recordExport(doc, format, bytes)
        return ResponseEntity
            .ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"strategyforge-${doc.dataset}-v${doc.schemaVersion}.$format\"")
            .header("X-StrategyForge-Schema-Version", doc.schemaVersion.toString())
            .header("X-StrategyForge-Reconciled", doc.reconciled.toString())
            .body(bytes)
    }
}
