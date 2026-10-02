package app.strategyforge.engine.backtest

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.strategy.StrategyDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.sin

/** D-042 in the backtester: both directions, partial take profit, risk-based sizing and the daily losing-trade cap. */
class BacktestFeaturesTest {
    private val start = Instant.parse("2025-01-06T00:00:00Z")

    private fun def(json: String): StrategyDefinition = StrategyDefinition.from(ObjectMapper().readTree(json))

    /** A smooth swing up and down ([period] bars) so both crossings happen many times. */
    private fun waves(
        n: Int,
        period: Int = 48,
    ): List<CandleData> =
        (0 until n).map { i ->
            val p = { k: Int -> 10000 + 600 * sin(k * 2 * Math.PI / period) }
            val o = BigDecimal(p(i)).setScale(2, RoundingMode.HALF_EVEN)
            val c = BigDecimal(p(i + 1)).setScale(2, RoundingMode.HALF_EVEN)
            CandleData(start.plus(Duration.ofHours(i.toLong())), o, o.max(c).add(BigDecimal(15)), o.min(c).subtract(BigDecimal(15)), c, BigDecimal(1000))
        }

    private fun series(bars: List<CandleData>) = listOf(SymbolSeries("BTC-USD", AssetClass.CRYPTO, bars, BigDecimal("0.01"), BigDecimal("0.00000001"), BigDecimal("0.00000001")))

    private val params = BacktestParams(start.plus(Duration.ofHours(24)), start.plus(Duration.ofHours(24 * 20)), BigDecimal(100000), CostModel())

    private fun strategy(
        sizing: String = """{"method":"RISK_PERCENT","value":1}""",
        partial: String = ""","partialTakeProfit":{"atPercent":1.5,"closePercent":50,"moveStopToEntry":true}""",
        extraRisk: String = "",
        stop: Number = 3,
    ) = """
        {"schemaVersion":"1.0","metadata":{"name":"swing","assetClass":"CRYPTO","timeframe":"1h","direction":"BOTH"},"universe":{"symbols":["BTC-USD"]},
         "dataRequirements":{"minimumHistoryBars":10,"maximumQuoteAgeSeconds":60,"indicators":[{"id":"S","type":"SMA","period":6}]},
         "entryRules":{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"CROSSES_ABOVE","right":"S"}]},
         "shortEntryRules":{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"CROSSES_BELOW","right":"S"}]},
         "exitRules":{"stopLossPercent":$stop,"takeProfitPercent":6,"maximumHoldingBars":40$partial},
         "positionSizing":$sizing,"orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},
         "riskLimits":{"maximumOpenPositions":1,"maximumDailyTrades":50,"maximumDailyLossPercent":90,"allowShort":true$extraRisk},"inactivityConditions":[]}
        """.trimIndent()

    @Test
    fun `a strategy that trades both directions opens longs and shorts and short profits rise as price falls`() {
        val out = BacktestEngine(def(strategy(partial = "")), params).run(series(waves(24 * 21)))
        val sides = out.trades.map { it.side }.toSet()
        assertThat(sides).containsExactlyInAnyOrder("LONG", "SHORT")
        out.trades.filter { it.side == "SHORT" && it.exitPrice != null }.forEach { t ->
            val gross = t.entryPrice.subtract(t.exitPrice).multiply(t.quantity)
            assertThat(t.grossPnl.toDouble()).isCloseTo(gross.toDouble(), within(0.02))
        }
        // Equity is consistent: ending cash plus open value equals the last equity point.
        assertThat(out.endingCash.add(out.openPositionsValue).toDouble()).isCloseTo(
            out.equity
                .last()
                .v
                .toDouble(),
            within(0.05),
        )
    }

    @Test
    fun `a partial take profit closes half, the rest exits later, and the quantities add up`() {
        val out = BacktestEngine(def(strategy()), params).run(series(waves(24 * 21)))
        val partials = out.trades.filter { it.exitReason == "PARTIAL_TAKE_PROFIT" }
        assertThat(partials).isNotEmpty()
        partials.forEach { p ->
            val rest = out.trades.filter { it.entryTime == p.entryTime && it.exitReason != "PARTIAL_TAKE_PROFIT" }
            assertThat(rest).`as`("the remainder of the position").hasSize(1)
            val total = p.quantity.add(rest.single().quantity)
            assertThat(p.quantity.toDouble()).isCloseTo(total.toDouble() / 2, within(1e-7))
            // The partial is taken at a 1.5% gain.
            val gain = if (p.side == "LONG") p.exitPrice!!.divide(p.entryPrice, 8, RoundingMode.HALF_EVEN) else p.entryPrice.divide(p.exitPrice!!, 8, RoundingMode.HALF_EVEN)
            assertThat(gain.toDouble()).isGreaterThanOrEqualTo(1.0149)
            // After the partial, the stop sits at the entry price, so the rest never loses beyond costs.
            if (rest.single().exitReason == "BREAKEVEN_STOP") assertThat(rest.single().grossPnl.toDouble()).isGreaterThan(-0.01 * total.toDouble() * p.entryPrice.toDouble())
        }
    }

    @Test
    fun `risk-percent sizing loses about the chosen share of equity when the stop is hit`() {
        val out = BacktestEngine(def(strategy(partial = "")), params).run(series(waves(24 * 21)))
        val first = out.trades.first()
        // 1% of 100,000 at risk with a 3% stop: notional about 33,333.
        assertThat(first.quantity.multiply(first.entryPrice).toDouble()).isCloseTo(33333.0, within(400.0))
        val capped = BacktestEngine(def(strategy(extraRisk = ""","maximumPositionPercent":10""", partial = "")), params).run(series(waves(24 * 21)))
        assertThat(
            capped.trades
                .first()
                .quantity
                .multiply(capped.trades.first().entryPrice)
                .toDouble(),
        ).`as`("capped at 10% of equity").isLessThanOrEqualTo(10_100.0)
    }

    @Test
    fun `after the daily cap of losing trades no new trades open that day`() {
        // A tight stop loses often on these swings.
        val tight = def(strategy(partial = "", stop = 0.2, extraRisk = ""","maximumDailyLosingTrades":1"""))
        val loose = def(strategy(partial = "", stop = 0.2))
        val capped = BacktestEngine(tight, params).run(series(waves(24 * 21, period = 10)))
        val uncapped = BacktestEngine(loose, params).run(series(waves(24 * 21, period = 10)))

        fun lossesPerDay(out: BacktestOutput) =
            out.trades
                .filter { it.exitTime != null && it.netPnl.signum() < 0 }
                .groupBy { it.exitTime!!.atZone(MarketCalendar.NEW_YORK).toLocalDate() }
                .mapValues { it.value.size }
        assertThat(lossesPerDay(uncapped).values.max()).`as`("without a cap several losses happen in one day").isGreaterThan(1)
        assertThat(lossesPerDay(capped).values).allSatisfy { assertThat(it).isLessThanOrEqualTo(1) }
    }
}
