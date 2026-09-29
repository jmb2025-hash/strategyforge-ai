package app.strategyforge.engine.reports

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.portfolio.PortfolioSummary
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class SeriesPoint(
    val at: Instant,
    val value: BigDecimal,
)

data class CostBreakdown(
    val commissions: BigDecimal,
    val spread: BigDecimal,
    val slippage: BigDecimal,
    val borrow: BigDecimal,
    val dividends: BigDecimal,
    val total: BigDecimal,
)

data class Attribution(
    val key: String,
    val label: String,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal?,
    val trades: Int,
)

data class Benchmark(
    val symbol: String,
    val startClose: BigDecimal?,
    val endClose: BigDecimal?,
    val returnPercent: BigDecimal?,
    val note: String?,
)

data class PortfolioReport(
    val summary: PortfolioSummary,
    val equity: List<SeriesPoint>,
    val drawdown: List<SeriesPoint>,
    val maxDrawdownPercent: BigDecimal,
    val periodReturnPercent: BigDecimal?,
    val costs: CostBreakdown,
    val byAsset: List<Attribution>,
    val byStrategy: List<Attribution>,
    val benchmarks: List<Benchmark>,
    val disclaimer: String,
)

data class OutcomeGroup(
    val group: String,
    val count: Int,
    val realizedPnl: BigDecimal,
    val averagePnl: BigDecimal?,
    val note: String,
)

data class OutcomeComparison(
    val portfolioId: UUID,
    val bySource: List<OutcomeGroup>,
    val byRecommendationDecision: List<OutcomeGroup>,
    val disclaimer: String,
)

/** One paper execution, read once and aggregated in Kotlin (amounts are exact decimal text). */
private data class Fill(
    val orderId: UUID,
    val instrumentId: UUID,
    val symbol: String,
    val side: String,
    val realizedPnl: BigDecimal,
    val commission: BigDecimal,
    val spread: BigDecimal,
    val slippage: BigDecimal,
) {
    val closing: Boolean get() = side == "SELL" || side == "BUY_TO_COVER"
}

