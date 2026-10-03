package app.strategyforge.engine.backtest

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.strategy.StrategyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * D-047 chart-level trade management on hand-built price paths: a stop below the signal candle's
 * wick, targets at multiples of the risk with the stop then moved to entry, the farthest-stop and
 * reward-to-risk filters, swing trailing, and sizing from the actual stop distance.
 */
class ChartLevelExitsTest {
    private val t0 = Instant.parse("2026-01-05T00:00:00Z")
    private val zero = CostModel(slippageBps = BigDecimal.ZERO, cryptoFallbackSpreadPercent = BigDecimal.ZERO, participationRatePercent = BigDecimal(100))

    /** Bars at 100 until [signal], where a candle wicks to 98 and closes at 101 (the entry signal); then [after]. */
    private fun path(after: List<Triple<Number, Number, Number>>): List<CandleData> {
        val flat = (0 until 30).map { i -> c(i, 100, 100.4, 99.6, 100) }
        val sig = c(30, 100, 101.2, 98, 101)
        // Each (low, high, close) bar opens at the previous close.
        var prev = BigDecimal(101)
        val rest =
            after.mapIndexed { k, (l, h, cl) ->
                val bar = CandleData(t0.plus(Duration.ofHours(31L + k)), prev, BigDecimal(h.toString()), BigDecimal(l.toString()), BigDecimal(cl.toString()), BigDecimal(1_000_000))
                prev = bar.close
                bar
            }
        return flat + sig + rest
    }

    private fun c(
        i: Int,
        o: Number,
        h: Number,
        l: Number,
        cl: Number,
    ) = CandleData(t0.plus(Duration.ofHours(i.toLong())), BigDecimal(o.toString()), BigDecimal(h.toString()), BigDecimal(l.toString()), BigDecimal(cl.toString()), BigDecimal(1_000_000))

    private fun def(exit: String) =
        StrategyDefinition.from(
            JacksonCanonical.mapper.readTree(
                """
                {"schemaVersion":"1.0","metadata":{"name":"x","assetClass":"CRYPTO","timeframe":"1h"},"universe":{"symbols":["BTC-USD"]},
                 "dataRequirements":{"minimumHistoryBars":5,"maximumQuoteAgeSeconds":60,"indicators":[]},
                 "entryRules":{"operator":"ALL","conditions":[{"left":"LOW","comparison":"LT","right":99},{"left":"CLOSE","comparison":"GT","right":100.5}]},
                 "exitRules":$exit,
                 "positionSizing":{"method":"RISK_PERCENT","value":1},"orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},
                 "riskLimits":{"maximumOpenPositions":1,"maximumDailyTrades":10,"maximumDailyLossPercent":50,"maximumPositionPercent":100,"allowShort":false},"inactivityConditions":[]}
                """.trimIndent(),
            ),
        )

    private fun run(
        exit: String,
        after: List<Triple<Number, Number, Number>>,
    ): BacktestOutput {
        val bars = path(after)
        return BacktestEngine(def(exit), BacktestParams(t0.plus(Duration.ofHours(10)), bars.last().openTime.plusSeconds(3600), BigDecimal(100000), zero))
            .run(listOf(SymbolSeries("BTC-USD", AssetClass.CRYPTO, bars, BigDecimal("0.01"), BigDecimal("0.0001"), BigDecimal("0.0001"))))
    }

    private val wick = """"stop":{"at":"SIGNAL_WICK","bufferPercent":0.5}"""

    @Test
    fun `the stop sits below the signal wick and a stop-out loses one percent of equity`() {
        // Stop = 98 - 0.5% = 97.51; entry fills at the next open, 101. Then price falls through the stop.
        val out = run("""{"stopLossPercent":5,"takeProfitPercent":20,"maximumHoldingBars":50,$wick}""", listOf(Triple(100, 101.5, 100.5), Triple(97, 100.5, 97.2)))
        val t = out.trades.single()
        assertThat(t.exitReason).isEqualTo("STOP_LOSS")
        assertThat(t.exitPrice!!.toDouble()).isCloseTo(97.51, within(0.01))
        // Sized from the signal: risk 1000 over (101 - 97.51) per unit.
        assertThat(t.quantity.toDouble()).isCloseTo(1000 / (101 - 97.51), within(0.01))
        assertThat(t.netPnl.toDouble()).isCloseTo(-1000.0, within(5.0))
    }

