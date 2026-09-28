package app.strategyforge.reports

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.portfolio.PortfolioSummary
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
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
    val from: Instant?,
    val to: Instant?,
    val equity: List<SeriesPoint>,
    val drawdown: List<SeriesPoint>,
    val maxDrawdownPercent: BigDecimal,
    val periodReturnPercent: BigDecimal?,
    val costs: CostBreakdown,
    val byAsset: List<Attribution>,
    val byStrategy: List<Attribution>,
    val benchmarks: List<Benchmark>,
    val closedTrades: List<Map<String, Any?>>,
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
    val declinedHypothetical: List<Map<String, Any?>>,
    val disclaimer: String,
)

/**
 * Reporting (FR-103, FR-104). Every figure is derived from the append-only ledger, executions,
 * lots and recorded decisions; comparisons are descriptive associations, never causal claims.
 */
@Service
class ReportService(
    private val jdbc: JdbcClient,
    private val portfolios: PortfolioService,
) {
    fun portfolio(
        id: UUID,
        from: Instant?,
        to: Instant?,
    ): PortfolioReport {
        val summary = portfolios.summary(id)
        val equity =
            jdbc
                .sql(
                    "select at, equity from portfolio_equity_snapshots where portfolio_id = :p and (cast(:f as timestamptz) is null or at >= :f) and (cast(:t as timestamptz) is null or at <= :t) order by at, id",
                ).param("p", id)
                .param("f", ts(from))
                .param("t", ts(to))
                .query { rs, _ -> SeriesPoint(rs.instant("at"), rs.getBigDecimal("equity")) }
                .list()
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
        return PortfolioReport(
            summary,
            from,
            to,
            equity,
            drawdown,
            drawdown.maxOfOrNull { it.value } ?: BigDecimal.ZERO,
            periodReturn,
            costs(id),
            byAsset(id, summary),
            byStrategy(id),
            listOf(benchmark("SPY", from ?: equity.firstOrNull()?.at, to ?: equity.lastOrNull()?.at), benchmark("BTC-USD", from ?: equity.firstOrNull()?.at, to ?: equity.lastOrNull()?.at)),
            closedTrades(id),
            DISCLAIMER,
        )
    }

    fun costs(id: UUID): CostBreakdown {
        val (commission, spread, slippage) =
            jdbc
                .sql("select coalesce(sum(commission),0) c, coalesce(sum(spread_cost),0) s, coalesce(sum(slippage_cost),0) sl from paper_executions where portfolio_id = :p")
                .param("p", id)
                .query { rs, _ -> Triple(rs.getBigDecimal("c"), rs.getBigDecimal("s"), rs.getBigDecimal("sl")) }
                .single()
        val borrow =
            jdbc
                .sql("select coalesce(sum(amount),0) from borrow_accruals where portfolio_id = :p")
                .param("p", id)
                .query(BigDecimal::class.java)
                .single()
        // DIVIDENDS is a credit account: a negative balance means dividends received, positive means paid in lieu.
        val dividends =
            jdbc
                .sql("select coalesce(sum(amount),0) from ledger_entries where portfolio_id = :p and account = 'DIVIDENDS'")
                .param("p", id)
                .query(BigDecimal::class.java)
                .single()
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
        id: UUID,
        summary: PortfolioSummary,
    ): List<Attribution> {
        val realized =
            jdbc
                .sql(
                    """
                    select i.id, i.symbol, coalesce(sum(e.realized_pnl),0) r, count(*) filter (where e.side in ('SELL','BUY_TO_COVER')) n
                    from paper_executions e join instruments i on i.id = e.instrument_id where e.portfolio_id = :p group by i.id, i.symbol order by i.symbol
                    """.trimIndent(),
                ).param("p", id)
                .query { rs, _ -> Triple(rs.uuid("id"), rs.getString("symbol"), rs.getBigDecimal("r") to rs.getInt("n")) }
                .list()
        val unrealized = summary.positions.associate { it.instrumentId to it.unrealizedPnl }
        val keys = (realized.map { it.first } + unrealized.keys).distinct()
        return keys.map { k ->
            val r = realized.firstOrNull { it.first == k }
            val symbol = r?.second ?: summary.positions.first { it.instrumentId == k }.symbol
            Attribution(k.toString(), symbol, Decimals.money(r?.third?.first ?: BigDecimal.ZERO), unrealized[k], r?.third?.second ?: 0)
        }
    }

    private fun byStrategy(id: UUID): List<Attribution> =
        jdbc
            .sql(
                """
                select o.strategy_id, coalesce(s.name, 'Manual and emergency orders') name, coalesce(sum(e.realized_pnl),0) r,
                  count(*) filter (where e.side in ('SELL','BUY_TO_COVER')) n
                from paper_executions e join paper_orders o on o.id = e.order_id left join strategies s on s.id = o.strategy_id
                where e.portfolio_id = :p group by o.strategy_id, s.name order by name
                """.trimIndent(),
            ).param("p", id)
            .query { rs, _ -> Attribution(rs.uuidOrNull("strategy_id")?.toString() ?: "MANUAL", rs.getString("name"), Decimals.money(rs.getBigDecimal("r")), null, rs.getInt("n")) }
            .list()

    fun benchmark(
        symbol: String,
        from: Instant?,
        to: Instant?,
    ): Benchmark {
        if (from == null || to == null) return Benchmark(symbol, null, null, null, "No reporting window yet")

        fun close(sql: String) =
            jdbc
                .sql(sql)
                .param("s", symbol)
                .param("t", ts(if (sql.contains("<=")) to else from))
                .query(BigDecimal::class.java)
                .optional()
                .orElse(null)
        val start = close("select c.close from candles c join instruments i on i.id = c.instrument_id where i.symbol = :s and c.timeframe = '1d' and c.open_time >= cast(:t as timestamptz) - interval '1 day' order by c.open_time limit 1")
        val end = close("select c.close from candles c join instruments i on i.id = c.instrument_id where i.symbol = :s and c.timeframe = '1d' and c.open_time <= :t order by c.open_time desc limit 1")
        if (start == null || end == null) return Benchmark(symbol, start, end, null, "Benchmark data unavailable for the window (not stored locally)")
        return Benchmark(symbol, start, end, Decimals.percent(end.subtract(start).multiply(Decimals.HUNDRED).divide(start, Decimals.MC)), null)
    }

    /** Closing executions: each realizes P/L against FIFO lots (fees shown separately). */
    private fun closedTrades(id: UUID): List<Map<String, Any?>> =
        jdbc
            .sql(
                """
                select e.id, e.order_id, i.symbol, e.side, e.quantity, e.price, e.realized_pnl, e.commission, e.spread_cost, e.slippage_cost, e.executed_at
                from paper_executions e join instruments i on i.id = e.instrument_id
                where e.portfolio_id = :p and e.side in ('SELL', 'BUY_TO_COVER') order by e.executed_at desc, e.fill_seq desc limit 500
                """.trimIndent(),
            ).param("p", id)
            .query { rs, _ ->
                mapOf(
                    "executionId" to rs.uuid("id"),
                    "orderId" to rs.uuid("order_id"),
                    "symbol" to rs.getString("symbol"),
                    "side" to rs.getString("side"),
                    "quantity" to rs.getBigDecimal("quantity"),
                    "price" to rs.getBigDecimal("price"),
                    "realizedPnl" to rs.getBigDecimal("realized_pnl"),
                    "commission" to rs.getBigDecimal("commission"),
                    "spreadCost" to rs.getBigDecimal("spread_cost"),
                    "slippageCost" to rs.getBigDecimal("slippage_cost"),
                    "executedAt" to rs.instant("executed_at"),
                )
            }.list()

    /**
     * FR-104: outcomes grouped by order source and by recommendation decision. Declined and expired
     * recommendations show a hypothetical price move from the reference price to the latest quote,
     * excluding costs; the comparison is descriptive and does not imply causation.
     */
    fun outcomes(portfolioId: UUID): OutcomeComparison {
        portfolios.get(portfolioId)
        val bySource =
            jdbc
                .sql(
                    """
                    select o.source, count(distinct o.id) n, coalesce(sum(e.realized_pnl),0) r
                    from paper_orders o left join paper_executions e on e.order_id = o.id
                    where o.portfolio_id = :p and o.status in ('FILLED','PARTIALLY_FILLED') group by o.source order by o.source
                    """.trimIndent(),
                ).param("p", portfolioId)
                .query { rs, _ -> group(rs.getString("source"), rs.getInt("n"), rs.getBigDecimal("r"), "Realized P/L of filled orders from this source; open positions are not included.") }
                .list()
        val byDecision =
            jdbc
                .sql(
                    """
                    select r.status, count(distinct r.id) n, coalesce(sum(e.realized_pnl),0) pnl
                    from recommendations r left join paper_executions e on e.order_id = r.order_id
                    where r.portfolio_id = :p group by r.status order by r.status
                    """.trimIndent(),
                ).param("p", portfolioId)
                .query { rs, _ -> group(rs.getString("status"), rs.getInt("n"), rs.getBigDecimal("pnl"), "Realized P/L of orders created from recommendations with this outcome.") }
                .list()
        val hypothetical =
            jdbc
                .sql(
                    """
                    select r.id, i.symbol, r.status, r.side, r.quantity, r.reference_price, q.last
                    from recommendations r join instruments i on i.id = r.instrument_id left join latest_quotes q on q.instrument_id = r.instrument_id
                    where r.portfolio_id = :p and r.status in ('DECLINED','EXPIRED') order by r.created_at desc limit 200
                    """.trimIndent(),
                ).param("p", portfolioId)
                .query { rs, _ ->
                    val ref = rs.getBigDecimal("reference_price")
                    val last = rs.getBigDecimal("last")
                    val buys = rs.getString("side") in setOf("BUY", "BUY_TO_COVER")
                    val move = last?.subtract(ref)?.multiply(rs.getBigDecimal("quantity"))?.let { if (buys) it else it.negate() }
                    mapOf(
                        "recommendationId" to rs.uuid("id"),
                        "symbol" to rs.getString("symbol"),
                        "status" to rs.getString("status"),
                        "side" to rs.getString("side"),
                        "referencePrice" to ref,
                        "latestPrice" to last,
                        "hypotheticalMoveUsd" to move?.let { Decimals.money(it) },
                    )
                }.list()
        return OutcomeComparison(portfolioId, bySource, byDecision, hypothetical, COMPARISON_DISCLAIMER)
    }

    private fun group(
        name: String,
        n: Int,
        pnl: BigDecimal,
        note: String,
    ) = OutcomeGroup(name, n, Decimals.money(pnl), if (n > 0) Decimals.money(pnl.divide(BigDecimal(n), Decimals.MC)) else null, note)

    /** Risk report: decisions and the rules that blocked them (FR-103 risk). */
    fun risk(portfolioId: UUID): Map<String, Any?> {
        portfolios.get(portfolioId)
        val decisions =
            jdbc
                .sql("select decision, count(*) n from risk_evaluations where portfolio_id = :p group by decision")
                .param("p", portfolioId)
                .query { rs, _ -> rs.getString("decision") to rs.getInt("n") }
                .list()
                .toMap()
        val rules =
            jdbc
                .sql("select rule, count(*) n from risk_evaluations, unnest(blocking_rules) rule where portfolio_id = :p group by rule order by n desc, rule")
                .param("p", portfolioId)
                .query { rs, _ -> mapOf("rule" to rs.getString("rule"), "blocks" to rs.getInt("n")) }
                .list()
        val bySource =
            jdbc
                .sql("select source, decision, count(*) n from risk_evaluations where portfolio_id = :p group by source, decision order by source, decision")
                .param("p", portfolioId)
                .query { rs, _ -> mapOf("source" to rs.getString("source"), "decision" to rs.getString("decision"), "count" to rs.getInt("n")) }
                .list()
        return mapOf("portfolioId" to portfolioId, "decisions" to decisions, "blockingRules" to rules, "bySource" to bySource)
    }

    /** Strategy report: signals, recommendation outcomes, orders, realized P/L, backtests and AI provenance. */
    fun strategy(strategyId: UUID): Map<String, Any?> {
        val name =
            jdbc
                .sql("select name from strategies where id = :s")
                .param("s", strategyId)
                .query(String::class.java)
                .optional()
                .orElseThrow { Problems.notFound("Strategy", strategyId) }

        fun counts(sql: String) =
            jdbc
                .sql(sql)
                .param("s", strategyId)
                .query { rs, _ -> rs.getString(1) to rs.getInt(2) }
                .list()
                .toMap()
        val realized =
            jdbc
                .sql("select coalesce(sum(e.realized_pnl),0) from paper_executions e join paper_orders o on o.id = e.order_id where o.strategy_id = :s")
                .param("s", strategyId)
                .query(BigDecimal::class.java)
                .single()
        val backtests =
            jdbc
                .sql("select id, status, result_status, metrics, completed_at from backtests where strategy_id = :s order by created_at desc limit 10")
                .param("s", strategyId)
                .query { rs, _ -> mapOf("id" to rs.uuid("id"), "status" to rs.getString("status"), "resultStatus" to rs.getString("result_status"), "metrics" to rs.getString("metrics")) }
                .list()
        val ai =
            jdbc
                .sql(
                    """
                    select c.id, c.status, c.content_hash, s.provider_type, s.model, s.id session_id, c.created_at
                    from strategy_compilations c join research_sessions s on s.id = c.session_id where c.strategy_id = :s order by c.created_at
                    """.trimIndent(),
                ).param("s", strategyId)
                .query { rs, _ ->
                    mapOf(
                        "compilationId" to rs.uuid("id"),
                        "status" to rs.getString("status"),
                        "contentHash" to rs.getString("content_hash"),
                        "provider" to rs.getString("provider_type"),
                        "model" to rs.getString("model"),
                        "researchSessionId" to rs.uuid("session_id"),
                        "compiledAt" to rs.instant("created_at"),
                    )
                }.list()
        return mapOf(
            "strategyId" to strategyId,
            "name" to name,
            "signals" to counts("select disposition, count(*) from signals where strategy_id = :s group by disposition"),
            "recommendations" to counts("select status, count(*) from recommendations where strategy_id = :s group by status"),
            "orders" to counts("select source || ':' || status, count(*) from paper_orders where strategy_id = :s group by source, status"),
            "realizedPnl" to Decimals.money(realized),
            "backtests" to backtests,
            "aiProvenance" to ai,
            "disclaimer" to DISCLAIMER,
        )
    }

    /** AI provenance report (FR-103, section 13): compilations with sources and subsequent paper results. */
    fun aiProvenance(): List<Map<String, Any?>> =
        jdbc
            .sql(
                """
                select c.id, c.status, c.content_hash, c.strategy_id, c.version_id, c.created_at, s.id session_id, s.title, s.provider_type, s.model, s.review_status,
                  (select coalesce(sum(coalesce(r.estimated_cost_usd, r.reserved_cost_usd)),0) from research_runs r where r.session_id = s.id) cost,
                  (select count(*) from research_sources rs join research_runs r on r.id = rs.run_id where r.session_id = s.id) sources,
                  (select count(*) from research_edits e where e.session_id = s.id) edits,
                  (select coalesce(sum(e.realized_pnl),0) from paper_executions e join paper_orders o on o.id = e.order_id where o.strategy_id = c.strategy_id) pnl
                from strategy_compilations c join research_sessions s on s.id = c.session_id order by c.created_at desc limit 500
                """.trimIndent(),
            ).query { rs, _ ->
                mapOf(
                    "compilationId" to rs.uuid("id"),
                    "status" to rs.getString("status"),
                    "contentHash" to rs.getString("content_hash"),
                    "strategyId" to rs.uuidOrNull("strategy_id"),
                    "versionId" to rs.uuidOrNull("version_id"),
                    "compiledAt" to rs.instant("created_at"),
                    "researchSessionId" to rs.uuid("session_id"),
                    "title" to rs.getString("title"),
                    "provider" to rs.getString("provider_type"),
                    "model" to rs.getString("model"),
                    "reviewStatus" to rs.getString("review_status"),
                    "aiCostUsd" to rs.getBigDecimal("cost").setScale(6, RoundingMode.HALF_EVEN),
                    "sources" to rs.getInt("sources"),
                    "ownerEdits" to rs.getInt("edits"),
                    "subsequentRealizedPnl" to Decimals.money(rs.getBigDecimal("pnl")),
                    "note" to "Subsequent results are simulated and are not evidence that the model caused them.",
                )
            }.list()

    companion object {
        const val DISCLAIMER = "Simulated paper-trading results based on replay or provider data, simulated fills, fees and slippage. Not real money; past results do not predict future results."
        const val COMPARISON_DISCLAIMER =
            "Descriptive comparison only. Differences between groups are associations and do not show that accepting, declining or automating caused them. " +
                "Hypothetical moves for declined or expired recommendations use the latest quote, ignore costs and assume the full quantity."
    }
}

@RestController
@RequestMapping("/v1/reports")
@Tag(name = "Reports")
class ReportController(
    private val reports: ReportService,
) {
    @GetMapping("/portfolios/{id}")
    fun portfolio(
        @PathVariable id: String,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ) = reports.portfolio(parseUuid(id), from, to)

    @GetMapping("/portfolios/{id}/outcomes")
    fun outcomes(
        @PathVariable id: String,
    ) = reports.outcomes(parseUuid(id))

    @GetMapping("/portfolios/{id}/risk")
    fun risk(
        @PathVariable id: String,
    ) = reports.risk(parseUuid(id))

    @GetMapping("/strategies/{id}")
    fun strategy(
        @PathVariable id: String,
    ) = reports.strategy(parseUuid(id))

    @GetMapping("/ai-provenance")
    fun aiProvenance() = reports.aiProvenance()
}
