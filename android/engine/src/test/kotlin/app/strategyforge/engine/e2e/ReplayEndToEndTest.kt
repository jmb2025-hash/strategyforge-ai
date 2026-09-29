package app.strategyforge.engine.e2e

import app.strategyforge.engine.Engine
import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.signals.AcceptRequest
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.Strategies.recs
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * RG-07 on the phone engine: the replay end-to-end scenario runs twice, each from a clean
 * database, and must produce identical outcomes (manual orders, an accepted recommendation and
 * autonomous trading with protective exits). Generated ids and wall-clock times are excluded;
 * market (replay) timestamps are included.
 */
class ReplayEndToEndTest {
    @Test
    fun `RG-07 NFR-012 replay end-to-end suite passes twice from clean databases with identical results`() {
        val first = run()
        val second = run()
        assertThat(first).`as`("the scenario produces trading activity").anyMatch { it.startsWith("execution|") }
        assertThat(first.filter { it.startsWith("signal|") }).hasSizeGreaterThan(3)
        assertThat(first).contains("recommendation|E2E Recommendation|ACCEPTED")
        assertThat(first).anyMatch { it.startsWith("order|E2E Manual|MANUAL|FILLED") }
        assertThat(first).anyMatch { it.startsWith("signal|E2E Autonomous|ETH-USD|") && (it.endsWith("[\"STOP_LOSS\"]") || it.endsWith("[\"TAKE_PROFIT\"]")) }
        assertThat(second).isEqualTo(first)
    }

    private fun run(): List<String> {
        val e = TestEngine.create()
        scenario(e)
        return digest(e)
    }

    private fun scenario(e: Engine) {
        val manual = e.portfolio(name = "E2E Manual", costModel = CostModel(commissionPerOrder = BigDecimal("1.00")))
        val rec = e.portfolio(name = "E2E Recommendations")
        val auto = e.portfolio(name = "E2E Autonomous")

        // Manual trading.
        e.order(manual, "BTC-USD", "BUY", "0.05")
        e.order(manual, "ETH-USD", "BUY", "1")
        e.advance(2)
        e.order(manual, "BTC-USD", "SELL", "0.02")

        // Recommendation Mode: one recommendation is accepted.
        val recStrategy = e.eligible(Strategies.alwaysLong("E2E Recommendation", "1m", symbol = "SOL-USD", quantity = "2"))
        e.activate(recStrategy, rec)
        e.advance(1)
        e.recommendations.accept(e.recs(recStrategy, "PENDING").single().id, AcceptRequest())

        // Autonomous Mode with tight protective exits.
        val autoStrategy =
            e.eligible(Strategies.alwaysLong("E2E Autonomous", "1m", symbol = "ETH-USD", quantity = "0.5", maxHoldingBars = 500, stopLossPercent = BigDecimal("0.05"), takeProfitPercent = BigDecimal("0.05")))
        e.activate(autoStrategy, auto, ActivationMode.AUTONOMOUS)
        e.advance(20)
    }

    private fun digest(e: Engine): List<String> {
        fun rows(
            sql: String,
            vararg cols: String,
        ): List<String> = e.db.sql(sql).list { r: Row -> cols.joinToString("|") { c -> norm(r.string(c)) } }
        return buildList {
            addAll(rows("select 'market' k, market_time from replay_state", "k", "market_time"))
            addAll(
                rows(
                    """
                    select 'execution' k, p.name, i.symbol, e.side, e.fill_seq, e.quantity, e.price, e.reference_price, e.commission, e.spread_cost, e.slippage_cost, e.realized_pnl
                    from paper_executions e join portfolios p on p.id = e.portfolio_id join instruments i on i.id = e.instrument_id
                    """.trimIndent(),
                    "k",
                    "name",
                    "symbol",
                    "side",
                    "fill_seq",
                    "quantity",
                    "price",
                    "reference_price",
                    "commission",
                    "spread_cost",
                    "slippage_cost",
                    "realized_pnl",
                ),
            )
            addAll(
                rows(
                    """
                    select 'signal' k, st.name, i.symbol, s.bucket_start, s.action, s.side, s.quantity, s.reference_price, s.disposition, s.triggered_rules
                    from signals s join strategies st on st.id = s.strategy_id join instruments i on i.id = s.instrument_id
                    """.trimIndent(),
                    "k",
                    "name",
                    "symbol",
                    "bucket_start",
                    "action",
                    "side",
                    "quantity",
                    "reference_price",
                    "disposition",
                    "triggered_rules",
                ),
            )
            addAll(rows("select 'recommendation' k, st.name, r.status from recommendations r join strategies st on st.id = r.strategy_id", "k", "name", "status"))
            addAll(rows("select 'order' k, p.name, o.source, o.status, o.side, o.quantity, o.filled_quantity from paper_orders o join portfolios p on p.id = o.portfolio_id", "k", "name", "source", "status", "side", "quantity", "filled_quantity"))
            addAll(rows("select 'ledger' k, p.name, l.account, l.amount, l.quantity from ledger_entries l join portfolios p on p.id = l.portfolio_id", "k", "name", "account", "amount", "quantity"))
            addAll(rows("select 'risk' k, decision from risk_evaluations", "k", "decision"))
            addAll(rows("select 'backtest' k, st.name, b.status, b.result_status, b.metrics from backtests b join strategies st on st.id = b.strategy_id", "k", "name", "status", "result_status", "metrics"))
            addAll(rows("select 'strategy' k, name, status from strategies", "k", "name", "status"))
        }.sorted()
    }

    private fun norm(v: String?): String = v?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: v ?: "null"
}
