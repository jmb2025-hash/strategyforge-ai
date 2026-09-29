package app.strategyforge.engine.backtest

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.strategy.StrategyDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class BacktestEngineTest {
    private val start = Instant.parse("2025-01-01T00:00:00Z")

    private fun def(json: String): StrategyDefinition = StrategyDefinition.from(ObjectMapper().readTree(json))

    private val crossStrategy =
        """
        {"schemaVersion":"1.0","metadata":{"name":"x","assetClass":"CRYPTO","timeframe":"1h"},"universe":{"symbols":["BTC-USD"]},
         "dataRequirements":{"minimumHistoryBars":12,"maximumQuoteAgeSeconds":60,"indicators":[{"id":"F","type":"SMA","period":3},{"id":"S","type":"SMA","period":8}]},
         "entryRules":{"operator":"ALL","conditions":[{"left":"F","comparison":"CROSSES_ABOVE","right":"S"}]},
         "exitRules":{"stopLossPercent":5,"takeProfitPercent":10,"maximumHoldingBars":30},
         "positionSizing":{"method":"PERCENT_OF_EQUITY","value":50},"orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},
         "riskLimits":{"maximumOpenPositions":1,"maximumDailyTrades":10,"maximumDailyLossPercent":50,"allowShort":false},"inactivityConditions":[]}
        """.trimIndent()

    private fun walk(
        seed: Int,
        n: Int,
        startPrice: Int = 10000,
    ): List<CandleData> {
        val r = Random(seed)
        var p = BigDecimal(startPrice)
        return (0 until n).map { i ->
            val o = p
            p = p.multiply(BigDecimal.ONE.add(BigDecimal(r.nextInt(-200, 201)).movePointLeft(4))).setScale(2, RoundingMode.HALF_EVEN)
            CandleData(start.plus(Duration.ofHours(i.toLong())), o, o.max(p).add(BigDecimal("5")), o.min(p).subtract(BigDecimal("5")), p, BigDecimal(1000))
        }
    }

    private fun series(bars: List<CandleData>) = listOf(SymbolSeries("BTC-USD", AssetClass.CRYPTO, bars, BigDecimal("0.01"), BigDecimal("0.00000001"), BigDecimal("0.00000001")))

    private val params = BacktestParams(start.plus(Duration.ofHours(12)), start.plus(Duration.ofHours(400)), BigDecimal(100000), CostModel(slippageBps = BigDecimal("5")))

    @Test
    fun `FR-050 MS-08 no look-ahead - changing future bars never changes earlier trades or equity`() {
        val base = walk(1, 400)
        val cut = start.plus(Duration.ofHours(200))
        val a = BacktestEngine(def(crossStrategy), params).run(series(base))
        repeat(5) { seed ->
            val altered = base.map { if (it.openTime >= cut) walk(100 + seed, 400)[base.indexOf(it)] else it }
            val b = BacktestEngine(def(crossStrategy), params).run(series(altered))
            // Decisions at a close are filled at the next open, so trades completed before the cut must match exactly.
            val before = { t: BacktestTrade -> t.exitTime != null && t.exitTime.isBefore(cut.minus(Duration.ofHours(1))) }
            assertThat(b.trades.filter(before)).isEqualTo(a.trades.filter(before))
            assertThat(b.equity.filter { it.t.isBefore(cut) }).isEqualTo(a.equity.filter { it.t.isBefore(cut) })
        }
        assertThat(a.trades).isNotEmpty()
    }

    @Test
    fun `FR-051 fills happen at the next bar open with spread and slippage and results are deterministic`() {
        val bars = walk(7, 400)
        val a = BacktestEngine(def(crossStrategy), params).run(series(bars))
        val b = BacktestEngine(def(crossStrategy), params).run(series(bars))
        assertThat(a).isEqualTo(b)
        val t = a.trades.first()
        val entryBar = bars.first { it.openTime == t.entryTime }
        assertThat(t.entryPrice).isGreaterThan(entryBar.open) // paid half the 0.20% crypto spread plus 5 bps slippage
        assertThat(t.spreadCost.signum()).isPositive()
        assertThat(t.slippageCost.signum()).isPositive()
        // The signal bar precedes the fill bar: entry is never at the bar that produced the signal.
        assertThat(bars.indexOf(entryBar)).isGreaterThan(12)
    }

    @Test
    fun `FR-051 FR-052 stop is assumed before target when a bar touches both and metrics are computed`() {
        val bars = walk(3, 60).toMutableList()
        val out0 = BacktestEngine(def(crossStrategy), params).run(series(bars))
        val first = out0.trades.firstOrNull() ?: return
        val idx = bars.indexOfFirst { it.openTime == first.entryTime }
        val b = bars[idx + 1]
        bars[idx + 1] = b.copy(high = first.entryPrice.multiply(BigDecimal("1.20")), low = first.entryPrice.multiply(BigDecimal("0.80")))
        val out = BacktestEngine(def(crossStrategy), params).run(series(bars))
        assertThat(out.trades.first().exitReason).isEqualTo("STOP_LOSS")
        val m = BacktestMetrics.compute(out, BigDecimal(100000), params.from, params.to, BigDecimal("0.05"))
        assertThat(m.keys).contains("netReturnPercent", "maxDrawdownPercent", "winRatePercent", "profitFactor", "expectancy", "exposurePercent", "turnover", "fees", "slippage", "benchmarkDifferencePercent")
        assertThat(m["annualizedReturnPercent"]).isNull()
        assertThat(m["annualizedReturnNote"]).isNotNull()
    }

    @Test
    fun `FR-053 equity and drawdown series are produced for every bar in the window`() {
        val out = BacktestEngine(def(crossStrategy), params).run(series(walk(9, 400)))
        assertThat(out.equity).hasSize(out.timelineBars)
        assertThat(out.drawdown).hasSize(out.timelineBars)
        assertThat(out.drawdown.all { it.v.signum() >= 0 }).isTrue()
    }
}