    @Test
    fun `targets at multiples of the risk take part off, then the stop moves to entry`() {
        // Risk 101 - 97.51 = 3.49: 1R = 104.49 (half off), 2R = 107.98 (the rest).
        val exit = """{"stopLossPercent":5,"takeProfitPercent":20,"maximumHoldingBars":50,$wick,"targets":[{"rMultiple":1,"closePercent":50},{"rMultiple":2,"closePercent":50}],"breakevenAfterTarget":1}"""
        val win = run(exit, listOf(Triple(100.5, 103, 102.5), Triple(102, 104.8, 104), Triple(103.5, 108.5, 108)))
        assertThat(win.trades.map { it.exitReason }).containsExactly("TARGET_1", "TARGET_2")
        assertThat(win.trades[0].exitPrice!!.toDouble()).isCloseTo(104.49, within(0.01))
        assertThat(win.trades[1].exitPrice!!.toDouble()).isCloseTo(107.98, within(0.01))
        assertThat(win.trades[0].quantity.toDouble()).isCloseTo(win.trades[1].quantity.toDouble(), within(0.0002))
        val scratch = run(exit, listOf(Triple(100.5, 103, 102.5), Triple(102, 104.8, 104), Triple(100.5, 104, 100.8)))
        assertThat(scratch.trades.map { it.exitReason }).containsExactly("TARGET_1", "BREAKEVEN_STOP")
        assertThat(scratch.trades[1].exitPrice!!.toDouble()).isCloseTo(101.0, within(0.01))
    }

    @Test
    fun `a stop farther than allowed, a wrong-side target or too little reward means no trade`() {
        val up = listOf(Triple(100.5, 103, 102.5), Triple(102, 110, 109))
        // The wick stop is 3.5% away; at most 2% is allowed.
        assertThat(run("""{"stopLossPercent":2,"takeProfitPercent":20,"maximumHoldingBars":50,$wick}""", up).trades).isEmpty()
        // A target level below the entry for a long.
        assertThat(run("""{"stopLossPercent":5,"takeProfitPercent":20,"maximumHoldingBars":50,$wick,"targets":[{"at":"LOW","closePercent":100}]}""", up).trades).isEmpty()
        // The first target pays 1R; at least 2R is required.
        assertThat(run("""{"stopLossPercent":5,"takeProfitPercent":20,"maximumHoldingBars":50,$wick,"targets":[{"rMultiple":1,"closePercent":100}],"minimumRewardRisk":2}""", up).trades).isEmpty()
        assertThat(run("""{"stopLossPercent":5,"takeProfitPercent":20,"maximumHoldingBars":50,$wick,"targets":[{"rMultiple":1,"closePercent":100}],"minimumRewardRisk":1}""", up).trades).hasSize(1)
    }

    @Test
    fun `the trailing stop follows each new confirmed swing low`() {
        // Up, a dip to 103 (swing low confirmed after two higher bars), up again, then a fall through 103.
        val path =
            listOf(
                Triple(101, 104, 103.5),
                Triple(103.2, 106, 105),
                Triple(104, 105.5, 104.2),
                Triple(103, 104.5, 104),
                Triple(104, 107, 106.5),
                Triple(106, 108, 107.5),
                Triple(102, 107.5, 102.5),
            )
        val out = run("""{"stopLossPercent":5,"takeProfitPercent":20,"maximumHoldingBars":50,$wick,"trailing":{"swingPeriod":2,"afterTarget":0}}""", path)
        val t = out.trades.single()
        assertThat(t.exitReason).isEqualTo("TRAILING_STOP")
        assertThat(t.exitPrice!!.toDouble()).isCloseTo(103.0, within(0.01))
        assertThat(t.netPnl.signum()).isPositive()
    }
}