private fun List<BigDecimal>.total(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

/**
 * Reporting (FR-103, FR-104). Every figure is derived from the append-only ledger, executions,
 * lots and recorded decisions; comparisons are descriptive associations, never causal claims.
 */
class ReportService(
    private val db: Db,
    private val portfolios: PortfolioService,
) {
    private fun fills(portfolioId: UUID): List<Fill> =
        db
            .sql("select e.order_id, e.instrument_id, i.symbol, e.side, e.realized_pnl, e.commission, e.spread_cost, e.slippage_cost from paper_executions e join instruments i on i.id = e.instrument_id where e.portfolio_id = :p")
            .param("p", portfolioId)
            .list { Fill(it.uuid("order_id"), it.uuid("instrument_id"), it.str("symbol"), it.str("side"), it.dec("realized_pnl"), it.dec("commission"), it.dec("spread_cost"), it.dec("slippage_cost")) }

    fun portfolio(id: UUID): PortfolioReport {
        val summary = portfolios.summary(id)
        val equity =
            db
                .sql("select at, equity from portfolio_equity_snapshots where portfolio_id = :p order by at, id")
                .param("p", id)
                .list { SeriesPoint(it.instant("at"), it.dec("equity")) }
        var peak = BigDecimal.ZERO
        val drawdown =
            equity.map { p ->
                peak = peak.max(p.value)
                SeriesPoint(p.at, if (peak.signum() > 0) Decimals.percent(peak.subtract(p.value).multiply(Decimals.HUNDRED).divide(peak, Decimals.MC)) else BigDecimal.ZERO)
            }
        val periodReturn =
            if (equity.size >= 2 && equity.first().value.signum() > 0) {
                Decimals.percent(
                    equity
                        .last()
                        .value
                        .subtract(equity.first().value)
                        .multiply(Decimals.HUNDRED)
                        .divide(equity.first().value, Decimals.MC),
                )
            } else {
                null
            }
        val fills = fills(id)
        val from = equity.firstOrNull()?.at
        val to = equity.lastOrNull()?.at
        return PortfolioReport(
            summary,
            equity,
            drawdown,
            drawdown.maxOfOrNull { it.value } ?: BigDecimal.ZERO,
            periodReturn,
            costs(id, fills),
            byAsset(fills, summary),
            byStrategy(fills),
            listOf(benchmark("SPY", from, to), benchmark("BTC-USD", from, to)),
            DISCLAIMER,
        )
    }

    private fun costs(
        id: UUID,
        fills: List<Fill>,
    ): CostBreakdown {
        val commission = fills.map { it.commission }.total()
        val spread = fills.map { it.spread }.total()
        val slippage = fills.map { it.slippage }.total()
        val borrow =
            db
                .sql("select amount from borrow_accruals where portfolio_id = :p")
                .param("p", id)
                .list { it.dec("amount") }
                .total()
        // DIVIDENDS is a credit account: a negative balance means dividends received, positive means paid in lieu.
        val dividends =
            db
                .sql("select amount from ledger_entries where portfolio_id = :p and account = 'DIVIDENDS'")
                .param("p", id)
                .list { it.dec("amount") }
                .total()
                .negate()
        return CostBreakdown(
            Decimals.money(commission),
            Decimals.money(spread),
            Decimals.money(slippage),
            Decimals.money(borrow),
            Decimals.money(dividends),
            Decimals.money(commission.add(spread).add(slippage).add(borrow)),
        )
    }

    private fun byAsset(
        fills: List<Fill>,
        summary: PortfolioSummary,
    ): List<Attribution> {
        val unrealized = summary.positions.associate { it.instrumentId to it.unrealizedPnl }
        val symbols = fills.associate { it.instrumentId to it.symbol } + summary.positions.associate { it.instrumentId to it.symbol }
        return symbols.entries.sortedBy { it.value }.map { (k, symbol) ->
            val mine = fills.filter { it.instrumentId == k }
            Attribution(k.toString(), symbol, Decimals.money(mine.map { it.realizedPnl }.total()), unrealized[k], mine.count { it.closing })
        }
    }

    private fun byStrategy(fills: List<Fill>): List<Attribution> {
        if (fills.isEmpty()) return emptyList()
        val orderStrategy =
            db
                .sql("select o.id, o.strategy_id, s.name from paper_orders o left join strategies s on s.id = o.strategy_id where o.id in (:ids)")
                .param("ids", fills.map { it.orderId }.distinct())
                .list { it.uuid("id") to (it.uuidOrNull("strategy_id") to it.string("name")) }
                .toMap()
        return fills
            .groupBy { orderStrategy[it.orderId]?.first }
            .map { (sid, group) ->
                val name = sid?.let { orderStrategy.values.firstOrNull { v -> v.first == it }?.second } ?: "Manual and emergency orders"
                Attribution(sid?.toString() ?: "MANUAL", name, Decimals.money(group.map { it.realizedPnl }.total()), null, group.count { it.closing })
            }.sortedBy { it.label }
    }

    fun benchmark(
        symbol: String,
        from: Instant?,
        to: Instant?,
    ): Benchmark {
        if (from == null || to == null) return Benchmark(symbol, null, null, null, "No reporting window yet")
        val start =
            db
                .sql("select c.close from candles c join instruments i on i.id = c.instrument_id where i.symbol = :s and c.timeframe = '1d' and c.open_time >= :t order by c.open_time limit 1")
                .param("s", symbol)
                .param("t", from.minus(Duration.ofDays(1)))
                .firstOrNull { it.dec("close") }
        val end =
            db
                .sql("select c.close from candles c join instruments i on i.id = c.instrument_id where i.symbol = :s and c.timeframe = '1d' and c.open_time <= :t order by c.open_time desc limit 1")
                .param("s", symbol)
                .param("t", to)
                .firstOrNull { it.dec("close") }
        if (start == null || end == null) return Benchmark(symbol, start, end, null, "Benchmark data unavailable for the window (not stored on this phone)")
        return Benchmark(symbol, start, end, Decimals.percent(end.subtract(start).multiply(Decimals.HUNDRED).divide(start, Decimals.MC)), null)
    }

    /**
     * FR-104: outcomes grouped by order source and by recommendation decision. The comparison is
     * descriptive and does not imply causation.
     */
    fun outcomes(portfolioId: UUID): OutcomeComparison {
        portfolios.get(portfolioId)
        val fills = fills(portfolioId)
        val pnlByOrder = fills.groupBy { it.orderId }.mapValues { (_, f) -> f.map { it.realizedPnl }.total() }
        val bySource =
            db
                .sql("select id, source from paper_orders where portfolio_id = :p and status in ('FILLED','PARTIALLY_FILLED')")
                .param("p", portfolioId)
                .list { it.uuid("id") to it.str("source") }
                .groupBy({ it.second }, { it.first })
                .toSortedMap()
                .map { (source, ids) -> group(source, ids.size, ids.mapNotNull { pnlByOrder[it] }.total(), "Realized P/L of filled orders from this source; open positions are not included.") }
        val byDecision =
            db
                .sql("select status, order_id from recommendations where portfolio_id = :p")
                .param("p", portfolioId)
                .list { it.str("status") to it.uuidOrNull("order_id") }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .map { (status, orders) -> group(status, orders.size, orders.mapNotNull { o -> o?.let { pnlByOrder[it] } }.total(), "Realized P/L of orders created from recommendations with this outcome.") }
        return OutcomeComparison(portfolioId, bySource, byDecision, COMPARISON_DISCLAIMER)
    }

    private fun group(
        name: String,
        n: Int,
        pnl: BigDecimal,
        note: String,
    ) = OutcomeGroup(name, n, Decimals.money(pnl), if (n > 0) Decimals.money(pnl.divide(BigDecimal(n), Decimals.MC)) else null, note)

    companion object {
        const val DISCLAIMER = "Simulated paper-trading results based on replay or provider data, simulated fills, fees and slippage. Not real money; past results do not predict future results."
        const val COMPARISON_DISCLAIMER =
            "Descriptive comparison only. Differences between groups are associations and do not show that accepting, declining or automating caused them."
    }
}

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
class ExportService(
    private val db: Db,
    private val portfolios: PortfolioService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun export(
        dataset: String,
        portfolioId: UUID?,
    ): ExportDocument =
        when (dataset) {
            "ledger" -> ledger(requirePortfolio(portfolioId))
            "executions" -> executions(requirePortfolio(portfolioId))
            "orders" -> orders(requirePortfolio(portfolioId))
            "recommendations" -> recommendations(requirePortfolio(portfolioId))
            "risk-evaluations" -> riskEvaluations(requirePortfolio(portfolioId))
            "audit" -> auditTrail()
            else -> throw Problems.notFound("Export dataset", dataset)
        }

    private fun requirePortfolio(id: UUID?): UUID {
        val p = id ?: throw Problems.badRequest("portfolio-required", "portfolioId is required for this dataset")
        portfolios.get(p)
        return p
    }

    private fun Row.num(c: String): BigDecimal? = decOrNull(c)

    private fun ledger(p: UUID): ExportDocument {
        val cols = listOf("seq", "journalId", "journalType", "occurredAt", "account", "symbol", "amount", "quantity", "description")
        val rows =
            db
                .sql(
                    """
                    select j.seq, j.id, j.journal_type, j.occurred_at, e.account, i.symbol, e.amount, e.quantity, j.description
                    from ledger_entries e join ledger_journals j on j.id = e.journal_id left join instruments i on i.id = e.instrument_id
                    where e.portfolio_id = :p order by j.seq, e.id
                    """.trimIndent(),
                ).param("p", p)
                .list { r -> listOf<Any?>(r.long("seq"), r.str("id"), r.str("journal_type"), r.instant("occurred_at"), r.str("account"), r.string("symbol"), r.dec("amount"), r.dec("quantity"), r.str("description")) }
        val byAccount = rows.groupBy { it[4] as String }.mapValues { (_, r) -> r.map { it[6] as BigDecimal }.total() }
        val totals = byAccount.mapKeys { "account.${it.key}" } + ("allEntries" to rows.map { it[6] as BigDecimal }.total())
        val summary = portfolios.summary(p)
        val checks =
            listOf(
                check("Double entry: all entries sum to zero", BigDecimal.ZERO, totals.getValue("allEntries")),
                check("CASH account equals portfolio cash", summary.cash, byAccount["CASH"] ?: BigDecimal.ZERO),
                check("Owner capital equals starting balance", summary.portfolio.startingBalance.negate(), byAccount["OWNER_CAPITAL"] ?: BigDecimal.ZERO),
            ) + balanceChecks(p, byAccount)
        return doc("ledger", p, cols, rows, totals, checks)
    }

    /** Independent re-aggregation from the database per account. */
    private fun balanceChecks(
        p: UUID,
        exported: Map<String, BigDecimal>,
    ): List<ReconciliationCheck> =
        db
            .sql("select account, amount from ledger_entries where portfolio_id = :p")
            .param("p", p)
            .list { it.str("account") to it.dec("amount") }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (account, amounts) -> check("Account $account matches ledger balance", amounts.total(), exported[account] ?: BigDecimal.ZERO) }

    private fun account(
        p: UUID,
        a: String,
    ): BigDecimal =
        db
            .sql("select amount from ledger_entries where portfolio_id = :p and account = :a")
            .param("p", p)
            .param("a", a)
            .list { it.dec("amount") }
            .total()

    private fun executions(p: UUID): ExportDocument {
        val cols = listOf("executionId", "orderId", "symbol", "side", "quantity", "price", "notional", "commission", "spreadCost", "slippageCost", "realizedPnl", "executedAt")
        val rows =
            db
                .sql(
                    """
                    select e.id, e.order_id, i.symbol, e.side, e.quantity, e.price, e.notional, e.commission, e.spread_cost, e.slippage_cost, e.realized_pnl, e.executed_at
                    from paper_executions e join instruments i on i.id = e.instrument_id where e.portfolio_id = :p order by e.executed_at, e.id
                    """.trimIndent(),
                ).param("p", p)
                .list { r ->
                    listOf<Any?>(
                        r.str("id"),
                        r.str("order_id"),
                        r.str("symbol"),
                        r.str("side"),
                        r.dec("quantity"),
                        r.dec("price"),
                        r.dec("notional"),
                        r.dec("commission"),
                        r.dec("spread_cost"),
                        r.dec("slippage_cost"),
                        r.dec("realized_pnl"),
                        r.instant("executed_at"),
                    )
                }

        fun sum(i: Int) = rows.map { it[i] as BigDecimal }.total()
        val totals = mapOf("notional" to sum(6), "commission" to sum(7), "spreadCost" to sum(8), "slippageCost" to sum(9), "realizedPnl" to sum(10))
        val checks =
            listOf(
                check("Commissions equal the ledger FEES account", account(p, "FEES"), totals.getValue("commission")),
                check("Realized P/L equals the ledger REALIZED_PNL account (sign reversed)", account(p, "REALIZED_PNL").negate(), totals.getValue("realizedPnl")),
            )
        return doc("executions", p, cols, rows, totals, checks)
    }

    private fun orders(p: UUID): ExportDocument {
        val cols = listOf("orderId", "symbol", "side", "orderType", "timeInForce", "quantity", "limitPrice", "stopPrice", "status", "source", "strategyId", "recommendationId", "filledQuantity", "averageFillPrice", "rejectionReason", "createdAt")
        val rows =
            db
                .sql(
                    """
                    select o.id, i.symbol, o.side, o.order_type, o.time_in_force, o.quantity, o.limit_price, o.stop_price, o.status, o.source, o.strategy_id, o.recommendation_id,
                      o.filled_quantity, o.average_fill_price, o.rejection_reason, o.created_at
                    from paper_orders o join instruments i on i.id = o.instrument_id where o.portfolio_id = :p order by o.created_at, o.id
                    """.trimIndent(),
                ).param("p", p)
                .list { r ->
                    listOf<Any?>(
                        r.str("id"),
                        r.str("symbol"),
                        r.str("side"),
                        r.str("order_type"),
                        r.str("time_in_force"),
                        r.dec("quantity"),
                        r.num("limit_price"),
                        r.num("stop_price"),
                        r.str("status"),
                        r.str("source"),
                        r.string("strategy_id"),
                        r.string("recommendation_id"),
                        r.dec("filled_quantity"),
                        r.num("average_fill_price"),
                        r.string("rejection_reason"),
                        r.instant("created_at"),
                    )
                }
        val filled = rows.map { it[12] as BigDecimal }.total()
        val executed =
            db
                .sql("select quantity from paper_executions where portfolio_id = :p")
                .param("p", p)
                .list { it.dec("quantity") }
                .total()
        return doc("orders", p, cols, rows, mapOf("filledQuantity" to filled, "orders" to BigDecimal(rows.size)), listOf(check("Filled quantity equals executed quantity", executed, filled)))
    }

    private fun recommendations(p: UUID): ExportDocument {
        val cols = listOf("recommendationId", "strategyId", "symbol", "status", "side", "quantity", "orderType", "limitPrice", "referencePrice", "expiresAt", "orderId", "createdAt", "decidedAt")
        val rows =
            db
                .sql(
                    """
                    select r.id, r.strategy_id, i.symbol, r.status, r.side, r.quantity, r.order_type, r.limit_price, r.reference_price, r.expires_at, r.order_id, r.created_at, r.decided_at
                    from recommendations r join instruments i on i.id = r.instrument_id where r.portfolio_id = :p order by r.created_at, r.id
                    """.trimIndent(),
                ).param("p", p)
                .list { r ->
                    listOf<Any?>(
                        r.str("id"),
                        r.str("strategy_id"),
                        r.str("symbol"),
                        r.str("status"),
                        r.str("side"),
                        r.dec("quantity"),
                        r.str("order_type"),
                        r.num("limit_price"),
                        r.dec("reference_price"),
                        r.instant("expires_at"),
                        r.string("order_id"),
                        r.instant("created_at"),
                        r.instantOrNull("decided_at"),
                    )
                }
        val withOrders = rows.count { it[10] != null }
        val linked = db.sql("select count(*) from paper_orders where portfolio_id = :p and recommendation_id is not null").param("p", p).long()
        return doc("recommendations", p, cols, rows, mapOf("recommendations" to BigDecimal(rows.size), "withOrders" to BigDecimal(withOrders)), listOf(check("One order per accepted recommendation", BigDecimal(linked), BigDecimal(withOrders))))
    }

    private fun riskEvaluations(p: UUID): ExportDocument {
        val cols = listOf("evaluationId", "source", "decision", "blockingRules", "marketTime", "createdAt", "durationMs")
        val rows =
            db
                .sql("select id, source, decision, blocking_rules, market_time, created_at, duration_ms from risk_evaluations where portfolio_id = :p order by created_at, id")
                .param("p", p)
                .list { r ->
                    val rules =
                        app.strategyforge.engine.common.JacksonCanonical.mapper
                            .readTree(r.str("blocking_rules"))
                            .joinToString(";") { it.asText() }
                    listOf<Any?>(r.str("id"), r.str("source"), r.str("decision"), rules, r.instant("market_time"), r.instant("created_at"), r.long("duration_ms"))
                }
        val blocked = rows.count { it[2] == "BLOCK" }
        return doc("risk-evaluations", p, cols, rows, mapOf("evaluations" to BigDecimal(rows.size), "blocked" to BigDecimal(blocked)), emptyList())
    }

    private fun auditTrail(): ExportDocument {
        val cols = listOf("seq", "eventId", "occurredAt", "actor", "category", "action", "outcome", "entityType", "entityId", "hash")
        val rows =
            db
                .sql("select id, event_id, occurred_at, actor, category, action, outcome, entity_type, entity_id, hash from audit_events order by id")
                .list { r -> listOf<Any?>(r.long("id"), r.str("event_id"), r.instant("occurred_at"), r.str("actor"), r.str("category"), r.str("action"), r.str("outcome"), r.string("entity_type"), r.string("entity_id"), r.str("hash")) }
        return doc("audit", null, cols, rows, mapOf("events" to BigDecimal(rows.size)), emptyList())
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

    /** Records what was exported, with a SHA-256 of the produced file (FR-110). */
    fun recordExport(
        doc: ExportDocument,
        format: String,
        bytes: ByteArray,
    ) {
        audit.record(
            AuditCategory.EXPORT,
            "EXPORT_CREATED",
            if (doc.reconciled) AuditOutcome.SUCCESS else AuditOutcome.FAILURE,
            "Export",
            doc.dataset,
            mapOf("format" to format, "portfolioId" to doc.portfolioId, "rows" to doc.rows.size, "sha256" to Hashing.sha256Hex(bytes), "reconciled" to doc.reconciled),
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
