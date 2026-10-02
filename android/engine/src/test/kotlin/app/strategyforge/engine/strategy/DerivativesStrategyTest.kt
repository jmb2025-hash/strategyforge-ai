package app.strategyforge.engine.strategy

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.backtest.BacktestRequest
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * D-044 end to end in demo mode: strategies using open interest, funding and CVD validate, explain
 * themselves, backtest with futures data lined up to the bars, and trade live on paper.
 */
class DerivativesStrategyTest {
    private val e = TestEngine.create()

    private fun strategy(
        assetClass: String = "CRYPTO",
        symbol: String = "BTC-USD",
    ) = """
        {"schemaVersion":"1.0",
         "metadata":{"name":"Flow and positioning","assetClass":"$assetClass","timeframe":"1h","createdBy":"IMPORTED","direction":"BOTH"},
         "universe":{"symbols":["$symbol"]},
         "dataRequirements":{"minimumHistoryBars":30,"maximumQuoteAgeSeconds":120,"indicators":[
           {"id":"OI","type":"OPEN_INTEREST","period":6},
           {"id":"FUND","type":"FUNDING_RATE"},
           {"id":"FLOW","type":"CVD","period":4},
           {"id":"LOW20","type":"LOWEST","period":20},
           {"id":"HIGH20","type":"HIGHEST","period":20}]},
         "entryRules":{"operator":"ALL","conditions":[
           {"left":"FLOW.value","comparison":"GT","right":0},
           {"left":"OI.change","comparison":"GT","right":0},
           {"left":"FUND.annualized","comparison":"LT","right":40}]},
         "shortEntryRules":{"operator":"ALL","conditions":[
           {"left":"FLOW.value","comparison":"LT","right":0},
           {"left":"OI.change","comparison":"GT","right":0},
           {"left":"FUND.value","comparison":"GT","right":-0.01}]},
         "exitRules":{"stopLossPercent":2,"takeProfitPercent":4,"maximumHoldingBars":12,
           "conditions":{"operator":"ANY","conditions":[{"left":"FLOW.delta","comparison":"LT","right":0}]},
           "shortConditions":{"operator":"ANY","conditions":[{"left":"FLOW.delta","comparison":"GT","right":0}]}},
         "positionSizing":{"method":"RISK_PERCENT","value":1},
         "orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},
         "riskLimits":{"maximumOpenPositions":1,"maximumDailyTrades":20,"maximumDailyLossPercent":5,"maximumDrawdownPercent":20,"allowShort":true},
         "inactivityConditions":["STALE_MARKET_DATA","MISSING_HISTORY","PROVIDER_UNAVAILABLE"]}
        """.trimIndent()

    @Test
    fun `futures indicators validate for crypto, are refused for stocks and are explained`() {
        val r = e.strategies.create(JacksonCanonical.mapper.readTree(strategy()))
        assertThat(r.strategy.status).`as`(r.validation.issues.toString()).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(r.explanation)
            .contains("the cumulative futures taker delta (CVD) of the last 4 bars")
            .contains("the % change in futures open interest over 6 bars")
            .contains("the annualised futures funding rate (%)")
            .contains("this bar's futures taker delta")
        val stock = e.strategies.create(JacksonCanonical.mapper.readTree(strategy("US_EQUITY", "AAPL")))
        assertThat(stock.validation.issues.map { it.code }).contains("DERIVATIVES_CRYPTO_ONLY")
    }

    @Test
    fun `it backtests with futures data lined up to the bars and trades both ways`() {
        val r = e.strategies.create(JacksonCanonical.mapper.readTree(strategy()))
        val b = e.backtests.submit(BacktestRequest(r.strategy.id, from = Instant.parse("2026-05-01T00:00:00Z"), to = Instant.parse("2026-06-22T00:00:00Z"), startingCapital = BigDecimal("100000")))
        assertThat(b.status).`as`(b.error ?: "").isEqualTo("COMPLETED")
        assertThat(b.integrity.toString()).doesNotContain("FUTURES_DATA")
        assertThat(b.dataset.toString()).contains("REPLAY_SYNTHETIC")
        val trades = e.backtests.trades(b.id)
        println("Futures-context backtest: ${trades.size} trades, sides ${trades.groupingBy { it["side"] }.eachCount()}, exits ${trades.groupingBy { it["exitReason"] }.eachCount()}")
        assertThat(trades.map { it["side"] }.toSet()).contains("LONG", "SHORT")
    }

    @Test
    fun `a live paper strategy reads the futures data each bar and opens positions`() {
        val p = e.portfolio(balance = "100000")
        val s = Strategies.alwaysLong("Funding gate", "1m", quantity = "0.1", maxHoldingBars = 500, stopLossPercent = 20, takeProfitPercent = 50)

        @Suppress("UNCHECKED_CAST")
        val data = s["dataRequirements"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val content =
            s +
                ("dataRequirements" to data + ("indicators" to (data["indicators"] as List<Any?>) + mapOf("id" to "FUND", "type" to "FUNDING_RATE") + mapOf("id" to "OI", "type" to "OPEN_INTEREST", "period" to 2))) +
                ("entryRules" to mapOf("operator" to "ALL", "conditions" to listOf(mapOf("left" to "FUND.value", "comparison" to "GT", "right" to -100), mapOf("left" to "OI.value", "comparison" to "GT", "right" to 0))))
        val id = e.eligible(content)
        e.activate(id, p, ActivationMode.AUTONOMOUS)
        e.advance(3)
        val buys = e.orders.list(p, null, 50).filter { it.side == OrderSide.BUY }
        val evals =
            e.db
                .sql("select status, detail from strategy_evaluations where strategy_id = :s")
                .param("s", id)
                .list { it.string("status") + " " + it.string("detail") }
        assertThat(buys).`as`(evals.toString()).isNotEmpty()
        assertThat(buys.first().status).isEqualTo(OrderStatus.FILLED)
    }
}
